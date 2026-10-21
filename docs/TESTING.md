# Testing

What is verified, how, and — just as important — what is **not** verified. Every claim in the
README maps to a row here.

## 1. Summary

| Level | Scope | Tool | Result |
| --- | --- | --- | --- |
| L0 | `core/` has no Android dependency | `grep` + separate `javac` | **pass** |
| L1 | Pure logic unit tests | JUnit 4 on a desktop JVM | **62 / 62 pass** |
| L2 | Frame codec contract (fragmentation, truncation, caps) | JUnit 4 | **pass** |
| L3 | Readiness matrix P1–P5 incl. contradictory profiles | JUnit 4 | **pass** |
| L4 | APK static verification | `aapt2`, `apksigner`, `dexdump` | **pass** |
| L5 | Real TLS handshake + AA control exchange | JUnit 4 over loopback TLS | **pass** |
| L6 | Install and run on a device/emulator | `adb` | **not run** (see §6) |
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
OK (62 tests)
```

## 3. What each test class covers

| Class | Tests | What would break without it |
| --- | --- | --- |
| `aa.AaFrameCodecTest` | 10 | Frame header endianness, byte-at-a-time TCP fragmentation, trailing partial frame, 64 KiB payload cap, out-of-bounds feed, unknown-id naming |
| `aa.ProtoTest` | 6 | Varint encoding vs protobuf reference vectors, nested messages, truncated varints, overlong length-delimited fields, fixed-width skip |
| `aa.AaControlTest` | 7 | Exact wire bytes for version/auth/channel-open, service-discovery channel descriptors (video 720p30, audio 48k/16/2, touch 1280×720), tolerant parsing of garbage |
| `aa.AaSessionTest` | 8 | Handshake ordering, idempotent `begin()`, refusal of channel open, ping, unknown-frame reporting, peer close, **real TLS loopback exchange** |
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
