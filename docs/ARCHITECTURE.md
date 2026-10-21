# Architecture

AiPlay is one Android module, no third-party runtime dependencies, 96 classes and a
98 KB DEX. The design constraint that produced that number: it must cold-start in under a
second on a 2018 MediaTek head unit with 2 GB of RAM.

## 1. Layers

```
┌──────────────────────────────────────────────────────────────┐
│ ui/          Activities + custom views. No XML layouts.      │
├──────────────────────────────────────────────────────────────┤
│ device/      Thin Android adapters: probe, shell, prefs, log │
├──────────────────────────────────────────────────────────────┤
│ receiver/    Foreground service, TCP listener, mDNS          │
├──────────────────────────────────────────────────────────────┤
│ core/        PURE JAVA. No android.* import. JVM-tested.     │
└──────────────────────────────────────────────────────────────┘
```

The `core/` boundary is enforced twice:

1. `tools/test.sh` greps for `^import android.` under `core/` and fails the build.
2. `core/` is compiled against the plain JDK, so an accidental Android type would not
   even resolve.

## 2. Core (pure, tested)

| Class | Responsibility | Tests |
| --- | --- | --- |
| `DeviceProfile` | Immutable device facts + capability list | via engine |
| `ReadinessEngine` | `DeviceProfile → Verdict` (P1–P5, score, action) | 9 |
| `PrivilegePlan` | The exact shell commands and the ADB recipe | 5 |
| `SessionStateMachine` | Guarded receiver lifecycle | 7 |
| `aa.AaFrameCodec` | AA framing, streaming parser, bounds | 10 |
| `aa.ProtoWriter` / `ProtoReader` | Minimal protobuf wire format | 6 |
| `aa.AaControl` | Message builders/parsers with cited field numbers | 7 |
| `aa.AaSession` | Control-plane state machine over a byte stream | 8 |

## 3. Android adapters

| Class | Notes |
| --- | --- |
| `CapabilityProbe` | Package manager, `Build`, `Settings.Global`, one `su` probe, one loopback bind. Bounded at ~300 ms, runs off the UI thread. |
| `PrivilegedShell` | Only two entry points: `runAsRoot` and `runShell`. 12 s timeout, output capped at 8 KB. |
| `GearheadController` | `pm enable` / `pm install-existing` / launch intent. All command strings come from `PrivilegePlan`. |
| `Diag` | 512-line ring buffer, crash handler, report builder. No SSIDs, no passwords, no MACs. |
| `ReportStore` / `ReportProvider` | Saves to app-specific external storage; a 60-line `ContentProvider` serves the file for sharing without AndroidX. |

## 4. Receiver

```
AaReceiverService (foreground, connectedDevice)
   ├── AaServer         ServerSocket (plain AA), PKCS12 identity from res/raw for in-band TLS
   │      └── AaSession (core)  ← pure control-plane logic
   └── MdnsAdvertiser   NsdManager `_androidauto._tcp`
```

`AaServer` does only three things: load the keystore, accept sockets, hand the streams
to `AaSession`. Everything protocol-shaped lives in `core`, which is why the handshake can
be integration-tested on a desktop JVM over a real TCP socket with TLS carried in-band.

## 5. UI

Screens are built in Java, not XML, for one reason: the design tokens (`Ui.BG0`,
`Ui.RADIUS_CARD_DP`, …) then have exactly one definition and cannot drift between layouts.
The cost is that layout changes are code changes; the benefit is a consistent 217 KB APK
with no AppCompat, no Material Components and no resource indirection.

| Screen | Job |
| --- | --- |
| `SplashActivity` + `SplashView` | 1.6 s brand animation, tap to skip, 0 assets |
| `HomeActivity` | Verdict, readiness gauge, one primary action, capability evidence |
| `SetupActivity` | ADB cookbook, copy to clipboard, inspect current state |
| `DiagnosticsActivity` | Full report, copy, save, share |
| `SettingsActivity` | Six preferences |

## 6. Threading

| Work | Thread |
| --- | --- |
| Probe + evaluate | `aiplay-probe` thread, result posted to main |
| Privileged shell | `aiplay-fix` thread |
| TCP accept | `aiplay-accept` thread |
| Session I/O | one thread per connection (`aiplay-session-N`) |
| Animation | main thread, one `ValueAnimator` |

No `AsyncTask`, no executors held in static fields, no work that outlives the activity
without a service.

## 7. Failure behaviour

* A probe that throws returns a partial profile rather than crashing the home screen.
* A shell that times out returns `code = -1` with the partial output and is shown verbatim.
* A keystore or mDNS failure stops the service and records the reason; the UI never shows
  "running" for a receiver that is not listening.
* The splash has a 4 s watchdog so a broken animation cannot trap the driver.
