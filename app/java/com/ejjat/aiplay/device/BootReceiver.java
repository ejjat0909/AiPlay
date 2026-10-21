package com.ejjat.aiplay.device;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.ejjat.aiplay.core.ignition.IgnitionPolicy;
import com.ejjat.aiplay.receiver.AaReceiverService;
import com.ejjat.aiplay.receiver.IgnitionMonitor;

/**
 * Cold boot and fast-boot resume.
 *
 * <p>{@code BOOT_COMPLETED}: the original opt-in ("Start with the car" and "Start the receiver at
 * boot") still starts the receiver, and "Start when the ignition turns on" counts a boot as an
 * ignition too. {@code QUICKBOOT_POWERON} (Android's and HTC's spelling) is what many fast-boot units
 * send when they resume instead of cold-booting; only the ignition setting reacts to it.</p>
 */
public final class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        String action = intent.getAction();
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action);
        boolean quick = IgnitionPolicy.QUICKBOOT_POWERON.equals(action)
                || IgnitionPolicy.QUICKBOOT_POWERON_HTC.equals(action);
        if (!boot && !quick) {
            return;
        }
        // The runtime receiver is not registered yet in a freshly started process.
        IgnitionMonitor.register(context);
        if (boot && Prefs.startOnBoot() && Prefs.autoStartReceiver()) {
            Diag.log("boot", "starting receiver after boot");
            try {
                AaReceiverService.start(context);
            } catch (RuntimeException error) {
                Diag.log("boot", "receiver start failed", error);
            }
            return;
        }
        if (Prefs.startOnIgnition() || Prefs.stopOnIgnitionOff()) {
            IgnitionMonitor.handle(context, action);
            return;
        }
        Diag.log("boot", (boot ? "boot completed" : "quick boot") + "; receiver auto-start disabled");
    }
}
