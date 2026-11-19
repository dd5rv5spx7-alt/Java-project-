package com.agrocti;

import com.agrocti.config.Config;
import com.agrocti.cti.AgroCtiPipeline;
import com.agrocti.data.CsvStore;
import com.agrocti.db.Database;
import com.agrocti.db.RunDao;
import com.agrocti.eval.Experiment;
import com.agrocti.eval.ModelResult;
import com.agrocti.eval.Workspace;
import com.agrocti.exception.AgroCtiException;
import com.agrocti.export.CHeaderExporter;
import com.agrocti.export.ModelStore;
import com.agrocti.ml.QuantizedAutoencoder;
import com.agrocti.module.AgroCTI_Incident;
import com.agrocti.module.AgroCTI_ThreatIndicator;
import com.agrocti.module.DataSeeder;
import com.agrocti.model.AttackEpisode;
import com.agrocti.server.DashboardServer;
import com.agrocti.server.LiveSimulator;
import com.agrocti.test.SelfTest;
import com.agrocti.util.Log;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CountDownLatch;

/**
 * Entry point.
 *   demo      (default) evaluate if no trained model exists, then start the live dashboard
 *   generate  create the simulated dataset again
 *   evaluate  train all detectors, compare them, save the model and the ESP32 header
 *   serve     start the live dashboard with the saved model
 *   selftest  run the built-in unit tests
 */
public class Main {

    private String command = "demo";
    private Path data = Paths.get("data");
    private Path dbProps = Paths.get("db.properties");
    private long seed = 2026;
    private int port = 8090;
    private int delay = 300;

    public static void main(String[] args) {
        Main m = new Main();
        try {
            m.parse(args);
            m.runCommand();
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println("Usage: run.sh [demo|generate|evaluate|serve|selftest] [--data dir] [--db db.properties] [--seed n] [--port n] [--delay ms]");
            System.exit(2);
        } catch (AgroCtiException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        } catch (SQLException e) {
            System.err.println("Database error: " + Database.explain(e).getMessage());
            System.exit(1);
        } catch (java.net.BindException e) {
            System.err.println("Port " + m.port + " is already in use - try --port 8091");
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Unexpected error: " + e);
            e.printStackTrace();
            System.exit(1);
        }
    }

    private void parse(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--")) {
                command = a;
                continue;
            }
            if (i + 1 >= args.length) throw new IllegalArgumentException(a + " needs a value");
            String v = args[++i];
            try {
                switch (a) {
                    case "--data": data = Paths.get(v); break;
                    case "--db": dbProps = Paths.get(v); break;
                    case "--seed": seed = Long.parseLong(v); break;
                    case "--port": port = Integer.parseInt(v); break;
                    case "--delay": delay = Integer.parseInt(v); break;
                    default: throw new IllegalArgumentException("unknown option " + a);
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(a + " must be a number, not '" + v + "'");
            }
        }
    }

    private Path modelFile() {
        return data.resolve("models").resolve("agro_cti_model.bin");
    }

    private void runCommand() throws Exception {
        switch (command) {
            case "selftest":
                System.exit(SelfTest.runAll(dbProps) ? 0 : 1);
                break;
            case "generate":
                for (String f : new String[]{CsvStore.TELEMETRY, CsvStore.ACTUATOR, CsvStore.EPISODES, Workspace.FEED_FILE}) {
                    Files.deleteIfExists(data.resolve(f));
                }
                Workspace.ensureData(data, seed);
                break;
            case "evaluate":
                evaluate();
                break;
            case "serve":
                serve();
                break;
            case "demo":
                if (!Files.exists(modelFile())) evaluate();
                serve();
                break;
            default:
                throw new IllegalArgumentException("unknown command '" + command + "'");
        }
    }

    private void evaluate() throws Exception {
        Workspace ws = Workspace.open(data, seed);
        Experiment ex = new Experiment(ws, seed);
        ex.run();
        Files.writeString(data.resolve("results.json"), ex.toJson());
        ModelStore.save(modelFile(), ex.autoencoder, ws.featureScaler, ws.moisture, ws.guard, ex.tau, ex.tauLow);

        Path fw = Paths.get("firmware");
        if (Files.isDirectory(fw)) {
            CHeaderExporter.writeModel(fw.resolve("agro_ae_model.h"), ex.quantized, ws.featureScaler, ws.moisture, ex.tau, ex.tauLow);
            CHeaderExporter.writeTestVectors(fw.resolve("test_vectors.h"), ws.ds, ws.extractor, ws.featureScaler, ex.quantized, picks(ws));
            Log.info("Wrote firmware/agro_ae_model.h and firmware/test_vectors.h for the ESP32");
        }
        Database.init(dbProps, false);
        DataSeeder.seed(ws.feed.all());
        new RunDao().saveAll(seed, ex.results);
        Log.info("Saved results to " + data.resolve("results.json") + " and MySQL " + Database.describe());

        System.out.println();
        System.out.println(String.format("%-36s %7s %7s %7s %7s %9s %10s", "Model", "F1 %", "Prec %", "Rec %", "FPR %", "Params", "Memory"));
        for (ModelResult r : ex.results) {
            System.out.println(String.format(Locale.ROOT, "%-36s %7.1f %7.1f %7.1f %7.2f %9d %8.1f KB", r.name,
                    100 * r.metrics.f1(), 100 * r.metrics.precision(), 100 * r.metrics.recall(), 100 * r.metrics.fpr(),
                    r.parameters, r.memoryBytes / 1024.0));
        }
        System.out.println();
    }

    /** Test vectors: the first labelled step of some attacks, and random normal steps. */
    private static int[][] picks(Workspace ws) {
        List<int[]> out = new ArrayList<>();
        int i = 0;
        for (AttackEpisode e : ws.ds.getEpisodes()) {
            if (i++ % 8 == 0) out.add(new int[]{e.getNode(), Math.min(Config.TOTAL_STEPS - 1, e.getStart() + 3)});
        }
        Random r = new Random(99);
        while (out.size() < 20) out.add(new int[]{r.nextInt(Config.NODES), Config.VAL_END + r.nextInt(Config.TOTAL_STEPS - Config.VAL_END)});
        return out.toArray(new int[0][]);
    }

    private void serve() throws Exception {
        Workspace ws = Workspace.open(data, seed);
        if (!Files.exists(modelFile())) throw new AgroCtiException("no trained model in " + modelFile() + " - run 'evaluate' first");
        ModelStore.Bundle b = ModelStore.load(modelFile(), ws.featureScaler, ws.moisture, ws.guard);
        QuantizedAutoencoder q = new QuantizedAutoencoder(b.autoencoder, ws.features(Config.LONG_WINDOW, Config.TRAIN_END, 7));
        Database.init(dbProps, false);
        Log.info("Connected to MySQL " + Database.describe());
        DataSeeder.seed(ws.feed.all());
        AgroCTI_ThreatIndicator indicators = new AgroCTI_ThreatIndicator();
        ws.feed.replaceAll(indicators.external());          // MySQL is the source of truth while the dashboard runs
        indicators.setFeedListener(ws.feed::replaceAll);     // edits in the browser reach the detector at once
        AgroCtiPipeline pipeline = new AgroCtiPipeline(q, ws.extractor, ws.featureScaler, ws.guard, ws.feed,
                b.tau, b.tauLow, AgroCtiPipeline.Options.full());
        AgroCTI_Incident incidents = new AgroCTI_Incident();
        LiveSimulator sim = new LiveSimulator(ws.ds, pipeline, delay, incidents, indicators);
        incidents.setClock(sim::getStep);
        DashboardServer server = new DashboardServer(port, sim, ws.feed, Paths.get("web"), data.resolve("results.json"),
                incidents, indicators);
        server.start();
        sim.start();
        CountDownLatch stop = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            stop.countDown();
        }));
        stop.await();
    }
}
