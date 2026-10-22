package com.ejjat.aiplay.core.hw;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class HwAnalysisTest {

    private static Map<String, String> ids(String... pairs) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    // ---- chip family

    @Test
    public void rockchipIsRecognisedFromBoardPlatformAndHardware() {
        ChipGuess.Result r = ChipGuess.guess(ids("ro.board.platform", "rk3399", "ro.hardware", "rk30board",
                "Build.BOARD", "rk30sdk"));
        assertEquals("Rockchip", r.family);
        assertEquals(3, r.evidence.size());
        assertTrue(r.conflicts.isEmpty());
    }

    @Test
    public void commonHeadUnitFamiliesAreRecognised() {
        assertEquals("MediaTek", ChipGuess.guess(ids("ro.board.platform", "mt8163")).family);
        assertEquals("Unisoc / Spreadtrum", ChipGuess.guess(ids("ro.board.platform", "ums512")).family);
        assertEquals("Unisoc / Spreadtrum", ChipGuess.guess(ids("ro.board.platform", "sc9863a")).family);
        assertEquals("Unisoc / Spreadtrum", ChipGuess.guess(ids("cpuinfo Hardware", "UIS7862")).family);
        assertEquals("Allwinner", ChipGuess.guess(ids("cpuinfo Hardware", "sun8i")).family);
        assertEquals("Allwinner", ChipGuess.guess(ids("ro.board.platform", "sun50iw10p1")).family);
        assertEquals("Qualcomm", ChipGuess.guess(ids("ro.board.platform", "msm8953")).family);
        assertEquals("Qualcomm", ChipGuess.guess(ids("ro.board.platform", "sdm660")).family);
        assertEquals("Amlogic", ChipGuess.guess(ids("ro.board.platform", "meson8")).family);
        assertEquals("Telechips", ChipGuess.guess(ids("ro.board.platform", "tcc8971")).family);
        assertEquals("Rockchip", ChipGuess.guess(ids("ro.product.board", "px6")).family);
    }

    @Test
    public void theEmulatorIsNamedAsNotRealHardware() {
        assertEquals("Android emulator (not real hardware)",
                ChipGuess.guess(ids("ro.hardware", "ranchu", "Build.HARDWARE", "ranchu")).family);
        assertEquals("Android emulator (not real hardware)",
                ChipGuess.guess(ids("Build.DEVICE", "emu64xa")).family);
    }

    @Test
    public void genericPlaceholdersAreReportedNotMatched() {
        ChipGuess.Result r = ChipGuess.guess(ids("Build.BOARD", "unknown", "ro.hardware", "generic",
                "Build.DEVICE", "full_dev", "Build.PRODUCT", "aosp_x86"));
        assertEquals("", r.family);
        assertEquals(4, r.generic.size());
    }

    @Test
    public void aUnitThatContradictsItselfIsFlagged() {
        ChipGuess.Result r = ChipGuess.guess(ids("ro.board.platform", "rk3399", "ro.hardware", "rk30board",
                "cpuinfo Hardware", "MediaTek MT8163"));
        assertEquals("Rockchip", r.family);               // two votes beat one
        assertEquals(1, r.conflicts.size());
        assertEquals("MediaTek", r.conflicts.get(0));
    }

    @Test
    public void unknownNamesAreNotInvented() {
        ChipGuess.Result r = ChipGuess.guess(ids("ro.board.platform", "zzz9000", "ro.hardware", "foo"));
        assertEquals("", r.family);
        assertTrue(r.evidence.isEmpty());
        assertEquals("", ChipGuess.guess(ids("a", "", "b", null)).family);
    }

    // ---- core class

    @Test
    public void coreDesignsGiveTheClassWhateverTheBoardIsCalled() {
        assertTrue(ChipGuess.performanceClass(CpuInfo.parse(HwFixtures.CPUINFO_ARM32_A7), 1200000)
                .contains("old-generation"));
        assertTrue(ChipGuess.performanceClass(CpuInfo.parse(HwFixtures.CPUINFO_ARM64_BIGLITTLE), 1800000)
                .contains("high-performance"));
        assertTrue(ChipGuess.performanceClass(CpuInfo.parse(HwFixtures.CPUINFO_UNISOC), 1600000)
                .contains("high-performance"));
        assertTrue(ChipGuess.performanceClass(CpuInfo.parse("processor : 0\nCPU implementer : 0x41\n"
                + "CPU part : 0xd03\n\nprocessor : 1\nCPU implementer : 0x41\nCPU part : 0xd03\n"), 1200000)
                .contains("efficiency-class"));
        assertEquals("", ChipGuess.performanceClass(new CpuInfo(), 0));
    }

    // ---- throttling

    private static long[] flat(long v, int n) {
        long[] a = new long[n];
        for (int i = 0; i < n; i++) {
            a[i] = v;
        }
        return a;
    }

    @Test
    public void steadyWorkIsNotThrottling() {
        ThrottleAnalysis.Verdict v = ThrottleAnalysis.analyze(flat(1000, 6), null, null);
        assertEquals(ThrottleAnalysis.Kind.STEADY, v.kind);
        assertFalse(v.throttled());
    }

    @Test
    public void aDropWithRisingTemperatureIsLikelyThermal() {
        long[] ops = {1000, 990, 900, 800, 700, 650};
        int[] temps = {52, 58, 63, 68, 72, 76};
        ThrottleAnalysis.Verdict v = ThrottleAnalysis.analyze(ops, temps, null);
        assertEquals(ThrottleAnalysis.Kind.DROPPED_THERMAL, v.kind);
        assertTrue(v.throttled());
        assertTrue(v.text, v.text.contains("temperature rose 24 C"));
    }

    @Test
    public void aDropWithAFallingClockIsLikelyThermal() {
        long[] ops = {1000, 1000, 900, 800, 700, 600};
        int[] freqs = {1800000, 1800000, 1700000, 1500000, 1300000, 1200000};
        ThrottleAnalysis.Verdict v = ThrottleAnalysis.analyze(ops, null, freqs);
        assertEquals(ThrottleAnalysis.Kind.DROPPED_THERMAL, v.kind);
        assertTrue(v.text, v.text.contains("clock fell 33%"));
    }

    @Test
    public void aDropWithNoThermalEvidenceDoesNotClaimThermal() {
        long[] ops = {1000, 1000, 900, 800, 700, 600};
        ThrottleAnalysis.Verdict v = ThrottleAnalysis.analyze(ops, new int[]{-1, -1, -1, -1, -1, -1},
                new int[]{-1, -1, -1, -1, -1, -1});
        assertEquals(ThrottleAnalysis.Kind.DROPPED_NO_EVIDENCE, v.kind);
        assertTrue(v.text.contains("another app"));
    }

    @Test
    public void speedingUpIsWarmUpNotAProblemAndShortRunsAreNotJudged() {
        assertEquals(ThrottleAnalysis.Kind.RAMPED_UP,
                ThrottleAnalysis.analyze(new long[]{500, 600, 900, 1000, 1000, 1000}, null, null).kind);
        assertEquals(ThrottleAnalysis.Kind.TOO_SHORT, ThrottleAnalysis.analyze(flat(1, 3), null, null).kind);
        assertEquals(ThrottleAnalysis.Kind.TOO_SHORT, ThrottleAnalysis.analyze(null, null, null).kind);
        assertEquals(ThrottleAnalysis.Kind.TOO_SHORT, ThrottleAnalysis.analyze(flat(0, 6), null, null).kind);
    }

    @Test
    public void aSmallDipBelowTheThresholdIsSteady() {
        assertEquals(ThrottleAnalysis.Kind.STEADY,
                ThrottleAnalysis.analyze(new long[]{1000, 1000, 950, 920, 900, 880}, null, null).kind);
    }

    @Test
    public void theBenchmarkRunsLoadedThreadsAndReportsEverySecond() throws Exception {
        final AtomicInteger warm = new AtomicInteger();
        final AtomicInteger single = new AtomicInteger();
        final AtomicInteger all = new AtomicInteger();
        CpuBench.Result r = CpuBench.run(2, 1, 1, 2, new CpuBench.Sampler() {
            @Override
            public void onSecond(int phase, int second, long ops) {
                (phase == CpuBench.PHASE_WARMUP ? warm
                        : phase == CpuBench.PHASE_SINGLE ? single : all).incrementAndGet();
                assertTrue("ops must be counted", ops > 0);
            }
        });
        assertEquals("the warm-up is reported to the sampler (for progress) but not measured", 1,
                warm.get());
        assertEquals(1, single.get());
        assertEquals(2, all.get());
        assertEquals(2, r.allCoreKopsPerSecond.length);
        assertEquals(2, r.threads);
        assertTrue(r.singleThreadKops > 0);
        assertTrue(r.allCoreKopsAverage() > 0);
    }

    // ---- flash health

    @Test
    public void emmcWearIsDecodedInTenPercentSteps() {
        assertTrue(FlashHealth.describe("0x01 0x02", "0x01").contains("10-20% of rated life"));
        assertTrue(FlashHealth.describe("0x0A 0x01", "0x01").contains("90-100%"));
        assertTrue(FlashHealth.describe("0x0B 0x0B", "0x03").contains("BEYOND"));
        assertTrue(FlashHealth.describe("0x00 0x00", "").contains("no estimate"));
        assertTrue(FlashHealth.describe("", "0x02").contains("WARNING"));
        assertEquals("", FlashHealth.describe("", ""));
        assertTrue(FlashHealth.worn("0x0A 0x01", "0x01"));
        assertTrue(FlashHealth.worn("0x01 0x01", "0x03"));
        assertFalse(FlashHealth.worn("0x02 0x02", "0x01"));
        assertFalse("an undefined controller is not reported as worn", FlashHealth.worn("0x00 0x00", "0x00"));
    }

    // ---- flashability

    private static HardwareData unit(String getprop, String partitions) {
        HardwareData d = new HardwareData();
        Map<String, String> all = PropParser.parseGetprop(getprop);
        d.propsSeen = all.size();
        d.props.putAll(PropParser.select(all));
        d.partitions = PartitionTable.parse(partitions);
        return d;
    }

    @Test
    public void anUnlockedRockchipStyleUnitIsReadAsSuch() {
        HardwareData d = unit(HwFixtures.GETPROP_HEADUNIT, HwFixtures.PARTITIONS_ROCKCHIP_NAMED);
        String text = String.join("\n", FlashHints.analyze(d));
        assertTrue(text, text.contains("verified boot state: orange (bootloader unlocked)"));
        assertTrue(text, text.contains("bootloader flash lock: 0 (unlocked)"));
        assertTrue(text, text.contains("A/B slots: no"));
        assertTrue(text, text.contains("dynamic partitions (super): no"));
        assertTrue(text, text.contains("recovery partition: present"));
        assertTrue(text, text.contains("ro.mcu.version=V2.3.1"));
        assertTrue(text, text.contains("NOT determinable"));
    }

    @Test
    public void aUnitWithNoVerifiedBootPropertiesSaysTheStateIsUnknown() {
        HardwareData d = unit("[ro.product.model]: [x]\n", "");
        String text = String.join("\n", FlashHints.analyze(d));
        assertTrue(text, text.contains("bootloader state: not reported"));
        assertTrue(text, text.contains("recovery partition: unknown"));
        assertTrue(text, text.contains("vendor/MCU properties: none found"));
    }

    @Test
    public void abAndDynamicPartitionsAreDetectedFromTheLayout() {
        HardwareData d = unit("[ro.boot.slot_suffix]: [_a]\n[ro.boot.verifiedbootstate]: [green]\n",
                HwFixtures.PARTITIONS_AB_SUPER);
        String text = String.join("\n", FlashHints.analyze(d));
        assertTrue(text, text.contains("A/B slots: yes (active slot _a)"));
        assertTrue(text, text.contains("dynamic partitions (super): yes"));
        assertTrue(text, text.contains("recovery inside boot"));
        assertTrue(text, text.contains("green (locked, stock)"));
    }

    @Test
    public void vendorAndUpdatePackagesAreClassifiedConservatively() {
        assertTrue(FlashHints.isVendorPackage("com.syu.music"));
        assertTrue(FlashHints.isVendorPackage("com.microntek.radio"));
        assertFalse(FlashHints.isVendorPackage("com.google.android.gms"));
        assertTrue(FlashHints.isUpdatePackage("com.rockchip.updater"));
        assertTrue(FlashHints.isUpdatePackage("com.vendor.fota.client"));
        assertFalse("Google's own updaters are not head-unit firmware",
                FlashHints.isUpdatePackage("com.google.android.gms.update"));
        assertFalse(FlashHints.isUpdatePackage("com.android.vending"));
        assertFalse(FlashHints.isUpdatePackage("com.example.notes"));
    }

    // ---- lag signals

    private static String signals(HardwareData d) {
        return String.join("\n", LagSignals.analyze(d));
    }

    @Test
    public void smallRamFullStorageAndSwapPressureAreAllRaised() {
        HardwareData d = new HardwareData();
        d.mem = MemInfo.parse("MemTotal: 989000 kB\nMemAvailable: 90000 kB\nSwapTotal: 0 kB\nSwapFree: 0 kB\n");
        d.volumes.add(new HardwareData.Volume("/data", 20L << 30, 1L << 30));
        d.flashHealth = "wear: BEYOND rated life (0x0b 0x0b)";
        String s = signals(d);
        assertTrue(s, s.contains("RAM is very small"));
        assertTrue(s, s.contains("/data is 95% full"));
        assertTrue(s, s.contains("flash chip reports wear"));
        assertTrue(s, s.contains("RAM is nearly full"));
    }

    @Test
    public void rk30IsStillRockchip() {
        assertEquals("Rockchip", ChipGuess.guess(ids("Build.BOARD", "rk30sdk")).family);
        assertEquals("Rockchip", ChipGuess.guess(ids("Build.BOARD", "rk3188")).family);
    }

    @Test
    public void swapPressureAndLowRamFlagAreRaised() {
        HardwareData d = new HardwareData();
        d.mem = MemInfo.parse(HwFixtures.MEMINFO_4GB);
        d.lowRamDevice = true;
        String s = signals(d);
        assertTrue(s, s.contains("swap is 75% used"));
        assertTrue(s, s.contains("low-RAM device"));
        assertFalse("76% in use is below the 85% alarm", s.contains("nearly full"));
    }

    @Test
    public void weakCpuSignalsComeFromCoresClockAndAbi() {
        HardwareData d = new HardwareData();
        d.cpu = CpuInfo.parse(HwFixtures.CPUINFO_ARM32_A7);
        d.maxFreqKhz = 1008000;
        d.abis = new String[]{"armeabi-v7a", "armeabi"};
        String s = signals(d);
        assertTrue(s, s.contains("old-generation"));
        assertTrue(s, s.contains("top CPU clock is only 1.01 GHz"));
        assertTrue(s, s.contains("32-bit only (armeabi-v7a, armeabi)"));
    }

    @Test
    public void heatAndLoadAreRaisedFromWhatWasRead() {
        HardwareData d = new HardwareData();
        d.cpu = CpuInfo.parse(HwFixtures.CPUINFO_ARM32_A7);       // 4 cores
        d.maxTempC = 86;
        d.loadavg = "9.50 7.00 4.00 3/400 1234";
        String s = signals(d);
        assertTrue(s, s.contains("reads 86 C"));
        assertTrue(s, s.contains("load average 9.5 on 4 core"));
    }

    @Test
    public void aHealthyUnitIsNeverDeclaredHealthy() {
        HardwareData d = new HardwareData();
        d.cpu = CpuInfo.parse(HwFixtures.CPUINFO_UNISOC);
        d.maxFreqKhz = 1800000;
        d.abis = new String[]{"arm64-v8a", "armeabi-v7a"};
        d.mem = MemInfo.parse("MemTotal: 8000000 kB\nMemAvailable: 6000000 kB\nSwapTotal: 0 kB\nSwapFree: 0 kB\n");
        d.volumes.add(new HardwareData.Volume("/data", 100L << 30, 70L << 30));
        String s = signals(d);
        assertTrue(s, s.contains("not a clean bill of health"));
    }

    @Test
    public void loadavgParsingToleratesGarbage() {
        assertEquals(1.25, LagSignals.loadFirst("1.25 0.90 0.70 1/512 9999"), 0.001);
        assertEquals(-1, LagSignals.loadFirst(""), 0);
        assertEquals(-1, LagSignals.loadFirst("denied"), 0);
        assertEquals(-1, LagSignals.loadFirst(null), 0);
    }

    @Test
    public void aThrottledCpuTestIsReportedAsASignal() {
        HardwareData d = new HardwareData();
        CpuBench.Result t = new CpuBench.Result();
        t.threads = 4;
        t.singleThreadKops = 1000;
        t.allCoreKopsPerSecond = new long[]{3000, 3000, 2500, 2000, 1500, 1200};
        t.tempsC = new int[]{50, 56, 62, 68, 74, 80};
        t.verdict = ThrottleAnalysis.analyze(t.allCoreKopsPerSecond, t.tempsC, null);
        d.cpuTest = t;
        String s = signals(d);
        assertNotNull(t.verdict);
        assertTrue(s, s.contains("likely thermal throttling"));
    }

    @Test
    public void aPlaceholderBootloaderIsNotShownAsAVersion() {
        HardwareData d = unit("[ro.bootloader]: [unknown]\n[ro.build.ab_update]: [false]\n", "");
        String text = String.join("\n", FlashHints.analyze(d));
        assertFalse(text, text.contains("bootloader version"));
        assertTrue(text, text.contains("not reported to apps"));
        assertTrue(text, text.contains("adb shell getprop"));
    }

    @Test
    public void hiddenBuildFlagsAreSaidToBeHiddenNotBlank() {
        HardwareData d = unit("[ro.adb.secure]: [0]\n", "");
        String text = String.join("\n", FlashHints.analyze(d));
        assertTrue(text, text.contains("ro.secure=(hidden)"));
        assertTrue(text, text.contains("ro.adb.secure=0"));
    }

    @Test
    public void aPlaceholderClockIsNeverReportedAsASlowChip() {
        HardwareData d = new HardwareData();
        d.maxFreqKhz = 1;               // what an emulator reports
        assertFalse(signals(d).contains("top CPU clock"));
        d.maxFreqKhz = 0;
        assertFalse(signals(d).contains("top CPU clock"));
        d.maxFreqKhz = 1008000;
        assertTrue(signals(d).contains("top CPU clock is only 1.01 GHz"));
    }
}
