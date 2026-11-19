package com.agrocti.ml;

import java.util.List;

/** The Adam optimiser (Kingma and Ba, 2015) with optional gradient-norm clipping. */
public final class Adam {

    private final double lr;
    private final double b1 = 0.9, b2 = 0.999, eps = 1e-8;
    private final double clip;
    private int t = 0;

    public Adam(double lr, double clip) {
        this.lr = lr;
        this.clip = clip;
    }

    public void step(List<Param> params, int batchSize) {
        t++;
        double norm = 0;
        for (Param p : params) for (double gi : p.g) norm += (gi / batchSize) * (gi / batchSize);
        norm = Math.sqrt(norm);
        double scale = (clip > 0 && norm > clip) ? clip / norm : 1.0;
        double c1 = 1 - Math.pow(b1, t), c2 = 1 - Math.pow(b2, t);
        for (Param p : params) {
            for (int i = 0; i < p.w.length; i++) {
                double gi = p.g[i] / batchSize * scale;
                p.m[i] = b1 * p.m[i] + (1 - b1) * gi;
                p.v[i] = b2 * p.v[i] + (1 - b2) * gi * gi;
                p.w[i] -= lr * (p.m[i] / c1) / (Math.sqrt(p.v[i] / c2) + eps);
            }
            p.zeroGrad();
        }
    }
}
