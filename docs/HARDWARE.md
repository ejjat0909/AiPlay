# The hardware section of the diagnostic report

**Purpose.** One report pasted from the car unit should be enough for someone who has never seen it to judge
its chip, memory, storage, why it lags and whether it can be flashed, so as to choose between **debloating**
the stock firmware and **changing the firmware**. Open *Diagnostics*; the section sits between the
verdict and the log, and *Copy* / *Save & share* include it.

AiPlay reads what an ordinary app may read. Android withholds a lot of this from apps, and which parts depends on
the Android version and the unit. The report therefore says **for each reading whether it worked**, and for every
blocked one prints the `adb` command that fills the gap. A blank never means "fine".

## What is in it

| Section | Contents | Source |
| --- | --- | --- |
| **chip and board** | `SOC_MANUFACTURER`/`SOC_MODEL` (Android 12+), `BOARD`, `HARDWARE`, `DEVICE`, ABIs, kernel, **CPU core designs** (`4x Cortex-A53 + 2x Cortex-A72`), max clock, **GPU name**, display, and a *chip family* guess with the evidence behind it | `Build.*`, `/proc/cpuinfo`, `cpufreq`, an EGL context, `getprop` |
| **memory** | RAM total / available / in use, swap, CMA, Android's own figure, heap per app, the nearest marketed size | `/proc/meminfo`, `ActivityManager` |
| **storage** | each volume's size, free space, % full; the physical flash chip (eMMC / UFS / USB) and its raw size; eMMC **wear** | `StatFs`, `/proc/mounts`, `/sys/block` |
| **cpu load, clocks and heat** | load average, per-core clock and governor, every thermal zone, Android's thermal state, and an optional **CPU test** | `/proc/loadavg`, `/sys/devices/system/cpu`, `/sys/class/thermal`, `PowerManager` |
| **running processes** | the biggest processes by memory, when they can be read | `ps`, or root's `top`/`dumpsys meminfo` |
| **flashability** | bootloader lock and verified-boot state, A/B slots, dynamic partitions, recovery partition, vendor/MCU properties, vendor and updater packages, the partition table | `getprop`, `/proc/partitions`, the package list |
| **properties** | the identifying hardware/build/boot/vendor properties (`ro.product.*`, `ro.build.*`, `ro.mcu.*` ...) | `getprop` |
| **could not be read** | each blocked reading with the reason, and the `adb` commands that complete it | |
| **lag signals** | the facts above that commonly cause lag, named for you | |

### Why the core designs matter most

Head units frequently report generic or renamed values (`ro.board.platform` = `generic`, a fingerprint
copied from another model). A vendor can rename a board; it cannot rename what the CPU *is*. The kernel prints each
core's ARM part number and AiPlay translates it (`0xd03` is Cortex-A53, `0xd08` Cortex-A72 ...). Together with the
core count, the maximum clock, the GPU name and the RAM, that identifies the chip class even when every name lies.
The *chip family* line matches known name prefixes (Rockchip `rk*`/`px*`, MediaTek `mt*`, Unisoc `ums*`/`sc*`/`uis*`,
Allwinner `sun*i`, Qualcomm `msm*`/`sdm*`, Amlogic, Telechips, NXP, Intel) and prints the evidence it matched. It
never invents a model, flags a unit whose identifiers contradict each other, and lists placeholders it ignored.

### The CPU test (a button, never automatic)

Idle clock and temperature say nothing about throttling: an idle governor clocks down anyway. *Run CPU test*
spends 8 seconds: a 1 s warm-up (so the runtime has compiled the loop; without it three threads appeared to do
6.5x one core's work), 2 s on one core, then 5 s on every core, recording the work done per second, the average clock and
the temperature. A chip that throttles does less in its last seconds than its first; the verdict is *steady*,
*rose* (warming up), *likely thermal throttling* (work fell 15% or more **and** the temperature rose 8 C or the clock
fell 10%), or *work fell but no thermal evidence could be read* (it could be another app). It is a **short** test:
throttling that needs minutes of heat soak will not show. The numbers are loop iterations, not a standard benchmark:
compare runs of the same unit, not against published scores. It loads the unit and the screen may stutter.

## What an app cannot read (found on Android 14 and 11 emulators)

| Reading | App on Android 14/11 | Over `adb` |
| --- | --- | --- |
| `/proc/cpuinfo`, `/proc/meminfo`, `Build.*`, `getprop` (most), `StatFs`, GPU name | readable | readable |
| `/proc/loadavg`, `/proc/partitions` | **denied** | readable |
| `/sys/block` (flash chip, wear), `/sys/class/thermal` (chip temperature) | **denied** | readable |
| `cpufreq` clocks | emulator reports a placeholder | readable on most units |
| other apps' processes and memory | **hidden** (`/proc` is mounted `hidepid`) | `ps`, `top`, `dumpsys meminfo` |
| `ro.secure`, `ro.debuggable` | hidden from apps | readable |
| eMMC `cid`/`csd` registers | **never read**: they contain a serial number | |

Whether *your* unit allows more is exactly what the report will show. Older and looser head-unit ROMs often do.
With root (`su`) the process list, `top` and `dumpsys meminfo` are filled in for real.

## Privacy

The report gets pasted into chats. Properties are filtered by an allowlist of hardware/build/boot/vendor prefixes
**and** a denylist (serial numbers, IMEI, MAC and Bluetooth addresses, Wi-Fi names, phone numbers, account words);
any MAC address or 14-16 digit number inside an allowed value is replaced. The package list is **not** dumped: the report
counts packages and names only head-unit vendor prefixes (`com.syu.`, `com.microntek.`, `com.nwd.`, `com.hzbhd.`,
`com.hct.`) and update-related names (`update`, `upgrade`, `fota`, `ota`). A test fails if any of a set of planted
secrets reaches the report. Read the report once before sharing it anyway; the vendor prefixes are a pointer, not proof.

## Reading it: debloat or firmware?

The report gives **signals**, not a verdict; the decision is yours (or whoever reads it).

* **Lags and RAM is small, or swap is heavy, or storage is nearly full:** debloat and free space first. Cheap, reversible.
* **Chip is old-generation cores (A7/A9), few cores, a low clock, 32-bit only:** debloating buys little; the hardware is the limit.
* **CPU test says thermal throttling, or temperatures are high:** a cooling or placement problem; no software fixes heat.
* **eMMC wear beyond rated life or spare blocks at warning:** the flash is dying; slowness and corruption follow.
* **Flashing:** the report shows the bootloader state, layout and vendor family. It **cannot** show whether the unit has a
  recovery or SD/USB update mode or the vendor's procedure; that comes from the unit's own settings, the vendor, or
  trial on a unit you can afford to lose. Do not flash firmware meant for another board.
* A signal is only raised from something that was read. "No signal" with a long "could not be read" list means *unknown*.
  Run the `adb` commands the report prints and paste their output next to it.

## Verified, and not

* **Tests (64):** parsers on representative inputs (ARM64 big.LITTLE, 32-bit A7, Unisoc-style, x86, 4 GB and 1 GB meminfo, toybox and
  old `ps`, Rockchip-style and A/B+super partition tables, a `getprop` full of planted secrets), chip matching,
  throttling rules, flash wear decoding, lag and flashability rules, adb fallbacks, and the whole report formatted. The fixtures
  follow the formats the kernels print; they are **not captures from a real unit**.
* **Emulators (API 34 and 30):** the section renders on the real Diagnostics screen; every blocked read is named; the CPU test
  runs, counts down on the button and rebuilds the report. With SELinux set permissive the real code read the load average,
  a thermal zone and the partition table.
* **Not run on any real device:** reading the eMMC chip and its wear, real `cpufreq` and chip thermal zones, the system-wide
  process list, the root path (`top`, `dumpsys meminfo`), and the vendor-package pointers. Those are covered by the parser tests only.
* The chip-name patterns and ARM part numbers come from public knowledge, not from a database of head units; a family
  that is not matched shows as "not recognised", not as a guess.


## The first real unit (alps 8227L_demo, firmware JCAC10003-OC2)

What its report showed, and what it taught the report:

| Reading | Value | What it means |
| --- | --- | --- |
| Android | label "10", fingerprint "6.0", **API 27** | the firmware's version labels disagree with each other; API 27 is Android 8.1 |
| CPU | 4x Cortex-A7, 1.12 GHz, Mali-450 MP, 32-bit only | an old-generation chip; AC82xx is Autochips, on MediaTek-style firmware |
| RAM | MemTotal 4.00 GiB, 93% in use, swap 43%, load 13 to 19 on 4 cores | the figure is suspiciously round and the behaviour is that of a much smaller RAM |
| Storage | /data 32.00 GiB on a 14.68 GiB eMMC | the 32 GB is not real |
| Flash | eMMC 70-80% of rated life used | the flash is well worn |
| Heat | 49 C CPU, 64 C PMIC, no throttling in the CPU test | heat is not the problem |
| Openness | `ro.secure=0`, `ro.debuggable=1`, SELinux permissive, userdebug build | `adb shell` is very likely root, so everything the report could not show is readable over adb |

The process list could not be captured (Android shows an app only its own processes): run
`adb shell top -b -n 1 -m 15` and `adb shell dumpsys meminfo` and compare.
