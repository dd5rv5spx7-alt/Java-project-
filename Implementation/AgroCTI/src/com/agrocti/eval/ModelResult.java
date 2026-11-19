package com.agrocti.eval;

import com.agrocti.ml.Metrics;
import com.agrocti.model.AttackType;
import com.agrocti.util.Json;

import java.util.EnumMap;
import java.util.Map;

/** Everything measured for one detector on the test period. */
public class ModelResult implements Json.Writable {

    public final String name;
    public final String kind;            // baseline, proposed or ablation
    public final Metrics metrics = new Metrics();
    public long parameters;
    public long memoryBytes;
    public double latencyMicros;
    public double threshold;
    /** F1 with the best possible threshold chosen using the test labels (an upper bound, not a fair result). */
    public double oracleF1 = -1;
    /** per attack type: {readings flagged, readings of that type} */
    public final Map<AttackType, long[]> perType = new EnumMap<>(AttackType.class);
    /** per attack type: {episodes detected, episodes, sum of detection delays in steps} */
    public final Map<AttackType, long[]> episodes = new EnumMap<>(AttackType.class);
    public long stormFp, stormTotal, manualFp, manualTotal;

    public ModelResult(String name, String kind) {
        this.name = name;
        this.kind = kind;
        for (AttackType t : AttackType.values()) {
            if (t.isAttack()) {
                perType.put(t, new long[2]);
                episodes.put(t, new long[3]);
            }
        }
    }

    public double episodeRate() {
        long d = 0, n = 0;
        for (long[] e : episodes.values()) { d += e[0]; n += e[1]; }
        return n == 0 ? 0 : (double) d / n;
    }

    public double meanDelayMinutes() {
        long d = 0, s = 0;
        for (long[] e : episodes.values()) { d += e[0]; s += e[2]; }
        return d == 0 ? 0 : 15.0 * s / d;
    }

    @Override
    public String toJson() {
        StringBuilder types = new StringBuilder("{");
        for (Map.Entry<AttackType, long[]> e : perType.entrySet()) {
            long[] ep = episodes.get(e.getKey());
            if (types.length() > 1) types.append(',');
            types.append(Json.quote(e.getKey().name())).append(':').append(Json.object()
                    .put("label", e.getKey().label())
                    .put("readingRecall", e.getValue()[1] == 0 ? 0 : (double) e.getValue()[0] / e.getValue()[1])
                    .put("readings", e.getValue()[1])
                    .put("episodesDetected", ep[0]).put("episodes", ep[1])
                    .put("meanDelayMin", ep[0] == 0 ? 0 : 15.0 * ep[2] / ep[0]).build());
        }
        types.append('}');
        return Json.object().put("name", name).put("kind", kind)
                .put("f1", metrics.f1()).put("precision", metrics.precision()).put("recall", metrics.recall())
                .put("fpr", metrics.fpr()).put("accuracy", metrics.accuracy())
                .put("tp", metrics.tp).put("fp", metrics.fp).put("tn", metrics.tn).put("fn", metrics.fn)
                .put("parameters", parameters).put("memoryBytes", memoryBytes).put("latencyMicros", latencyMicros)
                .put("threshold", threshold).put("oracleF1", oracleF1).put("episodeRate", episodeRate()).put("meanDelayMin", meanDelayMinutes())
                .put("stormFp", stormFp).put("stormTotal", stormTotal).put("manualFp", manualFp).put("manualTotal", manualTotal)
                .raw("perType", types.toString()).build();
    }
}
