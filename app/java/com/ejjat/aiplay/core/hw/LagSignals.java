package com.ejjat.aiplay.core.hw;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lists the facts in the report that commonly cause lag, so the reader does not have to spot them.
 *
 * <p>These are <b>signals, not a verdict</b>. Each line names what was seen and why it matters. The
 * absence of a signal is not "healthy": a signal can only be raised from something that was read, and
 * the report lists what could not be read. Choosing between a debloat and a firmware change is left to
 * the person reading the whole report.</p>
 */
public final class LagSignals {

    private static final long GIB = 1024L * 1024L * 1024L;

    private LagSignals() {
    }

    public static List<String> analyze(HardwareData d) {
        List<String> out = new ArrayList<String>();

        // ---- memory
        long total = d.mem.valid() ? d.mem.totalBytes() : d.amTotalBytes;
        if (total > 0) {
            if (total < GIB + GIB / 2) {
                out.add("RAM is very small (" + SizeFormat.bytes(total) + "): today's apps and a head "
                        + "unit's own services will keep pushing each other out. Debloating helps only "
                        + "so much here.");
            } else if (total < 2 * GIB + GIB / 2) {
                out.add("RAM is limited (" + SizeFormat.bytes(total) + "): background apps matter.");
            }
        }
        if (d.mem.valid() && d.mem.usedPercent() >= 85) {
            out.add("RAM is nearly full right now (" + d.mem.usedPercent() + "% in use, "
                    + SizeFormat.bytes(d.mem.availableBytes()) + " available): the unit is under memory "
                    + "pressure, the usual cause of apps reloading and stuttering.");
        }
        if (d.lowRamDevice) {
            out.add("Android flags this as a low-RAM device.");
        }
        if (d.mem.swapTotalBytes() > 0 && d.mem.swapUsedPercent() >= 50) {
            out.add("swap is " + d.mem.swapUsedPercent() + "% used (" + SizeFormat.bytes(d.mem.swapUsedBytes())
                    + " of " + SizeFormat.bytes(d.mem.swapTotalBytes()) + "): the unit is leaning on "
                    + "compressed or disk swap, which is slow.");
        }

        // ---- storage
        for (HardwareData.Volume v : d.volumes) {
            if (v.total <= 0) {
                continue;
            }
            int used = SizeFormat.percentUsed(v.total, v.free);
            if (used >= 90 || v.free < GIB) {
                out.add(v.label + " is " + used + "% full (" + SizeFormat.bytes(v.free) + " free): a "
                        + "nearly full flash slows every write and app start, and the system cannot "
                        + "update or cache properly.");
            }
        }
        if (d.flashHealth.contains("BEYOND") || d.flashHealth.contains("WARNING")
                || d.flashHealth.contains("URGENT")) {
            out.add("the flash chip reports wear: " + d.flashHealth);
        }

        if (total > 0 && StorageCheck.ramLooksSetNotMeasured(total)) {
            String pressure = d.mem.valid() && d.mem.usedPercent() >= 80 ? " With " + d.mem.usedPercent()
                    + "% in use, the unit behaves like it has less RAM than it claims." : "";
            out.add("MemTotal is " + SizeFormat.bytes(total) + ", " + SizeFormat.roundRamPercent(total)
                    + "% of a round " + SizeFormat.marketedRam(total) + ". A real kernel reports "
                    + "noticeably less than the installed RAM, so the figure looks set by the firmware "
                    + "rather than measured." + pressure + " Check /proc/iomem and dmesg over adb, and the "
                    + "RAM chips if the unit can be opened.");
        }
        for (String warning : StorageCheck.analyze(d)) {
            out.add(warning);
        }
        if (d.flashWearLowPercent >= 70) {
            boolean swapOnFlash = false;
            for (String swap : d.swapLines) {
                if (!swap.contains("zram")) {
                    swapOnFlash = true;
                }
            }
            out.add("the eMMC reports at least " + d.flashWearLowPercent + "% of its rated life used"
                    + (swapOnFlash && d.mem.swapUsedPercent() >= 10 ? ", and swap lives on that flash "
                    + "(every swapped page is a write that wears it further)" : "")
                    + ". A worn flash gets slower and can fail; heavy writes shorten what is left.");
        }

        // ---- processor
        int cores = d.coreCount();
        if (cores <= 2) {
            out.add("only " + cores + " CPU core(s): a dual-core chip struggles with a modern "
                    + "launcher plus navigation.");
        }
        String klass = ChipGuess.performanceClass(d.cpu, d.maxFreqKhz);
        if (klass.contains("old-generation")) {
            out.add("CPU class: " + klass + ".");
        }
        if (d.maxFreqKhz >= 100000 && d.maxFreqKhz < 1200000) {
            out.add("the top CPU clock is only " + String.format(Locale.US, "%.2f GHz", d.maxFreqKhz / 1e6)
                    + ": slow per core.");
        }
        if (d.abis.length > 0 && !d.has64BitAbi()) {
            out.add("32-bit only (" + HardwareReport.join(d.abis) + "): some current apps ship no 32-bit "
                    + "build, or run slower.");
        }

        // ---- heat and load
        if (d.maxTempC >= 80) {
            out.add("a temperature sensor reads " + d.maxTempC + " C: at this heat chips throttle.");
        }
        double load = loadFirst(d.loadavg);
        if (load >= 0 && load > cores * 1.5) {
            out.add("load average " + String.format(Locale.US, "%.1f", load) + " on " + cores
                    + " core(s): something is using the CPU right now (see the process list if it was "
                    + "readable).");
        }
        if (d.cpuTest != null && d.cpuTest.verdict != null && d.cpuTest.verdict.throttled()) {
            out.add("CPU test: " + d.cpuTest.verdict.text + ".");
        }
        if (d.cpuTest != null && d.cpuTest.threads > 1 && d.cpuTest.scaling() > 0
                && d.cpuTest.scaling() < d.cpuTest.threads * 0.6) {
            out.add(String.format(Locale.US, "CPU test: %d threads did only %.1fx the work of one: some "
                    + "cores were busy with other work or are parked.", d.cpuTest.threads,
                    d.cpuTest.scaling()));
        }

        if (out.isEmpty()) {
            out.add("none of the common lag signals was seen in what could be read. That is not a clean "
                    + "bill of health: see 'could not be read' below, and run the CPU test.");
        }
        return out;
    }

    /** First number of {@code /proc/loadavg} ("1.25 0.90 0.70 1/512 9999"), or -1. */
    public static double loadFirst(String loadavg) {
        if (loadavg == null || loadavg.trim().length() == 0) {
            return -1;
        }
        try {
            return Double.parseDouble(loadavg.trim().split("\\s+")[0]);
        } catch (NumberFormatException error) {
            return -1;
        }
    }
}
