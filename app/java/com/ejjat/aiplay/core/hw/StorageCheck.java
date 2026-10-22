package com.ejjat.aiplay.core.hw;

import java.util.ArrayList;
import java.util.List;

/**
 * Cross-checks the storage numbers against each other.
 *
 * <p>Android's "32 GB" comes from {@code StatFs}, a number the firmware can influence. The flash chip's
 * raw size comes from the block layer and is much harder to fake. A filesystem cannot be larger than
 * the chip it lives on, so when the two disagree the bigger one is not real. Likewise MemTotal: a real
 * kernel always reports less than the installed RAM.</p>
 */
public final class StorageCheck {

    private StorageCheck() {
    }

    /** Warnings about the internal volume; empty when nothing contradicts. */
    public static List<String> analyze(HardwareData d) {
        List<String> out = new ArrayList<String>();
        boolean onMmc = d.dataFilesystem.contains("mmc");
        long emmc = 0;
        for (HardwareData.Block block : d.blocks) {
            if (block.kind.equals("eMMC") && block.sizeBytes > emmc) {
                emmc = block.sizeBytes;
            }
        }
        if (!onMmc || emmc <= 0) {
            return out;
        }
        for (HardwareData.Volume volume : d.volumes) {
            if (volume.label.startsWith("/data") && volume.total > emmc * 1.02) {
                out.add(volume.label + " reports " + SizeFormat.bytes(volume.total) + " but the eMMC chip "
                        + "it is mounted from is only " + SizeFormat.bytes(emmc) + " raw. A filesystem "
                        + "cannot be bigger than its chip, so the reported size (and the percentage "
                        + "built on it) is not real. Trust the partition sizes below.");
            }
        }
        return out;
    }

    /** True when {@code total} is so close to a standard RAM size that it cannot be a measured figure. */
    public static boolean ramLooksSetNotMeasured(long totalBytes) {
        return SizeFormat.roundRamPercent(totalBytes) >= 99;
    }
}
