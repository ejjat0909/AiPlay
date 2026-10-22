package com.ejjat.aiplay.core.hw;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A real head unit: the first report AiPlay produced on one (alps 8227L_demo, Android API 27, 32-bit
 * quad Cortex-A7, JCAC10003-OC2 firmware). The values below are copied from that report, so the test
 * checks what the report says about <em>that</em> unit. Two things are reconstructed, not copied:
 * the per-core lines of /proc/cpuinfo (only the "Processor :" header shape and the core part number are
 * known) and the partition list (only the disk sizes and the by-name mount path are known).
 *
 * <p>The first version of the report got this unit wrong in five ways, which these tests pin down:
 * five cores instead of four, no chip family, "Android 10" taken at face value on an API 27 build, a
 * 32 GB /data on a 14.7 GB chip, and "recovery: none listed" for a table that is only numbered.</p>
 */
public class RealUnitTest {

    private static final String GETPROP =
            "[ro.product.manufacturer]: [alps]\n[ro.product.model]: [8227L_demo]\n"
                    + "[ro.product.device]: [8227L_demo]\n[ro.board.platform]: [ac8227l]\n"
                    + "[ro.hardware]: [ac8227l]\n[ro.mediatek.platform]: [AC8227L]\n"
                    + "[ro.mediatek.version.release]: [JCAC10003-OC2-V1.0.08R5-220225_1356]\n"
                    + "[ro.mtk_emmc_support]: [1]\n[ro.build.version.release]: [10]\n"
                    + "[ro.build.version.sdk]: [27]\n"
                    + "[ro.vendor.build.fingerprint]: [alps/full_8227L_demo/8227L_demo:10/O11019/1645768602:userdebug/test-keys]\n"
                    + "[ro.boottime.chg_mcu_val]: [24421621230]\n[ro.boot.verifiedbootstate]: [green]\n"
                    + "[ro.secure]: [0]\n[ro.debuggable]: [1]\n[ro.treble.enabled]: [true]\n"
                    + "[sys.oem_unlock_allowed]: [0]\n[ro.bootloader]: [unknown]\n"
                    + "[ro.boot.selinux]: [permissive]\n";

    static final String CPUINFO_AC8227L =
            "Processor\t: ARMv7 Processor rev 3 (v7l)\n"
                    + "processor\t: 0\nBogoMIPS\t: 26.00\nFeatures\t: swp half thumb fastmult vfp edsp neon vfpv3 tls vfpv4 idiva idivt vfpd32 lpae\n"
                    + "CPU implementer\t: 0x41\nCPU architecture: 7\nCPU variant\t: 0x0\nCPU part\t: 0xc07\nCPU revision\t: 3\n\n"
                    + "processor\t: 1\nBogoMIPS\t: 26.00\nCPU implementer\t: 0x41\nCPU part\t: 0xc07\n\n"
                    + "processor\t: 2\nBogoMIPS\t: 26.00\nCPU implementer\t: 0x41\nCPU part\t: 0xc07\n\n"
                    + "processor\t: 3\nBogoMIPS\t: 26.00\nCPU implementer\t: 0x41\nCPU part\t: 0xc07\n\n"
                    + "Hardware\t: AC8227L\nRevision\t: 0000\n";

    static final String PARTITIONS_NUMBERED =
            "major minor  #blocks  name\n\n 179 0 15393792 mmcblk0\n 179 1 3072 mmcblk0p1\n"
                    + " 179 2 3072 mmcblk0p2\n 179 3 512 mmcblk0p3\n 179 4 32768 mmcblk0p4\n"
                    + " 179 5 32768 mmcblk0p5\n 179 6 32768 mmcblk0p6\n 179 7 524288 mmcblk0p7\n"
                    + " 179 8 524288 mmcblk0p8\n 179 9 10485760 mmcblk0p9\n 179 32 4096 mmcblk0boot0\n"
                    + " 179 64 4096 mmcblk0boot1\n 8 0 15360000 sda\n 8 1 15359000 sda1\n"
                    + " 8 16 30286848 sdb\n 8 17 30285824 sdb1\n";

    private static HardwareData unit() {
        HardwareData d = new HardwareData();
        d.manufacturer = "alps";
        d.brand = "alps";
        d.model = "8227L_demo";
        d.device = "8227L_demo";
        d.product = "full_8227L_demo";
        d.board = "unknown";
        d.hardware = "ac8227l";
        d.abis = new String[]{"armeabi-v7a", "armeabi"};
        d.sdk = 27;
        d.androidRelease = "10";
        d.fingerprint = "alps/full_evb3561sv_w_65_m0/evb3561sv_w_65_m0:6.0/MRA58K/1645768653:userdebug/test-keys";
        d.cpu = CpuInfo.parse(CPUINFO_AC8227L);
        d.maxFreqKhz = 1118000;
        d.gpuRenderer = "Mali-450 MP";
        d.gpuVendor = "ARM";
        d.mem = MemInfo.parse("MemTotal: 4193000 kB\nMemFree: 21842 kB\nMemAvailable: 302624 kB\n"
                + "SwapTotal: 1048576 kB\nSwapFree: 602757 kB\n");
        d.amTotalBytes = 4193000L * 1024;
        d.swapLines.add("/dev/block/zram0 partition 1048572 445819 -1");
        d.volumes.add(new HardwareData.Volume("/data (internal)", 32L << 30, 5068000000L));
        d.blocks.add(new HardwareData.Block("mmcblk0", 15393792L * 1024, "eMMC", "chip name '400073'"));
        d.blocks.add(new HardwareData.Block("sda", 15360000L * 1024, "removable disk (USB or SD reader)",
                "Kingmax USB2.0 FlashDisk"));
        d.dataFilesystem = "/dev/block/platform/soc/11230000.mmc/by-name/userdata ext4 rw";
        d.flashHealth = FlashHealth.describe("0x08 0x00", "0x01");
        d.flashWearLowPercent = FlashHealth.usedPercentLow("0x08 0x00");
        d.loadavg = "13.45 19.67 18.28 2/1415 7710";
        d.thermalLines.add("thermal_zone0 (mtktscpu): 49 C");
        d.thermalLines.add("thermal_zone2 (mtktspmic): 64 C");
        d.maxTempC = 64;
        Map<String, String> all = PropParser.parseGetprop(GETPROP);
        d.propsSeen = all.size();
        d.props.putAll(PropParser.select(all));
        d.partitions = PartitionTable.parse(PARTITIONS_NUMBERED);
        d.processNote = "Android only lets AiPlay see its own processes (no root)";
        return d;
    }

    // ---- bug 1: the "Processor :" header made a fifth core

    @Test
    public void theProcessorHeaderLineIsNotACore() {
        CpuInfo cpu = CpuInfo.parse(CPUINFO_AC8227L);
        assertEquals(4, cpu.cores.size());
        assertEquals("4x Cortex-A7", cpu.coreSummary());
        assertFalse(cpu.isHeterogeneous());
        assertEquals("AC8227L", cpu.hardware);
        assertEquals("ARMv7 Processor rev 3 (v7l)", cpu.cores.get(0).modelName);
    }

    @Test
    public void aKernelWithOnlyAHeaderAndNoCoreLinesHasNoCores() {
        assertEquals(0, CpuInfo.parse("Processor\t: ARMv7 Processor rev 3 (v7l)\nHardware\t: X\n").cores.size());
    }

    @Test
    public void fourCortexA7CoresAreCalledOldGeneration() {
        String klass = ChipGuess.performanceClass(CpuInfo.parse(CPUINFO_AC8227L), 1118000);
        assertTrue(klass, klass.startsWith("4 old-generation cores"));
        assertTrue(klass, klass.contains("1.12 GHz"));
    }

    // ---- bug 2: no chip family

    @Test
    public void theAutochipsPartIsRecognisedAndTheMediatekFirmwareIsNoted() {
        Map<String, String> ids = new LinkedHashMap<String, String>();
        ids.put("Build.BOARD", "unknown");
        ids.put("Build.HARDWARE", "ac8227l");
        ids.put("cpuinfo Hardware", "AC8227L");
        ids.put("ro.mediatek.platform", "AC8227L");
        ChipGuess.Result r = ChipGuess.guess(ids);
        assertEquals("Autochips (AC82xx)", r.family);
        assertEquals(3, r.evidence.size());
        assertEquals(1, r.generic.size());

        Map<String, String> props = PropParser.select(PropParser.parseGetprop(GETPROP));
        String note = ChipGuess.bspNote(props, Collections.singletonList("thermal_zone0 (mtktscpu): 49 C"));
        assertTrue(note, note.contains("MediaTek's BSP"));
        assertTrue(note, note.contains("whatever name it carries"));
        assertEquals("", ChipGuess.bspNote(Collections.<String, String>emptyMap(), Collections.<String>emptyList()));
    }

    // ---- bug 3: "Android 10" on an API 27 build

    @Test
    public void apiLevelsMapToTheirRealAndroidRelease() {
        assertEquals("8.1", AndroidVersion.releaseFor(27));
        assertEquals("API 27 = Android 8.1", AndroidVersion.label(27));
        assertEquals("API 34 = Android 14", AndroidVersion.label(34));
        assertEquals("API 99", AndroidVersion.label(99));
        assertEquals("6.0", AndroidVersion.releaseOfFingerprint(
                "alps/full_evb3561sv_w_65_m0/evb3561sv_w_65_m0:6.0/MRA58K/1645768653:userdebug/test-keys"));
        assertEquals("", AndroidVersion.releaseOfFingerprint("garbage"));
    }

    @Test
    public void everyClaimThatDisagreesWithTheApiLevelIsListed() {
        List<String> claims = AndroidVersion.mismatches(27, "10",
                "alps/x/y:6.0/MRA58K/1:userdebug/test-keys",
                "alps/x/y:10/O11019/1:userdebug/test-keys");
        assertEquals(claims.toString(), 3, claims.size());
        assertTrue(claims.get(0).contains("Android 10") && claims.get(0).contains("Android 8.1"));
        assertTrue(claims.get(1).contains("Android 6.0"));
        assertTrue(claims.get(2).contains("Android 10"));
    }

    @Test
    public void consistentLabelsRaiseNothing() {
        assertTrue(AndroidVersion.mismatches(27, "8.1.0", "g/p/d:8.1.0/OPM/1:user/release-keys", "").isEmpty());
        assertTrue(AndroidVersion.mismatches(34, "14", "g/p/d:14/UE1A/1:userdebug/dev-keys", "").isEmpty());
        assertTrue("an API level the table does not know says nothing",
                AndroidVersion.mismatches(99, "50", "", "").isEmpty());
        assertTrue(AndroidVersion.mismatches(0, "8.1", "", "").isEmpty());
    }

    // ---- bug 4: sizes that cannot be real

    @Test
    public void aRoundMemTotalLooksSetNotMeasured() {
        assertTrue(StorageCheck.ramLooksSetNotMeasured(4193000L * 1024));       // 99.9% of 4 GiB
        assertTrue(StorageCheck.ramLooksSetNotMeasured(4L << 30));              // exactly 4 GiB
        assertFalse("a normal 4 GB phone reports ~3.6 GiB", StorageCheck.ramLooksSetNotMeasured(3786912L * 1024));
        assertFalse(StorageCheck.ramLooksSetNotMeasured(2L * 1024 * 1024 * 1024 - 80L * 1024 * 1024));
        assertFalse(StorageCheck.ramLooksSetNotMeasured(0));
        assertEquals(100, SizeFormat.roundRamPercent(4L << 30));
        assertEquals(0, SizeFormat.roundRamPercent(1990L * 1024 * 1024 / 2));
    }

    @Test
    public void aFilesystemBiggerThanItsChipIsCalledOut() {
        List<String> warnings = StorageCheck.analyze(unit());
        assertEquals(warnings.toString(), 1, warnings.size());
        assertTrue(warnings.get(0), warnings.get(0).contains("32.00 GiB")
                && warnings.get(0).contains("14.68 GiB") && warnings.get(0).contains("not real"));
    }

    @Test
    public void aVolumeThatFitsItsChipIsNotFlagged() {
        HardwareData d = unit();
        d.volumes.clear();
        d.volumes.add(new HardwareData.Volume("/data (internal)", 10L << 30, 4L << 30));
        assertTrue(StorageCheck.analyze(d).isEmpty());
        d.volumes.clear();
        d.volumes.add(new HardwareData.Volume("/data (internal)", 32L << 30, 4L << 30));
        d.dataFilesystem = "/dev/block/sda1 ext4 rw";                        // not on the eMMC: no claim
        assertTrue(StorageCheck.analyze(d).isEmpty());
    }

    // ---- bug 5: partitions known only by number

    @Test
    public void aNumberedTableIsDetectedAndNothingIsInventedAboutRecovery() {
        PartitionTable t = PartitionTable.parse(PARTITIONS_NUMBERED);
        assertTrue(t.namesAreNumbered());
        assertFalse(t.has("recovery"));
        String text = String.join("\n", FlashHints.analyze(unit()));
        assertTrue(text, text.contains("recovery partition: unknown"));
        assertFalse(text, text.contains("none listed"));
        assertTrue(text, text.contains("by-name"));
    }

    @Test
    public void byNameLabelsTurnANumberedTableIntoANamedOne() {
        Map<String, String> names = new LinkedHashMap<String, String>();
        names.put("mmcblk0p5", "boot");
        names.put("mmcblk0p6", "recovery");
        names.put("mmcblk0p9", "userdata");
        PartitionTable named = PartitionTable.parse(PARTITIONS_NUMBERED).withNames(names);
        assertFalse(named.namesAreNumbered());
        assertTrue(named.has("recovery"));
        assertEquals("userdata", named.notable().get(named.notable().size() - 1).name);
        HardwareData d = unit();
        d.partitions = named;
        assertTrue(String.join("\n", FlashHints.analyze(d)).contains("recovery partition: present"));
    }

    @Test
    public void theLargestInternalPartitionsExcludeUsbSticksAndBootSubdevices() {
        List<PartitionTable.Entry> top = PartitionTable.parse(PARTITIONS_NUMBERED).largestInternal(3);
        assertEquals("mmcblk0p9", top.get(0).name);
        assertEquals(10485760L, top.get(0).kb);
        for (PartitionTable.Entry e : top) {
            assertFalse(e.name, e.name.startsWith("sd") || e.name.contains("boot"));
        }
    }

    @Test
    public void anMcuBootEntryIsNotPassedOffAsAVersion() {
        String text = String.join("\n", FlashHints.analyze(unit()));
        assertTrue(text, text.contains("init services with an MCU/vendor name that ran at boot: chg_mcu_val"));
        assertFalse(text, text.contains("vendor/MCU properties: ro.boottime"));
    }

    // ---- the whole report for this unit

    @Test
    public void theReportForThisUnitNowSaysTheRightThings() {
        String r = HardwareReport.format(unit());
        for (String must : new String[]{
                "cpu cores     : 4: 4x Cortex-A7",
                "chip family   : Autochips (AC82xx)",
                "MediaTek's BSP",
                "API 27 = Android 8.1  [the firmware's label says 10]",
                "CONFLICT: the version string says Android 10",
                "CONFLICT: the build fingerprint says Android 6.0",
                "WARNING: MemTotal is",
                "WARNING: /data (internal) reports 32.00 GiB",
                "swap device: /dev/block/zram0",
                "recovery partition: unknown",
                "largest internal partitions",
                "mmcblk0p9",
                "70-80% of rated life used"}) {
            assertTrue("missing '" + must + "' in:\n" + r, r.contains(must));
        }
        assertFalse(r, r.contains("1x ARMv7"));
        assertFalse(r, r.contains("5 cores"));
        assertFalse(r, r.contains("none listed"));
        assertTrue(r, r.contains("adb shell cat /proc/iomem"));
    }

    @Test
    public void theLagSignalsForThisUnitCoverMemoryStorageFlashAndTheChip() {
        String s = String.join("\n", LagSignals.analyze(unit()));
        assertTrue(s, s.contains("old-generation"));
        assertTrue(s, s.contains("MemTotal is 4.00 GiB"));
        assertTrue(s, s.contains("behaves like it has less RAM than it claims"));
        assertTrue(s, s.contains("reports 32.00 GiB"));
        assertTrue(s, s.contains("at least 70% of its rated life"));
        assertTrue(s, s.contains("32-bit only"));
        assertTrue(s, s.contains("load average 13.5 on 4 core"));
        assertTrue(s, s.contains("top CPU clock is only 1.12 GHz"));
        assertFalse("swap here is zram, not the worn flash", s.contains("swap lives on that flash"));
    }

    @Test
    public void swapOnTheWornFlashIsCalledOut() {
        HardwareData d = unit();
        d.swapLines.clear();
        d.swapLines.add("/dev/block/mmcblk0p8 partition 524288 300000 -1");
        d.mem = MemInfo.parse("MemTotal: 1000000 kB\nMemAvailable: 90000 kB\nSwapTotal: 524288 kB\nSwapFree: 224288 kB\n");
        String s = String.join("\n", LagSignals.analyze(d));
        assertTrue(s, s.contains("swap lives on that flash"));
    }

    @Test
    public void wearEstimatesDecodeToTheirLowerBound() {
        assertEquals(70, FlashHealth.usedPercentLow("0x08 0x00"));
        assertEquals(0, FlashHealth.usedPercentLow("0x01 0x01"));
        assertEquals(90, FlashHealth.usedPercentLow("0x0A 0x01"));
        assertEquals(100, FlashHealth.usedPercentLow("0x0B 0x0B"));
        assertEquals(-1, FlashHealth.usedPercentLow("0x00 0x00"));
        assertEquals(-1, FlashHealth.usedPercentLow(null));
    }

    @Test
    public void theHomeScreenSummaryNamesTheRealAndroidRelease() {
        com.ejjat.aiplay.core.DeviceProfile profile = new com.ejjat.aiplay.core.DeviceProfile();
        profile.manufacturer = "alps";
        profile.model = "8227L_demo";
        profile.device = "8227L_demo";
        profile.sdkInt = 27;
        assertTrue(profile.describe(), profile.describe().contains("API 27 = Android 8.1"));
        assertFalse(profile.describe(), profile.describe().contains("Android 27"));
    }

    @Test
    public void aTableWithNoRecognisableNamesStillListsItsLargestPartitions() {
        // an API 27 emulator: virtio disks vda/vde1, not mmcblk, so neither "named" nor "numbered"
        HardwareData d = new HardwareData();
        d.partitions = PartitionTable.parse("major minor  #blocks  name\n\n 253 0 2623488 vda\n"
                + " 253 1 2621440 vda1\n 253 16 67584 vdb\n 253 64 100352 vde\n 253 65 98304 vde1\n"
                + " 252 0 6291456 dm-0\n");
        assertFalse(d.partitions.namesAreNumbered());
        String r = HardwareReport.format(d);
        assertTrue(r, r.contains("no partition has a recognised name"));
        assertTrue(r, r.contains("vda1"));
        assertFalse("a device-mapper node is not a partition", r.contains("dm-0 "));
        d.partitions = PartitionTable.parse("major minor  #blocks  name\n\n 253 0 2623488 vda\n");
        assertTrue(HardwareReport.format(d).contains("lists only whole disks"));
    }
}
