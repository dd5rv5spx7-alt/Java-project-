package com.agrocti.model;

import com.agrocti.exception.ValidationException;
import com.agrocti.util.Json;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** One step taken to contain or fix an incident (module 4: response actions). */
public class ResponseAction extends Record<Integer> {

    private int id;
    private int incidentId;
    private ActionType type = ActionType.OTHER;
    private String description;
    private Integer performedBy;       // users.id
    private String performedByName = "";
    private ActionStatus status = ActionStatus.PLANNED;
    private String dueDate = "";       // yyyy-mm-dd or empty
    private String incidentLabel = ""; // for display only

    public ResponseAction() { }

    public ResponseAction(int incidentId, ActionType type, String description, Integer performedBy,
                          ActionStatus status, String dueDate) {
        this.incidentId = incidentId;
        this.type = type;
        this.description = description;
        this.performedBy = performedBy;
        this.status = status;
        this.dueDate = dueDate == null ? "" : dueDate;
    }

    @Override
    public void validate() throws ValidationException {
        if (incidentId <= 0) throw new ValidationException("incidentId", "choose the incident this action belongs to");
        if (type == null) throw new ValidationException("type", "action type is required");
        description = requireText("description", description, 300);
        if (description.length() < 5) throw new ValidationException("description", "description must be at least 5 characters");
        if (status == null) throw new ValidationException("status", "status is required");
        if (!dueDate.isEmpty()) {
            try {
                LocalDate.parse(dueDate);
            } catch (DateTimeParseException e) {
                throw new ValidationException("dueDate", "due date must be a date such as 2026-07-20");
            }
        }
    }

    @Override
    public String label() {
        return type.text() + " for incident " + incidentId;
    }

    @Override
    public Integer getId() { return id; }
    public void setId(int id) { this.id = id; }
    public int getIncidentId() { return incidentId; }
    public void setIncidentId(int incidentId) { this.incidentId = incidentId; }
    public ActionType getType() { return type; }
    public void setType(ActionType type) { this.type = type; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Integer getPerformedBy() { return performedBy; }
    public void setPerformedBy(Integer performedBy) { this.performedBy = performedBy; }
    public String getPerformedByName() { return performedByName; }
    public void setPerformedByName(String n) { this.performedByName = n == null ? "" : n; }
    public ActionStatus getStatus() { return status; }
    public void setStatus(ActionStatus status) { this.status = status; }
    public String getDueDate() { return dueDate; }
    public void setDueDate(String dueDate) { this.dueDate = dueDate == null ? "" : dueDate; }
    public String getIncidentLabel() { return incidentLabel; }
    public void setIncidentLabel(String l) { this.incidentLabel = l == null ? "" : l; }

    @Override
    public String toJson() {
        Json.Builder b = Json.object().put("id", id).put("incidentId", incidentId).put("incidentLabel", incidentLabel)
                .put("type", type.name()).put("typeText", type.text()).put("description", description)
                .put("performedByName", performedByName).put("status", status.name()).put("dueDate", dueDate)
                .put("createdAt", getCreatedAt());
        b.raw("performedBy", performedBy == null ? "null" : performedBy.toString());
        return b.build();
    }
}
