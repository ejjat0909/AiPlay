package com.ejjat.aiplay.receiver;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import com.ejjat.aiplay.core.bt.WifiBootstrap;
import com.ejjat.aiplay.core.bt.WifiSelection;
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
 *   <li><b>SSID and password</b>: a network typed into Settings, else the Wi-Fi Direct group the
 *       unit creates ({@link WifiDirectGroup}), else the network the unit is on
 *       ({@link WifiSelection} has the precedence and its reasons).</li>
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

        String currentSsid = "";
        String currentBssid = "";
        try {
            WifiManager manager = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = manager == null ? null : manager.getConnectionInfo();
            if (info != null) {
                currentSsid = clean(info.getSSID());
                String address = info.getBSSID();
                // Without the location permission Android reports a fixed placeholder.
                if (address != null && !"02:00:00:00:00:00".equals(address)) {
                    currentBssid = address;
                }
            }
        } catch (RuntimeException error) {
            Diag.log("bluetooth", "could not read the Wi-Fi connection", error);
        }

        WifiDirectGroup.Info group = WifiDirectGroup.info();
        WifiSelection.Choice choice = WifiSelection.choose(Prefs.wifiSsid(), Prefs.wifiKey(),
                group == null ? "" : group.ssid, group == null ? "" : group.passphrase, currentSsid);
        config.ssid = choice.ssid;
        config.key = choice.key;
        config.securityMode = choice.securityMode;
        switch (choice.source) {
            case UNIT_GROUP:
                // The unit's own network: the receiver is at the group owner's address, and the
                // BSSID is the group owner's hardware address.
                config.ipAddress = WifiDirectGroup.ipOf(group.iface);
                config.bssid = group.bssid;
                break;
            case CURRENT_NETWORK:
                config.bssid = currentBssid;
                break;
            default:
                config.bssid = "";
                break;
        }
        Diag.log("bluetooth", "Wi-Fi for the phone: " + describe(choice.source));
        return config;
    }

    private static String describe(WifiSelection.Source source) {
        switch (source) {
            case MANUAL:
                return "the network set in Settings";
            case UNIT_GROUP:
                return "the unit's own Wi-Fi Direct network";
            case CURRENT_NETWORK:
                return "the network the unit is on (a phone hotspot cannot be joined by the phone "
                        + "that hosts it; set a network in Settings or allow Wi-Fi Direct)";
            default:
                return "none available";
        }
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
