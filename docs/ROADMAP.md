# Roadmap

## 0.1.0 — Unlock (this release)

- [x] Capability audit for P1–P5 with evidence rows
- [x] Enable / repair Android Auto through a root shell, ADB recipe otherwise
- [x] Splash, home, setup, diagnostics, settings
- [x] AA control plane: framing, version, auth, service discovery, ping
- [x] mDNS advertisement + TLS listener
- [x] 62 JVM tests, including a real TLS loopback handshake
- [x] 217 KB APK, no dependencies

## 0.2.0 — Projection

Acceptance: a real phone completes discovery **and** renders its UI on the head unit.

- [ ] Video channel: `AVChannelSetup`, `AVChannelStart`, media frames, H.264 → `MediaCodec` → `SurfaceView`
- [ ] Media audio: `AudioTrack` 48 kHz stereo with ack cadence
- [ ] Speech audio with media ducking
- [ ] Touch input: `TouchEventData` → `MotionEvent`, coordinate mapping per display
- [ ] Sensor channel: day/night + driving status
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
