package com.agrocti.eval;

import com.agrocti.config.Config;
import com.agrocti.cti.PhysicsGuard;
import com.agrocti.cti.ThreatFeed;
import com.agrocti.data.CsvStore;
import com.agrocti.data.Dataset;
import com.agrocti.data.FeatureExtractor;
import com.agrocti.data.MoistureModel;
import com.agrocti.data.Scaler;
import com.agrocti.data.TelemetryGenerator;
import com.agrocti.exception.AgroCtiException;
import com.agrocti.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static com.agrocti.config.Config.*;

/** Loads (or first generates) the dataset and fits everything that is learned from clean training data. */
public class Workspace {

    public static final String FEED_FILE = "threat_feed.json";

    public final Dataset ds;
    public final ThreatFeed feed;
    public final MoistureModel moisture = new MoistureModel();
    public final FeatureExtractor extractor = new FeatureExtractor(moisture);
    public final Scaler featureScaler = new Scaler();
    public final Scaler readingScaler = new Scaler();
    public final PhysicsGuard guard = new PhysicsGuard(moisture, TelemetryGenerator.trustedSources());

    private Workspace(Dataset ds, ThreatFeed feed) {
        this.ds = ds;
        this.feed = feed;
    }

    /** Generates the dataset files in dir if they are not there yet. */
    public static void ensureData(Path dir, long seed) throws IOException, InterruptedException, ExecutionException {
        if (Files.exists(dir.resolve(CsvStore.TELEMETRY)) && Files.exists(dir.resolve(FEED_FILE))) return;
        Log.info("Generating simulated smart-farm dataset (seed " + seed + ") in " + dir);
        Dataset ds = new TelemetryGenerator(seed).generate();
        CsvStore.save(ds, dir);
        ThreatFeed.save(ds.getFeed(), dir.resolve(FEED_FILE));
        Log.info("  " + NODES + " nodes x " + TOTAL_STEPS + " steps, " + ds.allEvents().size() + " actuator commands, "
                + ds.getEpisodes().size() + " attack episodes, " + ds.getFeed().size() + " feed indicators");
    }

    public static Workspace open(Path dir, long seed) throws IOException, InterruptedException, ExecutionException, AgroCtiException {
        ensureData(dir, seed);
        Dataset ds = CsvStore.load(dir);
        ThreatFeed feed = ThreatFeed.load(dir.resolve(FEED_FILE));
        Log.info("Loaded " + dir.resolve(CsvStore.TELEMETRY).getFileName() + " (" + (long) NODES * TOTAL_STEPS
                + " readings) and feed with " + feed.ipCount() + " IP indicators and " + feed.advisoryCount() + " advisories");
        return new Workspace(ds, feed);
    }

    /** Fits the water-balance model, the physics guard and both scalers on the training period. */
    public void fitPreprocessing() {
        moisture.fit(ds, 1, TRAIN_END);
        guard.fit(ds, WINDOW, TRAIN_END);
        featureScaler.fit(rawFeatures(WINDOW, TRAIN_END, 1));
        List<double[]> rows = new ArrayList<>();
        for (int n = 0; n < ds.nodes; n++) for (int t = 0; t < TRAIN_END; t++) rows.add(ds.values[n][t]);
        readingScaler.fit(rows);
    }

    public List<double[]> rawFeatures(int from, int to, int stride) {
        List<double[]> out = new ArrayList<>();
        for (int n = 0; n < ds.nodes; n++) {
            for (int t = Math.max(from, LONG_WINDOW); t < to; t += stride) out.add(extractor.extract(ds, n, t));
        }
        return out;
    }

    /** Standardised feature vectors, ordered node by node. */
    public List<double[]> features(int from, int to, int stride) {
        List<double[]> out = new ArrayList<>();
        for (double[] x : rawFeatures(from, to, stride)) out.add(featureScaler.transform(x));
        return out;
    }

    public double[] feature(int node, int t) {
        return featureScaler.transform(extractor.extract(ds, node, t));
    }

    /** Standardised readings t-SEQ_LEN .. t for the CNN-LSTM. */
    public double[][] sequence(int node, int t) {
        double[][] seq = new double[Config.SEQ_LEN + 1][];
        for (int k = 0; k <= Config.SEQ_LEN; k++) seq[k] = readingScaler.transform(ds.values[node][t - Config.SEQ_LEN + k]);
        return seq;
    }

    public List<double[][]> sequences(int from, int to, int stride) {
        List<double[][]> out = new ArrayList<>();
        for (int n = 0; n < ds.nodes; n++) {
            for (int t = Math.max(from, Config.SEQ_LEN); t < to; t += stride) out.add(sequence(n, t));
        }
        return out;
    }
}
