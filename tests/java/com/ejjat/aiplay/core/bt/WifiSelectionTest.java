package com.ejjat.aiplay.core.bt;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Which network the phone is told to join: manual, then the unit's own group, then the current one. */
public class WifiSelectionTest {

    @Test
    public void aTypedNetworkAlwaysWins() {
        WifiSelection.Choice c = WifiSelection.choose("HomeRouter", "pass1234",
                "DIRECT-xy-AiPlay", "grouppass", "Kisara");
        assertEquals(WifiSelection.Source.MANUAL, c.source);
        assertEquals("HomeRouter", c.ssid);
        assertEquals("pass1234", c.key);
        assertEquals(WifiBootstrap.SECURITY_WPA2_PERSONAL, c.securityMode);
    }

    @Test
    public void withNothingTypedTheUnitsOwnGroupIsUsed() {
        WifiSelection.Choice c = WifiSelection.choose("", "", "DIRECT-xy-AiPlay", "grouppass", "Kisara");
        assertEquals(WifiSelection.Source.UNIT_GROUP, c.source);
        assertEquals("DIRECT-xy-AiPlay", c.ssid);
        assertEquals("grouppass", c.key);
        assertEquals(WifiBootstrap.SECURITY_WPA2_PERSONAL, c.securityMode);
    }

    /** The measured failure: the phone's own hotspot, no password, was what the phone was told. */
    @Test
    public void theCurrentNetworkIsOnlyTheLastResort() {
        WifiSelection.Choice c = WifiSelection.choose("", "", null, null, "Kisara");
        assertEquals(WifiSelection.Source.CURRENT_NETWORK, c.source);
        assertEquals("Kisara", c.ssid);
        assertEquals(WifiBootstrap.SECURITY_OPEN, c.securityMode);
    }

    @Test
    public void currentNetworkKeepsTheSavedPassword() {
        WifiSelection.Choice c = WifiSelection.choose("", "savedpass", "", "", "Kisara");
        assertEquals(WifiSelection.Source.CURRENT_NETWORK, c.source);
        assertEquals("savedpass", c.key);
        assertEquals(WifiBootstrap.SECURITY_WPA2_PERSONAL, c.securityMode);
    }

    @Test
    public void unknownSsidPlaceholderIsNotANetwork() {
        assertEquals(WifiSelection.Source.NONE,
                WifiSelection.choose("", "", "", "", "<unknown ssid>").source);
        assertEquals(WifiSelection.Source.NONE, WifiSelection.choose(null, null, null, null, null).source);
    }

    @Test
    public void whitespaceOnlyEntriesAreIgnored() {
        assertEquals(WifiSelection.Source.UNIT_GROUP,
                WifiSelection.choose("   ", "x", "DIRECT-a", "k", "Kisara").source);
    }
}
