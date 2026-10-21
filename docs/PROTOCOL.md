# Android Auto wire protocol as implemented by AiPlay

Scope: what AiPlay actually speaks in 0.1.0, what is verified, and what is deliberately
left to 0.2.0. Anything not listed here is **not** implemented.

## 1. Layering

```
TCP  →  AA frame (plain)  ──────────────┬─ VERSION_REQUEST / RESPONSE      (raw, in the clear)
                                        ├─ SSL_HANDSHAKE ×n                 (TLS 1.2 records, in the clear)
                                        ├─ AUTH_COMPLETE                    (protobuf, in the clear)
                                        └─ everything else: each frame payload is one TLS record
```

The socket carries plain AA frames. TLS is not on the socket: the head unit is the TLS **client**
and runs the handshake inside `SSL_HANDSHAKE` messages; afterwards every frame (except those three
message types) has the `ENCRYPTED` flag and an encrypted payload. Wireless Android Auto reaches the
head unit's TCP port because a Bluetooth exchange told the phone its address and port (not yet
implemented, see ROADMAP); USB accessory mode (AOA) is a 0.3.0 transport.

## 2. Frame format (verified)

```
 offset  size  field
 0       1     channel id            (ChannelId.hpp: control=0, input=1, sensor=2, video=3,
                                     media audio=4, speech audio=5, system audio=6,
                                     av input=7, bluetooth=8)
 1       1     flags                 frame type | ENCRYPTED (8) | CONTROL (4)
 2       2     size, big endian      payload bytes in this frame (SHORT form)
 2       6     size + total, BE      FIRST frame of a split message: size u16, total u32
 then    n     payload
```

Frame type is `MIDDLE=0`, `FIRST=1`, `LAST=2`, `BULK=FIRST|LAST=3`. A message that fits in
`cMaxFramePayloadSize = 0x4000` bytes travels as one `BULK` frame; anything larger is split
into `FIRST` (which carries the extended size), zero or more `MIDDLE`, then `LAST`.

The **message payload begins with the message id**, two bytes big endian, followed by the
protobuf:

```
 message payload = [message id u16 BE][protobuf]
```

Implementation: `core/aa/AaFrameCodec.java`, `core/aa/AaChannel.java`, `core/aa/AaFrame.java`.
Tests: `AaFrameCodecTest` (hand-computed vectors, split/reassembly, byte-at-a-time
fragmentation, encrypted-frame rejection, continuation-without-FIRST, bounds, unfulfilled
declaration).

Citations: aasdk `src/Messenger/MessageOutStream.cpp` (header written first, then the size),
`FrameHeader.cpp`, `FrameSize.cpp`, `MessageId.cpp`, `ChannelId.hpp`, `FrameType.hpp`,
`EncryptionType.hpp`, `MessageType.hpp`.

### 2.1 Correction: 0.1.0–0.1.2 framing was wrong

Until 0.1.3 AiPlay wrote `[size u16][message id u16][protobuf]` — no channel id, no flags,
no split support, and the message id in the header instead of the payload. That format is
self-consistent, which is why `tools/aa-probe.py` reported "handshake complete" against it,
but **no real Android Auto phone speaks it**: a phone's first control frame
`[channel=0][flags=0x07][size][message id]…` would have been read as a 0x0007 message with the
message id inside the protobuf. Every "handshake complete" before 0.1.3 was AiPlay talking to
AiPlay. The 0.1.3 codec follows the aasdk format above, and the probe was rewritten to match,
so the loopback test now exercises the real contract.

### 2.2 Correction: the transport was not TLS-on-TCP (fixed in 0.2.0-alpha4)

0.1.x–0.2.0-alpha3 ran a TLS *server* directly on the TCP socket. That was self-consistent with
`tools/aa-probe.py` and the JVM tests (the same trap as 0.1.0–0.1.2's framing) but a real phone
would have sent plain AA bytes into a TLS server. aasdk
(`Messenger/Cryptor.cpp`, `Transport/SSLWrapper.cpp`, `Messenger/MessageOutStream.cpp`,
`MessageInStream.cpp`) and OpenAuto's `AndroidAutoEntity.cpp` (which drives it) do this instead:

1. the head unit sends `VERSION_REQUEST`; the phone answers `VERSION_RESPONSE`;
2. the head unit — the TLS **client**, `TLSv1_2_client_method`, `SSL_set_connect_state` — runs
   `SSL_do_handshake` over memory BIOs and sends the bytes it produced in an `SSL_HANDSHAKE`
   message; the phone's bytes come back the same way, until the handshake is done;
3. the head unit sends `AUTH_COMPLETE` (still in the clear);
4. from then on every frame carries `ENCRYPTED`. **Each frame's payload chunk is its own TLS
   record**; the frame size on the wire is the *encrypted* size, while the `total` in a FIRST frame
   is the *plaintext* size of the whole message.

Implemented in `core/aa/AaCryptor.java` (an `SSLEngine` in client mode; "bytes in, bytes out" for the
handshake, `encrypt`/`decrypt` for frames), `AaFrameCodec` (per-frame cipher; the parser hands each
message to a listener as soon as it completes, because the handler of the last `SSL_HANDSHAKE`
switches decryption on and the next frame in the same read may already be encrypted) and
`AaSession` (the state machine). The listener is now a plain `ServerSocket` (`receiver/AaServer`).

### 2.3 Two more corrections found in the same pass (also fixed in 0.2.0-alpha4)

* **`VERSION_REQUEST`/`VERSION_RESPONSE` are not protobuf.** aasdk writes two (three, for the
  response) big-endian 16-bit numbers: `00 01 00 01` for 1.1; `[major][minor][status]` back, with
  status `MATCH = 0`, `MISMATCH = 0xFFFF`, and a missing status counted as a mismatch. AiPlay sent a
  protobuf (`08 01 10 06`) — also four bytes long, which is why it looked fine — and the version was
  1.6 with no source; aasdk announces **1.1** (`include/f1x/aasdk/Version.hpp`).
* **The `CONTROL` flag is not "this is a control-channel message".** In aasdk it is
  `MessageType::CONTROL` and is set only on channel-level control messages on a *non-zero* channel
  (channel open request/response). Everything on channel 0 — version, handshake, auth, service
  discovery, ping, shutdown — is `SPECIFIC`, flag clear: a version request is `00 03 00 06 …`, not
  `00 07 …`. A real phone's channel-0 messages would not have matched AiPlay's `control` branch.
  Dispatch is now by channel and message id.

### 2.4 Head-unit identity (and what is *not* verified)

In aasdk the head unit presents a certificate and private key as TLS client certificate, and does
not verify the phone's. The certificate it embeds is a real credential: issued by *Google Automotive
Link* to *JVC Kenwood*, with its private key published in the repository. **AiPlay does not use
it**: borrowing another vendor's licensed head-unit identity is an impersonation workaround, which
the project rules out. AiPlay presents its own self-signed certificate
(`res/raw/receiver_identity.p12`) and, like aasdk, accepts the phone's certificate without checking
it (`AaCryptor.TrustPhone`: the phone's certificate chains to nothing a head unit could validate;
what gates a session is the Bluetooth pairing and Wi-Fi credentials, not this handshake).

**Unverified: whether a real phone accepts a head unit with a self-signed certificate.** Every
test uses a server that accepts any client certificate that is *present*; the JVM tests also make
the fake phone advertise an issuer list that excludes AiPlay's certificate, as a real phone's
`CertificateRequest` does, which is why the head unit's key manager (`AaCryptor.AlwaysPresent`)
sends its certificate regardless of that list. If phones turn out to require a Google-issued
chain, AiPlay cannot work as a head unit without a credential it is not entitled to; that would
need a decision, not a workaround.

## 3. Message identifiers (verified)

Control-channel ids, from `aasdk_proto/ControlMessageIdsEnum.proto` (f1xpl/aasdk, GPL-3.0):

| Id | Name | behaviour |
| --- | --- | --- |
| `0x0001` | `VERSION_REQUEST` | **sent** on session start, raw `[major u16][minor u16]` = 1.1, in the clear |
| `0x0002` | `VERSION_RESPONSE` | raw `[major][minor][status]`; mismatch ends the session; match starts TLS |
| `0x0003` | `SSL_HANDSHAKE` | **sent and parsed**: raw TLS 1.2 handshake bytes, head unit is the client, in the clear |
| `0x0004` | `AUTH_COMPLETE` | **sent** with `status = OK` once TLS is up; still in the clear |
| `0x0005` | `SERVICE_DISCOVERY_REQUEST` | parsed, logged |
| `0x0006` | `SERVICE_DISCOVERY_RESPONSE` | **sent** with channel descriptors |
| `0x0007` | `CHANNEL_OPEN_REQUEST` | sent by the phone **on the channel it wants**; answered on that channel, `CONTROL`-typed: `OK` for video, `FAIL` otherwise |
| `0x0008` | `CHANNEL_OPEN_RESPONSE` | **sent** |
| `0x000b`/`0x000c` | `PING_REQUEST` / `PING_RESPONSE` | answered |
| `0x000f`/`0x0010` | `SHUTDOWN_REQUEST` / `RESPONSE` | acknowledged |
| `0x000d`…`0x0013` | navigation focus, voice, audio focus | logged, not answered |

Channel-scoped ids live in `AVChannelMessageIdsEnum.proto` and are **not** control ids: on the
video/audio channels `SETUP_REQUEST = 0x8000`, `START_INDICATION = 0x8001`,
`STOP_INDICATION = 0x8002`, `SETUP_RESPONSE = 0x8003`, `AV_MEDIA_ACK_INDICATION = 0x8004`,
`AV_INPUT_OPEN_REQUEST = 0x8005`, `AV_INPUT_OPEN_RESPONSE = 0x8006`,
`VIDEO_FOCUS_REQUEST = 0x8007`, `VIDEO_FOCUS_INDICATION = 0x8008`, and media payloads arrive
with `AV_MEDIA_WITH_TIMESTAMP_INDICATION = 0x0000` / `AV_MEDIA_INDICATION = 0x0001`.

## 4. Message layouts (verified field numbers)

From the aasdk protobuf definitions:

```proto
// VersionRequest / VersionResponse are NOT protobuf: raw big-endian u16s, see 2.3.
message AuthCompleteIndication { required Status.Enum status = 1; }        // OK = 0
message ServiceDiscoveryRequest { string device_name = 4; string device_brand = 5; }
message ServiceDiscoveryResponse {
  repeated ChannelDescriptor channels = 1;
  string head_unit_name = 2;  string car_model = 3;  string car_year = 4;
  string car_serial = 5;      bool left_hand_drive_vehicle = 6;
  string headunit_manufacturer = 7;  string headunit_model = 8;
  string sw_build = 9;        string sw_version = 10;
  bool can_play_native_media_during_vr = 11;  bool hide_clock = 12;
}
message ChannelDescriptor {
  uint32 channel_id = 1;
  SensorChannel sensor_channel = 2;  AVChannel av_channel = 3;
  InputChannel input_channel = 4;    AVInputChannel av_input_channel = 5;
  BluetoothChannel bluetooth_channel = 6;
  NavigationChannel navigation_channel = 8;
  VendorExtensionChannel vendor_extension_channel = 12;
}
message AVChannel {
  AVStreamType.Enum stream_type = 1;   // AUDIO = 1, VIDEO = 3
  AudioType.Enum audio_type = 2;       // SPEECH = 1, SYSTEM = 2, MEDIA = 3
  repeated AudioConfig audio_configs = 3;  // sample_rate, bit_depth, channel_count
  repeated VideoConfig video_configs = 4;  // resolution, fps, margins, dpi, depth
  bool available_while_in_call = 5;
}
message ChannelOpenResponse { required Status.Enum status = 1; }
```

Channel ids in the descriptors are the same numbers the frames use (`AaChannel`: input 1,
sensor 2, video 3, media audio 4, speech audio 5, …). 0.1.x numbered its descriptors 1–4, which
did not match the frame channel ids; 0.2.0 fixes that.

AiPlay advertises **only what it can serve**: since 0.2.0 that is the video channel,
`1280×720@30` (`AaControl.ServiceDiscoveryConfig`). Audio and input are still implemented as
descriptors but switched off, so a phone is never offered a channel that is then refused.

```proto
message ChannelOpenRequest { int32 priority = 1; int32 channel_id = 2; }
message AVChannelSetupRequest  { uint32 config_index = 1; }
message AVChannelSetupResponse { required AVChannelSetupStatus.Enum media_status = 1;  // FAIL = 1, OK = 2
                                 required uint32 max_unacked = 2; repeated uint32 configs = 3; }
message AVChannelStartIndication { int32 session = 1; uint32 config = 2; }
message AVMediaAckIndication { required int32 session = 1; required uint32 value = 2; }
message VideoFocusRequest    { int32 disp_index = 1; VideoFocusMode.Enum focus_mode = 2;  // FOCUSED = 1
                               VideoFocusReason.Enum focus_reason = 3; }
message VideoFocusIndication { VideoFocusMode.Enum focus_mode = 1; bool unrequested = 2; }
```

Media: `MEDIA_WITH_TIMESTAMP` (`0x0000`) is `[timestamp u64 BE µs][Annex-B H.264]`; `MEDIA`
(`0x0001`) is the H.264 alone. Both travel on the video channel with the control flag clear.

## 5. Session as implemented (0.2.0-alpha4)

```
head unit (TLS client)                           phone (TLS server)
   │──── VERSION_REQUEST 1.1  [raw, plain] ──────────▶│
   │◀──── VERSION_RESPONSE [raw, plain] ───────────────│
   │──── SSL_HANDSHAKE (ClientHello) [plain] ─────────▶│
   │◀──── SSL_HANDSHAKE (ServerHello … Done) ──────────│     TLS 1.2, in the clear, inside AA frames
   │──── SSL_HANDSHAKE (Certificate … Finished) ──────▶│
   │◀──── SSL_HANDSHAKE (ChangeCipherSpec, Finished) ──│
   │──── AUTH_COMPLETE (OK) [plain] ──────────────────▶│
   │         ════════ every frame below is ENCRYPTED ════════
   │◀──── SERVICE_DISCOVERY_REQUEST ───────────────────│
   │──── SERVICE_DISCOVERY_RESPONSE (video) ──────────▶│
   │◀──── CHANNEL_OPEN_REQUEST   [video ch, CONTROL] ───│
   │──── CHANNEL_OPEN_RESPONSE (OK) [video ch, CONTROL] ▶│
   │◀──── SETUP_REQUEST (config 0) ─────────────────────│
   │──── SETUP_RESPONSE (OK, max_unacked 1, [0]) ─────▶│
   │◀──── VIDEO_FOCUS_REQUEST (FOCUSED) ────────────────│
   │──── VIDEO_FOCUS_INDICATION (FOCUSED) ─────────────▶│
   │◀──── START_INDICATION (session, config) ───────────│
   │◀──── MEDIA_WITH_TIMESTAMP … ───────────────────────│   H.264, one picture per message
   │──── MEDIA_ACK (session, 1) ────────────────────────▶│   one ack per picture
   │◀──── STOP_INDICATION ──────────────────────────────│
```

Verified by:

* `AaTransportTest` — a real TCP loopback against a fake phone with a **server-mode `SSLEngine` that
  requires a client certificate**: the exact order above, every message's flags and encryption
  checked, a 60 kB picture split into several encrypted frames, version mismatch, and an encrypted
  frame before TLS being rejected;
* `AaCryptorTest` (TLS 1.2, client certificate sent despite a non-matching issuer list, handshake
  flights delivered 7 bytes at a time, garbage records rejected), `AaFrameCodecTest` (per-frame
  encryption, plaintext `total`, cipher installed between two messages of one read);
* `tools/aa-probe.py` against `tools/AaHarness.java`: the probe's TLS server is **OpenSSL**
  (`ssl.MemoryBIO`), the head unit's client is JSSE/Conscrypt, so the in-band TLS is checked
  across two independent implementations; 90 pictures acked, stream byte-identical to the clip;
* `tools/device-test.sh` on an Android emulator: same flow against the real APK.

The mutation checks that guard this are in the 0.2.0-alpha4 changelog entry.

## 6. Not implemented

| Feature | Notes |
| --- | --- |
| BLE advertisement + headset profile | what the dongle does besides listening on RFCOMM (section 8.2); may be needed to make a phone connect |
| Microphone (`AV_INPUT`) | voice assistant through the head unit's mic |
| Location sensor | not offered; OpenAuto leaves it out too |
| Bluetooth channel (`BLUETOOTH`, 8) | pairing helper only |
| Two-finger gestures | sent in the message shape, unverified against a phone |

## 8. Bluetooth bootstrap (wireless Android Auto) — 0.2.0-alpha5

A phone does not scan the LAN for a head unit. It learns where to connect over **Bluetooth**: the
head unit exposes an RFCOMM service, the phone connects, and they exchange a few small messages
that name the TCP address and the Wi-Fi network. Implemented in `core/bt/WifiBootstrap.java`
(pure Java, tested) and `receiver/BluetoothBootstrapServer.java` (Android RFCOMM glue, **not run on
hardware**).

```
head unit (RFCOMM server)                    phone
   │── WifiStartRequest  {ip, port} ───────────▶│   id 1: where the head unit's TCP server is
   │◀──────────────────── WifiInfoRequest ──────│   id 2
   │── WifiInfoResponse  {ssid,key,bssid,…} ───▶│   id 3: which Wi-Fi network to join
   │◀── two more messages (ids 4–7) ───────────│   read and logged, not answered
   │      … the phone joins the Wi-Fi and opens TCP to ip:port; section 2.2 takes over …
```

* Service UUID `4de17a00-52cb-11e6-bdf4-0800200c9a66`, the head unit as RFCOMM **server**.
* Frame `[payload size u16 BE][message id u16 BE][protobuf]`; the size excludes the 4 header bytes.
* ids: `WifiStartRequest 1, WifiInfoRequest 2, WifiInfoResponse 3, WifiVersionRequest 4,
  WifiVersionResponse 5, WifiConnectStatus 6, WifiStartResponse 7`.

```proto
message WifiStartRequest  { required string ip_address = 1; required int32 port = 2; }
message WifiInfoResponse  { required string ssid = 1; required string key = 2; required string bssid = 3;
                            required SecurityMode security_mode = 4;     // OPEN 1, WPA2_PERSONAL 8, WPA_WPA2_PERSONAL 12 …
                            required AccessPointType access_point_type = 5; }  // STATIC 0, DYNAMIC 1
```

All fields are `required` (proto2), so zero values (`STATIC = 0`) are still written.

### 8.1 How well-founded this is

**Weaker than the aasdk-based sections.** aasdk and OpenAuto do not implement this exchange (OpenAuto's
`AndroidBluetoothServer` only accepts the connection). Everything above comes from **one** project,
nisargjhaveri/WirelessAndroidAutoDongle (`bluetoothProfiles.cpp`, `bluetoothHandler.cpp`,
`proto/WifiStartRequest.proto`, `proto/WifiInfoResponse.proto`), which is a working head-unit stand-in,
so the sequence is believable, but it was read, not independently confirmed, and the dongle simply reads
(and ignores) the last two messages, so their content is unknown to AiPlay.

### 8.2 What is not implemented, and why a phone may still not connect

The dongle does more than listen. It also **advertises the UUID over BLE** and, outside dongle mode,
**connects a headset (HSP) profile to the phone**, which appears to be what makes the phone's Android Auto
open the RFCOMM connection. AiPlay does neither: it only listens. Whether a paired Android unit's ordinary
Bluetooth link is enough to trigger the phone is **unknown**. The phone must also already be paired with
the unit. These are the first things to check if the phone never connects: Diagnostics shows `bluetooth:`
lines for each step, and "phone connected" is the first sign of progress.

### 8.3 What the unit tells the phone

* **IP and port**: the unit's own Wi-Fi/Ethernet IPv4 address and the receiver port.
* **SSID and password**: from Settings > Wi-Fi for the phone. Android does not let an app read the
  password of the network it is on, so it is entered once. It is stored in the app's private
  preferences, **not encrypted**, and sent to any phone that connects to AiPlay's Bluetooth service
  (the phone must be paired with the unit, which is the only gate). With no SSID configured the details
  are not sent at all.
* **BSSID**: the current access point's address when Android reveals it (needs the location permission);
  otherwise empty. Whether the phone needs it is unknown.

## 9. Input, sensor and audio (0.2.0-alpha6)

All ids and fields below were read from aasdk, and behaviour checked against OpenAuto's
`InputService.cpp`, `SensorService.cpp`, `AudioService.cpp`, `ServiceFactory.cpp` and
`AndroidAutoEntity.cpp`. Like everything else here, none of it has met a real phone.

### 9.1 Input flows head unit -> phone

The head unit **captures** touches and keys on its own screen and **sends** them. The phone sends exactly
one message on this channel, `BINDING_REQUEST` (`0x8002`, repeated `scan_codes = 1`), naming the keys it
wants; the head unit answers `BINDING_RESPONSE` (`0x8003`, `status`, OK only if every code was advertised).
After that the head unit sends `INPUT_EVENT_INDICATION` (`0x8001`):

```proto
message InputEventIndication { uint64 timestamp = 1;            // microseconds
                               TouchEvent touch_event = 3; ButtonEvents button_event = 4; ... }
message TouchEvent    { repeated TouchLocation touch_location = 1;   // every pointer
                        uint32 action_index = 2; TouchAction touch_action = 3; }  // PRESS 0, RELEASE 1, DRAG 2
message TouchLocation { uint32 x = 1; uint32 y = 2; uint32 pointer_id = 3; }
message ButtonEvent   { uint32 scan_code = 1; bool is_pressed = 2; uint32 meta = 3; bool long_press = 4; }
```

* **Coordinates** are in the `TouchConfig` width x height sent in service discovery, which AiPlay sets to the
  video resolution (1280x720). That the phone wants the *video's* coordinate space is the convention in
  aasdk-based head units; the protobuf files do not say so.
* **Letterboxing**: the video is centred 16:9 inside the screen, so a touch is mapped relative to the video
  rectangle (`core/input/TouchMapper`), not the screen. A gesture that starts on a letterbox bar is not sent;
  one that leaves the video is clamped to its edge. Verified on 1920x1080, 1024x600 and 1280x480 screens.
* **Keys**: advertised `UP DOWN LEFT RIGHT ENTER` and `TOGGLE_PLAY NEXT PREV PLAY PAUSE` (Android key codes,
  which equal aasdk's `ButtonCode`). **HOME and BACK are not advertised or forwarded**: on the head unit they
  leave the projection screen. A key is advertised only if it is forwarded. Auto-repeat is not forwarded.
* **Multi-touch** sends every pointer with the changed index as `action_index`, following the message shape.
  OpenAuto only ever sends one pointer; two-finger gestures are **unverified**.
* Touches are written from a dedicated thread (the UI thread may not use the network); surplus `DRAG` events are
  dropped when the network falls behind, presses and releases never.

### 9.2 Sensor channel

The phone opens the channel and sends `SENSOR_START_REQUEST` (`0x8001`, `sensor_type = 1`) per sensor; the head
unit answers `SENSOR_START_RESPONSE` (`0x8002`) and pushes `SENSOR_EVENT_INDICATION` (`0x8003`). AiPlay offers
the two sensors OpenAuto offers: **driving status** (type 13, always `UNRESTRICTED`: the unit has no speed source)
and **night mode** (type 10, from the head unit's own UI mode, pushed again whenever it changes). Location is not
offered. Whether a given navigation app insists on more than this is unknown.

### 9.3 Audio

Audio travels on the AV channels exactly like video (open, setup, start, `MEDIA_WITH_TIMESTAMP`, one ack per
message with `max_unacked = 1`, stop); the payload is 16-bit little-endian PCM:

| Channel | id | format | `AudioStream` / usage |
| --- | --- | --- | --- |
| media | 4 | 48 kHz stereo | `USAGE_MEDIA` |
| speech (navigation prompts, assistant) | 5 | 16 kHz mono | `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` |
| system sounds | 6 | 16 kHz mono | `USAGE_ASSISTANCE_SONIFICATION` |

* **Focus** (control channel): `AUDIO_FOCUS_REQUEST` (`0x12`) is answered `GAIN`, or `LOSS` for a release;
  `NAVIGATION_FOCUS_REQUEST` (`0x0d`) is answered type 2. Both exactly as OpenAuto does; aasdk defines finer
  states (e.g. `GAIN_TRANSIENT_GUIDANCE_ONLY`) and which a phone prefers is unknown.
* **Playback** never blocks the session thread: audio goes through a bounded queue (1.5 s; the *oldest* audio is
  dropped on overflow) to a per-channel `AudioTrack` thread, so a slow audio device cannot stall video or input.
  On stop the queued tail still plays.
* **Ducking** is AiPlay's own design (OpenAuto does not do it): media drops to 25% while a speech or system
  stream is active and returns afterwards. It relies on the phone starting and stopping those streams per
  prompt, which is not confirmed for every phone and app.
* **Not implemented**: the microphone (`AV_INPUT`, channel 7), so "OK Google" through the head unit's mic does
  not work. AiPlay does not request Android audio focus on the head unit either.

## 7. Honesty boundary

"Verified" above means: the wire format matches a public, citable definition and is
covered by a unit or integration test in `tests/`. It does **not** mean a phone has
accepted it. That step needs hardware and is tracked in [TESTING.md](TESTING.md).
