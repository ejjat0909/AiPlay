package com.ejjat.aiplay.receiver;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import com.ejjat.aiplay.core.bt.WifiBootstrap;
import com.ejjat.aiplay.device.Diag;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The head unit's Bluetooth RFCOMM service for wireless Android Auto.
 *
 * <p>Registers the Android Auto wireless service ({@link WifiBootstrap#SERVICE_UUID}) as an RFCOMM
 * <em>server</em> and waits for the phone, then tells it where the TCP receiver is and which Wi-Fi
 * network to join ({@link WifiBootstrap}). That is the whole job: the AA session itself runs over
 * TCP ({@link AaServer}).</p>
 *
 * <p><b>Not exercised on hardware.</b> The message exchange is tested over a loopback; this class,
 * the Bluetooth stack and the phone's willingness to connect have never run together. In
 * particular the dongle this was modelled on also advertises the UUID over BLE and connects a
 * headset profile to nudge the phone; AiPlay does neither yet. The phone must also already be paired
 * with this unit. Every step logs under {@code bluetooth:} so a failure is visible in Diagnostics.</p>
 */
final class BluetoothBootstrapServer {

    private static final String SERVICE_NAME = "AiPlay Android Auto";
    /** A phone that connects and then says nothing should not hold the single worker forever. */
    private static final long SESSION_TIMEOUT_MS = 30000L;

    private final Context context;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private BluetoothServerSocket serverSocket;
    private Thread thread;

    BluetoothBootstrapServer(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Starts listening; returns false (and says why in the log) when Bluetooth cannot be used. */
    boolean start() {
        if (!running.compareAndSet(false, true)) {
            return true;
        }
        try {
            if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)) {
                return fail("this unit has no Bluetooth");
            }
            if (!hasConnectPermission()) {
                return fail("Bluetooth permission not granted (BLUETOOTH_CONNECT); "
                        + "allow it when AiPlay asks, then restart the receiver");
            }
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) {
                return fail("no Bluetooth adapter");
            }
            if (!adapter.isEnabled()) {
                return fail("Bluetooth is turned off");
            }
            serverSocket = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME,
                    UUID.fromString(WifiBootstrap.SERVICE_UUID));
            Diag.log("bluetooth", "listening for the phone on RFCOMM service "
                    + WifiBootstrap.SERVICE_UUID + " (adapter " + adapter.getName() + ")");
        } catch (SecurityException error) {
            return fail("Bluetooth permission refused: " + error.getMessage());
        } catch (IOException error) {
            return fail("could not open the RFCOMM service: " + error.getMessage());
        } catch (RuntimeException error) {
            return fail("Bluetooth failed: " + error);
        }

        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "aiplay-bt-accept");
        thread.start();
        return true;
    }

    void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        close(serverSocket);
        serverSocket = null;
        if (thread != null) {
            thread.interrupt();
        }
        Diag.log("bluetooth", "stopped");
    }

    private boolean fail(String reason) {
        running.set(false);
        Diag.log("bluetooth", "bootstrap unavailable: " + reason);
        return false;
    }

    private boolean hasConnectPermission() {
        if (Build.VERSION.SDK_INT < 31) {
            return true; // BLUETOOTH / BLUETOOTH_ADMIN are install-time permissions here
        }
        return context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void acceptLoop() {
        while (running.get()) {
            BluetoothSocket socket = null;
            try {
                socket = serverSocket.accept();
                String who = describe(socket);
                Diag.log("bluetooth", "phone connected: " + who);
                handle(socket);
            } catch (IOException error) {
                if (running.get()) {
                    Diag.log("bluetooth", "accept failed: " + error.getMessage());
                    return; // a dead server socket does not recover; restart the receiver
                }
            } catch (SecurityException error) {
                Diag.log("bluetooth", "permission revoked: " + error.getMessage());
                return;
            } finally {
                close(socket);
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
        }
    }

    private static String describe(BluetoothSocket socket) {
        try {
            return socket.getRemoteDevice().getName() + " [" + socket.getRemoteDevice().getAddress()
                    + "]";
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
