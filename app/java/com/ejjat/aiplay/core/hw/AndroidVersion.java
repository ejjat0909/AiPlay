package com.ejjat.aiplay.core.hw;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks the Android version a unit claims against the API level it actually runs.
 *
 * <p>Version <em>strings</em> ({@code ro.build.version.release}, the build fingerprint) are labels a
 * firmware maker can set to anything, and head units often do. The API level is what the framework
 * implements and what apps are built against, so it is the number to believe; this class names the
 * Android release that API level really is and lists every claim that disagrees with it.</p>
 */
public final class AndroidVersion {

    private AndroidVersion() {
    }

    /** The Android release for an API level, or "" when this table does not know it. */
    public static String releaseFor(int sdk) {
        switch (sdk) {
            case 21: return "5.0";
            case 22: return "5.1";
            case 23: return "6.0";
            case 24: return "7.0";
            case 25: return "7.1";
            case 26: return "8.0";
            case 27: return "8.1";
            case 28: return "9";
            case 29: return "10";
            case 30: return "11";
            case 31: return "12";
            case 32: return "12L";
            case 33: return "13";
            case 34: return "14";
            case 35: return "15";
            default: return "";
        }
    }

    /** "API 27 = Android 8.1", or just "API 99" when the release is not known. */
    public static String label(int sdk) {
        String release = releaseFor(sdk);
        return release.length() == 0 ? "API " + sdk : "API " + sdk + " = Android " + release;
    }

    /** The release inside a build fingerprint ({@code brand/product/device:RELEASE/id/incr:type/tags}). */
    public static String releaseOfFingerprint(String fingerprint) {
        if (fingerprint == null) {
            return "";
        }
        String[] parts = fingerprint.split(":");
        if (parts.length < 2) {
            return "";
        }
        return parts[1].split("/")[0].trim();
    }

    /** Leading integer of a version string ("10", "8.1.0" -> 8), or -1. */
    static int major(String version) {
        if (version == null) {
            return -1;
        }
        int end = 0;
        while (end < version.length() && Character.isDigit(version.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return -1;
        }
        try {
            return Integer.parseInt(version.substring(0, end));
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    /**
     * One line per claim that disagrees with the API level; an empty list means the labels are
     * consistent (or the API level is not one this table knows, so nothing can be said).
     */
    public static List<String> mismatches(int sdk, String release, String fingerprint,
                                          String vendorFingerprint) {
        List<String> out = new ArrayList<String>();
        String real = releaseFor(sdk);
        if (real.length() == 0) {
            return out;
        }
        int expected = major(real);
        if (release != null && release.length() > 0 && major(release) != expected) {
            out.add("the version string says Android " + release + ", but " + label(sdk)
                    + ": the label is wrong or edited. Apps run on the API level.");
        }
        String fp = releaseOfFingerprint(fingerprint);
        if (fp.length() > 0 && major(fp) != expected) {
            out.add("the build fingerprint says Android " + fp + " (a copied or reused fingerprint).");
        }
        String vfp = releaseOfFingerprint(vendorFingerprint);
        if (vfp.length() > 0 && major(vfp) != expected && !vfp.equals(fp)) {
            out.add("the vendor partition's fingerprint says Android " + vfp + ".");
        }
        return out;
    }
}
