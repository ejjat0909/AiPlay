package com.ejjat.aiplay;

import android.app.Application;

import com.ejjat.aiplay.device.Diag;
import com.ejjat.aiplay.device.Prefs;
import com.ejjat.aiplay.receiver.IgnitionMonitor;

/** Application entry point: nothing eager, nothing blocking. */
public final class AiPlayApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        Prefs.init(this);
        Diag.installCrashHandler();
        Diag.log("app", "AiPlay " + Diag.version(this) + " started on API "
                + android.os.Build.VERSION.SDK_INT);
        IgnitionMonitor.register(this);
    }
}
