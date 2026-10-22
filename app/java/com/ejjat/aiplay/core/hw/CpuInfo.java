package com.ejjat.aiplay.core.hw;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses {@code /proc/cpuinfo}.
 *
 * <p>On ARM the kernel prints, per core, the implementer and part number of the CPU. Those identify the
 * <em>core design</em> (Cortex-A53, A72 ...) however the board or fingerprint was renamed, which is why they
 * are the most trustworthy chip evidence an app can read. Part numbers are from ARM's public MIDR
 * assignments; an unknown part is reported as hex rather than guessed.</p>
 */
public final class CpuInfo {

    /** One logical CPU as listed by the kernel. */
    public static final class Core {
        public int index = -1;
        public String modelName = "";
        public int implementer = -1;
        public int part = -1;
        public String bogomips = "";
        public String features = "";

        /** "Cortex-A53", "ARM part 0xd99", "Qualcomm part 0x801", or the x86 model name. */
        public String designName() {
            if (implementer >= 0 && part >= 0) {
                return coreName(implementer, part);
            }
            return modelName.length() > 0 ? modelName : "unknown core";
        }
    }

    public final List<Core> cores = new ArrayList<Core>();
    /** The {@code Hardware} line (older kernels), often a board name, often generic. */
    public String hardware = "";

    public static CpuInfo parse(String text) {
        CpuInfo info = new CpuInfo();
        if (text == null) {
            return info;
        }
        Core current = null;
        for (String raw : text.split("\n")) {
            int colon = raw.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = raw.substring(0, colon).trim().toLowerCase();
            String value = raw.substring(colon + 1).trim();
            if (key.equals("processor")) {
                current = new Core();
                try {
                    current.index = Integer.parseInt(value);
                } catch (NumberFormatException notANumber) {
                    // 32-bit ARM prints the model here ("ARMv7 Processor rev 4 (v7l)") and the
                    // index in a separate line; keep the text so the core still has a name.
                    current.index = info.cores.size();
                    current.modelName = value;
                }
                info.cores.add(current);
            } else if (key.equals("hardware")) {
                info.hardware = value;
            } else if (current != null) {
                if (key.equals("model name") || key.equals("cpu model")) {
                    current.modelName = value;
                } else if (key.equals("cpu implementer")) {
                    current.implementer = parseHex(value);
                } else if (key.equals("cpu part")) {
                    current.part = parseHex(value);
                } else if (key.equals("bogomips")) {
                    current.bogomips = value;
                } else if (key.equals("features") || key.equals("flags")) {
                    current.features = value;
                }
            }
        }
        return info;
    }

    private static int parseHex(String value) {
        try {
            String v = value.toLowerCase();
            return Integer.parseInt(v.startsWith("0x") ? v.substring(2) : v, 16);
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    /** ARM-designed cores by MIDR part number (implementer 0x41). */
    static String armCore(int part) {
        switch (part) {
            case 0xc05: return "Cortex-A5";
            case 0xc07: return "Cortex-A7";
            case 0xc08: return "Cortex-A8";
            case 0xc09: return "Cortex-A9";
            case 0xc0d: return "Cortex-A12";
            case 0xc0e: return "Cortex-A17";
            case 0xc0f: return "Cortex-A15";
            case 0xd01: return "Cortex-A32";
            case 0xd03: return "Cortex-A53";
            case 0xd04: return "Cortex-A35";
            case 0xd05: return "Cortex-A55";
            case 0xd07: return "Cortex-A57";
            case 0xd08: return "Cortex-A72";
            case 0xd09: return "Cortex-A73";
            case 0xd0a: return "Cortex-A75";
            case 0xd0b: return "Cortex-A76";
            case 0xd0d: return "Cortex-A77";
            case 0xd41: return "Cortex-A78";
            case 0xd44: return "Cortex-X1";
            default: return null;
        }
    }

    public static String coreName(int implementer, int part) {
        if (implementer == 0x41) {
            String name = armCore(part);
            if (name != null) {
                return name;
            }
            return String.format("ARM part 0x%x", part);
        }
        String maker;
        switch (implementer) {
            case 0x51: maker = "Qualcomm"; break;
            case 0x53: maker = "Samsung"; break;
            case 0x48: maker = "HiSilicon"; break;
            case 0x4e: maker = "NVIDIA"; break;
            case 0x61: maker = "Apple"; break;
            case 0x69: maker = "Intel"; break;
            default: maker = String.format("implementer 0x%x", implementer); break;
        }
        return String.format("%s part 0x%x", maker, part);
    }

    /** "4x Cortex-A53" or "4x Cortex-A53 + 2x Cortex-A72": core groups in kernel order. */
    public String coreSummary() {
        if (cores.isEmpty()) {
            return "";
        }
        Map<String, Integer> groups = new LinkedHashMap<String, Integer>();
        for (Core core : cores) {
            String name = core.designName();
            Integer count = groups.get(name);
            groups.put(name, count == null ? 1 : count + 1);
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Integer> entry : groups.entrySet()) {
            if (out.length() > 0) {
                out.append(" + ");
            }
            out.append(entry.getValue()).append("x ").append(entry.getKey());
        }
        return out.toString();
    }

    /** True when the cores are not all the same design (a big.LITTLE style layout). */
    public boolean isHeterogeneous() {
        if (cores.size() < 2) {
            return false;
        }
        String first = cores.get(0).designName();
        for (Core core : cores) {
            if (!core.designName().equals(first)) {
                return true;
            }
        }
        return false;
    }
}
