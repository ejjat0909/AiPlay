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

    private AaTlsServer server;
    private MdnsAdvertiser advertiser;

    public static boolean isRunning() {
        return RUNNING.get();
    }

    public static void start(Context context) {
        Intent intent = new Intent(context, AaReceiverService.class).setAction(ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (RuntimeException error) {
            Diag.log("receiver", "could not start service", error);
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
        startEndpoint();
        return START_STICKY;
    }

    private void startEndpoint() {
        if (RUNNING.get()) {
            Diag.log("receiver", "already running");
            return;
        }
        int port = Prefs.receiverPort();
        server = new AaTlsServer(this, port, new AaTlsServer.Listener() {
            @Override
            public void onEvent(String message) {
                // Already recorded by Diag inside the server.
            }
        });
        try {
            server.start();
            RUNNING.set(true);
        } catch (Exception error) {
            Diag.log("receiver", "TLS endpoint failed to start", error);
            RUNNING.set(false);
            stopSelf();
            return;
        }

        String name = "AiPlay-" + Integer.toHexString(
                (Build.MODEL + Build.DEVICE).hashCode() & 0xFFFF);
        advertiser = new MdnsAdvertiser(this, name, port);
        advertiser.start();
    }

    private void stopEndpoint() {
        if (advertiser != null) {
            advertiser.stop();
            advertiser = null;
        }
        if (server != null) {
            server.stop();
            server = null;
        }
        RUNNING.set(false);
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }
}
