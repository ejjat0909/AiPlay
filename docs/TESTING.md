# Testing

What is verified, how, and — just as important — what is **not** verified. Every claim in the
README maps to a row here.

## 1. Summary

| Level | Scope | Tool | Result |
| --- | --- | --- | --- |
| L0 | `core/` has no Android dependency | `grep` + separate `javac` | **pass** |
| L1 | Pure logic unit tests | JUnit 4 on a desktop JVM | **63 / 63 pass** |
| L2 | Frame codec contract (fragmentation, truncation, caps) | JUnit 4 | **pass** |
| L3 | Readiness matrix P1–P5 incl. contradictory profiles | JUnit 4 | **pass** |
| L4 | APK static verification | `aapt2`, `apksigner`, `dexdump` | **pass** |
| L5 | Real TLS handshake + AA control exchange | JUnit 4 over loopback TLS | **pass** |
| L6 | Install and run on a device/emulator, receiver answers a probe | `tools/device-test.sh` | **scripted**, not yet run by the maintainer |
| L7 | Projection against a real phone | phone + car | **not run** — 0.2.0 |

## 2. Reproduce

```sh
./tools/test.sh     # L0, L1, L2, L3, L5   (JDK only, no Android SDK)
./tools/build.sh    # L4
```

`tools/test.sh` output:

```
== L0: core must not depend on Android ==
  ok
== compiling core + tests (JDK only) ==
== generating throwaway TLS keystore ==
== running 7 test classes ==
JUnit version 4.13.2
.....................................................
Time: 1.242
OK (63 tests)
```

## 2.1 The three rigs, and what each one can prove

`tools/device-test.sh` runs the same loop against any of them: install, launch, start the
receiver, `adb forward tcp:5277`, then `tools/aa-probe.py 127.0.0.1`. The forward is why the
emulator works at all — it bypasses mDNS and the emulator's NAT, which is the only thing the
laptop cannot do by itself.

| Rig | Head unit runs on | Phone side runs on | Proves | Cannot prove |
| --- | --- | --- | --- | --- |
| 1 | emulator | laptop (`aa-probe.py`) | framing, TLS, control exchange, channel open, UI layout | codecs, real discovery, real phone |
| 2 | phone over USB/Wi-Fi adb | laptop | the same, on real hardware and real MediaCodec | real phone either |
| 3 | head unit (or phone) | **real phone** with Android Auto | the actual product | needs 0.2.0 video/input first |

A real phone **cannot** be the source for an emulator head unit: the emulator sits behind NAT
(`10.0.2.x`), is not reachable over mDNS, and has no USB-AOA passthrough. Rig 1 and rig 2 use a
software phone on purpose; rig 3 is the only place a real Android Auto phone can be used.

### Rig 1 — emulator as the head unit (fastest loop, no car, no phone)

```sh
sdkmanager "system-images;android-34;google_apis;x86_64"   # once
avdmanager create avd -n aiplay-hu -k "system-images;android-34;google_apis;x86_64"
emulator -avd aiplay-hu -skin 1024x600 -no-snapshot -gpu swiftshader_indirect &
adb wait-for-device
./tools/device-test.sh -s emulator-5554
```

The script starts the receiver through the launcher activity
(`--ez aiplay.start_receiver true`) because the service is deliberately not exported. If you start
it by hand instead, rerun with `--no-install`.

### Rig 2 — a phone as the head unit (real hardware, real codecs)

```sh
adb devices                     # note the serial if more than one
./tools/device-test.sh -s <serial>
```

### Rig 3 — real phone to real head unit (the only end-to-end proof)

Both on the same Wi-Fi, receiver started in the app, then connect from the phone's Android Auto
UI. Not testable before 0.2.0 ships video and input.

### Reading a run

Expected on 0.1.3 (framing correct, no video channel yet):

```
<- control    SERVICE_DISCOVERY_RESPONSE 4 channel(s), head unit='AiPlay'
     channel 1   av          stream_type=3
     channel 4   input
-> control    CHANNEL_OPEN_REQUEST
<- control    CHANNEL_OPEN_RESPONSE      status=1 (FAIL)   ← the 0.2.0 acceptance test
RESULT: handshake complete — receiver speaks Android Auto
```

The same session in the app: `adb logcat -s AiPlay:D`.

### Head-unit layout without a head unit

```sh
adb shell wm size 1024x600 && adb shell wm density 160    # pretend to be the unit
adb exec-out screencap -p > home.png
adb shell wm size reset && adb shell wm density reset
```

## 3. What each test class covers

| Class | Tests | What would break without it |
| --- | --- | --- |
| `aa.AaFrameCodecTest` | 20 | Frame header endianness, flags (channel 0 is SPECIFIC, channel open is CONTROL), byte-at-a-time fragmentation, 64 KiB cap, **per-frame encryption with plaintext `total`**, cipher installed mid-read, encrypted frame without TLS |
| `aa.ProtoTest` | 6 | Varint encoding vs protobuf reference vectors, nested messages, truncated varints, overlong length-delimited fields, fixed-width skip |
| `aa.AaControlTest` | 12 | Exact wire bytes (raw version 1.1, auth, channel open), discovery descriptors, version mismatch / missing status |
| `aa.AaSessionTest` | 14 | Channel logic in plaintext mode: ordering, channel open on its own channel, refusal of unserved channels, video setup/focus/start/stop, per-frame acks, split-framed 50 kB picture |
| `aa.AaCryptorTest` | 6 | In-band TLS 1.2 against a server-mode engine that requires a client certificate and advertises a non-matching issuer; 7-byte flight fragments; garbage rejected |
| `aa.AaTransportTest` | 4 | Real TCP loopback: raw version, in-band handshake, auth in the clear, encrypted video session, every message's flags/encryption, version mismatch, early encrypted frame |
| `bt.WifiBootstrapTest` | 14 | Bluetooth bootstrap: UUID/ids, hand-computed wire bytes (zero-valued required fields included), reader fragmentation, full exchange over a loopback, no SSID => nothing sent, hang-up and silent-phone handling |
| `aa.AaChannelsTest` | 19 | Input / sensor / audio: hand-computed wire bytes, binding accepted only for advertised keys, touch not sent until bound, sensor refused for unoffered types, audio focus, every audio channel set up/start/ack/stop |
| `input.TouchMapperTest` | 5 | Letterbox mapping on 16:9, 1024x600, 1280x480 and portrait panels; bar taps outside; edge clamping |
| `audio.AudioCoreTest` | 10 | Bounded PCM queue (drops the oldest, never blocks, finish() keeps the tail), music ducking policy |
| `ReceiverIdentityTest` | 2 | The committed TLS identity loads and is not PBES2 (older Android cannot read it) |
| `aa.AaAvTest` | 11 | AV message ids and wire bytes (setup status OK = 2), timestamp parsing, H.264 SPS/PPS/IDR scanning and config-prefix split |
| `ReadinessEngineTest` | 9 | P1–P5 classification, hidden-vs-disabled, GMS present-but-disabled, score monotonicity, capability completeness |
| `PrivilegePlanTest` | 5 | The exact `pm` commands, `su -c` joining, recipe completeness |
| `SessionStateMachineTest` | 7 | Legal/illegal transitions, idempotent events, failure recovery |

The TLS test builds a server context from a throwaway PKCS12 keystore that `tools/test.sh`
generates with `keytool`, connects a trust-all client over `127.0.0.1`, and performs:

```
VERSION_REQUEST  →  VERSION_RESPONSE  →  AUTH_COMPLETE
SERVICE_DISCOVERY_REQUEST → SERVICE_DISCOVERY_RESPONSE
PING_REQUEST → PING_RESPONSE
```

That is the same `AaSession` object the Android service uses — only the keystore loader differs.

## 4. Static APK verification (L4)

```
$ ./tools/build.sh
...
Signer #1 certificate DN: CN=AiPlay Debug, O=AiPlay, C=MY
APK : build/AiPlay-0.1.0.apk
size: 217 KB
package: name='com.ejjat.aiplay' versionCode='1' versionName='0.1.0' ...
launchable-activity: name='com.ejjat.aiplay.ui.SplashActivity'
```

| Metric | Value |
| --- | --- |
| APK size | 217 KB (budget 1.5 MB) |
| DEX size | 98 KB |
| `resources.arsc` | 9 KB |
| Classes | 96 |
| Methods | 825 |
| Native libraries | 0 |
| Permissions | 9 |
| `apksigner verify` | v1 + v2 signatures valid |
| minSdk / targetSdk | 24 / 34 |

## 5. Manual checks performed

* Splash timing constants reviewed against `SplashView` (1600 ms cold, 700 ms warm, 4 s watchdog).
* Design renders generated from the same geometry constants as the code
  (`tools/gen-preview.py`) and visually inspected for overlap, alignment and contrast.
* Palette contrast: `TEXT0` on `BG1` = 15.8:1, `TEXT1` on `SURFACE` = 7.4:1, `TEXT2` on
  `SURFACE` = 3.9:1 (used only for tertiary labels ≥ 12 sp).

## 6. Receiver probe (validated)

`tools/aa-probe.py` is the client half of the handshake. It is validated against
`tools/AaHarness.java`, a desktop server that runs the **same** `AaSession` class the Android
service uses:

```
$ java -cp build/test/classes:build/harness AaHarness 5277 build/test/keystore.p12 &
$ python3 tools/aa-probe.py 127.0.0.1 --verbose
connected  : tls TLSv1.3 / TLS_AES_256_GCM_SHA384
<- VERSION_REQUEST            2 field(s), 4 byte(s)
-> VERSION_RESPONSE           version 1.6 status=0
<- AUTH_COMPLETE              status=0
-> SERVICE_DISCOVERY_REQUEST  device='AiPlay probe'
<- SERVICE_DISCOVERY_RESPONSE 4 channel(s), head unit='AiPlay harness', model='Desktop', sw='0.1.0'
-> PING_REQUEST               timestamp=1234567
<- PING_RESPONSE              1 field(s), 6 byte(s)
-> CHANNEL_OPEN_REQUEST       channel 1 priority 1
<- CHANNEL_OPEN_RESPONSE      status=1 (FAIL)
RESULT: handshake complete — receiver is speaking Android Auto
```

This proves the protocol path end to end on the JVM. The remaining unknown is Android's own
`NsdManager`/keystore plumbing on a real device — that is what the phone test below covers.

## 7. Phone test (the next step, needs no car)

```sh
adb install -r build/AiPlay-0.1.0.apk
# in the app: Start wireless receiver
python3 tools/aa-probe.py <phone-ip>
```

Pass criteria: `RESULT: handshake complete`, plus `adb logcat -s AiPlay` showing
`mdns: registered …` and `receiver: tls handshake ok`.

## 8. What could not be tested here, and why

**An Android emulator was attempted and abandoned.** The largest VM profile available in this
workspace is 3 GiB (3 vCPU). A headless API 30 x86_64 AVD with `-memory 768 -gpu off` was
created, but `qemu-system-x86_64` peaked at **2.4 GiB RSS** and was OOM-killed twice:

```
Out of memory: Killed process (qemu-system-x86) total-vm:3433868kB anon-rss:2434912kB
```

A desktop with 8 GB (or a physical head unit over `adb`) is the correct environment. The
commands are ready in `tools/device-test.sh` so the first run on real hardware takes one minute.

**Not verified, therefore not claimed:**

| Claim | Status |
| --- | --- |
| The app installs and launches on a real ROM | not run (L6) |
| The probe returns the same verdict a real ROM deserves | logic tested, probe untested |
| `pm enable` actually turns Android Auto on | command string tested, execution untested |
| A phone discovers `_androidauto._tcp` and completes TLS | protocol tested on JVM; radio untested (run §7) |
| Video/audio/touch projection | not implemented (0.2.0) |

## 9. Device test script (for the first real run)

`tools/device-test.sh` (included) performs:

```sh
adb install -r build/AiPlay-0.1.0.apk
adb shell am start -n com.ejjat.aiplay/.ui.SplashActivity
adb shell dumpsys activity activities | grep -i aiplay
adb logcat -d | grep -i aiplay
adb shell am start-foreground-service -n com.ejjat.aiplay/.receiver.AaReceiverService \
  -a com.ejjat.aiplay.action.START_RECEIVER
adb shell "cat /proc/net/tcp | grep 149D"        # 5277 = 0x149D
```

Success criteria for 0.1.0 on hardware:

1. Splash plays, home appears within 2 s, no ANR.
2. The verdict matches reality (P1 on a working AA unit, P2 on a disabled one).
3. `Enable` changes the verdict to P1 without a reboot.
4. With the receiver started, `/proc/net/tcp` shows port 5277 listening and mDNS registers.
5. Diagnostics saves a report to `Android/data/com.ejjat.aiplay/files/reports/`.


## Device test: input, sensor and audio (0.2.0-alpha6)

`tools/device-test.sh` now ends with an input phase. After streaming the clip, `aa-probe.py` (the phone)
stays connected (`--hold`) while the script acts as a person on the device:

1. taps a **letterbox bar** (when the screen has bars): must not reach the phone;
2. taps the **centre**, the **top-left** and the **bottom-right** corner of the *video*;
3. **swipes** across the video;
4. presses **DPAD_RIGHT**;
5. switches the unit to **night mode** (`cmd uimode night yes`), then back.

`tools/input-geometry.py` works out where to tap from a fresh screenshot (the projection screen rotates, and
`input` injects in the *current* orientation; an early version measured too soon and sent swapped
coordinates, which looked like an app bug and was a rig bug). `tools/verify-input.py` then checks what the
phone received: coordinates within a few pixels, press then drags then release, key down then up, the night
event, **exactly four presses** (so the bar tap was not forwarded), and from the app log that each audio
stream was written *and played* in full and that the music was ducked and restored. Skip with
`--no-input-test`.

Verified on an API 34 emulator at 1920x1080, `wm size` 1024x600 and 1280x480. Audio goes to the emulator's
AudioFlinger (confirmed with `dumpsys media.audio_flinger`: right sample rates and channel masks, right
usage types, the mixer consumed every frame); what is *heard* cannot be checked here.
