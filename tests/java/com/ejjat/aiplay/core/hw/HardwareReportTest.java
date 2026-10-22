package com.ejjat.aiplay.core.hw;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Map;

/** The whole section, formatted from fixture data the way a head unit would fill it. */
public class HardwareReportTest {

    private static HardwareData rockchipUnit() {
        HardwareData d = new HardwareData();
        d.manufacturer = "Generic";
        d.model = "rk3399_all";
        d.device = "rk3399_all";
        d.board = "rk30sdk";
        d.hardware = "rk30board";
        d.abis = new String[]{"arm64-v8a", "armeabi-v7a", "armeabi"};
        d.sdk = 27;
        d.androidRelease = "8.1.0";
        d.cpu = CpuInfo.parse(HwFixtures.CPUINFO_ARM64_BIGLITTLE);
        d.kernel = "4.4.167";
        d.gpuRenderer = "Mali-T860";
        d.gpuVendor = "ARM";
        d.displayInfo = "1024x600 @ 160 dpi";
        d.maxFreqKhz = 1800000;
        d.mem = MemInfo.parse(HwFixtures.MEMINFO_4GB);
        d.amTotalBytes = 3786912L * 1024;
        d.amAvailBytes = 900L << 20;
        d.amThresholdBytes = 150L << 20;
        d.memoryClassMb = 192;
        d.largeMemoryClassMb = 512;
        d.volumes.add(new HardwareData.Volume("/data", 26L << 30, 2L << 30));
        d.blocks.add(new HardwareData.Block("mmcblk0", 30535680L * 1024, "eMMC", "name 'AJNB4R'"));
        d.dataFilesystem = "/dev/block/mmcblk0p10 ext4 rw";
        d.flashHealth = FlashHealth.describe("0x03 0x03", "0x01");
        d.loadavg = "1.10 0.90 0.70 2/300 5000";
        d.cpuFreqLines.add("cpu0: 600 MHz (max 1416 MHz, governor interactive)");
        d.thermalLines.add("thermal_zone0 (soc-thermal): 48 C");
        d.maxTempC = 48;
        d.thermalStatus = "none";
        Map<String, String> all = PropParser.parseGetprop(HwFixtures.GETPROP_HEADUNIT);
        d.propsSeen = all.size();
        d.props.putAll(PropParser.select(all));
        d.partitions = PartitionTable.parse(HwFixtures.PARTITIONS_ROCKCHIP_NAMED);
        d.packagesTotal = 180;
        d.packagesUser = 12;
        d.vendorPackages.add("com.syu.music");
        d.updatePackages.add("com.rockchip.updater");
        d.skip("/proc/stat", "permission denied");
        return d;
    }

    @Test
    public void everyPartOfTheRequestedReportIsPresent() {
        String r = HardwareReport.format(rockchipUnit());
        // chip and board
        for (String must : new String[]{"chip and board", "SoC", "board", "rk30sdk", "hardware",
                "ABIs", "arm64-v8a", "kernel", "4.4.167", "4x Cortex-A53 + 2x Cortex-A72", "GPU", "Mali-T860",
                "chip family   : Rockchip", "ro.board.platform = rk3399", "core class", "max clock"}) {
            assertTrue("missing '" + must + "' in:\n" + r, r.contains(must));
        }
        // memory and storage
        for (String must : new String[]{"memory", "RAM total", "3.61 GiB", "nearest marketed size: 4 GB",
                "RAM available", "swap", "storage", "/data", "92% full", "flash mmcblk0", "eMMC",
                "marketed >= 32 GB", "flash health", "20-30% of rated life used"}) {
            assertTrue("missing '" + must + "' in:\n" + r, r.contains(must));
        }
        // why it lags
        for (String must : new String[]{"cpu load, clocks and heat", "load average", "cpu0: 600 MHz",
                "thermal_zone0", "thermal state", "CPU test", "not run", "running processes",
                "lag signals"}) {
            assertTrue("missing '" + must + "' in:\n" + r, r.contains(must));
        }
        // flashability
        for (String must : new String[]{"flashability", "orange (bootloader unlocked)", "A/B slots: no",
                "recovery partition: present", "ro.mcu.version=V2.3.1", "com.syu.music",
                "com.rockchip.updater", "partitions (", "recovery", "NOT determinable"}) {
            assertTrue("missing '" + must + "' in:\n" + r, r.contains(must));
        }
    }

    @Test
    public void nothingPersonalIsInTheReport() {
        String r = HardwareReport.format(rockchipUnit());
        for (String secret : HwFixtures.SECRETS) {
            assertFalse("leaked " + secret, r.contains(secret));
        }
    }

    @Test
    public void unreadableThingsAreListedWithAReasonNotLeftBlank() {
        String r = HardwareReport.format(rockchipUnit());
        assertTrue(r, r.contains("could not be read"));
        assertTrue(r, r.contains("/proc/stat: permission denied"));
    }

    @Test
    public void withoutAProcessListTheReportPrintsTheCommandsToRunInstead() {
        HardwareData d = rockchipUnit();
        d.processNote = "Android only shows an app its own processes";
        String r = HardwareReport.format(d);
        assertTrue(r, r.contains("not captured: Android only shows an app its own processes"));
        assertTrue(r, r.contains("adb shell top -b -n 1 -m 15"));
        assertTrue(r, r.contains("adb shell dumpsys meminfo"));
        assertTrue(r, r.contains("adb shell ps -A -o PID,RSS,NAME"));
    }

    @Test
    public void aReadableProcessListIsShownBiggestFirst() {
        HardwareData d = rockchipUnit();
        d.processes = ProcessTable.parse(HwFixtures.PS_TOYBOX);
        d.processNote = "";
        String r = HardwareReport.format(d);
        assertTrue(r, r.contains("top by memory (RSS):"));
        assertTrue(r.indexOf("com.google.android.gms") < r.indexOf("com.android.launcher3"));
        assertFalse(r.contains("adb shell top -b"));
    }

    @Test
    public void anEmptyUnitStillFormatsAndSaysEverythingIsUnread() {
        HardwareData d = new HardwareData();
        d.skip("/proc/cpuinfo", "denied");
        String r = HardwareReport.format(d);
        assertTrue(r, r.contains("not readable"));
        assertTrue(r, r.contains("not recognised from any identifier"));
        assertTrue(r, r.contains("none readable"));
        assertTrue(r, r.contains("not a clean bill of health"));
    }

    @Test
    public void aThrottledTestIsShownWithItsPerSecondSeries() {
        HardwareData d = rockchipUnit();
        CpuBench.Result t = new CpuBench.Result();
        t.threads = 6;
        t.singleThreadKops = 900;
        t.allCoreKopsPerSecond = new long[]{4000, 3900, 3500, 3000, 2600, 2300};
        t.tempsC = new int[]{50, 55, 61, 67, 72, 78};
        t.avgFreqKhz = new int[]{1800000, 1800000, 1600000, 1400000, 1200000, 1000000};
        t.verdict = ThrottleAnalysis.analyze(t.allCoreKopsPerSecond, t.tempsC, t.avgFreqKhz);
        d.cpuTest = t;
        String r = HardwareReport.format(d);
        assertTrue(r, r.contains("4000@50C/1800MHz"));
        assertTrue(r, r.contains("2300@78C/1000MHz"));
        assertTrue(r, r.contains("likely thermal throttling"));
        assertTrue(r, r.contains("6 threads"));
        assertFalse(r, r.contains("not run"));
    }

    @Test
    public void everyBlockedReadingGetsTheAdbCommandThatFillsIt() {
        HardwareData d = new HardwareData();               // nothing readable at all
        String r = HardwareReport.format(d);
        for (String command : new String[]{"adb shell cat /proc/loadavg /proc/stat",
                "scaling_cur_freq", "thermal_zone*/temp", "adb shell cat /proc/partitions",
                "/sys/block/mmcblk0/device/life_time", "verifiedboot", "adb shell cat /proc/cpuinfo",
                "adb shell cat /proc/meminfo", "dumpsys SurfaceFlinger"}) {
            assertTrue("missing '" + command + "' in:\n" + r, r.contains(command));
        }
        assertTrue(r, r.contains("to fill the gaps"));
    }

    @Test
    public void aFullyReadableUnitAsksForNothingMore() {
        HardwareData d = rockchipUnit();
        d.processes = ProcessTable.parse(HwFixtures.PS_TOYBOX);
        d.processNote = "";
        d.gpuRenderer = "Mali-T860";
        d.cpuFreqLines.add("cpu0: 600 MHz (max 1416 MHz, governor interactive)");
        d.maxFreqKhz = 1800000;
        assertEquals(java.util.Collections.<String>emptyList(), AdbFallbacks.commands(d));
        assertFalse(HardwareReport.format(d).contains("adb shell cat /proc/partitions"));
        assertFalse(HardwareReport.format(d).contains("adb shell cat /proc/meminfo"));
    }
}
