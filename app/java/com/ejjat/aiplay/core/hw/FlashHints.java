package com.ejjat.aiplay.core.hw;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What the report can say about reflashing, and, as important, what it cannot.
 *
 * <p>An app can read the bootloader/verified-boot properties and the partition layout. It cannot tell
 * whether a unit has a recovery or SD-card update mode: that is firmware behaviour, found by trying it
 * or by the vendor's documentation. Vendor and updater package names are listed only as pointers to
 * <em>which family of firmware</em> the unit runs; they do not prove a method exists.</p>
 */
public final class FlashHints {

    /** Package prefixes seen on aftermarket Android head units. A pointer, not proof. */
    public static final String[] VENDOR_PREFIXES = {
            "com.syu.", "com.microntek.", "com.nwd.", "com.hzbhd.", "com.hct."
    };

    private static final String[] UPDATE_WORDS = {"update", "upgrade", "fota", ".ota", "ota."};

    private FlashHints() {
    }

    public static boolean isVendorPackage(String name) {
        for (String prefix : VENDOR_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isUpdatePackage(String name) {
        String lower = name.toLowerCase();
        if (lower.startsWith("com.google.") || lower.startsWith("com.android.vending")) {
            return false;
        }
        for (String word : UPDATE_WORDS) {
            if (lower.contains(word)) {
                return true;
            }
        }
        return false;
    }

    public static List<String> analyze(HardwareData data) {
        List<String> lines = new ArrayList<String>();
        Map<String, String> p = data.props;

        // --- bootloader and verified boot
        String locked = PropParser.firstReal(p, "ro.boot.flash.locked");
        String vbState = PropParser.firstReal(p, "ro.boot.verifiedbootstate");
        String vbmeta = PropParser.firstReal(p, "ro.boot.vbmeta.device_state");
        String oemSupported = PropParser.firstReal(p, "ro.oem_unlock_supported");
        String oemAllowed = PropParser.firstReal(p, "sys.oem_unlock_allowed");
        boolean any = false;
        if (locked.length() > 0) {
            any = true;
            lines.add("bootloader flash lock: " + locked + (locked.equals("1") ? " (locked)"
                    : locked.equals("0") ? " (unlocked)" : ""));
        }
        if (vbState.length() > 0) {
            any = true;
            lines.add("verified boot state: " + vbState + (vbState.equals("orange")
                    ? " (bootloader unlocked)" : vbState.equals("green") ? " (locked, stock)"
                    : vbState.equals("yellow") ? " (locked, custom key)"
                    : vbState.equals("red") ? " (verification failed)" : ""));
        }
        if (vbmeta.length() > 0) {
            any = true;
            lines.add("vbmeta device state: " + vbmeta);
        }
        if (oemSupported.length() > 0 || oemAllowed.length() > 0) {
            any = true;
            lines.add("OEM unlocking: supported=" + (oemSupported.length() == 0 ? "?" : oemSupported)
                    + " allowed=" + (oemAllowed.length() == 0 ? "?" : oemAllowed));
        }
        if (!any) {
            lines.add("bootloader state: not reported to apps. Either this unit sets no verified-boot "
                    + "or lock properties (common on older or cheap firmware) or Android hides them "
                    + "from apps, so the lock state is unknown from here. adb can read more: "
                    + "adb shell getprop | grep -i -E 'verifiedboot|flash.locked|vbmeta'");
        }
        String bootloader = PropParser.firstReal(p, "ro.bootloader", "ro.boot.bootloader");
        if (bootloader.length() > 0) {
            lines.add("bootloader version: " + bootloader);
        }

        // --- security flags that decide whether a debloat over adb is possible
        String secure = PropParser.first(p, "ro.secure");
        String debuggable = PropParser.first(p, "ro.debuggable");
        String adbSecure = PropParser.first(p, "ro.adb.secure");
        if (secure.length() > 0 || debuggable.length() > 0 || adbSecure.length() > 0) {
            lines.add("build flags: ro.secure=" + (secure.length() == 0 ? "(hidden)" : secure)
                    + " ro.debuggable=" + (debuggable.length() == 0 ? "(hidden)" : debuggable)
                    + " ro.adb.secure=" + (adbSecure.length() == 0 ? "(hidden)" : adbSecure)
                    + (debuggable.equals("1") ? " (a debuggable build: adb root may work)" : ""));
        }

        // --- layout
        PartitionTable parts = data.partitions;
        String slot = PropParser.first(p, "ro.boot.slot_suffix");
        boolean ab = "true".equals(PropParser.first(p, "ro.build.ab_update")) || slot.length() > 0
                || (parts.valid() && parts.seamlessSlots());
        lines.add("A/B slots: " + (ab ? "yes" + (slot.length() > 0 ? " (active slot " + slot + ")" : "")
                : parts.valid() || p.size() > 0 ? "no" : "unknown"));
        boolean dynamic = "true".equals(PropParser.first(p, "ro.boot.dynamic_partitions"))
                || (parts.valid() && parts.dynamicPartitions());
        lines.add("dynamic partitions (super): " + (dynamic ? "yes"
                : parts.valid() || p.size() > 0 ? "no" : "unknown"));
        String treble = PropParser.first(p, "ro.treble.enabled");
        if (treble.length() > 0) {
            lines.add("Treble: " + treble);
        }
        if (parts.valid() && parts.namesAreNumbered()) {
            lines.add("recovery partition: unknown. This kernel lists partitions by number "
                    + "(mmcblk0p1 ...) and the by-name labels were not readable, so recovery cannot be "
                    + "told from boot or system. adb shell ls -l /dev/block/platform/*/*/by-name");
        } else if (parts.valid()) {
            lines.add("recovery partition: " + (parts.has("recovery") || parts.has("recovery_a")
                    ? "present" : ab ? "none listed (A/B units keep recovery inside boot)"
                    : "none listed"));
        } else {
            lines.add("recovery partition: unknown (/proc/partitions was not readable)");
        }

        // --- vendor family pointers
        List<String> vendorProps = new ArrayList<String>();
        List<String> bootServices = new ArrayList<String>();
        for (String key : p.keySet()) {
            String lower = key.toLowerCase();
            if (lower.contains("mcu") || lower.contains("syu") || lower.contains("microntek")
                    || lower.contains("hct")) {
                if (key.startsWith("ro.boottime.")) {
                    bootServices.add(key.substring("ro.boottime.".length()));
                } else {
                    vendorProps.add(key + "=" + p.get(key));
                }
            }
        }
        if (!bootServices.isEmpty()) {
            lines.add("init services with an MCU/vendor name that ran at boot: " + join(bootServices)
                    + " (the unit has an MCU-related service; this is a boot-time entry, not a version)");
        }
        if (!vendorProps.isEmpty()) {
            lines.add("vendor/MCU properties: " + join(vendorProps));
        } else {
            lines.add("vendor/MCU properties: none found (head units with a separate MCU often expose "
                    + "ro.mcu.* keys)");
        }
        if (!data.vendorPackages.isEmpty()) {
            lines.add("head-unit vendor packages (a pointer to the firmware family, not proof): "
                    + join(data.vendorPackages));
        }
        if (!data.updatePackages.isEmpty()) {
            lines.add("update-related packages: " + join(data.updatePackages));
        } else if (data.packagesTotal >= 0) {
            lines.add("update-related packages: none found");
        }

        lines.add("NOT determinable from the OS: whether this unit has a recovery or SD-card update "
                + "mode, and the vendor's flashing procedure. Check the unit's own settings "
                + "(firmware update, 'upgrade from SD/USB'), the vendor's documentation, or try it on "
                + "a unit you can afford to lose.");
        return lines;
    }

    private static String join(List<String> items) {
        StringBuilder out = new StringBuilder();
        for (String item : items) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(item);
        }
        return out.toString();
    }
}
