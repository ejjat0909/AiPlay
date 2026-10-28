package com.ejjat.aiplay.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Handler;
import android.os.Looper;

import com.ejjat.aiplay.device.Diag;
import com.ejjat.aiplay.device.Prefs;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/**
 * The Wi-Fi network the unit creates for the phone: a Wi-Fi Direct group the unit owns.
 *
 * <p>Why: the phone joins a Wi-Fi network named in the Bluetooth bootstrap. In a car the unit is not on
 * any router, and a phone cannot join its own hotspot (measured: telling the phone to join the hotspot
 * the unit was using made it answer "incorrect credentials"). A Wi-Fi Direct group owner is an ordinary
 * WPA2 access point to the phone, needs no root and no privileged permission, and the framework picks
 * the name and passphrase, which {@link #info()} then reports for the bootstrap.</p>
 *
 * <p>Skipped when the user typed a network into Settings (router mode). Best effort: if this unit
 * cannot create a group, the log says why and the bootstrap falls back to the current network.</p>
 */
final class WifiDirectGroup {

    /** The group as the phone needs to know it. The passphrase is never logged. */
    static final class Info {
        final String ssid;
        final String passphrase;
        final String iface;
        /** The group's hardware address (the owner's), which the phone's bootstrap expects filled in. */
        final String bssid;

        Info(String ssid, String passphrase, String iface, String bssid) {
            this.ssid = ssid;
            this.passphrase = passphrase;
            this.iface = iface;
            this.bssid = bssid == null ? "" : bssid;
        }
    }

    /** Android's fixed address for a Wi-Fi Direct group owner, used until the interface reports one. */
    static final String DEFAULT_GROUP_OWNER_IP = "192.168.49.1";

    private static final long RETRY_MS = 5000L;
    private static final long INFO_POLL_MS = 600L;
    private static final int INFO_POLLS = 8;
    private static final int MAX_CREATE_ATTEMPTS = 6;

    private static volatile WifiDirectGroup current;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile Info info;
    private volatile boolean running;
    private int createAttempts;

    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private BroadcastReceiver receiver;

    WifiDirectGroup(Context context) {
        this.context = context.getApplicationContext();
    }

    /** The active group, or null when there is none (or the user chose a router network). */
    static Info info() {
        WifiDirectGroup group = current;
        return group == null ? null : group.info;
    }

    void start() {
        if (running) {
            return;
        }
        if (Prefs.wifiSsid().length() > 0) {
            Diag.log("wifi", "a network is set in Settings; not creating a Wi-Fi Direct group");
            return;
        }
        if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
            Diag.log("wifi", "this unit has no Wi-Fi Direct; the phone will be sent the current network");
            return;
        }
        try {
            manager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
            if (manager == null) {
                Diag.log("wifi", "Wi-Fi Direct service not available");
                return;
            }
            channel = manager.initialize(context, Looper.getMainLooper(), null);
            if (channel == null) {
                Diag.log("wifi", "could not open a Wi-Fi Direct channel");
                return;
            }
            running = true;
            current = this;
            createAttempts = 0;
            watch();
            ensureGroup();
        } catch (RuntimeException error) {
            running = false;
            current = null;
            Diag.log("wifi", "Wi-Fi Direct could not start", error);
        }
    }

    void stop() {
        if (!running) {
            return;
        }
        running = false;
        current = null;
        info = null;
        ReceiverState.setWifi("", "");
        handler.removeCallbacksAndMessages(null);
        if (receiver != null) {
            try {
                context.unregisterReceiver(receiver);
            } catch (RuntimeException ignored) {
                // already gone
            }
            receiver = null;
        }
        try {
            manager.removeGroup(channel, null);
        } catch (RuntimeException ignored) {
            // the framework drops the group with the process anyway
        }
        Diag.log("wifi", "Wi-Fi Direct group released");
    }

    private void watch() {
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (!running) {
                    return;
                }
                // The group came or went (another app, Wi-Fi toggled): make sure we still own one.
                handler.removeCallbacks(recheck);
                handler.postDelayed(recheck, 1500L);
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        try {
            context.registerReceiver(receiver, filter);
        } catch (RuntimeException error) {
            Diag.log("wifi", "could not watch Wi-Fi Direct state", error);
            receiver = null;
        }
    }

    private final Runnable recheck = new Runnable() {
        @Override
        public void run() {
            if (running) {
                ensureGroup();
            }
        }
    };

    private void ensureGroup() {
        manager.requestGroupInfo(channel, new WifiP2pManager.GroupInfoListener() {
            @Override
            public void onGroupInfoAvailable(WifiP2pGroup group) {
                if (!running) {
                    return;
                }
                if (group != null && group.isGroupOwner()) {
                    adopt(group);
                } else if (group != null) {
                    Diag.log("wifi", "unit is a client of another Wi-Fi Direct group; leaving it");
                    manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
                        @Override
                        public void onSuccess() {
                            createGroup();
                        }

                        @Override
                        public void onFailure(int reason) {
                            createGroup();
                        }
                    });
                } else {
                    info = null;
                    ReceiverState.setWifi("", "");
                    createGroup();
                }
            }
        });
    }

    private void createGroup() {
        if (!running) {
            return;
        }
        createAttempts++;
        manager.createGroup(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Diag.log("wifi", "Wi-Fi Direct group requested");
                pollInfo(0);
            }

            @Override
            public void onFailure(int reason) {
                Diag.log("wifi", "could not create a Wi-Fi Direct group: " + describe(reason)
                        + " (attempt " + createAttempts + ")");
                if (running && createAttempts < MAX_CREATE_ATTEMPTS && reason != WifiP2pManager.P2P_UNSUPPORTED) {
                    handler.postDelayed(recheck, RETRY_MS);
                } else if (running) {
                    Diag.log("wifi", "giving up on Wi-Fi Direct; the phone will be sent the current network");
                }
            }
        });
    }

    private void pollInfo(final int poll) {
        if (!running) {
            return;
        }
        manager.requestGroupInfo(channel, new WifiP2pManager.GroupInfoListener() {
            @Override
            public void onGroupInfoAvailable(WifiP2pGroup group) {
                if (!running) {
                    return;
                }
                if (group != null && group.isGroupOwner() && group.getPassphrase() != null) {
                    adopt(group);
                } else if (poll < INFO_POLLS) {
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            pollInfo(poll + 1);
                        }
                    }, INFO_POLL_MS);
                } else {
                    Diag.log("wifi", "the Wi-Fi Direct group never reported a name and passphrase");
                }
            }
        });
    }

    private void adopt(WifiP2pGroup group) {
        String passphrase = group.getPassphrase();
        if (passphrase == null) {
            pollInfo(0);
            return;
        }
        String owner = group.getOwner() == null ? "" : group.getOwner().deviceAddress;
        Info next = new Info(group.getNetworkName(), passphrase, group.getInterface(), owner);
        boolean changed = info == null || !next.ssid.equals(info.ssid)
                || !next.passphrase.equals(info.passphrase);
        info = next;
        ReceiverState.setWifi(next.ssid, next.passphrase);
        if (changed) {
            Diag.log("wifi", "Wi-Fi Direct group up: network " + next.ssid + ", interface "
                    + next.iface + ", address " + ipOf(next.iface));
        }
    }

    /** IPv4 address of the group's interface, or the fixed group-owner address until it has one. */
    static String ipOf(String iface) {
        if (iface != null && iface.length() > 0) {
            try {
                NetworkInterface nif = NetworkInterface.getByName(iface);
                if (nif != null) {
                    for (InetAddress address : Collections.list(nif.getInetAddresses())) {
                        if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                            return address.getHostAddress();
                        }
                    }
                }
            } catch (Exception error) {
                Diag.log("wifi", "could not read the group's address", error);
            }
        }
        return DEFAULT_GROUP_OWNER_IP;
    }

    private static String describe(int reason) {
        switch (reason) {
            case WifiP2pManager.P2P_UNSUPPORTED:
                return "Wi-Fi Direct is not supported";
            case WifiP2pManager.BUSY:
                return "the system is busy";
            case WifiP2pManager.ERROR:
                return "internal error";
            default:
                return "code " + reason;
        }
    }
}
