package com.ejjat.aiplay.core.hw;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Reads {@code getprop} output and picks the properties that identify a board, without leaking
 * anything personal.
 *
 * <p>A diagnostic report gets pasted into chats and issues, so the filter is an allowlist of prefixes
 * (hardware, build, boot, bootloader, vendor/MCU keys) <em>and</em> a denylist for anything that can
 * identify a person or a device (serial numbers, IMEI, MAC and Bluetooth addresses, phone numbers,
 * Wi-Fi names). Values that look like a MAC address or a 14-16 digit IMEI are replaced even under an
 * allowed key.</p>
 */
public final class PropParser {

    private static final Pattern LINE = Pattern.compile("^\\[([^\\]]+)\\]: \\[(.*)\\]$");
    private static final Pattern MAC = Pattern.compile("(?i)\\b([0-9a-f]{2}[:-]){5}[0-9a-f]{2}\\b");
    private static final Pattern LONG_DIGITS = Pattern.compile("\\b\\d{14,16}\\b");

    private static final String[] ALLOW_PREFIX = {
            "ro.product.", "ro.build.", "ro.board.", "ro.soc.", "ro.hardware", "ro.chipname",
            "ro.mediatek.", "ro.mcu", "ro.vendor.product.", "ro.vendor.build.", "ro.odm.product.",
            "ro.system.product.", "ro.system.build.", "ro.bootloader", "ro.bootmode", "ro.boot.",
            "ro.treble.", "ro.virtual_ab.", "ro.oem_unlock_supported", "sys.oem_unlock_allowed",
            "ro.secure", "ro.debuggable", "ro.adb.secure", "ro.kernel.", "ro.config.low_ram",
            "ro.sf.lcd_density", "ro.crypto.", "ro.cpu.", "ro.rk.", "ro.zygote", "ro.revision",
            "dalvik.vm.heapsize", "dalvik.vm.heapgrowthlimit", "ro.vndk.version",
            "ro.build.version.", "ro.apex.updatable", "ro.product.first_api_level"
    };

    /** Keys that carry these words are never printed. */
    private static final String[] DENY_WORDS = {
            "serial", "imei", "meid", "macaddr", "mac_addr", "wifi", "bt.addr", "bluetooth", "android_id",
            "phone", "number", "iccid", "imsi", "fingerprint_id", "deviceid", "device_id", "uuid",
            "location", "gps", "ssid", "password", "token", "account"
    };

    private static final String[] VENDOR_WORDS = {"mcu", "chip", "platform", "soc", "sunxi", "rockchip",
            "mtk", "hct", "syu", "microntek", "unisoc", "spreadtrum", "amlogic", "allwinner", "telechips"};

    private PropParser() {
    }

    /** Parses {@code [key]: [value]} lines. Lines in any other shape are ignored. */
    public static Map<String, String> parseGetprop(String text) {
        Map<String, String> props = new LinkedHashMap<String, String>();
        if (text == null) {
            return props;
        }
        for (String line : text.split("\n")) {
            java.util.regex.Matcher m = LINE.matcher(line.trim());
            if (m.matches()) {
                props.put(m.group(1), m.group(2));
            }
        }
        return props;
    }

    public static boolean denied(String key) {
        String lower = key.toLowerCase();
        for (String word : DENY_WORDS) {
            if (lower.contains(word)) {
                return true;
            }
        }
        return false;
    }

    public static boolean wanted(String key) {
        if (denied(key)) {
            return false;
        }
        for (String prefix : ALLOW_PREFIX) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        String lower = key.toLowerCase();
        if (lower.startsWith("ro.") || lower.startsWith("persist.vendor.") || lower.startsWith("sys.")
                || lower.startsWith("vendor.")) {
            for (String word : VENDOR_WORDS) {
                if (lower.contains(word)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Replaces anything that looks like a MAC address or an IMEI inside a value. */
    public static String scrub(String value) {
        String out = MAC.matcher(value).replaceAll("<mac removed>");
        return LONG_DIGITS.matcher(out).replaceAll("<number removed>");
    }

    /** The allowed, scrubbed properties, sorted by key. */
    public static Map<String, String> select(Map<String, String> all) {
        Map<String, String> picked = new TreeMap<String, String>();
        for (Map.Entry<String, String> entry : all.entrySet()) {
            if (wanted(entry.getKey())) {
                picked.put(entry.getKey(), scrub(entry.getValue()));
            }
        }
        return picked;
    }

    /** Like {@link #first} but "unknown" (the placeholder many builds print) counts as not set. */
    public static String firstReal(Map<String, String> props, String... keys) {
        for (String key : keys) {
            String value = props.get(key);
            if (value != null && value.length() > 0 && !value.equalsIgnoreCase("unknown")) {
                return value;
            }
        }
        return "";
    }

    /** First non-empty value among the keys, or "". */
    public static String first(Map<String, String> props, String... keys) {
        for (String key : keys) {
            String value = props.get(key);
            if (value != null && value.length() > 0) {
                return value;
            }
        }
        return "";
    }
}
