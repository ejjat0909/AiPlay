package com.ejjat.aiplay.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import com.ejjat.aiplay.device.Diag;
import com.ejjat.aiplay.device.Prefs;
import com.ejjat.aiplay.ui.view.SplashView;

/**
 * Brand splash. Skippable by tap, shortened on warm starts, and disabled entirely from Settings.
 * A watchdog guarantees the driver never gets stuck here.
 */
public final class SplashActivity extends Activity {

    private static final long WARM_MS = 700L;
    private static final long COLD_MS = 1600L;
    private static final long WATCHDOG_MS = 4000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean navigated;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.immersive(this);

        if (!Prefs.showSplash()) {
            goHome();
            return;
        }

        SplashView splash = new SplashView(this);
        setContentView(splash);
        splash.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Diag.log("splash", "skipped by tap");
                goHome();
            }
        });

        long duration = Prefs.splashSeen() ? WARM_MS : COLD_MS;
        Prefs.markSplashSeen();
        splash.start(new Runnable() {
            @Override
            public void run() {
                goHome();
            }
        }, duration);

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!navigated) {
                    Diag.log("splash", "watchdog fired");
                    goHome();
                }
            }
        }, WATCHDOG_MS);
    }

    private void goHome() {
        if (navigated) {
            return;
        }
        navigated = true;
        handler.removeCallbacksAndMessages(null);
        startActivity(new Intent(this, HomeActivity.class));
        Ui.fadeIn(this);
        finish();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
