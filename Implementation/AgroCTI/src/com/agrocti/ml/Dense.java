package com.agrocti.ml;

import java.util.Random;

/** A fully connected layer y = W x + b, with an optional ReLU. */
public final class Dense {

    public final int in;
    public final int out;
    public final boolean relu;
    public final Param W;
    public final Param b;

    public Dense(int in, int out, boolean relu, Random r) {
        this.in = in;
        this.out = out;
        this.relu = relu;
        W = new Param(in * out).init(r, in, out);
        b = new Param(out);
    }

    public double[] forward(double[] x) {
        double[] y = new double[out];
        for (int o = 0; o < out; o++) {
            double s = b.w[o];
            int base = o * in;
            for (int i = 0; i < in; i++) s += W.w[base + i] * x[i];
            y[o] = relu ? Math.max(0, s) : s;
        }
        return y;
    }

    /** Adds this sample's gradients and returns dL/dx. dy is changed in place by the ReLU mask. */
    public double[] backward(double[] x, double[] y, double[] dy) {
        double[] dx = new double[in];
        for (int o = 0; o < out; o++) {
            double d = (relu && y[o] <= 0) ? 0 : dy[o];
            if (d == 0) continue;
            b.g[o] += d;
            int base = o * in;
            for (int i = 0; i < in; i++) {
                W.g[base + i] += d * x[i];
                dx[i] += W.w[base + i] * d;
            }
        }
        return dx;
    }
}
