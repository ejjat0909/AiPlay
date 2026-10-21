# Android Auto wire protocol as implemented by AiPlay

Scope: what AiPlay actually speaks in 0.1.0, what is verified, and what is deliberately
left to 0.2.0. Anything not listed here is **not** implemented.

## 1. Layering

```
TCP :5277  →  TLS 1.2/1.3  →  AA frame  →  protobuf control message   (AiPlay today; see 2.2: a real phone uses plain TCP with TLS inside the frames)
```

Discovery is mDNS (`_androidauto._tcp`). USB accessory mode (AOA) is a 0.3.0 transport.

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

### 2.2 Correction: the transport is not TLS-on-TCP (found while building 0.2.0)

Checking the video messages against aasdk's sources also showed how the TLS layer really works,
and AiPlay's does not match it. In aasdk (`Messenger/Cryptor.cpp`, `Transport/SSLWrapper.cpp`):

* the TCP connection carries **plain AA frames**; there is no TLS on the socket;
* TLS 1.2 runs **inside the protocol**, over memory BIOs: handshake records travel in
  `SSL_HANDSHAKE` (`0x0003`) control messages, and once it completes, later frames are sent with
  the `ENCRYPTED` flag and a TLS-protected payload;
* the **head unit is the TLS client** (`SSL_set_connect_state`, `TLS_client_method`) and the
  phone is the TLS server.

AiPlay 0.1.x–0.2.0-alpha1 instead runs a TLS *server* directly on tcp 5277. That is self-consistent
with `tools/aa-probe.py` and the JVM tests (the same trap as 0.1.0–0.1.2's framing), but **a real
phone would send plain AA bytes into a TLS server and the handshake would fail**. The video channel
below is independent of the transport and is verified; the transport is not. Replacing it
(in-band TLS with a `SSLEngine` in client mode, plain TCP, encrypted frames) is the next milestone,
and the wireless path also needs a Bluetooth bootstrap before the phone knows where to connect
(see ROADMAP).

## 3. Message identifiers (verified)

Control-channel ids, from `aasdk_proto/ControlMessageIdsEnum.proto` (f1xpl/aasdk, GPL-3.0):

| Id | Name | behaviour |
| --- | --- | --- |
| `0x0001` | `VERSION_REQUEST` | **sent** on session start |
| `0x0002` | `VERSION_RESPONSE` | parsed → `AUTH_COMPLETE` |
| `0x0003` | `SSL_HANDSHAKE` | not used (TLS is the handshake) |
| `0x0004` | `AUTH_COMPLETE` | **sent** with `status = OK` |
| `0x0005` | `SERVICE_DISCOVERY_REQUEST` | parsed, logged |
| `0x0006` | `SERVICE_DISCOVERY_RESPONSE` | **sent** with channel descriptors |
| `0x0007` | `CHANNEL_OPEN_REQUEST` | sent by the phone **on the channel it wants**; answered on that channel: `OK` for video, `FAIL` otherwise |
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
message VersionRequest  { int32 major = 1; int32 minor = 2; }              // sent as 1.6
message VersionResponse { int32 major = 1; int32 minor = 2; int32 status = 3; }
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

## 5. Session as implemented (0.2.0-alpha1)

```
head unit                                phone
   │──── VERSION_REQUEST (1.6) ───────────────▶│   (inside AiPlay's TLS-on-TCP, see 2.2)
   │◀──────────── VERSION_RESPONSE ────────────│
   │──── AUTH_COMPLETE (OK) ──────────────────▶│
   │◀──────── SERVICE_DISCOVERY_REQUEST ───────│
   │──── SERVICE_DISCOVERY_RESPONSE (video) ──▶│
   │◀──── CHANNEL_OPEN_REQUEST   [video ch] ────│
   │──── CHANNEL_OPEN_RESPONSE (OK) [video ch] ▶│
   │◀──── SETUP_REQUEST (config 0) ─────────────│
   │──── SETUP_RESPONSE (OK, max_unacked 1, [0]) ▶│
   │◀──── VIDEO_FOCUS_REQUEST (FOCUSED) ────────│
   │──── VIDEO_FOCUS_INDICATION (FOCUSED) ─────▶│
   │◀──── START_INDICATION (session, config) ───│
   │◀──── MEDIA_WITH_TIMESTAMP … ───────────────│   H.264, one picture per message
   │──── MEDIA_ACK (session, 1) ────────────────▶│   one ack per picture
   │◀──── STOP_INDICATION ───────────────────────│
```

Verified by `AaSessionTest` (each step, including split framing of a 50 kB picture),
`AaAvTest` (hand-computed wire vectors), and `tools/aa-probe.py` against `tools/AaHarness.java`:
90 pictures sent, 90 acked, received stream **byte-identical** to the clip and decodable.
Decoding to a screen is `receiver/VideoDecoder.java` (`MediaCodec` → `SurfaceView`), exercised only
by `tools/device-test.sh` on a device.

## 6. Not implemented

| Channel | Needs |
| --- | --- |
| Transport | in-band TLS (`SSL_HANDSHAKE`, head unit = TLS client), plain TCP, encrypted frames; Bluetooth bootstrap for wireless |
| Media audio | `AVChannelSetup` + `AudioTrack` 48 kHz stereo, ack cadence |
| Speech audio | second `AudioTrack`, ducking |
| Input | `TouchEventData` (absolute, normalised) → `MotionEvent`, `ButtonEventsData` |
| Sensor | `SensorChannel` start request, day/night + driving status |
| Bluetooth | pairing helper only |

## 7. Honesty boundary

"Verified" above means: the wire format matches a public, citable definition and is
covered by a unit or integration test in `tests/`. It does **not** mean a phone has
accepted it. That step needs hardware and is tracked in [TESTING.md](TESTING.md).
