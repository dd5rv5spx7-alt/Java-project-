package com.agrocti.ml;

import java.util.Random;

/** A block of trainable weights with its gradient and Adam moment estimates. */
public final class Param {

    public final double[] w;
    public final double[] g;
    final double[] m;
    final double[] v;

    public Param(int size) {
        w = new double[size];
        g = new double[size];
        m = new double[size];
        v = new double[size];
    }

    /** Xavier/Glorot uniform initialisation. */
    public Param init(Random r, int fanIn, int fanOut) {
        double lim = Math.sqrt(6.0 / (fanIn + fanOut));
        for (int i = 0; i < w.length; i++) w[i] = (r.nextDouble() * 2 - 1) * lim;
        return this;
    }

    public void zeroGrad() {
        java.util.Arrays.fill(g, 0);
    }

    public int size() {
        return w.length;
    }
}
