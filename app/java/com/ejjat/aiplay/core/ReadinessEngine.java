package com.ejjat.aiplay.core;

/**
 * Pure decision logic: {@link DeviceProfile} in, {@link Verdict} out.
 *
 * <p>Deliberately free of Android imports so the entire condition matrix — including the
 * contradictory profiles real ROMs produce — is covered by desktop unit tests.</p>
 */
public final class ReadinessEngine {

    private ReadinessEngine() {
    }

    public static Verdict evaluate(DeviceProfile profile) {
        if (profile == null) {
            throw new IllegalArgumentException("profile == null");
        }

        int score = 0;
        if (profile.gearheadInstalled) {
            score += 40;
        }
        if (profile.gearheadEnabled) {
            score += 40;
        }
        if (profile.gmsInstalled && profile.gmsEnabled) {
            score += 10;
        }
        if (profile.sdkInt >= 24) {
            score += 5;
        }
        if (profile.suAvailable) {
            score += 5;
        }
        if (score > 100) {
            score = 100;
        }

        Condition condition;
        Action action;
        String rationale;

        if (profile.gearheadInstalled && profile.gearheadEnabled && profile.gearheadHasLauncher) {
            condition = Condition.P1_READY;
            action = Action.OPEN_ANDROID_AUTO;
            rationale = "gearhead_ready";
            score = 100;
        } else if (profile.gearheadInstalled && profile.gearheadEnabled) {
            condition = Condition.P3_HIDDEN;
            action = Action.REPAIR_ANDROID_AUTO;
            rationale = "gearhead_hidden";
        } else if (profile.gearheadInstalled) {
            condition = Condition.P2_DISABLED;
            action = Action.ENABLE_ANDROID_AUTO;
            rationale = "gearhead_disabled";
        } else if (profile.gmsInstalled) {
            condition = Condition.P4_RECEIVER_GMS;
            action = Action.START_RECEIVER;
            rationale = "no_gearhead_gms";
        } else {
            condition = Condition.P5_RECEIVER_NO_GMS;
            action = Action.START_RECEIVER;
            rationale = "no_gearhead_no_gms";
        }

        return new Verdict(condition, action, score, rationale, profile.capabilities());
    }
}
