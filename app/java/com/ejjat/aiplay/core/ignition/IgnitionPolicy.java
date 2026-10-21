package com.ejjat.aiplay.core.ignition;

/**
 * Decides what an "ignition" signal should do to the receiver.
 *
 * <p>A head unit has no standard way to tell an app that the car was switched on. What exists are
 * side effects of it, and a waking unit usually produces several in a row (boot, screen on, unlock),
 * so the policy has to be debounced or the receiver would be started three times. The signals
 * understood here are ones Android itself defines, plus up to two actions the user types in for a
 * unit that broadcasts its own ACC intent:</p>
 *
 * <ul>
 *   <li><b>on</b>: {@code BOOT_COMPLETED} (cold boot), {@code QUICKBOOT_POWERON} (the "fast boot"
 *       resume that many aftermarket and HTC units send, Android's and HTC's spelling),
 *       {@code SCREEN_ON}, {@code USER_PRESENT}, {@code ENTER_CAR_MODE}, and the custom ACC-on
 *       action;</li>
 *   <li><b>off</b>: {@code EXIT_CAR_MODE} and the custom ACC-off action. {@code SCREEN_OFF} is
 *       deliberately <em>not</em> an off signal: a screen timeout is not the car being switched
 *       off.</li>
 * </ul>
 *
 * <p>Whether any of these fires on a particular unit's ignition is unknown; that is a property of the
 * unit, not of this class. Pure Java so it can be unit-tested without Android.</p>
 */
public final class IgnitionPolicy {

    public enum Decision {
        START, STOP, IGNORE
    }

    public static final String BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED";
    public static final String QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON";
    public static final String QUICKBOOT_POWERON_HTC = "com.htc.intent.action.QUICKBOOT_POWERON";
    public static final String SCREEN_ON = "android.intent.action.SCREEN_ON";
    public static final String USER_PRESENT = "android.intent.action.USER_PRESENT";
    public static final String ENTER_CAR_MODE = "android.app.action.ENTER_CAR_MODE";
    public static final String EXIT_CAR_MODE = "android.app.action.EXIT_CAR_MODE";

    /**
     * A wake produces boot, screen-on and unlock within seconds; after a start, further "on"
     * signals inside this window are the same event.
     */
    public static final long DEBOUNCE_MS = 10000L;

    /** What the user has turned on. */
    public static final class Settings {
        public boolean startOnIgnition;
        public boolean stopOnIgnitionOff;
        /** The unit's own ACC-on broadcast action, or empty. */
        public String customOn = "";
        /** The unit's own ACC-off broadcast action, or empty. */
        public String customOff = "";
    }

    private long lastStartAt = Long.MIN_VALUE / 2;
    private String reason = "";

    /** Why the last {@link #decide} answered as it did, for the log. */
    public String reason() {
        return reason;
    }

    public static boolean isOnSignal(String action, Settings settings) {
        if (action == null) {
            return false;
        }
        return action.equals(BOOT_COMPLETED) || action.equals(QUICKBOOT_POWERON)
                || action.equals(QUICKBOOT_POWERON_HTC) || action.equals(SCREEN_ON)
                || action.equals(USER_PRESENT) || action.equals(ENTER_CAR_MODE)
                || (settings.customOn.length() > 0 && action.equals(settings.customOn));
    }

    public static boolean isOffSignal(String action, Settings settings) {
        if (action == null) {
            return false;
        }
        return action.equals(EXIT_CAR_MODE)
                || (settings.customOff.length() > 0 && action.equals(settings.customOff));
    }

    /**
     * @param action         the broadcast action received
     * @param nowMillis      a monotonic-enough clock in milliseconds
     * @param receiverActive true when the receiver is already starting or running
     */
    public synchronized Decision decide(String action, long nowMillis, Settings settings,
                                        boolean receiverActive) {
        if (isOffSignal(action, settings)) {
            if (!settings.stopOnIgnitionOff) {
                reason = "ignition-off signal, but 'stop when the ignition turns off' is disabled";
                return Decision.IGNORE;
            }
            if (!receiverActive) {
                reason = "ignition-off signal, receiver is not running";
                return Decision.IGNORE;
            }
            // An explicit off ends the previous wake-up: if the car comes back a moment later (ACC
            // drops while the engine cranks and returns), that on must start the receiver again.
            lastStartAt = Long.MIN_VALUE / 2;
            reason = "ignition off";
            return Decision.STOP;
        }
        if (isOnSignal(action, settings)) {
            if (!settings.startOnIgnition) {
                reason = "ignition-on signal, but 'start when the ignition turns on' is disabled";
                return Decision.IGNORE;
            }
            if (receiverActive) {
                reason = "receiver is already running or starting";
                return Decision.IGNORE;
            }
            if (nowMillis - lastStartAt < DEBOUNCE_MS && nowMillis >= lastStartAt) {
                reason = "same wake-up as the start " + (nowMillis - lastStartAt) + " ms ago";
                return Decision.IGNORE;
            }
            lastStartAt = nowMillis;
            reason = "ignition on";
            return Decision.START;
        }
        reason = "not an ignition signal";
        return Decision.IGNORE;
    }
}
