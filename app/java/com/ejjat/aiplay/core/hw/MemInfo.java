package com.ejjat.aiplay.core.hw;

import java.util.LinkedHashMap;
import java.util.Map;

/** Parses {@code /proc/meminfo} (values in kB) and turns sizes into the numbers a reader needs. */
public final class MemInfo {

    public final Map<String, Long> kb = new LinkedHashMap<String, Long>();

    public static MemInfo parse(String text) {
        MemInfo info = new MemInfo();
        if (text == null) {
            return info;
        }
        for (String line : text.split("\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).trim();
            String rest = line.substring(colon + 1).trim();
            int space = rest.indexOf(' ');
            String number = space < 0 ? rest : rest.substring(0, space);
            try {
                info.kb.put(key, Long.parseLong(number));
            } catch (NumberFormatException ignored) {
                // a line that is not "Name: <number> kB" is not memory accounting
            }
        }
        return info;
    }

    public boolean valid() {
        return get("MemTotal") > 0;
    }

    public long get(String key) {
        Long value = kb.get(key);
        return value == null ? 0L : value.longValue();
    }

    public long totalBytes() {
        return get("MemTotal") * 1024L;
    }

    /** MemAvailable when the kernel has it (3.14+), otherwise free + buffers + cache. */
    public long availableBytes() {
        long available = get("MemAvailable");
        if (available <= 0) {
            available = get("MemFree") + get("Buffers") + get("Cached");
        }
        return available * 1024L;
    }

    public long swapTotalBytes() {
        return get("SwapTotal") * 1024L;
    }

    public long swapUsedBytes() {
        return Math.max(0L, get("SwapTotal") - get("SwapFree")) * 1024L;
    }

    /** Percentage (0..100) of RAM that is not available; 0 when the total is unknown. */
    public int usedPercent() {
        long total = totalBytes();
        if (total <= 0) {
            return 0;
        }
        return (int) Math.round(100.0 * (total - availableBytes()) / total);
    }

    /** Percentage (0..100) of swap in use; 0 when there is none. */
    public int swapUsedPercent() {
        long total = swapTotalBytes();
        return total <= 0 ? 0 : (int) Math.round(100.0 * swapUsedBytes() / total);
    }
}
