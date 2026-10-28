package com.ejjat.aiplay.receiver;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;

import com.ejjat.aiplay.core.bt.PairingAdvisor;
import com.ejjat.aiplay.core.bt.WifiBootstrap;
import com.ejjat.aiplay.device.Diag;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The head unit's Bluetooth RFCOMM service for wireless Android Auto.
 *
 * <p>Registers the Android Auto wireless service ({@link WifiBootstrap#SERVICE_UUID}) as an RFCOMM
 * <em>server</em> and waits for the phone, then tells it where the TCP receiver is and which Wi-Fi
 * network to join ({@link WifiBootstrap}). The AA session itself runs over TCP ({@link AaServer}).</p>
 *
 * <p><b>What a phone needs, learned on a real unit.</b> A phone offers wireless Android Auto only if the
 * unit's Bluetooth service list contains this service <em>when the two are paired</em>; Android caches
 * the list at bonding. So the service must be registered <b>before</b> pairing (which is why the
 * receiver now starts at boot by default) and a phone that paired earlier has to Forget the unit and
 * pair again. {@link PairingAdvisor} says which of these applies, on screen and in the log.</p>
 *
 * <p>Robustness: Bluetooth being switched off and on re-registers the service; a dead server socket is
 * reopened instead of leaving the receiver silently deaf; every pairing/connection event is logged
 * under {@code bluetooth:}. Still <b>unverified against a connecting phone</b>.</p>
 */
final class BluetoothBootstrapServer {

    private static final String SERVICE_NAME = "AiPlay Android Auto";
    /** A phone that connects and then says nothing should not hold the single worker forever. */
    private static final long SESSION_TIMEOUT_MS = 30000L;
    private static final long REOPEN_DELAY_MS = 3000L;

    private final Context context;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object lock = new Object();
    /** Addresses bonded while the service was not registered (and still bonded). */
    private final Set<String> bondedWhileDown = new HashSet<String>();

    private BluetoothAdapter adapter;
    private BluetoothServerSocket serverSocket;
    private BleAdvertiser advertiser;
    private BroadcastReceiver events;
    private volatile int sessions;
    private volatile String connectedPhone;
    private String lastStatus = "";

    BluetoothBootstrapServer(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Starts; returns false (and says why in the log) when Bluetooth cannot be used at all. */
    boolean start() {
        if (!running.compareAndSet(false, true)) {
            return true;
        }
        if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)) {
            return fail("this unit has no Bluetooth");
        }
        if (!hasConnectPermission()) {
            return fail("Bluetooth permission not granted (BLUETOOTH_CONNECT); "
                    + "allow it when AiPlay asks, then restart the receiver");
        }
        adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            return fail("no Bluetooth adapter");
        }
        sessions = 0;
        connectedPhone = null;
        registerEvents();
        logBondedPhones();
        if (adapter.isEnabled()) {
            openService();
        } else {
            Diag.log("bluetooth", "Bluetooth is off; the service will start when it is switched on");
            publishStatus();
        }
        return true;
    }

    void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        unregisterEvents();
        closeService();
        Diag.log("bluetooth", "stopped");
    }

    // ---- service lifecycle -------------------------------------------------------------------

    private void openService() {
        synchronized (lock) {
            if (!running.get() || serverSocket != null) {
                return;
            }
            try {
                BluetoothServerSocket socket = adapter.listenUsingRfcommWithServiceRecord(
                        SERVICE_NAME, UUID.fromString(WifiBootstrap.SERVICE_UUID));
                serverSocket = socket;
                Diag.log("bluetooth", "listening for the phone on RFCOMM service "
                        + WifiBootstrap.SERVICE_UUID + " (adapter " + adapter.getName()
                        + ", scan mode " + scanModeName(adapter.getScanMode()) + ")");
                startAccepting(socket);
                advertiser = new BleAdvertiser(context, adapter);
                advertiser.start();
            } catch (SecurityException error) {
                Diag.log("bluetooth", "Bluetooth permission refused: " + error.getMessage());
            } catch (IOException error) {
                Diag.log("bluetooth", "could not open the RFCOMM service: " + error.getMessage()
                        + "; retrying");
                reopenLater();
            } catch (RuntimeException error) {
                Diag.log("bluetooth", "Bluetooth failed", error);
            }
        }
        publishStatus();
    }

    private void closeService() {
        synchronized (lock) {
            if (advertiser != null) {
                advertiser.stop();
                advertiser = null;
            }
            BluetoothServerSocket socket = serverSocket;
            serverSocket = null;
            close(socket);
        }
    }

    private boolean isCurrent(BluetoothServerSocket socket) {
        synchronized (lock) {
            return running.get() && socket == serverSocket;
        }
    }

    private void reopenLater() {
        Thread retry = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(REOPEN_DELAY_MS);
                } catch (InterruptedException stopped) {
                    return;
                }
                if (running.get() && adapter != null && adapter.isEnabled()) {
                    closeService();
                    openService();
                }
            }
        }, "aiplay-bt-reopen");
        retry.setDaemon(true);
        retry.start();
    }

    private void startAccepting(final BluetoothServerSocket socket) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop(socket);
            }
        }, "aiplay-bt-accept");
        thread.start();
    }

    private void acceptLoop(BluetoothServerSocket socket) {
        while (isCurrent(socket)) {
            BluetoothSocket client = null;
            try {
                client = socket.accept();
                String who = describe(client);
                sessions++;
                connectedPhone = who;
                Diag.log("bluetooth", "phone connected: " + who);
                // The phone's name is in the log, not on screen: a phone retries every few seconds
                // and a status that alternates between names and "waiting" is just noise.
                ReceiverState.setBluetooth("phone is connecting");
                handle(client);
            } catch (IOException error) {
                if (isCurrent(socket)) {
                    Diag.log("bluetooth", "accept failed: " + error.getMessage()
                            + "; reopening the service");
                    reopenLater();
                }
                return; // closed on purpose, or being reopened
            } catch (SecurityException error) {
                Diag.log("bluetooth", "permission revoked: " + error.getMessage());
                return;
            } finally {
                connectedPhone = null;
                close(client);
            }
        }
    }

    private void handle(final BluetoothSocket socket) {
        // BluetoothSocket has no read timeout: close it from a watchdog instead.
        final AtomicBoolean done = new AtomicBoolean(false);
        Thread watchdog = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(SESSION_TIMEOUT_MS);
                } catch (InterruptedException finished) {
                    return;
                }
                if (!done.get()) {
                    Diag.log("bluetooth", "no activity for " + SESSION_TIMEOUT_MS / 1000
                            + " s, closing the connection");
                    close(socket);
                }
            }
        }, "aiplay-bt-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        try {
            WifiBootstrap.Config config = WifiConfigProvider.build(context);
            WifiBootstrap.Result result = WifiBootstrap.run(socket.getInputStream(),
                    socket.getOutputStream(), config, new WifiBootstrap.Sink() {
                        @Override
                        public void onEvent(String message) {
                            Diag.log("bluetooth", message);
                        }
                    });
            Diag.log("bluetooth", result.complete()
                    ? "phone was given the receiver address and Wi-Fi details"
                    : "bootstrap ended without giving the phone the Wi-Fi details"
                            + (result.refused == null ? "" : ": " + result.refused));
        } catch (IOException error) {
            Diag.log("bluetooth", "bootstrap connection ended: " + error.getMessage());
        } finally {
            done.set(true);
            watchdog.interrupt();
            connectedPhone = null;
            publishStatus();
        }
    }

    // ---- pairing events ----------------------------------------------------------------------

    private void registerEvents() {
        events = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                try {
                    onBluetoothEvent(intent);
                } catch (RuntimeException error) {
                    Diag.log("bluetooth", "event handling failed", error);
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        try {
            context.registerReceiver(events, filter);
        } catch (RuntimeException error) {
            Diag.log("bluetooth", "could not watch Bluetooth events", error);
            events = null;
        }
    }

    private void unregisterEvents() {
        if (events != null) {
            try {
                context.unregisterReceiver(events);
            } catch (RuntimeException ignored) {
                // already gone
            }
            events = null;
        }
    }

    private void onBluetoothEvent(Intent intent) {
        String action = intent.getAction();
        if (action == null) {
            return;
        }
        if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            if (state == BluetoothAdapter.STATE_ON) {
                Diag.log("bluetooth", "Bluetooth switched on");
                openService();
            } else if (state == BluetoothAdapter.STATE_TURNING_OFF
                    || state == BluetoothAdapter.STATE_OFF) {
                Diag.log("bluetooth", "Bluetooth switched off");
                closeService();
                publishStatus();
            }
            return;
        }
        if (BluetoothAdapter.ACTION_SCAN_MODE_CHANGED.equals(action)) {
            Diag.log("bluetooth", "scan mode now " + scanModeName(intent.getIntExtra(
                    BluetoothAdapter.EXTRA_SCAN_MODE, BluetoothAdapter.ERROR)));
            return;
        }
        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        String who = device == null ? "(unknown device)" : label(device);
        if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
            int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
            if (state == BluetoothDevice.BOND_BONDED && device != null) {
                boolean serviceUp;
                synchronized (lock) {
                    serviceUp = serverSocket != null;
                    if (!serviceUp) {
                        bondedWhileDown.add(device.getAddress());
                    }
                }
                Diag.log("bluetooth", "paired with " + who + (serviceUp
                        ? " (AiPlay's service was running, so the phone can see it)"
                        : " while AiPlay's service was NOT running; this phone may need to pair again"));
            } else if (state == BluetoothDevice.BOND_NONE && device != null) {
                synchronized (lock) {
                    bondedWhileDown.remove(device.getAddress());
                }
                Diag.log("bluetooth", "pairing with " + who + " removed");
            } else if (state == BluetoothDevice.BOND_BONDING) {
                Diag.log("bluetooth", "pairing with " + who + " in progress");
            }
            publishStatus();
        } else if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
            Diag.log("bluetooth", "link up with " + who);
        } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
            Diag.log("bluetooth", "link down with " + who);
        }
    }

    private void logBondedPhones() {
        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            Diag.log("bluetooth", bonded.size() + " paired device(s) at start"
                    + (bonded.isEmpty() ? "" : "; if none of them opens Android Auto, Forget the unit "
                    + "on the phone and pair again while this receiver is running"));
            for (BluetoothDevice device : bonded) {
                Diag.log("bluetooth", "  paired: " + label(device));
            }
        } catch (RuntimeException error) {
            Diag.log("bluetooth", "could not list paired devices", error);
        }
    }

    private void publishStatus() {
        if (connectedPhone != null) {
            ReceiverState.setBluetooth("phone is connecting");
            return;
        }
        boolean on = false;
        int bonded = 0;
        try {
            on = adapter != null && adapter.isEnabled();
            if (on) {
                bonded = adapter.getBondedDevices().size();
            }
        } catch (RuntimeException ignored) {
            // report what we know
        }
        boolean up;
        boolean stale;
        synchronized (lock) {
            up = serverSocket != null;
            stale = !bondedWhileDown.isEmpty();
        }
        PairingAdvisor.Advice advice = PairingAdvisor.advise(
                new PairingAdvisor.Facts(on, up, bonded, stale, sessions));
        ReceiverState.setBluetooth(advice.status);
        if (!advice.status.equals(lastStatus)) {
            lastStatus = advice.status;
            Diag.log("bluetooth", "status: " + advice.status + " | " + advice.hint);
        }
    }

    // ---- helpers -----------------------------------------------------------------------------

    private boolean fail(String reason) {
        running.set(false);
        Diag.log("bluetooth", "bootstrap unavailable: " + reason);
        ReceiverState.setBluetooth("unavailable: " + reason);
        return false;
    }

    private boolean hasConnectPermission() {
        if (Build.VERSION.SDK_INT < 31) {
            return true; // BLUETOOTH / BLUETOOTH_ADMIN are install-time permissions here
        }
        return context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static String scanModeName(int mode) {
        switch (mode) {
            case BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE:
                return "discoverable";
            case BluetoothAdapter.SCAN_MODE_CONNECTABLE:
                return "connectable (not discoverable)";
            case BluetoothAdapter.SCAN_MODE_NONE:
                return "off";
            default:
                return String.valueOf(mode);
        }
    }

    private static String label(BluetoothDevice device) {
        try {
            return device.getName() + " [" + device.getAddress() + "]";
        } catch (RuntimeException error) {
            return "[" + device.getAddress() + "]";
        }
    }

    private static String describe(BluetoothSocket socket) {
        try {
            return label(socket.getRemoteDevice());
        } catch (RuntimeException error) {
            return "(unknown device)";
        }
    }

    private static void close(BluetoothSocket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // nothing useful to do
            }
        }
    }

    private static void close(BluetoothServerSocket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // nothing useful to do
            }
        }
    }
}
