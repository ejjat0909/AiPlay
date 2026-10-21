package com.ejjat.aiplay.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.ejjat.aiplay.BuildConfig;
import com.ejjat.aiplay.R;
import com.ejjat.aiplay.device.Prefs;

/** Six preferences, no accounts, no sync. */
public final class SettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.immersive(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG1);
        LinearLayout root = Ui.column(this, 20);
        root.addView(Ui.heading(this, getString(R.string.action_settings)));
        root.addView(Ui.spacer(this, 14));

        root.addView(toggle(R.string.settings_splash, Prefs.showSplash(),
                new ToggleHandler() {
                    @Override
                    public void onChanged(boolean value) {
                        Prefs.setShowSplash(value);
                    }
                }));
        root.addView(toggle(R.string.settings_keep_screen_on, Prefs.keepScreenOn(),
                new ToggleHandler() {
                    @Override
                    public void onChanged(boolean value) {
                        Prefs.setKeepScreenOn(value);
                    }
                }));
        root.addView(toggle(R.string.settings_start_on_boot, Prefs.startOnBoot(),
                new ToggleHandler() {
                    @Override
                    public void onChanged(boolean value) {
                        Prefs.setStartOnBoot(value);
                    }
                }));
        root.addView(toggle(R.string.settings_auto_receiver, Prefs.autoStartReceiver(),
                new ToggleHandler() {
                    @Override
                    public void onChanged(boolean value) {
                        Prefs.setAutoStartReceiver(value);
                    }
                }));
        root.addView(toggle(R.string.settings_verbose, Prefs.verbose(),
                new ToggleHandler() {
                    @Override
                    public void onChanged(boolean value) {
                        Prefs.setVerbose(value);
                    }
                }));

        root.addView(Ui.spacer(this, 18));
        TextView port = Ui.text(this, getString(R.string.settings_port, Prefs.receiverPort()),
                14, Ui.TEXT1);
        port.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                editPort();
            }
        });
        root.addView(port);

        root.addView(Ui.spacer(this, 12));
        TextView wifi = Ui.text(this, wifiLine(), 14, Ui.TEXT1);
        wifi.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                editWifi((TextView) view);
            }
        });
        root.addView(wifi);
        root.addView(Ui.caption(this, getString(R.string.settings_wifi_hint)));

        root.addView(Ui.spacer(this, 26));
        root.addView(Ui.caption(this, "AiPlay " + BuildConfig.VERSION_NAME
                + " · GPL-3.0 · unofficial, not affiliated with Google"));
        root.addView(Ui.spacer(this, 6));
        root.addView(Ui.caption(this, getString(R.string.settings_credits)));

        scroll.addView(root);
        setContentView(scroll);
    }

    private interface ToggleHandler {
        void onChanged(boolean value);
    }

    private View toggle(int labelRes, boolean initial, final ToggleHandler handler) {
        LinearLayout row = Ui.row(this);
        row.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));

        TextView label = Ui.text(this, getString(labelRes), 15, Ui.TEXT0);
        label.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(label);

        Switch toggle = new Switch(this);
        toggle.setChecked(initial);
        toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                handler.onChanged(isChecked);
            }
        });
        row.addView(toggle);
        return row;
    }

    private void editPort() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(Prefs.receiverPort()));
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_port_title)
                .setView(input)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            int port = Integer.parseInt(input.getText().toString().trim());
                            if (port > 1024 && port < 65535) {
                                Prefs.setReceiverPort(port);
                            }
                        } catch (NumberFormatException ignored) {
                            // keep the previous value
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String wifiLine() {
        String ssid = Prefs.wifiSsid();
        if (ssid.length() == 0) {
            return getString(R.string.settings_wifi_unset);
        }
        return getString(R.string.settings_wifi, ssid,
                Prefs.wifiKey().length() == 0 ? getString(R.string.settings_wifi_open)
                        : getString(R.string.settings_wifi_protected));
    }

    private void editWifi(final TextView line) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        form.setPadding(pad, Ui.dp(this, 8), pad, 0);

        final EditText ssid = new EditText(this);
        ssid.setHint(R.string.settings_wifi_ssid_hint);
        ssid.setSingleLine(true);
        ssid.setText(Prefs.wifiSsid());
        form.addView(ssid);

        final EditText key = new EditText(this);
        key.setHint(R.string.settings_wifi_key_hint);
        key.setSingleLine(true);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setText(Prefs.wifiKey());
        form.addView(key);

        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_wifi_title)
                .setView(form)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Prefs.setWifi(ssid.getText().toString(), key.getText().toString());
                        line.setText(wifiLine());
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
