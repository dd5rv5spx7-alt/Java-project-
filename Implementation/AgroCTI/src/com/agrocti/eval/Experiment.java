package com.agrocti.eval;

import com.agrocti.cti.AgroCtiPipeline;
import com.agrocti.cti.Decision;
import com.agrocti.data.Dataset;
import com.agrocti.data.FeatureExtractor;
import com.agrocti.ml.CnnLstmDetector;
import com.agrocti.ml.IsolationForest;
import com.agrocti.ml.Metrics;
import com.agrocti.ml.QuantizedAutoencoder;
import com.agrocti.ml.TinyAutoencoder;
import com.agrocti.model.AttackEpisode;
import com.agrocti.model.AttackType;
import com.agrocti.model.ContextEvent;
import com.agrocti.model.ThreatIndicator;
import com.agrocti.util.Json;
import com.agrocti.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.agrocti.config.Config.*;

/**
 * Trains the three detectors on clean data, picks every threshold on the clean
 * validation days (no attack labels are used), and measures everything on the test days.
 */
public class Experiment {

    /** Threshold = this quantile of the clean validation scores. */
    public static final double ALERT_QUANTILE = 0.995;
    public static final double SUSPECT_QUANTILE = 0.97;

    private final Workspace ws;
    private final long seed;
    public TinyAutoencoder autoencoder;
    public QuantizedAutoencoder quantized;
    public double tau, tauLow;
    public final List<ModelResult> results = new ArrayList<>();
    public List<ThreatIndicator> sightings = new ArrayList<>();
    public final List<double[]> lossCurves = new ArrayList<>();

    public Experiment(Workspace ws, long seed) {
        this.ws = ws;
        this.seed = seed;
    }

    /** Scores for each test reading; index [node][t - VAL_END]. */
    private interface Scorer {
        double score(int node, int t);
    }

    public void run() {
        Dataset ds = ws.ds;
        ws.fitPreprocessing();
        Log.info(String.format(Locale.ROOT, "Water-balance model: dSM = %.3f*rain + %.3f*flow + %.3f*evap + %.3f  (residual sd %.3f)",
                ws.moisture.getCoefficients()[0], ws.moisture.getCoefficients()[1], ws.moisture.getCoefficients()[2],
                ws.moisture.getCoefficients()[3], ws.moisture.getResidualStd()));

        List<double[]> trainF = ws.features(LONG_WINDOW, TRAIN_END, 1);
        Log.info("Training samples: " + trainF.size() + " feature vectors of " + FeatureExtractor.DIM + " values");

        // ---------------- Isolation Forest
        Log.info("Training Isolation Forest (100 trees, 256 samples each)");
        IsolationForest iforest = new IsolationForest(100, 256, seed);
        iforest.fit(trainF);
        ModelResult rIf = evaluateScores(iforest.name(), "baseline", (n, t) -> iforest.score(ws.feature(n, t)));
        rIf.parameters = iforest.parameterCount();
        rIf.memoryBytes = iforest.memoryBytes();
        rIf.latencyMicros = timeIt(x -> iforest.score(x));

        // ---------------- CNN-LSTM
        Log.info("Training CNN-LSTM (conv 16 filters, LSTM 32 units) on raw reading sequences");
        CnnLstmDetector cnn = new CnnLstmDetector(SEQ_LEN, NF, 16, 32, 6, 32, 0.003, seed);
        cnn.fit(ws.sequences(SEQ_LEN, TRAIN_END, 2));
        lossCurves.add(cnn.getLossHistory().stream().mapToDouble(Double::doubleValue).toArray());
        // per-step forecast errors, averaged over the same 2-hour window the other detectors see
        double[][] stepErr = new double[NODES][TOTAL_STEPS];
        for (int n = 0; n < NODES; n++) for (int t = TRAIN_END - WINDOW; t < TOTAL_STEPS; t++) stepErr[n][t] = cnn.score(ws.sequence(n, t));
        ModelResult rCnn = evaluateScores(cnn.name(), "baseline", (n, t) -> {
            double s = 0;
            for (int k = 0; k < WINDOW; k++) s += stepErr[n][t - k];
            return s / WINDOW;
        });
        rCnn.parameters = cnn.parameterCount();
        rCnn.memoryBytes = cnn.memoryBytes();
        rCnn.latencyMicros = timeSeq(cnn);

        // ---------------- TinyML autoencoder, float and int8
        autoencoder = new TinyAutoencoder(FeatureExtractor.DIM, seed);
        Log.info("Training TinyML autoencoder " + java.util.Arrays.toString(autoencoder.getSizes()).replaceAll("[\\[\\] ]", "").replace(',', '-'));
        autoencoder.fit(trainF);
        lossCurves.add(0, autoencoder.getLossHistory().stream().mapToDouble(Double::doubleValue).toArray());
        ModelResult rAe = evaluateScores(autoencoder.name(), "ablation", (n, t) -> autoencoder.score(ws.feature(n, t)));
        rAe.parameters = autoencoder.parameterCount();
        rAe.memoryBytes = autoencoder.memoryBytes();
        rAe.latencyMicros = timeIt(x -> autoencoder.score(x));

        quantized = new QuantizedAutoencoder(autoencoder, ws.features(LONG_WINDOW, TRAIN_END, 7));
        double[] val = valScores((n, t) -> quantized.score(ws.feature(n, t)));
        tau = Metrics.quantile(val, ALERT_QUANTILE);
        tauLow = Metrics.quantile(val, SUSPECT_QUANTILE);
        Log.info(String.format(Locale.ROOT, "int8 thresholds from clean validation days: alert %.3f, suspect %.3f", tau, tauLow));

        // ---------------- pipeline variants (ablation) and the full proposed framework
        AgroCtiPipeline.Options[] variants = {
                new AgroCtiPipeline.Options("TinyML int8 autoencoder only", false, false, false),
                new AgroCtiPipeline.Options("+ physical / agronomic context", true, false, false),
                new AgroCtiPipeline.Options("+ regional correlation", true, true, false),
                AgroCtiPipeline.Options.full(),
        };
        List<ModelResult> pipes = new ArrayList<>();
        for (AgroCtiPipeline.Options o : variants) {
            AgroCtiPipeline p = new AgroCtiPipeline(quantized, ws.extractor, ws.featureScaler, ws.guard, ws.feed, tau, tauLow, o);
            ModelResult r = evaluatePipeline(p, o.label, o.feed ? "proposed" : "ablation");
            r.parameters = quantized.parameterCount();
            r.memoryBytes = deviceFootprint();
            r.threshold = tau;
            pipes.add(r);
            if (o.feed) sightings = p.getCorrelator().getSightings();
        }
        results.add(rIf);
        results.add(rCnn);
        results.add(rAe);
        results.addAll(pipes);
        for (ModelResult r : results) Log.info(String.format("%-36s %s", r.name, r.metrics));
    }

    /** Bytes needed on the microcontroller: int8 model, scaler, water-balance model and the reading window. */
    public long deviceFootprint() {
        return quantized.memoryBytes() + 2L * FeatureExtractor.DIM * 4 + 5 * 4 + (long) WINDOW * NF * 4;
    }

    private double[] valScores(Scorer s) {
        List<Double> v = new ArrayList<>();
        for (int n = 0; n < NODES; n++) for (int t = TRAIN_END; t < VAL_END; t++) v.add(s.score(n, t));
        return v.stream().mapToDouble(Double::doubleValue).toArray();
    }

    private ModelResult evaluateScores(String name, String kind, Scorer s) {
        double thr = Metrics.quantile(valScores(s), ALERT_QUANTILE);
        boolean[][] pred = new boolean[NODES][TOTAL_STEPS - VAL_END];
        int m = NODES * (TOTAL_STEPS - VAL_END), i = 0;
        double[] all = new double[m];
        boolean[] lab = new boolean[m];
        for (int n = 0; n < NODES; n++) {
            for (int t = VAL_END; t < TOTAL_STEPS; t++) {
                double v = s.score(n, t);
                pred[n][t - VAL_END] = v > thr;
                all[i] = v;
                lab[i++] = windowLabel(ws.ds, n, t).isAttack();
            }
        }
        ModelResult r = score(name, kind, pred);
        r.threshold = thr;
        r.oracleF1 = bestF1(all, lab);
        return r;
    }

    /** Highest F1 over every possible threshold (uses the labels; reported only as an upper bound). */
    static double bestF1(double[] scores, boolean[] labels) {
        Integer[] idx = new Integer[scores.length];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (a, b) -> Double.compare(scores[b], scores[a]));
        long pos = 0;
        for (boolean l : labels) if (l) pos++;
        long tp = 0, fp = 0;
        double best = 0;
        for (int k = 0; k < idx.length; k++) {
            if (labels[idx[k]]) tp++; else fp++;
            if (k + 1 < idx.length && scores[idx[k + 1]] == scores[idx[k]]) continue;
            double f1 = 2.0 * tp / (tp + fp + pos);
            best = Math.max(best, f1);
        }
        return best;
    }

    private ModelResult evaluatePipeline(AgroCtiPipeline p, String name, String kind) {
        boolean[][] pred = new boolean[NODES][TOTAL_STEPS - VAL_END];
        long t0 = System.nanoTime();
        for (int t = VAL_END - STEPS_PER_DAY; t < TOTAL_STEPS; t++) {     // one clean day to warm up the state
            List<Decision> ds = p.processStep(ws.ds, t);
            if (t < VAL_END) continue;
            for (Decision d : ds) pred[d.node][t - VAL_END] = d.isAlarm();
        }
        double micros = (System.nanoTime() - t0) / 1000.0 / ((long) NODES * (TOTAL_STEPS - VAL_END + STEPS_PER_DAY));
        ModelResult r = score(name, kind, pred);
        r.latencyMicros = micros;
        return r;
    }

    private ModelResult score(String name, String kind, boolean[][] pred) {
        Dataset ds = ws.ds;
        ModelResult r = new ModelResult(name, kind);
        for (int n = 0; n < NODES; n++) {
            for (int t = VAL_END; t < TOTAL_STEPS; t++) {
                boolean p = pred[n][t - VAL_END];
                AttackType a = windowLabel(ds, n, t);
                r.metrics.add(p, a.isAttack());
                if (a.isAttack()) {
                    long[] c = r.perType.get(a);
                    c[1]++;
                    if (p) c[0]++;
                } else if (ds.context[n][t] == ContextEvent.HEAVY_STORM) {
                    r.stormTotal++;
                    if (p) r.stormFp++;
                } else if (ds.context[n][t] == ContextEvent.MANUAL_IRRIGATION) {
                    r.manualTotal++;
                    if (p) r.manualFp++;
                }
            }
        }
        for (AttackEpisode ep : ds.getEpisodes()) {
            long[] e = r.episodes.get(ep.getType());
            e[1]++;
            int first = -1, firstLabel = -1;
            for (int t = ep.getStart(); t < Math.min(TOTAL_STEPS, ep.getEnd() + WINDOW - 1); t++) {
                if (firstLabel < 0 && ds.labels[ep.getNode()][t].isAttack()) firstLabel = t;
                if (firstLabel >= 0 && pred[ep.getNode()][t - VAL_END]) { first = t; break; }
            }
            if (first >= 0) {
                e[0]++;
                e[2] += first - firstLabel;
            }
        }
        return r;
    }

    /**
     * Ground truth of the window that ends at step t: the attack type of the most recent
     * manipulated reading in the last WINDOW steps, or NONE. Every detector sees the whole window.
     */
    public static AttackType windowLabel(Dataset ds, int n, int t) {
        for (int k = 0; k < WINDOW; k++) {
            if (ds.labels[n][t - k].isAttack()) return ds.labels[n][t - k];
        }
        return AttackType.NONE;
    }

    private interface VecScore { double score(double[] x); }

    private double timeIt(VecScore f) {
        List<double[]> xs = ws.features(VAL_END, VAL_END + 200, 1);
        double sink = 0;
        for (double[] x : xs) sink += f.score(x);                 // warm-up for the JIT compiler
        long t0 = System.nanoTime();
        for (int rep = 0; rep < 3; rep++) for (double[] x : xs) sink += f.score(x);
        long dt = System.nanoTime() - t0;
        if (sink == 42) Log.info("");
        return dt / 1000.0 / (3.0 * xs.size());
    }

    private double timeSeq(CnnLstmDetector cnn) {
        List<double[][]> xs = ws.sequences(VAL_END, VAL_END + 200, 1);
        double sink = 0;
        for (double[][] x : xs) sink += cnn.score(x);
        long t0 = System.nanoTime();
        for (int rep = 0; rep < 3; rep++) for (double[][] x : xs) sink += cnn.score(x);
        long dt = System.nanoTime() - t0;
        if (sink == 42) Log.info("");
        return dt / 1000.0 / (3.0 * xs.size());
    }

    public String toJson() {
        long attackReadings = 0, testReadings = (long) NODES * (TOTAL_STEPS - VAL_END);
        for (int n = 0; n < NODES; n++) for (int t = VAL_END; t < TOTAL_STEPS; t++) if (windowLabel(ws.ds, n, t).isAttack()) attackReadings++;
        StringBuilder curves = new StringBuilder("[");
        for (int i = 0; i < lossCurves.size(); i++) curves.append(i > 0 ? "," : "").append(Json.numbers(lossCurves.get(i)));
        curves.append(']');
        return Json.object().put("seed", seed).put("nodes", NODES).put("testReadings", testReadings)
                .put("attackReadings", attackReadings).put("episodes", ws.ds.getEpisodes().size())
                .put("tau", tau).put("tauLow", tauLow).put("deviceFootprintBytes", deviceFootprint())
                .raw("models", Json.array(results)).raw("sightings", Json.array(sightings))
                .raw("lossCurves", curves.toString()).build();
    }
}
