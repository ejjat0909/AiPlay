# AiPlay

**Android Auto on head units that were never sold with it.**

AiPlay is a small, offline Android app for aftermarket Android head units. It tells you *why*
your unit has no Android Auto — and fixes the fixable cases in one tap — then offers a native
Android Auto wireless endpoint for the units where nothing can be enabled.

* **217 KB APK**, no native libraries, no webview, no telemetry, no account.
* Works from **Android 7.0 (API 24)** to **Android 14 (API 34)**.
* Splash screen, home screen and diagnostics designed for a 1024×600 dashboard, not a phone.
* Evidence first: every green tick on the home screen was probed on *your* device, just now.

> AiPlay is unofficial. "Android Auto" is a Google product; AiPlay is not affiliated with,
> endorsed by or certified by Google. It ships no Google code and no Apple identity.

---

## What it actually does

| Your head unit | AiPlay's answer |
| --- | --- |
| Android Auto installed and enabled | **Open Android Auto** |
| Android Auto installed but disabled | **Enable Android Auto** — one privileged `pm enable` |
| Android Auto enabled but hidden by the ROM | **Repair Android Auto** — re-install for user 0, grant, relaunch |
| No Android Auto package, Play services present | **Wireless receiver** (experimental) |
| No Android Auto and no Play services | **Wireless receiver** (experimental) — the only route left |
| No root and no ADB | A copy-paste **ADB recipe** that does exactly the same thing from a laptop |

The condition model (P1–P5) is documented in [PLAN.md §2](PLAN.md) and
[docs/COMPATIBILITY.md](docs/COMPATIBILITY.md).

## Status — read this before installing

| Layer | State |
| --- | --- |
| Capability audit | **working**, unit-tested |
| Enable / repair Android Auto | **working** where a root shell exists, ADB recipe otherwise |
| Wireless receiver: discovery, TLS, AA control plane | **implemented and unit-tested** (real TLS loopback handshake), not yet verified against a live phone |
| Receiver: video / audio / touch channels | **not implemented** — milestone 0.2.0 |

The receiver is honest about this: it labels itself *handshake only (experimental)* in the app
and in its notification. It will let a phone find the unit and complete version negotiation and
service discovery; it will not draw CarPlay-style projection yet.

## Install

```sh
# build it yourself (no Gradle)
ANDROID_HOME=/path/to/android-sdk ./tools/build.sh
adb install -r build/AiPlay-0.1.0.apk
```

Requirements for the build: JDK 17, Android SDK build-tools 34 and platform 34. Nothing else.
See [docs/BUILD.md](docs/BUILD.md).

## Screens

| Splash | Home |
| --- | --- |
| ![splash](docs/splash-preview.png) | ![home](docs/home-preview.png) |

Both images are **design reference renders** produced by `tools/gen-preview.py` from the same
geometry, palette and easing constants as `SplashView.java` and `HomeActivity.java`. They are
not device screenshots — see [docs/TESTING.md](docs/TESTING.md) for what was and was not run
on hardware.

## Why not just port DiPlay?

Because DiPlay is a **CarPlay** receiver. It speaks AirPlay, RTSP, RTP, IAP2 and MFi — an
entirely different protocol family from Android Auto's TLS + protobuf channels. AiPlay keeps
DiPlay's *product architecture* (endpoint lifecycle, display handoff, audio sink, diagnostics)
and replaces every byte on the wire.

The evidence for that statement is in [docs/REA-ANALYSIS.md](docs/REA-ANALYSIS.md), produced by
running [REA](https://github.com/morluto/rea) against the shipped DiPlay 0.2.21 APK.

## Repository map

```
PLAN.md               engineering plan: problem, architecture, UI spec, protocol, milestones
docs/REA-ANALYSIS.md  what DiPlay 0.2.21 is, from the shipped artifact
docs/ARCHITECTURE.md  module map and runtime flows
docs/PROTOCOL.md      Android Auto wire format: verified vs unverified
docs/COMPATIBILITY.md P1–P5, per-ROM notes, ADB cookbook
docs/TESTING.md       what is tested, how, and what could not be tested here
docs/ROADMAP.md       0.2.0 → 1.0.0
app/                  manifest, resources, Java sources (no AndroidX)
tests/                JUnit 4 suite for the pure core (runs on a desktop JVM)
tools/                build.sh, test.sh, gen-icons.py
```

## Licence

GPL-3.0. AiPlay reuses the architecture and product ideas of
[DiPlay](https://github.com/shihabal3amri/DiPlay) (GPL-3.0, itself based on
[xcertplay](https://github.com/shilapi/xcertplay)) and the Android Auto protocol identifiers
published in [aasdk](https://github.com/f1xpl/aasdk) (GPL-3.0). Notices are preserved; see
[LICENSE](LICENSE) and [docs/THIRD-PARTY.md](docs/THIRD-PARTY.md).
