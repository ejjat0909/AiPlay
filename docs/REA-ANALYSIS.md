# Reverse engineering DiPlay 0.2.21 (REA)

This document is the evidence base for the claim in `README.md`: **DiPlay is a CarPlay
receiver, not an Android Auto app**, so AiPlay reuses its architecture rather than its
protocol. Everything below was produced from the *shipped artifact*, not from the source
tree, using [REA](https://github.com/morluto/rea) 6.7.0.

## 1. Method

| Step | Command |
| --- | --- |
| Tool install | `npm install -g rea-agents` (rea 6.7.0) |
| Android provider | `jadx-headless-mcp 0.7.1`, SHA-256 `6e5eacf5…ff75`, `REA_JADX_MCP_JAR` |
| Artifact | `DiPlay-0.2.21.apk`, SHA-256 `916c0708beb1022d4ea9678ff6a46dfb6d234f707837a2cbd80d615e8e2bb2e3` |
| Package inventory | `rea inspect-android-package rea-artifacts/DiPlay-0.2.21.apk --format json` |
| Class inventory | `rea inspect-android-class <apk> <fqcn> --format json` |

Evidence identifiers returned by the tool:

```
evidence_id : ev_803ee07065be8389fdca96e2abaf73997cb81ee6a2d0f41c61924c08a5f304a7
authority   : shipped-artifact
confidence  : observed
engine      : jadx-headless-mcp 0.7.1 (source revision 5844800a486d2248fa949b74c9896285f5b54de2)
classes     : 13,541        resources: 358
```

REA's own limitations apply and are quoted, not paraphrased: static decompiler
observations do not establish runtime behaviour, the APK is never executed, and method
signatures use provider display types.

## 2. What the manifest says

| Field | Value |
| --- | --- |
| Package | `com.shihab.diplay` |
| Version | `0.2.21` (code `40`) |
| SDK | min 24, target 37 |
| Activities | `DiPlayActivity`, `CarPlayHostActivity`, `CarPlayVideoActivity`, `AdbClusterActivity`, `ImageCropActivity`, `MainActivity` |
| Services | `DiPlaySessionService`, `WheelKeyService`, `CarPlayVpnService`, `UsbAutoConfirmService`, `MapEmbedService`, `PhoneAudioConnectionService` |
| Providers | `CrashRecordInstaller`, `DiagnosticReportProvider`, `UpdateApkProvider` |
| Features | `android.hardware.usb.host` |
| Native libs | `libspeex_echo.so`, `libxcertplay_i2c.so`, `liblocal_hotspot_radio.so`, `libandroidx.graphics.path.so` × 4 ABIs |

29 permissions, including `NEARBY_WIFI_DEVICES`, `CHANGE_WIFI_MULTICAST_STATE`,
`RECORD_AUDIO`, `MANAGE_OWN_CALLS`, `PACKAGE_USAGE_STATS`, `SYSTEM_ALERT_WINDOW` and
`WRITE_SETTINGS` — the profile of a projection receiver that owns the head unit's network,
audio and call state, not of a utility app.

## 3. What the class inventory says

| Class | Methods | Reading |
| --- | --- | --- |
| `orchestration.CarPlayController` | **278** | Session orchestrator: wired/wireless handoff, watchdogs, cluster UI, diagnostics |
| `airplay.AirPlaySession` | **79** | RTSP/RTSP-over-TCP session, cluster content, zoom |
| `media.AndroidMediaSink` | **66** | `MediaCodec` video/audio sink, renderer restart |

Package families, by file count:

| Package | Files | Protocol family |
| --- | --- | --- |
| `airplay/` | 43 | RTSP, bplist, SRP-6a pair setup, TLV8, ChaCha20-Poly1305, RTP audio, NTP |
| `network/` | 49 | Wi-Fi Direct group ownership, LocalOnlyHotspot, hotspot credential read/write, IPv6 NCM, join repair |
| `media/` | 45 | MediaCodec decode, frame pacing, audio buffer recovery, Opus, AEC |
| `transport/` | 42 | USB host bring-up, iAP2 link |
| `iap2/` | 7 | Apple accessory protocol, MFi SAP auth |
| `hud/` | 54 | BYD cluster/HUD vendor integration |

Two facts settle the question:

1. **`iap2/` + `mfi/` exist.** CarPlay requires an Apple accessory identity and the iAP2
   link. Android Auto has no equivalent — it authenticates with TLS and Google's own
   service layer.
2. **`airplay/Srp6a`, `Tlv8Codec`, `BplistCodec`, `RtspMessage` exist.** These are AirPlay
   primitives. Android Auto uses protobuf over a length-prefixed TLS stream, and has no
   RTSP, no bplist, no SRP.

## 4. What is worth reusing

| DiPlay concern | Why it matters | How AiPlay reuses it |
| --- | --- | --- |
| Endpoint lifecycle (`CarPlayController`) | A projection endpoint has to survive sleep, USB re-plug, Wi-Fi drops | AiPlay's `SessionStateMachine` + foreground service |
| Transport selection (`network/`, 49 files) | Getting the phone onto the same L2 network is the hardest real-world step | AiPlay advertises over mDNS and documents the same Wi-Fi topologies |
| Display handoff (`CarPlayVideoActivity`, `VideoSurfaceHandoff`) | A head unit is a multi-window device | v0.2.0 video channel design |
| Audio sink (`AndroidMediaSink`) | Decoder restart and underrun recovery dominate the bug list | v0.2.0 audio channel design |
| Diagnostics (`DiagnosticReportProvider`) | Remote debugging without logs is guesswork | `Diag` ring buffer + shareable report |
| Settings IA (7 categories, `AGENTS.md`) | A driver must find a setting in one glance | AiPlay's flat 6-setting screen, deliberately smaller |

## 5. What is *not* reused

* No Apple code, no MFi identity, no `offline-mfi` assets. AiPlay ships a self-signed
  TLS identity for its own endpoint and nothing else.
* No BYD vendor integration.
* No DiPlay source files. AiPlay is a clean-room implementation of the same *product
  shape* with a different protocol.

## 6. Reproducing this analysis

```sh
npm install -g rea-agents
mkdir -p ~/.local/share/rea/android && cd ~/.local/share/rea/android
curl -fL -o jadx-headless-mcp-0.7.1-all.jar \
  https://github.com/1013503897/jadx-headless-mcp/releases/download/v0.7.1/jadx-headless-mcp-0.7.1-all.jar
sha256sum -c <<< '6e5eacf500b64292bfb73c49797c1958f6ee44646e43e868039ae7feb573ff75  jadx-headless-mcp-0.7.1-all.jar'
export REA_JADX_MCP_JAR=$PWD/jadx-headless-mcp-0.7.1-all.jar
curl -fL -O https://github.com/shihabal3amri/DiPlay/releases/download/v0.2.21/DiPlay-0.2.21.apk
rea inspect-android-package DiPlay-0.2.21.apk --format json
rea inspect-android-class DiPlay-0.2.21.apk com.shilapi.xcertplay.orchestration.CarPlayController
```
