package com.agrocti.ml;

import com.agrocti.data.Scaler;

import java.util.List;

/**
 * Post-training int8 quantisation of the TinyML autoencoder, as it runs on an ESP32.
 * Weights are symmetric per-layer int8, biases int32, activations int8.
 * Inference below uses only integer multiply-accumulate, like the C code in firmware/.
 */
public class QuantizedAutoencoder implements AnomalyDetector<double[]> {

    private final int[] sizes;
    private final byte[][] wq;
    private final int[][] bq;
    private final double[] wScale;      // per layer
    private final double[] actScale;    // scale of each layer's input activations (index 0 = network input)

    public QuantizedAutoencoder(TinyAutoencoder fp, List<double[]> calibration) {
        List<Dense> layers = fp.getLayers();
        sizes = fp.getSizes();
        int L = layers.size();
        wq = new byte[L][];
        bq = new int[L][];
        wScale = new double[L];
        actScale = new double[L + 1];
        // activation ranges from calibration data
        double[] maxAct = new double[L + 1];
        maxAct[0] = Scaler.CLIP;
        for (double[] x : calibration) {
            List<double[]> acts = fp.forwardAll(x);
            for (int l = 1; l < L; l++) {
                for (double a : acts.get(l)) maxAct[l] = Math.max(maxAct[l], Math.abs(a));
            }
        }
        for (int l = 0; l < L; l++) actScale[l] = Math.max(maxAct[l], 1e-6) / 127.0;
        for (int l = 0; l < L; l++) {
            Dense d = layers.get(l);
            double m = 1e-9;
            for (double w : d.W.w) m = Math.max(m, Math.abs(w));
            wScale[l] = m / 127.0;
            wq[l] = new byte[d.W.size()];
            for (int i = 0; i < wq[l].length; i++) wq[l][i] = (byte) clamp(Math.round(d.W.w[i] / wScale[l]), -127, 127);
            bq[l] = new int[d.out];
            for (int o = 0; o < d.out; o++) bq[l][o] = (int) Math.round(d.b.w[o] / (wScale[l] * actScale[l]));
        }
    }

    private static long clamp(long v, long lo, long hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    @Override
    public String name() {
        return "TinyML Autoencoder (int8)";
    }

    public static byte quantizeInput(double z, double scale) {
        return (byte) clamp(Math.round(z / scale), -127, 127);
    }

    /** Integer-only forward pass; the final layer is converted back to real numbers. */
    public double[] reconstruct(double[] x) {
        int L = wq.length;
        byte[] a = new byte[x.length];
        for (int i = 0; i < x.length; i++) a[i] = quantizeInput(x[i], actScale[0]);
        double[] outF = null;
        for (int l = 0; l < L; l++) {
            int in = sizes[l], out = sizes[l + 1];
            double m = wScale[l] * actScale[l];
            byte[] next = new byte[out];
            double[] real = new double[out];
            for (int o = 0; o < out; o++) {
                int acc = bq[l][o];
                int base = o * in;
                for (int i = 0; i < in; i++) acc += wq[l][base + i] * a[i];
                real[o] = acc * m;
                if (l < L - 1) {
                    double r = Math.max(0, real[o]);                       // ReLU
                    next[o] = (byte) clamp(Math.round(r / actScale[l + 1]), 0, 127);
                }
            }
            a = next;
            outF = real;
        }
        return outF;
    }

    @Override
    public void fit(List<double[]> normalData) {
        throw new UnsupportedOperationException("the int8 model is built from a trained float model");
    }

    @Override
    public double score(double[] x) {
        double[] y = reconstruct(x);
        double s = 0;
        for (int i = 0; i < x.length; i++) s += (y[i] - x[i]) * (y[i] - x[i]);
        return s / x.length;
    }

    /** Squared error of each input dimension (used to see which features caused an alert). */
    public double[] errorVector(double[] x) {
        double[] y = reconstruct(x);
        double[] e = new double[x.length];
        for (int i = 0; i < x.length; i++) e[i] = (y[i] - x[i]) * (y[i] - x[i]);
        return e;
    }

    @Override
    public long parameterCount() {
        long n = 0;
        for (int l = 0; l < wq.length; l++) n += wq[l].length + bq[l].length;
        return n;
    }

    @Override
    public long memoryBytes() {
        long bytes = 0;
        int widest = 0;
        for (int l = 0; l < wq.length; l++) bytes += wq[l].length + 4L * bq[l].length + 8;  // int8 W, int32 b, 2 float scales
        for (int s : sizes) widest = Math.max(widest, s);
        return bytes + 2L * widest + 4L * widest;   // two int8 activation buffers + float output row
    }

    public int[] getSizes() { return sizes.clone(); }
    public byte[][] getWeights() { return wq; }
    public int[][] getBiases() { return bq; }
    public double[] getWeightScales() { return wScale.clone(); }
    public double[] getActScales() { return actScale.clone(); }
}
