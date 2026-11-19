package com.agrocti.cti;

import com.agrocti.config.Config;
import com.agrocti.data.Dataset;
import com.agrocti.data.MoistureModel;
import com.agrocti.exception.InvalidReadingException;
import com.agrocti.model.ActuatorEvent;

import java.util.Set;

import static com.agrocti.config.Config.*;

/**
 * Agronomic and physical context checks:
 *  1. impossible values (range check),
 *  2. is an unusual window explained by rain? (soil cannot gain more water than it receives);
 *     after rain heavier than anything seen in training, the soil stays unusual for a day,
 *  3. is it explained by irrigation ordered from a trusted controller?
 */
public class PhysicsGuard {

    private static final double[] MIN = {0, -10, -20, 0, 0, 0, 0, 0};
    private static final double[] MAX = {100, 70, 60, 100, 200, 20, 100, 1000};

    private final MoistureModel model;
    private final Set<String> trusted;
    private double windowSumStd = 1;
    private double maxDailyRain = Double.MAX_VALUE;

    public PhysicsGuard(MoistureModel model, Set<String> trusted) {
        this.model = model;
        this.trusted = trusted;
    }

    /** Learns how much the water-balance residual normally adds up to over one window. */
    public void fit(Dataset ds, int from, int to) {
        double ss = 0;
        long n = 0;
        for (int node = 0; node < ds.nodes; node++) {
            for (int t = Math.max(from, WINDOW); t < to; t++) {
                double s = residualSum(ds, node, t);
                ss += s * s;
                n++;
            }
        }
        windowSumStd = Math.sqrt(ss / n);
        maxDailyRain = 0;
        for (int node = 0; node < ds.nodes; node++) {
            for (int t = Math.max(from, STEPS_PER_DAY); t < to; t++) {
                maxDailyRain = Math.max(maxDailyRain, sumOver(ds, node, t, RAIN, STEPS_PER_DAY));
            }
        }
    }

    public double getMaxDailyRain() {
        return maxDailyRain;
    }

    public void setMaxDailyRain(double v) {
        maxDailyRain = v;
    }

    /** True if the last 24 hours brought more rain than any day of the training period. */
    public boolean extremeRainRecently(Dataset ds, int node, int t) {
        return sumOver(ds, node, t, RAIN, STEPS_PER_DAY) > maxDailyRain;
    }

    public void setWindowSumStd(double v) {
        windowSumStd = v;
    }

    public double getWindowSumStd() {
        return windowSumStd;
    }

    public static void checkRange(double[] v) throws InvalidReadingException {
        for (int f = 0; f < NF; f++) {
            if (Double.isNaN(v[f]) || v[f] < MIN[f] || v[f] > MAX[f]) {
                throw new InvalidReadingException(f, String.format("%s = %.2f %s is outside the physical range %.0f..%.0f",
                        FEATURES[f], v[f], UNITS[f], MIN[f], MAX[f]));
            }
        }
    }

    public double residualSum(Dataset ds, int node, int t) {
        double s = 0;
        for (int k = 0; k < WINDOW; k++) s += model.residual(ds, node, t - k);
        return s;
    }

    /** Largest gain of water that the water balance cannot explain, in standard deviations. */
    public double maxPositiveResidual(Dataset ds, int node, int t) {
        double m = 0;
        for (int k = 0; k < WINDOW; k++) m = Math.max(m, model.residual(ds, node, t - k));
        return m / model.getResidualStd();
    }

    private static double sumOver(Dataset ds, int node, int t, int feature) {
        return sumOver(ds, node, t, feature, WINDOW);
    }

    static double sumOver(Dataset ds, int node, int t, int feature, int steps) {
        double s = 0;
        for (int k = 0; k < steps && t - k >= 0; k++) s += ds.values[node][t - k][feature];
        return s;
    }

    /** Water added in the last 6 hours is still draining, so the explanations look this far back. */
    public static final int RAIN_LOOKBACK = LONG_WINDOW;

    /** Share of the reconstruction error caused by the irrigation channels (flow and commands), which rain does not change. */
    public static double integrityShare(double[] err) {
        double total = 0, integrity = 0;
        for (int i = 0; i < err.length; i++) {
            total += err[i];
            int ch = i / 4;
            if (i < 32 && (ch == FLOW || ch == CMD)) integrity += err[i];
        }
        return total == 0 ? 0 : integrity / total;
    }

    /** True when rain in the window explains the unusual readings. */
    public boolean explainedByWeather(Dataset ds, int node, int t, double[] err) {
        return (sumOver(ds, node, t, RAIN, RAIN_LOOKBACK) >= 1.0 || extremeRainRecently(ds, node, t))
                && maxPositiveResidual(ds, node, t) < 3.0
                && integrityShare(err) < 0.4;
    }

    /**
     * True when the water came from irrigation that only trusted controllers ordered:
     * the soil really got wetter, no water appeared from nowhere, and no command came from elsewhere.
     */
    public boolean explainedByTrustedIrrigation(Dataset ds, int node, int t) {
        double flow = sumOver(ds, node, t, FLOW, LONG_WINDOW);
        if (flow < 4.0) return false;
        double gain = 0;
        for (int k = 0; k < LONG_WINDOW; k++) {
            int s = t - k;
            for (ActuatorEvent e : ds.eventsAt(node, s)) {
                if (!trusted.contains(e.getSourceIp())) return false;
            }
            if (ds.values[node][s][CMD] > 3) return false;
            gain += Math.max(0, ds.values[node][s][SM] - ds.values[node][s - 1][SM]);
        }
        return gain >= 0.3 * 0.12 * flow && maxPositiveResidual(ds, node, t) < 3.0;
    }

    public boolean isTrusted(String ip) {
        return trusted.contains(ip);
    }

    public static String featureName(int f) {
        return Config.FEATURES[f];
    }
}
