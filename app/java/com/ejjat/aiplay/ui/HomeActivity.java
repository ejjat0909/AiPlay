package com.ejjat.aiplay.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.ejjat.aiplay.BuildConfig;
import com.ejjat.aiplay.R;
import com.ejjat.aiplay.core.Action;
import com.ejjat.aiplay.core.Capability;
import com.ejjat.aiplay.core.Condition;
import com.ejjat.aiplay.core.DeviceProfile;
import com.ejjat.aiplay.core.PrivilegePlan;
import com.ejjat.aiplay.core.ReadinessEngine;
import com.ejjat.aiplay.core.Verdict;
import com.ejjat.aiplay.device.CapabilityProbe;
import com.ejjat.aiplay.device.Diag;
import com.ejjat.aiplay.device.GearheadController;
import com.ejjat.aiplay.device.Prefs;
import com.ejjat.aiplay.device.PrivilegedShell;
import com.ejjat.aiplay.receiver.AaReceiverService;
import com.ejjat.aiplay.receiver.ReceiverState;
import com.ejjat.aiplay.ui.view.RingGauge;

/** The one screen that matters: what this head unit can do, and the single action that fixes it. */
public final class HomeActivity extends Activity {

    private static final int REQUEST_NOTIFICATIONS = 41;
    private boolean askedPermissions;

    private final Handler main = new Handler(Looper.getMainLooper());

    private RingGauge gauge;
    private TextView statusTitle;
    private TextView statusBody;
    private TextView deviceLine;
    private TextView receiverLine;
    /** Network name and password the phone can join. Not written to the diagnostic log. */
    private TextView wifiLine;
    private LinearLayout wifiBox;
    /** Scan to join the unit's Wi-Fi network; shown only while the unit hosts its own. */
    private com.ejjat.aiplay.ui.view.QrView wifiQr;
    private String wifiQrPayload = "";
    private Button primary;
    private Button receiverToggle;
    private ProgressBar receiverSpinner;
    private ReceiverState.State lastReceiverState;
    private LinearLayout capabilityBox;

    private DeviceProfile profile;
    private Verdict verdict;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.immersive(this);
        if (Prefs.keepScreenOn()) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        setContentView(buildRoot());
        refresh("initial");
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateReceiverLine();
        ReceiverState.addListener(receiverListener);
    }

    @Override
    protected void onPause() {
        ReceiverState.removeListener(receiverListener);
        super.onPause();
    }

    private final ReceiverState.Listener receiverListener = new ReceiverState.Listener() {
        @Override
        public void onReceiverState(ReceiverState.State state, String detail) {
            updateReceiverLine();
            if (lastReceiverState == ReceiverState.State.STARTING) {
                if (state == ReceiverState.State.RUNNING) {
                    toast(getString(R.string.toast_receiver_ready));
                } else if (state == ReceiverState.State.FAILED) {
                    toast(getString(R.string.toast_receiver_failed, detail));
                }
            }
            lastReceiverState = state;
        }
    };

    private View buildRoot() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Ui.BG1);

        LinearLayout root = Ui.column(this, 16);
        root.setLayoutParams(new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(buildHeader());
        root.addView(Ui.spacer(this, 10));
        root.addView(buildHero());
        root.addView(Ui.spacer(this, 14));
        root.addView(buildCapabilitiesHeader());
        root.addView(Ui.spacer(this, 8));

        capabilityBox = Ui.column(this, 0);
        root.addView(capabilityBox);
        root.addView(Ui.spacer(this, 14));
        root.addView(buildFooter());

        scroll.addView(root);
        return scroll;
    }

    private View buildHeader() {
        LinearLayout header = Ui.row(this);

        View dot = new View(this);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(
                Ui.dp(this, 34), Ui.dp(this, 34));
        dot.setLayoutParams(dotParams);
        dot.setBackground(Ui.gradient(this, Ui.ACCENT_A, Ui.ACCENT_B, 999));
        header.addView(dot);

        LinearLayout titles = Ui.column(this, 0);
        titles.setPadding(Ui.dp(this, 12), 0, 0, 0);
        TextView name = Ui.heading(this, "AiPlay");
        name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20);
        titles.addView(name);
        deviceLine = Ui.caption(this, getString(R.string.probing));
        titles.addView(deviceLine);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titles.setLayoutParams(titleParams);
        header.addView(titles);

        Button diagnostics = Ui.chip(this, getString(R.string.action_diagnostics));
        diagnostics.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(HomeActivity.this, DiagnosticsActivity.class));
            }
        });
        header.addView(diagnostics);

        Button settings = Ui.chip(this, getString(R.string.action_settings));
        settings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(HomeActivity.this, SettingsActivity.class));
            }
        });
        header.addView(settings);
        return header;
    }

    private View buildHero() {
        LinearLayout hero = Ui.row(this);
        hero.setBackground(Ui.outlined(this, Ui.SURFACE, Ui.RADIUS_CARD_DP, Ui.LINE, 1f));
        hero.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));

        gauge = new RingGauge(this);
        LinearLayout.LayoutParams gaugeParams = new LinearLayout.LayoutParams(
                Ui.dp(this, 112), Ui.dp(this, 112));
        gaugeParams.setMargins(0, 0, Ui.dp(this, 16), 0);
        gauge.setLayoutParams(gaugeParams);
        gauge.setLabel("READY");
        hero.addView(gauge);

        LinearLayout right = Ui.column(this, 0);
        right.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        statusTitle = Ui.text(this, getString(R.string.probing), 19, Ui.TEXT0);
        statusTitle.setTypeface(statusTitle.getTypeface(), android.graphics.Typeface.BOLD);
        right.addView(statusTitle);

        right.addView(Ui.spacer(this, 6));

        statusBody = Ui.text(this, "", 13, Ui.TEXT1);
        right.addView(statusBody);

        right.addView(Ui.spacer(this, 10));

        primary = Ui.primaryButton(this, getString(R.string.action_probe_again));
        primary.setEnabled(false);
        primary.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                onPrimaryAction();
            }
        });
        right.addView(primary);

        LinearLayout receiverRow = Ui.row(this);
        receiverRow.setPadding(0, Ui.dp(this, 10), 0, 0);
        receiverSpinner = new ProgressBar(this);
        receiverSpinner.setIndeterminate(true);
        receiverSpinner.setVisibility(View.GONE);
        LinearLayout.LayoutParams spinnerParams = new LinearLayout.LayoutParams(
                Ui.dp(this, 16), Ui.dp(this, 16));
        spinnerParams.setMargins(0, 0, Ui.dp(this, 8), 0);
        receiverRow.addView(receiverSpinner, spinnerParams);
        receiverLine = Ui.caption(this, "");
        receiverRow.addView(receiverLine);
        right.addView(receiverRow);

        // Network text on the left, the QR code beside it, so it fits on the first screen.
        wifiBox = Ui.row(this);
        wifiBox.setPadding(0, Ui.dp(this, 8), 0, 0);
        wifiBox.setVisibility(View.GONE);
        wifiLine = Ui.text(this, "", 15, Ui.TEXT1);
        wifiBox.addView(wifiLine, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        wifiQr = new com.ejjat.aiplay.ui.view.QrView(this);
        wifiQr.setVisibility(View.GONE);
        wifiBox.addView(wifiQr, new LinearLayout.LayoutParams(Ui.dp(this, 150), Ui.dp(this, 150)));
        right.addView(wifiBox);

        hero.addView(right);
        return hero;
    }

    private View buildCapabilitiesHeader() {
        LinearLayout row = Ui.row(this);
        TextView title = Ui.text(this, getString(R.string.capabilities), 15, Ui.TEXT0);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        row.addView(title);

        TextView hint = Ui.caption(this, getString(R.string.capabilities_hint));
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        hintParams.setMargins(Ui.dp(this, 10), 0, 0, 0);
        hint.setLayoutParams(hintParams);
        hint.setGravity(Gravity.END);
        row.addView(hint);
        return row;
    }

    private View buildFooter() {
        LinearLayout footer = Ui.row(this);

        Button recipe = Ui.secondaryButton(this, getString(R.string.action_adb_recipe));
        LinearLayout.LayoutParams recipeParams = new LinearLayout.LayoutParams(
                0, Ui.dp(this, 52), 1f);
        recipeParams.setMargins(0, 0, Ui.dp(this, 10), 0);
        recipe.setLayoutParams(recipeParams);
        recipe.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(HomeActivity.this, SetupActivity.class));
            }
        });
        footer.addView(recipe);

        receiverToggle = Ui.secondaryButton(this, getString(R.string.action_start_receiver));
        receiverToggle.setLayoutParams(new LinearLayout.LayoutParams(0, Ui.dp(this, 52), 1f));
        receiverToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ReceiverState.State now = ReceiverState.state();
                if (now == ReceiverState.State.STARTING) {
                    return;     // the button is disabled while starting; ignore a stray tap
                }
                if (now == ReceiverState.State.RUNNING) {
                    AaReceiverService.stop(HomeActivity.this);
                    updateReceiverLine();
                } else {
                    startReceiver();
                }
            }
        });
        footer.addView(receiverToggle);
        return footer;
    }

    private void refresh(final String reason) {
        if (busy) {
            return;
        }
        busy = true;
        statusTitle.setText(R.string.probing);
        statusBody.setText("");
        primary.setEnabled(false);
        primary.setText(R.string.action_probe_again);
        Diag.log("home", "probe (" + reason + ")");

        new Thread(new Runnable() {
            @Override
            public void run() {
                final DeviceProfile probed = CapabilityProbe.probe(HomeActivity.this);
                final Verdict evaluated = ReadinessEngine.evaluate(probed);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        busy = false;
                        profile = probed;
                        verdict = evaluated;
                        render();
                    }
                });
            }
        }, "aiplay-probe").start();
    }

    private void render() {
        if (profile == null || verdict == null) {
            return;
        }
        deviceLine.setText(profile.describe());
        gauge.setValue(verdict.score, true);
        gauge.setLabel(verdict.condition.code);

        statusTitle.setText(titleFor(verdict.condition));
        statusBody.setText(bodyFor(verdict.condition));
        primary.setText(labelFor(verdict.action));
        primary.setEnabled(true);

        capabilityBox.removeAllViews();
        for (Capability capability : verdict.capabilities) {
            capabilityBox.addView(buildCapabilityRow(capability));
        }
        updateReceiverLine();
    }

    private View buildCapabilityRow(Capability capability) {
        LinearLayout row = Ui.row(this);
        row.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 5));

        View dot = new View(this);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(
                Ui.dp(this, 8), Ui.dp(this, 8));
        dotParams.setMargins(0, 0, Ui.dp(this, 12), 0);
        dot.setLayoutParams(dotParams);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(colorFor(capability.state));
        dot.setBackground(circle);
        row.addView(dot);

        TextView label = Ui.text(this, labelFor(capability.id), 14, Ui.TEXT1);
        label.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(label);

        TextView value = Ui.text(this, capability.evidence, 13,
                capability.state == Capability.State.MISSING ? Ui.WARN : Ui.TEXT2);
        value.setGravity(Gravity.END);
        row.addView(value);

        View divider = new View(this);
        divider.setBackgroundColor(0x14FFFFFF);
        LinearLayout wrapper = Ui.column(this, 0);
        wrapper.addView(row);
        wrapper.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return wrapper;
    }

    private void onPrimaryAction() {
        if (verdict == null) {
            refresh("retry");
            return;
        }
        switch (verdict.action) {
            case OPEN_ANDROID_AUTO:
                if (!GearheadController.open(this)) {
                    toast(getString(R.string.toast_open_failed));
                    startActivity(new Intent(this, SetupActivity.class));
                }
                break;
            case ENABLE_ANDROID_AUTO:
                confirmAndRun(getString(R.string.confirm_enable),
                        PrivilegePlan.enableCommands(), false);
                break;
            case REPAIR_ANDROID_AUTO:
                confirmAndRun(getString(R.string.confirm_repair),
                        PrivilegePlan.repairCommands(), false);
                break;
            case START_RECEIVER:
                startReceiver();
                break;
            default:
                refresh("unknown-action");
                break;
        }
    }

    private void confirmAndRun(String title, final java.util.List<String> commands,
                               final boolean unused) {
        if (!profile.suAvailable) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.no_root_title)
                    .setMessage(getString(R.string.no_root_body))
                    .setPositiveButton(R.string.action_adb_recipe,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    startActivity(new Intent(HomeActivity.this, SetupActivity.class));
                                }
                            })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(PrivilegePlan.describe(commands))
                .setPositiveButton(R.string.action_run, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        runCommands(commands);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void runCommands(final java.util.List<String> commands) {
        busy = true;
        primary.setEnabled(false);
        primary.setText(R.string.action_running);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final PrivilegedShell.Result result = verdict.action == Action.REPAIR_ANDROID_AUTO
                        ? GearheadController.repair() : GearheadController.enable();
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        busy = false;
                        new AlertDialog.Builder(HomeActivity.this)
                                .setTitle(result.ok() ? R.string.result_ok : R.string.result_failed)
                                .setMessage(result.summary())
                                .setPositiveButton(R.string.action_recheck,
                                        new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface dialog, int which) {
                                                refresh("after-fix");
                                            }
                                        })
                                .show();
                    }
                });
            }
        }, "aiplay-fix").start();
    }

    private void startReceiver() {
        // Notifications (Android 13+) are needed for the foreground notification; Bluetooth
        // (Android 12+) and location are for the phone-finds-receiver step and are optional: the
        // receiver starts whatever is answered. Ask once per start, in one prompt.
        java.util.List<String> missing = new java.util.ArrayList<String>();
        if (Build.VERSION.SDK_INT >= 33) {
            addIfMissing(missing, Manifest.permission.POST_NOTIFICATIONS);
        }
        if (Build.VERSION.SDK_INT >= 31) {
            addIfMissing(missing, Manifest.permission.BLUETOOTH_CONNECT);
        }
        addIfMissing(missing, Manifest.permission.ACCESS_FINE_LOCATION);
        if (!missing.isEmpty() && !askedPermissions) {
            askedPermissions = true;
            requestPermissions(missing.toArray(new String[0]), REQUEST_NOTIFICATIONS);
            return;
        }
        AaReceiverService.start(this);
        updateReceiverLine();
    }

    private void addIfMissing(java.util.List<String> list, String permission) {
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            list.add(permission);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_NOTIFICATIONS) {
            startReceiver();
        }
    }

    /**
     * Shows the unit's own Wi-Fi network as text and as a QR code to scan. Deliberately not passed to
     * Diag: the password must not reach the log or a saved report.
     */
    private void updateWifiLine(ReceiverState.State state) {
        if (wifiLine == null) {
            return;
        }
        String typed = Prefs.wifiSsid();
        if (state == ReceiverState.State.RUNNING && typed.length() > 0) {
            // A network typed into Settings overrides the unit's own one: say so, name only.
            wifiLine.setText(getString(R.string.wifi_from_settings, typed));
            wifiBox.setVisibility(View.VISIBLE);
            showQr(null);
            return;
        }
        String name = ReceiverState.wifiName();
        if (state != ReceiverState.State.RUNNING || name.length() == 0) {
            wifiBox.setVisibility(View.GONE);
            showQr(null);
            return;
        }
        wifiLine.setText(getString(R.string.wifi_scan_to_join) + "\n"
                + getString(R.string.wifi_for_phone, name) + "\n"
                + getString(R.string.wifi_password, ReceiverState.wifiPassword()));
        wifiBox.setVisibility(View.VISIBLE);
        showQr(com.ejjat.aiplay.core.qr.WifiQr.payload(name, ReceiverState.wifiPassword()));
    }

    private void showQr(String payload) {
        if (wifiQr == null) {
            return;
        }
        if (payload == null) {
            wifiQrPayload = "";
            wifiQr.setVisibility(View.GONE);
            return;
        }
        if (!payload.equals(wifiQrPayload)) {
            try {
                wifiQr.setCode(com.ejjat.aiplay.core.qr.QrCode.encodeText(payload));
                wifiQrPayload = payload;
            } catch (IllegalArgumentException tooLong) {
                wifiQr.setVisibility(View.GONE);
                return;
            }
        }
        wifiQr.setVisibility(View.VISIBLE);
    }

    private void updateReceiverLine() {
        if (receiverLine == null) {
            return;
        }
        ReceiverState.State state = ReceiverState.state();
        String text;
        switch (state) {
            case STARTING:
                text = getString(R.string.receiver_starting);
                break;
            case RUNNING: {
                text = getString(R.string.receiver_running, Prefs.receiverPort());
                String bluetooth = ReceiverState.bluetooth();
                if (bluetooth.length() > 0) {
                    text += "\n" + getString(R.string.receiver_bluetooth, bluetooth);
                }
                break;
            }
            case FAILED:
                text = getString(R.string.receiver_failed, ReceiverState.detail());
                break;
            default:
                text = getString(R.string.receiver_stopped);
                break;
        }
        receiverLine.setText(text);
        com.ejjat.aiplay.device.Diag.log("home", "receiver line shows: "
                + text.replace('\n', '|') + " (" + state + ")");
        receiverLine.setTextColor(state == ReceiverState.State.FAILED ? Ui.WARN : Ui.TEXT2);
        updateWifiLine(state);
        if (receiverSpinner != null) {
            receiverSpinner.setVisibility(state == ReceiverState.State.STARTING
                    ? View.VISIBLE : View.GONE);
        }
        // The receiver is AiPlay's own feature, so it must be reachable even when Android Auto is
        // already healthy (P1). While it starts, the button says so and cannot be pressed twice.
        if (receiverToggle != null) {
            receiverToggle.setEnabled(state != ReceiverState.State.STARTING);
            receiverToggle.setText(state == ReceiverState.State.STARTING
                    ? R.string.action_starting_receiver
                    : state == ReceiverState.State.RUNNING
                            ? R.string.action_stop_receiver : R.string.action_start_receiver);
        }
    }

    private String titleFor(Condition condition) {
        switch (condition) {
            case P1_READY:
                return getString(R.string.status_ready_title);
            case P2_DISABLED:
                return getString(R.string.status_disabled_title);
            case P3_HIDDEN:
                return getString(R.string.status_hidden_title);
            case P4_RECEIVER_GMS:
                return getString(R.string.status_receiver_title);
            default:
                return getString(R.string.status_no_gms_title);
        }
    }

    private String bodyFor(Condition condition) {
        switch (condition) {
            case P1_READY:
                return getString(R.string.status_ready_body);
            case P2_DISABLED:
                return getString(R.string.status_disabled_body);
            case P3_HIDDEN:
                return getString(R.string.status_hidden_body);
            case P4_RECEIVER_GMS:
                return getString(R.string.status_receiver_body);
            default:
                return getString(R.string.status_no_gms_body);
        }
    }

    private String labelFor(Action action) {
        switch (action) {
            case OPEN_ANDROID_AUTO:
                return getString(R.string.action_open_aa);
            case ENABLE_ANDROID_AUTO:
                return getString(R.string.action_enable_aa);
            case REPAIR_ANDROID_AUTO:
                return getString(R.string.action_repair_aa);
            default:
                return getString(R.string.action_start_receiver);
        }
    }

    private String labelFor(Capability.Id id) {
        switch (id) {
            case GEARHEAD_PACKAGE:
                return getString(R.string.cap_gearhead);
            case GEARHEAD_ENABLED:
                return getString(R.string.cap_gearhead_enabled);
            case GEARHEAD_LAUNCHER:
                return getString(R.string.cap_gearhead_launcher);
            case PLAY_SERVICES:
                return getString(R.string.cap_gms);
            case ROOT_SHELL:
                return getString(R.string.cap_root);
            case SHIZUKU:
                return getString(R.string.cap_shizuku);
            case ADB_WIRELESS:
                return getString(R.string.cap_adb);
            case USB_HOST:
                return getString(R.string.cap_usb);
            case WIFI_DIRECT:
                return getString(R.string.cap_wifi_direct);
            case BLUETOOTH:
                return getString(R.string.cap_bluetooth);
            default:
                return getString(R.string.cap_port);
        }
    }

    private static int colorFor(Capability.State state) {
        switch (state) {
            case OK:
                return Ui.OK;
            case WARN:
                return Ui.WARN;
            case MISSING:
                return Ui.BAD;
            default:
                return Ui.TEXT2;
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
