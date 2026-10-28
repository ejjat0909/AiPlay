package com.ejjat.aiplay.receiver;

import android.os.Handler;
import android.os.Looper;

import com.ejjat.aiplay.device.Diag;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What the receiver is doing, for the screen to show.
 *
 * <p>Starting it is not instant: the foreground service has to start, the TCP port to bind and the
 * Bluetooth service to register, and any of them can fail. Without this the Start button gave no
 * sign that anything was happening, nor why it had not worked. Listeners are always called on the
 * main thread.</p>
 */
public final class ReceiverState {

    public enum State {
        STOPPED, STARTING, RUNNING, FAILED
    }

    public interface Listener {
        void onReceiverState(State state, String detail);
    }

    /** How long STARTING may last before it is reported as a failure. */
    public static final long START_TIMEOUT_MS = 10000L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Listener> LISTENERS =
            new CopyOnWriteArrayList<Listener>();

    private static volatile State state = State.STOPPED;
    private static volatile String detail = "";
    private static volatile String bluetooth = "";
    /** The Wi-Fi network the unit offers the phone. Memory only: never logged, never in a report. */
    private static volatile String wifiName = "";
    private static volatile String wifiPassword = "";
    private static final Runnable TIMEOUT = new Runnable() {
        @Override
        public void run() {
            if (state == State.STARTING) {
                set(State.FAILED, "the system did not start the receiver. If this unit blocks "
                        + "background apps, allow AiPlay to run in the background and try again.");
            }
        }
    };

    private ReceiverState() {
    }

    public static State state() {
        return state;
    }

    public static String detail() {
        return detail;
    }

    /** Short Bluetooth status line ("waiting for a phone", "unavailable: ..."), or empty. */
    public static String bluetooth() {
        return bluetooth;
    }

    /** Name of the unit's own Wi-Fi network for the phone, or "" when there is none. */
    public static String wifiName() {
        return wifiName;
    }

    /** Its password, for showing on screen so a person can join by hand. Never log it. */
    public static String wifiPassword() {
        return wifiPassword;
    }

    public static void addListener(Listener listener) {
        LISTENERS.addIfAbsent(listener);
        listener.onReceiverState(state, detail);
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    static void set(final State next, final String text) {
        Diag.log("receiver", "state " + state + " -> " + next
                + (text == null || text.length() == 0 ? "" : ": " + text));
        state = next;
        detail = text == null ? "" : text;
        MAIN.removeCallbacks(TIMEOUT);
        if (next == State.STARTING) {
            MAIN.postDelayed(TIMEOUT, START_TIMEOUT_MS);
        }
        if (next == State.STOPPED || next == State.FAILED) {
            bluetooth = "";
            wifiName = "";
            wifiPassword = "";
        }
        notifyListeners();
    }

    static void setWifi(String name, String password) {
        wifiName = name == null ? "" : name;
        wifiPassword = password == null ? "" : password;
        notifyListeners();
    }

    static void setBluetooth(String text) {
        bluetooth = text == null ? "" : text;
        notifyListeners();
    }

    private static void notifyListeners() {
        final State s = state;
        final String d = detail;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                for (Listener listener : LISTENERS) {
                    listener.onReceiverState(s, d);
                }
            }
        });
    }
}
