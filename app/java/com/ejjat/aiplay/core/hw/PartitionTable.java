package com.ejjat.aiplay.core.hw;

import java.util.ArrayList;
import java.util.List;

/** Parses {@code /proc/partitions} and reads what the layout says about how the unit is flashed. */
public final class PartitionTable {

    public static final class Entry {
        public final String name;
        public final long kb;

        Entry(String name, long kb) {
            this.name = name;
            this.kb = kb;
        }
    }

    public final List<Entry> entries = new ArrayList<Entry>();

    public static PartitionTable parse(String text) {
        PartitionTable table = new PartitionTable();
        if (text == null) {
            return table;
        }
        for (String line : text.split("\n")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length != 4) {
                continue;
            }
            try {
                Integer.parseInt(parts[0]);
                Integer.parseInt(parts[1]);
                table.entries.add(new Entry(parts[3], Long.parseLong(parts[2])));
            } catch (NumberFormatException header) {
                // the "major minor #blocks name" header line
            }
        }
        return table;
    }

    /** A copy with device names (mmcblk0p5) replaced by their by-name labels ("recovery"). */
    public PartitionTable withNames(java.util.Map<String, String> names) {
        PartitionTable out = new PartitionTable();
        for (Entry entry : entries) {
            String label = names.get(entry.name);
            out.entries.add(new Entry(label != null ? label : entry.name, entry.kb));
        }
        return out;
    }

    /**
     * True when partitions are listed only by number (mmcblk0p1 ...): the kernel does not say which
     * one is boot, recovery or system; those labels live in /dev/block/.../by-name.
     */
    public boolean namesAreNumbered() {
        if (!valid() || !notable().isEmpty()) {
            return false;
        }
        for (Entry entry : entries) {
            if (entry.name.matches("mmcblk\\d+p\\d+|sd[a-z]+\\d+|nvme\\d+n\\d+p\\d+")) {
                return true;
            }
        }
        return false;
    }

    /**
     * The biggest partitions of the internal flash: not whole disks, not boot/rpmb sub-devices, and
     * not USB sticks when the unit has an eMMC.
     */
    public List<Entry> largestInternal(int count) {
        boolean hasMmc = false;
        for (Entry entry : entries) {
            if (entry.name.matches("mmcblk\\d+p\\d+")) {
                hasMmc = true;
            }
        }
        List<Entry> pool = new ArrayList<Entry>();
        for (Entry entry : entries) {
            String n = entry.name;
            if (isDisk(n) || n.startsWith("loop") || n.startsWith("ram") || n.startsWith("zram")
                    || n.startsWith("dm-") || n.contains("boot0") || n.contains("boot1")
                    || n.contains("rpmb")) {
                continue;
            }
            if (hasMmc && n.startsWith("sd")) {
                continue;
            }
            pool.add(entry);
        }
        java.util.Collections.sort(pool, new java.util.Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return a.kb == b.kb ? 0 : (a.kb < b.kb ? 1 : -1);
            }
        });
        return pool.subList(0, Math.min(count, pool.size()));
    }

    public boolean valid() {
        return !entries.isEmpty();
    }

    public boolean has(String name) {
        for (Entry entry : entries) {
            if (entry.name.equals(name)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasSuffix(String suffix) {
        for (Entry entry : entries) {
            if (entry.name.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /** True when slots exist ({@code boot_a} and {@code boot_b} style names). */
    public boolean seamlessSlots() {
        return hasSuffix("_a") && hasSuffix("_b");
    }

    /** Dynamic partitions (Android 10+): one {@code super} partition holds system/vendor/product. */
    public boolean dynamicPartitions() {
        return has("super") || has("super_a");
    }

    /** Whole-disk entries (no trailing partition number), e.g. {@code mmcblk0}, {@code sda}. */
    public List<Entry> disks() {
        List<Entry> disks = new ArrayList<Entry>();
        for (Entry entry : entries) {
            if (isDisk(entry.name)) {
                disks.add(entry);
            }
        }
        return disks;
    }

    static boolean isDisk(String name) {
        // loop, ram, zram, dm-N, mmcblk0boot0, mmcblk0rpmb and mmcblk0p1 are not whole disks
        return name.matches("mmcblk\\d+") || name.matches("nvme\\d+n\\d+")
                || name.matches("sd[a-z]+") || name.matches("vd[a-z]+");
    }

    /** Names of the partitions that matter for reflashing, in the order the kernel listed them. */
    public List<Entry> notable() {
        String[] interesting = {"boot", "recovery", "system", "vendor", "super", "userdata", "misc",
                "vbmeta", "dtbo", "odm", "product", "cache", "metadata", "persist", "oem", "bootloader",
                "uboot", "resource", "kernel", "logo", "dtb", "param", "backup", "frp"};
        List<Entry> out = new ArrayList<Entry>();
        for (Entry entry : entries) {
            String base = entry.name.replaceAll("_[ab]$", "");
            for (String name : interesting) {
                if (base.equals(name)) {
                    out.add(entry);
                    break;
                }
            }
        }
        return out;
    }
}
