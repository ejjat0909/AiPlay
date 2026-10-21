# Changelog

All notable changes to AiPlay. Format follows Keep a Changelog; versions follow SemVer.

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
