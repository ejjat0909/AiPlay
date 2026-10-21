# Changelog

All notable changes to AiPlay. Format follows Keep a Changelog; versions follow SemVer.

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
