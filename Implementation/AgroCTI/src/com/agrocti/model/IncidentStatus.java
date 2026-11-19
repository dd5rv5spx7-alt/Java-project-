package com.agrocti.model;

/** Where an incident is in the analyst's work flow. */
public enum IncidentStatus {
    OPEN, INVESTIGATING, RESOLVED, FALSE_ALARM;

    /** Closed incidents get no new response actions. */
    public boolean isClosed() {
        return this == RESOLVED || this == FALSE_ALARM;
    }
}
