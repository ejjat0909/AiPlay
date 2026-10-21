package com.ejjat.aiplay.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the exact shell command lists AiPlay will run, or the ADB recipe it will show.
 *
 * <p>Pure so the strings are unit-tested: a typo here would silently fail on a real head unit and
 * look like "the app does not work".</p>
 *
 * <p>Rule learned from a real device (Android 16, 0.1.1): every {@code pm}/{@code cmd package}
 * command that accepts {@code --user} must carry it explicitly. Without it the platform defaults to
 * {@code --user current} ({@code -2}), which requires {@code INTERACT_ACROSS_USERS} — a permission
 * an ordinary app uid does not have, so the command dies with a {@code SecurityException} instead
 * of answering the question.</p>
 */
public final class PrivilegePlan {

    public static final String GEARHEAD_PACKAGE = "com.google.android.projection.gearhead";
    public static final String PERMISSION_FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION";

    private PrivilegePlan() {
    }

    /** Commands that turn a disabled Android Auto package back on. */
    public static List<String> enableCommands() {
        List<String> commands = new ArrayList<String>(2);
        commands.add("pm enable --user 0 " + GEARHEAD_PACKAGE);
        commands.add("pm grant " + GEARHEAD_PACKAGE + " " + PERMISSION_FINE_LOCATION);
        return commands;
    }

    /** Commands that repair a package which is enabled but unreachable. */
    public static List<String> repairCommands() {
        List<String> commands = new ArrayList<String>(3);
        commands.add("pm install-existing --user 0 " + GEARHEAD_PACKAGE);
        commands.addAll(enableCommands());
        return commands;
    }

    /** Commands that ask the platform whether the package is visible to the launcher. */
    public static List<String> inspectCommands() {
        return inspectCommands(0);
    }

    /**
     * Commands that ask the platform whether the package is visible to the launcher, for one user.
     *
     * <p>{@code resolve-activity} needs a real intent: a bare package name is parsed as a URI and
     * always answers "No activity found". The launcher query below is the question we actually
     * mean, and {@code --user} keeps it legal for an unprivileged caller.</p>
     */
    public static List<String> inspectCommands(int userId) {
        List<String> commands = new ArrayList<String>(3);
        commands.add("pm list packages -d --user " + userId + " | grep -i gearhead");
        commands.add(launcherQuery(userId));
        commands.add("dumpsys package " + GEARHEAD_PACKAGE + " | head -40");
        return commands;
    }

    /** The one command that answers "does this package have a launcher entry for this user?". */
    public static String launcherQuery(int userId) {
        return "cmd package resolve-activity --brief --user " + userId
                + " -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p "
                + GEARHEAD_PACKAGE;
    }

    /** Joins commands for a single {@code su -c} invocation. */
    public static String asSuScript(List<String> commands) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < commands.size(); i++) {
            if (i > 0) {
                builder.append("; ");
            }
            builder.append(commands.get(i));
        }
        return builder.toString();
    }

    /** Copy-paste recipe for a PC-side ADB shell, exactly as documented in PLAN.md appendix B. */
    public static String adbRecipe() {
        StringBuilder builder = new StringBuilder();
        builder.append("# 1. Is Android Auto installed but disabled?\n");
        builder.append("adb shell pm list packages -d | grep -i gearhead\n\n");
        builder.append("# 2. Enable it for the current user\n");
        builder.append("adb shell pm enable --user 0 ").append(GEARHEAD_PACKAGE).append("\n\n");
        builder.append("# 3. Grant the permission a head unit needs\n");
        builder.append("adb shell pm grant ").append(GEARHEAD_PACKAGE).append(' ')
                .append(PERMISSION_FINE_LOCATION).append("\n\n");
        builder.append("# 4. Confirm the entry point resolves\n");
        builder.append("adb shell ").append(launcherQuery(0)).append("\n\n");
        builder.append("# 5. Reboot the head unit\n");
        builder.append("adb reboot\n");
        return builder.toString();
    }

    /** One-line summary of what the app will do, shown in the confirmation dialog. */
    public static String describe(List<String> commands) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < commands.size(); i++) {
            if (i > 0) {
                builder.append('\n');
            }
            builder.append("$ ").append(commands.get(i));
        }
        return builder.toString();
    }
}
