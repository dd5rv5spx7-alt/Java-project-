package com.agrocti.ml;

import com.agrocti.exception.ModelNotTrainedException;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Isolation Forest (Liu, Ting and Zhou, 2008). Unusual points are isolated by
 * fewer random splits, so a short average path length means a high anomaly score.
 */
public class IsolationForest implements AnomalyDetector<double[]> {

    private final int trees;
    private final int sampleSize;
    private final long seed;
    private final List<Tree> forest = new ArrayList<>();

    public IsolationForest(int trees, int sampleSize, long seed) {
        this.trees = trees;
        this.sampleSize = sampleSize;
        this.seed = seed;
    }

    /** One tree stored in flat arrays (feature = -1 marks a leaf). */
    private static final class Tree {
        final List<int[]> nodes = new ArrayList<>();      // {feature, left, right, size}
        final List<Double> splits = new ArrayList<>();
    }

    @Override
    public String name() {
        return "Isolation Forest";
    }

    /** Average path length of an unsuccessful search in a binary search tree of n points. */
    static double c(int n) {
        if (n <= 1) return 0;
        if (n == 2) return 1;
        return 2 * (Math.log(n - 1) + 0.5772156649) - 2.0 * (n - 1) / n;
    }

    @Override
    public void fit(List<double[]> data) {
        Random r = new Random(seed);
        int maxDepth = (int) Math.ceil(Math.log(sampleSize) / Math.log(2));
        forest.clear();
        for (int t = 0; t < trees; t++) {
            List<double[]> sample = new ArrayList<>(sampleSize);
            for (int i = 0; i < sampleSize; i++) sample.add(data.get(r.nextInt(data.size())));
            Tree tree = new Tree();
            build(tree, sample, 0, maxDepth, r);
            forest.add(tree);
        }
    }

    private int build(Tree tree, List<double[]> pts, int depth, int maxDepth, Random r) {
        int id = tree.nodes.size();
        tree.nodes.add(new int[]{-1, -1, -1, pts.size()});
        tree.splits.add(0.0);
        if (depth >= maxDepth || pts.size() <= 1) return id;
        int d = pts.get(0).length;
        for (int attempt = 0; attempt < 10; attempt++) {
            int f = r.nextInt(d);
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            for (double[] p : pts) {
                lo = Math.min(lo, p[f]);
                hi = Math.max(hi, p[f]);
            }
            if (hi - lo < 1e-12) continue;
            double split = lo + r.nextDouble() * (hi - lo);
            List<double[]> left = new ArrayList<>(), right = new ArrayList<>();
            for (double[] p : pts) (p[f] < split ? left : right).add(p);
            tree.splits.set(id, split);
            int l = build(tree, left, depth + 1, maxDepth, r);
            int rr = build(tree, right, depth + 1, maxDepth, r);
            tree.nodes.set(id, new int[]{f, l, rr, pts.size()});
            return id;
        }
        return id;
    }

    private double pathLength(Tree tree, double[] x) {
        int id = 0, depth = 0;
        while (true) {
            int[] n = tree.nodes.get(id);
            if (n[0] < 0) return depth + c(n[3]);
            id = x[n[0]] < tree.splits.get(id) ? n[1] : n[2];
            depth++;
        }
    }

    @Override
    public double score(double[] x) {
        if (forest.isEmpty()) throw new ModelNotTrainedException(name());
        double sum = 0;
        for (Tree t : forest) sum += pathLength(t, x);
        return Math.pow(2, -(sum / forest.size()) / c(sampleSize));
    }

    public long nodeCount() {
        long n = 0;
        for (Tree t : forest) n += t.nodes.size();
        return n;
    }

    @Override
    public long parameterCount() {
        return nodeCount() * 2;    // a split feature and a split value per node
    }

    @Override
    public long memoryBytes() {
        return nodeCount() * (4 + 4 + 4 + 4 + 8);   // feature, left, right, size (int) + split (double)
    }
}
