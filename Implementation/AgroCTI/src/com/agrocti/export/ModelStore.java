package com.agrocti.export;

import com.agrocti.cti.PhysicsGuard;
import com.agrocti.data.MoistureModel;
import com.agrocti.data.Scaler;
import com.agrocti.ml.Dense;
import com.agrocti.ml.TinyAutoencoder;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Saves the trained model in a small binary file with DataOutputStream and reads it back,
 * so the dashboard can start without training again.
 */
public final class ModelStore {

    private static final int MAGIC = 0x41474354;   // "AGCT"
    private static final int VERSION = 2;

    private ModelStore() { }

    /** Everything the dashboard needs, read back from the file. */
    public static final class Bundle {
        public TinyAutoencoder autoencoder;
        public double tau, tauLow;
    }

    public static void save(Path file, TinyAutoencoder ae, Scaler scaler, MoistureModel mm, PhysicsGuard guard,
                            double tau, double tauLow) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            int[] sizes = ae.getSizes();
            out.writeInt(sizes.length);
            for (int s : sizes) out.writeInt(s);
            for (Dense d : ae.getLayers()) {
                for (double w : d.W.w) out.writeDouble(w);
                for (double b : d.b.w) out.writeDouble(b);
            }
            writeArray(out, scaler.getMean());
            writeArray(out, scaler.getStd());
            writeArray(out, mm.getCoefficients());
            out.writeDouble(mm.getResidualStd());
            out.writeDouble(guard.getWindowSumStd());
            out.writeDouble(guard.getMaxDailyRain());
            out.writeDouble(tau);
            out.writeDouble(tauLow);
        }
    }

    /** Reads the model and restores the scaler, water-balance model and guard in place. */
    public static Bundle load(Path file, Scaler scaler, MoistureModel mm, PhysicsGuard guard) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != MAGIC) throw new IOException(file + " is not an Agro-CTI model file");
            int version = in.readInt();
            if (version != VERSION) throw new IOException("unsupported model version " + version);
            int n = in.readInt();
            int[] sizes = new int[n];
            for (int i = 0; i < n; i++) sizes[i] = in.readInt();
            TinyAutoencoder ae = new TinyAutoencoder(sizes, 0, 1, 0, 0);
            for (Dense d : ae.getLayers()) {
                for (int i = 0; i < d.W.w.length; i++) d.W.w[i] = in.readDouble();
                for (int i = 0; i < d.b.w.length; i++) d.b.w[i] = in.readDouble();
            }
            ae.markTrained();
            double[] mean = readArray(in), std = readArray(in), coef = readArray(in);
            scaler.restore(mean, std);
            mm.restore(coef, in.readDouble());
            guard.setWindowSumStd(in.readDouble());
            guard.setMaxDailyRain(in.readDouble());
            Bundle b = new Bundle();
            b.autoencoder = ae;
            b.tau = in.readDouble();
            b.tauLow = in.readDouble();
            return b;
        }
    }

    private static void writeArray(DataOutputStream out, double[] a) throws IOException {
        out.writeInt(a.length);
        for (double v : a) out.writeDouble(v);
    }

    private static double[] readArray(DataInputStream in) throws IOException {
        double[] a = new double[in.readInt()];
        for (int i = 0; i < a.length; i++) a[i] = in.readDouble();
        return a;
    }
}
