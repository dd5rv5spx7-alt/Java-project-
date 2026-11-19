package com.agrocti.model;

/** Kinds of response to an incident. */
public enum ActionType {
    BLOCK_IP("Block source IP at the gateway"),
    RECALIBRATE("Recalibrate / replace the sensor"),
    MANUAL_VALVE("Switch the valve to manual control"),
    FIRMWARE_UPDATE("Update node firmware"),
    FIELD_VISIT("Field inspection"),
    OTHER("Other");

    private final String text;

    ActionType(String text) {
        this.text = text;
    }

    public String text() {
        return text;
    }
}
