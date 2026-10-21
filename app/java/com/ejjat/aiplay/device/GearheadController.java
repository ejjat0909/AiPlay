package com.ejjat.aiplay.device;

import android.content.Context;
import android.content.Intent;

import com.ejjat.aiplay.core.PrivilegePlan;

import java.util.List;

/** Enables, repairs and opens Android Auto. All decisions come from {@link PrivilegePlan}. */
public final class GearheadController {

    private GearheadController() {
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
        if (intent == null) {
            Diag.log("gearhead", "no launch intent; package is disabled or hidden");
            return false;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
            Diag.log("gearhead", "launcher opened");
            return true;
        } catch (RuntimeException error) {
            Diag.log("gearhead", "startActivity failed", error);
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

    public static PrivilegedShell.Result inspect() {
        return PrivilegedShell.runShell(
                "pm list packages -d 2>/dev/null | grep -i gearhead; "
                        + "cmd package resolve-activity --brief " + CapabilityProbe.GEARHEAD);
    }
}
