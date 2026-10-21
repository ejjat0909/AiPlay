package com.ejjat.aiplay.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.SystemClock;

import com.ejjat.aiplay.core.ignition.IgnitionPolicy;
import com.ejjat.aiplay.device.Diag;
import com.ejjat.aiplay.device.Prefs;

/**
 * Starts and stops the receiver when the car is switched on and off, as far as the unit lets an app
 * know.
 *
 * <p>There is no standard ignition (ACC) signal for apps. Two kinds of signals are used, and the
 * limits of each are real:</p>
 * <ul>
 *   <li><b>Manifest receivers</b> ({@code BootReceiver}): cold boot and the {@code QUICKBOOT_POWERON}
 *       fast-boot resume. They work even when the process is dead.</li>
 *   <li><b>A runtime receiver</b> (registered here, from {@code AiPlayApp}): screen on, unlock, car
 *       mode and the unit's own ACC action typed into Settings. It only hears anything while the
 *       process is alive; a unit that kills background apps while parked will not deliver these
 *       (the manifest signals still will). Android also stops background apps from starting
 *       foreground services on 12+, which can refuse a start from here; that refusal is shown as a
 *       failed start, not hidden.</li>
 * </ul>
 * <p>What the decisions are is in {@link IgnitionPolicy}, which is unit-tested; this class only wires
 * Android broadcasts to it.</p>
 */
public final class IgnitionMonitor {

    private static final IgnitionPolicy POLICY = new IgnitionPolicy();
    private static BroadcastReceiver registered;

    private IgnitionMonitor() {
    }

    /** Reads the current settings. */
    static IgnitionPolicy.Settings settings() {
        IgnitionPolicy.Settings settings = new IgnitionPolicy.Settings();
        settings.startOnIgnition = Prefs.startOnIgnition();
        settings.stopOnIgnitionOff = Prefs.stopOnIgnitionOff();
        settings.customOn = Prefs.accOnAction();
        settings.customOff = Prefs.accOffAction();
        return settings;
    }

    /**
     * (Re)registers the runtime receiver. Idempotent; call again after the custom actions change so
     * the new names are heard.
     */
    public static synchronized void register(Context context) {
        Context app = context.getApplicationContext();
        if (registered != null) {
            try {
                app.unregisterReceiver(registered);
            } catch (IllegalArgumentException ignored) {
                // already gone
            }
            registered = null;
        }
        IgnitionPolicy.Settings settings = settings();
        if (!settings.startOnIgnition && !settings.stopOnIgnitionOff) {
            return;     // nothing to listen for: do not hold a receiver for a disabled feature
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(IgnitionPolicy.SCREEN_ON);
        filter.addAction(IgnitionPolicy.USER_PRESENT);
        filter.addAction(IgnitionPolicy.ENTER_CAR_MODE);
        filter.addAction(IgnitionPolicy.EXIT_CAR_MODE);
        if (settings.customOn.length() > 0) {
            filter.addAction(settings.customOn);
        }
        if (settings.customOff.length() > 0) {
            filter.addAction(settings.customOff);
        }
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                handle(context, intent == null ? null : intent.getAction());
            }
        };
        if (Build.VERSION.SDK_INT >= 33) {
            // Exported: a vendor's ACC broadcast comes from another app or the system. Anyone can
            // send the configured action, so it can start or stop the receiver; there is nothing
            // more sensitive behind it than that.
            app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            app.registerReceiver(receiver, filter);
        }
        registered = receiver;
        Diag.log("ignition", "listening for screen on, unlock, car mode"
                + (settings.customOn.length() > 0 ? ", " + settings.customOn : "")
                + (settings.customOff.length() > 0 ? ", " + settings.customOff : ""));
    }

    /** Applies the policy to one broadcast. Called from the runtime receiver and {@code BootReceiver}. */
    public static void handle(Context context, String action) {
        if (action == null) {
            return;
        }
        boolean active = ReceiverState.state() == ReceiverState.State.STARTING
                || ReceiverState.state() == ReceiverState.State.RUNNING;
        IgnitionPolicy.Decision decision;
        String reason;
        synchronized (POLICY) {
            decision = POLICY.decide(action, SystemClock.elapsedRealtime(), settings(), active);
            reason = POLICY.reason();
        }
        if (decision == IgnitionPolicy.Decision.IGNORE
                && !IgnitionPolicy.isOnSignal(action, settings())
                && !IgnitionPolicy.isOffSignal(action, settings())) {
            return;     // not ours: do not fill the log with unrelated broadcasts
        }
        Diag.log("ignition", action + " -> " + decision + " (" + reason + ")");
        if (decision == IgnitionPolicy.Decision.START) {
            AaReceiverService.start(context);
        } else if (decision == IgnitionPolicy.Decision.STOP) {
            AaReceiverService.stop(context);
        }
    }
}
