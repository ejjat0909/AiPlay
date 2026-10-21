# Changelog

All notable changes to AiPlay. Format follows Keep a Changelog; versions follow SemVer.

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
