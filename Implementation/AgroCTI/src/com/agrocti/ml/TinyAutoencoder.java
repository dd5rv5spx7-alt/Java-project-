package com.agrocti.ml;

import com.agrocti.exception.ModelNotTrainedException;
import com.agrocti.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * The TinyML autoencoder: 38 -> 32 -> 4 -> 32 -> 38 with ReLU in the hidden layers.
 * It learns to rebuild normal feature vectors; the reconstruction error is the anomaly score.
 */
public class TinyAutoencoder implements AnomalyDetector<double[]> {

    private final int[] sizes;
    private final List<Dense> layers = new ArrayList<>();
    private final int epochs;
    private final int batch;
    private final double lr;
    private final long seed;
    private boolean trained = false;
    private final List<Double> lossHistory = new ArrayList<>();

    public TinyAutoencoder(int[] sizes, int epochs, int batch, double lr, long seed) {
        this.sizes = sizes.clone();
        this.epochs = epochs;
        this.batch = batch;
        this.lr = lr;
        this.seed = seed;
        Random r = new Random(seed);
        for (int i = 0; i + 1 < sizes.length; i++) {
            layers.add(new Dense(sizes[i], sizes[i + 1], i + 2 < sizes.length, r));
        }
    }

    public TinyAutoencoder(int inputDim, long seed) {
        this(new int[]{inputDim, 32, 4, 32, inputDim}, 60, 64, 0.001, seed);
    }

    @Override
    public String name() {
        return "TinyML Autoencoder (float32)";
    }

    public List<Dense> getLayers() {
        return layers;
    }

    public int[] getSizes() {
        return sizes.clone();
    }

    public List<Double> getLossHistory() {
        return lossHistory;
    }

    /** All layer outputs; acts.get(0) is the input. */
    List<double[]> forwardAll(double[] x) {
        List<double[]> acts = new ArrayList<>();
        acts.add(x);
        double[] h = x;
        for (Dense d : layers) {
            h = d.forward(h);
            acts.add(h);
        }
        return acts;
    }

    public double[] reconstruct(double[] x) {
        double[] h = x;
        for (Dense d : layers) h = d.forward(h);
        return h;
    }

    /** Mean squared error of one sample; also adds its gradients when train is true. */
    public double lossAndGrad(double[] x, boolean train) {
        List<double[]> acts = forwardAll(x);
        double[] y = acts.get(acts.size() - 1);
        double loss = 0;
        double[] dy = new double[y.length];
        for (int i = 0; i < y.length; i++) {
            double e = y[i] - x[i];
            loss += e * e;
            dy[i] = 2 * e / y.length;
        }
        if (train) {
            for (int l = layers.size() - 1; l >= 0; l--) {
                dy = layers.get(l).backward(acts.get(l), acts.get(l + 1), dy);
            }
        }
        return loss / y.length;
    }

    public List<Param> params() {
        List<Param> ps = new ArrayList<>();
        for (Dense d : layers) {
            ps.add(d.W);
            ps.add(d.b);
        }
        return ps;
    }

    @Override
    public void fit(List<double[]> data) {
        Random r = new Random(seed + 1);
        Adam opt = new Adam(lr, 5.0);
        List<double[]> order = new ArrayList<>(data);
        List<Param> ps = params();
        for (int e = 0; e < epochs; e++) {
            Collections.shuffle(order, r);
            double total = 0;
            int inBatch = 0;
            for (double[] x : order) {
                total += lossAndGrad(x, true);
                if (++inBatch == batch) {
                    opt.step(ps, inBatch);
                    inBatch = 0;
                }
            }
            if (inBatch > 0) opt.step(ps, inBatch);
            lossHistory.add(total / order.size());
            if (e % 10 == 0 || e == epochs - 1) {
                Log.info(String.format("  autoencoder epoch %2d/%d  loss %.4f", e + 1, epochs, total / order.size()));
            }
        }
        trained = true;
    }

    @Override
    public double score(double[] x) {
        if (!trained) throw new ModelNotTrainedException(name());
        double[] y = reconstruct(x);
        double s = 0;
        for (int i = 0; i < x.length; i++) s += (y[i] - x[i]) * (y[i] - x[i]);
        return s / x.length;
    }

    @Override
    public long parameterCount() {
        long n = 0;
        for (Dense d : layers) n += d.W.size() + d.b.size();
        return n;
    }

    @Override
    public long memoryBytes() {
        int widest = 0;
        for (int s : sizes) widest = Math.max(widest, s);
        return parameterCount() * 4 + 2L * widest * 4;   // float32 weights + two activation buffers
    }

    public void markTrained() {
        trained = true;
    }
}
