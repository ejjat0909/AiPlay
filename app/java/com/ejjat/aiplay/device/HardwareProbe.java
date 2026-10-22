package com.ejjat.aiplay.device;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.os.StatFs;
import android.util.DisplayMetrics;

import com.ejjat.aiplay.core.hw.CpuBench;
import com.ejjat.aiplay.core.hw.CpuInfo;
import com.ejjat.aiplay.core.hw.FlashHealth;
import com.ejjat.aiplay.core.hw.FlashHints;
import com.ejjat.aiplay.core.hw.HardwareData;
import com.ejjat.aiplay.core.hw.MemInfo;
import com.ejjat.aiplay.core.hw.PartitionTable;
import com.ejjat.aiplay.core.hw.ProcessTable;
import com.ejjat.aiplay.core.hw.PropParser;
import com.ejjat.aiplay.core.hw.ThrottleAnalysis;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Reads the hardware facts for the diagnostic report.
 *
 * <p>An ordinary app is allowed to read some of this and not the rest, and which is which varies by
 * Android version and by unit. So <b>every read records its own outcome</b>: a file that is missing or
 * denied is named in {@link HardwareData#unreadable} with the reason, never silently skipped, and
 * nothing here throws. Nothing identifying is kept: serials, MAC addresses and the like are filtered in
 * {@link PropParser}, and the eMMC {@code cid}/{@code csd} registers (which carry a serial number) are
 * never read.</p>
 */
public final class HardwareProbe {

    private static final long MAX_FILE = 256 * 1024;
    private static volatile CpuBench.Result lastCpuTest;

    private HardwareProbe() {
    }

    public static CpuBench.Result lastCpuTest() {
        return lastCpuTest;
    }

    // ---- file helper ------------------------------------------------------------------------

    /** Why a read failed, in words: "permission denied", "not present", or the exception text. */
    static String why(IOException error) {
        String message = String.valueOf(error.getMessage());
        if (message.contains("Permission denied") || message.contains("EACCES")) {
            return "permission denied";
        }
        if (message.contains("No such file") || message.contains("ENOENT")
                || error instanceof FileNotFoundException && message.contains("(No such")) {
            return "not present";
        }
        return message;
    }

    /** The file's text, or null with the reason noted when {@code note} is true. */
    static String read(String path, HardwareData data, boolean note) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(new File(path));
            byte[] buffer = new byte[(int) Math.min(MAX_FILE, 64 * 1024)];
            int total = 0;
            int n;
            while (total < buffer.length && (n = in.read(buffer, total, buffer.length - total)) > 0) {
                total += n;
            }
            return new String(buffer, 0, total, "UTF-8");
        } catch (IOException error) {
            if (note && data != null) {
                data.skip(path, why(error));
            }
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // nothing useful to do
                }
            }
        }
    }

    static String readTrim(String path) {
        String text = read(path, null, false);
        return text == null ? null : text.trim();
    }

    // ---- the collector ----------------------------------------------------------------------

    /** Collects everything that is safe to read without loading the unit. Call off the main thread. */
    public static HardwareData collect(final Context context) {
        final HardwareData d = new HardwareData();
        step(d, "build info", new Step() {
            @Override
            public void run() {
                buildInfo(context, d);
            }
        });
        step(d, "cpu", new Step() {
            @Override
            public void run() {
                cpu(d);
            }
        });
        step(d, "gpu", new Step() {
            @Override
            public void run() {
                gpu(d);
            }
        });
        step(d, "memory", new Step() {
            @Override
            public void run() {
                memory(context, d);
            }
        });
        step(d, "storage", new Step() {
            @Override
            public void run() {
                storage(context, d);
            }
        });
        step(d, "clocks and temperatures", new Step() {
            @Override
            public void run() {
                clocksAndHeat(context, d, true);
            }
        });
        step(d, "load", new Step() {
            @Override
            public void run() {
                load(d);
            }
        });
        step(d, "properties", new Step() {
            @Override
            public void run() {
                properties(d);
            }
        });
        step(d, "partitions", new Step() {
            @Override
            public void run() {
                partitions(d);
            }
        });
        step(d, "packages", new Step() {
            @Override
            public void run() {
                packages(context, d);
            }
        });
        step(d, "processes", new Step() {
            @Override
            public void run() {
                processes(context, d);
            }
        });
        d.cpuTest = lastCpuTest;
        return d;
    }

    private interface Step {
        void run() throws Exception;
    }

    /** One section's failure must not lose the others: any exception becomes an "unreadable" line. */
    private static void step(HardwareData d, String name, Step step) {
        try {
            step.run();
        } catch (Throwable error) {
            Diag.log("hardware", name + " failed", error);
            d.skip(name, error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    private static void buildInfo(Context context, HardwareData d) {
        d.manufacturer = nz(Build.MANUFACTURER);
        d.brand = nz(Build.BRAND);
        d.model = nz(Build.MODEL);
        d.device = nz(Build.DEVICE);
        d.product = nz(Build.PRODUCT);
        d.board = nz(Build.BOARD);
        d.hardware = nz(Build.HARDWARE);
        d.abis = Build.SUPPORTED_ABIS == null ? new String[0] : Build.SUPPORTED_ABIS;
        d.sdk = Build.VERSION.SDK_INT;
        d.androidRelease = nz(Build.VERSION.RELEASE);
        if (Build.VERSION.SDK_INT >= 31) {
            d.socManufacturer = nz(Build.SOC_MANUFACTURER);
            d.socModel = nz(Build.SOC_MODEL);
            // Android fills these with "unknown" when the vendor did not set them.
            if (d.socManufacturer.equalsIgnoreCase("unknown")) {
                d.socManufacturer = "";
            }
            if (d.socModel.equalsIgnoreCase("unknown")) {
                d.socModel = "";
            }
        }
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        d.displayInfo = metrics.widthPixels + "x" + metrics.heightPixels + " @ " + metrics.densityDpi
                + " dpi";
        // /proc/version is denied to apps on newer Android; os.version below is the fallback, so a
        // denial here is not worth a line in "could not be read".
        String version = read("/proc/version", d, false);
        d.kernel = version != null ? firstLine(version) : nz(System.getProperty("os.version"));
        if (d.kernel.length() > 120) {
            d.kernel = d.kernel.substring(0, 120) + "...";
        }
    }

    private static void cpu(HardwareData d) {
        String text = read("/proc/cpuinfo", d, true);
        if (text != null) {
            d.cpu = CpuInfo.parse(text);
        }
        int max = -1;
        for (int i = 0; i < 32; i++) {
            String v = readTrim("/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq");
            if (v == null) {
                v = readTrim("/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_max_freq");
            }
            int khz = parseInt(v);
            if (plausibleKhz(khz) && khz > max) {
                max = khz;
            }
        }
        d.maxFreqKhz = max;
        if (max < 0) {
            d.skip("/sys/devices/system/cpu/cpu*/cpufreq", "no usable clock speed (not readable, or the "
                    + "unit reports a placeholder value)");
        }
    }

    /** A real CPU clock is between 100 MHz and 6 GHz; anything else is a placeholder (emulators report 0 or 1). */
    static boolean plausibleKhz(int khz) {
        return khz >= 100000 && khz <= 6000000;
    }

    /** The GPU's own name: the strongest chip hint after the CPU cores, and hard to rename. */
    private static void gpu(HardwareData d) {
        EGLDisplay display = EGL14.EGL_NO_DISPLAY;
        EGLContext context = EGL14.EGL_NO_CONTEXT;
        EGLSurface surface = EGL14.EGL_NO_SURFACE;
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] version = new int[2];
            if (display == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(display, version, 0, version, 1)) {
                d.skip("GPU name", "no EGL display");
                return;
            }
            int[] attrs = {EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE};
            EGLConfig[] configs = new EGLConfig[1];
            int[] count = new int[1];
            if (!EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) || count[0] == 0) {
                d.skip("GPU name", "no usable EGL config");
                return;
            }
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            surface = EGL14.eglCreatePbufferSurface(display, configs[0],
                    new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
                d.skip("GPU name", "could not make a GL context current");
                return;
            }
            d.gpuRenderer = nz(GLES20.glGetString(GLES20.GL_RENDERER));
            d.gpuVendor = nz(GLES20.glGetString(GLES20.GL_VENDOR));
            d.gpuVersion = nz(GLES20.glGetString(GLES20.GL_VERSION));
        } finally {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT);
                if (surface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(display, surface);
                }
                if (context != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(display, context);
                }
                // eglTerminate is deliberately not called: the display is shared with the app's own
                // hardware-accelerated UI, and tearing it down here could break it.
            }
        }
    }

    private static void memory(Context context, HardwareData d) {
        String text = read("/proc/meminfo", d, true);
        if (text != null) {
            d.mem = MemInfo.parse(text);
        }
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(info);
            d.amTotalBytes = info.totalMem;
            d.amAvailBytes = info.availMem;
            d.amThresholdBytes = info.threshold;
            d.amLowMemory = info.lowMemory;
            d.memoryClassMb = am.getMemoryClass();
            d.largeMemoryClassMb = am.getLargeMemoryClass();
            d.lowRamDevice = am.isLowRamDevice();
        }
    }

    private static void storage(Context context, HardwareData d) {
        addVolume(d, "/data (internal)", Environment.getDataDirectory());
        File shared = Environment.getExternalStorageDirectory();
        if (shared != null) {
            addVolume(d, "shared storage", shared);
        }
        String mounts = read("/proc/mounts", d, true);
        if (mounts != null) {
            for (String line : mounts.split("\n")) {
                String[] cols = line.split("\\s+");
                if (cols.length >= 4 && cols[1].equals("/data")) {
                    d.dataFilesystem = cols[0] + " " + cols[2] + " " + firstOpt(cols[3]);
                    break;
                }
            }
        }
        blockDevices(d);
    }

    private static void addVolume(HardwareData d, String label, File path) {
        try {
            StatFs stat = new StatFs(path.getPath());
            long total = stat.getTotalBytes();
            long free = stat.getAvailableBytes();
            for (HardwareData.Volume known : d.volumes) {
                if (known.total == total && known.free == free) {
                    return;         // the same filesystem listed twice (shared storage on /data)
                }
            }
            d.volumes.add(new HardwareData.Volume(label, total, free));
        } catch (RuntimeException error) {
            d.skip(label, "StatFs failed: " + error.getMessage());
        }
    }

    private static void blockDevices(HardwareData d) {
        File[] entries = new File("/sys/block").listFiles();
        if (entries == null) {
            d.skip("/sys/block", "cannot list (no physical flash details)");
            return;
        }
        Arrays.sort(entries);
        for (File entry : entries) {
            String name = entry.getName();
            boolean emmc = name.matches("mmcblk\\d+");
            boolean scsi = name.matches("sd[a-z]+");
            boolean nvme = name.matches("nvme\\d+n\\d+");
            if (!emmc && !scsi && !nvme) {
                continue;
            }
            String sectors = readTrim("/sys/block/" + name + "/size");
            long bytes = sectors == null ? -1 : parseLong(sectors) * 512L;
            String removable = readTrim("/sys/block/" + name + "/removable");
            StringBuilder detail = new StringBuilder();
            String kind;
            if (emmc) {
                String type = readTrim("/sys/block/" + name + "/device/type");
                kind = "SD".equals(type) ? "SD card" : "MMC".equals(type) ? "eMMC"
                        : "eMMC or SD (type " + type + ")";
                String label = readTrim("/sys/block/" + name + "/device/name");
                if (label != null && label.length() > 0) {
                    detail.append("chip name '").append(label).append("'");
                }
                // The wear registers. cid and csd carry a serial number and are never read.
                String life = readTrim("/sys/block/" + name + "/device/life_time");
                String eol = readTrim("/sys/block/" + name + "/device/pre_eol_info");
                if (life != null || eol != null) {
                    d.flashHealth = FlashHealth.describe(life, eol);
                }
            } else if (scsi) {
                String vendor = readTrim("/sys/block/" + name + "/device/vendor");
                String model = readTrim("/sys/block/" + name + "/device/model");
                boolean usb = "1".equals(removable);
                kind = usb ? "removable disk (USB or SD reader)" : "SCSI disk (UFS storage or a USB disk)";
                if (vendor != null || model != null) {
                    detail.append((vendor == null ? "" : vendor) + " " + (model == null ? "" : model));
                }
            } else {
                kind = "NVMe";
            }
            d.blocks.add(new HardwareData.Block(name, bytes, kind, detail.toString().trim()));
        }
        if (d.blocks.isEmpty()) {
            d.skip("/sys/block", "lists no mmcblk/sd/nvme device the app may read");
        }
    }

    /**
     * Clock and temperature right now. {@code note} is true for the report (record what is unreadable)
     * and false inside the CPU test, which samples once a second.
     */
    private static void clocksAndHeat(Context context, HardwareData d, boolean note) {
        boolean anyFreq = false;
        for (int i = 0; i < 32; i++) {
            String base = "/sys/devices/system/cpu/cpu" + i + "/cpufreq/";
            String cur = readTrim(base + "scaling_cur_freq");
            if (cur == null || !plausibleKhz(parseInt(cur))) {
                continue;
            }
            anyFreq = true;
            String max = readTrim(base + "cpuinfo_max_freq");
            String governor = readTrim(base + "scaling_governor");
            d.cpuFreqLines.add("cpu" + i + ": " + mhz(parseInt(cur)) + " (max " + mhz(parseInt(max))
                    + ", governor " + (governor == null ? "?" : governor) + ")");
        }
        if (!anyFreq && note) {
            d.skip("scaling_cur_freq", "no usable reading (not readable, or a placeholder value)");
        }
        int maxTemp = -1;
        File[] zones = new File("/sys/class/thermal").listFiles();
        if (zones != null) {
            Arrays.sort(zones);
            for (File zone : zones) {
                if (!zone.getName().startsWith("thermal_zone")) {
                    continue;
                }
                int milli = parseInt(readTrim(zone.getPath() + "/temp"));
                int c = normaliseTemp(milli);
                if (c == Integer.MIN_VALUE) {
                    continue;
                }
                String type = readTrim(zone.getPath() + "/type");
                d.thermalLines.add(zone.getName() + " (" + (type == null ? "?" : type) + "): " + c + " C");
                maxTemp = Math.max(maxTemp, c);
            }
        }
        if (d.thermalLines.isEmpty() && note) {
            // checked before the battery line is added: the battery is not the chip
            d.skip("/sys/class/thermal", "no thermal zone readable (the chip's own temperature is "
                    + "hidden from apps)");
        }
        Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null) {
            int tenths = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            if (tenths != Integer.MIN_VALUE && tenths > 0) {
                d.thermalLines.add("battery: " + (tenths / 10) + " C");
                maxTemp = Math.max(maxTemp, tenths / 10);
            }
        }
        if (d.thermalLines.isEmpty() && note) {
            d.skip("temperatures", "no thermal zone and no battery sensor readable");
        }
        d.maxTempC = maxTemp;
        if (Build.VERSION.SDK_INT >= 29) {
            PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (power != null) {
                d.thermalStatus = thermalName(power.getCurrentThermalStatus());
            }
        }
    }

    /** Millidegrees or degrees to degrees, or MIN_VALUE for an implausible or missing reading. */
    static int normaliseTemp(int raw) {
        if (raw == Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        int c = Math.abs(raw) >= 1000 ? raw / 1000 : raw;
        return c < -20 || c > 150 ? Integer.MIN_VALUE : c;
    }

    private static String thermalName(int status) {
        switch (status) {
            case 0: return "none";
            case 1: return "light";
            case 2: return "moderate";
            case 3: return "severe";
            case 4: return "critical";
            case 5: return "emergency";
            case 6: return "shutdown";
            default: return "unknown (" + status + ")";
        }
    }

    private static void load(HardwareData d) {
        String text = read("/proc/loadavg", d, true);
        if (text != null) {
            d.loadavg = text.trim();
        }
    }

    private static void properties(HardwareData d) {
        PrivilegedShell.Result result = PrivilegedShell.runShellMax("getprop", 200000);
        if (!result.ok() && result.output.length() < 100) {
            d.skip("getprop", "could not run: " + result.summary());
            return;
        }
        java.util.Map<String, String> all = PropParser.parseGetprop(result.output);
        d.propsSeen = all.size();
        d.props.putAll(PropParser.select(all));
        if (all.isEmpty()) {
            d.skip("getprop", "ran but listed nothing");
        }
    }

    private static void partitions(HardwareData d) {
        String text = read("/proc/partitions", d, true);
        if (text != null) {
            d.partitions = PartitionTable.parse(text);
            if (!d.partitions.valid()) {
                d.skip("/proc/partitions", "read but empty");
            }
        }
    }

    /** Counts packages and lists only vendor-style and update-related names, never the full list. */
    private static void packages(Context context, HardwareData d) {
        PackageManager pm = context.getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(0);
        d.packagesTotal = apps.size();
        int user = 0;
        List<String> vendor = new ArrayList<String>();
        List<String> update = new ArrayList<String>();
        for (ApplicationInfo app : apps) {
            if ((app.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                user++;
            }
            if (FlashHints.isVendorPackage(app.packageName)) {
                vendor.add(app.packageName);
            }
            if (FlashHints.isUpdatePackage(app.packageName)) {
                update.add(app.packageName);
            }
        }
        Collections.sort(vendor);
        Collections.sort(update);
        d.packagesUser = user;
        d.vendorPackages.addAll(vendor.subList(0, Math.min(12, vendor.size())));
        d.updatePackages.addAll(update.subList(0, Math.min(12, update.size())));
        if (d.packagesTotal <= 5) {
            d.skip("package list", "only " + d.packagesTotal + " packages visible: Android is hiding "
                    + "other apps from AiPlay");
        }
    }

    /**
     * Android only shows an app its own processes (since 5.1; 7.0 closed /proc), so without root this
     * will usually list AiPlay alone. That is detected and said, with the commands to run instead,
     * rather than printing a one-line "process list".
     */
    private static void processes(Context context, HardwareData d) {
        String own = context.getPackageName();
        PrivilegedShell.Result ps = PrivilegedShell.runShellMax("ps -A -o PID,RSS,NAME 2>&1 || ps 2>&1", 60000);
        ProcessTable table = ProcessTable.parse(ps.output);

        d.root = PrivilegedShell.hasRoot();
        if (d.root) {
            PrivilegedShell.Result rootPs = PrivilegedShell.runAsRootMax("ps -A -o PID,RSS,NAME 2>&1 || ps 2>&1", 60000);
            ProcessTable rootTable = ProcessTable.parse(rootPs.output);
            if (rootTable.valid()) {
                table = rootTable;
            }
            d.rootTop = trimOutput(PrivilegedShell.runAsRootMax("top -b -n 1 -m 15 2>&1", 6000).output, 40);
            d.rootMeminfo = trimOutput(PrivilegedShell.runAsRootMax("dumpsys meminfo 2>&1", 8000).output, 30);
        }
        if (table.valid() && table.looksSystemWide()) {
            d.processes = table;
            d.processNote = "";
        } else if (table.valid()) {
            d.processNote = "Android only lets AiPlay see its own processes" + (d.root ? "" : " (no root)")
                    + "; the list showed " + table.all.size() + " entr" + (table.all.size() == 1 ? "y" : "ies")
                    + " (" + own + " and the shell that ran ps)";
        } else {
            d.processNote = "the process list could not be read" + (ps.output.length() > 0
                    ? ": " + firstLine(ps.output) : "");
        }
    }

    // ---- the CPU test -----------------------------------------------------------------------

    /**
     * Loads every core for a few seconds and records work, clock and temperature each second, then
     * decides whether the chip throttled. Blocks for about {@link #TEST_SECONDS} seconds: call it off
     * the main thread. {@code progress} is called once per second with the seconds done.
     */
    public static final int WARM_SECONDS = 1;
    public static final int SINGLE_SECONDS = 2;
    public static final int ALL_SECONDS = 5;
    public static final int TEST_SECONDS = WARM_SECONDS + SINGLE_SECONDS + ALL_SECONDS;

    public static CpuBench.Result runCpuTest(final Context context, final Progress progress)
            throws InterruptedException {
        final int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        final List<Integer> temps = new ArrayList<Integer>();
        final List<Integer> freqs = new ArrayList<Integer>();
        Diag.log("hardware", "CPU test: " + cores + " threads, " + TEST_SECONDS + " s");
        CpuBench.Result result = CpuBench.run(cores, WARM_SECONDS, SINGLE_SECONDS, ALL_SECONDS,
                new CpuBench.Sampler() {
            @Override
            public void onSecond(int phase, int second, long ops) {
                int done = phase == CpuBench.PHASE_WARMUP ? second + 1
                        : phase == CpuBench.PHASE_SINGLE ? WARM_SECONDS + second + 1
                        : WARM_SECONDS + SINGLE_SECONDS + second + 1;
                if (progress != null) {
                    progress.onSecond(done, TEST_SECONDS);
                }
                if (phase != CpuBench.PHASE_ALL) {
                    return;
                }
                HardwareData sample = new HardwareData();
                clocksAndHeat(context, sample, false);
                temps.add(sample.maxTempC);
                freqs.add(averageKhz(sample));
            }
        });
        result.tempsC = toArray(temps);
        result.avgFreqKhz = toArray(freqs);
        result.verdict = ThrottleAnalysis.analyze(result.allCoreKopsPerSecond, result.tempsC,
                result.avgFreqKhz);
        lastCpuTest = result;
        Diag.log("hardware", "CPU test done: single " + result.singleThreadKops + " Kops/s, all-core "
                + result.allCoreKopsAverage() + " Kops/s, " + result.verdict.text);
        return result;
    }

    /** Receives the seconds done. */
    public interface Progress {
        void onSecond(int done, int total);
    }

    private static int averageKhz(HardwareData sample) {
        long sum = 0;
        int n = 0;
        for (String line : sample.cpuFreqLines) {
            int mhzAt = line.indexOf(": ");
            int mhzEnd = line.indexOf(" MHz");
            if (mhzAt > 0 && mhzEnd > mhzAt) {
                int mhz = parseInt(line.substring(mhzAt + 2, mhzEnd));
                if (mhz > 0) {
                    sum += mhz * 1000L;
                    n++;
                }
            }
        }
        return n == 0 ? -1 : (int) (sum / n);
    }

    private static int[] toArray(List<Integer> list) {
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i);
        }
        return out;
    }

    // ---- tiny helpers -----------------------------------------------------------------------

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private static String firstLine(String text) {
        int end = text.indexOf('\n');
        return (end < 0 ? text : text.substring(0, end)).trim();
    }

    private static String firstOpt(String options) {
        int comma = options.indexOf(',');
        return comma < 0 ? options : options.substring(0, comma);
    }

    private static String mhz(int khz) {
        return khz <= 0 ? "? MHz" : (khz / 1000) + " MHz";
    }

    private static int parseInt(String value) {
        try {
            return value == null ? Integer.MIN_VALUE : Integer.parseInt(value.trim());
        } catch (NumberFormatException error) {
            return Integer.MIN_VALUE;
        }
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    private static String trimOutput(String text, int maxLines) {
        StringBuilder out = new StringBuilder();
        BufferedReader reader = new BufferedReader(new java.io.StringReader(text));
        try {
            String line;
            int n = 0;
            while ((line = reader.readLine()) != null && n++ < maxLines) {
                out.append("  ").append(line).append('\n');
            }
        } catch (IOException ignored) {
            // a StringReader does not fail
        }
        return out.toString().trim().length() == 0 ? "" : out.toString().replaceAll("\\s+$", "");
    }
}
