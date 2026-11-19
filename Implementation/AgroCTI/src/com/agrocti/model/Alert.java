package com.agrocti.model;

import com.agrocti.config.Config;
import com.agrocti.exception.ValidationException;
import com.agrocti.util.Json;

/**
 * An incident: consecutive alarm readings of one node merged into one record,
 * so the farmer sees one alert per attack instead of one per 15 minutes.
 */
public class Alert extends Record<Integer> {

    private int id;
    private final int node;
    private final int startStep;
    private int endStep;
    private int readings;
    private Severity severity;
    private double maxScore;
    private String guess;
    private String reasons;
    private String truth = "";      // ground truth, known only because the data is simulated
    private IncidentStatus status = IncidentStatus.OPEN;   // set by the analyst (module 3)
    private String note = "";
    private Integer assignedTo;     // users.id of the analyst working on it
    private String assignedName = "";

    public Alert(int node, int step, Severity severity, double score, String guess, String reasons) {
        this.node = node;
        this.startStep = step;
        this.endStep = step;
        this.readings = 1;
        this.severity = severity;
        this.maxScore = score;
        this.guess = guess;
        this.reasons = reasons;
    }

    /** Full constructor used when reading from the database. */
    public Alert(int id, int node, int startStep, int endStep, int readings, Severity severity,
                 double maxScore, String guess, String reasons, String truth) {
        this(node, startStep, severity, maxScore, guess, reasons);
        this.id = id;
        this.endStep = endStep;
        this.readings = readings;
        this.truth = truth;
    }

    /** Adds one more alarm reading to this incident. */
    public void extend(int step, Severity s, double score, String newReasons) {
        endStep = step;
        readings++;
        if (s.ordinal() > severity.ordinal()) {
            severity = s;
            reasons = newReasons;
        }
        maxScore = Math.max(maxScore, score);
    }

    /** Rules for an incident that an analyst creates or edits by hand. */
    @Override
    public void validate() throws ValidationException {
        requireRange("node", node, 0, Config.NODES - 1);
        if (severity == null || !severity.isAlarm()) throw new ValidationException("severity", "severity must be ALERT or CRITICAL");
        guess = requireText("guess", guess, 80);
        if (status == null) throw new ValidationException("status", "status is required");
        if (note == null) note = "";
        if (note.length() > 500) throw new ValidationException("note", "note must be at most 500 characters");
        if (status.isClosed() && note.isBlank()) {
            throw new ValidationException("note", "write a short note explaining why the incident is closed");
        }
    }

    @Override
    public String label() {
        return "#" + id + " " + Config.nodeName(node) + " - " + guess;
    }

    @Override
    public Integer getId() { return id; }
    public void setId(int id) { this.id = id; }
    public int getNode() { return node; }
    public int getStartStep() { return startStep; }
    public int getEndStep() { return endStep; }
    public int getReadings() { return readings; }
    public Severity getSeverity() { return severity; }
    public double getMaxScore() { return maxScore; }
    public String getGuess() { return guess; }
    public String getReasons() { return reasons; }
    public String getTruth() { return truth; }
    public void setTruth(String truth) { this.truth = truth; }
    public void setSeverity(Severity severity) { this.severity = severity; }
    public void setGuess(String guess) { this.guess = guess; }
    public void setReasons(String reasons) { this.reasons = reasons; }
    public IncidentStatus getStatus() { return status; }
    public void setStatus(IncidentStatus status) { this.status = status; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note == null ? "" : note; }
    public Integer getAssignedTo() { return assignedTo; }
    public void setAssignedTo(Integer assignedTo) { this.assignedTo = assignedTo; }
    public String getAssignedName() { return assignedName; }
    public void setAssignedName(String n) { this.assignedName = n == null ? "" : n; }

    @Override
    public String toJson() {
        return Json.object().put("id", id).put("node", node).put("nodeName", Config.nodeName(node))
                .put("region", Config.REGIONS[Config.regionOf(node)])
                .put("startStep", startStep).put("endStep", endStep).put("readings", readings)
                .put("time", Config.timeOf(startStep).toString().replace('T', ' '))
                .put("durationMin", 15L * (endStep - startStep + 1))
                .put("severity", severity.name()).put("score", Math.round(maxScore * 100) / 100.0)
                .put("guess", guess).put("reasons", reasons).put("truth", truth)
                .put("status", status.name()).put("note", note).put("assignedName", assignedName)
                .raw("assignedTo", assignedTo == null ? "null" : assignedTo.toString()).build();
    }
}
