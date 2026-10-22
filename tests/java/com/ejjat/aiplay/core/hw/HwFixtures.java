package com.ejjat.aiplay.core.hw;

/**
 * Representative inputs in the formats these chips produce. They are NOT captures from a particular
 * unit: the layouts follow what the kernels print (field names, core part numbers, partition names), the
 * values are plausible. Tests prove the parsers cope with each format, not that a given unit looks so.
 * Not named *Test so tools/test.sh does not run it as a test class.
 */
final class HwFixtures {

    private HwFixtures() {
    }

    /** 64-bit kernel, big.LITTLE 4x A53 + 2x A72, the shape of a Rockchip RK3399 board. */
    static final String CPUINFO_ARM64_BIGLITTLE =
            "processor\t: 0\nBogoMIPS\t: 48.00\nFeatures\t: fp asimd evtstrm aes pmull sha1 sha2 crc32\n"
                    + "CPU implementer\t: 0x41\nCPU architecture: 8\nCPU variant\t: 0x0\nCPU part\t: 0xd03\n"
                    + "CPU revision\t: 4\n\n"
                    + "processor\t: 1\nBogoMIPS\t: 48.00\nCPU implementer\t: 0x41\nCPU part\t: 0xd03\n\n"
                    + "processor\t: 2\nBogoMIPS\t: 48.00\nCPU implementer\t: 0x41\nCPU part\t: 0xd03\n\n"
                    + "processor\t: 3\nBogoMIPS\t: 48.00\nCPU implementer\t: 0x41\nCPU part\t: 0xd03\n\n"
                    + "processor\t: 4\nBogoMIPS\t: 48.00\nCPU implementer\t: 0x41\nCPU part\t: 0xd08\n\n"
                    + "processor\t: 5\nBogoMIPS\t: 48.00\nCPU implementer\t: 0x41\nCPU part\t: 0xd08\n\n"
                    + "Hardware\t: Rockchip RK3399 Evaluation Board\n";

    /** 32-bit kernel, quad Cortex-A7, the shape of an Allwinner head-unit chip. */
    static final String CPUINFO_ARM32_A7 =
            "processor\t: 0\nmodel name\t: ARMv7 Processor rev 5 (v7l)\nBogoMIPS\t: 2.00\n"
                    + "Features\t: half thumb fastmult vfp edsp neon vfpv3 tls vfpv4 idiva idivt\n"
                    + "CPU implementer\t: 0x41\nCPU architecture: 7\nCPU variant\t: 0x0\nCPU part\t: 0xc07\n"
                    + "CPU revision\t: 5\n\n"
                    + "processor\t: 1\nmodel name\t: ARMv7 Processor rev 5 (v7l)\nCPU implementer\t: 0x41\n"
                    + "CPU part\t: 0xc07\n\n"
                    + "processor\t: 2\nmodel name\t: ARMv7 Processor rev 5 (v7l)\nCPU implementer\t: 0x41\n"
                    + "CPU part\t: 0xc07\n\n"
                    + "processor\t: 3\nmodel name\t: ARMv7 Processor rev 5 (v7l)\nCPU implementer\t: 0x41\n"
                    + "CPU part\t: 0xc07\n\n"
                    + "Hardware\t: sun8i\nRevision\t: 0000\n";

    /** 8 cores: 6x A55 + 2x A75, the shape of a Unisoc UIS7862-class head-unit chip. */
    static final String CPUINFO_UNISOC =
            "processor\t: 0\nCPU implementer\t: 0x41\nCPU part\t: 0xd05\n\n"
                    + "processor\t: 1\nCPU implementer\t: 0x41\nCPU part\t: 0xd05\n\n"
                    + "processor\t: 2\nCPU implementer\t: 0x41\nCPU part\t: 0xd05\n\n"
                    + "processor\t: 3\nCPU implementer\t: 0x41\nCPU part\t: 0xd05\n\n"
                    + "processor\t: 4\nCPU implementer\t: 0x41\nCPU part\t: 0xd05\n\n"
                    + "processor\t: 5\nCPU implementer\t: 0x41\nCPU part\t: 0xd05\n\n"
                    + "processor\t: 6\nCPU implementer\t: 0x41\nCPU part\t: 0xd0a\n\n"
                    + "processor\t: 7\nCPU implementer\t: 0x41\nCPU part\t: 0xd0a\n\n"
                    + "Hardware\t: Unisoc UMS512\n";

    static final String CPUINFO_X86 =
            "processor\t: 0\nvendor_id\t: GenuineIntel\nmodel name\t: Intel(R) Xeon(R) CPU @ 2.20GHz\n"
                    + "flags\t\t: fpu vme\n\nprocessor\t: 1\nvendor_id\t: GenuineIntel\n"
                    + "model name\t: Intel(R) Xeon(R) CPU @ 2.20GHz\n";

    /** A "4 GB" unit as the kernel reports it, with compressed swap. */
    static final String MEMINFO_4GB =
            "MemTotal:        3786912 kB\nMemFree:          212340 kB\nMemAvailable:     910220 kB\n"
                    + "Buffers:           10240 kB\nCached:           780112 kB\nSwapCached:        20480 kB\n"
                    + "SwapTotal:       1048572 kB\nSwapFree:         262143 kB\nCmaTotal:         163840 kB\n"
                    + "HugePages_Total:       0\n";

    static final String MEMINFO_1GB =
            "MemTotal:         989000 kB\nMemFree:           40000 kB\nBuffers:           20000 kB\n"
                    + "Cached:           120000 kB\nSwapTotal:             0 kB\nSwapFree:              0 kB\n";

    /** Android 8+ toybox: ps -A -o PID,RSS,NAME */
    static final String PS_TOYBOX =
            "  PID   RSS NAME\n    1  4096 init\n  412 80240 system_server\n  900 152300 com.android.launcher3\n"
                    + " 1200 210044 com.google.android.gms\n 1500 64000 com.ejjat.aiplay\n 1600 abc broken\n";

    /** Android 5-7 ps */
    static final String PS_OLD =
            "USER     PID   PPID  VSIZE  RSS     WCHAN    PC         NAME\n"
                    + "root      1     0     8960   760   ffffffff 00000000 S /init\n"
                    + "system    412   120   1500000 90112 ffffffff 00000000 S system_server\n"
                    + "u0_a12    900   120   1400000 150000 ffffffff 00000000 S com.android.launcher3\n";

    static final String PARTITIONS_ROCKCHIP =
            "major minor  #blocks  name\n\n 179        0   30535680 mmcblk0\n"
                    + " 179        1       4096 mmcblk0p1\n 179        2       4096 mmcblk0p2\n"
                    + " 179        3       4096 mmcblk0p3\n 179        4       4096 mmcblk0p4\n"
                    + " 179        5      32768 mmcblk0p5\n 179        6      32768 mmcblk0p6\n"
                    + " 179        7      32768 mmcblk0p7\n 179        8      65536 mmcblk0p8\n"
                    + " 179        9    2097152 mmcblk0p9\n 179       10     131072 mmcblk0p10\n"
                    + " 179       11   26214400 mmcblk0p11\n 179       32       4096 mmcblk0boot0\n"
                    + " 179       64       4096 mmcblk0boot1\n";

    /** The names a head unit's /proc/partitions would carry; sizes are plausible. */
    static final String PARTITIONS_ROCKCHIP_NAMED =
            "major minor  #blocks  name\n\n 179 0 30535680 mmcblk0\n"
                    + " 179 1 4096 uboot\n 179 2 4096 trust\n 179 3 4096 misc\n 179 4 32768 resource\n"
                    + " 179 5 32768 kernel\n 179 6 65536 boot\n 179 7 65536 recovery\n"
                    + " 179 8 2097152 system\n 179 9 131072 vendor\n 179 10 26214400 userdata\n";

    static final String PARTITIONS_AB_SUPER =
            "major minor  #blocks  name\n\n 8 0 62914560 sda\n 8 1 65536 boot_a\n 8 2 65536 boot_b\n"
                    + " 8 3 8192 vbmeta_a\n 8 4 8192 vbmeta_b\n 8 5 6291456 super\n 8 6 40000000 userdata\n"
                    + " 7 0 20000 loop0\n 253 0 4096 dm-0\n";

    /** getprop dump: a head unit's identifying keys mixed with personal ones that must not leak. */
    static final String GETPROP_HEADUNIT =
            "[ro.product.manufacturer]: [Generic]\n[ro.product.model]: [rk3399_all]\n"
                    + "[ro.product.device]: [rk3399_all]\n[ro.product.board]: [rk30sdk]\n"
                    + "[ro.board.platform]: [rk3399]\n[ro.hardware]: [rk30board]\n"
                    + "[ro.build.fingerprint]: [rockchip/rk3399_all/rk3399_all:8.1.0/OPM2/20200101:userdebug/test-keys]\n"
                    + "[ro.build.version.release]: [8.1.0]\n[ro.build.ab_update]: []\n"
                    + "[ro.mcu.version]: [V2.3.1]\n[ro.mcu.baud]: [115200]\n"
                    + "[ro.boot.serialno]: [0123456789ABCDEF]\n[ro.serialno]: [0123456789ABCDEF]\n"
                    + "[ro.boot.verifiedbootstate]: [orange]\n[ro.boot.flash.locked]: [0]\n"
                    + "[ro.secure]: [1]\n[ro.debuggable]: [0]\n[ro.adb.secure]: [1]\n"
                    + "[ro.treble.enabled]: [true]\n[ro.bootloader]: [U-Boot 2017.09]\n"
                    + "[persist.sys.wifi.mac]: [aa:bb:cc:dd:ee:ff]\n[ro.build.host]: [mac aa:bb:cc:dd:ee:ff x]\n"
                    + "[ro.build.description]: [IMEI 123456789012345 leaked]\n"
                    + "[ro.boot.wifi.addr]: [11:22:33:44:55:66]\n[net.hostname]: [my-private-phone]\n"
                    + "[persist.sys.timezone]: [Asia/Kuala_Lumpur]\n[ro.product.name]: [rk3399_all]\n"
                    + "garbage line without brackets\n";

    /** The values that must never appear in a report. */
    static final String[] SECRETS = {"0123456789ABCDEF", "aa:bb:cc:dd:ee:ff", "11:22:33:44:55:66",
            "123456789012345", "my-private-phone"};
}
