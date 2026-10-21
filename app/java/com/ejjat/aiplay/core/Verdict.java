package com.ejjat.aiplay.core;

import java.util.Collections;
import java.util.List;

/** The outcome of evaluating a {@link DeviceProfile}: what is wrong, how bad, what to do. */
public final class Verdict {

    public final Condition condition;
    public final Action action;
    public final int score;
    /** Stable key the UI translates; core stays free of user-facing copy. */
    public final String rationaleKey;
    public final List<Capability> capabilities;

    public Verdict(Condition condition, Action action, int score, String rationaleKey,
                   List<Capability> capabilities) {
        this.condition = condition;
        this.action = action;
        this.score = score;
        this.rationaleKey = rationaleKey;
        this.capabilities = Collections.unmodifiableList(capabilities);
    }

    @Override
    public String toString() {
        return condition.code + " " + condition.key + " score=" + score + " action=" + action;
    }
}
