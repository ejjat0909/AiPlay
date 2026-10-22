package com.ejjat.aiplay.device;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.concurrent.TimeUnit;

/**
 * Runs the small, fixed set of shell commands AiPlay needs.
 *
 * <p>Only two entry points exist: {@link #runAsRoot(String)} (requires a working {@code su}) and
 * {@link #runShell(String)} (the app's own uid). AiPlay never bundles an exploit and never escalates
 * on its own; if {@code su} is not there, the UI shows the ADB recipe instead.</p>
 */
public final class PrivilegedShell {

    private static final int TIMEOUT_SECONDS = 12;
    private static final int DEFAULT_MAX_CHARS = 8192;
    private static volatile Boolean rootCache;

    /** Every location a real su binary has shipped in; a missing file means "no root", cheaply. */
    private static final String[] SU_PATHS = {
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
            "/system/sbin/su", "/vendor/bin/su", "/system/bin/failsafe/su"
    };

    private PrivilegedShell() {
    }

    /** Result of one shell invocation. */
    public static final class Result {
        public final int code;
        public final String output;

        Result(int code, String output) {
            this.code = code;
            this.output = output == null ? "" : output;
        }

        public boolean ok() {
            return code == 0;
        }

        public String summary() {
            String trimmed = output.trim();
            if (trimmed.isEmpty()) {
                return ok() ? "done" : "exit " + code;
            }
            return trimmed;
        }
    }

    /** True when a {@code su} binary exists and grants uid 0. Cached for the process lifetime. */
    public static boolean hasRoot() {
        Boolean cached = rootCache;
        if (cached != null) {
            return cached.booleanValue();
        }
        if (!suBinaryExists()) {
            // 0.1.1 ran `su` unconditionally, so every report carried a 14-line
            // IOException: error=2 stack trace for a phone that simply has no su.
            rootCache = Boolean.FALSE;
            Diag.log("shell", "su probe: no su binary (" + SU_PATHS.length + " paths checked)");
            return false;
        }
        boolean granted = false;
        try {
            Result result = exec(new String[]{"su", "-c", "id"}, 3000);
            granted = result.ok() && result.output.contains("uid=0");
        } catch (Exception ignored) {
            granted = false;
        }
        rootCache = Boolean.valueOf(granted);
        Diag.log("shell", "su probe: " + (granted ? "granted" : "present but denied"));
        return granted;
    }

    private static boolean suBinaryExists() {
        for (String path : SU_PATHS) {
            try {
                if (new File(path).exists()) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // a broken path entry must not abort the probe
            }
        }
        return false;
    }

    public static Result runAsRoot(String script) {
        return exec(new String[]{"su", "-c", script}, TIMEOUT_SECONDS * 1000, DEFAULT_MAX_CHARS);
    }

    public static Result runShell(String script) {
        return exec(new String[]{"sh", "-c", script}, TIMEOUT_SECONDS * 1000, DEFAULT_MAX_CHARS);
    }

    /** Like {@link #runShell} for commands with long output (a full {@code getprop} is ~30 KB). */
    public static Result runShellMax(String script, int maxChars) {
        return exec(new String[]{"sh", "-c", script}, TIMEOUT_SECONDS * 1000, maxChars);
    }

    public static Result runAsRootMax(String script, int maxChars) {
        return exec(new String[]{"su", "-c", script}, TIMEOUT_SECONDS * 1000, maxChars);
    }

    private static Result exec(String[] command, int timeoutMillis) {
        return exec(command, timeoutMillis, DEFAULT_MAX_CHARS);
    }

    private static Result exec(String[] command, int timeoutMillis, int maxChars) {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            process = builder.start();
            Writer writer = new OutputStreamWriter(process.getOutputStream());
            writer.close();

            StringBuilder output = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() < maxChars) {
                    output.append(line).append('\n');
                }
            }
            boolean finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroy();
                return new Result(-1, output + "\n[timed out after " + timeoutMillis + " ms]");
            }
            return new Result(process.exitValue(), output.toString());
        } catch (Exception error) {
            // "su exists but this app may not run it" (error=13) and "no such file" (error=2) are
            // ordinary answers, not crashes: one line, no 12-line stack trace in every report.
            String message = String.valueOf(error.getMessage());
            boolean expected = error instanceof java.io.IOException
                    && (message.contains("error=13") || message.contains("error=2,")
                    || message.contains("Permission denied") || message.contains("No such file"));
            if (expected) {
                Diag.log("shell", command[0] + " cannot be run by this app ("
                        + (message.contains("error=13") || message.contains("Permission denied")
                        ? "permission denied" : "not found") + ")");
            } else {
                Diag.log("shell", "exec failed: " + command[0], error);
            }
            return new Result(-1, error.getClass().getSimpleName() + ": " + error.getMessage());
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                } catch (Exception ignored) {
                    // nothing useful to do
                }
            }
        }
    }
}
