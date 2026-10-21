package com.ejjat.aiplay.device;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

import com.ejjat.aiplay.core.PrivilegePlan;

import java.util.List;

/** Enables, repairs and opens Android Auto. All decisions come from {@link PrivilegePlan}. */
public final class GearheadController {

    /** AOSP PER_USER_RANGE: uid = userId * 100000 + appId. Unchanged since Android 4.2. */
    private static final int PER_USER_RANGE = 100000;

    private GearheadController() {
    }

    /** The user AiPlay itself runs as — the only user it may address without a permission. */
    private static int currentUserId() {
        return android.os.Process.myUid() / PER_USER_RANGE;
    }

    public static Intent launchIntent(Context context) {
        try {
            return context.getPackageManager()
                    .getLaunchIntentForPackage(CapabilityProbe.GEARHEAD);
        } catch (RuntimeException error) {
            Diag.log("gearhead", "launch intent lookup failed", error);
            return null;
        }
    }

    public static boolean open(Context context) {
        Intent intent = launchIntent(context);
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(intent);
                Diag.log("gearhead", "launcher opened");
                return true;
            } catch (RuntimeException error) {
                Diag.log("gearhead", "startActivity failed", error);
            }
        } else {
            Diag.log("gearhead", "no launcher entry for " + CapabilityProbe.GEARHEAD);
        }
        // Android Auto 15.x ships no launcher activity on many ROMs (ColorOS, One UI, Android 11+).
        // That is not a fault, so fall back to the package's own App info page instead of a dead end.
        Intent info = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + CapabilityProbe.GEARHEAD));
        info.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(info);
            Diag.log("gearhead", "opened app info (no launcher entry)");
            return true;
        } catch (RuntimeException error) {
            Diag.log("gearhead", "app info fallback failed", error);
            return false;
        }
    }

    public static PrivilegedShell.Result enable() {
        List<String> commands = PrivilegePlan.enableCommands();
        Diag.log("gearhead", "running: " + PrivilegePlan.asSuScript(commands));
        PrivilegedShell.Result result =
                PrivilegedShell.runAsRoot(PrivilegePlan.asSuScript(commands));
        Diag.log("gearhead", "enable result: " + result.summary());
        return result;
    }

    public static PrivilegedShell.Result repair() {
        List<String> commands = PrivilegePlan.repairCommands();
        Diag.log("gearhead", "running: " + PrivilegePlan.asSuScript(commands));
        PrivilegedShell.Result result =
                PrivilegedShell.runAsRoot(PrivilegePlan.asSuScript(commands));
        Diag.log("gearhead", "repair result: " + result.summary());
        return result;
    }

    /**
     * Reads package state that an app uid cannot see by itself.
     *
     * <p>0.1.0 ran this as {@code sh -c} under the app's own uid, so every {@code cmd package} call
     * defaulted to {@code --user current} and died with {@code Permission Denial: ... uid u0a663
     * ... requires INTERACT_ACROSS_USERS}. Package state below the app's own uid needs root or an
     * adb shell, so say that plainly instead of dumping a stack trace into the report.</p>
     */
    public static PrivilegedShell.Result inspect() {
        // UserHandle.myUserId() and Context.getUserId() are @hide in the SDK stubs; deriving the
        // id from our own uid is the public equivalent.
        int userId = currentUserId();
        if (!PrivilegedShell.hasRoot()) {
            Diag.log("gearhead", "inspect skipped: no root shell (user " + userId + ")");
            return new PrivilegedShell.Result(2,
                    "Inspect needs a root shell or adb. From a laptop:\n\n"
                            + "adb shell " + PrivilegePlan.launcherQuery(0));
        }
        String script = PrivilegePlan.asSuScript(PrivilegePlan.inspectCommands(userId));
        Diag.log("gearhead", "inspect (user " + userId + "): " + script);
        PrivilegedShell.Result result = PrivilegedShell.runAsRoot(script);
        Diag.log("gearhead", "inspect result: " + result.summary());
        return result;
    }
}
