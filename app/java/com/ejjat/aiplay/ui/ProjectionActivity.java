package com.ejjat.aiplay.ui;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.ejjat.aiplay.R;
import com.ejjat.aiplay.receiver.ProjectionHub;

/**
 * Full-screen surface for the phone's projected UI.
 *
 * <p>The stream is 16:9 (1280x720 in 0.2.0). It is letterboxed to keep that ratio on any head
 * unit, instead of being stretched to fit a 1024x600 or 1280x480 panel. Input is not wired yet:
 * this screen shows the phone, it does not control it.</p>
 */
public final class ProjectionActivity extends Activity implements ProjectionHub.Listener {

    private static final float ASPECT = 1280f / 720f;

    private FrameLayout root;
    private SurfaceView surfaceView;
    private TextView status;
    private ProjectionHub hub;
    private boolean pictureShown;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hub = ProjectionHub.get(this);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        surfaceView = new SurfaceView(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));

        status = new TextView(this);
        status.setTextColor(Ui.TEXT1);
        status.setTextSize(16);
        status.setText(R.string.projection_waiting);
        root.addView(status, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int ol, int ot, int or, int ob) {
                fitSurface(r - l, b - t);
            }
        });
        setContentView(root);

        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                hub.attachSurface(holder.getSurface());
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                hub.detachSurface();
            }
        });
    }

    private void fitSurface(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        int w = width;
        int h = Math.round(width / ASPECT);
        if (h > height) {
            h = height;
            w = Math.round(height * ASPECT);
        }
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) surfaceView.getLayoutParams();
        if (params.width != w || params.height != h) {
            params.width = w;
            params.height = h;
            params.gravity = Gravity.CENTER;
            surfaceView.setLayoutParams(params);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        hub.setListener(this);
    }

    @Override
    protected void onPause() {
        hub.setListener(null);
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUi();
        }
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    // ---- ProjectionHub.Listener (main thread) ------------------------------------------------

    @Override
    public void onStatus(String text) {
        if (text.startsWith("showing")) {
            pictureShown = true;
            status.setVisibility(View.GONE);
        } else if (!pictureShown) {
            status.setText(text);
            status.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onEnded() {
        pictureShown = false;
        status.setText(R.string.projection_ended);
        status.setVisibility(View.VISIBLE);
    }
}
