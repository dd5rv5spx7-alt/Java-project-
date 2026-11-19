package com.agrocti.model;

import com.agrocti.config.Config;
import com.agrocti.exception.ValidationException;
import com.agrocti.util.Json;

/**
 * One entry of the external agricultural threat-intelligence feed.
 * Two kinds are used: "ipv4" (a known malicious address) and
 * "regional-advisory" (a warning that sensors in a region are being spoofed).
 */
public final class ThreatIndicator extends Record<String> {

    private final String id;
    private final String type;
    private final String value;        // IP address, or the pattern for an advisory
    private final int region;          // -1 for IP indicators
    private final int validFrom;       // step, advisories only
    private final int validTo;
    private final int confidence;      // 0..100
    private final String source;

    public ThreatIndicator(String id, String type, String value, int region,
                           int validFrom, int validTo, int confidence, String source) {
        this.id = id;
        this.type = type;
        this.value = value;
        this.region = region;
        this.validFrom = validFrom;
        this.validTo = validTo;
        this.confidence = confidence;
        this.source = source;
    }

    /** Convenience constructor for an IP indicator. */
    public ThreatIndicator(String id, String ip, int confidence, String source) {
        this(id, "ipv4", ip, -1, 0, Integer.MAX_VALUE, confidence, source);
    }

    @Override
    public String getId() { return id; }
    public String getType() { return type; }
    public String getValue() { return value; }
    public int getRegion() { return region; }
    public int getValidFrom() { return validFrom; }
    public int getValidTo() { return validTo; }
    public int getConfidence() { return confidence; }
    public String getSource() { return source; }

    public boolean isIp() {
        return "ipv4".equals(type);
    }

    public boolean activeAt(int step) {
        return step >= validFrom && step <= validTo;
    }

    /** Indicators found by this network itself (regional campaigns) have ids starting with "local-". */
    public boolean isLocal() {
        return id != null && id.startsWith("local-");
    }

    @Override
    public void validate() throws ValidationException {
        requireText("id", id, 20);
        requireMatch("id", id, "[a-z]+-\\d{3,6}", "ind-033");
        if (!isIp() && !"regional-advisory".equals(type)) {
            throw new ValidationException("type", "type must be ipv4 or regional-advisory");
        }
        requireText("value", value, 64);
        if (isIp() && !isIpv4(value)) throw new ValidationException("value", "value must be an IPv4 address such as 203.0.113.45");
        if (!isIp()) {
            requireRange("region", region, 0, Config.REGIONS.length - 1);
            if (validFrom < 0 || validTo < validFrom) {
                throw new ValidationException("validTo", "the advisory must end after it starts");
            }
        }
        requireRange("confidence", confidence, 0, 100);
        requireText("source", source, 40);
    }

    @Override
    public String label() {
        return id + " " + (isIp() ? value : value + " advisory");
    }

    @Override
    public String toJson() {
        Json.Builder b = Json.object().put("id", id).put("type", type).put("value", value)
                .put("confidence", confidence).put("source", source).put("local", isLocal())
                .put("createdAt", getCreatedAt());
        if (!isIp()) {
            b.put("region", Config.REGIONS[region]).put("regionIndex", region)
             .put("validFrom", validFrom).put("validTo", validTo)
             .put("validFromTime", Config.timeOf(validFrom).toString())
             .put("validToTime", Config.timeOf(validTo).toString());
        }
        return b.build();
    }
}
