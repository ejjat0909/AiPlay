package com.ejjat.aiplay.core.ignition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.ejjat.aiplay.core.ignition.IgnitionPolicy.Decision;

import org.junit.Test;

public class IgnitionPolicyTest {

    private static IgnitionPolicy.Settings settings(boolean start, boolean stop) {
        IgnitionPolicy.Settings settings = new IgnitionPolicy.Settings();
        settings.startOnIgnition = start;
        settings.stopOnIgnitionOff = stop;
        return settings;
    }

    @Test
    public void everyAndroidDefinedOnSignalStartsTheReceiver() {
        String[] signals = {
                IgnitionPolicy.BOOT_COMPLETED, IgnitionPolicy.QUICKBOOT_POWERON,
                IgnitionPolicy.QUICKBOOT_POWERON_HTC, IgnitionPolicy.SCREEN_ON,
                IgnitionPolicy.USER_PRESENT, IgnitionPolicy.ENTER_CAR_MODE};
        for (String signal : signals) {
            assertEquals(signal, Decision.START,
                    new IgnitionPolicy().decide(signal, 1000, settings(true, false), false));
        }
    }

    @Test
    public void nothingHappensWhileTheSwitchIsOff() {
        IgnitionPolicy policy = new IgnitionPolicy();
        assertEquals(Decision.IGNORE,
                policy.decide(IgnitionPolicy.SCREEN_ON, 1000, settings(false, false), false));
        assertTrue(policy.reason().contains("disabled"));
        assertEquals(Decision.IGNORE,
                policy.decide(IgnitionPolicy.EXIT_CAR_MODE, 2000, settings(true, false), true));
        assertTrue(policy.reason().contains("disabled"));
    }

    @Test
    public void aWakeThatFiresBootScreenOnAndUnlockStartsExactlyOnce() {
        IgnitionPolicy policy = new IgnitionPolicy();
        IgnitionPolicy.Settings s = settings(true, false);
        assertEquals(Decision.START, policy.decide(IgnitionPolicy.QUICKBOOT_POWERON, 1000, s, false));
        // the start has not finished yet and the receiver reports inactive for a moment
        assertEquals(Decision.IGNORE, policy.decide(IgnitionPolicy.SCREEN_ON, 1400, s, false));
        assertTrue(policy.reason().contains("same wake-up"));
        assertEquals(Decision.IGNORE, policy.decide(IgnitionPolicy.USER_PRESENT, 3000, s, false));
    }

    @Test
    public void aLaterWakeStartsAgainAfterTheDebounceWindow() {
        IgnitionPolicy policy = new IgnitionPolicy();
        IgnitionPolicy.Settings s = settings(true, false);
        assertEquals(Decision.START, policy.decide(IgnitionPolicy.SCREEN_ON, 1000, s, false));
        assertEquals(Decision.START, policy.decide(IgnitionPolicy.SCREEN_ON,
                1000 + IgnitionPolicy.DEBOUNCE_MS, s, false));
    }

    @Test
    public void anAlreadyRunningReceiverIsLeftAlone() {
        IgnitionPolicy policy = new IgnitionPolicy();
        assertEquals(Decision.IGNORE,
                policy.decide(IgnitionPolicy.BOOT_COMPLETED, 1000, settings(true, false), true));
        assertTrue(policy.reason().contains("already"));
    }

    @Test
    public void offSignalsStopOnlyWhenAllowedAndRunning() {
        IgnitionPolicy.Settings s = settings(true, true);
        assertEquals(Decision.STOP,
                new IgnitionPolicy().decide(IgnitionPolicy.EXIT_CAR_MODE, 1000, s, true));
        assertEquals(Decision.IGNORE,
                new IgnitionPolicy().decide(IgnitionPolicy.EXIT_CAR_MODE, 1000, s, false));
        assertEquals(Decision.IGNORE, new IgnitionPolicy().decide(IgnitionPolicy.EXIT_CAR_MODE,
                1000, settings(true, false), true));
    }

    @Test
    public void aScreenTimeoutIsNotTheCarSwitchingOff() {
        IgnitionPolicy.Settings s = settings(true, true);
        assertFalse(IgnitionPolicy.isOffSignal("android.intent.action.SCREEN_OFF", s));
        assertEquals(Decision.IGNORE, new IgnitionPolicy().decide(
                "android.intent.action.SCREEN_OFF", 1000, s, true));
    }

    @Test
    public void theUnitsOwnAccActionsAreHonouredOnceConfigured() {
        IgnitionPolicy.Settings s = settings(true, true);
        assertEquals("not configured yet: unknown action is ignored", Decision.IGNORE,
                new IgnitionPolicy().decide("com.vendor.ACC_ON", 1000, s, false));
        s.customOn = "com.vendor.ACC_ON";
        s.customOff = "com.vendor.ACC_OFF";
        assertEquals(Decision.START, new IgnitionPolicy().decide("com.vendor.ACC_ON", 1000, s, false));
        assertEquals(Decision.STOP, new IgnitionPolicy().decide("com.vendor.ACC_OFF", 1000, s, true));
        assertEquals(Decision.IGNORE,
                new IgnitionPolicy().decide("com.vendor.OTHER", 1000, s, false));
    }

    @Test
    public void switchingTheCarOffAndOnQuicklyStillStarts() {
        // ACC drops while the engine cranks and comes straight back: the on after the off is a
        // new wake-up even though it is inside the debounce window of the first start.
        IgnitionPolicy policy = new IgnitionPolicy();
        IgnitionPolicy.Settings s = settings(true, true);
        assertEquals(Decision.START, policy.decide(IgnitionPolicy.ENTER_CAR_MODE, 1000, s, false));
        assertEquals(Decision.STOP, policy.decide(IgnitionPolicy.EXIT_CAR_MODE, 4000, s, true));
        assertEquals(Decision.START, policy.decide(IgnitionPolicy.ENTER_CAR_MODE, 6000, s, false));
    }

    @Test
    public void duplicateOnSignalsAfterThatRestartAreStillDebounced() {
        IgnitionPolicy policy = new IgnitionPolicy();
        IgnitionPolicy.Settings s = settings(true, true);
        assertEquals(Decision.START, policy.decide(IgnitionPolicy.ENTER_CAR_MODE, 1000, s, false));
        assertEquals(Decision.STOP, policy.decide(IgnitionPolicy.EXIT_CAR_MODE, 4000, s, true));
        assertEquals(Decision.START, policy.decide(IgnitionPolicy.QUICKBOOT_POWERON, 6000, s, false));
        assertEquals(Decision.IGNORE, policy.decide(IgnitionPolicy.SCREEN_ON, 6500, s, false));
    }

    @Test
    public void nullAndEmptyActionsAreSafe() {
        IgnitionPolicy.Settings s = settings(true, true);
        assertEquals(Decision.IGNORE, new IgnitionPolicy().decide(null, 1000, s, false));
        assertEquals(Decision.IGNORE, new IgnitionPolicy().decide("", 1000, s, false));
        s.customOn = "";
        assertFalse("an empty custom action must never match the empty action",
                IgnitionPolicy.isOnSignal("", s));
    }
}
