# AiPlay — Engineering Plan

| Field | Value |
| --- | --- |
| Project | **AiPlay** — Android Auto for head units that were never sold with it |
| Repo | `github.com/ejjat0909/AiPlay` (branch `main`) |
| Base / prior art | `github.com/shihabal3amri/DiPlay` v0.2.21 (CarPlay receiver, GPL-3.0), itself based on `shilapi/xcertplay` |
| Analysis method | REA (`morluto/rea` v6.7.0, JADX-headless provider) on the **shipped APK**, plus source review |
| Target devices | Aftermarket Android head units, Android 7.0 → 14 (API 24 → 34) |
| Planned version | `0.1.0` (first shippable), `0.2.x` (receiver channels) |
| Language | Java 11 source level, no Kotlin, no AndroidX, no Compose |
| APK budget | **≤ 1.5 MB** (no native libs, no bundled models, no webview) |
| Status | Plan approved for implementation; v0.1.0 scope is defined in §16 |

---

## 1. Executive summary

AiPlay is a small, fast, offline Android app that makes Android Auto usable on an
aftermarket Android head unit whose ROM does not offer it.

The request that started this project was: *"take DiPlay and turn it into an Android
Auto version"*. The honest engineering answer, established in §4 with evidence from
the shipped DiPlay APK, is that **DiPlay and AiPlay solve different protocol
problems**. DiPlay is an **Apple CarPlay (AirPlay + IAP2 + MFi)** receiver: an iPhone
sends it H.264 over RTSP/RTP. Android Auto is a **TLS + protobuf** protocol with a
completely different handshake, channel model and authentication story. There is no
mechanical port from one to the other.

What *is* reusable from DiPlay is its **product architecture**: the way it turns an
Android head unit into a projection endpoint (display surface handoff, MediaCodec
decode pacing, audio sink, input mapping, mDNS advertisement, connection state
machine, settings information architecture, diagnostics export). AiPlay re-implements
that architecture against the Android Auto protocol, in a deliberately smaller and
lighter codebase.

AiPlay therefore ships in two layers:

* **Layer 1 — Unlock (v0.1.0, works today, no phone needed).** A capability audit and
  privilege bridge that detects exactly *why* a head unit has no Android Auto and
  fixes the fixable cases (Android Auto present-but-disabled, hidden from launcher,
  or blocked by a missing permission), with an ADB cookbook for the rest.
* **Layer 2 — Receiver (v0.2.0+, experimental).** A native Android Auto wireless
  endpoint (mDNS + TLS + AA framing + channels) so a phone can project into the head
  unit even when the ROM has no Android Auto component at all.

Layer 1 is the part that makes the app useful on day one, and it is the part this
repository can verify end-to-end without a car. Layer 2's wire format is specified
in §9 against the public `aasdk` protocol definitions, and its first two stages
(advertisement + TLS + control-message framing) are implemented and unit-tested in
v0.1.0; video/audio/input channels are explicitly scoped to v0.2.0.

---

## 2. The problem, stated precisely

"An Android player that doesn't support Android Auto" is not one condition. It is at
least five different conditions, and each has a different fix:

| # | Condition on the head unit | What the driver sees | Fixable by an app? |
| --- | --- | --- | --- |
| P1 | `com.google.android.projection.gearhead` installed **and enabled** | AA works | Nothing to fix |
| P2 | Gearhead installed but **disabled** (`pm list packages -d`) | No AA icon, no AA in Settings | **Yes** — one `pm enable` |
| P3 | Gearhead installed, enabled, but **hidden** from launcher and/or missing a runtime permission | AA exists but unreachable | **Yes** — enable + grant + launch intent |
| P4 | Gearhead **absent**, but the unit has GMS and an AA-capable Android version | No AA at all | Partly — needs an AA *receiver* (Layer 2), or a system-app install |
| P5 | Gearhead absent **and** no GMS / heavily stripped Chinese ROM | No AA, no Play Services | No — needs a receiver that does not depend on GMS |

AiPlay's audit distinguishes P1–P5 by evidence (package manager state, GMS presence,
root availability, `ro.build` fingerprint, `FEATURE_USB_HOST`, WiFi Direct support)
and never pretends a P5 device is a P2 device.

**Why "just install the APK" does not work (P4/P5).** Android Auto on a *head unit*
is not a normal app: the projection role requires the platform to expose the
`gearhead` projection service and, on many builds, system signature or a privileged
`/system/priv-app` location. A user-space sideload of the consumer APK gives you the
phone-side companion UI, not a head-unit endpoint. This was already established in
this workspace's earlier head-unit work and is restated here so the plan does not
repeat a known dead end.

---

## 3. Goals and non-goals

### Goals

1. **G1** — Tell the driver, in one screen, exactly which of P1–P5 applies to *their*
   unit, with the evidence shown (not a guess).
2. **G2** — Fix P2/P3 with one tap where a privilege path exists (root, Shizuku,
   ADB-over-Wi-Fi), and give a copy-paste ADB recipe where it does not.
3. **G3** — Provide a real Android Auto wireless endpoint skeleton (advertisement,
   TLS, AA control framing) that a phone can discover, and grow it into a full
   receiver without rewriting the app.
4. **G4** — Stay tiny: cold start < 700 ms on a 2019-era MTK head unit, APK ≤ 1.5 MB,
   RAM < 60 MB idle, zero network calls to the internet.
5. **G5** — Look like it belongs in a car: a splash screen and home screen that are
   readable at 1024×600 in daylight, one-hand reachable, and genuinely attractive.
6. **G6** — Be honest: every capability shown as "supported" is probed at runtime;
   every experimental path is labelled experimental in the UI and in the docs.

### Non-goals (v0.1.0)

* No CarPlay support (that is DiPlay's job; AiPlay does not compete with it).
* No full AA video/audio projection yet (v0.2.0).
* No iOS support, no dongle emulation, no DRM circumvention.
* No telemetry, no account, no cloud, no update checker.
* No BYD-specific HUD/cluster integration (DiPlay's BYD work stays in DiPlay; AiPlay
  only keeps a generic vehicle-data hook).

---

## 4. What DiPlay actually is (REA evidence)

Analysis was performed on the **shipped artifact**
`DiPlay-0.2.21.apk` (SHA-256 `916c0708beb1022d4ea9678ff6a46dfb6d234f707837a2cbd80d615e8e2bb2e3`)
with REA 6.7.0 and the `jadx-headless-mcp 0.7.1` provider
(JAR SHA-256 `6e5eacf500b64292bfb73c49797c1958f6ee44646e43e868039ae7feb573ff75`).

Observed facts (static, `authority: shipped-artifact`, `confidence: observed`):

| Fact | Value |
| --- | --- |
| Package | `com.shihab.diplay` |
| Version | `0.2.21` (code `40`), minSdk 24, targetSdk 37 |
| DEX classes | **13,541** |
| Resources | 358 |
| Permissions | 29 (see appendix A) |
| Activities | 7 (`DiPlayActivity`, `CarPlayHostActivity`, `CarPlayVideoActivity`, `AdbClusterActivity`, …) |
| Services | 10 (`DiPlaySessionService`, `WheelKeyService`, `CarPlayVpnService`, `UsbAutoConfirmService`, …) |
| Native libs | `libspeex_echo.so`, `libxcertplay_i2c.so`, `liblocal_hotspot_radio.so`, `libandroidx.graphics.path.so` (4 ABIs) |
| Largest classes | `CarPlayController` **278 methods**, `AirPlaySession` **79**, `AndroidMediaSink` **66** |

Interpretation, with the protocol families visible in the class inventory:

* `airplay/*` — RTSP control, bplist codec, SRP-6a pair setup, TLV8, ChaCha20-Poly1305
  frame sealing, RTP audio, NTP clock. This is **AirPlay**, i.e. the CarPlay transport.
* `iap2/*` — Apple iAP2 accessory protocol, MFi authentication (`MfiSapAuthSetup`,
  `offline-mfi` assets). CarPlay requires an Apple accessory identity; DiPlay ships an
  experimental one recovered from public firmware.
* `network/*` — 49 classes dedicated to **getting an iPhone onto the same L2 network**:
  WiFi Direct group ownership, LocalOnlyHotspot, hotspot credential read/write, IPv6
  NCM bridging, scan pausing, join repair. This is the single biggest source of
  real-world pain in the CarPlay project and the most instructive part of its design.
* `media/*` — `MediaCodec` decode, frame pacing, audio buffer recovery, Opus encode for
  the microphone, echo cancellation. Platform-level work, reusable in concept.
* `hud/*` — BYD-specific cluster/HUD integration over vendor services.

**Conclusion:** DiPlay is a serious, protocol-complete CarPlay receiver. None of its
wire protocol applies to Android Auto. Its *architecture* — endpoint lifecycle,
transport selection, display handoff, audio sink, diagnostics — does, and AiPlay
copies the shape while replacing every byte on the wire.

The full analysis, including the exact commands to reproduce it, is in
[`docs/REA-ANALYSIS.md`](docs/REA-ANALYSIS.md).

---

## 5. Design principles

1. **Evidence over assumption.** The home screen shows *probes*, not marketing.
   A green tick always means "this was checked on this device, now".
2. **One job per screen.** Splash → readiness → fix → diagnose.
3. **No privilege escalation tricks.** AiPlay uses `su` only if a working `su` already
   exists, never ships an exploit, never asks for root it cannot use.
4. **Small is a feature.** Every dependency is a liability on a 2 GB head unit with a
   2018 SoC. Target: zero third-party runtime dependencies.
5. **Fail loudly, in the log.** Every experimental path writes a bounded, shareable
   diagnostic ring buffer (no PII, no SSID/password).
6. **The car is not a phone.** Large touch targets (≥ 56 dp), high contrast, no
   gesture-only actions, no accidental swipes that change state.

---

## 6. Capability model and decision tree

```
                    ┌─────────────────────────────┐
                    │ Probe: Gearhead package?    │
                    └──────────────┬──────────────┘
             present ┌─────────────┴─────────────┐ absent
                     ▼                           ▼
        ┌────────────────────────┐   ┌──────────────────────────────┐
        │ enabled?               │   │ GMS present?                 │
        └───────┬────────────────┘   └───────┬──────────────┬───────┘
        yes     │        no                  │ yes          │ no
                ▼         ▼                  ▼              ▼
        ┌───────────┐ ┌──────────┐  ┌──────────────┐  ┌───────────────┐
        │ P1: ready │ │ P2: fix  │  │ P4: receiver │  │ P5: receiver  │
        │ open AA   │ │ pm enable│  │ or system    │  │ only (no GMS) │
        └───────────┘ └──────────┘  │ install path │  └───────────────┘
                                    └──────────────┘
```

Each leaf maps to one primary button on the home screen, so the driver never has to
choose between five technical options:

| Leaf | Primary action | Secondary |
| --- | --- | --- |
| P1 | **Open Android Auto** (launch intent) | Diagnostics |
| P2 | **Enable Android Auto** (privileged `pm enable`) | Copy ADB recipe |
| P3 | **Repair Android Auto** (enable + grant + launch) | Copy ADB recipe |
| P4 | **Start wireless receiver** (experimental) | ADB system-install recipe |
| P5 | **Start wireless receiver** (experimental) | Explain why GMS-free units behave differently |

---

## 7. Architecture

### 7.1 Module map (single Gradle-free module)

```
com.ejjat.aiplay
├── AiPlayApp                 Application: prefs, crash guard, log ring
├── ui
│   ├── SplashActivity        animated brand splash, routes to Home
│   ├── HomeActivity          readiness + primary action + capability grid
│   ├── SetupActivity         guided fix (ADB cookbook, privilege chooser)
│   ├── DiagnosticsActivity   report viewer, save/share
│   ├── SettingsActivity      preferences
│   └── view
│       ├── SplashView        custom-drawn animated splash (no assets)
│       ├── RingGauge         readiness arc
│       ├── GlowCard          rounded card with gradient hairline
│       └── StatusRow         capability row (icon + label + evidence)
├── core                      PURE JAVA — no android.* imports, JVM-testable
│   ├── DeviceProfile         immutable device facts
│   ├── Capability            enum + evaluation rules
│   ├── ReadinessEngine       profile → verdict (P1..P5) + score + actions
│   ├── PrivilegePlan         command lists for root/ADB repair
│   ├── SessionState          enum + allowed transitions
│   ├── SessionStateMachine   guarded transitions + event log
│   └── aa
│       ├── AaMessageType     verified control message ids (aasdk)
│       ├── AaFrame           length/type/payload value object
│       ├── AaFrameCodec      encode/decode + partial-buffer parser
│       ├── ProtoWriter       minimal protobuf writer (varint, len-delimited)
│       ├── ProtoReader       minimal protobuf reader
│       └── AaControl         version/auth/service-discovery/channel messages
├── device                    Android adapters (thin, no logic)
│   ├── CapabilityProbe       PackageManager/Build/Settings → DeviceProfile
│   ├── PrivilegedShell       su / Shizuku / adb bridge executor
│   ├── GearheadController    enable / grant / launch
│   ├── Prefs                 SharedPreferences wrapper
│   └── Diag                  bounded ring buffer + report writer
└── receiver                  Layer 2 (experimental)
    ├── AaReceiverService     foreground service, owns the endpoint
    ├── AaTlsServer           TLS listener on 5277 (bundled self-signed identity)
    ├── MdnsAdvertiser        NsdManager `_androidauto._tcp` advertisement
    └── ReceiverLog           per-session frame log for diagnostics
```

**Rule:** `core/` must not import `android.*`. This is enforced by a test that greps
the compiled sources and by the fact that `core/` is compiled twice — once against
`android.jar`, once against plain JDK for the JVM test suite.

### 7.2 Runtime flow

```
Launcher → SplashActivity (1.6 s, skippable by tap)
            └→ HomeActivity
                 ├─ CapabilityProbe.probe()  (background thread, 300 ms budget)
                 ├─ ReadinessEngine.evaluate(profile) → Verdict
                 ├─ primary action → GearheadController / AaReceiverService
                 └─ capability grid → SetupActivity / DiagnosticsActivity
```

`CapabilityProbe` never blocks the UI thread and never takes more than ~300 ms: every
probe is a `PackageManager`/`Build`/`Settings` read, plus one bounded `su -c id`
attempt (2 s timeout, cached for the process lifetime).

### 7.3 Data model

```java
final class DeviceProfile {
    String manufacturer, brand, model, device, romFingerprint;
    int sdkInt, previewSdk;
    String abi;
    boolean gearheadInstalled, gearheadEnabled;
    int gearheadVersionCode;
    boolean gmsInstalled, gmsEnabled;
    boolean suAvailable;          // bounded probe, cached
    boolean shizukuInstalled;
    boolean adbEnabled, adbWifiEnabled;
    boolean usbHost, wifiDirect, bluetooth;
    int screenWidthDp, screenHeightDp, densityDpi;
}
```

`ReadinessEngine` is a pure function `DeviceProfile → Verdict`:

```java
final class Verdict {
    Condition condition;      // P1..P5
    int score;                // 0..100, for the ring gauge
    Action primary;           // enum: OPEN_AA, ENABLE_AA, REPAIR_AA, START_RECEIVER
    List<Capability> satisfied, missing;
    String rationale;         // one sentence, shown under the gauge
}
```

Keeping this pure is what makes the app testable without a device: 30+ unit tests
cover the condition matrix, including contradictory profiles (e.g. Gearhead enabled
but GMS missing, which real ROMs do produce).

### 7.4 Session state machine

```
IDLE ──probe──▶ PROBING ──ok──▶ READY ──start──▶ CONNECTING ──peer──▶ PROJECTING
  ▲                 │              │                  │                  │
  └──── error ◀─────┴──────────────┴────── ERROR ◀────┴──────────────────┘
```

Transitions are explicit and validated; an illegal transition throws in tests and is
ignored (with a log line) in release builds. The same machine drives the receiver
service and the home screen indicator, so the UI can never show "connected" while
the service is idle.

### 7.5 Privilege providers

| Provider | Detection | Used for | Failure mode |
| --- | --- | --- | --- |
| `Root` | `su -c id` returns uid 0 within 2 s | `pm enable`, `pm grant`, `settings put` | "Root present but denied" |
| `Shizuku` | package `moe.shizuku.privileged.api` present | ADB-level shell via binder (v0.2) | "Shizuku installed but not running" |
| `AdbLocal` | `Settings.Global.ADB_ENABLED` and Wi-Fi ADB port known | Nothing automatic; generates recipe | "ADB on but no client" |
| `None` | otherwise | Copy-paste recipe only | "No privilege path" |

AiPlay never bundles `su`, never exploits a CVE, and never asks the user to disable
verified boot. The ADB recipe it generates is exactly the documented path:

```sh
adb shell pm list packages -d | grep -i gearhead      # is it just disabled?
adb shell pm enable --user 0 com.google.android.projection.gearhead
adb shell pm grant com.google.android.projection.gearhead android.permission.ACCESS_FINE_LOCATION
adb reboot
```

---

## 8. Layer 2 — Android Auto endpoint design

### 8.1 Why a receiver at all

For P4/P5 units there is no `gearhead` to enable. The only way a phone can project into
such a unit is for the unit to **be the head unit** in the AA protocol sense: advertise
itself, accept the phone's TLS connection, and serve the video/audio/input channels.
That is what commercial receivers (Headunit Reloaded) and `openauto` do on a Raspberry
Pi. AiPlay implements the same role natively, in Java, on the head unit itself.

### 8.2 Transport

| Transport | Status in AiPlay | Notes |
| --- | --- | --- |
| **Wi-Fi (TCP 5277)** | implemented (v0.1.0: advertisement + TLS + control framing) | Works when phone and head unit share a hotspot; no cable, no USB permission dialogs |
| USB AOA (`UsbManager`, accessory mode) | planned v0.3 | Requires the phone to switch to accessory mode; vendor-specific quirks per ROM |
| Bluetooth RFCOMM bootstrap | planned v0.3 | Real cars use BT for the initial handshake and Wi-Fi credentials |

### 8.3 Discovery

`NsdManager` registers `_androidauto._tcp` on the receiver port. The service name is
`AiPlay-<last 4 of ANDROID_ID>`; TXT records carry the protocol version and a
`wireless` flag. The advertiser is torn down with the service, and registration
failures are surfaced in diagnostics instead of being swallowed.

### 8.4 Framing (verified)

Every AA control message on the wire is:

```
+--------+--------+----------------+
| len BE | type BE| payload (len)  |
| uint16 | uint16 | protobuf       |
+--------+--------+----------------+
```

Message ids are taken from `aasdk_proto/ControlMessageIdsEnum.proto` (GPL-3.0,
f1xpl/aasdk) and reproduced in `AaMessageType` with the source cited:

| Id | Message | Direction |
| --- | --- | --- |
| `0x0001` | `VERSION_REQUEST` | head unit → phone |
| `0x0002` | `VERSION_RESPONSE` | phone → head unit |
| `0x0003` | `SSL_HANDSHAKE` | both |
| `0x0004` | `AUTH_COMPLETE` | head unit → phone |
| `0x0005` | `SERVICE_DISCOVERY_REQUEST` | phone → head unit |
| `0x0006` | `SERVICE_DISCOVERY_RESPONSE` | head unit → phone |
| `0x0007` | `CHANNEL_OPEN_REQUEST` | phone → head unit |
| `0x0008` | `CHANNEL_OPEN_RESPONSE` | head unit → phone |
| `0x000b` | `PING_REQUEST` | both |
| `0x000c` | `PING_RESPONSE` | both |
| `0x000d` | `NAVIGATION_FOCUS_REQUEST` | both |
| `0x000e` | `NAVIGATION_FOCUS_RESPONSE` | both |
| `0x000f` | `SHUTDOWN_REQUEST` | both |
| `0x0010` | `SHUTDOWN_RESPONSE` | both |
| `0x0011` | `VOICE_SESSION_REQUEST` | both |
| `0x0012` | `AUDIO_FOCUS_REQUEST` | both |
| `0x0013` | `AUDIO_FOCUS_RESPONSE` | both |

`AaFrameCodec` implements the header, a strict maximum frame size (1 MiB) to avoid
unbounded allocation from a hostile peer, and a streaming parser that tolerates TCP
fragmentation. It is unit-tested with hand-built byte vectors, a fragmentation test
(byte-at-a-time feed) and a fuzz-ish truncation test.

### 8.5 Handshake sequence (v0.2.0 target)

```
phone                                   head unit (AiPlay)
  │  TCP connect :5277                        │
  │──────────────────────────────────────────▶│
  │  TLS ClientHello                          │
  │◀──────────── TLS ServerHello + cert ──────│  (bundled self-signed identity)
  │  TLS established                          │
  │◀──── VERSION_REQUEST (0x0001) ────────────│  major 1, minor 6
  │──── VERSION_RESPONSE (0x0002) ───────────▶│
  │──── SERVICE_DISCOVERY_REQUEST (0x0005) ──▶│
  │◀─── SERVICE_DISCOVERY_RESPONSE (0x0006) ──│  video/audio/input/sensor descriptors
  │──── CHANNEL_OPEN_REQUEST (0x0007) ───────▶│  per channel
  │◀─── CHANNEL_OPEN_RESPONSE (0x0008) ───────│
  │──── VIDEO_FOCUS_REQUEST (video channel) ─▶│
  │◀─── video frames (media messages) ────────│
  │──── touch/button events (input channel) ─▶│
```

### 8.6 Channels

| Channel | v0.1.0 | v0.2.0 | Implementation notes |
| --- | --- | --- | --- |
| Video | — | ✅ | `MediaCodec` H.264 decoder → `SurfaceView`; reuse DiPlay's pacing lessons (frame timestamps, backlog drop) |
| Media audio | — | ✅ | `AudioTrack` 48 kHz stereo, AA `AVChannelSetup` |
| Speech/nav audio | — | ✅ | second `AudioTrack`, ducking media |
| Input (touch) | — | ✅ | AA `TouchEventData` (absolute, normalized 0..1) → `MotionEvent` injection |
| Input (keys) | — | ✅ | rotary/button events for steering-wheel keys |
| Sensor | — | ✅ | day/night, driving status, GPS passthrough |
| Bluetooth | — | planned | pairing helper only |
| Microphone | — | planned | AA voice channel → `AudioRecord` |

### 8.7 Honesty boundary

The plan marks **verified** = "wire format matches a public, citable definition and is
covered by a unit test"; **unverified** = "designed but not exercised against a real
phone". v0.1.0 ships verified framing + TLS + advertisement, and says so in the UI.
No screen in AiPlay claims a working projection that has not been observed on a device.

---

## 9. UI / UX specification

### 9.1 Design language

| Token | Value | Use |
| --- | --- | --- |
| `bg.0` | `#05070D` | splash base |
| `bg.1` | `#0A1020` | app background |
| `surface` | `#111A2C` | cards |
| `surface.hi` | `#16233A` | pressed / raised |
| `line` | `#1E2C45` | hairline strokes |
| `text.0` | `#F2F6FF` | primary |
| `text.1` | `#9DB0CC` | secondary |
| `text.2` | `#5F7292` | tertiary |
| `accent.a` | `#22D3EE` | cyan — "ready / projection" |
| `accent.b` | `#3B82F6` | blue — brand gradient end |
| `warn` | `#F59E0B` | needs a fix |
| `bad` | `#EF4444` | unsupported |
| `ok` | `#34D399` | verified |

Type: system sans (Roboto on AOSP, Noto on Chinese ROMs) at 12/14/16/22/34 sp, weights
400/500. No bundled fonts — a font file would cost more APK than it is worth and break
on ROMs with a different font stack.

Spacing scale: 4 / 8 / 12 / 16 / 24 / 32 dp. Corner radius 18 dp on cards, 14 dp on
buttons, full pill on chips.

### 9.2 Splash screen (the "beautiful" requirement)

**Concept — "signal lock".** A dark cockpit at night; a cyan arc sweeps around a ring
and *locks* into a play triangle; the wordmark rises out of the glow. It reads as
"your screen is waking up", not as a logo bumper.

Layers, back to front:

1. **Base** — vertical gradient `#05070D → #0A1020`, plus a radial glow centred at
   42% height, radius 0.55×min(w,h), colour `#12325C` at 35% alpha, fading to 0.
2. **Vignette** — radial darkening at the corners (alpha 0.55 at edge), drawn with a
   `RadialGradient` so the centre stays clean on 600-nit panels.
3. **Ring** — two arcs of 96° and 48° with 3 dp stroke, swept from -90°, stroke shader
   `LinearGradient(#22D3EE → #3B82F6)`, with a 12 dp blurred twin underneath at 30%
   alpha for a soft neon bleed. Rotation 0 → 270° over 1100 ms, `DecelerateInterpolator(1.6)`.
4. **Play glyph** — rounded triangle, white, scaled 0.6 → 1.0 with
   `OvershootInterpolator(1.8)` starting at 380 ms, plus a 6% breathing pulse afterwards.
5. **Wordmark** — "AiPlay", 34 sp, letter-spacing 0.18 em, alpha 0 → 1 and translateY
   12 dp → 0 over 420 ms starting at 620 ms.
6. **Tagline** — "Android Auto · for every head unit", 13 sp, `text.1`, fades in at 900 ms.
7. **Progress hairline** — 2 dp, 120 dp wide, centred at 82% height; fill 0 → 100% over
   1200 ms with the accent gradient; disappears at completion.
8. **Version** — bottom-right, 11 sp, `text.2`.

Timing: **total 1600 ms**, then a 240 ms cross-fade into Home. A tap anywhere skips to
Home immediately (head units are used impatiently). If the process is warm, the splash
shortens to 700 ms. `Settings → Show splash` can disable it entirely.

Rendering: one custom `View` drawing into a hardware-accelerated canvas, driven by a
single `ValueAnimator` (0→1 over 1600 ms) and one `Choreographer` frame callback for
the breathing pulse. No bitmaps, no `AnimationDrawable`, no Lottie. Cost: ~260 lines of
Java and **0 bytes** of assets.

### 9.3 Home screen

```
┌──────────────────────────────────────────────────────────────┐
│ [◉] AiPlay            BYD DiLink 5.1 · Android 10 · API 29   │
│                                                              │
│  ┌────────────────────────────┐  ┌─────────────────────────┐ │
│  │      ◜ 62 ◝                │  │ ANDROID AUTO IS DISABLED│ │
│  │   readiness gauge          │  │ Found on this unit but  │ │
│  │                            │  │ turned off by the ROM.  │ │
│  │  Android Auto is disabled  │  │                         │ │
│  │                            │  │  [ Enable Android Auto ]│ │
│  └────────────────────────────┘  └─────────────────────────┘ │
│                                                              │
│  Capabilities                                                │
│  ✓ Android Auto package      installed, disabled (v12.3)     │
│  ✓ Google Play services      present                         │
│  ✓ USB host                  supported                       │
│  ✕ Root shell                not available                   │
│  ✓ Wi-Fi Direct              supported                       │
│                                                              │
│  [ Diagnostics ]   [ ADB recipe ]   [ Settings ]             │
└──────────────────────────────────────────────────────────────┘
```

* Readiness gauge: 120 dp arc, value = `Verdict.score`, animated 0 → value over 700 ms.
* Primary button is **56 dp tall**, full-width on narrow screens, accent gradient.
* Capability rows are evidence-first: the value column shows *what was observed*.
* Works at 800×480 → 1920×1080; content scrolls when height < 560 dp.

### 9.4 Settings

Start on boot · Auto-enable AA at boot · Keep screen on · Show splash · Receiver port
(5277) · Receiver auto-start · Diagnostic verbosity · Reset pairing. Persisted in
`SharedPreferences`; no accounts, no sync.

### 9.5 Diagnostics

Bounded ring buffer (512 lines, 64 KB cap) of structured events:
`time, level, area, message`. Report includes `DeviceProfile`, verdict, last 200 log
lines, receiver session summary. Saved to `Downloads/AiPlay/aiplay-report-<ts>.txt`
with a `FileProvider` share path. Never includes SSIDs, passwords, or phone names.

### 9.6 Accessibility

* Contrast ≥ 4.5:1 for all text (verified against the palette above).
* Every icon has a content description; every action is reachable by keyboard/rotary.
* No colour-only status: each state also has a word ("Ready", "Disabled", "Missing").
* RTL: layouts use `start/end` margins only; Arabic strings included in v0.2.
* Font scale 1.3× tested (the home grid reflows to a single column).

---

## 10. Build system (no Gradle)

Gradle is deliberately avoided: it costs ~1 GB of download, a 2 GB daemon and 3–6
minutes per build on the target hardware class, for an app with **zero dependencies**.

`tools/build.sh` performs:

```
aapt2 compile  res/ -o build/res.zip
aapt2 link     build/res.zip --manifest AndroidManifest.xml -I android.jar \
               --java build/gen --output build/base.apk --auto-add-overlay
javac          -source 11 -target 11 -bootclasspath android.jar \
               -d build/classes $(find java build/gen -name '*.java')
d8             --release --min-api 24 --lib android.jar --output build/dex build/classes
zip            base.apk + classes.dex
zipalign       -p 4
apksigner      sign --ks <debug-or-release> --v1 --v2 --v3
```

Outputs: `build/AiPlay-<version>.apk`, `build/badging.txt`, `build/apk-size.txt`.
The script is deterministic (fixed timestamps via `zip -X`), works offline once the SDK
is present, and takes ~12 s on one vCPU.

**Size budget** (measured in `docs/TESTING.md`): DEX ≤ 260 KB, resources ≤ 40 KB,
icons ≤ 120 KB, no native libs, no assets → total ≤ 1.5 MB.

---

## 11. Test strategy

| Level | What | How | Gate |
| --- | --- | --- | --- |
| L0 | `core/` compiles without `android.*` | `grep` + separate `javac` against JDK | must pass |
| L1 | Unit tests on pure core | JUnit 4 on the JVM (`tools/test.sh`) | 100% pass |
| L2 | Frame codec contract | byte vectors, fragmentation, truncation, oversize | 100% pass |
| L3 | Readiness matrix | all P1–P5 + contradictory profiles | 100% pass |
| L4 | Static APK checks | `aapt2 dump badging`, `apksigner verify`, DEX method count, size | scripted |
| L5 | JVM harness | fake `DeviceProfile` → verdict → rendered report | scripted |
| L6 | Device | install on real head unit, audit, enable, receiver advertise | manual, documented |

Not testable in this environment, and therefore *not claimed*: real AA projection
(needs a phone + a car), USB AOA quirks, MediaCodec on MTK/Snapdragon, and any
behaviour behind a vendor ROM. §16 gates those on physical hardware.

Test commands:

```sh
./tools/test.sh      # JUnit on core/  (no Android SDK needed beyond javac)
./tools/build.sh     # builds and statically verifies the APK
```

---

## 12. Performance budget

| Metric | Budget | Measured |
| --- | --- | --- |
| Cold start → splash visible | 250 ms | see TESTING.md |
| Splash → home | 1600 ms (700 warm) | by design |
| Home audit (background) | ≤ 300 ms | bounded probes |
| Idle RSS | ≤ 60 MB | no bitmaps, no webview |
| APK | ≤ 1.5 MB | measured per build |
| Receiver idle CPU | ≤ 1% | one selector thread, no polling |

---

## 13. Security and privacy

* **No network egress** except the local AA endpoint. No analytics, no crash upload.
* **No bundled secrets.** The receiver identity is generated at build time into
  `res/raw` and is a *self-signed, non-secret* endpoint certificate; it is not an Apple
  or Google identity and grants no access to any service.
* **No privilege escalation.** `su` is invoked only if the user already has it; the
  command set is fixed and displayed before execution.
* **Bounded input parsing.** Frame length cap 1 MiB, protobuf depth cap, TLS handshake
  timeout 10 s, one session at a time.
* **Data minimisation.** Diagnostics never include SSIDs, passwords, phone names, or
  MAC addresses.

---

## 14. Compatibility matrix

| Android | API | Expected | Notes |
| --- | --- | --- | --- |
| 7.0 – 7.1 | 24–25 | supported | minSdk 24; NsdManager, TLS 1.2 |
| 8.0 – 8.1 | 26–27 | supported | notification channels |
| 9 | 28 | supported | foreground service type |
| 10 | 29 | supported | scoped storage for diagnostics |
| 11 – 12 | 30–31 | supported | package visibility (`<queries>`) required for Gearhead lookups |
| 13 – 14 | 33–34 | supported | `POST_NOTIFICATIONS`, FGS types |

Known limits: units with no GMS cannot run AA even with the receiver until v0.2's
GMS-free path is validated; some Chinese ROMs rename Gearhead (the probe searches by
package *and* by intent, and reports "unknown variant" instead of guessing).

---

## 15. Repository layout

```
AiPlay/
├── PLAN.md                     ← this document
├── README.md
├── LICENSE                     GPL-3.0 (derivative work of DiPlay)
├── CHANGELOG.md
├── docs/
│   ├── REA-ANALYSIS.md         evidence-backed analysis of DiPlay 0.2.21
│   ├── ARCHITECTURE.md
│   ├── PROTOCOL.md             AA wire format, verified vs unverified
│   ├── COMPATIBILITY.md        P1–P5, per-ROM notes, ADB cookbook
│   ├── BUILD.md
│   ├── TESTING.md
│   └── ROADMAP.md
├── app/
│   ├── AndroidManifest.xml
│   ├── res/{values,drawable,layout,raw,mipmap-*}
│   └── java/com/ejjat/aiplay/{...}
├── tests/java/...              JUnit 4, JVM-only
└── tools/{build.sh,test.sh,gen-icons.py,verify.sh}
```

---

## 16. Milestones

### M1 — v0.1.0 "Unlock" (this delivery)

Acceptance criteria:

- [ ] App builds to ≤ 1.5 MB APK with `tools/build.sh`, signed, installable.
- [ ] Splash plays as specified in §9.2 and is skippable.
- [ ] Home shows a verdict for P1–P5 with evidence rows.
- [ ] `Enable Android Auto` executes the privileged command when `su` exists, and
      falls back to the ADB recipe otherwise.
- [ ] Receiver advertises `_androidauto._tcp` and accepts a TLS connection.
- [ ] Frame codec + readiness engine covered by unit tests; all pass.
- [ ] Docs: PLAN, REA analysis, compatibility, protocol, build, testing.

### M2 — v0.2.0 "Projection" (next)

- Video channel (H.264 → MediaCodec → SurfaceView), audio channels, touch input.
- End-to-end test on a real head unit with a real phone; capture a session log.
- GMS-free path validated (or explicitly declared unsupported).

### M3 — v0.3.0 "Wired"

- USB AOA transport, Bluetooth bootstrap, steering-wheel key mapping.

### M4 — v1.0.0

- Stable receiver, per-ROM compatibility database (community-contributed reports),
  translations (MS/AR/ZH/ES), OTA-less update instructions.

---

## 17. Risks

| Risk | Impact | Mitigation |
| --- | --- | --- |
| ROM has no Gearhead and no GMS | Layer 1 cannot help; Layer 2 unproven | Be explicit in UI; keep the receiver path separate and labelled |
| AA protocol changes with phone updates | Receiver breaks | Version negotiation is a first-class message; log the negotiated version |
| Vendor ROM blocks `su` differently | Enable path fails | Multiple providers + exact error surfaced, never a silent failure |
| Head unit kills background services | Receiver dies mid-session | Foreground service + boot receiver + "keep screen on" |
| Trademark confusion ("Android Auto" is Google's) | Takedown risk | README disclaimer: unofficial, not affiliated with Google; name is descriptive |
| GPL obligations from DiPlay | Licence violation | Project is GPL-3.0, notices preserved, derivation documented |

---

## Appendix A — DiPlay 0.2.21 permissions (REA, observed)

`ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION, ACCESS_LOCAL_NETWORK, ACCESS_NETWORK_STATE,
ACCESS_WIFI_STATE, BLUETOOTH, BLUETOOTH_ADMIN, BLUETOOTH_CONNECT,
CHANGE_WIFI_MULTICAST_STATE, CHANGE_WIFI_STATE, DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION,
FOREGROUND_SERVICE, FOREGROUND_SERVICE_CONNECTED_DEVICE, FOREGROUND_SERVICE_LOCATION,
FOREGROUND_SERVICE_MICROPHONE, INTERNET, MANAGE_OWN_CALLS, MODIFY_AUDIO_SETTINGS,
NEARBY_WIFI_DEVICES, PACKAGE_USAGE_STATS, POST_NOTIFICATIONS, READ_EXTERNAL_STORAGE,
RECEIVE_BOOT_COMPLETED, RECORD_AUDIO, REQUEST_INSTALL_PACKAGES, SYSTEM_ALERT_WINDOW,
WAKE_LOCK, WRITE_EXTERNAL_STORAGE, WRITE_SETTINGS`

AiPlay needs a strict subset: `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`,
`CHANGE_WIFI_MULTICAST_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`,
`POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`.

## Appendix B — ADB cookbook

```sh
# 1. Is Android Auto present but disabled?
adb shell pm list packages -d | grep -i gearhead

# 2. Enable it for the current user
adb shell pm enable --user 0 com.google.android.projection.gearhead

# 3. Give it the permissions a head unit needs
adb shell pm grant com.google.android.projection.gearhead android.permission.ACCESS_FINE_LOCATION

# 4. Confirm the service is visible
adb shell cmd package resolve-activity --brief com.google.android.projection.gearhead

# 5. Reboot the unit
adb reboot
```

## Appendix C — Evidence index

| Evidence | Value |
| --- | --- |
| REA evidence id (DiPlay package) | `ev_803ee07065be8389fdca96e2abaf73997cb81ee6a2d0f41c61924c08a5f304a7` |
| Artifact SHA-256 | `916c0708beb1022d4ea9678ff6a46dfb6d234f707837a2cbd80d615e8e2bb2e3` |
| Engine | `jadx-headless-mcp 0.7.1`, jar `6e5eacf5…ff75`, source rev `5844800a…` |
| Classes / resources | 13,541 / 358 |
| Protocol reference | `aasdk_proto/ControlMessageIdsEnum.proto` (f1xpl/aasdk, GPL-3.0) |
