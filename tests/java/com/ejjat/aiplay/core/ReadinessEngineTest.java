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

    /**
     * Real device evidence (OPPO CPH2739, Android 16, AA 15.6.654484): gearhead is installed and
     * enabled but exposes no launcher activity. 0.1.0 called that P3 and told the driver to repair a
     * healthy install; the launcher entry is informational, not a defect.
     */
    @Test
    public void installedAndEnabledWithoutLauncherEntryIsStillReady() {
        DeviceProfile profile = base();
        profile.gearheadInstalled = true;
        profile.gearheadEnabled = true;
        profile.gearheadHasLauncher = false;

        Verdict verdict = ReadinessEngine.evaluate(profile);
        assertEquals(Condition.P1_READY, verdict.condition);
        assertEquals(Action.OPEN_ANDROID_AUTO, verdict.action);
        assertEquals("gearhead_ready_no_icon", verdict.rationaleKey);
        assertEquals(100, verdict.score);
        assertEquals(Capability.State.WARN,
                find(verdict, Capability.Id.GEARHEAD_LAUNCHER).state);
    }

    @Test
    public void launcherEntryIsReportedAsOkWhenPresent() {
        DeviceProfile profile = base();
        profile.gearheadInstalled = true;
        profile.gearheadEnabled = true;
        profile.gearheadHasLauncher = true;

        Verdict verdict = ReadinessEngine.evaluate(profile);
        assertEquals(Capability.State.OK,
                find(verdict, Capability.Id.GEARHEAD_LAUNCHER).state);
        assertEquals("gearhead_ready", verdict.rationaleKey);
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
    public void capabilitiesAlwaysDescribeElevenFacts() {
        Verdict verdict = ReadinessEngine.evaluate(base());
        assertEquals(11, verdict.capabilities.size());
        assertEquals(Capability.Id.GEARHEAD_PACKAGE, verdict.capabilities.get(0).id);
        assertEquals(Capability.Id.GEARHEAD_ENABLED, verdict.capabilities.get(1).id);
        assertEquals(Capability.Id.GEARHEAD_LAUNCHER, verdict.capabilities.get(2).id);
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
