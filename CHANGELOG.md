# Changelog

All notable changes to AiPlay. Format follows Keep a Changelog; versions follow SemVer.

## [0.2.0-alpha10] - 2026-10-23

First measurements of the Bluetooth bootstrap on a real unit (alps 8227L_demo, API 27) with a real phone
(OPPO CPH2739, Android 16). The phone was paired and connected over A2DP, Hands-Free and AVRCP, and AiPlay
said "Bluetooth: waiting for a phone" indefinitely. A Bluetooth HCI capture (`btsnoop`) showed why: the phone
asked the unit for Hands-Free, AVRCP and A2DP-sink service records and **never searched for the Android Auto
service** (`4de17a00-52cb-11e6-bdf4-0800200c9a66`), and never opened an RFCOMM connection.

### Cause found

* **Receiver off at pairing time.** `auto_start_receiver` defaulted to false, so after a fresh install or a
  data wipe AiPlay only started when someone opened it, *after* the phone had paired. Android caches a paired
  device's services at bonding, so the phone kept a picture of a unit with no Android Auto service.

### Changed

* **The receiver now starts at boot by default** (`Prefs.autoStartReceiver()` is true), so the service is
  registered before anyone pairs.
* **Pairing advice** (`core/bt/PairingAdvisor`, tested): the Bluetooth line says what to do next (pair now,
  re-pair, open Android Auto) and the log carries the longer explanation. A bond created while the service
  was down is flagged.
* **Bluetooth being switched off and on** re-registers the service (it used to fail once and stay failed); a
  dead server socket is reopened after 3 s instead of leaving the receiver silently deaf.
* **Events logged under `bluetooth:`**: pairing started/finished/removed (and whether the service was up),
  link up/down, scan mode, paired devices at start.
* **Optional BLE advertisement** of the Android Auto UUID (`BleAdvertiser`), as the dongle's "dongle mode"
  does. It is skipped, with the reason in the log, on units whose Bluetooth has no LE peripheral mode, which
  includes the unit above (`android.hardware.bluetooth_le` is absent).

### Fixed: the phone's `WifiConnectStatus = -3`

* **`bssid` was sent empty** for the unit's own Wi-Fi Direct network. The phone answered
  `WifiConnectStatus = -3` 13 ms after its `WifiStartResponse` (too fast to be a join attempt) and never
  opened TCP. With the group owner's address filled in (`WifiP2pGroup.getOwner().deviceAddress`, equal to the
  supplicant's `bssid`) the phone answers `0`, joins, and **opens the TCP connection to port 5277**.
  First measured contact between a real phone and the receiver.

### Measured: where the session now stops

Real phone (Android 16, Android Auto): TCP accepted, `VERSION_REQUEST` answered (phone speaks 1.7, status 0),
TLS started with the head unit as client, the phone's first handshake flight answered with AiPlay's
certificate flight (1168 B) and then **the phone closes the connection within 50 to 300 ms, every time**,
with no alert read. **Confirmed by the phone** (OPPO CPH2739, Android Auto): "Communication error 7 - Your car's
software didn't pass Android Auto security checks." That is the open question in docs/PROTOCOL.md 2.4,
answered: a current phone rejects AiPlay's self-signed head-unit identity.

### Still unverified

* Whether the phone, once it has paired with the service present, opens the RFCOMM connection. That is the
  next measurement.
* Whether the phone accepts AiPlay's self-signed identity after the Wi-Fi step (docs/PROTOCOL.md 2.4).

## [0.2.0-alpha9] - 2026-10-22

The first report from a real head unit (alps 8227L_demo, API 27, 32-bit quad Cortex-A7, firmware
JCAC10003-OC2) showed that the hardware section got that unit wrong or left it blank in several places.
All of them are fixed and pinned by `RealUnitTest`, which uses the values from that report.

### Fixed (each one was wrong or empty on the real unit)

* **Five cores instead of four.** Older 32-bit kernels start `/proc/cpuinfo` with `Processor : ARMv7 Processor
  rev 3 (v7l)`, a description of the CPU family; it was counted as a core. That also hid the "old-generation
  cores" verdict (the fake core had no known design) and inflated the load-average comparison.
* **Chip family "not recognised".** Added Autochips (AC82xx) and a note when the firmware is built on
  MediaTek's BSP (`ro.mediatek.*`, `ro.mtk_*`, `mtk*` thermal sensors): the SoC is a MediaTek part or a design
  derived from one, whatever name it carries.
* **"Android 10" taken at face value on an API 27 build.** The unit's version string says 10, its build
  fingerprint says 6.0 and its vendor fingerprint says 10, while the API level is 27 (Android 8.1). The report
  now names the release the API level really is and lists every claim that disagrees. The Home screen summary
  said "Android 27" (an API level labelled as a release); it now says "API 27 = Android 8.1".
* **Sizes that cannot be real are called out.**
  * `/data` reported 32.00 GiB on an eMMC chip of 14.68 GiB: a filesystem cannot be bigger than its chip.
  * `MemTotal` of 4.00 GiB (99.9% of a round 4 GB): a real kernel reports noticeably less than the installed
    RAM, so a round figure looks set by the firmware. Combined with 93% in use it says the unit behaves like
    it has less RAM than it claims, and the report prints the `adb` commands that can check it
    (`/proc/iomem`, `dmesg`).
* **"recovery partition: none listed" for a table that is only numbered.** `/proc/partitions` on that kernel
  lists `mmcblk0p1`..; the labels live in `/dev/block/.../by-name`. The report now reads those symlinks when it
  can, says "unknown" when it cannot, and always lists the largest internal partitions (it used to end the
  section in a blank line for any table with unrecognised names).
* `ro.boottime.chg_mcu_val` was shown as a "vendor/MCU property"; it is a boot-time entry of an init service,
  not a version, and is now labelled so.

### Added

* eMMC wear as a number (`70-80%` -> at least 70%), a signal when it is at least 70%, and a stronger one when
  swap lives on that flash instead of zram; swap devices are read from `/proc/swaps`.
* 20 new tests (255 total): `RealUnitTest` and the new classes `AndroidVersion` and `StorageCheck`. Five
  mutations (header counted as a core, Autochips removed, version check off, storage check off, numbered table
  claiming "none listed") each fail 2-5 tests.

### Verified

* Android 8.1 (API 27, x86) emulator, the closest I can get to the unit's Android level: `device-test.sh` exit 0,
  all 25 checks (video, three audio streams, touch/keys/swipe at the right coordinates, day/night, keep-alive
  pings); `ignition-test.sh` 11 of 11 with no battery exemption (including a real reboot), as expected since the
  background-start restriction only starts at Android 12. The Diagnostics report renders and its labels are
  consistent (no false "conflict").
* **Not** verified: any of this on the unit itself beyond the report it produced. The `RealUnitTest` cpuinfo core
  lines are reconstructed (only the header shape and the Cortex-A7 part number are known from the report).

## [0.2.0-alpha8] — 2026-10-22

A hardware section for the Diagnostics report, so one report from the car unit is enough to choose between a
debloat and a firmware change. Verified on emulators; **not yet on a head unit**, which is what it is for.

### Added

* **Hardware section** (docs/HARDWARE.md), on the Diagnostics screen and in *Copy* / *Save & share*:
  * *chip and board*: `SOC_MANUFACTURER`/`SOC_MODEL`, `BOARD`, `HARDWARE`, ABIs, kernel, the **CPU core designs**
    from `/proc/cpuinfo` (the evidence a vendor cannot rename), max clock, the GPU name, and a chip-family match
    that prints its evidence, flags contradictions and lists the placeholders it ignored;
  * *memory*: total / available / swap / CMA from `/proc/meminfo` and Android's own figure, with the nearest
    marketed size and a warning when the two totals disagree;
  * *storage*: size, free space and % full per volume, the physical flash chip and its raw size, and eMMC wear
    decoded from `life_time` / `pre_eol_info` (the serial-bearing `cid`/`csd` are never read);
  * *cpu load, clocks and heat*: load average, per-core clock and governor, every thermal zone, Android's thermal
    state, and a **CPU test** button (8 s: warm-up, one core, all cores) that records work, clock and temperature each second
    and says whether the chip throttled;
  * *running processes*: the top by memory when readable (root, or a permissive ROM), otherwise a plain statement and the `adb`
    commands to run;
  * *flashability*: bootloader and verified-boot state, A/B and dynamic partitions, recovery partition, vendor/MCU properties
    (`ro.mcu.*`), vendor and updater packages, the partition table, and a statement of what cannot be determined from the OS;
  * *properties* (privacy-filtered), *could not be read* (each with the reason and the `adb` command that fills the gap), and
    *lag signals* (the facts that commonly cause lag, not a verdict).
* Permission `QUERY_ALL_PACKAGES`, only to notice head-unit vendor and updater packages; the full app list is never printed.
* 71 new tests (235 total).

### Fixed (found when a test run went wrong, not part of the hardware work)

* **A quiet phone was dropped after 15 seconds.** The receiver closed a session after 15 s without data and never
  pinged the phone itself; a phone showing a still map could send nothing for that long. OpenAuto (read, not
  recalled) does the opposite: the **head unit pings every 5 s and quits only when two pings in a row go
  unanswered**. AiPlay now does the same (`AaSession.tick`, first ping one interval after the session is up,
  encrypted, answered pings reset the count); a TLS handshake that never finishes is still abandoned after 15 s.
  Found because the emulator had been left in night mode, so my test's "switch to night" step did nothing, the
  probe sat silent and the receiver hung up. Tests: 5 session rules (interval, answering phone never dropped,
  silent phone dropped after the second unanswered ping, a still map is not a dead phone, handshake timeout), and
  2 over a real socket (silent-but-answering phone stays; non-answering phone is dropped after exactly two pings).
  On the emulator: a phone silent for 30 s while answering stayed connected (6 pings); mutations (drop on the first
  unanswered ping, never drop, pongs not counted, first ping immediate) each fail 2-3 tests.
* `tools/device-test.sh` now resets night mode first; a leftover night mode made its night step a no-op.
* **A 12-line stack trace in every report** when `su` exists but this app may not run it (`error=13`, common on head
  units): `PrivilegedShell` now logs one line (`su cannot be run by this app (permission denied)`). Seen at the end of
  the report on the Android 11 emulator. The Diagnostics intro now says what is filtered (serial numbers, MAC
  addresses) and that only vendor/updater package names are listed.

### Found and fixed while testing the hardware section

* A clock of "0.00 GHz" was reported as a slow chip: the emulator reports a placeholder, and the code believed it. Clocks under
  100 MHz are now rejected and the reading is listed as unusable.
* The process list showed `ps` and `sh` (the commands the app itself ran) and looked system-wide. It now requires
  system processes before calling a list real.
* The CPU test made three threads look like 6.5x one core: the one-core phase ran while the runtime was still compiling the
  loop. A 1 s warm-up fixed it (now 2.7x to 2.9x).
* `rk30sdk` (a real Rockchip board name) was not matched by the Rockchip pattern.
* `unknown` placeholders (`ro.bootloader = unknown`) were printed as values.
* The thermal-zone gap was hidden whenever the battery sensor was readable.

### Verified

* JVM: 235 tests.
* Emulators API 34 and 30: the section renders on the real Diagnostics screen; the CPU button counts down and rebuilds the
  report; every blocked read is named. With SELinux permissive the real code read `/proc/loadavg`, a thermal zone and
  `/proc/partitions`.

### Not verified

* Everything specific to a real unit: eMMC chip and wear files, real `cpufreq` and chip thermal zones, the system-wide process
  list, the root path, vendor packages. The parsers for them are tested on representative inputs, not captures.
* What *your* unit lets an app read. That is the point of running it there.

## [0.2.0-alpha7] — 2026-10-21

Ignition (ACC) start, and the Start button's feedback fixed for real. Verified on emulators; **no real head
unit has been tried**, and which ignition signal a unit sends is unknown.

### Added

* **Start on ignition** (`docs/IGNITION.md`). Settings: *Start when the ignition turns on*, *Stop when the
  ignition turns off* (off by default), and *This unit's ACC signals* for a unit that broadcasts its own
  action. AiPlay reacts to `BOOT_COMPLETED`, `QUICKBOOT_POWERON` (Android's and HTC's spelling, manifest, so it
  works with the process dead), screen on, unlock, car mode, and the typed actions. A wake that fires several
  signals starts the receiver once (10 s debounce); an explicit off ends that wake-up, so an ACC drop while
  cranking restarts it. `SCREEN_OFF` is not an off signal. Decisions are in `IgnitionPolicy` (12 tests).
* **Background start** row in Settings: shows whether AiPlay is exempt from battery optimization and opens the
  system prompt to allow it. Needed on Android 12+, where a background app may not start a foreground service.
  A refusal now reads "Android does not let an app in the background start this. In AiPlay's Settings, allow
  background start (battery optimization), or open AiPlay and press Start." instead of an exception name.
* `tools/ignition-test.sh`: drives the real Settings screen, sends the signals, finishes with a real reboot.

### Fixed

* **The Start button's "Starting…" was never drawn.** The service's `onStartCommand` runs on the main thread
  and did all the startup there (keystore, port, mDNS, Bluetooth: ~2.5 s on a slow unit), so the screen froze
  before the new state was painted and the spinner never appeared. Startup now runs on its own thread; the
  screen updates 22 ms after the state change and the spinner is visible for the whole start (checked frame by
  frame on a screen recording). This was also an ANR risk on a slow head unit.
* The "port in use" message is plain: "port 5277 is already in use by another app. Close it, or change the
  receiver port in Settings."
* `IgnitionPolicy` first treated an off-then-on within the debounce window as one wake-up; a test whose own
  name said otherwise showed it was wrong.

### Verified

* JVM: 164 tests; mutations (no reset on stop, no debounce) each fail 2 tests.
* Emulator, Android 14: with the app exempt, all 13 checks pass from a genuinely background app: custom ACC
  on/off, car mode on/off, screen wake, `QUICKBOOT_POWERON` to a dead process, a real reboot. Without the
  exemption, 11 of 11: every signal is decided on, the background start is refused with the explanation, and a
  real boot still starts (boot is exempt). Settings row: tap, system prompt, Allow, row shows "allowed".
* Emulator, Android 11: `ignition-test.sh` 11 of 11 with no exemption (the background restriction does not exist
  before Android 12): custom ACC on/off, screen wake, `QUICKBOOT_POWERON` to a dead process and a real reboot all
  start the receiver. Car mode is skipped there (no `cmd uimode car` before Android 12). `device-test.sh` also
  passes in full on Android 11 (touch, keys, sensors, all three audio streams).
* The first Bluetooth RFCOMM service start on an Android runtime: `listening for the phone on RFCOMM service
  4de17a00-...` (no phone to connect, so only the listening half).

### Not verified

* **Any real head unit.** If your unit's ACC produces none of these signals, nothing here helps; use the
  "finding your unit's own ACC action" steps in `docs/IGNITION.md`.
* Units that kill background apps while parked will not deliver the runtime signals (boot and quick-boot still
  arrive).

## [0.2.0-alpha6] — 2026-10-21

Start-button feedback, touch input, sensors and audio. Everything is verified on an emulator against a stand-in
phone; **none of it has run against a real phone.**

### Added

* **Start button feedback.** Pressing *Start wireless receiver* now shows a spinner and "Starting…" (the button
  is disabled meanwhile), then "Receiver is ready", or the reason it failed: port in use, the system refusing the
  service, a 10 s timeout. The receiver line shows the Bluetooth state too ("waiting for a phone", "phone
  connected: …", "unavailable: …"). New `ReceiverState`.
* **Touch and keys, head unit -> phone.** The projection screen sends taps, drags and (multi-pointer) touches
  mapped to the video rectangle, and D-pad/Enter/media keys. A gesture starting on a letterbox bar is ignored.
  Sent from their own thread; surplus drags are dropped when the network lags.
* **Sensor channel**: driving status (always unrestricted) and night mode, pushed again when the unit's UI mode
  changes.
* **Audio**: media (48 kHz stereo), speech and system sounds (16 kHz mono), audio-focus and navigation-focus
  answers, per-channel `AudioTrack` playback fed through a bounded queue so a slow device cannot stall video or
  input, and media ducking while a prompt plays.
* `tools/verify-input.py`, `tools/input-geometry.py`; `aa-probe.py` rewritten around a non-blocking reader so it
  can stream video and three audio channels at once and receive the touches and keys the head unit sends.
* 38 new tests (153 total).

### Fixed (found by the device tests)

* **Audio tail wait was wrong.** The player called `stop()` and then read the playback position; Android reports
  0 for a stopped track, so the player always waited its full 2 s deadline before ending, which also kept the
  music ducked 2 s too long, and logged "0 played". It now waits while the track is still playing.
* **Service discovery** now offers all six channels with their real ids (video 3, media 4, speech 5, system 6,
  input 1, sensor 2), with per-channel audio formats; HOME and BACK are no longer advertised as input keys,
  because they are not forwarded.
* The projection screen no longer restarts on a day/night switch (`uiMode` in `configChanges`).

### Corrected

* My earlier plan had touch **backwards** (the phone sending touches to AiPlay). In Android Auto the head unit
  captures input and sends it; the phone only binds the keys it wants (`docs/PROTOCOL.md` 9.1).

### Verified

* JVM: 153 tests. A concurrency test sends touch from a second thread while the session thread writes media
  acks over the same encrypted stream; without the write lock it failed in 2 of 3 runs (it is a race, so it is
  probabilistic).
* Probe (OpenSSL phone) against the desktop harness: all 10 CHECK lines, video byte-identical.
* Emulator API 34 (XL VM): `device-test.sh` exit 0 on 1920x1080, 1024x600 (bars above/below) and 1280x480 (bars
  at the sides): taps and both video corners map to the right phone coordinates, the swipe arrives as press,
  ~30 drags, release, the key as down/up, night mode reaches the phone, exactly four presses (bar tap not sent),
  every audio frame written and played, music ducked and restored.

### Not verified

* A real phone. Whether a phone accepts the sensor set, the audio-focus answers, the touch coordinate space and
  the ducking trigger is unknown.
* **No microphone**: "OK Google" through the head unit does not work. Two-finger gestures are untested.
* Audio is checked down to AudioFlinger's mixer, not by ear.

## [0.2.0-alpha5] — 2026-10-21

Bluetooth bootstrap: how a phone finds the receiver. **Nothing here has run against a real phone or
real Bluetooth hardware**; see "Not verified".

### Added

* **Bluetooth bootstrap** (`core/bt/WifiBootstrap`, pure Java): the head-unit side of wireless Android
  Auto's RFCOMM exchange. The unit sends `WifiStartRequest {ip, port}`, answers the phone's
  `WifiInfoRequest` with `WifiInfoResponse {ssid, key, bssid, security, ap type}`, then reads the phone's
  last two messages. Service UUID `4de17a00-52cb-11e6-bdf4-0800200c9a66`, frame
  `[size u16][id u16][protobuf]`. Details and sources: `docs/PROTOCOL.md` section 8.
* **Android glue**: `BluetoothBootstrapServer` (RFCOMM server, 30 s watchdog, permission/adapter checks that
  say what is wrong), `WifiConfigProvider` (own IPv4 address, SSID, BSSID), started and stopped with the
  receiver. A receiver without Bluetooth still works; it just cannot be found by a phone.
* **Settings > Wi-Fi for the phone**: SSID and password to hand to the phone. Android will not reveal the
  password of the network the unit is on, so it is typed once; it is stored unencrypted in the app's private
  preferences. With no SSID set, the Wi-Fi details are not sent.
* Permissions: `BLUETOOTH_CONNECT` (Android 12+, requested with the notification permission),
  `BLUETOOTH`/`BLUETOOTH_ADMIN` (up to Android 11), `ACCESS_FINE_LOCATION` (only to read the access point's
  BSSID). Starting the receiver proceeds whatever the answers.
* 14 new tests (115 total).

### Fixed

* **A slow first launch could bury the projection screen.** On a freshly booted, slow device the splash
  screen's delayed hop to Home landed after the phone had started streaming and covered `ProjectionActivity`,
  so nothing rendered. This is the "first run after a fresh boot" failure that had been reported as
  unexplained since 0.2.0-alpha3. The splash now does not open Home while a phone is streaming.

### Verified

* JVM: 115 tests. Mutation checks on the new code: wrong field number for `security_mode` (1 test fails),
  size field counting the header (7 fail), sending Wi-Fi details with no SSID (1 fails).
* Emulator (API 34, XL VM), fresh boot + first run, which is the slow case: the same build failed on 2 of 3
  fresh boots before the fix and passed 3 of 3 after it; in one of those the log shows the new
  "a phone is already streaming; not opening Home" line and the projection screen displaying. Warm runs: 6 of 6,
  every screenshot shows the test pattern. Small samples.
* On the emulator the Bluetooth service reports `bluetooth: bootstrap unavailable: ...` and the receiver is
  otherwise unaffected.

### Not verified (and likely to matter)

* **The RFCOMM service has never run on real hardware**, and no phone has ever connected to it.
* **The phone may never connect.** The reference dongle this was modelled on also advertises the UUID over BLE
  and connects a headset profile to the phone; AiPlay only listens. Whether that is enough is unknown
  (`docs/PROTOCOL.md` 8.2).
* The whole design rests on one reference implementation (aasdk/OpenAuto do not cover this exchange), and the
  content of the phone's last two messages is unknown.
* The self-signed head-unit certificate question (2.4) is still open.

## [0.2.0-alpha4] — 2026-10-21

The transport rework. Reading aasdk's sources for it showed that AiPlay's wire format was still wrong
in **four** places, three of them in what 0.1.3 had called "the real framing". None of them could be
seen by AiPlay's own tests or probe, because both agreed with AiPlay. Details: `docs/PROTOCOL.md` 2.2–2.4.

### Fixed (each would have stopped a real phone)

* **TLS was on the socket; real Android Auto carries it inside the protocol.** The receiver is now a
  plain TCP listener (`AaServer`, replacing `AaTlsServer`). The head unit is the TLS *client*: after the
  version exchange it runs TLS 1.2 in `SSL_HANDSHAKE` messages (`AaCryptor`, an `SSLEngine`), sends
  `AUTH_COMPLETE` in the clear, and from then on every frame is encrypted — each frame's payload chunk
  as its own TLS record, with the FIRST frame's `total` counting plaintext.
* **`VERSION_REQUEST`/`VERSION_RESPONSE` are raw big-endian u16s, not protobuf** (`00 01 00 01`, not
  `08 01 10 06`), and the announced version is aasdk's 1.1, not an unsourced 1.6.
* **Channel-0 messages carried the `CONTROL` flag; aasdk sends them without it.** The flag is only for
  channel open/close on non-zero channels. Dispatch is now by channel and message id.
* **A channel open on the control channel is no longer accepted** (a real phone sends it on the channel
  it wants).

### Added

* `AaCryptor`, `AaFrameCodec.Cipher`/`Listener`, `AaFrame.encrypted`; the parser delivers each message
  as it completes so the cipher can be switched on between two messages of one read.
* `tools/aa-probe.py` rewritten as a faithful phone: plain TCP, in-band TLS **server** (OpenSSL via
  `ssl.MemoryBIO`), per-frame encrypted records, and `WIRE MISMATCH` checks on the flags, version
  encoding and which messages are encrypted. `tools/AaHarness.java` follows. `tools/fixtures/
  probe-phone.pem` is a throwaway test certificate for the probe's TLS server.
* 14 new tests (101 total): `AaTransportTest`, `AaCryptorTest`, encrypted-codec tests.

### Not done / unverified

* **A real phone has never connected.** Whether it accepts a self-signed head-unit certificate is
  unknown (PROTOCOL.md 2.4). AiPlay deliberately does **not** use the certificate embedded in aasdk: it
  was issued to JVC Kenwood by Google Automotive Link and its private key is public, which makes it
  someone else's head-unit identity.
* The phone still has no way to find AiPlay: wireless Android Auto uses a Bluetooth exchange to hand the
  phone the head unit's Wi-Fi address and port. That is the next milestone.

### Verified

* JVM: 101 tests. **Mutation checks** (each change made, the suite run, the change reverted): stock key
  manager instead of `AlwaysPresent` — 6 tests fail; `CONTROL` flag back on channel 0 — fails; encryption
  switched on before auth-complete — fails; parser never given the cipher — fails. The first version of
  the client-certificate test did *not* fail under the stock key manager, because the fake phone advertised
  no issuers; it now advertises one that excludes AiPlay's certificate, like a real phone.
* Cross-implementation: probe (OpenSSL TLS server) against the harness (JSSE client): 90/90 pictures
  acked, stream byte-identical to the clip.
* Emulator (API 34, XL VM, no swap): `device-test.sh` 10/10 exit 0, every screenshot shows the test
  pattern, `TLS up: TLSv1.2 TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384` on Android's own TLS stack.

### Changed

* `device-test.sh` waits for the receiver by reading the head unit's first two bytes (`00 03`, the
  version request) instead of doing a TLS handshake on the socket.

## [0.2.0-alpha3] - 2026-10-21

Found by the user on an API 34 emulator (`c2.goldfish.h264.decoder`): the decoder started, took 15 of
90 pictures, dropped the other 75 and never rendered. Reproduced on an API 34 x86_64 emulator
(0-4 pictures fed, no picture).

### Fixed

* **A slow decoder lost the whole stream.** `VideoDecoder` waited 20 ms for an input buffer; on a miss
  it dropped the picture and then discarded everything until the next keyframe, so one short stall
  became a permanent one (and a phone sends keyframes rarely). It now waits up to 1.5 s, draining the
  decoder's output while it waits, and only then resyncs. Because the ack is sent after the picture is
  handed over, a slow decoder now slows the phone down instead of being flooded.
* **Pictures that arrived before the screen existed were thrown away.** The projection screen can take
  seconds to appear on a slow unit. Pictures since the last keyframe are now buffered (up to 120 pictures
  / 8 MB) and replayed, off the UI thread, when the surface attaches.
* The stop line reports input stalls (`decoder stopped: 90 fed, 0 dropped, 13 input stall(s)`), so a slow
  decoder is visible in the log.

### Changed (test rig and counters, same version)

* `device-test.sh` takes its screenshot right after `first picture rendered` instead of 2 s in (on a slow
  decoder that caught the home screen in 7 of 10 runs), and pre-confirms Android's "Viewing full screen /
  Got it" box (`settings put secure immersive_mode_confirmations confirmed`) so it does not sit on the
  picture. That box is a system dialog shown the first time any app goes immersive; it is not an AiPlay
  bug and the video renders underneath it, but on a real unit it needs one tap.
* Pictures discarded while waiting for the screen (superseded by a newer keyframe) now count as dropped, so
  `fed + dropped` adds up to the pictures received. Before, runs printed `60 fed, 0 dropped` for 90 sent.

### Verified

* API 34 x86_64 emulator on an XL VM (8 GiB, no swap, `c2.goldfish.h264.decoder`), 10 runs of
  `device-test.sh` before the rig change and 10 after: 20 of 20 exit 0. After the change all 10 screenshots
  show the full test pattern (`90 fed, 0 dropped` in each).
* The earlier Large-VM run that did not render was not reproduced and is still unexplained; that emulator
  was running on swap.
* Not verified: real hardware, and the user's `emulator-5556`.

## [0.2.0-alpha2] — 2026-10-21

First run on a real Android runtime (API 30 emulator on a Large VM). Two bugs that only a device could
show, both of which would have stopped a head unit.

### Fixed

* **The receiver did not start on Android 11 and older.** `receiver_identity.p12` had been written
  with a current JDK's default PBES2 encryption, which Android before ~API 31 cannot read
  (`SecretKeyFactory 1.2.840.113549.1.5.12 not available`). It is regenerated with the legacy
  encryption, and `ReceiverIdentityTest` fails the build if PBES2 returns. API 34 had hidden this.
* **The H.264 decoder did not start on decoders that reject `KEY_LOW_LATENCY`** (the emulator's
  goldfish codec: `decoder can not set low-latency to 1 (err -1010)`; likely also some head-unit
  SoCs). The hint is now tried first and retried without it.

### Verified on a device

* API 30 emulator (`sdk_gphone_x86_64`, software GPU): `tools/device-test.sh` exits 0 — receiver up,
  90 of 90 pictures acked at 30 fps, `OMX.android.goldfish.h264.decoder` started, first 1280x720
  picture rendered, and the mid-stream screenshot shows the decoded test pattern letterboxed 16:9.
* Not yet: dropped pictures. On that emulator (memory-starved, running on swap) only 37 of 90 pictures
  were decoded before input timeouts forced a resync to the next keyframe. Real hardware needs a look.

## [0.2.0-alpha1] — 2026-10-21

First step of 0.2.0 (projection): the video channel. **Not yet usable with a real phone** — see
"Known gap".

### Added

* **Video channel** (`AaAv`, `AaSession`): `ChannelOpenRequest` on the video channel is answered `OK`
  on that channel, then `SETUP_REQUEST` → `SETUP_RESPONSE` (OK, max_unacked 1, config 0),
  `VIDEO_FOCUS_REQUEST` → `FOCUSED`, `START`/`STOP`, and every media picture is delivered to a
  `VideoSink` and acked. Large pictures survive split framing.
* **Decoder and screen:** `VideoDecoder` (H.264 Annex-B → `MediaCodec` → `Surface`; remembers SPS/PPS,
  waits for an IDR) and `ProjectionActivity` (letterboxed 16:9 `SurfaceView`). `H264` helper finds
  SPS/PPS/IDR in a chunk.
* **Probe streams a clip:** `tools/aa-probe.py` now plays the phone's side of the video channel with
  `tools/fixtures/test-720p30.h264` (3 s, 90 pictures, 258 KB). `device-test.sh` additionally checks
  that a picture was rendered and saves a mid-stream screenshot to `build/device-test-video.png`.
* 22 new tests (85 total): wire vectors, session routing, split framing, H.264 NAL scanning.

### Fixed

* **Channel ids in service discovery** were 1–4 and did not match the frame channel ids
  (`AaChannel`: video 3, media audio 4, …). Now they do.
* **Channel open** is answered on the channel it arrived on (a phone sends it there), not always on
  control. A control-channel request is still accepted.
* Only channels the receiver can serve are advertised (video). Audio/input descriptors exist but are
  off until those channels are built.

### Known gap (found while verifying against aasdk)

AiPlay runs a TLS *server* on the TCP socket. Real Android Auto uses **plain TCP with TLS inside the
protocol (`SSL_HANDSHAKE`), the head unit being the TLS client**. The tests and probe agree with
AiPlay, not with a phone, so a real phone cannot connect yet. Wireless AA also needs a Bluetooth
bootstrap. Both are roadmap items; details in `docs/PROTOCOL.md` 2.2.

## [0.1.5] — 2026-10-21

Clean-up from the first green emulator run (0.1.4 passed; two log lines looked wrong).

### Fixed

* **`SSLHandshakeException` from a "first session".** `device-test.sh` waited for the receiver by
  opening a bare TCP connection. `adb forward` accepts that connection on the laptop whether or not
  the device is listening (so the wait proved nothing), and the receiver then logged a failed TLS
  session for it. The wait now does a real TLS handshake through the forward, and the receiver
  logs a handshake that never completes as `session N: TLS never completed (port probe or
  incompatible client): <reason>` instead of an unlabelled exception.
* **`splash: watchdog fired` was unexplained.** The watchdog is the intended safety net for an
  animation that never finishes (screen off or locked, animations disabled — common on a headless
  emulator). It still fires; its log line now says why.

## [0.1.4] — 2026-10-21

First run on an emulator (API 34, `sdk_gphone64_arm64`). The receiver worked; the script that
was supposed to start it did not.

### Fixed

* **`device-test.sh` could not start the receiver.** `AaReceiverService` is `exported="false"`
  (correctly), so `adb shell am start-foreground-service` is refused and the script then probed a
  dead port. The launcher activity is exported, so the app now starts its own receiver when it is
  launched with `--ez aiplay.start_receiver true`, and the script waits for the port to accept a
  connection before probing — with an explicit message instead of an SSL EOF.
* **Emulator `gearhead` is a stub.** `1.2.531830-stub` is installed and enabled in the
  `google_apis` images but cannot project; the capability row now says so (WARN) instead of OK.

### Verified

* Full control exchange on an emulator: TLS 1.2, version 1.6, auth, service discovery
  (193-byte response, 4 channels), ping, channel open answered, clean shutdown.

## [0.1.3] — 2026-10-21

**The framing was wrong.** Found while building the test rig for the video channel.

### Fixed

* **Wire format rewritten to the real Android Auto framing.** 0.1.0–0.1.2 wrote
  `[size u16][message id u16][protobuf]`. The aasdk definition is
  `[channel u8][flags u8][size u16 BE][payload]`, with the message id as the first two bytes of
  the payload, `FIRST/MIDDLE/LAST` split frames above `0x4000` bytes, and an extended
  `size + total` field on the FIRST frame. The old format was self-consistent, so the probe
  completed a "handshake" against it — no real phone would have. New `AaChannel` (channel ids,
  flags, `MAX_FRAME_PAYLOAD`) and `AaFrame` (channel, control, message id, payload); codec
  splits and reassembles; encrypted frames are rejected explicitly.
* **`tools/aa-probe.py` rewritten** to the same format and now parses the channel descriptors
  out of service discovery and opens the video channel, so it reports *which* stage a receiver
  fails at instead of a single boolean.
* **`AaSession` speaks per-channel messages** and logs channel + id, so a channel-specific
  message is never mistaken for a control message.

### Changed

* 62 JVM tests (was 58), including hand-computed wire vectors and split-message reassembly.
* `docs/PROTOCOL.md` §2–§3 rewritten with the corrected format, citations and an explicit
  correction note.

## [0.1.2] — 2026-10-21

Second device pass. 0.1.1 fixed the verdict; this release fixes what the report *said* about
itself.

### Fixed

* **Reports lied about their own version.** `BuildConfig` was a committed constant, so the
  0.1.1 APK printed "app version : 0.1.0 (1)". It is now generated by `tools/build.sh` from
  `AndroidManifest.xml`, and `Diag.version()` reads the installed package as ground truth.
* **"tcp 5277 in use" on a working receiver.** The probe bound the port while AiPlay's own
  listener held it, so a running receiver looked like a conflict. `RECEIVER_PORT` now reports
  `listening (own receiver)`, and the report line carries `running=`.
* **14 lines of `IOException: error=2` in every report.** `su` was executed unconditionally;
  the probe now checks the seven real `su` paths first and logs one line when there is none.

### Added

* `docs/states.png` — reference renders of the P1 home screen and of a proposed session
  screen (`tools/gen-states.py`), so the connected state can be reviewed before it is built.

## [0.1.1] — 2026-10-21

Fixes found on the first real device (OPPO CPH2739 / `OP5F05L1`, Android 16, Android Auto
15.6.654484-release). Both bugs made a healthy phone look broken.

### Fixed

* **`SecurityException` in `Inspect Android Auto state`.** The inspect script ran under the
  app's own uid, so `cmd package resolve-activity` defaulted to `--user current` (`-2`) and
  died with `Permission Denial: … uid u0a663 … requires INTERACT_ACROSS_USERS`. Inspect is
  now root-gated (package state below the app uid needs root or adb) and every command
  carries an explicit `--user`.
* **`resolve-activity --brief <package>` was not a query.** A bare package name is parsed as
  a URI, so the answer was always "No activity found". Replaced with a real launcher query
  (`-a MAIN -c LAUNCHER -p <package>`) in the app, the ADB recipe and the cookbook.
* **False `P3 (hidden)` / `Repair Android Auto` on a healthy install.** Modern Android Auto
  ships no launcher activity, so installed + enabled is now `P1 (ready)`; the icon is
  reported as an informational capability instead of a defect.
* **`Open Android Auto` dead end.** With no launcher entry the button now falls back to the
  package's App info page instead of a toast.
* **Receiver was unreachable on a healthy unit.** The footer button is now a Start/Stop
  toggle instead of Stop-only, so the receiver can be started even when the verdict is P1
  (a phone with Android Auto installed could not start it at all in 0.1.0).

### Changed

* 57 JVM tests (was 53): the no-launcher case, the explicit-`--user` rule and the real
  launcher query are now covered by tests.

## [0.1.0] — 2026-10-21

First public version. "Unlock": make an Android head unit usable with Android Auto, and lay
the protocol foundation for a native receiver.

### Added

* Capability audit that classifies a head unit as P1–P5 with per-capability evidence.
* One-tap **Enable** and **Repair** for Android Auto when a root shell is available, with
  a copy-paste ADB recipe when it is not.
* Animated splash screen drawn with paths and shaders — no bitmaps, no animation library.
* Home screen with a readiness gauge, one primary action and an evidence list.
* Diagnostics: bounded ring buffer, full report, save and share.
* Settings: splash, keep-screen-on, start with the car, receiver auto-start, port, verbose.
* Android Auto control plane: frame codec, protobuf writer/reader, version negotiation,
  auth complete, service discovery with channel descriptors, ping, shutdown.
* mDNS advertisement (`_androidauto._tcp`) and a TLS listener on port 5277.
* 53 JVM unit and integration tests, including a real TLS loopback handshake.

### Known limitations

* Receiver video, audio and input channels are not implemented (0.2.0).
* No verification against a live phone yet.
* Shizuku-based privilege is detected but not used (0.2.0).

## [Unreleased]

* USB AOA transport.
* Session recording for replay-based testing.
