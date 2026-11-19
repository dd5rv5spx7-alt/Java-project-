package com.agrocti.model;

/** One command sent to an irrigation valve or pump (one line of the actuator log). */
public final class ActuatorEvent {

    private final int step;
    private final int node;
    private final String command;
    private final String sourceIp;

    public ActuatorEvent(int step, int node, String command, String sourceIp) {
        this.step = step;
        this.node = node;
        this.command = command;
        this.sourceIp = sourceIp;
    }

    public int getStep() { return step; }
    public int getNode() { return node; }
    public String getCommand() { return command; }
    public String getSourceIp() { return sourceIp; }

    @Override
    public String toString() {
        return step + "," + node + "," + command + "," + sourceIp;
    }
}
