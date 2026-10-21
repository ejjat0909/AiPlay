package com.ejjat.aiplay.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.ejjat.aiplay.R;
import com.ejjat.aiplay.core.PrivilegePlan;
import com.ejjat.aiplay.device.Diag;
import com.ejjat.aiplay.device.GearheadController;
import com.ejjat.aiplay.device.PrivilegedShell;

/** The ADB cookbook: every command the driver may need, copyable, plus a root shortcut. */
public final class SetupActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.immersive(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG1);
        LinearLayout root = Ui.column(this, 20);

        TextView title = Ui.heading(this, getString(R.string.setup_title));
        root.addView(title);
        root.addView(Ui.spacer(this, 6));
        root.addView(Ui.text(this, getString(R.string.setup_intro), 13, Ui.TEXT1));
        root.addView(Ui.spacer(this, 16));

        final TextView recipe = Ui.text(this, PrivilegePlan.adbRecipe(), 12, Ui.TEXT0);
        recipe.setTypeface(android.graphics.Typeface.MONOSPACE);
        recipe.setTextIsSelectable(true);
        recipe.setBackground(Ui.outlined(this, 0xFF0C1424, 14, Ui.LINE, 1f));
        recipe.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
        root.addView(recipe);

        root.addView(Ui.spacer(this, 14));

        Button copy = Ui.primaryButton(this, getString(R.string.action_copy_recipe));
        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ClipboardManager clipboard =
                        (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("AiPlay ADB recipe",
                            PrivilegePlan.adbRecipe()));
                    Toast.makeText(SetupActivity.this, R.string.toast_copied,
                            Toast.LENGTH_SHORT).show();
                }
            }
        });
        root.addView(copy);

        root.addView(Ui.spacer(this, 10));

        Button inspect = Ui.secondaryButton(this, getString(R.string.action_inspect));
        inspect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                inspect();
            }
        });
        root.addView(inspect);

        root.addView(Ui.spacer(this, 22));
        root.addView(Ui.text(this, getString(R.string.setup_notes), 12, Ui.TEXT2));

        scroll.addView(root);
        setContentView(scroll);
    }

    private void inspect() {
        final PrivilegedShell.Result result = GearheadController.inspect();
        Diag.log("setup", "inspect: " + result.summary());
        new AlertDialog.Builder(this)
                .setTitle(R.string.inspect_title)
                .setMessage(result.output.trim().isEmpty()
                        ? getString(R.string.inspect_empty) : result.output.trim())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }
}
