package com.ejjat.aiplay.core.hw;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Everything the hardware report knows, collected by the Android side ({@code HardwareProbe}) and
 * formatted by {@link HardwareReport}. Plain fields, no Android types, so the formatter and the
 * analysis can be tested on a desktop with fixtures.
 *
 * <p>An empty string / -1 / null means "not read". Anything that could not be read is also named in
 * {@link #unreadable} with the reason, so the report never shows a blank that looks like a value.</p>
 */
public final class HardwareData {

    // ---- chip and board
    public String manufacturer = "", brand = "", model = "", device = "", product = "";
    public String socManufacturer = "", socModel = "", board = "", hardware = "";
    public String[] abis = new String[0];
    public int sdk;
    public String androidRelease = "";
    public CpuInfo cpu = new CpuInfo();
    public String kernel = "";
    public String fingerprint = "";
    public String gpuVendor = "", gpuRenderer = "", gpuVersion = "";
    public String displayInfo = "";

    // ---- memory
    public MemInfo mem = new MemInfo();
    public long amTotalBytes = -1, amAvailBytes = -1, amThresholdBytes = -1;
    public boolean amLowMemory, lowRamDevice;
    public int memoryClassMb = -1, largeMemoryClassMb = -1;

    // ---- storage
    /** One mounted volume: where it is, how big, how much is free. */
    public static final class Volume {
        public final String label;
        public final long total;
        public final long free;

        public Volume(String label, long total, long free) {
            this.label = label;
            this.total = total;
            this.free = free;
        }
    }

    /** One physical block device (eMMC, UFS/SCSI disk, NVMe) found under /sys/block. */
    public static final class Block {
        public final String name;
        public final long sizeBytes;
        public final String kind;
        public final String detail;

        public Block(String name, long sizeBytes, String kind, String detail) {
            this.name = name;
            this.sizeBytes = sizeBytes;
            this.kind = kind;
            this.detail = detail;
        }
    }

    public final List<Volume> volumes = new ArrayList<Volume>();
    public final List<Block> blocks = new ArrayList<Block>();
    public String dataFilesystem = "";
    public String flashHealth = "";
    /** Lower bound of the eMMC wear estimate in percent, or -1 when not exposed. */
    public int flashWearLowPercent = -1;
    /** Lines of /proc/swaps without the header. */
    public final List<String> swapLines = new ArrayList<String>();

    // ---- load, clocks, heat
    public String loadavg = "";
    public final List<String> cpuFreqLines = new ArrayList<String>();
    public int maxFreqKhz = -1;
    public final List<String> thermalLines = new ArrayList<String>();
    public int maxTempC = -1;
    public String thermalStatus = "";
    public CpuBench.Result cpuTest;

    // ---- processes
    public ProcessTable processes = new ProcessTable();
    public String processNote = "";
    public String rootTop = "";
    public String rootMeminfo = "";
    public boolean root;

    // ---- properties and partitions
    public final Map<String, String> props = new TreeMap<String, String>();
    public int propsSeen;
    public PartitionTable partitions = new PartitionTable();

    // ---- packages (only vendor-style and update-related names are listed)
    public int packagesTotal = -1, packagesUser = -1;
    public final List<String> vendorPackages = new ArrayList<String>();
    public final List<String> updatePackages = new ArrayList<String>();

    // ---- what could not be read
    public final List<String> unreadable = new ArrayList<String>();

    public void skip(String what, String why) {
        unreadable.add(what + ": " + why);
    }

    /** The core count to use in rules: what /proc/cpuinfo listed, else what Java reports. */
    public int coreCount() {
        return cpu.cores.isEmpty() ? Runtime.getRuntime().availableProcessors() : cpu.cores.size();
    }

    public boolean has64BitAbi() {
        for (String abi : abis) {
            if (abi.contains("64")) {
                return true;
            }
        }
        return false;
    }
}
