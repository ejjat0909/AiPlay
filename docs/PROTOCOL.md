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

| Channel | Needs |
| --- | --- |
| Bluetooth bootstrap | wireless AA: the phone learns the head unit's Wi-Fi address and port over Bluetooth (RFCOMM). Without it a phone never connects to AiPlay's port. Unverified in detail |
| Media audio | `AVChannelSetup` + `AudioTrack` 48 kHz stereo, ack cadence |
| Speech audio | second `AudioTrack`, ducking |
| Input | `TouchEventData` (absolute, normalised) → `MotionEvent`, `ButtonEventsData` |
| Sensor | `SensorChannel` start request, day/night + driving status |

## 7. Honesty boundary

"Verified" above means: the wire format matches a public, citable definition and is
covered by a unit or integration test in `tests/`. It does **not** mean a phone has
accepted it. That step needs hardware and is tracked in [TESTING.md](TESTING.md).
