package com.agrocti.model;

/** Ground-truth label of a reading. NONE means the reading is genuine. */
public enum AttackType {
    NONE("Normal"),
    CONSTANT_SPOOF("Constant (stuck-at) spoofing"),
    REPLAY_SPOOF("Replay spoofing"),
    DRIFT_SPOOF("Drift / bias injection"),
    SPIKE_INJECTION("Spike injection"),
    ACTUATOR_INJECTION("Actuator command injection"),
    REGIONAL_CAMPAIGN("Regional coordinated spoofing");

    private final String label;

    AttackType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean isAttack() {
        return this != NONE;
    }
}
