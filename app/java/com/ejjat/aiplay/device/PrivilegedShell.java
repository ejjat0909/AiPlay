package com.ejjat.aiplay.device;

import java.io.BufferedReader;
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
    private static volatile Boolean rootCache;

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
        boolean granted = false;
        try {
            Result result = exec(new String[]{"su", "-c", "id"}, 3000);
            granted = result.ok() && result.output.contains("uid=0");
        } catch (Exception ignored) {
            granted = false;
        }
        rootCache = Boolean.valueOf(granted);
        Diag.log("shell", "su probe: " + (granted ? "granted" : "unavailable"));
        return granted;
    }

    public static Result runAsRoot(String script) {
        return exec(new String[]{"su", "-c", script}, TIMEOUT_SECONDS * 1000);
    }

    public static Result runShell(String script) {
        return exec(new String[]{"sh", "-c", script}, TIMEOUT_SECONDS * 1000);
    }

    private static Result exec(String[] command, int timeoutMillis) {
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
                if (output.length() < 8192) {
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
            Diag.log("shell", "exec failed: " + command[0], error);
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
