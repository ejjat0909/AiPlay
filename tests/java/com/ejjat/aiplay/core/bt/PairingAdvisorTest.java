package com.ejjat.aiplay.core.bt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The decision table behind the Bluetooth line on the home screen. */
public class PairingAdvisorTest {

    private static PairingAdvisor.Advice a(boolean on, boolean up, int bonded, boolean stale, int seen) {
        return PairingAdvisor.advise(new PairingAdvisor.Facts(on, up, bonded, stale, seen));
    }

    @Test
    public void bluetoothOffComesFirst() {
        assertEquals("turn Bluetooth on", a(false, false, 3, true, 2).status);
    }

    @Test
    public void serviceNotRegisteredIsReportedBeforePairingAdvice() {
        PairingAdvisor.Advice advice = a(true, false, 0, false, 0);
        assertEquals("service not running", advice.status);
        assertTrue(advice.hint.contains("Start the receiver first"));
    }

    @Test
    public void noPhonePairedAsksToPairWhileRunning() {
        PairingAdvisor.Advice advice = a(true, true, 0, false, 0);
        assertEquals("pair your phone with this unit", advice.status);
        assertTrue(advice.hint.contains("while this receiver is running"));
    }

    /** The measured failure: bonded before the service existed, phone never asked for it. */
    @Test
    public void pairedBeforeServiceExistedAsksToRePair() {
        PairingAdvisor.Advice advice = a(true, true, 1, true, 0);
        assertEquals("re-pair the phone", advice.status);
        assertTrue(advice.hint.contains("Forget"));
        assertTrue(advice.hint.contains("pair again"));
    }

    @Test
    public void pairedWhileRunningAsksToOpenAndroidAuto() {
        PairingAdvisor.Advice advice = a(true, true, 1, false, 0);
        assertEquals("paired; open Android Auto on the phone", advice.status);
        assertFalse(advice.hint.isEmpty());
    }

    @Test
    public void aPhoneThatConnectedBeforeWins() {
        assertEquals("phone reached the unit; waiting for Wi-Fi", a(true, true, 1, true, 1).status);
    }

    @Test
    public void statusLinesStayShortForTheHomeScreen() {
        boolean[] tf = {true, false};
        for (boolean on : tf) for (boolean up : tf) for (boolean stale : tf) {
            for (int bonded = 0; bonded < 3; bonded++) for (int seen = 0; seen < 2; seen++) {
                PairingAdvisor.Advice advice = a(on, up, bonded, stale, seen);
                assertTrue(advice.status, advice.status.length() <= 48);
                assertFalse(advice.status.isEmpty());
                assertFalse(advice.hint.isEmpty());
            }
        }
    }
}
