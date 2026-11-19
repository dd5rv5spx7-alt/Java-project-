package com.agrocti.model;

/** What a logged-in user may do. */
public enum Role {
    /** Everything, including user accounts. */
    ADMIN,
    /** Security analyst: manages nodes, indicators, incidents and response actions. */
    ANALYST,
    /** Farmer: can look at everything but change nothing. */
    FARMER;

    public boolean canEditRecords() {
        return this == ADMIN || this == ANALYST;
    }

    public boolean canManageUsers() {
        return this == ADMIN;
    }
}
