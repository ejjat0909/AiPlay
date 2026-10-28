package com.ejjat.aiplay.core.qr;

/**
 * The text a phone's camera understands as "join this Wi-Fi network":
 * {@code WIFI:T:WPA;S:<ssid>;P:<password>;;}. Both Android's camera/Google Lens and iOS read it.
 *
 * <p>The characters {@code \ ; , : "} in the name or password are escaped with a backslash, as the
 * format requires.</p>
 */
public final class WifiQr {

    private WifiQr() {
    }

    public static String payload(String ssid, String password) {
        StringBuilder out = new StringBuilder("WIFI:");
        if (password == null || password.length() == 0) {
            out.append("T:nopass;");
        } else {
            out.append("T:WPA;");
        }
        out.append("S:").append(escape(ssid)).append(';');
        if (password != null && password.length() > 0) {
            out.append("P:").append(escape(password)).append(';');
        }
        out.append(';');
        return out.toString();
    }

    static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || c == ';' || c == ',' || c == ':' || c == '"') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }
}
