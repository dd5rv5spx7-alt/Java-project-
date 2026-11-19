package com.agrocti.data;

import com.agrocti.config.Config;

import static com.agrocti.config.Config.*;

/**
 * Turns the last WINDOW readings of one node into a fixed-length feature vector.
 * The same computation is small enough to run on the microcontroller.
 * For each of the 8 sensor channels: last value, mean, log standard deviation and slope (32),
 * then: water-balance residual sum and maximum, soil/air temperature gap, hour sine and cosine,
 * and the 6-hour water-balance residual sum (6).
 */
public class FeatureExtractor {

    public static final int DIM = NF * 4 + 6;
    public static final String[] NAMES = new String[DIM];

    static {
        String[] stat = {"last", "mean", "logstd", "slope"};
        for (int f = 0; f < NF; f++) {
            for (int s = 0; s < 4; s++) NAMES[f * 4 + s] = FEATURES[f] + "_" + stat[s];
        }
        NAMES[32] = "balance_residual_sum";
        NAMES[33] = "balance_residual_max";
        NAMES[34] = "soil_air_temp_gap";
        NAMES[35] = "hour_sin";
        NAMES[36] = "hour_cos";
        NAMES[37] = "balance_residual_6h";
    }

    /** Index ranges of the weather channels (air temperature, humidity, rainfall). */
    public static boolean isWeatherFeature(int i) {
        int ch = i / 4;
        return i < 32 && (ch == AT || ch == RH || ch == RAIN);
    }

    private final MoistureModel moisture;

    public FeatureExtractor(MoistureModel moisture) {
        this.moisture = moisture;
    }

    public MoistureModel getMoistureModel() {
        return moisture;
    }

    /** Features of the window that ends at step t (needs t >= LONG_WINDOW). */
    public double[] extract(Dataset ds, int node, int t) {
        double[] out = new double[DIM];
        int w = WINDOW;
        double xMean = (w - 1) / 2.0;
        double xVar = 0;
        for (int k = 0; k < w; k++) xVar += (k - xMean) * (k - xMean);
        for (int f = 0; f < NF; f++) {
            double sum = 0;
            for (int k = 0; k < w; k++) sum += ds.values[node][t - w + 1 + k][f];
            double mean = sum / w;
            double ss = 0, cov = 0;
            for (int k = 0; k < w; k++) {
                double v = ds.values[node][t - w + 1 + k][f];
                ss += (v - mean) * (v - mean);
                cov += (k - xMean) * (v - mean);
            }
            out[f * 4] = ds.values[node][t][f];
            out[f * 4 + 1] = mean;
            out[f * 4 + 2] = Math.log(Math.sqrt(ss / w) + 1e-3);
            out[f * 4 + 3] = cov / xVar;
        }
        double rs = 0, rmax = 0, airMean = 0;
        for (int k = 0; k < w; k++) {
            int s = t - w + 1 + k;
            double r = moisture.residual(ds, node, s);
            rs += r;
            rmax = Math.max(rmax, Math.abs(r));
            airMean += ds.values[node][s][AT];
        }
        out[32] = rs;
        out[33] = rmax;
        out[34] = ds.values[node][t][ST] - airMean / w;
        double h = Config.hourOf(t);
        out[35] = Math.sin(2 * Math.PI * h / 24);
        out[36] = Math.cos(2 * Math.PI * h / 24);
        double longSum = 0;
        double clip = 3 * moisture.getResidualStd();      // one sudden jump must not dominate 6 hours
        for (int k = 0; k < LONG_WINDOW; k++) longSum += Math.max(-clip, Math.min(clip, moisture.residual(ds, node, t - k)));
        out[37] = longSum;
        return out;
    }
}
