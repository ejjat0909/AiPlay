package com.ejjat.aiplay.core.hw;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Names the chip family from every identifier the system offers, and says how far to trust it.
 *
 * <p>Head units frequently report generic or renamed values, so this only <em>matches known prefixes</em>
 * and shows the evidence it matched on. It never invents a model. A family with no match is "not
 * recognised" and the reader is pointed at the CPU core designs, which a vendor cannot rename.</p>
 */
public final class ChipGuess {

    private static final Map<String, Pattern> FAMILIES = new LinkedHashMap<String, Pattern>();
    private static final Pattern GENERIC = Pattern.compile(
            "(?i)^(generic\\w*|unknown|full_\\w*|aosp\\w*|devboard|android|none|default|sdk\\w*|board|"
                    + "qcom_generic|universal)$");

    static {
        FAMILIES.put("Android emulator (not real hardware)",
                Pattern.compile("(?i)\\b(ranchu|goldfish|emulator|sdk_gphone\\w*|emu\\d+\\w*)\\b"));
        FAMILIES.put("Rockchip",
                Pattern.compile("(?i)\\brk\\d{2,4}\\w*|rockchip|\\bpx(3|5|6|30)\\b|\\brk30board\\b"));
        FAMILIES.put("MediaTek",
                Pattern.compile("(?i)\\bmt\\d{4}\\w*|mediatek|\\bmtk\\w*"));
        FAMILIES.put("Unisoc / Spreadtrum",
                Pattern.compile("(?i)\\bums\\d+\\w*|\\bsc\\d{4}\\w*|spreadtrum|unisoc|\\bsp\\d{4}\\w*|\\buis\\d{4}\\w*"));
        FAMILIES.put("Allwinner",
                Pattern.compile("(?i)\\bsun\\d+i\\w*|allwinner|sunxi"));
        FAMILIES.put("Qualcomm",
                Pattern.compile("(?i)\\bmsm\\d+\\w*|\\bsdm\\d+\\w*|\\bsm\\d{4}\\w*|\\bapq\\d+\\w*|qcom|qualcomm"
                        + "|\\bkona\\b|\\blahaina\\b|\\btaro\\b|\\bsdx\\d+"));
        FAMILIES.put("Amlogic",
                Pattern.compile("(?i)amlogic|\\bmeson\\w*|\\bgxl\\b|\\bg12[ab]\\b|\\bsm1\\b|\\bs9[0-9]{2}\\w*"));
        FAMILIES.put("Samsung Exynos",
                Pattern.compile("(?i)exynos|\\buniversal\\d+\\w*"));
        FAMILIES.put("HiSilicon / Kirin",
                Pattern.compile("(?i)hisilicon|kirin|\\bhi\\d{4}\\w*"));
        FAMILIES.put("Telechips",
                Pattern.compile("(?i)telechips|\\btcc\\d+\\w*"));
        FAMILIES.put("Autochips (AC82xx)",
                Pattern.compile("(?i)\\bac8\\d{3}\\w*|autochips"));
        FAMILIES.put("NXP i.MX / S32",
                Pattern.compile("(?i)\\bimx\\d*\\w*|\\bs32\\w*|\\bnxp\\b"));
        FAMILIES.put("Intel x86",
                Pattern.compile("(?i)\\bintel\\b|\\bx86\\w*|\\bbxt\\b|\\bcht\\b|\\bbroxton\\b"));
    }

    public static final class Result {
        /** Best family, or "" when nothing matched. */
        public String family = "";
        /** One line per identifier that matched, "Rockchip  <- ro.board.platform = rk3399". */
        public final List<String> evidence = new ArrayList<String>();
        /** Families other than {@link #family} that also matched (the unit contradicts itself). */
        public final List<String> conflicts = new ArrayList<String>();
        /** Identifiers whose value is a placeholder such as "generic" or "unknown". */
        public final List<String> generic = new ArrayList<String>();
    }

    /** @param identifiers name to value, e.g. "ro.board.platform" to "rk3399"; empty values are ignored */
    public static Result guess(Map<String, String> identifiers) {
        Result result = new Result();
        Map<String, Integer> votes = new LinkedHashMap<String, Integer>();
        for (Map.Entry<String, String> entry : identifiers.entrySet()) {
            String value = entry.getValue() == null ? "" : entry.getValue().trim();
            if (value.length() == 0) {
                continue;
            }
            if (GENERIC.matcher(value).matches()) {
                result.generic.add(entry.getKey() + " = " + value);
                continue;
            }
            for (Map.Entry<String, Pattern> family : FAMILIES.entrySet()) {
                if (family.getValue().matcher(value).find()) {
                    result.evidence.add(family.getKey() + "  <-  " + entry.getKey() + " = " + value);
                    Integer count = votes.get(family.getKey());
                    votes.put(family.getKey(), count == null ? 1 : count + 1);
                    break;           // one family per identifier
                }
            }
        }
        int best = 0;
        for (Map.Entry<String, Integer> vote : votes.entrySet()) {
            if (vote.getValue() > best) {
                best = vote.getValue();
                result.family = vote.getKey();
            }
        }
        for (String family : votes.keySet()) {
            if (!family.equals(result.family)) {
                result.conflicts.add(family);
            }
        }
        return result;
    }

    /**
     * A note when the firmware is built on MediaTek's BSP, which says nothing reliable about the chip
     * itself: several vendors ship MediaTek-derived designs under their own names. Empty when no
     * MediaTek-style property or thermal sensor is present.
     */
    public static String bspNote(Map<String, String> props, List<String> thermalLines) {
        boolean properties = false;
        for (String key : props.keySet()) {
            if (key.startsWith("ro.mediatek.") || key.startsWith("ro.mtk_")) {
                properties = true;
                break;
            }
        }
        boolean sensors = false;
        for (String line : thermalLines) {
            if (line.contains("(mtk")) {
                sensors = true;
            }
        }
        if (!properties && !sensors) {
            return "";
        }
        return "the firmware is built on MediaTek's BSP (" + (properties ? "ro.mediatek.* / ro.mtk_* "
                + "properties" : "") + (properties && sensors ? ", " : "") + (sensors ? "mtk* thermal "
                + "sensors" : "") + "): the SoC is a MediaTek part or a design derived from one, "
                + "whatever name it carries";
    }

    /**
     * What the core designs say about the chip's class. Cores cannot be renamed by a vendor, so this
     * is the evidence to prefer when the names above disagree or are generic.
     */
    public static String performanceClass(CpuInfo cpu, int maxFreqKhz) {
        if (cpu == null || cpu.cores.isEmpty()) {
            return "";
        }
        boolean old = true;
        boolean efficiencyOnly = true;
        boolean performance = false;
        boolean known = false;
        for (CpuInfo.Core core : cpu.cores) {
            String name = core.designName();
            if (!name.startsWith("Cortex-")) {
                old = false;
                efficiencyOnly = false;
                continue;
            }
            known = true;
            boolean oldCore = name.equals("Cortex-A5") || name.equals("Cortex-A7")
                    || name.equals("Cortex-A8") || name.equals("Cortex-A9");
            boolean efficiency = name.equals("Cortex-A53") || name.equals("Cortex-A55")
                    || name.equals("Cortex-A35") || name.equals("Cortex-A32") || name.equals("Cortex-A7");
            if (!oldCore) {
                old = false;
            }
            if (!efficiency) {
                efficiencyOnly = false;
            }
            if (name.equals("Cortex-A57") || name.equals("Cortex-A72") || name.equals("Cortex-A73")
                    || name.equals("Cortex-A75") || name.equals("Cortex-A76") || name.equals("Cortex-A77")
                    || name.equals("Cortex-A78") || name.equals("Cortex-X1") || name.equals("Cortex-A15")
                    || name.equals("Cortex-A17")) {
                performance = true;
            }
        }
        if (!known) {
            return "core designs not identified from /proc/cpuinfo";
        }
        String clock = maxFreqKhz > 0 ? String.format(java.util.Locale.US, ", max %.2f GHz",
                maxFreqKhz / 1000000.0) : "";
        if (old) {
            return cpu.cores.size() + " old-generation cores (designs from 2005-2011)" + clock
                    + ": a weak chip by today's app demands";
        }
        if (performance) {
            return "has high-performance cores" + clock + ": a capable chip class";
        }
        if (efficiencyOnly) {
            return cpu.cores.size() + " efficiency-class cores only" + clock
                    + ": adequate for navigation and music, slow for heavy apps";
        }
        return cpu.cores.size() + " cores" + clock;
    }
}
