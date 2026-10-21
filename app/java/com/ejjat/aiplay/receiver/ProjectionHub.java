package com.ejjat.aiplay.receiver;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import com.ejjat.aiplay.core.aa.AaSession;
import com.ejjat.aiplay.device.Diag;
import com.ejjat.aiplay.ui.ProjectionActivity;

/**
 * Joins the phone's video (session thread) to the screen (UI thread).
 *
 * <p>One process-wide instance: there is one receiver and one projection screen. It implements
 * {@link AaSession.VideoSink}, owns the {@link VideoDecoder}, and opens
 * {@link ProjectionActivity} when the phone starts streaming.</p>
 */
public final class ProjectionHub implements AaSession.VideoSink, VideoDecoder.Listener {

    /** UI callbacks, always delivered on the main thread. */
    public interface Listener {
        void onStatus(String status);

        void onEnded();
    }

    private static ProjectionHub instance;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final VideoDecoder decoder = new VideoDecoder(this);

    private volatile Listener listener;
    private volatile String status = "idle";
    private volatile boolean streaming;

    private ProjectionHub(Context context) {
        this.context = context.getApplicationContext();
    }

    public static synchronized ProjectionHub get(Context context) {
        if (instance == null) {
            instance = new ProjectionHub(context);
        }
        return instance;
    }

    public String status() {
        return status;
    }

    /** True from the phone's start indication until it stops (or the session ends). */
    public boolean isStreaming() {
        return streaming;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
        if (listener != null) {
            listener.onStatus(status);
        }
    }

    public void attachSurface(Surface surface) {
        decoder.setSurface(surface);
    }

    public void detachSurface() {
        decoder.setSurface(null);
    }

    // ---- AaSession.VideoSink (session thread) ------------------------------------------------

    @Override
    public void onVideoSetup(int configIndex) {
        publish("phone is setting up video");
    }

    @Override
    public void onVideoStart(int session, int config) {
        streaming = true;
        publish("phone started streaming");
        main.post(new Runnable() {
            @Override
            public void run() {
                openScreen();
            }
        });
    }

    @Override
    public void onVideoFrame(long timestampMicros, byte[] data, int offset, int length) {
        decoder.feed(timestampMicros, data, offset, length);
    }

    @Override
    public void onVideoStop() {
        streaming = false;
        decoder.stop();
        publish("stream ended");
        main.post(new Runnable() {
            @Override
            public void run() {
                Listener current = listener;
                if (current != null) {
                    current.onEnded();
                }
            }
        });
    }

    @Override
    public void onVideoFocus(boolean focused) {
        Diag.log("video", "focus " + (focused ? "granted" : "released"));
    }

    // ---- VideoDecoder.Listener (session thread) ----------------------------------------------

    @Override
    public void onFirstFrame(int width, int height) {
        publish("showing " + width + "x" + height);
    }

    @Override
    public void onFailure(String reason) {
        publish(reason);
    }

    private void publish(final String text) {
        status = text;
        main.post(new Runnable() {
            @Override
            public void run() {
                Listener current = listener;
                if (current != null) {
                    current.onStatus(text);
                }
            }
        });
    }

    /**
     * Brings the projection screen up. Android 10+ blocks background activity starts, so this
     * works while AiPlay is visible (as in the test rig) but may be refused when the app is in
     * the background; the refusal is logged rather than hidden.
     */
    private void openScreen() {
        try {
            context.startActivity(new Intent(context, ProjectionActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        } catch (RuntimeException error) {
            Diag.log("video", "could not open the projection screen", error);
        }
    }
}
