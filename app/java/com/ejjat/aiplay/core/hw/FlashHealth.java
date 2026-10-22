package com.ejjat.aiplay.core.hw;

/**
 * Decodes eMMC wear indicators.
 *
 * <p>eMMC 5.0+ exposes {@code life_time} (two hex estimates, one for each kind of flash, each in steps
 * of 10% of rated life: 0x01 is 0-10% used ... 0x0A is 90-100%, 0x0B is beyond the rated life) and
 * {@code pre_eol_info} (1 normal, 2 warning at 80% of the spare blocks used, 3 urgent at 90%). Many
 * controllers report 0x00 ("not defined"), and many kernels do not expose the files; that is reported
 * as such, never as "healthy".</p>
 */
public final class FlashHealth {

    private FlashHealth() {
    }

    public static String describe(String lifeTime, String preEol) {
        StringBuilder out = new StringBuilder();
        if (lifeTime != null && lifeTime.trim().length() > 0) {
            String[] parts = lifeTime.trim().split("\\s+");
            int worst = -1;
            boolean undefined = true;
            for (String part : parts) {
                int value = parseHex(part);
                if (value > 0) {
                    undefined = false;
                    worst = Math.max(worst, value);
                }
            }
            if (undefined) {
                out.append("wear: the controller reports no estimate (").append(lifeTime.trim()).append(")");
            } else if (worst >= 0x0B) {
                out.append("wear: BEYOND rated life (").append(lifeTime.trim()).append(")");
            } else {
                out.append("wear: ").append((worst - 1) * 10).append("-").append(worst * 10)
                        .append("% of rated life used (").append(lifeTime.trim()).append(")");
            }
        }
        if (preEol != null && preEol.trim().length() > 0) {
            int value = parseHex(preEol.trim());
            if (out.length() > 0) {
                out.append("; ");
            }
            switch (value) {
                case 1: out.append("spare blocks: normal"); break;
                case 2: out.append("spare blocks: WARNING (80% used)"); break;
                case 3: out.append("spare blocks: URGENT (90% used)"); break;
                default: out.append("spare blocks: not reported (").append(preEol.trim()).append(")");
            }
        }
        return out.toString();
    }

    /** True when either indicator reports a worn card; lag from a dying flash is real. */
    public static boolean worn(String lifeTime, String preEol) {
        if (lifeTime != null) {
            for (String part : lifeTime.trim().split("\\s+")) {
                if (parseHex(part) >= 0x09) {
                    return true;
                }
            }
        }
        return preEol != null && parseHex(preEol.trim()) >= 2;
    }

    private static int parseHex(String value) {
        try {
            String v = value.trim().toLowerCase();
            return Integer.parseInt(v.startsWith("0x") ? v.substring(2) : v, 16);
        } catch (NumberFormatException error) {
            return -1;
        }
    }
}
