package com.ejjat.aiplay.core.hw;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Map;

public class HwParsersTest {

    // ---- cpuinfo

    @Test
    public void bigLittleCoresAreNamedFromTheirPartNumbers() {
        CpuInfo cpu = CpuInfo.parse(HwFixtures.CPUINFO_ARM64_BIGLITTLE);
        assertEquals(6, cpu.cores.size());
        assertEquals("4x Cortex-A53 + 2x Cortex-A72", cpu.coreSummary());
        assertTrue(cpu.isHeterogeneous());
        assertEquals("Rockchip RK3399 Evaluation Board", cpu.hardware);
    }

    @Test
    public void thirtyTwoBitKernelStillGivesCoreDesigns() {
        // "processor : 0" is followed by a model name here, and there is no 64-bit part line layout
        CpuInfo cpu = CpuInfo.parse(HwFixtures.CPUINFO_ARM32_A7);
        assertEquals(4, cpu.cores.size());
        assertEquals("4x Cortex-A7", cpu.coreSummary());
        assertFalse(cpu.isHeterogeneous());
        assertEquals("sun8i", cpu.hardware);
    }

    @Test
    public void unisocShapedChipIsSixEfficiencyPlusTwoPerformance() {
        assertEquals("6x Cortex-A55 + 2x Cortex-A75",
                CpuInfo.parse(HwFixtures.CPUINFO_UNISOC).coreSummary());
    }

    @Test
    public void x86FallsBackToTheModelName() {
        CpuInfo cpu = CpuInfo.parse(HwFixtures.CPUINFO_X86);
        assertEquals(2, cpu.cores.size());
        assertEquals("2x Intel(R) Xeon(R) CPU @ 2.20GHz", cpu.coreSummary());
    }

    @Test
    public void unknownPartsAreShownAsHexNotGuessed() {
        assertEquals("ARM part 0xd99", CpuInfo.coreName(0x41, 0xd99));
        assertEquals("Qualcomm part 0x801", CpuInfo.coreName(0x51, 0x801));
        assertEquals("implementer 0x99 part 0x1", CpuInfo.coreName(0x99, 0x1));
        assertEquals("Cortex-A53", CpuInfo.coreName(0x41, 0xd03));
        assertEquals("Cortex-A72", CpuInfo.coreName(0x41, 0xd08));
        assertEquals("Cortex-A55", CpuInfo.coreName(0x41, 0xd05));
        assertEquals("Cortex-A7", CpuInfo.coreName(0x41, 0xc07));
    }

    @Test
    public void garbageAndNullCpuinfoGiveAnEmptyResult() {
        assertEquals(0, CpuInfo.parse(null).cores.size());
        assertEquals(0, CpuInfo.parse("no colons here\n\n").cores.size());
        assertEquals("", CpuInfo.parse("").coreSummary());
    }

    // ---- meminfo

    @Test
    public void meminfoGivesTotalAvailableAndSwap() {
        MemInfo mem = MemInfo.parse(HwFixtures.MEMINFO_4GB);
        assertTrue(mem.valid());
        assertEquals(3786912L * 1024, mem.totalBytes());
        assertEquals(910220L * 1024, mem.availableBytes());
        assertEquals(76, mem.usedPercent());
        assertEquals(1048572L * 1024, mem.swapTotalBytes());
        assertEquals((1048572L - 262143L) * 1024, mem.swapUsedBytes());
        assertEquals(75, mem.swapUsedPercent());
        assertEquals(163840L, mem.get("CmaTotal"));
    }

    @Test
    public void oldKernelsWithoutMemAvailableUseFreePlusBuffersPlusCache() {
        MemInfo mem = MemInfo.parse(HwFixtures.MEMINFO_1GB);
        assertEquals((40000L + 20000L + 120000L) * 1024, mem.availableBytes());
        assertEquals(0, mem.swapTotalBytes());
        assertEquals(0, mem.swapUsedPercent());
    }

    @Test
    public void unreadableMeminfoIsInvalidNotZeroMemory() {
        assertFalse(MemInfo.parse(null).valid());
        assertFalse(MemInfo.parse("permission denied").valid());
        assertEquals(0, MemInfo.parse("").usedPercent());
    }

    // ---- sizes

    @Test
    public void marketedSizeIsTheNextCommonSizeUp() {
        assertEquals("4 GB", SizeFormat.marketedRam(3786912L * 1024));       // 3.61 GiB
        assertEquals("1 GB", SizeFormat.marketedRam(989000L * 1024));
        assertEquals("2 GB", SizeFormat.marketedRam(1900L * 1024 * 1024));
        assertEquals("1.5 GB", SizeFormat.marketedRam(1400L * 1024 * 1024));
        assertEquals("32 GB", SizeFormat.marketedStorage(30535680L * 1024));  // 29.1 GiB eMMC
        assertEquals("64 GB", SizeFormat.marketedStorage(59L * 1024 * 1024 * 1024));
        assertEquals("unknown", SizeFormat.marketedRam(0));
    }

    @Test
    public void sizesPrintInBinaryUnits() {
        assertEquals("3.61 GiB", SizeFormat.bytes(3786912L * 1024));
        assertEquals("512 B", SizeFormat.bytes(512));
        assertEquals("1.00 KiB", SizeFormat.bytes(1024));
        assertEquals("unknown", SizeFormat.bytes(-1));
        assertEquals(93, SizeFormat.percentUsed(100, 7));
        assertEquals(0, SizeFormat.percentUsed(0, 0));
    }

    // ---- partitions

    @Test
    public void rockchipLayoutHasARecoveryPartitionAndNoSlots() {
        PartitionTable t = PartitionTable.parse(HwFixtures.PARTITIONS_ROCKCHIP_NAMED);
        assertTrue(t.valid());
        assertTrue(t.has("recovery"));
        assertFalse(t.seamlessSlots());
        assertFalse(t.dynamicPartitions());
        assertEquals(1, t.disks().size());
        assertEquals("mmcblk0", t.disks().get(0).name);
        assertTrue(t.notable().size() >= 8);
    }

    @Test
    public void abSuperLayoutIsRecognisedAndLoopDevicesAreNotDisks() {
        PartitionTable t = PartitionTable.parse(HwFixtures.PARTITIONS_AB_SUPER);
        assertTrue(t.seamlessSlots());
        assertTrue(t.dynamicPartitions());
        assertFalse(t.has("recovery"));
        assertEquals(1, t.disks().size());
        assertEquals("sda", t.disks().get(0).name);
    }

    @Test
    public void numberedMmcPartitionsAreNotCountedAsDisks() {
        PartitionTable t = PartitionTable.parse(HwFixtures.PARTITIONS_ROCKCHIP);
        assertEquals(1, t.disks().size());       // mmcblk0 only: not p1.., not boot0/boot1
        assertFalse(PartitionTable.parse(null).valid());
        assertFalse(PartitionTable.parse("Permission denied").valid());
    }

    // ---- processes

    @Test
    public void toyboxPsIsParsedAndSortedByMemory() {
        ProcessTable t = ProcessTable.parse(HwFixtures.PS_TOYBOX);
        assertEquals(5, t.all.size());                      // the broken row is skipped
        assertEquals("com.google.android.gms", t.top(3).get(0).name);
        assertEquals("com.android.launcher3", t.top(3).get(1).name);
        assertEquals(2, t.top(2).size());
        assertEquals(4, t.foreignCount("com.ejjat.aiplay"));
    }

    @Test
    public void oldPsWithMoreColumnsFindsRssAndNameFromTheHeader() {
        ProcessTable t = ProcessTable.parse(HwFixtures.PS_OLD);
        assertEquals(3, t.all.size());
        assertEquals(150000L, t.top(1).get(0).rssKb);
        // the old format has a state letter before the name, so the NAME column holds "S"
        // for a naive reader; the header-based reader must not crash and must keep the rows
        assertTrue(t.valid());
    }

    @Test
    public void aProcessListThatOnlyShowsAiPlayHasNoForeignProcesses() {
        ProcessTable t = ProcessTable.parse("PID RSS NAME\n1500 64000 com.ejjat.aiplay\n");
        assertEquals(0, t.foreignCount("com.ejjat.aiplay"));
        assertFalse(ProcessTable.parse("ps: permission denied").valid());
    }

    // ---- properties and privacy

    @Test
    public void getpropLinesAreParsedAndGarbageIgnored() {
        Map<String, String> all = PropParser.parseGetprop(HwFixtures.GETPROP_HEADUNIT);
        assertEquals("rk3399", all.get("ro.board.platform"));
        assertEquals("", all.get("ro.build.ab_update"));
        assertFalse(all.containsKey("garbage line without brackets"));
    }

    @Test
    public void identifyingPropertiesNeverReachTheReport() {
        Map<String, String> picked = PropParser.select(PropParser.parseGetprop(HwFixtures.GETPROP_HEADUNIT));
        String text = picked.toString();
        for (String secret : HwFixtures.SECRETS) {
            assertFalse("leaked: " + secret + " in " + text, text.contains(secret));
        }
        assertFalse(picked.containsKey("ro.boot.serialno"));
        assertFalse(picked.containsKey("ro.serialno"));
        assertFalse(picked.containsKey("persist.sys.wifi.mac"));
        assertFalse(picked.containsKey("net.hostname"));
        assertFalse("timezone is not hardware", picked.containsKey("persist.sys.timezone"));
    }

    @Test
    public void aMacOrImeiInsideAnAllowedValueIsScrubbed() {
        Map<String, String> picked = PropParser.select(PropParser.parseGetprop(HwFixtures.GETPROP_HEADUNIT));
        assertEquals("mac <mac removed> x", picked.get("ro.build.host"));
        assertTrue(picked.get("ro.build.description").contains("<number removed>"));
    }

    @Test
    public void hardwareAndMcuPropertiesAreKept() {
        Map<String, String> picked = PropParser.select(PropParser.parseGetprop(HwFixtures.GETPROP_HEADUNIT));
        assertEquals("rk3399", picked.get("ro.board.platform"));
        assertEquals("rk30board", picked.get("ro.hardware"));
        assertEquals("V2.3.1", picked.get("ro.mcu.version"));
        assertEquals("115200", picked.get("ro.mcu.baud"));
        assertEquals("orange", picked.get("ro.boot.verifiedbootstate"));
        assertEquals("0", picked.get("ro.boot.flash.locked"));
        assertEquals("U-Boot 2017.09", picked.get("ro.bootloader"));
    }

    @Test
    public void firstReturnsTheFirstNonEmptyValue() {
        Map<String, String> p = PropParser.parseGetprop("[a]: []\n[b]: [two]\n");
        assertEquals("two", PropParser.first(p, "a", "b", "c"));
        assertEquals("", PropParser.first(p, "x"));
        assertNull(p.get("x"));
    }

    @Test
    public void aListOfOnlyTheAppAndItsShellIsNotSystemWide() {
        // what an app sees when it runs ps on a recent Android: itself, sh and ps
        ProcessTable own = ProcessTable.parse(
                "PID RSS NAME\n6233 3290 sh\n6234 3510 ps\n5000 64000 com.ejjat.aiplay\n");
        assertTrue(own.valid());
        assertFalse(own.looksSystemWide());
    }

    @Test
    public void aListWithSystemProcessesIsSystemWide() {
        assertTrue(ProcessTable.parse(HwFixtures.PS_TOYBOX).looksSystemWide());     // has system_server
        StringBuilder many = new StringBuilder("PID RSS NAME\n");
        for (int i = 0; i < 9; i++) {
            many.append(100 + i).append(" 1000 app.number").append(i).append('\n');
        }
        assertTrue("nine unrelated apps is a real list", ProcessTable.parse(many.toString()).looksSystemWide());
        assertFalse(ProcessTable.parse("PID RSS NAME\n1 1 a\n2 1 b\n").looksSystemWide());
    }

    @Test
    public void unknownIsAPlaceholderNotAValue() {
        Map<String, String> p = PropParser.parseGetprop("[ro.bootloader]: [unknown]\n[ro.boot.bootloader]: [U-Boot 1]\n");
        assertEquals("U-Boot 1", PropParser.firstReal(p, "ro.bootloader", "ro.boot.bootloader"));
        assertEquals("", PropParser.firstReal(p, "ro.bootloader"));
        assertEquals("unknown", PropParser.first(p, "ro.bootloader"));
    }
}
