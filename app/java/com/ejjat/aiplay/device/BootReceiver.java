package com.ejjat.aiplay.device;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.ejjat.aiplay.receiver.AaReceiverService;

/** Optional start-on-boot for the receiver. Off unless the driver enables it. */
public final class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        if (!Prefs.startOnBoot() || !Prefs.autoStartReceiver()) {
            Diag.log("boot", "boot completed; receiver auto-start disabled");
            return;
        }
        Diag.log("boot", "starting receiver after boot");
        try {
            AaReceiverService.start(context);
        } catch (RuntimeException error) {
            Diag.log("boot", "receiver start failed", error);
        }
    }
}
