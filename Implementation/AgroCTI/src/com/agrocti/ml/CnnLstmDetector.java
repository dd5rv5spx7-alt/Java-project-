package com.agrocti.ml;

import com.agrocti.exception.ModelNotTrainedException;
import com.agrocti.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * CNN-LSTM forecasting baseline. A 1-D convolution (kernel 3, ReLU) reads the last L
 * standardised readings, an LSTM summarises the convolution output, and a dense layer
 * predicts the next reading. The squared prediction error is the anomaly score.
 * Input: double[L+1][F]; rows 0..L-1 are the history, row L is the reading to check.
 */
public class CnnLstmDetector implements AnomalyDetector<double[][]> {

    private final int L, F, K, C, H, T;
    private final int epochs, batch;
    private final double lr;
    private final long seed;
    final Param Wc, bc, Wl, bl, Wd, bd;
    private boolean trained = false;
    private final List<Double> lossHistory = new ArrayList<>();

    public CnnLstmDetector(int seqLen, int features, int filters, int hidden, int epochs, int batch, double lr, long seed) {
        this.L = seqLen;
        this.F = features;
        this.K = 3;
        this.C = filters;
        this.H = hidden;
        this.T = L - K + 1;
        this.epochs = epochs;
        this.batch = batch;
        this.lr = lr;
        this.seed = seed;
        Random r = new Random(seed);
        Wc = new Param(C * K * F).init(r, K * F, C);
        bc = new Param(C);
        Wl = new Param(4 * H * (C + H)).init(r, C + H, 4 * H);
        bl = new Param(4 * H);
        for (int j = H; j < 2 * H; j++) bl.w[j] = 1.0;            // forget-gate bias
        Wd = new Param(F * H).init(r, H, F);
        bd = new Param(F);
    }

    @Override
    public String name() {
        return "CNN-LSTM Anomaly Detector";
    }

    public List<Double> getLossHistory() {
        return lossHistory;
    }

    /** Values kept from the forward pass for back-propagation. */
    private final class Cache {
        final double[][] z = new double[T][C], a = new double[T][C];
        final double[][] h = new double[T + 1][H], c = new double[T + 1][H];
        final double[][] gi = new double[T][H], gf = new double[T][H], go = new double[T][H], gu = new double[T][H];
        final double[] y = new double[F];
    }

    private static double sig(double x) {
        return 1 / (1 + Math.exp(-x));
    }

    private double forward(double[][] seq, Cache k) {
        for (int t = 0; t < T; t++) {
            for (int ch = 0; ch < C; ch++) {
                double s = bc.w[ch];
                for (int q = 0; q < K; q++) {
                    int base = (ch * K + q) * F;
                    for (int f = 0; f < F; f++) s += Wc.w[base + f] * seq[t + q][f];
                }
                k.z[t][ch] = s;
                k.a[t][ch] = Math.max(0, s);
            }
        }
        int in = C + H;
        double[] g = new double[4 * H];
        for (int t = 0; t < T; t++) {
            double[] hp = k.h[t];
            for (int j = 0; j < 4 * H; j++) {
                double s = bl.w[j];
                int base = j * in;
                for (int i = 0; i < C; i++) s += Wl.w[base + i] * k.a[t][i];
                for (int i = 0; i < H; i++) s += Wl.w[base + C + i] * hp[i];
                g[j] = s;
            }
            for (int j = 0; j < H; j++) {
                double ig = sig(g[j]), fg = sig(g[H + j]), og = sig(g[2 * H + j]), ug = Math.tanh(g[3 * H + j]);
                k.gi[t][j] = ig; k.gf[t][j] = fg; k.go[t][j] = og; k.gu[t][j] = ug;
                k.c[t + 1][j] = fg * k.c[t][j] + ig * ug;
                k.h[t + 1][j] = og * Math.tanh(k.c[t + 1][j]);
            }
        }
        double loss = 0;
        for (int f = 0; f < F; f++) {
            double s = bd.w[f];
            for (int j = 0; j < H; j++) s += Wd.w[f * H + j] * k.h[T][j];
            k.y[f] = s;
            double e = s - seq[L][f];
            loss += e * e;
        }
        return loss / F;
    }

    private void backward(double[][] seq, Cache k) {
        double[] dh = new double[H], dc = new double[H];
        for (int f = 0; f < F; f++) {
            double dy = 2 * (k.y[f] - seq[L][f]) / F;
            bd.g[f] += dy;
            for (int j = 0; j < H; j++) {
                Wd.g[f * H + j] += dy * k.h[T][j];
                dh[j] += Wd.w[f * H + j] * dy;
            }
        }
        int in = C + H;
        double[][] da = new double[T][C];
        double[] dg = new double[4 * H];
        for (int t = T - 1; t >= 0; t--) {
            for (int j = 0; j < H; j++) {
                double tc = Math.tanh(k.c[t + 1][j]);
                double dO = dh[j] * tc;
                double dct = dc[j] + dh[j] * k.go[t][j] * (1 - tc * tc);
                double dI = dct * k.gu[t][j];
                double dF = dct * k.c[t][j];
                double dU = dct * k.gi[t][j];
                dc[j] = dct * k.gf[t][j];
                dg[j] = dI * k.gi[t][j] * (1 - k.gi[t][j]);
                dg[H + j] = dF * k.gf[t][j] * (1 - k.gf[t][j]);
                dg[2 * H + j] = dO * k.go[t][j] * (1 - k.go[t][j]);
                dg[3 * H + j] = dU * (1 - k.gu[t][j] * k.gu[t][j]);
            }
            double[] dhPrev = new double[H];
            for (int j = 0; j < 4 * H; j++) {
                double d = dg[j];
                if (d == 0) continue;
                bl.g[j] += d;
                int base = j * in;
                for (int i = 0; i < C; i++) {
                    Wl.g[base + i] += d * k.a[t][i];
                    da[t][i] += Wl.w[base + i] * d;
                }
                for (int i = 0; i < H; i++) {
                    Wl.g[base + C + i] += d * k.h[t][i];
                    dhPrev[i] += Wl.w[base + C + i] * d;
                }
            }
            dh = dhPrev;
        }
        for (int t = 0; t < T; t++) {
            for (int ch = 0; ch < C; ch++) {
                if (k.z[t][ch] <= 0) continue;
                double d = da[t][ch];
                bc.g[ch] += d;
                for (int q = 0; q < K; q++) {
                    int base = (ch * K + q) * F;
                    for (int f = 0; f < F; f++) Wc.g[base + f] += d * seq[t + q][f];
                }
            }
        }
    }

    public List<Param> params() {
        return Arrays.asList(Wc, bc, Wl, bl, Wd, bd);
    }

    /** Loss of one sample and (optionally) its gradients; used by the gradient check test. */
    public double lossAndGrad(double[][] seq, boolean train) {
        Cache k = new Cache();
        double loss = forward(seq, k);
        if (train) backward(seq, k);
        return loss;
    }

    @Override
    public void fit(List<double[][]> data) {
        Random r = new Random(seed + 1);
        Adam opt = new Adam(lr, 5.0);
        List<double[][]> order = new ArrayList<>(data);
        List<Param> ps = params();
        Cache k = new Cache();
        for (int e = 0; e < epochs; e++) {
            Collections.shuffle(order, r);
            double total = 0;
            int inBatch = 0;
            for (double[][] seq : order) {
                total += forward(seq, k);
                backward(seq, k);
                if (++inBatch == batch) {
                    opt.step(ps, inBatch);
                    inBatch = 0;
                }
            }
            if (inBatch > 0) opt.step(ps, inBatch);
            lossHistory.add(total / order.size());
            Log.info(String.format("  CNN-LSTM epoch %d/%d  loss %.4f", e + 1, epochs, total / order.size()));
        }
        trained = true;
    }

    @Override
    public double score(double[][] seq) {
        if (!trained) throw new ModelNotTrainedException(name());
        return forward(seq, new Cache());
    }

    public void markTrained() {
        trained = true;
    }

    @Override
    public long parameterCount() {
        long n = 0;
        for (Param p : params()) n += p.size();
        return n;
    }

    @Override
    public long memoryBytes() {
        long acts = (long) T * C * 2 + 2L * (T + 1) * H + 4L * H + (long) (L + 1) * F;
        return parameterCount() * 4 + acts * 4;      // float32 weights + activations of one window
    }
}
