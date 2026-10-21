package com.ejjat.aiplay.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class PrivilegePlanTest {

    @Test
    public void enableCommandsUseCurrentUser() {
        List<String> commands = PrivilegePlan.enableCommands();
        assertEquals(2, commands.size());
        assertEquals("pm enable --user 0 com.google.android.projection.gearhead", commands.get(0));
        assertTrue(commands.get(1).startsWith("pm grant com.google.android.projection.gearhead"));
    }

    @Test
    public void repairReinstallsBeforeEnabling() {
        List<String> commands = PrivilegePlan.repairCommands();
        assertEquals(3, commands.size());
        assertEquals("pm install-existing --user 0 com.google.android.projection.gearhead", commands.get(0));
        assertEquals("pm enable --user 0 com.google.android.projection.gearhead", commands.get(1));
    }

    @Test
    public void suScriptJoinsWithSemicolons() {
        String script = PrivilegePlan.asSuScript(PrivilegePlan.enableCommands());
        assertEquals("pm enable --user 0 com.google.android.projection.gearhead; "
                + "pm grant com.google.android.projection.gearhead android.permission.ACCESS_FINE_LOCATION",
                script);
    }

    @Test
    public void recipeMentionsEveryStep() {
        String recipe = PrivilegePlan.adbRecipe();
        assertTrue(recipe.contains("adb shell pm list packages -d"));
        assertTrue(recipe.contains("pm enable --user 0"));
        assertTrue(recipe.contains("pm grant"));
        assertTrue(recipe.contains("adb reboot"));
    }

    /**
     * Device evidence: an app uid running {@code cmd package resolve-activity} without --user gets
     * {@code Permission Denial: ... user -2 ... requires INTERACT_ACROSS_USERS}. Every inspect
     * command that accepts --user must therefore carry an explicit user id.
     */
    @Test
    public void inspectCommandsAlwaysPassExplicitUser() {
        List<String> commands = PrivilegePlan.inspectCommands(7);
        assertEquals(3, commands.size());
        assertEquals("pm list packages -d --user 7 | grep -i gearhead", commands.get(0));
        assertTrue(commands.get(1).contains("--user 7"));
        assertTrue(commands.get(1).contains("-a android.intent.action.MAIN"));
        assertTrue(commands.get(1).contains("-c android.intent.category.LAUNCHER"));
        assertTrue(commands.get(1).contains("-p com.google.android.projection.gearhead"));
        for (String command : commands) {
            if (command.startsWith("pm ") || command.startsWith("cmd ")) {
                assertTrue(command + " must pass --user", command.contains("--user "));
            }
        }
    }

    /** A bare package name is a URI to {@code resolve-activity} and always answers "No activity". */
    @Test
    public void launcherQueryIsARealIntent() {
        String query = PrivilegePlan.launcherQuery(0);
        assertTrue(query.startsWith("cmd package resolve-activity --brief --user 0 "));
        assertTrue(query.contains("-a android.intent.action.MAIN"));
        assertTrue(query.contains("-c android.intent.category.LAUNCHER"));
        assertTrue(query.contains("-p com.google.android.projection.gearhead"));
        assertFalse(query.endsWith("--brief --user 0 com.google.android.projection.gearhead"));
    }

    @Test
    public void recipeUsesTheRealLauncherQuery() {
        assertTrue(PrivilegePlan.adbRecipe().contains(PrivilegePlan.launcherQuery(0)));
    }

    @Test
    public void describeIsShellReadable() {
        String text = PrivilegePlan.describe(PrivilegePlan.enableCommands());
        assertTrue(text.startsWith("$ pm enable"));
        assertTrue(text.contains("\n$ pm grant"));
    }
}
