package com.agrocti.server;

import com.agrocti.config.Config;
import com.agrocti.cti.AgroCtiPipeline;
import com.agrocti.cti.Decision;
import com.agrocti.data.Dataset;
import com.agrocti.eval.Experiment;
import com.agrocti.model.Alert;
import com.agrocti.module.AgroCTI_Incident;
import com.agrocti.module.AgroCTI_ThreatIndicator;
import com.agrocti.model.AttackType;
import com.agrocti.model.Severity;
import com.agrocti.model.ThreatIndicator;
import com.agrocti.util.Json;
import com.agrocti.util.Log;
import com.agrocti.util.RingBuffer;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static com.agrocti.config.Config.*;

/**
 * Replays the test days as if the readings were arriving live. The clock thread runs the
 * pipeline one 15-minute step at a time; a second thread writes incidents to MySQL through
 * a BlockingQueue (producer-consumer). The HTTP threads read the state through synchronized methods.
 */
public class LiveSimulator implements Runnable {

    /** One point of the chart of a farm. */
    public static final class Point {
        final int step;
        final double sm, flow, score;
        final Severity sev;
        final boolean truth;

        Point(int step, double sm, double flow, double score, Severity sev, boolean truth) {
            this.step = step;
            this.sm = sm;
            this.flow = flow;
            this.score = score;
            this.sev = sev;
            this.truth = truth;
        }
    }

    private static final Alert POISON = new Alert(-1, -1, Severity.NORMAL, 0, "", "");
    private static final int GAP = 4;   // an alarm within 1 hour continues the same incident

    private final Dataset ds;
    private final AgroCtiPipeline pipeline;
    private final AgroCTI_Incident incidents;
    private final AgroCTI_ThreatIndicator indicators;
    private final BlockingQueue<Alert> writeQueue = new LinkedBlockingQueue<>();
    private final List<RingBuffer<Point>> series = new ArrayList<>();
    private final Decision[] last = new Decision[NODES];
    private final Alert[] open = new Alert[NODES];
    private final Map<Severity, Integer> readingCounts = new EnumMap<>(Severity.class);
    private final Set<String> savedSightings = new HashSet<>();
    private final Thread clock;
    private final Thread writer;
    private volatile int delayMs;
    private boolean paused = false;
    private int step;
    private long tp, fp, fn, tn;

    public LiveSimulator(Dataset ds, AgroCtiPipeline pipeline, int delayMs, AgroCTI_Incident incidents,
                         AgroCTI_ThreatIndicator indicators) throws SQLException {
        this.ds = ds;
        this.incidents = incidents;
        this.indicators = indicators;
        this.pipeline = pipeline;
        this.delayMs = delayMs;
        for (int n = 0; n < NODES; n++) series.add(new RingBuffer<>(STEPS_PER_DAY * 2));
        incidents.deleteAll();
        indicators.deleteLocal();
        clock = new Thread(this, "sim-clock");
        clock.setDaemon(true);
        writer = new Thread(this::writeLoop, "incident-writer");
        writer.setDaemon(true);
        writer.setPriority(Thread.MIN_PRIORITY);
    }

    public void start() {
        writer.start();
        clock.start();
    }

    @Override
    public void run() {
        // warm-up: one clean day before the test period, not shown
        for (step = VAL_END - STEPS_PER_DAY; step < VAL_END; step++) pipeline.processStep(ds, step);
        Log.info("Live replay of " + TEST_DAYS + " test days started (" + delayMs + " ms per 15-minute step)");
        try {
            for (int t = VAL_END; t < TOTAL_STEPS; t++) {
                waitIfPaused();
                tick(t);
                Thread.sleep(delayMs);
            }
            Log.info("Replay finished: all test days processed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            writeQueue.add(POISON);
        }
    }

    private synchronized void waitIfPaused() throws InterruptedException {
        while (paused) wait();
    }

    public synchronized void setPaused(boolean p) {
        paused = p;
        notifyAll();
    }

    public void setDelay(int ms) {
        delayMs = Math.max(20, Math.min(5000, ms));
    }

    public int getDelay() {
        return delayMs;
    }

    /** Current step of the replay (used as the time of incidents reported by hand). */
    public synchronized int getStep() {
        return Math.max(step, VAL_END);
    }

    private void tick(int t) {
        List<Decision> ds2 = pipeline.processStep(ds, t);
        synchronized (this) {
            step = t;
            for (Decision d : ds2) {
                int n = d.node;
                last[n] = d;
                readingCounts.merge(d.severity, 1, Integer::sum);
                boolean truth = Experiment.windowLabel(ds, n, t).isAttack();
                if (d.isAlarm() && truth) tp++;
                else if (d.isAlarm()) fp++;
                else if (truth) fn++;
                else tn++;
                series.get(n).add(new Point(t, ds.values[n][t][SM], ds.values[n][t][FLOW], d.score, d.severity, truth));
                if (d.isAlarm()) {
                    String reasons = String.join("; ", d.reasons);
                    if (open[n] != null && t - open[n].getEndStep() <= GAP) {
                        synchronized (open[n]) {               // the writer thread may be saving it
                            open[n].extend(t, d.severity, d.score, reasons);
                            open[n].setTruth(truthText(n, t));
                        }
                    } else {
                        open[n] = new Alert(n, t, d.severity, d.score, d.guess, reasons);
                        open[n].setTruth(truthText(n, t));
                    }
                    writeQueue.add(open[n]);
                }
            }
        }
        for (ThreatIndicator s : pipeline.getCorrelator().getSightings()) {
            if (savedSightings.add(s.getId())) {
                try {
                    indicators.saveSighting(s);
                    Log.info("Shared new local indicator " + s.getId() + ": " + s.getValue() + " in " + REGIONS[s.getRegion()]);
                } catch (SQLException e) {
                    Log.warn("could not save indicator: " + e.getMessage());
                }
            }
        }
    }

    private String truthText(int n, int t) {
        AttackType a = Experiment.windowLabel(ds, n, t);
        return a.isAttack() ? a.label() : "no attack (false alarm)";
    }

    /** Consumer thread: saves new incidents and updates running ones. */
    private void writeLoop() {
        try {
            while (true) {
                Alert a = writeQueue.take();
                if (a == POISON) break;
                synchronized (a) {
                    try {
                        incidents.save(a);      // insert the first time, update afterwards
                    } catch (SQLException e) {
                        Log.warn("could not save incident: " + e.getMessage());
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ JSON for the dashboard
    public synchronized String stateJson() {
        StringBuilder nodes = new StringBuilder("[");
        for (int n = 0; n < NODES; n++) {
            Decision d = last[n];
            if (n > 0) nodes.append(',');
            nodes.append(Json.object().put("id", n).put("name", nodeName(n)).put("region", REGIONS[regionOf(n)])
                    .put("severity", d == null ? "NORMAL" : d.severity.name())
                    .put("score", d == null ? 0 : d.score)
                    .put("guess", d == null ? "" : d.guess)
                    .put("truth", d != null && Experiment.windowLabel(ds, n, d.step).isAttack()).build());
        }
        nodes.append(']');
        StringBuilder counts = new StringBuilder("{");
        for (Severity s : Severity.values()) {
            if (counts.length() > 1) counts.append(',');
            counts.append(Json.quote(s.name())).append(':').append(readingCounts.getOrDefault(s, 0));
        }
        counts.append('}');
        int done = Math.max(0, step - VAL_END + 1);
        return Json.object().put("step", step).put("time", Config.timeOf(Math.max(step, VAL_END)).toString().replace('T', ' '))
                .put("progress", (double) done / (TOTAL_STEPS - VAL_END)).put("paused", paused).put("delayMs", delayMs)
                .put("tau", pipeline.getTau()).put("tauLow", pipeline.getTauLow())
                .put("tp", tp).put("fp", fp).put("fn", fn).put("tn", tn)
                .raw("counts", counts.toString()).raw("nodes", nodes.toString()).build();
    }

    public String seriesJson(int node) {
        List<Point> pts = series.get(node).latest(STEPS_PER_DAY * 2);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < pts.size(); i++) {
            Point p = pts.get(i);
            if (i > 0) sb.append(',');
            sb.append('[').append(p.step).append(',').append(Json.num(Math.round(p.sm * 100) / 100.0)).append(',')
              .append(Json.num(Math.round(p.flow * 10) / 10.0)).append(',')
              .append(Json.num(Math.round(p.score * 1000) / 1000.0)).append(',')
              .append(p.sev.ordinal()).append(',').append(p.truth ? 1 : 0).append(']');
        }
        return sb.append(']').toString();
    }
}
