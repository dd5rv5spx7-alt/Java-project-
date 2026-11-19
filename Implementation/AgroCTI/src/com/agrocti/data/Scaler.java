package com.agrocti.data;

import java.util.List;

/** Z-score standardisation, with values clipped to [-CLIP, CLIP] so int8 inputs never overflow. */
public class Scaler {

    public static final double CLIP = 8.0;
    private double[] mean;
    private double[] std;

    public void fit(List<double[]> rows) {
        int d = rows.get(0).length;
        mean = new double[d];
        std = new double[d];
        for (double[] r : rows) for (int i = 0; i < d; i++) mean[i] += r[i];
        for (int i = 0; i < d; i++) mean[i] /= rows.size();
        for (double[] r : rows) for (int i = 0; i < d; i++) std[i] += (r[i] - mean[i]) * (r[i] - mean[i]);
        for (int i = 0; i < d; i++) std[i] = Math.max(Math.sqrt(std[i] / rows.size()), 1e-2);
    }

    public double[] transform(double[] x) {
        double[] z = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            z[i] = Math.max(-CLIP, Math.min(CLIP, (x[i] - mean[i]) / std[i]));
        }
        return z;
    }

    public void restore(double[] m, double[] s) {
        mean = m.clone();
        std = s.clone();
    }

    public double[] getMean() { return mean.clone(); }
    public double[] getStd() { return std.clone(); }
}
