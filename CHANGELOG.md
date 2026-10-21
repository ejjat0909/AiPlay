# Changelog

All notable changes to AiPlay. Format follows Keep a Changelog; versions follow SemVer.

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
