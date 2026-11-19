package com.agrocti.cti;

import com.agrocti.config.Config;
import com.agrocti.data.Dataset;
import com.agrocti.data.FeatureExtractor;
import com.agrocti.data.Scaler;
import com.agrocti.exception.InvalidReadingException;
import com.agrocti.ml.QuantizedAutoencoder;
import com.agrocti.model.ActuatorEvent;
import com.agrocti.model.Severity;
import com.agrocti.model.ThreatIndicator;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.agrocti.config.Config.*;

/**
 * The proposed Agro-CTI framework. For every node and step:
 *   on-device stage  - int8 autoencoder score of the feature window;
 *   context stage    - physical range check, rain / trusted-irrigation explanations, unknown command sources;
 *   CTI stage        - external feed (malicious IPs, regional advisories) and regional correlation.
 * The stages can be switched off one by one for the ablation study.
 */
public class AgroCtiPipeline {

    /** Which stages are switched on. */
    public static final class Options {
        public final boolean context, regional, feed;
        public final String label;

        public Options(String label, boolean context, boolean regional, boolean feed) {
            this.label = label;
            this.context = context;
            this.regional = regional;
            this.feed = feed;
        }

        public static Options full() {
            return new Options("Proposed TinyML Agro-CTI (full)", true, true, true);
        }
    }

    private final QuantizedAutoencoder model;
    private final FeatureExtractor extractor;
    private final Scaler scaler;
    private final PhysicsGuard guard;
    private final ThreatFeed feed;
    private final CtiCorrelator correlator = new CtiCorrelator();
    private final Options opt;
    private final double tau, tauLow;
    private final boolean[] hostileValve = new boolean[NODES];
    private final boolean[] unknownValve = new boolean[NODES];

    public AgroCtiPipeline(QuantizedAutoencoder model, FeatureExtractor extractor, Scaler scaler, PhysicsGuard guard,
                           ThreatFeed feed, double tau, double tauLow, Options opt) {
        this.model = model;
        this.extractor = extractor;
        this.scaler = scaler;
        this.guard = guard;
        this.feed = feed;
        this.tau = tau;
        this.tauLow = tauLow;
        this.opt = opt;
    }

    public CtiCorrelator getCorrelator() {
        return correlator;
    }

    public double getTau() { return tau; }
    public double getTauLow() { return tauLow; }
    public Options getOptions() { return opt; }

    /** Processes every node at step t. Steps must be given in increasing order. */
    public List<Decision> processStep(Dataset ds, int t) {
        List<Decision> out = new ArrayList<>(ds.nodes);
        boolean[] explained = new boolean[ds.nodes];
        // ---- stage 1 + 2: local score and local context, for all nodes
        for (int n = 0; n < ds.nodes; n++) {
            double[] z = scaler.transform(extractor.extract(ds, n, t));
            double[] err = model.errorVector(z);
            double s = 0;
            for (double e : err) s += e;
            s /= err.length;
            Decision d = new Decision(n, t, s);
            if (s > tau) d.raise(Severity.ALERT, String.format("autoencoder score %.2f above threshold %.2f", s, tau));
            else if (s > tauLow) d.raise(Severity.SUSPECT, String.format("autoencoder score %.2f above suspicion level %.2f", s, tauLow));

            if (opt.context) {
                try {
                    PhysicsGuard.checkRange(ds.values[n][t]);
                } catch (InvalidReadingException e) {
                    d.raise(Severity.CRITICAL, "impossible reading: " + e.getMessage());
                }
                if (d.severity == Severity.ALERT || d.severity == Severity.SUSPECT) {
                    if (guard.explainedByWeather(ds, n, t, err)) {
                        explained[n] = true;
                    } else if (guard.explainedByTrustedIrrigation(ds, n, t)) {
                        explained[n] = true;
                    }
                }
            }
            // actuator commands: who is opening the valve?
            for (ActuatorEvent e : ds.eventsAt(n, t)) {
                if ("CLOSE".equals(e.getCommand())) {
                    hostileValve[n] = false;
                    unknownValve[n] = false;
                    continue;
                }
                if (opt.feed) {
                    Optional<ThreatIndicator> hit = feed.lookupIp(e.getSourceIp());
                    if (hit.isPresent()) {
                        hostileValve[n] = true;
                        d.raise(Severity.CRITICAL, "command from " + e.getSourceIp() + " listed in threat feed ("
                                + hit.get().getId() + ", confidence " + hit.get().getConfidence() + ")");
                    }
                }
                if (opt.context && !guard.isTrusted(e.getSourceIp())) unknownValve[n] = true;
            }
            boolean flowing = ds.values[n][t][FLOW] > 0.5;
            if (!flowing) {
                hostileValve[n] = false;
                unknownValve[n] = false;
            }
            if (hostileValve[n] && d.severity != Severity.CRITICAL) {
                d.raise(Severity.CRITICAL, "valve still open after a command from a feed-listed address");
            }
            if (explained[n] && d.severity.ordinal() <= Severity.ALERT.ordinal()) {
                d.severity = Severity.INFO;
                d.reasons.add("explained by " + (guard.extremeRainRecently(ds, n, t) ? "extreme rainfall in the last 24 h"
                        : PhysicsGuard.sumOver(ds, n, t, RAIN, PhysicsGuard.RAIN_LOOKBACK) >= 1 ? "rainfall" : "trusted irrigation")
                        + " (soil water balance is consistent)");
            }
            if (unknownValve[n] && flowing && !hostileValve[n]) {
                if (d.score > tauLow) d.raise(Severity.ALERT, "valve opened by an address that is not a registered controller");
                else d.raise(Severity.SUSPECT, "valve opened by an address that is not a registered controller");
            }
            if (d.severity == Severity.SUSPECT || d.severity.isAlarm()) correlator.registerSuspect(n, t);
            out.add(d);
        }
        // ---- stage 3: regional correlation and advisories escalate suspects
        for (int r = 0; r < REGIONS.length; r++) {
            boolean campaign = opt.regional && correlator.regionalCampaign(r, t);
            List<ThreatIndicator> adv = opt.feed ? feed.activeAdvisories(r, t) : java.util.Collections.emptyList();
            for (int f = 0; f < FARMS_PER_REGION; f++) {
                Decision d = out.get(r * FARMS_PER_REGION + f);
                if (d.severity != Severity.SUSPECT) continue;
                if (campaign) {
                    d.raise(Severity.ALERT, correlator.suspiciousFarms(r, t).size() + " farms in " + REGIONS[r]
                            + " suspicious within 2 h (regional campaign)");
                    d.guess = "Regional coordinated spoofing";
                } else if (!adv.isEmpty()) {
                    d.raise(Severity.ALERT, "matches active advisory " + adv.get(0).getId() + " for " + REGIONS[r]
                            + " (" + adv.get(0).getValue() + ")");
                }
            }
        }
        for (Decision d : out) if (d.guess.isEmpty() && (d.isAlarm() || d.severity == Severity.SUSPECT)) d.guess = guess(ds, d);
        return out;
    }

    private static double sum(Dataset ds, int n, int t, int f) {
        double s = 0;
        for (int k = 0; k < WINDOW; k++) s += ds.values[n][t - k][f];
        return s;
    }

    /** A simple rule-based guess of the kind of attack, shown to the farmer. */
    private String guess(Dataset ds, Decision d) {
        int n = d.node, t = d.step;
        for (String r : d.reasons) if (r.contains("address") || r.contains("threat feed")) return "Actuator command injection";
        if (ds.values[n][t][CMD] >= 5) return "Actuator command injection";
        double[] sm = new double[WINDOW];
        for (int k = 0; k < WINDOW; k++) sm[k] = ds.values[n][t - WINDOW + 1 + k][SM];
        double mean = 0;
        for (double v : sm) mean += v;
        mean /= WINDOW;
        double var = 0;
        for (double v : sm) var += (v - mean) * (v - mean);
        if (Math.sqrt(var / WINDOW) < 0.02) return "Stuck-at / constant spoofing";
        double prev = ds.values[n][t - 1][SM];
        if (Math.abs(ds.values[n][t][SM] - prev) > 6 || Math.abs(ds.values[n][t][ST] - ds.values[n][t - 1][ST]) > 4) {
            return "Spike injection";
        }
        if (guard.residualSum(ds, n, t) > 2 * guard.getWindowSumStd()) return "Drift / bias injection";
        return "Sensor integrity anomaly (possible replay)";
    }
}
