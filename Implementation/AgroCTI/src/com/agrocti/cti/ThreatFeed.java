package com.agrocti.cti;

import com.agrocti.config.Config;
import com.agrocti.exception.FeedFormatException;
import com.agrocti.model.ThreatIndicator;
import com.agrocti.util.Json;
import com.agrocti.util.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * External agricultural threat-intelligence feed, read from a JSON file
 * (a simplified STIX-like layout). IP indicators are kept in a HashMap for O(1) lookup.
 * While the dashboard runs, analysts can change the indicators in MySQL; replaceAll() then swaps the
 * contents while the detector thread keeps reading, so every method is synchronized.
 */
public class ThreatFeed {

    private final Map<String, ThreatIndicator> byIp = new HashMap<>();
    private final List<ThreatIndicator> advisories = new ArrayList<>();
    private final List<ThreatIndicator> all = new ArrayList<>();

    public ThreatFeed(List<ThreatIndicator> indicators) {
        for (ThreatIndicator t : indicators) add(t);
    }

    public final synchronized void add(ThreatIndicator t) {
        all.add(t);
        if (t.isIp()) byIp.put(t.getValue(), t);
        else advisories.add(t);
    }

    public synchronized Optional<ThreatIndicator> lookupIp(String ip) {
        return Optional.ofNullable(byIp.get(ip));
    }

    public synchronized List<ThreatIndicator> activeAdvisories(int region, int step) {
        List<ThreatIndicator> out = new ArrayList<>();
        for (ThreatIndicator a : advisories) {
            if (a.getRegion() == region && a.activeAt(step)) out.add(a);
        }
        return out;
    }

    public synchronized List<ThreatIndicator> all() {
        return Collections.unmodifiableList(new ArrayList<>(all));
    }

    /** Replaces every indicator, e.g. after an analyst edited them in the database. */
    public synchronized void replaceAll(List<ThreatIndicator> indicators) {
        all.clear();
        byIp.clear();
        advisories.clear();
        for (ThreatIndicator t : indicators) add(t);
    }

    public synchronized int ipCount() {
        return byIp.size();
    }

    public synchronized int advisoryCount() {
        return advisories.size();
    }

    // ------------------------------------------------------------------ file I/O
    public static void save(List<ThreatIndicator> indicators, Path file) throws IOException {
        String json = Json.object().put("feed", "Agro-CTI simulated feed").put("format", "agro-cti-1")
                .raw("indicators", Json.array(indicators)).build();
        Files.writeString(file, json.replace("},{", "},\n  {"), StandardCharsets.UTF_8);
    }

    public static ThreatFeed load(Path file) throws IOException, FeedFormatException {
        Object root;
        try {
            root = JsonParser.parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new FeedFormatException(file.getFileName() + ": " + e.getMessage());
        }
        if (!(root instanceof Map)) throw new FeedFormatException("feed must be a JSON object");
        Object list = ((Map<?, ?>) root).get("indicators");
        if (!(list instanceof List)) throw new FeedFormatException("feed has no \"indicators\" array");
        List<ThreatIndicator> out = new ArrayList<>();
        int i = 0;
        for (Object o : (List<?>) list) {
            i++;
            if (!(o instanceof Map)) throw new FeedFormatException("indicator " + i + " is not an object");
            out.add(parse((Map<?, ?>) o, i));
        }
        return new ThreatFeed(out);
    }

    private static ThreatIndicator parse(Map<?, ?> m, int i) throws FeedFormatException {
        String id = str(m, "id", i), type = str(m, "type", i), value = str(m, "value", i);
        int conf = (int) num(m, "confidence", i);
        String source = m.get("source") == null ? "unknown" : m.get("source").toString();
        if ("ipv4".equals(type)) {
            if (!value.matches("\\d{1,3}(\\.\\d{1,3}){3}")) throw new FeedFormatException("indicator " + i + ": bad IPv4 address " + value);
            return new ThreatIndicator(id, value, conf, source);
        }
        if ("regional-advisory".equals(type)) {
            String regionName = str(m, "region", i);
            int region = -1;
            for (int r = 0; r < Config.REGIONS.length; r++) if (Config.REGIONS[r].equalsIgnoreCase(regionName)) region = r;
            if (region < 0) throw new FeedFormatException("indicator " + i + ": unknown region " + regionName);
            return new ThreatIndicator(id, type, value, region, (int) num(m, "validFrom", i), (int) num(m, "validTo", i), conf, source);
        }
        throw new FeedFormatException("indicator " + i + ": unknown type " + type);
    }

    private static String str(Map<?, ?> m, String k, int i) throws FeedFormatException {
        Object v = m.get(k);
        if (!(v instanceof String)) throw new FeedFormatException("indicator " + i + ": missing text field \"" + k + "\"");
        return (String) v;
    }

    private static double num(Map<?, ?> m, String k, int i) throws FeedFormatException {
        Object v = m.get(k);
        if (!(v instanceof Double)) throw new FeedFormatException("indicator " + i + ": missing number field \"" + k + "\"");
        return (Double) v;
    }
}
