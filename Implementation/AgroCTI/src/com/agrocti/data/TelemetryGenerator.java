package com.agrocti.data;

import com.agrocti.config.Config;
import com.agrocti.model.ActuatorEvent;
import com.agrocti.model.AttackEpisode;
import com.agrocti.model.AttackType;
import com.agrocti.model.ContextEvent;
import com.agrocti.model.ThreatIndicator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static com.agrocti.config.Config.*;

/**
 * Simulates the smart-farm network: regional weather, soil physics,
 * an automatic irrigation controller on every farm, the actuator log,
 * and the attacks of the test period. Each region is simulated on its own
 * thread; the result only depends on the seed.
 */
public class TelemetryGenerator {

    private final long seed;
    private final AtomicInteger episodeIds = new AtomicInteger(1);
    private final List<String> usedAttackerIps = Collections.synchronizedList(new ArrayList<>());
    private final List<int[]> campaigns = Collections.synchronizedList(new ArrayList<>()); // {id, region, start, end, pattern}

    public TelemetryGenerator(long seed) {
        this.seed = seed;
    }

    public Dataset generate() throws InterruptedException, ExecutionException {
        Dataset ds = new Dataset(NODES, TOTAL_STEPS, NF);
        ExecutorService pool = Executors.newFixedThreadPool(REGIONS.length);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int r = 0; r < REGIONS.length; r++) {
                results.add(pool.submit(new RegionSimulation(r, ds)));
            }
            for (Future<Integer> f : results) {
                f.get();                        // waits, and re-throws any error of that thread
            }
        } finally {
            pool.shutdown();
        }
        ds.getEpisodes().sort((a, b) -> Integer.compare(a.getId(), b.getId()));
        buildFeed(ds);
        return ds;
    }

    // ------------------------------------------------------------------ threat feed
    private void buildFeed(Dataset ds) {
        Random fr = new Random(seed * 17 + 5);
        List<ThreatIndicator> feed = ds.getFeed();
        int n = 1;
        List<String> ips = new ArrayList<>(new LinkedHashSet<>(usedAttackerIps));
        Collections.sort(ips);
        for (String ip : ips) {
            if (fr.nextDouble() < 0.7) {                     // the feed knows 70 % of the attackers
                feed.add(new ThreatIndicator(String.format("ind-%03d", n++), ip, 70 + fr.nextInt(30), "AgriCERT-Sim"));
            }
        }
        for (int i = 0; i < 15; i++) {                       // addresses that never touch our farms
            feed.add(new ThreatIndicator(String.format("ind-%03d", n++), randomAttackerIp(fr), 50 + fr.nextInt(40), "OSINT-Agri"));
        }
        List<int[]> cs = new ArrayList<>(campaigns);
        cs.sort((a, b) -> Integer.compare(a[0], b[0]));
        for (int[] c : cs) {
            if (fr.nextDouble() < 0.6) {                     // 60 % of campaigns are reported by others
                feed.add(new ThreatIndicator(String.format("ind-%03d", n++), "regional-advisory",
                        c[4] == 0 ? "drift" : "constant", c[1], c[2] + 6, c[3] + 12, 60 + fr.nextInt(30), "AgriCERT-Sim"));
            }
        }
        for (int i = 0; i < 2; i++) {                        // false alarms in the feed
            int s = VAL_END + STEPS_PER_DAY + fr.nextInt(TEST_DAYS * STEPS_PER_DAY - 3 * STEPS_PER_DAY);
            feed.add(new ThreatIndicator(String.format("ind-%03d", n++), "regional-advisory", "drift",
                    fr.nextInt(REGIONS.length), s, s + 48, 40 + fr.nextInt(20), "OSINT-Agri"));
        }
    }

    private static String randomAttackerIp(Random r) {
        // reserved documentation and benchmarking ranges (RFC 5737, RFC 2544): never real hosts
        String[] prefixes = {"192.0.2.", "198.51.100.", "203.0.113.", "198.18.7.", "198.19.42."};
        return prefixes[r.nextInt(prefixes.length)] + (2 + r.nextInt(250));
    }

    // ------------------------------------------------------------------ one region
    private final class RegionSimulation implements Callable<Integer> {
        private final int region;
        private final Dataset ds;
        private final double[] airT = new double[TOTAL_STEPS];
        private final double[] rh = new double[TOTAL_STEPS];
        private final double[] rain = new double[TOTAL_STEPS];
        private final boolean[] heavy = new boolean[TOTAL_STEPS];
        private final List<List<AttackEpisode>> plans = new ArrayList<>();

        RegionSimulation(int region, Dataset ds) {
            this.region = region;
            this.ds = ds;
        }

        @Override
        public Integer call() {
            weather();
            planAttacks();
            for (int f = 0; f < FARMS_PER_REGION; f++) {
                simulateFarm(region * FARMS_PER_REGION + f, plans.get(f));
            }
            return region;
        }

        private void weather() {
            Random wr = new Random(seed * 31 + region * 1000L);
            int days = TOTAL_STEPS / STEPS_PER_DAY;
            double[] dayOff = new double[days];
            for (int d = 0; d < days; d++) {
                dayOff[d] = wr.nextGaussian() * 1.5;
                if (wr.nextDouble() < 0.18) {                       // ordinary afternoon shower
                    int start = d * STEPS_PER_DAY + 52 + wr.nextInt(24);
                    int dur = 2 + wr.nextInt(7);
                    for (int k = 0; k < dur && start + k < TOTAL_STEPS; k++) {
                        rain[start + k] += 0.5 + wr.nextDouble() * 2.5;
                    }
                }
            }
            for (int i = 0; i < 2; i++) {                           // two heavy storms in the test period
                int d = TRAIN_DAYS + VAL_DAYS + 1 + wr.nextInt(TEST_DAYS - 2);
                int start = d * STEPS_PER_DAY + 48 + wr.nextInt(32);
                int dur = 4 + wr.nextInt(5);
                for (int k = 0; k < dur; k++) {
                    rain[start + k] += 6 + wr.nextDouble() * 6;
                    heavy[start + k] = true;
                }
            }
            double cooling = 0;
            for (int t = 0; t < TOTAL_STEPS; t++) {
                double h = hourOf(t);
                cooling = rain[t] > 0 ? Math.min(4, cooling + 1.0) : cooling * 0.85;
                airT[t] = 29 + 0.05 * (t / STEPS_PER_DAY) + dayOff[t / STEPS_PER_DAY]
                        + 6 * Math.sin(2 * Math.PI * (h - 9) / 24) - cooling + wr.nextGaussian() * 0.3;
                double v = 68 - 1.8 * (airT[t] - 29) + (rain[t] > 0 ? 22 : 0) + 4 * cooling + wr.nextGaussian() * 2;
                rh[t] = Math.max(25, Math.min(100, v));
            }
        }

        private void planAttacks() {
            Random ar = new Random(seed * 131 + region * 7919L);
            for (int f = 0; f < FARMS_PER_REGION; f++) {
                plans.add(new ArrayList<>());
            }
            int testStart = VAL_END + STEPS_PER_DAY / 2;
            int testLen = TOTAL_STEPS - testStart - 80;
            // regional coordinated campaigns first (they need several farms free at the same time)
            for (int c = 0; c < 2; c++) {
                int campaignId = region * 10 + c;
                int start = testStart + ar.nextInt(testLen);
                int dur = 32 + ar.nextInt(25);
                int pattern = ar.nextInt(2);                // 0 = drift, 1 = constant with noise
                double rate = 0.05 + ar.nextDouble() * 0.04;
                List<Integer> farms = new ArrayList<>();
                for (int f = 0; f < FARMS_PER_REGION; f++) farms.add(f);
                Collections.shuffle(farms, ar);
                int count = 3 + ar.nextInt(3);
                int minS = Integer.MAX_VALUE, maxE = 0;
                for (int i = 0; i < count; i++) {
                    int f = farms.get(i);
                    int s = start + ar.nextInt(3);
                    if (clashes(plans.get(f), s, s + dur)) continue;
                    add(f, AttackType.REGIONAL_CAMPAIGN, s, s + dur, campaignId,
                            pattern == 0 ? "drift +" + String.format("%.3f", rate) + "/step" : "constant with noise",
                            new double[]{pattern, rate});
                    minS = Math.min(minS, s);
                    maxE = Math.max(maxE, s + dur);
                }
                if (maxE > 0) campaigns.add(new int[]{campaignId, region, minS, maxE, pattern});
            }
            AttackType[] kinds = {AttackType.CONSTANT_SPOOF, AttackType.REPLAY_SPOOF, AttackType.DRIFT_SPOOF,
                    AttackType.SPIKE_INJECTION, AttackType.ACTUATOR_INJECTION};
            for (int f = 0; f < FARMS_PER_REGION; f++) {
                List<AttackType> pick = new ArrayList<>(Arrays.asList(kinds));
                Collections.shuffle(pick, ar);
                int n = 2 + ar.nextInt(2);
                for (int i = 0; i < n; i++) {
                    AttackType type = pick.get(i);
                    for (int tries = 0; tries < 60; tries++) {
                        int s = testStart + ar.nextInt(testLen);
                        int dur;
                        double[] p;
                        String detail;
                        switch (type) {
                            case CONSTANT_SPOOF:
                                dur = 24 + ar.nextInt(49);
                                boolean noisy = ar.nextBoolean();
                                p = new double[]{noisy ? 1 : 0};
                                detail = noisy ? "fixed value with added noise" : "stuck-at value";
                                break;
                            case REPLAY_SPOOF:
                                dur = 16 + ar.nextInt(25);
                                p = new double[0];
                                detail = "replays readings from 24 h earlier";
                                break;
                            case DRIFT_SPOOF:
                                dur = 24 + ar.nextInt(33);
                                double rate = 0.08 + ar.nextDouble() * 0.12;
                                p = new double[]{rate};
                                detail = String.format("moisture bias +%.2f per step", rate);
                                break;
                            case SPIKE_INJECTION:
                                dur = 12;
                                p = new double[]{ar.nextLong()};
                                detail = "3-4 false spikes in 3 hours";
                                break;
                            default:
                                boolean burst = ar.nextBoolean();
                                dur = burst ? 3 + ar.nextInt(4) : 4 + ar.nextInt(3);
                                String ip = randomAttackerIp(ar);
                                usedAttackerIps.add(ip);
                                p = new double[]{burst ? 1 : 0, 0};
                                detail = (burst ? "command burst from " : "single stealthy OPEN from ") + ip;
                                break;
                        }
                        if (!clashes(plans.get(f), s, s + dur)) {
                            add(f, type, s, s + dur, -1, detail, p);
                            break;
                        }
                    }
                }
            }
        }

        private boolean clashes(List<AttackEpisode> plan, int s, int e) {
            for (AttackEpisode ep : plan) {
                if (ep.overlaps(s, e, 24)) return true;
            }
            return false;
        }

        private void add(int farm, AttackType type, int s, int e, int campaign, String detail, double[] params) {
            int node = region * FARMS_PER_REGION + farm;
            AttackEpisode ep = new AttackEpisode(episodeIds.getAndIncrement(), type, node, s, e, campaign, detail);
            plans.get(farm).add(ep);
            paramsOf.put(ep, params);
        }

        private final java.util.Map<AttackEpisode, double[]> paramsOf = new java.util.HashMap<>();

        // -------------------------------------------------------------- one farm
        private void simulateFarm(int node, List<AttackEpisode> plan) {
            Random fr = new Random(seed * 7 + node * 104729L + 1);
            double fc = 34 + fr.nextDouble() * 6;           // field capacity, %
            double trigger = 22 + fr.nextDouble() * 4;      // irrigate below this, %
            double ec0 = 0.8 + fr.nextDouble() * 0.8;
            double m = 28 + fr.nextDouble() * 6;
            double st = 27;
            String gw = gatewayIp(node);

            // planned manual irrigations by the farmer (harmless, at unusual hours)
            boolean[] manual = new boolean[TOTAL_STEPS];
            double[] manualFlow = new double[TOTAL_STEPS];
            for (int d = 0; d < TOTAL_STEPS / STEPS_PER_DAY; d++) {
                double p = d >= TRAIN_DAYS + VAL_DAYS ? 0.06 : 0.02;
                if (fr.nextDouble() < p) {
                    int s = d * STEPS_PER_DAY + (fr.nextBoolean() ? 44 + fr.nextInt(12) : 88 + fr.nextInt(4));
                    int dur = 4 + fr.nextInt(3);
                    double fl = 10 + fr.nextDouble() * 6;
                    for (int k = 0; k < dur && s + k < TOTAL_STEPS; k++) {
                        manual[s + k] = true;
                        manualFlow[s + k] = fl;
                    }
                }
            }

            int irrigating = 0;
            double flowSet = 0;
            boolean manualOn = false, attackOn = false;
            double[] constantValue = new double[plan.size()];
            Random spikeRng = null;
            boolean[] spikeAt = new boolean[TOTAL_STEPS];
            for (AttackEpisode ep : plan) {
                if (ep.getType() == AttackType.SPIKE_INJECTION) {
                    Random sr = new Random((long) paramsOf.get(ep)[0]);
                    int count = 3 + sr.nextInt(2);
                    for (int i = 0; i < count; i++) spikeAt[ep.getStart() + sr.nextInt(ep.getEnd() - ep.getStart())] = true;
                    spikeRng = sr;
                }
                ds.addEpisode(ep);
            }

            for (int t = 0; t < TOTAL_STEPS; t++) {
                double h = hourOf(t);
                double[] v = ds.values[node][t];
                AttackEpisode act = active(plan, t, AttackType.ACTUATOR_INJECTION);

                // ---------- irrigation controller (reads the REPORTED moisture of the previous step)
                double reportedPrev = t > 0 ? ds.values[node][t - 1][SM] : m;
                boolean inWindow = (h >= 5 && h < 8) || (h >= 17 && h < 19);
                int cmds = 0;
                if (irrigating == 0 && reportedPrev < trigger && inWindow) {
                    irrigating = 6;
                    flowSet = 12 + fr.nextDouble() * 6;
                    String src = fr.nextDouble() < 0.8 ? gw : CONTROLLER_IP;
                    ds.addEvent(new ActuatorEvent(t, node, "OPEN", src));
                    cmds++;
                }
                if (manual[t] && !manualOn) {
                    ds.addEvent(new ActuatorEvent(t, node, "OPEN", gw));
                    cmds++;
                    manualOn = true;
                } else if (!manual[t] && manualOn) {
                    ds.addEvent(new ActuatorEvent(t, node, "CLOSE", gw));
                    cmds++;
                    manualOn = false;
                }
                double attackFlow = 0;
                if (act != null) {
                    double[] p = paramsOf.get(act);
                    String ip = act.getDetail().substring(act.getDetail().lastIndexOf(' ') + 1);
                    if (p[0] == 1) {                                   // burst
                        int burst = 6 + fr.nextInt(10);
                        for (int k = 0; k < burst; k++) ds.addEvent(new ActuatorEvent(t, node, k % 2 == 0 ? "OPEN" : "SET_FLOW", ip));
                        cmds += burst;
                        attackFlow = 20 + fr.nextDouble() * 5;
                    } else {                                           // stealthy
                        if (t == act.getStart()) {
                            ds.addEvent(new ActuatorEvent(t, node, "OPEN", ip));
                            cmds++;
                            p[1] = 12 + fr.nextDouble() * 4;
                        }
                        attackFlow = p[1];
                    }
                    ds.labels[node][t] = AttackType.ACTUATOR_INJECTION;
                    attackOn = true;
                } else if (attackOn) {
                    AttackEpisode prev = activeEnded(plan, t);
                    String ip = prev == null ? CONTROLLER_IP : prev.getDetail().substring(prev.getDetail().lastIndexOf(' ') + 1);
                    ds.addEvent(new ActuatorEvent(t, node, "CLOSE", ip));
                    cmds++;
                    attackOn = false;
                }
                while (fr.nextDouble() < 0.13) {                        // routine status polls
                    ds.addEvent(new ActuatorEvent(t, node, "STATUS", CONTROLLER_IP));
                    cmds++;
                }
                double flow = 0;
                if (irrigating > 0) {
                    flow = flowSet;
                    irrigating--;
                    if (irrigating == 0) {
                        ds.addEvent(new ActuatorEvent(t, node, "CLOSE", gw));
                        cmds++;
                    }
                }
                if (manual[t]) {
                    flow = Math.max(flow, manualFlow[t]);
                    ds.context[node][t] = ContextEvent.MANUAL_IRRIGATION;
                }
                flow = Math.max(flow, attackFlow);
                if (heavy[t] || (t >= 12 && heavyRecent(t))) ds.context[node][t] = ContextEvent.HEAVY_STORM;

                // ---------- soil physics
                double et = (0.03 + 0.05 * Math.max(0, Math.sin(Math.PI * (h - 6) / 12))) * (1 + 0.03 * (airT[t] - 29));
                m += 0.55 * rain[t] + 0.12 * flow - et;
                if (m > fc) m -= 0.25 * (m - fc);
                m = Math.max(5, m);
                st += 0.15 * ((airT[t] - 3 - 0.08 * (m - 30)) - st);

                // ---------- what the sensors report
                v[SM] = m + fr.nextGaussian() * 0.25;
                v[ST] = st + fr.nextGaussian() * 0.15;
                v[AT] = airT[t] + fr.nextGaussian() * 0.2;
                v[RH] = rh[t];
                v[RAIN] = rain[t];
                v[EC] = ec0 + 0.025 * (fc - m) + fr.nextGaussian() * 0.02;
                v[FLOW] = flow > 0 ? flow + fr.nextGaussian() * 0.3 : Math.abs(fr.nextGaussian() * 0.03);
                v[CMD] = cmds;

                // ---------- sensor spoofing (changes the reported values only)
                for (int i = 0; i < plan.size(); i++) {
                    AttackEpisode ep = plan.get(i);
                    if (!ep.covers(t)) continue;
                    double[] p = paramsOf.get(ep);
                    int k = t - ep.getStart();
                    switch (ep.getType()) {
                        case CONSTANT_SPOOF:
                            if (k == 0) constantValue[i] = Math.round(v[SM] * 10) / 10.0 + 1.5;
                            v[SM] = constantValue[i] + (p[0] == 1 ? fr.nextGaussian() * 0.25 : 0);
                            ds.labels[node][t] = ep.getType();
                            break;
                        case REPLAY_SPOOF:
                            v[SM] = ds.values[node][t - STEPS_PER_DAY][SM];
                            v[ST] = ds.values[node][t - STEPS_PER_DAY][ST];
                            ds.labels[node][t] = ep.getType();
                            break;
                        case DRIFT_SPOOF:
                            v[SM] += p[0] * (k + 1);
                            ds.labels[node][t] = ep.getType();
                            break;
                        case SPIKE_INJECTION:
                            if (spikeAt[t]) {
                                if (spikeRng.nextBoolean()) v[SM] += 12 + spikeRng.nextDouble() * 13;
                                else v[ST] += 8 + spikeRng.nextDouble() * 7;
                                ds.labels[node][t] = ep.getType();
                            }
                            break;
                        case REGIONAL_CAMPAIGN:
                            if (p[0] == 0) {
                                v[SM] += p[1] * (k + 1);
                            } else {
                                if (k == 0) constantValue[i] = Math.round(v[SM] * 10) / 10.0 + 1.0;
                                v[SM] = constantValue[i] + fr.nextGaussian() * 0.25;
                            }
                            ds.labels[node][t] = ep.getType();
                            break;
                        default:
                            break;
                    }
                }
            }
        }

        /** True during a heavy storm and the 3 hours after it (the soil is still draining). */
        private boolean heavyRecent(int t) {
            for (int k = 0; k <= 12; k++) if (heavy[t - k]) return true;
            return false;
        }

        private AttackEpisode active(List<AttackEpisode> plan, int t, AttackType type) {
            for (AttackEpisode ep : plan) {
                if (ep.getType() == type && ep.covers(t)) return ep;
            }
            return null;
        }

        private AttackEpisode activeEnded(List<AttackEpisode> plan, int t) {
            for (AttackEpisode ep : plan) {
                if (ep.getType() == AttackType.ACTUATOR_INJECTION && ep.getEnd() == t) return ep;
            }
            return null;
        }
    }

    /** Set of addresses that legitimately control the farms. */
    public static Set<String> trustedSources() {
        Set<String> s = new HashSet<>();
        s.add(CONTROLLER_IP);
        for (int n = 0; n < NODES; n++) s.add(gatewayIp(n));
        return s;
    }
}
