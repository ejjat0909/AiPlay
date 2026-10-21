# Roadmap

## 0.1.0 — Unlock (this release)

- [x] Capability audit for P1–P5 with evidence rows
- [x] Enable / repair Android Auto through a root shell, ADB recipe otherwise
- [x] Splash, home, setup, diagnostics, settings
- [x] AA control plane: framing, version, auth, service discovery, ping
- [x] mDNS advertisement + TLS listener
- [x] 63 JVM tests, including a real TLS loopback handshake
- [x] 217 KB APK, no dependencies

## 0.2.0 — Projection

Acceptance: a real phone completes discovery **and** renders its UI on the head unit.

- [x] Video channel on the wire: open, setup, focus, start/stop, media + acks (0.2.0-alpha1, loopback-verified)
- [x] H.264 → `MediaCodec` → `SurfaceView` (`ProjectionActivity`) — compiles; **not yet run on a device**
- [x] Transport rework: plain TCP, in-band TLS as the *client* via `SSL_HANDSHAKE`, per-frame encryption, raw version messages, flag semantics (0.2.0-alpha4, PROTOCOL.md 2.2–2.3) — verified on a JVM, against OpenSSL, and on an emulator; **not against a real phone**
- [ ] **Open question: does a phone accept a self-signed head-unit certificate?** (PROTOCOL.md 2.4) — can only be answered with a phone
- [x] Bluetooth bootstrap message exchange (0.2.0-alpha5): RFCOMM service, `WifiStartRequest`/`WifiInfoResponse`; tested over a loopback, **RFCOMM glue never run on hardware** (PROTOCOL.md 8)
- [ ] Make a phone actually connect: BLE advertisement of the service UUID and a headset-profile nudge, as the reference dongle does (PROTOCOL.md 8.2) — needs a real phone
- [x] Media audio: `AudioTrack` 48 kHz stereo with ack cadence (0.2.0-alpha6)
- [x] Speech audio with media ducking (0.2.0-alpha6; the ducking trigger is unconfirmed on phones)
- [x] Touch input: screen → `TouchEvent` → phone, letterbox-aware mapping (0.2.0-alpha6; direction corrected: head unit sends)
- [x] Sensor channel: day/night + driving status (0.2.0-alpha6)
- [ ] Session recording: store a real handshake as a replayable fixture
- [ ] Compatibility database of observed phones/ROMs

## 0.3.0 — Wired

- [ ] USB AOA transport (`UsbManager`, accessory mode)
- [ ] Bluetooth bootstrap for wireless credentials
- [ ] Steering-wheel key mapping

## 0.4.0 — Polish

- [ ] Arabic, Malay, Simplified Chinese translations
- [ ] Multi-window / split-screen behaviour on 1280×480 bars
- [ ] Auto-start on car ignition (ACC) detection

## 1.0.0 — Stable

- [ ] Receiver verified on at least five head-unit families
- [ ] Crash-free rate measured over a release cycle
- [ ] Signed release with a documented update path (no OTA server)

## Explicitly out of scope

- CarPlay (that is DiPlay's job)
- Any Apple identity, MFi key or DRM workaround
- Bundling Google's Android Auto APK
- Root exploits or bootloader unlocking
