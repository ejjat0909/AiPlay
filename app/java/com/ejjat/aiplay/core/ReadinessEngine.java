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

        if (profile.gearheadInstalled && profile.gearheadEnabled) {
            // Installed + enabled is the whole answer. A missing launcher entry is *not* a defect:
            // Android Auto 15.x ships no launcher activity on ColorOS/One UI/modern builds and is
            // reached through Settings > Connected devices. 0.1.0 treated that as P3 "hidden" and
            // told a perfectly healthy phone to "repair" Android Auto.
            condition = Condition.P1_READY;
            action = Action.OPEN_ANDROID_AUTO;
            rationale = profile.gearheadHasLauncher
                    ? "gearhead_ready" : "gearhead_ready_no_icon";
            score = 100;
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
