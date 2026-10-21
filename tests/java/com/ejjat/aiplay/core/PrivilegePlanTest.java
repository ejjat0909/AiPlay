package com.ejjat.aiplay.core;

import static org.junit.Assert.assertEquals;
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

    @Test
    public void describeIsShellReadable() {
        String text = PrivilegePlan.describe(PrivilegePlan.enableCommands());
        assertTrue(text.startsWith("$ pm enable"));
        assertTrue(text.contains("\n$ pm grant"));
    }
}
