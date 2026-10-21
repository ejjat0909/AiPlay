package com.ejjat.aiplay.receiver;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import com.ejjat.aiplay.core.bt.WifiBootstrap;
import com.ejjat.aiplay.device.Diag;
import com.ejjat.aiplay.device.Prefs;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builds what the head unit tells the phone over Bluetooth: where the receiver is and which Wi-Fi
 * network to join.
 *
 * <ul>
 *   <li><b>IP address</b>: read from the unit's own network interfaces (Wi-Fi first), never
 *       typed in. The port is the receiver's configured port.</li>
 *   <li><b>SSID and password</b>: from Settings. Android does not let an app read the password of
 *       the network it is on, so it has to be entered once. If no SSID is set, the current
 *       network's name is used when Android will say it.</li>
 *   <li><b>BSSID</b>: the access point's address, from the current connection when Android reveals
 *       it (needs the location permission), else left empty.</li>
 * </ul>
 */
final class WifiConfigProvider {

    private WifiConfigProvider() {
    }

    static WifiBootstrap.Config build(Context context) {
        WifiBootstrap.Config config = new WifiBootstrap.Config();
        config.port = Prefs.receiverPort();
        config.ipAddress = localIpv4();

        String ssid = Prefs.wifiSsid();
        String bssid = "";
        try {
            WifiManager manager = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = manager == null ? null : manager.getConnectionInfo();
            if (info != null) {
                String current = clean(info.getSSID());
                if (ssid.length() == 0 && current.length() > 0 && !"<unknown ssid>".equals(current)) {
                    ssid = current;
                }
                String address = info.getBSSID();
                // Without the location permission Android reports a fixed placeholder.
                if (address != null && !"02:00:00:00:00:00".equals(address)) {
                    bssid = address;
                }
            }
        } catch (RuntimeException error) {
            Diag.log("bluetooth", "could not read the Wi-Fi connection", error);
        }
        config.ssid = ssid;
        config.bssid = bssid;
        config.key = Prefs.wifiKey();
        config.securityMode = config.key.length() == 0
                ? WifiBootstrap.SECURITY_OPEN : WifiBootstrap.SECURITY_WPA2_PERSONAL;
        return config;
    }

    private static String clean(String ssid) {
        if (ssid == null) {
            return "";
        }
        if (ssid.length() >= 2 && ssid.startsWith("\"") && ssid.endsWith("\"")) {
            return ssid.substring(1, ssid.length() - 1);
        }
        return ssid;
    }

    /** First up, non-loopback IPv4 address; Wi-Fi and Ethernet interfaces before anything else. */
    static String localIpv4() {
        List<String> preferred = new ArrayList<String>();
        List<String> others = new ArrayList<String>();
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                String name = nif.getName();
                boolean lan = name.startsWith("wlan") || name.startsWith("eth");
                for (InetAddress address : Collections.list(nif.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        (lan ? preferred : others).add(address.getHostAddress());
                    }
                }
            }
        } catch (Exception error) {
            Diag.log("bluetooth", "could not list network interfaces", error);
        }
        if (!preferred.isEmpty()) {
            return preferred.get(0);
        }
        return others.isEmpty() ? "" : others.get(0);
    }
}
