package com.ejjat.aiplay.receiver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import com.ejjat.aiplay.R;
import com.ejjat.aiplay.device.Diag;
import com.ejjat.aiplay.device.Prefs;
import com.ejjat.aiplay.ui.HomeActivity;

import java.util.concurrent.atomic.AtomicBoolean;

/** Foreground owner of the Android Auto endpoint. One session at a time, always visible. */
public final class AaReceiverService extends Service {

    public static final String ACTION_START = "com.ejjat.aiplay.action.START_RECEIVER";
    public static final String ACTION_STOP = "com.ejjat.aiplay.action.STOP_RECEIVER";

    private static final String CHANNEL_ID = "receiver";
    private static final int NOTIFICATION_ID = 1001;
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    /** Serialises starting and stopping; the start runs on its own thread, stop may come any time. */
    private final Object lifecycle = new Object();
    private Thread startThread;

    private AaServer server;
    private MdnsAdvertiser advertiser;
    private BluetoothBootstrapServer bluetooth;
    private WifiDirectGroup wifiGroup;

    public static boolean isRunning() {
        return RUNNING.get();
    }

    public static void start(Context context) {
        if (!RUNNING.get()) {
            ReceiverState.set(ReceiverState.State.STARTING, "");
        }
        Intent intent = new Intent(context, AaReceiverService.class).setAction(ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (RuntimeException error) {
            Diag.log("receiver", "could not start service", error);
            ReceiverState.set(ReceiverState.State.FAILED, refusal(error));
        }
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, AaReceiverService.class).setAction(ACTION_STOP);
        try {
            context.startService(intent);
        } catch (RuntimeException error) {
            Diag.log("receiver", "could not stop service", error);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            Diag.log("receiver", "stop requested");
            stopEndpoint();
            stopSelf();
            return START_NOT_STICKY;
        }
        startForegroundCompat();
        beginStart();
        return START_STICKY;
    }

    /**
     * Starting means loading the keystore, binding the port, registering mDNS and opening Bluetooth,
     * which takes seconds on a slow unit. {@code onStartCommand} runs on the main thread, so doing it
     * there froze the screen: the "Starting..." state was set but never drawn before the work
     * finished, which defeated the whole point of showing it (and risks an ANR on a slow head unit).
     */
    private void beginStart() {
        synchronized (lifecycle) {
            if (RUNNING.get() || (startThread != null && startThread.isAlive())) {
                Diag.log("receiver", "already running or starting");
                return;
            }
            startThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    startEndpoint();
                }
            }, "aiplay-receiver-start");
            startThread.start();
        }
    }

    private void startEndpoint() {
        synchronized (lifecycle) {
            startEndpointLocked();
        }
    }

    private void startEndpointLocked() {
        if (RUNNING.get()) {
            Diag.log("receiver", "already running");
            return;
        }
        int port = Prefs.receiverPort();
        server = new AaServer(this, port, new AaServer.Listener() {
            @Override
            public void onEvent(String message) {
                // Already recorded by Diag inside the server.
            }
        });
        try {
            server.start();
            RUNNING.set(true);
        } catch (Exception error) {
            Diag.log("receiver", "receiver failed to start", error);
            RUNNING.set(false);
            ReceiverState.set(ReceiverState.State.FAILED, describe(error));
            stopSelf();
            return;
        }

        String name = "AiPlay-" + Integer.toHexString(
                (Build.MODEL + Build.DEVICE).hashCode() & 0xFFFF);
        advertiser = new MdnsAdvertiser(this, name, port);
        advertiser.start();

        // Optional: without Bluetooth the receiver still works for a client that already knows
        // its address (the test rig). It just cannot be found by a phone.
        // The network the phone is sent must exist before the phone asks for it.
        wifiGroup = new WifiDirectGroup(this);
        wifiGroup.start();

        bluetooth = new BluetoothBootstrapServer(this);
        bluetooth.start();
        ReceiverState.set(ReceiverState.State.RUNNING, "");
    }

    /**
     * Why Android refused to start the service. Since Android 12 an app in the background may not
     * start a foreground service, which is exactly the situation of a start triggered by the
     * ignition; the fix is the user's, so say so instead of printing an exception name.
     */
    static String refusal(RuntimeException error) {
        if (error.getClass().getSimpleName().contains("NotAllowed")) {
            return "Android does not let an app in the background start this. In AiPlay's "
                    + "Settings, allow background start (battery optimization), or open AiPlay "
                    + "and press Start.";
        }
        return "Android refused to start the service: " + error.getMessage();
    }

    private static String describe(Exception error) {
        String message = error.getMessage();
        if (error instanceof java.net.BindException) {
            return "port " + Prefs.receiverPort() + " is already in use by another app. Close it, "
                    + "or change the receiver port in Settings.";
        }
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private void stopEndpoint() {
        synchronized (lifecycle) {
            stopEndpointLocked();
        }
    }

    private void stopEndpointLocked() {
        if (bluetooth != null) {
            bluetooth.stop();
            bluetooth = null;
        }
        if (wifiGroup != null) {
            wifiGroup.stop();
            wifiGroup = null;
        }
        if (advertiser != null) {
            advertiser.stop();
            advertiser = null;
        }
        if (server != null) {
            server.stop();
            server = null;
        }
        RUNNING.set(false);
        if (ReceiverState.state() != ReceiverState.State.FAILED) {
            ReceiverState.set(ReceiverState.State.STOPPED, "");
        }
    }

    @Override
    public void onDestroy() {
        Diag.log("receiver", "service destroyed");
        stopEndpoint();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notification_channel_desc));
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private void startForegroundCompat() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, HomeActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        Notification notification = builder
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_body, Prefs.receiverPort()))
                .setSmallIcon(R.drawable.ic_stat_aiplay)
                .setContentIntent(open)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification);
            return;
        }
        try {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } catch (SecurityException refused) {
            // Android 14+ requires a Bluetooth/USB grant for connectedDevice. A phone used purely
            // over Wi-Fi has neither, so fall back to the sanctioned specialUse type.
            Diag.log("receiver", "connectedDevice FGS refused, falling back to specialUse", refused);
            try {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } catch (RuntimeException failed) {
                Diag.log("receiver", "foreground service unavailable on this ROM", failed);
                stopSelf();
            }
        }
    }
}
