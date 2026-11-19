package com.agrocti.test;

import com.agrocti.config.Config;
import com.agrocti.cti.CtiCorrelator;
import com.agrocti.cti.PhysicsGuard;
import com.agrocti.cti.ThreatFeed;
import com.agrocti.data.CsvStore;
import com.agrocti.data.Dataset;
import com.agrocti.data.MoistureModel;
import com.agrocti.data.TelemetryGenerator;
import com.agrocti.db.Database;
import com.agrocti.exception.DataFormatException;
import com.agrocti.exception.FeedFormatException;
import com.agrocti.exception.InvalidReadingException;
import com.agrocti.exception.ModelNotTrainedException;
import com.agrocti.ml.CnnLstmDetector;
import com.agrocti.ml.IsolationForest;
import com.agrocti.ml.Metrics;
import com.agrocti.ml.Param;
import com.agrocti.ml.QuantizedAutoencoder;
import com.agrocti.ml.TinyAutoencoder;
import com.agrocti.model.ActionStatus;
import com.agrocti.model.ActionType;
import com.agrocti.model.Alert;
import com.agrocti.model.FarmNode;
import com.agrocti.model.IncidentStatus;
import com.agrocti.model.NodeStatus;
import com.agrocti.model.ResponseAction;
import com.agrocti.model.Role;
import com.agrocti.model.UserAccount;
import com.agrocti.module.AgroCTI_FarmNode;
import com.agrocti.module.AgroCTI_Incident;
import com.agrocti.module.AgroCTI_ResponseAction;
import com.agrocti.module.AgroCTI_ThreatIndicator;
import com.agrocti.module.AgroCTI_User;
import com.agrocti.module.DataSeeder;
import com.agrocti.exception.ValidationException;
import com.agrocti.model.Severity;
import com.agrocti.model.ThreatIndicator;
import com.agrocti.util.JsonParser;
import com.agrocti.util.RingBuffer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Built-in unit tests (run with: run.sh selftest). No test library is needed. */
public final class SelfTest {

    private interface Check {
        String run() throws Exception;     // returns a short description of what was measured
    }

    private static int passed, failed;

    private SelfTest() { }

    private static void test(String id, String name, Check c) {
        try {
            String detail = c.run();
            passed++;
            System.out.printf("  PASS  %-5s %-68s %s%n", id, name, detail);
        } catch (Throwable e) {
            failed++;
            System.out.printf("  FAIL  %-5s %-68s %s%n", id, name, e);
        }
    }

    private static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    /** Largest relative difference between analytic and numerical gradients. */
    private static double gradCheck(List<Param> params, java.util.function.DoubleSupplier loss, Runnable grads) {
        for (Param p : params) p.zeroGrad();
        grads.run();
        double worst = 0;
        Random r = new Random(5);
        for (Param p : params) {
            for (int k = 0; k < Math.min(12, p.size()); k++) {
                int i = r.nextInt(p.size());
                double old = p.w[i], h = 1e-5;
                p.w[i] = old + h;
                double lp = loss.getAsDouble();
                p.w[i] = old - h;
                double lm = loss.getAsDouble();
                p.w[i] = old;
                double num = (lp - lm) / (2 * h);
                double denom = Math.max(1e-6, Math.abs(num) + Math.abs(p.g[i]));
                worst = Math.max(worst, Math.abs(num - p.g[i]) / denom);
            }
        }
        return worst;
    }

    public static boolean runAll(Path dbProps) throws Exception {
        System.out.println("Agro-CTI self-test");
        Random r = new Random(1);

        test("UT1", "Autoencoder back-propagation matches numerical gradient", () -> {
            TinyAutoencoder ae = new TinyAutoencoder(new int[]{6, 5, 3, 5, 6}, 1, 1, 0.01, 3);
            double[] x = {0.5, -1.2, 0.3, 2.0, -0.7, 0.1};
            double e = gradCheck(ae.params(), () -> ae.lossAndGrad(x, false), () -> ae.lossAndGrad(x, true));
            check(e < 1e-4, "relative error " + e);
            return String.format("max relative error %.1e", e);
        });

        test("UT2", "CNN-LSTM back-propagation through time matches numerical gradient", () -> {
            CnnLstmDetector m = new CnnLstmDetector(5, 3, 2, 3, 1, 1, 0.01, 4);
            double[][] seq = new double[6][3];
            for (double[] row : seq) for (int i = 0; i < 3; i++) row[i] = r.nextGaussian();
            double e = gradCheck(m.params(), () -> m.lossAndGrad(seq, false), () -> m.lossAndGrad(seq, true));
            check(e < 1e-4, "relative error " + e);
            return String.format("max relative error %.1e", e);
        });

        test("UT3", "Isolation Forest scores an outlier above normal points", () -> {
            List<double[]> data = new ArrayList<>();
            for (int i = 0; i < 2000; i++) data.add(new double[]{r.nextGaussian(), r.nextGaussian()});
            IsolationForest f = new IsolationForest(100, 256, 7);
            f.fit(data);
            double normal = f.score(new double[]{0, 0}), outlier = f.score(new double[]{6, -6});
            check(outlier > normal + 0.15, "outlier " + outlier + " normal " + normal);
            return String.format("normal %.2f, outlier %.2f", normal, outlier);
        });

        test("UT4", "Scoring before training throws ModelNotTrainedException", () -> {
            try {
                new IsolationForest(10, 16, 1).score(new double[]{1});
                throw new AssertionError("no exception");
            } catch (ModelNotTrainedException e) {
                return "\"" + e.getMessage().substring(0, 30) + "...\"";
            }
        });

        test("UT5", "Metrics: F1, precision, recall and FPR of a known confusion matrix", () -> {
            Metrics m = new Metrics();
            for (int i = 0; i < 8; i++) m.add(true, true);
            for (int i = 0; i < 2; i++) m.add(true, false);
            for (int i = 0; i < 2; i++) m.add(false, true);
            for (int i = 0; i < 88; i++) m.add(false, false);
            check(Math.abs(m.f1() - 0.8) < 1e-12 && Math.abs(m.fpr() - 2.0 / 90) < 1e-12, m.toString());
            check(Math.abs(Metrics.quantile(new double[]{1, 2, 3, 4, 5}, 0.5) - 3) < 1e-12, "quantile");
            return "F1 0.800, FPR 0.022 as expected";
        });

        test("UT6", "int8 autoencoder stays close to the float model", () -> {
            List<double[]> data = new ArrayList<>();
            for (int i = 0; i < 3000; i++) {
                double a = r.nextGaussian();
                data.add(new double[]{a, 2 * a, -a, a + 0.1 * r.nextGaussian(), r.nextGaussian(), 0.5 * a});
            }
            TinyAutoencoder ae = new TinyAutoencoder(new int[]{6, 8, 3, 8, 6}, 15, 32, 0.01, 2);
            java.io.PrintStream out = System.out;
            System.setOut(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
            try { ae.fit(data); } finally { System.setOut(out); }
            QuantizedAutoencoder q = new QuantizedAutoencoder(ae, data);
            double worst = 0;
            for (int i = 0; i < 200; i++) {
                double f = ae.score(data.get(i)), qq = q.score(data.get(i));
                worst = Math.max(worst, Math.abs(f - qq) / Math.max(f, 0.05));
            }
            check(worst < 0.25, "relative difference " + worst);
            return String.format("largest relative score difference %.3f", worst);
        });

        test("UT7", "Water-balance least squares recovers known coefficients", () -> {
            Dataset ds = new Dataset(1, 400, Config.NF);
            double sm = 30;
            for (int t = 0; t < 400; t++) {
                double[] v = ds.values[0][t];
                v[Config.RAIN] = t % 17 == 0 ? 2 : 0;
                v[Config.FLOW] = t % 40 < 5 ? 15 : 0;
                v[Config.AT] = 29;
                sm += 0.55 * v[Config.RAIN] + 0.12 * v[Config.FLOW] - 0.05 * MoistureModel.evaporationProxy(t, 29) - 0.03;
                v[Config.SM] = sm;
            }
            MoistureModel mm = new MoistureModel();
            mm.fit(ds, 1, 400);
            double[] c = mm.getCoefficients();
            check(Math.abs(c[0] - 0.55) < 1e-6 && Math.abs(c[1] - 0.12) < 1e-6, Arrays.toString(c));
            return String.format("rain %.3f, flow %.3f, evap %.3f", c[0], c[1], c[2]);
        });

        test("UT8", "Physically impossible reading raises InvalidReadingException", () -> {
            double[] v = {120, 25, 30, 60, 0, 1, 0, 0};
            try {
                PhysicsGuard.checkRange(v);
                throw new AssertionError("no exception");
            } catch (InvalidReadingException e) {
                check(e.getFeature() == Config.SM, "wrong feature");
                return "\"" + e.getMessage() + "\"";
            }
        });

        test("UT9", "Threat feed: valid file loads, malformed entries are rejected", () -> {
            Path dir = Files.createTempDirectory("agrocti");
            Path good = dir.resolve("feed.json");
            List<ThreatIndicator> list = Arrays.asList(new ThreatIndicator("i1", "203.0.113.9", 90, "test"),
                    new ThreatIndicator("i2", "regional-advisory", "drift", 1, 10, 20, 70, "test"));
            ThreatFeed.save(list, good);
            ThreatFeed f = ThreatFeed.load(good);
            check(f.lookupIp("203.0.113.9").isPresent() && f.activeAdvisories(1, 15).size() == 1
                    && f.activeAdvisories(1, 25).isEmpty(), "lookup");
            String[] bad = {"{\"indicators\": 5}", "{\"indicators\":[{\"id\":\"x\",\"type\":\"ipv4\",\"value\":\"999.1\",\"confidence\":5}]}",
                    "{\"indicators\":[{\"id\":\"x\",\"type\":\"regional-advisory\",\"value\":\"d\",\"region\":\"Mars\",\"confidence\":5,\"validFrom\":1,\"validTo\":2}]}",
                    "{\"indicators\": [ {\"id\": } ]}"};
            int rejected = 0;
            for (String b : bad) {
                Files.writeString(dir.resolve("bad.json"), b);
                try {
                    ThreatFeed.load(dir.resolve("bad.json"));
                } catch (FeedFormatException e) {
                    rejected++;
                }
            }
            check(rejected == bad.length, rejected + " of " + bad.length + " rejected");
            return "2 indicators loaded, 4 of 4 malformed feeds rejected";
        });

        test("UT10", "JSON parser handles nesting, escapes and numbers", () -> {
            Object o = JsonParser.parse("{\"a\":[1,2.5,-3e2],\"b\":{\"c\":\"x\\\"y\\u0041\"},\"d\":true,\"e\":null}");
            Map<?, ?> m = (Map<?, ?>) o;
            check(((List<?>) m.get("a")).get(2).equals(-300.0), "number");
            check("x\"yA".equals(((Map<?, ?>) m.get("b")).get("c")), "string");
            return "parsed 4 keys correctly";
        });

        test("UT11", "Malformed telemetry CSV raises DataFormatException", () -> {
            Path dir = Files.createTempDirectory("agrocti");
            Files.writeString(dir.resolve(CsvStore.TELEMETRY), "header\n0,GUN-F1,0,2026-07-01T00:00,abc\n");
            try {
                CsvStore.load(dir);
                throw new AssertionError("no exception");
            } catch (DataFormatException e) {
                return "\"" + e.getMessage() + "\"";
            }
        });

        test("UT12", "Regional correlator needs 3 farms of the same region", () -> {
            CtiCorrelator c = new CtiCorrelator();
            c.registerSuspect(0, 100);
            c.registerSuspect(1, 101);
            c.registerSuspect(6, 101);          // farm of another region
            check(!c.regionalCampaign(0, 101), "2 farms must not be enough");
            c.registerSuspect(2, 102);
            check(c.regionalCampaign(0, 102), "3 farms must be a campaign");
            check(!c.regionalCampaign(1, 102), "other region");
            check(c.getSightings().size() == 1, "one sighting");
            check(!c.regionalCampaign(0, 130), "old suspects expire");
            return "campaign found at 3 farms, sighting shared, expires after 2 h";
        });

        test("UT13", "Simulator is deterministic for a fixed seed", () -> {
            Dataset a = new TelemetryGenerator(11).generate(), b = new TelemetryGenerator(11).generate();
            for (int n = 0; n < Config.NODES; n += 5) {
                for (int t = 0; t < Config.TOTAL_STEPS; t += 97) check(Arrays.equals(a.values[n][t], b.values[n][t]), "differs");
            }
            check(a.getEpisodes().size() == b.getEpisodes().size(), "episodes");
            return a.getEpisodes().size() + " attack episodes, identical readings";
        });

        test("UT14", "Generic RingBuffer keeps only the newest items", () -> {
            RingBuffer<Integer> b = new RingBuffer<>(3);
            for (int i = 1; i <= 5; i++) b.add(i);
            check(b.latest(10).equals(Arrays.asList(3, 4, 5)), b.latest(10).toString());
            return "[3, 4, 5]";
        });

        // ---------------------------------------------------------- MySQL tests (on the separate agro_cti_test database)
        boolean db;
        try {
            Database.init(dbProps, true);
            Database.dropAll();
            DataSeeder.seed(List.of(new ThreatIndicator("ind-000", "198.51.100.7", 90, "test")));
            db = true;
        } catch (Exception e) {
            db = false;
            failed += 6;
            System.out.println("  FAIL  UT15-UT20 MySQL is not reachable, the 6 database tests could not run: " + e.getMessage());
        }
        if (db) databaseTests();

        System.out.printf("%d passed, %d failed%n", passed, failed);
        return failed == 0;
    }

    /** Expects a ValidationException naming the given field. */
    private interface Action {
        Object run() throws Exception;
    }

    private static void expectInvalid(String field, Action c) throws Exception {
        try {
            c.run();
        } catch (ValidationException e) {
            if (field != null && !field.equals(e.getField())) throw new AssertionError("wrong field " + e.getField() + ": " + e.getMessage());
            return;
        }
        throw new AssertionError("a ValidationException for '" + field + "' was expected");
    }

    private static void databaseTests() {
        AgroCTI_User users = new AgroCTI_User();
        AgroCTI_FarmNode nodes = new AgroCTI_FarmNode();
        AgroCTI_ThreatIndicator inds = new AgroCTI_ThreatIndicator();
        AgroCTI_Incident incs = new AgroCTI_Incident();
        AgroCTI_ResponseAction acts = new AgroCTI_ResponseAction();

        test("UT15", "Incidents (JDBC): detector insert/update keeps the analyst's status", () -> {
            Alert a = new Alert(3, 2600, Severity.ALERT, 1.5, "Drift / bias injection", "test");
            incs.save(a);
            incs.AP25110090170_incident_update(a.getId(), IncidentStatus.INVESTIGATING, "checking", null, Severity.ALERT, a.getGuess());
            a.extend(2603, Severity.CRITICAL, 2.5, "more");
            incs.save(a);                                   // the detector updates the running incident
            Alert back = incs.findById(a.getId()).orElseThrow();
            check(back.getEndStep() == 2603 && back.getSeverity() == Severity.CRITICAL && back.getReadings() == 2, "detector columns");
            check(back.getStatus() == IncidentStatus.INVESTIGATING && back.getNote().equals("checking"), "analyst columns kept");
            expectInvalid("note", () -> incs.AP25110090170_incident_update(a.getId(), IncidentStatus.RESOLVED, "", null, Severity.ALERT, "x"));
            return "id " + a.getId() + " inserted, updated twice; closing without a note refused";
        });

        test("UT16", "Farm nodes: create, retrieve (search+sort), update, delete, rules", () -> {
            check(nodes.count() == Config.NODES, "24 seeded nodes");
            FarmNode n = nodes.AP25110090046_farmnode_create(new FarmNode(null, "gun-f9", "Guntur", "Banana", "10.1.9.1", "v1.4.2", NodeStatus.ACTIVE, null));
            check(n.getId() > 0 && n.getCode().equals("GUN-F9"), "created and upper-cased");
            expectInvalid("code", () -> nodes.AP25110090046_farmnode_create(new FarmNode(null, "GUN-F9", "Guntur", "Rice", "10.1.9.2", "v1.0.0", NodeStatus.ACTIVE, null)));
            expectInvalid("gatewayIp", () -> nodes.AP25110090046_farmnode_create(new FarmNode(null, "GUN-F10", "Guntur", "Rice", "10.1.300.2", "v1.0.0", NodeStatus.ACTIVE, null)));
            expectInvalid("region", () -> nodes.AP25110090046_farmnode_create(new FarmNode(null, "XYZ-F1", "Mars", "Rice", "10.1.3.2", "v1.0.0", NodeStatus.ACTIVE, null)));
            check(nodes.AP25110090046_farmnode_retrive("banana", null, false).size() == 1, "search");
            List<FarmNode> byCode = nodes.AP25110090046_farmnode_retrive(null, "code", true);
            check(byCode.get(0).getCode().compareTo(byCode.get(byCode.size() - 1).getCode()) > 0, "sort descending");
            n.setStatus(NodeStatus.MAINTENANCE);
            nodes.AP25110090046_farmnode_update(n);
            check(nodes.findById(n.getId()).orElseThrow().getStatus() == NodeStatus.MAINTENANCE, "update");
            FarmNode monitored = nodes.AP25110090046_farmnode_retrive("GUN-F1", null, false).get(0);
            expectInvalid(null, () -> { nodes.AP25110090046_farmnode_delete(monitored.getId()); return ""; });
            nodes.AP25110090046_farmnode_delete(n.getId());
            check(nodes.findById(n.getId()).isEmpty(), "deleted");
            return "duplicate code, bad IP, bad region and deleting a monitored node all refused";
        });

        test("UT17", "Threat indicators: create with next id, duplicate IP refused, update, delete", () -> {
            List<Integer> pushed = new ArrayList<>();
            inds.setFeedListener(list -> pushed.add(list.size()));
            ThreatIndicator t = inds.AP25110090062_indicator_create(new ThreatIndicator("", "203.0.113.200", 80, "Analyst"));
            check(t.getId().equals("ind-001"), "next id was " + t.getId());
            expectInvalid("value", () -> inds.AP25110090062_indicator_create(new ThreatIndicator("", "198.51.100.7", 60, "x")));
            expectInvalid("confidence", () -> inds.AP25110090062_indicator_create(new ThreatIndicator("", "203.0.113.201", 140, "x")));
            inds.AP25110090062_indicator_update(new ThreatIndicator(t.getId(), "203.0.113.201", 95, "Analyst"));
            check(inds.findById(t.getId()).orElseThrow().getConfidence() == 95, "updated");
            inds.AP25110090062_indicator_delete(t.getId());
            check(inds.findById(t.getId()).isEmpty() && pushed.equals(Arrays.asList(2, 2, 1)), "pipeline told " + pushed);
            return "ind-001 created, updated, deleted; feed pushed to pipeline 3 times";
        });

        test("UT18", "Response actions: transaction moves incident to INVESTIGATING; cascade delete", () -> {
            Alert a = incs.save(new Alert(5, 2700, Severity.ALERT, 1.2, "Replay attack", "test"));
            int analyst = users.AP25110090060_user_retrive("analyst", null, false).get(0).getId();
            int farmer = users.AP25110090060_user_retrive("farmer", null, false).get(0).getId();
            ResponseAction r = acts.AP25110090048_action_create(new ResponseAction(a.getId(), ActionType.BLOCK_IP, "Block 203.0.113.9", analyst, ActionStatus.PLANNED, "2026-07-20"));
            check(incs.findById(a.getId()).orElseThrow().getStatus() == IncidentStatus.INVESTIGATING, "status moved");
            expectInvalid("performedBy", () -> acts.AP25110090048_action_create(new ResponseAction(a.getId(), ActionType.OTHER, "farmer task", farmer, ActionStatus.PLANNED, "")));
            expectInvalid("dueDate", () -> acts.AP25110090048_action_create(new ResponseAction(a.getId(), ActionType.OTHER, "bad date", analyst, ActionStatus.PLANNED, "20-07-2026")));
            r.setStatus(ActionStatus.DONE);
            acts.AP25110090048_action_update(r);
            check(acts.findById(r.getId()).orElseThrow().getStatus() == ActionStatus.DONE, "updated");
            incs.AP25110090170_incident_update(a.getId(), IncidentStatus.RESOLVED, "IP blocked", analyst, Severity.ALERT, a.getGuess());
            expectInvalid("incidentId", () -> acts.AP25110090048_action_create(new ResponseAction(a.getId(), ActionType.OTHER, "too late", analyst, ActionStatus.PLANNED, "")));
            check(incs.AP25110090170_incident_delete(a.getId()) == 1 && acts.findById(r.getId()).isEmpty(), "cascade");
            return "status OPEN -> INVESTIGATING, closed incident refused, action deleted with incident";
        });

        test("UT19", "Users: password hash, login, duplicate name, last-admin rule", () -> {
            UserAccount u = users.AP25110090060_user_create(new UserAccount("Test_User", "Test User", Role.ANALYST, "secret123"));
            check(u.getUsername().equals("test_user") && u.getPasswordHash().length() == 64 && !u.getPasswordHash().contains("secret"), "hashed");
            check(users.authenticate("test_user", "secret123").isPresent(), "login ok");
            check(users.authenticate("test_user", "wrong-pass1").isEmpty(), "wrong password refused");
            expectInvalid("username", () -> users.AP25110090060_user_create(new UserAccount("test_user", "Again", Role.FARMER, "secret123")));
            expectInvalid("password", () -> users.AP25110090060_user_create(new UserAccount("short_pw", "Short", Role.FARMER, "abc")));
            UserAccount admin = users.AP25110090060_user_retrive("admin", null, false).get(0);
            admin.setRole(Role.ANALYST);
            expectInvalid("role", () -> users.AP25110090060_user_update(admin));
            expectInvalid(null, () -> { users.AP25110090060_user_delete(u.getId(), u); return ""; });
            users.AP25110090060_user_delete(u.getId(), null);
            check(users.findById(u.getId()).isEmpty(), "deleted");
            return "PBKDF2 hash stored, wrong password refused, rules enforced";
        });

        test("UT20", "Search is parameterised and sort column is whitelisted (no SQL injection)", () -> {
            check(nodes.AP25110090046_farmnode_retrive("' OR '1'='1", null, false).isEmpty(), "injection text matched nothing");
            try {
                nodes.AP25110090046_farmnode_retrive(null, "node_code; DROP TABLE users", false);
                throw new AssertionError("bad sort column accepted");
            } catch (IllegalArgumentException expected) {
                // refused
            }
            check(users.count() == 3, "users table still there");
            return "quote text found 0 rows, unknown sort column refused";
        });
    }
}
