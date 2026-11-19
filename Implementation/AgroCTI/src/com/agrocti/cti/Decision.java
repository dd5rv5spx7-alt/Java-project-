package com.agrocti.cti;

import com.agrocti.model.Alert;
import com.agrocti.model.Severity;

import java.util.ArrayList;
import java.util.List;

/** What the pipeline decided for one node at one step, and why. */
public final class Decision {

    public final int node;
    public final int step;
    public final double score;
    public Severity severity = Severity.NORMAL;
    public String guess = "";
    public final List<String> reasons = new ArrayList<>();

    Decision(int node, int step, double score) {
        this.node = node;
        this.step = step;
        this.score = score;
    }

    void raise(Severity s, String reason) {
        if (s.ordinal() > severity.ordinal()) severity = s;
        if (!reasons.contains(reason)) reasons.add(reason);    // a burst of commands gives the same reason many times
    }

    public boolean isAlarm() {
        return severity.isAlarm();
    }

    public Alert toAlert() {
        return new Alert(node, step, severity, score, guess, String.join("; ", reasons));
    }
}
