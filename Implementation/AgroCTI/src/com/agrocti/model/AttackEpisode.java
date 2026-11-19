package com.agrocti.model;

/** One attack on one farm node, from step start (inclusive) to end (exclusive). */
public final class AttackEpisode {

    private final int id;
    private final AttackType type;
    private final int node;
    private final int start;
    private final int end;
    private final int campaign;     // -1 if not part of a regional campaign
    private final String detail;

    public AttackEpisode(int id, AttackType type, int node, int start, int end, int campaign, String detail) {
        this.id = id;
        this.type = type;
        this.node = node;
        this.start = start;
        this.end = end;
        this.campaign = campaign;
        this.detail = detail;
    }

    public int getId() { return id; }
    public AttackType getType() { return type; }
    public int getNode() { return node; }
    public int getStart() { return start; }
    public int getEnd() { return end; }
    public int getCampaign() { return campaign; }
    public String getDetail() { return detail; }

    public boolean covers(int step) {
        return step >= start && step < end;
    }

    public boolean overlaps(int s, int e, int gap) {
        return s < end + gap && e + gap > start;
    }
}
