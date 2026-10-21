package com.ejjat.aiplay.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.Surface;

import com.ejjat.aiplay.core.aa.AaAudio;
import com.ejjat.aiplay.core.aa.AaInput;
import com.ejjat.aiplay.core.aa.AaSensor;
import com.ejjat.aiplay.core.aa.AaSession;
import com.ejjat.aiplay.core.audio.AudioMixPolicy;
import com.ejjat.aiplay.core.input.TouchMapper;
import com.ejjat.aiplay.device.Diag;
import com.ejjat.aiplay.ui.ProjectionActivity;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Joins the phone's session (network thread) to the screen, the speakers and the touch screen.
 *
 * <p>One process-wide instance: there is one receiver and one projection screen. It is the video,
 * audio and sensor sink of the {@link AaSession}, owns the {@link VideoDecoder} and one
 * {@link AudioPlayer} per audio channel, opens {@link ProjectionActivity} when the phone starts
 * streaming, and carries input the other way: touches and keys captured by the activity are sent to
 * the phone from a dedicated thread (a socket write on the UI thread would be refused).</p>
 */
public final class ProjectionHub implements AaSession.VideoSink, AaSession.AudioSink,
        AaSession.SensorSource, VideoDecoder.Listener {

    /** UI callbacks, always delivered on the main thread. */
    public interface Listener {
        void onStatus(String status);

        void onEnded();
    }

    /** Touch coordinates sent to the phone are in the video's resolution. */
    public static final int VIDEO_WIDTH = 1280;
    public static final int VIDEO_HEIGHT = 720;

    /** Drag events beyond this many waiting to be sent are dropped; the newest is what matters. */
    private static final int MAX_PENDING_DRAGS = 2;

    private static ProjectionHub instance;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final VideoDecoder decoder = new VideoDecoder(this);
    private final TouchMapper mapper = new TouchMapper(VIDEO_WIDTH, VIDEO_HEIGHT);
    private final AudioMixPolicy mixPolicy = new AudioMixPolicy();
    private final ConcurrentHashMap<Integer, AudioPlayer> players =
            new ConcurrentHashMap<Integer, AudioPlayer>();
    private final AtomicInteger pendingDrags = new AtomicInteger();
    private final ExecutorService inputThread = Executors.newSingleThreadExecutor(
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "aiplay-input");
                    thread.setDaemon(true);
                    return thread;
                }
            });

    private volatile Listener listener;
    private volatile String status = "idle";
    private volatile boolean streaming;
    private volatile AaSession session;
    private volatile boolean lastNight;
    private boolean inputErrorLogged;
    private BroadcastReceiver nightWatcher;

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

    /** Maps the projection screen's coordinates to the phone's. */
    public TouchMapper mapper() {
        return mapper;
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

    // ---- session lifetime ---------------------------------------------------------------------

    /** Called by the server when a phone's session starts. */
    public void attachSession(AaSession newSession) {
        session = newSession;
        lastNight = isNight();
        pendingDrags.set(0);
        inputErrorLogged = false;
        registerNightWatcher();
    }

    /** Called by the server when the session ends, whatever the reason. */
    public void detachSession(AaSession ended) {
        if (session == ended) {
            session = null;
            unregisterNightWatcher();
        }
        streaming = false;
        mixPolicy.reset();
        for (AudioPlayer player : players.values()) {
            player.stop();
        }
    }

    // ---- AaSession.VideoSink (session thread) -------------------------------------------------

    @Override
    public void onVideoSetup(int configIndex) {
        publish("phone is setting up video");
    }

    @Override
    public void onVideoStart(int sessionId, int config) {
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

    // ---- AaSession.AudioSink (session thread) -------------------------------------------------

    @Override
    public void onAudioStart(final int channel, int sessionId) {
        final AaAudio.Format format = AaAudio.formatFor(channel);
        if (format == null) {
            return;
        }
        mixPolicy.setActive(channel, true);
        final AudioPlayer[] self = new AudioPlayer[1];
        self[0] = new AudioPlayer(channel, format, new AudioPlayer.Listener() {
            @Override
            public void onFinished(int finished) {
                // Only the player that is still current may end the ducking: a new stream on the
                // same channel may already have replaced this one.
                if (players.remove(finished, self[0])) {
                    mixPolicy.setActive(finished, false);
                    applyVolumes();
                }
            }
        });
        if (!self[0].start(mixPolicy.volumeFor(channel))) {
            mixPolicy.setActive(channel, false);
            return;
        }
        AudioPlayer replaced = players.put(channel, self[0]);
        if (replaced != null) {
            replaced.stop();
        }
        applyVolumes();
    }

    @Override
    public void onAudioData(int channel, long timestampMicros, byte[] data, int offset,
                            int length) {
        AudioPlayer player = players.get(channel);
        if (player != null) {
            player.write(data, offset, length);
        }
    }

    @Override
    public void onAudioStop(int channel) {
        AudioPlayer player = players.get(channel);
        if (player != null) {
            player.stop();      // drains; ducking ends when it has finished playing
        }
    }

    @Override
    public void onAudioFocus(int requestedType, int grantedState) {
        Diag.log("audio", "focus request " + requestedType + " -> granted state " + grantedState);
    }

    private void applyVolumes() {
        for (java.util.Map.Entry<Integer, AudioPlayer> entry : players.entrySet()) {
            entry.getValue().setVolume(mixPolicy.volumeFor(entry.getKey()));
        }
    }

    // ---- AaSession.SensorSource ---------------------------------------------------------------

    @Override
    public boolean isNight() {
        int mode = context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * The head unit has no speed source, so it never reports the car as moving; the phone then
     * never restricts its UI. OpenAuto does the same.
     */
    @Override
    public int drivingStatus() {
        return AaSensor.DRIVING_UNRESTRICTED;
    }

    private void registerNightWatcher() {
        unregisterNightWatcher();
        nightWatcher = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ignored, Intent intent) {
                final boolean night = isNight();
                if (night == lastNight) {
                    return;
                }
                lastNight = night;
                final AaSession current = session;
                if (current == null) {
                    return;
                }
                Diag.log("sensor", "head unit switched to " + (night ? "night" : "day")
                        + " mode; telling the phone");
                inputThread.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            current.pushNightMode(night);
                        } catch (IOException error) {
                            Diag.log("sensor", "could not send night mode: " + error.getMessage());
                        }
                    }
                });
            }
        };
        context.registerReceiver(nightWatcher, new IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED));
    }

    private void unregisterNightWatcher() {
        if (nightWatcher != null) {
            try {
                context.unregisterReceiver(nightWatcher);
            } catch (IllegalArgumentException ignored) {
                // already unregistered
            }
            nightWatcher = null;
        }
    }

    // ---- input: screen -> phone (UI thread in, input thread out) ------------------------------

    /**
     * Sends one touch indication to the phone. Returns at once; the write happens on the input
     * thread. When the network falls behind, surplus drag events are dropped (the phone only needs
     * the latest finger position); presses and releases are never dropped.
     */
    public void sendTouch(final int action, final int actionIndex, final int[] xs, final int[] ys,
                          final int[] pointerIds) {
        final AaSession current = session;
        if (current == null || !current.isInputBound()) {
            return;
        }
        final boolean drag = action == AaInput.DRAG;
        if (drag && pendingDrags.get() >= MAX_PENDING_DRAGS) {
            return;
        }
        if (drag) {
            pendingDrags.incrementAndGet();
        }
        final long timestamp = System.currentTimeMillis() * 1000L;
        inputThread.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    current.sendTouch(timestamp, action, actionIndex, xs, ys, pointerIds);
                } catch (IOException error) {
                    logInputError(error);
                } finally {
                    if (drag) {
                        pendingDrags.decrementAndGet();
                    }
                }
            }
        });
    }

    /**
     * Sends an Android key to the phone if it is one the receiver forwards. Returns true when the
     * key was taken (so the caller consumes it), whether or not the write then succeeded.
     */
    public boolean sendKey(int androidKeyCode, final boolean pressed) {
        final int code = scanCodeFor(androidKeyCode);
        final AaSession current = session;
        if (code < 0 || current == null || !current.isInputBound()) {
            return false;
        }
        final long timestamp = System.currentTimeMillis() * 1000L;
        inputThread.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    current.sendButton(timestamp, code, pressed);
                } catch (IOException error) {
                    logInputError(error);
                }
            }
        });
        return true;
    }

    /** True when a key would be forwarded right now (a phone is bound and the key is one we send). */
    public boolean willForwardKey(int androidKeyCode) {
        AaSession current = session;
        return scanCodeFor(androidKeyCode) >= 0 && current != null && current.isInputBound();
    }

    /** Android key code to the {@link AaInput} code, or -1 when it is not forwarded. */
    static int scanCodeFor(int androidKeyCode) {
        switch (androidKeyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: return AaInput.KEY_DPAD_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return AaInput.KEY_DPAD_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return AaInput.KEY_DPAD_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return AaInput.KEY_DPAD_RIGHT;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER: return AaInput.KEY_ENTER;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: return AaInput.KEY_TOGGLE_PLAY;
            case KeyEvent.KEYCODE_MEDIA_NEXT: return AaInput.KEY_NEXT;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS: return AaInput.KEY_PREV;
            case KeyEvent.KEYCODE_MEDIA_PLAY: return AaInput.KEY_PLAY;
            case KeyEvent.KEYCODE_MEDIA_PAUSE: return AaInput.KEY_PAUSE;
            default: return -1;
        }
    }

    private void logInputError(IOException error) {
        if (!inputErrorLogged) {
            inputErrorLogged = true;
            Diag.log("input", "could not send to the phone: " + error.getMessage());
        }
    }

    // ---- VideoDecoder.Listener (session thread) -----------------------------------------------

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
