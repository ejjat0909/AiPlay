package com.ejjat.aiplay.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ReadinessEngineTest {

    private static DeviceProfile base() {
        DeviceProfile profile = new DeviceProfile();
        profile.manufacturer = "Test";
        profile.model = "Head unit";
        profile.sdkInt = 29;
        profile.gearheadInstalled = false;
        profile.gearheadEnabled = false;
        profile.gearheadHasLauncher = false;
        profile.gmsInstalled = false;
        profile.gmsEnabled = false;
        return profile;
    }

    @Test
    public void p1WhenInstalledEnabledAndLaunched() {
        DeviceProfile profile = base();
        profile.gearheadInstalled = true;
        profile.gearheadEnabled = true;
        profile.gearheadHasLauncher = true;
        profile.gmsInstalled = true;
        profile.gmsEnabled = true;

        Verdict verdict = ReadinessEngine.evaluate(profile);
        assertEquals(Condition.P1_READY, verdict.condition);
        assertEquals(Action.OPEN_ANDROID_AUTO, verdict.action);
        assertEquals(100, verdict.score);
    }

    @Test
    public void p2WhenInstalledButDisabled() {
        DeviceProfile profile = base();
        profile.gearheadInstalled = true;
        profile.gearheadEnabled = false;
        profile.gmsInstalled = true;
        profile.gmsEnabled = true;

        Verdict verdict = ReadinessEngine.evaluate(profile);
        assertEquals(Condition.P2_DISABLED, verdict.condition);
        assertEquals(Action.ENABLE_ANDROID_AUTO, verdict.action);
        assertTrue(verdict.condition.isRepairable());
        assertTrue(verdict.score < 100);
    }

    @Test
    public void p3WhenEnabledButNoLauncherEntry() {
        DeviceProfile profile = base();
        profile.gearheadInstalled = true;
        profile.gearheadEnabled = true;
        profile.gearheadHasLauncher = false;

        Verdict verdict = ReadinessEngine.evaluate(profile);
        assertEquals(Condition.P3_HIDDEN, verdict.condition);
        assertEquals(Action.REPAIR_ANDROID_AUTO, verdict.action);
    }

    @Test
    public void p4WhenMissingPackageButGmsPresent() {
        DeviceProfile profile = base();
        profile.gmsInstalled = true;
        profile.gmsEnabled = true;

        Verdict verdict = ReadinessEngine.evaluate(profile);
        assertEquals(Condition.P4_RECEIVER_GMS, verdict.condition);
        assertEquals(Action.START_RECEIVER, verdict.action);
        assertTrue(verdict.condition.needsReceiver());
    }

    @Test
    public void p5WhenNoPackageAndNoGms() {
        DeviceProfile profile = base();
        Verdict verdict = ReadinessEngine.evaluate(profile);
        assertEquals(Condition.P5_RECEIVER_NO_GMS, verdict.condition);
        assertEquals(Action.START_RECEIVER, verdict.action);
    }

    @Test
    public void disabledGmsIsNotTreatedAsPresent() {
        DeviceProfile profile = base();
        profile.gmsInstalled = true;
        profile.gmsEnabled = false;
        Verdict verdict = ReadinessEngine.evaluate(profile);
        assertEquals(Condition.P4_RECEIVER_GMS, verdict.condition);
        Capability gms = find(verdict, Capability.Id.PLAY_SERVICES);
        assertEquals(Capability.State.WARN, gms.state);
    }

    @Test
    public void rootAndAdbRaiseScoreButDoNotChangeCondition() {
        DeviceProfile profile = base();
        profile.gearheadInstalled = true;
        profile.gearheadEnabled = false;
        int withoutRoot = ReadinessEngine.evaluate(profile).score;

        profile.suAvailable = true;
        int withRoot = ReadinessEngine.evaluate(profile).score;

        assertTrue(withRoot > withoutRoot);
        assertEquals(Condition.P2_DISABLED, ReadinessEngine.evaluate(profile).condition);
    }

    @Test
    public void capabilitiesAlwaysDescribeTenFacts() {
        Verdict verdict = ReadinessEngine.evaluate(base());
        assertEquals(10, verdict.capabilities.size());
        assertEquals(Capability.Id.GEARHEAD_PACKAGE, verdict.capabilities.get(0).id);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNullProfile() {
        ReadinessEngine.evaluate(null);
    }

    private static Capability find(Verdict verdict, Capability.Id id) {
        for (Capability capability : verdict.capabilities) {
            if (capability.id == id) {
                return capability;
            }
        }
        throw new AssertionError("missing capability " + id);
    }
}
