package com.agrocti.data;

import com.agrocti.config.Config;

/**
 * A water-balance model of the soil, fitted on clean training data with least squares:
 *   change in moisture = a*rain + b*flow + c*evaporation + d
 * The difference between the measured and the predicted change (the residual)
 * shows readings that do not obey physics, for example a replayed or frozen sensor.
 */
public class MoistureModel {

    private final double[] coef = new double[4];
    private double residualStd = 1;

    public static double evaporationProxy(int step, double airTemp) {
        double h = Config.hourOf(step);
        return Math.max(0, Math.sin(Math.PI * (h - 6) / 12)) * (1 + 0.03 * (airTemp - 29));
    }

    private static double[] inputs(double[] v, int step) {
        return new double[]{v[Config.RAIN], v[Config.FLOW], evaporationProxy(step, v[Config.AT]), 1.0};
    }

    public void fit(Dataset ds, int from, int to) {
        double[][] xtx = new double[4][4];
        double[] xty = new double[4];
        for (int n = 0; n < ds.nodes; n++) {
            for (int t = Math.max(1, from); t < to; t++) {
                double[] x = inputs(ds.values[n][t], t);
                double y = ds.values[n][t][Config.SM] - ds.values[n][t - 1][Config.SM];
                for (int i = 0; i < 4; i++) {
                    xty[i] += x[i] * y;
                    for (int j = 0; j < 4; j++) xtx[i][j] += x[i] * x[j];
                }
            }
        }
        for (int i = 0; i < 4; i++) xtx[i][i] += 1e-6;
        double[] c = solve(xtx, xty);
        System.arraycopy(c, 0, coef, 0, 4);
        double ss = 0;
        long count = 0;
        for (int n = 0; n < ds.nodes; n++) {
            for (int t = Math.max(1, from); t < to; t++) {
                double r = residual(ds, n, t);
                ss += r * r;
                count++;
            }
        }
        residualStd = Math.sqrt(ss / count);
    }

    /** Measured minus predicted change of soil moisture at one step. */
    public double residual(Dataset ds, int node, int t) {
        double[] x = inputs(ds.values[node][t], t);
        double pred = 0;
        for (int i = 0; i < 4; i++) pred += coef[i] * x[i];
        return (ds.values[node][t][Config.SM] - ds.values[node][t - 1][Config.SM]) - pred;
    }

    /** Restores a model saved earlier (see ModelStore). */
    public void restore(double[] c, double std) {
        System.arraycopy(c, 0, coef, 0, 4);
        residualStd = std;
    }

    public double getResidualStd() {
        return residualStd;
    }

    public double[] getCoefficients() {
        return coef.clone();
    }

    /** Gaussian elimination with partial pivoting. */
    static double[] solve(double[][] a, double[] b) {
        int n = b.length;
        double[][] m = new double[n][n + 1];
        for (int i = 0; i < n; i++) {
            System.arraycopy(a[i], 0, m[i], 0, n);
            m[i][n] = b[i];
        }
        for (int col = 0; col < n; col++) {
            int piv = col;
            for (int r = col + 1; r < n; r++) if (Math.abs(m[r][col]) > Math.abs(m[piv][col])) piv = r;
            double[] tmp = m[col]; m[col] = m[piv]; m[piv] = tmp;
            for (int r = 0; r < n; r++) {
                if (r == col) continue;
                double f = m[r][col] / m[col][col];
                for (int k = col; k <= n; k++) m[r][k] -= f * m[col][k];
            }
        }
        double[] x = new double[n];
        for (int i = 0; i < n; i++) x[i] = m[i][n] / m[i][i];
        return x;
    }
}
