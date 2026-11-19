package com.agrocti.model;

/** Outcome of the pipeline for one reading, from least to most serious. */
public enum Severity {
    NORMAL, INFO, SUSPECT, ALERT, CRITICAL;

    public boolean isAlarm() {
        return this == ALERT || this == CRITICAL;
    }
}
