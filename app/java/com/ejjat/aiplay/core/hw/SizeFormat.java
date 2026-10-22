package com.ejjat.aiplay.core.hw;

import java.util.Locale;

/** Sizes for people: binary units, and the marketing size a number most likely came from. */
public final class SizeFormat {

    private static final double[] RAM_SIZES_GB = {0.25, 0.5, 1, 1.5, 2, 3, 4, 6, 8, 12, 16, 24, 32};
    private static final double[] STORAGE_SIZES_GB = {2, 4, 8, 16, 32, 64, 128, 256, 512, 1024, 2048};

    private SizeFormat() {
    }

    public static String bytes(long bytes) {
        if (bytes < 0) {
            return "unknown";
        }
        double value = bytes;
        String[] units = {"B", "KiB", "MiB", "GiB", "TiB"};
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return unit == 0 ? String.format(Locale.US, "%d B", bytes)
                : String.format(Locale.US, "%.2f %s", value, units[unit]);
    }

    /** Smallest common RAM size that is at least {@code bytes}: "4 GB" for a 3.7 GiB MemTotal. */
    public static String marketedRam(long bytes) {
        return marketed(bytes, RAM_SIZES_GB);
    }

    /** Smallest common storage size that is at least {@code bytes}. */
    public static String marketedStorage(long bytes) {
        return marketed(bytes, STORAGE_SIZES_GB);
    }

    private static String marketed(long bytes, double[] sizes) {
        if (bytes <= 0) {
            return "unknown";
        }
        double gib = bytes / (1024.0 * 1024.0 * 1024.0);
        for (double size : sizes) {
            if (gib <= size) {
                return size == Math.floor(size) ? String.format(Locale.US, "%d GB", (long) size)
                        : String.format(Locale.US, "%.1f GB", size);
            }
        }
        return ">" + (long) sizes[sizes.length - 1] + " GB";
    }

    /** Percentage of {@code total} that is used, 0..100; 0 when the total is unknown. */
    public static int percentUsed(long total, long free) {
        if (total <= 0) {
            return 0;
        }
        return (int) Math.round(100.0 * (total - Math.min(free, total)) / total);
    }
}
