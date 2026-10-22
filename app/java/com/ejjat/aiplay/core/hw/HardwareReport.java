package com.ejjat.aiplay.core.hw;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Formats {@link HardwareData} as the "hardware" section of the diagnostic report.
 *
 * <p>The goal is that one pasted report is enough for someone who has never seen the unit to judge its
 * chip, memory, storage, lag causes and flashability. Anything not read is said to be unread, with the
 * reason, and given the command to run instead where there is one.</p>
 */
public final class HardwareReport {

    private HardwareReport() {
    }

    public static String format(HardwareData d) {
        StringBuilder o = new StringBuilder(8192);
        o.append("hardware\n--------\n\n");
        chip(o, d);
        memory(o, d);
        storage(o, d);
        load(o, d);
        processes(o, d);
        flash(o, d);
        o.append("properties (identifying ones are removed; ").append(d.props.size()).append(" of ")
                .append(d.propsSeen).append(" shown)\n");
        o.append("----------------------------------------------\n");
        if (d.props.isEmpty()) {
            o.append("none readable\n");
        }
        for (Map.Entry<String, String> entry : d.props.entrySet()) {
            o.append("  ").append(entry.getKey()).append(" = ").append(entry.getValue()).append('\n');
        }
        o.append('\n');
        o.append("could not be read\n-----------------\n");
        if (d.unreadable.isEmpty()) {
            o.append("everything the report asks for was readable\n");
        }
        for (String line : d.unreadable) {
            o.append("  - ").append(line).append('\n');
        }
        List<String> gaps = AdbFallbacks.commands(d);
        if (!gaps.isEmpty()) {
            o.append("to fill the gaps, run these over adb from a computer and paste the output "
                    + "after this report:\n");
            for (String command : gaps) {
                o.append("    ").append(command).append('\n');
            }
        }
        o.append('\n');
        o.append("lag signals (facts that commonly cause lag; not a verdict)\n");
        o.append("-----------------------------------------------------------\n");
        for (String line : LagSignals.analyze(d)) {
            o.append("  * ").append(line).append('\n');
        }
        o.append('\n');
        return o.toString();
    }

    private static void line(StringBuilder o, String label, String value) {
        o.append(String.format(Locale.US, "%-14s: ", label)).append(value.length() == 0 ? "-" : value)
                .append('\n');
    }

    private static void chip(StringBuilder o, HardwareData d) {
        o.append("chip and board\n--------------\n");
        line(o, "SoC", (d.socManufacturer + " " + d.socModel).trim().length() == 0
                ? (d.sdk >= 31 ? "not reported" : "not reported (Build.SOC_* needs Android 12)")
                : (d.socManufacturer + " " + d.socModel).trim());
        line(o, "board", d.board);
        line(o, "hardware", d.hardware);
        line(o, "device", d.device + (d.product.length() > 0 ? " / product " + d.product : ""));
        line(o, "model", d.manufacturer + " " + d.model + (d.brand.length() > 0 ? " (brand " + d.brand + ")" : ""));
        line(o, "ABIs", join(d.abis));
        line(o, "android", d.androidRelease + " (API " + d.sdk + ")");
        line(o, "kernel", d.kernel);
        line(o, "cpu cores", d.cpu.cores.isEmpty() ? "not readable" : d.cpu.cores.size() + ": "
                + d.cpu.coreSummary());
        if (d.cpu.hardware.length() > 0) {
            line(o, "cpuinfo HW", d.cpu.hardware);
        }
        if (d.maxFreqKhz > 0) {
            line(o, "max clock", String.format(Locale.US, "%.2f GHz", d.maxFreqKhz / 1e6));
        }
        String klass = ChipGuess.performanceClass(d.cpu, d.maxFreqKhz);
        if (klass.length() > 0) {
            line(o, "core class", klass);
        }
        line(o, "GPU", (d.gpuRenderer + (d.gpuVendor.length() > 0 ? " (" + d.gpuVendor + ")" : "")).trim());
        if (d.gpuVersion.length() > 0) {
            line(o, "GL version", d.gpuVersion);
        }
        line(o, "display", d.displayInfo);

        Map<String, String> ids = new java.util.LinkedHashMap<String, String>();
        ids.put("Build.SOC_MANUFACTURER", d.socManufacturer);
        ids.put("Build.SOC_MODEL", d.socModel);
        ids.put("Build.BOARD", d.board);
        ids.put("Build.HARDWARE", d.hardware);
        ids.put("Build.DEVICE", d.device);
        ids.put("Build.PRODUCT", d.product);
        ids.put("cpuinfo Hardware", d.cpu.hardware);
        ids.put("GL renderer", d.gpuRenderer);
        for (String key : new String[]{"ro.board.platform", "ro.hardware", "ro.product.board",
                "ro.chipname", "ro.soc.model", "ro.soc.manufacturer", "ro.mediatek.platform",
                "ro.boot.hardware"}) {
            ids.put(key, PropParser.first(d.props, key));
        }
        ChipGuess.Result guess = ChipGuess.guess(ids);
        o.append("chip family   : ").append(guess.family.length() == 0
                ? "not recognised from any identifier" : guess.family).append('\n');
        for (String e : guess.evidence) {
            o.append("                ").append(e).append('\n');
        }
        if (!guess.conflicts.isEmpty()) {
            o.append("                CONFLICT: identifiers also match ").append(join(guess.conflicts))
                    .append(" (a renamed or spoofed value; trust the core designs above)\n");
        }
        if (!guess.generic.isEmpty()) {
            o.append("                generic placeholders (no information): ")
                    .append(join(guess.generic)).append('\n');
        }
        o.append('\n');
    }

    private static void memory(StringBuilder o, HardwareData d) {
        o.append("memory\n------\n");
        if (d.mem.valid()) {
            long total = d.mem.totalBytes();
            line(o, "RAM total", SizeFormat.bytes(total) + "  (nearest marketed size: "
                    + SizeFormat.marketedRam(total) + "; the kernel reserves some)");
            line(o, "RAM available", SizeFormat.bytes(d.mem.availableBytes()) + "  ("
                    + d.mem.usedPercent() + "% in use)");
            line(o, "MemFree", SizeFormat.bytes(d.mem.get("MemFree") * 1024L));
            line(o, "swap", d.mem.swapTotalBytes() == 0 ? "none" : SizeFormat.bytes(d.mem.swapUsedBytes())
                    + " used of " + SizeFormat.bytes(d.mem.swapTotalBytes()) + " (" + d.mem.swapUsedPercent()
                    + "%)");
            if (d.mem.get("CmaTotal") > 0) {
                line(o, "CMA", SizeFormat.bytes(d.mem.get("CmaTotal") * 1024L) + " reserved (video/camera)");
            }
        } else {
            line(o, "RAM (meminfo)", "not readable");
        }
        if (d.amTotalBytes > 0) {
            line(o, "RAM (Android)", SizeFormat.bytes(d.amTotalBytes) + " total, "
                    + SizeFormat.bytes(d.amAvailBytes) + " available, low-memory threshold "
                    + SizeFormat.bytes(d.amThresholdBytes) + (d.amLowMemory ? "  ** LOW MEMORY **" : ""));
            if (d.mem.valid()) {
                double ratio = (double) d.amTotalBytes / Math.max(1, d.mem.totalBytes());
                if (ratio < 0.9 || ratio > 1.1) {
                    o.append("                the two totals differ by more than 10%: one of them is "
                            + "not telling the truth\n");
                }
            }
        }
        line(o, "app heap", d.memoryClassMb < 0 ? "" : d.memoryClassMb + " MB per app ("
                + d.largeMemoryClassMb + " MB large); low-RAM flag: " + d.lowRamDevice);
        o.append("                A Settings screen can show a spoofed size; /proc/meminfo comes from "
                + "the kernel.\n\n");
    }

    private static void storage(StringBuilder o, HardwareData d) {
        o.append("storage\n-------\n");
        if (d.volumes.isEmpty()) {
            line(o, "volumes", "not readable");
        }
        for (HardwareData.Volume v : d.volumes) {
            line(o, v.label, SizeFormat.bytes(v.total) + " total, " + SizeFormat.bytes(v.free)
                    + " free, " + SizeFormat.percentUsed(v.total, v.free) + "% full  (marketed size >= "
                    + SizeFormat.marketedStorage(v.total) + ")");
        }
        if (d.dataFilesystem.length() > 0) {
            line(o, "/data mount", d.dataFilesystem);
        }
        if (d.blocks.isEmpty()) {
            line(o, "flash chip", "not readable (/sys/block was not accessible)");
        }
        for (HardwareData.Block b : d.blocks) {
            line(o, "flash " + b.name, SizeFormat.bytes(b.sizeBytes) + " raw, " + b.kind
                    + (b.detail.length() > 0 ? ", " + b.detail : "") + "  (marketed >= "
                    + SizeFormat.marketedStorage(b.sizeBytes) + ")");
        }
        line(o, "flash health", d.flashHealth.length() == 0
                ? "not exposed by this kernel (no life_time / pre_eol_info)" : d.flashHealth);
        o.append('\n');
    }

    private static void load(StringBuilder o, HardwareData d) {
        o.append("cpu load, clocks and heat\n-------------------------\n");
        line(o, "load average", d.loadavg.length() == 0 ? "not readable" : d.loadavg + "  (1, 5, 15 min; "
                + d.coreCount() + " cores)");
        if (d.cpuFreqLines.isEmpty()) {
            line(o, "cpu clocks", "not readable (/sys/devices/system/cpu/*/cpufreq)");
        }
        for (String s : d.cpuFreqLines) {
            o.append("  ").append(s).append('\n');
        }
        if (d.thermalLines.isEmpty()) {
            line(o, "temperatures", "not readable (/sys/class/thermal)");
        }
        for (String s : d.thermalLines) {
            o.append("  ").append(s).append('\n');
        }
        line(o, "thermal state", d.thermalStatus.length() == 0 ? "not available (needs Android 10)"
                : d.thermalStatus);
        CpuBench.Result t = d.cpuTest;
        if (t == null) {
            line(o, "CPU test", "not run (press 'Run CPU test' on the Diagnostics screen: it loads "
                    + "every core for 8 s)");
        } else {
            line(o, "CPU test", t.threads + " threads. One core: " + t.singleThreadKops + " Kops/s. All "
                    + "cores: " + t.allCoreKopsAverage() + " Kops/s (" + String.format(Locale.US, "%.1f",
                    t.scaling()) + "x one core).");
            StringBuilder series = new StringBuilder();
            for (int i = 0; i < t.allCoreKopsPerSecond.length; i++) {
                if (i > 0) {
                    series.append("  ");
                }
                series.append(t.allCoreKopsPerSecond[i]);
                if (i < t.tempsC.length && t.tempsC[i] >= 0) {
                    series.append('@').append(t.tempsC[i]).append('C');
                }
                if (i < t.avgFreqKhz.length && t.avgFreqKhz[i] > 0) {
                    series.append('/').append(t.avgFreqKhz[i] / 1000).append("MHz");
                }
            }
            o.append("                per second (Kops[@temp][/avg clock]): ").append(series).append('\n');
            o.append("                ").append(t.verdict == null ? "" : t.verdict.text).append('\n');
            o.append("                (iteration counts, not a standard benchmark: compare runs, not "
                    + "published scores)\n");
        }
        o.append('\n');
    }

    private static void processes(StringBuilder o, HardwareData d) {
        o.append("running processes\n-----------------\n");
        if (d.processes.valid() && d.processNote.length() == 0) {
            o.append("top by memory (RSS):\n");
            for (ProcessTable.Proc p : d.processes.top(15)) {
                o.append(String.format(Locale.US, "  %8s  pid %-6d %s%n", SizeFormat.bytes(p.rssKb * 1024L),
                        p.pid, p.name));
            }
        } else {
            o.append("not captured: ").append(d.processNote.length() == 0
                    ? "no process list was readable" : d.processNote).append('\n');
            o.append("Run these over adb from a computer and paste the output:\n");
            o.append("  adb shell top -b -n 1 -m 15\n");
            o.append("  adb shell dumpsys meminfo\n");
            o.append("  adb shell ps -A -o PID,RSS,NAME\n");
        }
        if (d.rootTop.length() > 0) {
            o.append("top (root):\n").append(d.rootTop).append('\n');
        }
        if (d.rootMeminfo.length() > 0) {
            o.append("dumpsys meminfo (root, first lines):\n").append(d.rootMeminfo).append('\n');
        }
        o.append('\n');
    }

    private static void flash(StringBuilder o, HardwareData d) {
        o.append("flashability\n------------\n");
        for (String line : FlashHints.analyze(d)) {
            o.append("  - ").append(line).append('\n');
        }
        PartitionTable pt = d.partitions;
        if (pt.valid()) {
            long diskKb = 0;
            for (PartitionTable.Entry disk : pt.disks()) {
                diskKb += disk.kb;
            }
            o.append("partitions (").append(pt.entries.size()).append(" entries, whole disks ")
                    .append(SizeFormat.bytes(diskKb * 1024L)).append("):\n");
            for (PartitionTable.Entry e : pt.notable()) {
                o.append(String.format(Locale.US, "  %-14s %s%n", e.name, SizeFormat.bytes(e.kb * 1024L)));
            }
        } else {
            o.append("partitions: not readable (/proc/partitions)\n");
        }
        o.append('\n');
    }

    static String join(String[] items) {
        StringBuilder out = new StringBuilder();
        for (String item : items) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(item);
        }
        return out.toString();
    }

    static String join(List<String> items) {
        return join(items.toArray(new String[0]));
    }
}
