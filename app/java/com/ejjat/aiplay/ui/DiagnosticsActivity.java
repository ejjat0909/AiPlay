package com.ejjat.aiplay.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.ejjat.aiplay.R;
import com.ejjat.aiplay.core.DeviceProfile;
import com.ejjat.aiplay.core.ReadinessEngine;
import com.ejjat.aiplay.core.Verdict;
import com.ejjat.aiplay.device.CapabilityProbe;
import com.ejjat.aiplay.core.hw.CpuBench;
import com.ejjat.aiplay.core.hw.HardwareData;
import com.ejjat.aiplay.core.hw.HardwareReport;
import com.ejjat.aiplay.device.Diag;
import com.ejjat.aiplay.device.HardwareProbe;
import com.ejjat.aiplay.device.ReportStore;

import java.io.File;

/** Evidence, not marketing: the full audit and the bounded log, saveable and shareable. */
public final class DiagnosticsActivity extends Activity {

    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView output;
    private String reportText = "";
    private Button cpuTest;
    private boolean testing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.immersive(this);

        LinearLayout root = Ui.column(this, 20);
        root.setBackgroundColor(Ui.BG1);
        root.addView(Ui.heading(this, getString(R.string.action_diagnostics)));
        root.addView(Ui.spacer(this, 4));
        root.addView(Ui.caption(this, getString(R.string.diagnostics_intro)));
        root.addView(Ui.spacer(this, 12));

        output = Ui.text(this, getString(R.string.probing), 11, Ui.TEXT1);
        output.setTypeface(android.graphics.Typeface.MONOSPACE);
        output.setTextIsSelectable(true);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(output);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroll, scrollParams);

        cpuTest = Ui.secondaryButton(this, getString(R.string.action_cpu_test));
        LinearLayout.LayoutParams testParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 46));
        testParams.setMargins(0, Ui.dp(this, 10), 0, 0);
        cpuTest.setLayoutParams(testParams);
        cpuTest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                runCpuTest();
            }
        });
        root.addView(cpuTest);

        LinearLayout actions = Ui.row(this);
        actions.setPadding(0, Ui.dp(this, 10), 0, 0);

        Button refresh = Ui.secondaryButton(this, getString(R.string.action_refresh));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, Ui.dp(this, 50), 1f);
        params.setMargins(0, 0, Ui.dp(this, 8), 0);
        refresh.setLayoutParams(params);
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                collect();
            }
        });
        actions.addView(refresh);

        Button copy = Ui.secondaryButton(this, getString(R.string.action_copy));
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, Ui.dp(this, 50), 1f);
        copyParams.setMargins(0, 0, Ui.dp(this, 8), 0);
        copy.setLayoutParams(copyParams);
        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ClipboardManager clipboard =
                        (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("AiPlay report", reportText));
                    toast(getString(R.string.toast_copied));
                }
            }
        });
        actions.addView(copy);

        Button save = Ui.primaryButton(this, getString(R.string.action_save));
        save.setLayoutParams(new LinearLayout.LayoutParams(0, Ui.dp(this, 50), 1f));
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                save();
            }
        });
        actions.addView(save);

        root.addView(actions);
        setContentView(root);
        collect();
    }

    private void collect() {
        output.setText(getString(R.string.probing));
        new Thread(new Runnable() {
            @Override
            public void run() {
                final DeviceProfile profile = CapabilityProbe.probe(DiagnosticsActivity.this);
                final Verdict verdict = ReadinessEngine.evaluate(profile);
                final HardwareData hardware = HardwareProbe.collect(DiagnosticsActivity.this);
                final String report = Diag.report(DiagnosticsActivity.this, profile, verdict,
                        HardwareReport.format(hardware));
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        reportText = report;
                        output.setText(report);
                    }
                });
            }
        }, "aiplay-report").start();
    }

    /** Loads every core for 8 s on its own thread; the button shows the seconds, then the report is rebuilt. */
    private void runCpuTest() {
        if (testing) {
            return;
        }
        testing = true;
        cpuTest.setEnabled(false);
        toast(getString(R.string.cpu_test_warning));
        new Thread(new Runnable() {
            @Override
            public void run() {
                String outcome;
                try {
                    CpuBench.Result result = HardwareProbe.runCpuTest(DiagnosticsActivity.this,
                            new HardwareProbe.Progress() {
                                @Override
                                public void onSecond(final int done, final int total) {
                                    main.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            cpuTest.setText(getString(R.string.action_cpu_testing,
                                                    done, total));
                                        }
                                    });
                                }
                            });
                    outcome = result.verdict == null ? "done" : result.verdict.text;
                } catch (InterruptedException interrupted) {
                    outcome = "interrupted";
                } catch (RuntimeException error) {
                    Diag.log("hardware", "CPU test failed", error);
                    outcome = "failed: " + error.getMessage();
                }
                final String message = outcome;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        testing = false;
                        cpuTest.setEnabled(true);
                        cpuTest.setText(R.string.action_cpu_test);
                        toast(getString(R.string.toast_cpu_test_done, message));
                        collect();
                    }
                });
            }
        }, "aiplay-cpu-test").start();
    }

    private void save() {
        try {
            File file = ReportStore.save(this, reportText);
            toast(getString(R.string.toast_saved, file.getAbsolutePath()));
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_STREAM, ReportStore.uriFor(file.getName()));
            share.putExtra(Intent.EXTRA_SUBJECT, "AiPlay diagnostic report");
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, getString(R.string.action_share)));
        } catch (Exception error) {
            Diag.log("diagnostics", "save failed", error);
            toast(getString(R.string.toast_save_failed));
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
