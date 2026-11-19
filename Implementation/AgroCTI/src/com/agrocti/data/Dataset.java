package com.agrocti.data;

import com.agrocti.model.ActuatorEvent;
import com.agrocti.model.AttackEpisode;
import com.agrocti.model.AttackType;
import com.agrocti.model.ContextEvent;
import com.agrocti.model.ThreatIndicator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Telemetry of every farm node at every time step, the actuator log,
 * the ground-truth labels and the list of attack episodes.
 */
public class Dataset {

    public final int nodes;
    public final int steps;
    /** values[node][step][feature] */
    public final double[][][] values;
    public final AttackType[][] labels;
    public final ContextEvent[][] context;

    private final Map<Integer, List<ActuatorEvent>> events = new HashMap<>();
    private final List<AttackEpisode> episodes = new ArrayList<>();
    private final List<ThreatIndicator> feed = new ArrayList<>();

    public Dataset(int nodes, int steps, int features) {
        this.nodes = nodes;
        this.steps = steps;
        values = new double[nodes][steps][features];
        labels = new AttackType[nodes][steps];
        context = new ContextEvent[nodes][steps];
        for (int n = 0; n < nodes; n++) {
            java.util.Arrays.fill(labels[n], AttackType.NONE);
            java.util.Arrays.fill(context[n], ContextEvent.NONE);
        }
    }

    private static int key(int node, int step) {
        return node * 100_000 + step;
    }

    public synchronized void addEvent(ActuatorEvent e) {
        events.computeIfAbsent(key(e.getNode(), e.getStep()), k -> new ArrayList<>()).add(e);
    }

    /** Commands sent to a node at one step (empty list if none). */
    public List<ActuatorEvent> eventsAt(int node, int step) {
        List<ActuatorEvent> l = events.get(key(node, step));
        return l == null ? Collections.emptyList() : l;
    }

    public List<ActuatorEvent> allEvents() {
        List<ActuatorEvent> all = new ArrayList<>();
        for (List<ActuatorEvent> l : events.values()) {
            all.addAll(l);
        }
        all.sort((a, b) -> a.getStep() != b.getStep() ? Integer.compare(a.getStep(), b.getStep())
                : Integer.compare(a.getNode(), b.getNode()));
        return all;
    }

    public synchronized void addEpisode(AttackEpisode e) {
        episodes.add(e);
    }

    public List<AttackEpisode> getEpisodes() {
        return episodes;
    }

    public List<ThreatIndicator> getFeed() {
        return feed;
    }

    public double[] reading(int node, int step) {
        return values[node][step];
    }
}
