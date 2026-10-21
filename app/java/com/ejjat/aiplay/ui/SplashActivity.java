package com.ejjat.aiplay.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import com.ejjat.aiplay.device.Diag;
import com.ejjat.aiplay.device.Prefs;
import com.ejjat.aiplay.receiver.AaReceiverService;
import com.ejjat.aiplay.receiver.ProjectionHub;
import com.ejjat.aiplay.ui.view.SplashView;

/**
 * Brand splash. Skippable by tap, shortened on warm starts, and disabled entirely from Settings.
 * A watchdog guarantees the driver never gets stuck here.
 */
public final class SplashActivity extends Activity {

    /**
     * Test hook. {@code AaReceiverService} is not exported (correctly), so {@code adb shell am
     * start-foreground-service} is refused. The launcher activity is exported, so a rig can ask
     * the app to start the receiver itself:
     *
     * <pre>adb shell am start -n com.ejjat.aiplay/.ui.SplashActivity \
     *       --ez aiplay.start_receiver true</pre>
     */
    public static final String EXTRA_START_RECEIVER = "aiplay.start_receiver";

    private static final long WARM_MS = 700L;
    private static final long COLD_MS = 1600L;
    private static final long WATCHDOG_MS = 4000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean navigated;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.immersive(this);

        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_START_RECEIVER, false)) {
            Diag.log("splash", "start_receiver extra: starting the receiver");
            AaReceiverService.start(this);
        }

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
                    Diag.log("splash", "watchdog fired after " + WATCHDOG_MS + " ms: the animation never finished "
                            + "(screen off or locked, or animations disabled); continuing to Home");
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
        if (ProjectionHub.get(this).isStreaming()) {
            // A phone started projecting while the splash was still up (slow first launch, or the
            // receiver was started by the splash itself). Home must not be pushed over the video.
            Diag.log("splash", "a phone is already streaming; not opening Home");
            finish();
            return;
        }
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
