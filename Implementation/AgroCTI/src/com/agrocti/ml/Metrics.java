package com.agrocti.ml;

import java.util.Arrays;

/** Confusion-matrix counts and the scores computed from them. */
public final class Metrics {

    public long tp, fp, tn, fn;

    public void add(boolean predicted, boolean actual) {
        if (predicted && actual) tp++;
        else if (predicted) fp++;
        else if (actual) fn++;
        else tn++;
    }

    public double precision() {
        return tp + fp == 0 ? 0 : (double) tp / (tp + fp);
    }

    public double recall() {
        return tp + fn == 0 ? 0 : (double) tp / (tp + fn);
    }

    public double f1() {
        double p = precision(), r = recall();
        return p + r == 0 ? 0 : 2 * p * r / (p + r);
    }

    public double fpr() {
        return fp + tn == 0 ? 0 : (double) fp / (fp + tn);
    }

    public double accuracy() {
        long all = tp + fp + tn + fn;
        return all == 0 ? 0 : (double) (tp + tn) / all;
    }

    /** The q-th quantile (0..1) of the values, with linear interpolation. */
    public static double quantile(double[] values, double q) {
        double[] s = values.clone();
        Arrays.sort(s);
        double pos = q * (s.length - 1);
        int lo = (int) Math.floor(pos);
        int hi = Math.min(s.length - 1, lo + 1);
        return s[lo] + (pos - lo) * (s[hi] - s[lo]);
    }

    @Override
    public String toString() {
        return String.format("F1 %.1f%%  precision %.1f%%  recall %.1f%%  FPR %.2f%%  (TP %d FP %d TN %d FN %d)",
                100 * f1(), 100 * precision(), 100 * recall(), 100 * fpr(), tp, fp, tn, fn);
    }
}
