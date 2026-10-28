package com.ejjat.aiplay.core.bt;

/**
 * Which Wi-Fi network the phone is told to join during the Bluetooth bootstrap.
 *
 * <p>Measured on a real unit: the phone answered {@code WifiConnectStatus = -3} (incorrect credentials,
 * as the community-decoded status codes have it) when AiPlay named the phone's own hotspot with no
 * password. A phone cannot join its own hotspot, and in a car the unit is not on any router. So the
 * default is a network the <em>unit creates</em> (a Wi-Fi Direct group); a network typed into Settings
 * still wins, for a unit that shares a router with the phone.</p>
 *
 * <p>Precedence: manual Settings entry, then the unit's own group, then the network the unit is
 * currently joined to (with whatever password was saved).</p>
 */
public final class WifiSelection {

    public enum Source { MANUAL, UNIT_GROUP, CURRENT_NETWORK, NONE }

    public static final class Choice {
        public final Source source;
        public final String ssid;
        public final String key;
        /** {@link WifiBootstrap#SECURITY_OPEN} or {@link WifiBootstrap#SECURITY_WPA2_PERSONAL}. */
        public final int securityMode;

        Choice(Source source, String ssid, String key) {
            this.source = source;
            this.ssid = ssid;
            this.key = key;
            this.securityMode = key.length() == 0 ? WifiBootstrap.SECURITY_OPEN
                    : WifiBootstrap.SECURITY_WPA2_PERSONAL;
        }
    }

    private WifiSelection() {
    }

    public static Choice choose(String manualSsid, String manualKey,
                                String groupSsid, String groupKey,
                                String currentSsid) {
        String ms = nz(manualSsid).trim();
        if (ms.length() > 0) {
            return new Choice(Source.MANUAL, ms, nz(manualKey));
        }
        String gs = nz(groupSsid).trim();
        if (gs.length() > 0) {
            return new Choice(Source.UNIT_GROUP, gs, nz(groupKey));
        }
        String cs = nz(currentSsid).trim();
        if (cs.length() > 0 && !"<unknown ssid>".equals(cs)) {
            return new Choice(Source.CURRENT_NETWORK, cs, nz(manualKey));
        }
        return new Choice(Source.NONE, "", "");
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
