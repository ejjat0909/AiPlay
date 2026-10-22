package com.ejjat.aiplay.core.hw;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@code adb shell} commands that fill the gaps this report could not read itself.
 *
 * <p>An app runs with its own, restricted identity; {@code adb shell} runs as the shell user, which
 * Android lets read most of these files. So for each reading that was blocked, the report names the
 * command whose output completes it, to be pasted next to the report.</p>
 */
public final class AdbFallbacks {

    private AdbFallbacks() {
    }

    public static List<String> commands(HardwareData d) {
        List<String> out = new ArrayList<String>();
        if (d.loadavg.length() == 0) {
            out.add("adb shell cat /proc/loadavg /proc/stat");
        }
        if (d.cpuFreqLines.isEmpty() || d.maxFreqKhz < 0) {
            out.add("adb shell \"cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq "
                    + "/sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq\"");
        }
        boolean zones = false;
        for (String line : d.thermalLines) {
            if (line.startsWith("thermal_zone")) {
                zones = true;
            }
        }
        if (!zones) {
            out.add("adb shell \"cat /sys/class/thermal/thermal_zone*/type "
                    + "/sys/class/thermal/thermal_zone*/temp\"");
        }
        if (!d.partitions.valid()) {
            out.add("adb shell cat /proc/partitions");
            out.add("adb shell ls -l /dev/block/by-name");
        }
        if (d.blocks.isEmpty()) {
            out.add("adb shell \"ls /sys/block; cat /sys/block/mmcblk0/device/life_time "
                    + "/sys/block/mmcblk0/device/pre_eol_info\"");
        }
        if (!hasLockState(d)) {
            out.add("adb shell \"getprop | grep -i -E 'verifiedboot|flash.locked|vbmeta|oem_unlock|"
                    + "ro.secure|ro.debuggable'\"");
        }
        if (d.cpu.cores.isEmpty()) {
            out.add("adb shell cat /proc/cpuinfo");
        }
        if (!d.mem.valid()) {
            out.add("adb shell cat /proc/meminfo");
        }
        if (d.gpuRenderer.length() == 0) {
            out.add("adb shell dumpsys SurfaceFlinger | grep -i -E 'GLES|renderer'");
        }
        return out;
    }

    private static boolean hasLockState(HardwareData d) {
        return PropParser.firstReal(d.props, "ro.boot.flash.locked", "ro.boot.verifiedbootstate",
                "ro.boot.vbmeta.device_state").length() > 0;
    }
}
