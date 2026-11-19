package com.agrocti.cti;

import com.agrocti.config.Config;
import com.agrocti.model.ThreatIndicator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.TreeSet;

/**
 * Correlates suspicious readings across the farms of a region. Several farms of the
 * same region becoming suspicious within the same two hours is very unlikely by chance,
 * so it is treated as a coordinated (regional) spoofing campaign. Detected campaigns are
 * written out as new indicators that can be shared with neighbouring regions.
 */
public class CtiCorrelator {

    public static final int WINDOW_STEPS = 8;
    public static final int MIN_FARMS = 3;

    private final List<Deque<int[]>> recent = new ArrayList<>();   // per region: {step, node}
    private final int[] campaignUntil;
    private final List<ThreatIndicator> sightings = new ArrayList<>();

    public CtiCorrelator() {
        for (int r = 0; r < Config.REGIONS.length; r++) recent.add(new ArrayDeque<>());
        campaignUntil = new int[Config.REGIONS.length];
        java.util.Arrays.fill(campaignUntil, -1);
    }

    public synchronized void reset() {
        for (Deque<int[]> d : recent) d.clear();
        java.util.Arrays.fill(campaignUntil, -1);
        sightings.clear();
    }

    public synchronized void registerSuspect(int node, int step) {
        recent.get(Config.regionOf(node)).addLast(new int[]{step, node});
    }

    /** Distinct farms of the region that were suspicious in the last WINDOW_STEPS steps. */
    public synchronized TreeSet<Integer> suspiciousFarms(int region, int step) {
        Deque<int[]> d = recent.get(region);
        while (!d.isEmpty() && d.peekFirst()[0] <= step - WINDOW_STEPS) d.removeFirst();
        TreeSet<Integer> farms = new TreeSet<>();
        for (int[] e : d) farms.add(e[1]);
        return farms;
    }

    /** True if a regional campaign is in progress; records a sighting when one starts. */
    public synchronized boolean regionalCampaign(int region, int step) {
        TreeSet<Integer> farms = suspiciousFarms(region, step);
        if (farms.size() >= MIN_FARMS) {
            if (campaignUntil[region] < step) {
                StringBuilder names = new StringBuilder();
                for (int n : farms) names.append(names.length() == 0 ? "" : " ").append(Config.nodeName(n));
                sightings.add(new ThreatIndicator(String.format("local-%03d", sightings.size() + 1), "regional-advisory",
                        "sensor-spoofing: " + names, region, step, step + 24, 50 + 10 * Math.min(4, farms.size() - 2), "Agro-CTI local"));
            }
            campaignUntil[region] = step + WINDOW_STEPS;
            return true;
        }
        return campaignUntil[region] >= step;
    }

    public synchronized List<ThreatIndicator> getSightings() {
        return new ArrayList<>(sightings);
    }
}
