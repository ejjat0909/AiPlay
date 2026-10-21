package com.ejjat.aiplay.core;

/**
 * The five real-world states of "this head unit does not have Android Auto".
 *
 * <p>Derived from the shipped-device evidence model in PLAN.md §2. The point of naming them is
 * that only two of the five are fixable by flipping a package state, and the app must not pretend
 * otherwise.</p>
 */
public enum Condition {

    /** Android Auto is installed and enabled. Nothing to fix. */
    P1_READY("P1", "ready"),

    /** Android Auto is installed but disabled for the current user. One command fixes it. */
    P2_DISABLED("P2", "disabled"),

    /**
     * Legacy: 0.1.0 reported this whenever gearhead had no launcher activity. Modern Android Auto
     * (15.x) never ships one, so 0.1.1 classifies installed+enabled as {@link #P1_READY} and keeps
     * this value only so older reports still parse. Not produced by {@link ReadinessEngine}.
     */
    P3_HIDDEN("P3", "hidden"),

    /** No Android Auto package, but Google Play services exist: a receiver can work. */
    P4_RECEIVER_GMS("P4", "receiver_gms"),

    /** No Android Auto package and no Google Play services: receiver only, no GMS path. */
    P5_RECEIVER_NO_GMS("P5", "receiver_no_gms");

    public final String code;
    public final String key;

    Condition(String code, String key) {
        this.code = code;
        this.key = key;
    }

    /** True when the driver has something to fix with one tap. */
    public boolean isRepairable() {
        return this == P2_DISABLED || this == P3_HIDDEN;
    }

    /** True when the only route to projection is AiPlay's own receiver. */
    public boolean needsReceiver() {
        return this == P4_RECEIVER_GMS || this == P5_RECEIVER_NO_GMS;
    }
}
