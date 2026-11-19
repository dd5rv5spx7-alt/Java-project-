package com.agrocti.model;

import com.agrocti.config.Config;
import com.agrocti.exception.ValidationException;
import com.agrocti.util.Json;

import java.util.Arrays;

/** A sensor-and-valve node installed on one farm (module 1: farm node registry). */
public class FarmNode extends Record<Integer> {

    private int id;
    private Integer simIndex;        // 0..23 for the nodes of the monitored (simulated) network, null otherwise
    private String code;             // e.g. GUN-F3
    private String region;
    private String crop;
    private String gatewayIp;
    private String firmware;         // e.g. v1.4.2
    private NodeStatus status = NodeStatus.ACTIVE;
    private Integer ownerId;         // users.id of the farmer, may be null
    private String ownerName = "";   // filled in by the query, for display only

    public FarmNode() { }

    public FarmNode(Integer simIndex, String code, String region, String crop, String gatewayIp,
                    String firmware, NodeStatus status, Integer ownerId) {
        this.simIndex = simIndex;
        this.code = code;
        this.region = region;
        this.crop = crop;
        this.gatewayIp = gatewayIp;
        this.firmware = firmware;
        this.status = status;
        this.ownerId = ownerId;
    }

    @Override
    public void validate() throws ValidationException {
        code = requireText("code", code, 12).toUpperCase();
        requireMatch("code", code, "[A-Z]{3}-F\\d{1,2}", "GUN-F7 (3 letters, -F, farm number)");
        region = requireText("region", region, 20);
        if (Arrays.stream(Config.REGIONS).noneMatch(region::equals)) {
            throw new ValidationException("region", "region must be one of " + String.join(", ", Config.REGIONS));
        }
        crop = requireText("crop", crop, 30);
        gatewayIp = requireText("gatewayIp", gatewayIp, 15);
        if (!isIpv4(gatewayIp)) throw new ValidationException("gatewayIp", "gateway IP must be an IPv4 address such as 10.1.3.1");
        firmware = requireText("firmware", firmware, 12);
        requireMatch("firmware", firmware, "v\\d+\\.\\d+\\.\\d+", "v1.4.2");
        if (status == null) throw new ValidationException("status", "status is required");
    }

    @Override
    public String label() {
        return code + " (" + region + ", " + crop + ")";
    }

    public boolean isMonitored() {
        return simIndex != null;
    }

    @Override
    public Integer getId() { return id; }
    public void setId(int id) { this.id = id; }
    public Integer getSimIndex() { return simIndex; }
    public void setSimIndex(Integer simIndex) { this.simIndex = simIndex; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getCrop() { return crop; }
    public void setCrop(String crop) { this.crop = crop; }
    public String getGatewayIp() { return gatewayIp; }
    public void setGatewayIp(String gatewayIp) { this.gatewayIp = gatewayIp; }
    public String getFirmware() { return firmware; }
    public void setFirmware(String firmware) { this.firmware = firmware; }
    public NodeStatus getStatus() { return status; }
    public void setStatus(NodeStatus status) { this.status = status; }
    public Integer getOwnerId() { return ownerId; }
    public void setOwnerId(Integer ownerId) { this.ownerId = ownerId; }
    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName == null ? "" : ownerName; }

    @Override
    public String toJson() {
        Json.Builder b = Json.object().put("id", id).put("code", code).put("region", region).put("crop", crop)
                .put("gatewayIp", gatewayIp).put("firmware", firmware).put("status", status.name())
                .put("monitored", isMonitored()).put("ownerName", ownerName).put("createdAt", getCreatedAt());
        b.raw("ownerId", ownerId == null ? "null" : ownerId.toString());
        b.raw("simIndex", simIndex == null ? "null" : simIndex.toString());
        return b.build();
    }
}
