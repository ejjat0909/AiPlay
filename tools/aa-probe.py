#!/usr/bin/env python3
"""
AiPlay receiver probe — play the phone's side of Android Auto against a head unit running AiPlay.

Wire behaviour follows aasdk (the open-source head-unit library), not AiPlay's own code:

* plain TCP; every frame is [channel u8][flags u8][size u16 BE][payload], FIRST frames carry a
  six-byte size field (size u16 + total u32), and the message id is the first two bytes of the
  message payload;
* the head unit sends VERSION_REQUEST as raw big-endian u16s [major][minor] (not protobuf) with the
  CONTROL flag clear; the phone answers [major][minor][status];
* the head unit then starts TLS 1.2 as the *client*: the handshake travels in SSL_HANDSHAKE (0x0003)
  messages on channel 0, this probe being the TLS *server* (ssl.MemoryBIO);
* after TLS every frame is flagged ENCRYPTED and each frame's payload chunk is its own TLS record;
  only VERSION_REQUEST, SSL_HANDSHAKE and AUTH_COMPLETE stay in the clear;
* the CONTROL flag marks only channel-level open/close messages on non-zero channels.

    python3 tools/aa-probe.py 192.168.1.50
    python3 tools/aa-probe.py 192.168.1.50 --port 5277 --no-video

Exit code 0 means: the head unit's wire format matched all of the above, TLS completed, service
discovery worked, the video channel opened, and every picture of the test clip was acked.
The probe's own TLS identity is a throwaway test certificate (tools/fixtures/probe-phone.pem); it
does not ask the head unit for a client certificate (that is covered by the JVM tests).
Standard library only.
"""
import argparse
import os
import socket
import ssl
import struct
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
PROBE_CERT = os.path.join(HERE, "fixtures", "probe-phone.pem")
DEFAULT_CLIP = os.path.join(HERE, "fixtures", "test-720p30.h264")

# ---- channels, flags, message ids -----------------------------------------------------------

CONTROL, INPUT, SENSOR, VIDEO, MEDIA_AUDIO, SPEECH_AUDIO, SYSTEM_AUDIO, AV_INPUT, BLUETOOTH = range(9)
CHANNEL_NAMES = {
    CONTROL: "control", INPUT: "input", SENSOR: "sensor", VIDEO: "video",
    MEDIA_AUDIO: "media-audio", SPEECH_AUDIO: "speech-audio", SYSTEM_AUDIO: "system-audio",
    AV_INPUT: "av-input", BLUETOOTH: "bluetooth",
}

FRAME_MIDDLE, FRAME_FIRST, FRAME_LAST, FRAME_BULK = 0, 1, 2, 3
FLAG_ENCRYPTED = 1 << 3
FLAG_CONTROL = 1 << 2
MAX_FRAME_PAYLOAD = 0x4000

VERSION_REQUEST = 0x0001
VERSION_RESPONSE = 0x0002
SSL_HANDSHAKE = 0x0003
AUTH_COMPLETE = 0x0004
SERVICE_DISCOVERY_REQUEST = 0x0005
SERVICE_DISCOVERY_RESPONSE = 0x0006
CHANNEL_OPEN_REQUEST = 0x0007
CHANNEL_OPEN_RESPONSE = 0x0008
PING_REQUEST = 0x000B
PING_RESPONSE = 0x000C
SHUTDOWN_REQUEST = 0x000F
SHUTDOWN_RESPONSE = 0x0010

# The three messages aasdk sends in the clear even after TLS is up.
ALWAYS_PLAIN = (VERSION_REQUEST, SSL_HANDSHAKE, AUTH_COMPLETE)

MESSAGE_NAMES = {
    VERSION_REQUEST: "VERSION_REQUEST", VERSION_RESPONSE: "VERSION_RESPONSE",
    SSL_HANDSHAKE: "SSL_HANDSHAKE", AUTH_COMPLETE: "AUTH_COMPLETE",
    SERVICE_DISCOVERY_REQUEST: "SERVICE_DISCOVERY_REQUEST",
    SERVICE_DISCOVERY_RESPONSE: "SERVICE_DISCOVERY_RESPONSE",
    CHANNEL_OPEN_REQUEST: "CHANNEL_OPEN_REQUEST", CHANNEL_OPEN_RESPONSE: "CHANNEL_OPEN_RESPONSE",
    PING_REQUEST: "PING_REQUEST", PING_RESPONSE: "PING_RESPONSE",
    SHUTDOWN_REQUEST: "SHUTDOWN_REQUEST", SHUTDOWN_RESPONSE: "SHUTDOWN_RESPONSE",
}

# AVChannelMessage.Enum (aasdk AVChannelMessageIdsEnum.proto) — channel-specific, control flag clear.
AV_MEDIA_WITH_TIMESTAMP = 0x0000
AV_MEDIA = 0x0001
AV_SETUP_REQUEST = 0x8000
AV_START_INDICATION = 0x8001
AV_STOP_INDICATION = 0x8002
AV_SETUP_RESPONSE = 0x8003
AV_MEDIA_ACK = 0x8004
AV_VIDEO_FOCUS_REQUEST = 0x8007
AV_VIDEO_FOCUS_INDICATION = 0x8008
AV_SETUP_OK = 2        # AVChannelSetupStatus.Enum: NONE=0, FAIL=1, OK=2
FOCUS_FOCUSED = 1      # VideoFocusMode.Enum: NONE=0, FOCUSED=1, UNFOCUSED=2

AV_NAMES = {
    AV_MEDIA_WITH_TIMESTAMP: "MEDIA_WITH_TIMESTAMP", AV_MEDIA: "MEDIA",
    AV_SETUP_REQUEST: "SETUP_REQUEST", AV_START_INDICATION: "START_INDICATION",
    AV_STOP_INDICATION: "STOP_INDICATION", AV_SETUP_RESPONSE: "SETUP_RESPONSE",
    AV_MEDIA_ACK: "MEDIA_ACK", AV_VIDEO_FOCUS_REQUEST: "VIDEO_FOCUS_REQUEST",
    AV_VIDEO_FOCUS_INDICATION: "VIDEO_FOCUS_INDICATION",
}


def message_name(channel, control, message_id):
    if channel == CONTROL or control:
        return MESSAGE_NAMES.get(message_id, "UNKNOWN_0x%04X" % message_id)
    if channel == VIDEO:
        return AV_NAMES.get(message_id, "0x%04X" % message_id)
    return "0x%04X" % message_id


# ---- protobuf (just enough) -----------------------------------------------------------------

def varint(value):
    out = bytearray()
    while value > 0x7F:
        out.append((value & 0x7F) | 0x80)
        value >>= 7
    out.append(value)
    return bytes(out)


def tag(field, wire):
    return varint((field << 3) | wire)


def pb_varint(field, value):
    return tag(field, 0) + varint(value)


def pb_string(field, text):
    raw = text.encode("utf-8")
    return tag(field, 2) + varint(len(raw)) + raw


def read_varint(data, pos):
    result = 0
    shift = 0
    while True:
        b = data[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        if not b & 0x80:
            return result, pos
        shift += 7


def parse_fields(data):
    """Yields (field, wire, value) for a flat protobuf message."""
    pos = 0
    while pos < len(data):
        key, pos = read_varint(data, pos)
        field, wire = key >> 3, key & 0x7
        if wire == 0:
            value, pos = read_varint(data, pos)
        elif wire == 2:
            length, pos = read_varint(data, pos)
            value = data[pos:pos + length]
            pos += length
        elif wire == 1:
            value = data[pos:pos + 8]
            pos += 8
        elif wire == 5:
            value = data[pos:pos + 4]
            pos += 4
        else:
            raise ValueError("unsupported wire type %d" % wire)
        yield field, wire, value


# ---- in-band TLS: the phone is the TLS server -----------------------------------------------

class PhoneTls:
    """TLS 1.2 server over memory BIOs, the counterpart of aasdk's client-side Cryptor."""

    def __init__(self, certfile):
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.maximum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(certfile)
        self.incoming = ssl.MemoryBIO()
        self.outgoing = ssl.MemoryBIO()
        self.obj = context.wrap_bio(self.incoming, self.outgoing, server_side=True)
        self.done = False

    def handshake(self, data):
        """Feeds the head unit's handshake bytes; returns the bytes to send back (maybe empty)."""
        self.incoming.write(data)
        try:
            self.obj.do_handshake()
            self.done = True
        except ssl.SSLWantReadError:
            pass
        return self.outgoing.read()

    def encrypt(self, chunk):
        self.obj.write(chunk)
        return self.outgoing.read()

    def decrypt(self, record):
        self.incoming.write(record)
        out = b""
        while True:
            try:
                piece = self.obj.read(65536)
            except ssl.SSLWantReadError:
                return out
            if not piece:
                return out
            out += piece


# ---- framing --------------------------------------------------------------------------------

def encode_message(channel, control, message_id, payload=b"", tls=None):
    """One message -> wire frames. With `tls`, each frame's chunk is its own TLS record."""
    message = struct.pack(">H", message_id) + payload
    flags_base = (FLAG_CONTROL if control else 0) | (FLAG_ENCRYPTED if tls else 0)

    def frame(frame_type, chunk, total=None):
        body = tls.encrypt(chunk) if tls else chunk
        head = struct.pack(">BBH", channel, flags_base | frame_type, len(body))
        if total is not None:
            head += struct.pack(">I", total)      # plaintext size of the whole message
        return head + body

    if len(message) <= MAX_FRAME_PAYLOAD:
        return frame(FRAME_BULK, message)
    out = bytearray()
    offset = 0
    while offset < len(message):
        size = min(MAX_FRAME_PAYLOAD, len(message) - offset)
        first = offset == 0
        last = offset + size >= len(message)
        frame_type = FRAME_FIRST if first else (FRAME_LAST if last else FRAME_MIDDLE)
        out += frame(frame_type, message[offset:offset + size], len(message) if first else None)
        offset += size
    return bytes(out)


def recv_exact(sock, count):
    chunks = []
    remaining = count
    while remaining:
        chunk = sock.recv(remaining)
        if not chunk:
            raise EOFError("peer closed after %d of %d bytes" % (count - remaining, count))
        chunks.append(chunk)
        remaining -= len(chunk)
    return b"".join(chunks)


class Message:
    """A reassembled message plus how it travelled."""

    def __init__(self, channel, control, message_id, payload, encrypted, flags):
        self.channel, self.control, self.id = channel, control, message_id
        self.payload, self.encrypted, self.flags = payload, encrypted, flags


def recv_message(sock, tls):
    buffer = b""
    total = None
    encrypted_any = False
    first_flags = None
    while True:
        header = recv_exact(sock, 2)
        channel, flags = header[0], header[1]
        if first_flags is None:
            first_flags = flags
        frame_type = flags & FRAME_BULK
        control = bool(flags & FLAG_CONTROL)
        if frame_type == FRAME_FIRST:
            size, total_size = struct.unpack(">HI", recv_exact(sock, 6))
            total = total_size
        else:
            size = struct.unpack(">H", recv_exact(sock, 2))[0]
            if frame_type != FRAME_BULK and total is None:
                raise ValueError("continuation frame without a FIRST frame")
        body = recv_exact(sock, size) if size else b""
        if flags & FLAG_ENCRYPTED:
            if tls is None or not tls.done:
                raise ValueError("head unit sent an encrypted frame before TLS completed")
            body = tls.decrypt(body)
            encrypted_any = True
        buffer += body
        if frame_type in (FRAME_BULK, FRAME_LAST):
            if total is not None and len(buffer) != total:
                raise ValueError("message size mismatch: got %d, expected %d (the FIRST frame's "
                                 "total must be the plaintext size)" % (len(buffer), total))
            break
    if len(buffer) < 2:
        raise ValueError("message shorter than a message id")
    message_id = struct.unpack(">H", buffer[:2])[0]
    return Message(channel, control, message_id, buffer[2:], encrypted_any, first_flags)


# ---- decoding -------------------------------------------------------------------------------

def parse_channels(payload):
    """ServiceDiscoveryResponse -> [(channel_id, kind, stream_type or None)]."""
    channels = []
    for field, wire, value in parse_fields(payload):
        if field != 1 or wire != 2:
            continue
        channel_id, kind, stream_type = 0, "unknown", None
        for sub_field, sub_wire, sub_value in parse_fields(value):
            if sub_field == 1 and sub_wire == 0:
                channel_id = sub_value
            elif sub_wire == 2 and sub_field in (2, 3, 4, 5, 6, 8, 12):
                kind = {2: "sensor", 3: "av", 4: "input", 5: "av-input", 6: "bluetooth",
                        8: "navigation", 12: "vendor"}[sub_field]
                if sub_field == 3:
                    for av_field, av_wire, av_value in parse_fields(sub_value):
                        if av_field == 1 and av_wire == 0:
                            stream_type = av_value
        channels.append((channel_id, kind, stream_type))
    return channels


def describe(m):
    if m.id == VERSION_REQUEST and m.channel == CONTROL and len(m.payload) >= 4:
        major, minor = struct.unpack(">HH", m.payload[:4])
        return "raw version %d.%d" % (major, minor)
    if m.id == SSL_HANDSHAKE:
        return "%d byte(s) of TLS handshake" % len(m.payload)
    fields = list(parse_fields(m.payload)) if m.payload else []
    if m.channel == CONTROL and m.id == SERVICE_DISCOVERY_RESPONSE:
        text = {f: v.decode("utf-8", "replace") for f, w, v in fields if w == 2 and f != 1}
        return "%d channel(s), head unit=%r, model=%r, sw=%r" % (
            len(parse_channels(m.payload)), text.get(2, ""), text.get(3, ""), text.get(10, ""))
    values = {f: v for f, w, v in fields}
    if m.id == CHANNEL_OPEN_RESPONSE:
        return "status=%s (%s)" % (values.get(1, "?"), "OK" if values.get(1) == 0 else "FAIL")
    if m.id == AUTH_COMPLETE and m.channel == CONTROL:
        return "status=%s" % values.get(1, "?")
    if m.channel == VIDEO and not m.control:
        if m.id == AV_SETUP_RESPONSE:
            configs = [v for f, w, v in fields if f == 3]
            return "media_status=%s (%s) max_unacked=%s configs=%s" % (
                values.get(1, "?"), "OK" if values.get(1) == AV_SETUP_OK else "NOT OK",
                values.get(2, "?"), configs)
        if m.id == AV_VIDEO_FOCUS_INDICATION:
            return "focus_mode=%s (%s) unrequested=%s" % (
                values.get(1, "?"), "FOCUSED" if values.get(1) == FOCUS_FOCUSED else "other",
                values.get(2, 0))
        if m.id == AV_MEDIA_ACK:
            return "session=%s value=%s" % (values.get(1, "?"), values.get(2, "?"))
    return "%d field(s), %d byte(s)" % (len(fields), len(m.payload))


# ---- H.264 ----------------------------------------------------------------------------------

def split_access_units(data):
    """Annex-B stream -> list of access units (SPS/PPS stay attached to the slice they precede).

    One slice per picture is assumed, which is what the bundled clip uses (no sliced threads).
    """
    starts = []
    i = 0
    while True:
        i = data.find(b"\x00\x00\x01", i)
        if i < 0:
            break
        starts.append(i)
        i += 3
    units, begin = [], None
    for index, position in enumerate(starts):
        nal_type = data[position + 3] & 0x1F
        real_start = position - 1 if position > 0 and data[position - 1] == 0 else position
        if begin is None:
            begin = real_start
        end = (starts[index + 1] - (1 if data[starts[index + 1] - 1] == 0 else 0)
               if index + 1 < len(starts) else len(data))
        if nal_type in (1, 5):          # a VCL NAL completes the access unit
            units.append(data[begin:end])
            begin = None
    return units


# ---- probe ----------------------------------------------------------------------------------

class WireError(Exception):
    pass


def stream_video(send, receive, clip_path, fast):
    """Phone side of the video channel: setup, focus, start, frames, stop. True when all acked."""
    with open(clip_path, "rb") as handle:
        units = split_access_units(handle.read())
    if not units:
        print("     %s contains no H.264 pictures" % clip_path)
        return False
    print("     clip %s: %d picture(s)" % (os.path.basename(clip_path), len(units)))

    send(VIDEO, False, AV_SETUP_REQUEST, pb_varint(1, 0))
    m = receive()
    values = {f: v for f, w, v in parse_fields(m.payload)}
    if m.id != AV_SETUP_RESPONSE or values.get(1) != AV_SETUP_OK:
        print("     video setup was not accepted")
        return False
    max_unacked = max(1, values.get(2, 1))

    send(VIDEO, False, AV_VIDEO_FOCUS_REQUEST,
         pb_varint(1, 0) + pb_varint(2, FOCUS_FOCUSED) + pb_varint(3, 1))
    m = receive()
    if m.id != AV_VIDEO_FOCUS_INDICATION or dict((f, v) for f, w, v in
                                                 parse_fields(m.payload)).get(1) != FOCUS_FOCUSED:
        print("     video focus was not granted")
        return False

    send(VIDEO, False, AV_START_INDICATION, pb_varint(1, 1) + pb_varint(2, 0))

    outstanding = acked = 0
    started = time.time()
    for index, unit in enumerate(units):
        while outstanding >= max_unacked:
            if receive(quiet=True).id == AV_MEDIA_ACK:
                outstanding -= 1
                acked += 1
        send(VIDEO, False, AV_MEDIA_WITH_TIMESTAMP, struct.pack(">Q", index * 33333) + unit,
             quiet=index > 0)
        outstanding += 1
        if not fast:
            delay = started + (index + 1) / 30.0 - time.time()
            if delay > 0:
                time.sleep(delay)
    while outstanding > 0:
        if receive(quiet=True).id == AV_MEDIA_ACK:
            outstanding -= 1
            acked += 1
    send(VIDEO, False, AV_STOP_INDICATION)
    elapsed = time.time() - started
    print("     sent %d picture(s), %d acked, %.1fs (%.1f fps)"
          % (len(units), acked, elapsed, len(units) / elapsed if elapsed else 0))
    return acked == len(units)


def probe(host, port, timeout, device_name, open_channel, send_video=True, clip=DEFAULT_CLIP,
          fast=False):
    sock = socket.create_connection((host, port), timeout=timeout)
    sock.settimeout(timeout)
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    print("connected  : plain tcp (TLS runs inside the protocol)")

    tls = PhoneTls(PROBE_CERT)
    violations = []

    def send(channel, control, message_id, payload=b"", quiet=False, plain=False):
        use_tls = tls if (tls.done and not plain) else None
        sock.sendall(encode_message(channel, control, message_id, payload, use_tls))
        if not quiet:
            print("-> %-10s %-26s %d byte(s)%s"
                  % (CHANNEL_NAMES.get(channel, channel),
                     message_name(channel, control, message_id), len(payload),
                     "  [encrypted]" if use_tls else ""))

    def receive(quiet=False):
        m = recv_message(sock, tls)
        # What aasdk does, checked on every message from the head unit.
        if m.channel == CONTROL and m.control:
            violations.append("%s on channel 0 had the CONTROL flag set; aasdk sends channel-0 "
                              "messages as SPECIFIC" % message_name(m.channel, m.control, m.id))
        should_be_plain = m.channel == CONTROL and m.id in ALWAYS_PLAIN
        if tls.done and m.encrypted == should_be_plain:
            violations.append("%s was %s after TLS was up" % (
                message_name(m.channel, m.control, m.id),
                "encrypted" if m.encrypted else "in the clear"))
        if not quiet:
            print("<- %-10s %-26s %s%s"
                  % (CHANNEL_NAMES.get(m.channel, m.channel),
                     message_name(m.channel, m.control, m.id), describe(m),
                     "  [encrypted]" if m.encrypted else ""))
        return m

    ok = False
    try:
        # 1. version: raw u16s, flags 0x03 (BULK, plain, SPECIFIC)
        m = receive()
        if m.id != VERSION_REQUEST or m.channel != CONTROL:
            raise WireError("expected VERSION_REQUEST first on the control channel")
        if len(m.payload) != 4:
            raise WireError("VERSION_REQUEST payload is %d bytes; aasdk sends 4 raw bytes "
                            "[major u16][minor u16]" % len(m.payload))
        if m.flags != FRAME_BULK:
            raise WireError("VERSION_REQUEST flags are 0x%02X; aasdk sends 0x03" % m.flags)
        send(CONTROL, False, VERSION_RESPONSE, struct.pack(">HHH", 1, 7, 0), plain=True)

        # 2. TLS inside SSL_HANDSHAKE messages; the head unit is the client and speaks first
        while True:
            m = receive()
            if m.id == SSL_HANDSHAKE:
                reply = tls.handshake(m.payload)
                if reply:
                    send(CONTROL, False, SSL_HANDSHAKE, reply, plain=True)
                continue
            if m.id == AUTH_COMPLETE:
                if not tls.done:
                    raise WireError("AUTH_COMPLETE arrived before the TLS handshake finished")
                break
            raise WireError("unexpected %s during the handshake"
                            % message_name(m.channel, m.control, m.id))
        print("     tls up: %s / %s" % (tls.obj.version(), tls.obj.cipher()[0]))

        # 3. everything from here is encrypted
        send(CONTROL, False, SERVICE_DISCOVERY_REQUEST,
             pb_string(4, device_name) + pb_string(5, "AiPlay probe"))
        m = receive()
        if m.id != SERVICE_DISCOVERY_RESPONSE:
            raise WireError("expected SERVICE_DISCOVERY_RESPONSE")
        channels = parse_channels(m.payload)
        for channel_id, kind, stream_type in channels:
            extra = "" if stream_type is None else " stream_type=%d" % stream_type
            print("     channel %-3d %-11s%s" % (channel_id, kind, extra))
        ok = True

        send(CONTROL, False, PING_REQUEST, pb_varint(1, 1234567))
        receive()

        target = open_channel
        if target is None:
            video = [c for c, kind, st in channels if kind == "av" and st == 3]
            target = video[0] if video else (channels[0][0] if channels else None)
        if target is None:
            print("     head unit advertised no channels to open")
            return 1
        # A phone sends ChannelOpenRequest on the channel it wants, CONTROL-typed.
        send(target, True, CHANNEL_OPEN_REQUEST, pb_varint(1, 0) + pb_varint(2, target))
        m = receive()
        values = {f: v for f, w, v in parse_fields(m.payload)} if m.id == CHANNEL_OPEN_RESPONSE else {}
        if values.get(1) != 0:
            print("     receiver refused channel %d (status %s)" % (target, values.get(1)))
            return 1
        if m.channel != target or not m.control:
            violations.append("channel open response should be CONTROL-typed on channel %d" % target)
        print("     channel %d opened" % target)

        if send_video and target == VIDEO:
            if not stream_video(send, receive, clip, fast):
                return 1
    except WireError as error:
        print("WIRE MISMATCH: %s" % error)
        ok = False
    finally:
        try:
            send(CONTROL, False, SHUTDOWN_REQUEST)
        except OSError:
            pass
        sock.close()

    print()
    if violations:
        for violation in violations:
            print("WIRE MISMATCH: %s" % violation)
        print("RESULT: the head unit does not match aasdk's wire behaviour")
        return 1
    print("RESULT: %s" % ("handshake complete over in-band TLS, video channel streamed — receiver "
                          "speaks Android Auto"
                          if ok and send_video and open_channel in (None, VIDEO)
                          else "handshake complete over in-band TLS — receiver speaks Android Auto"
                          if ok else "handshake incomplete"))
    return 0 if ok else 1


def main():
    parser = argparse.ArgumentParser(description="Probe an AiPlay Android Auto receiver")
    parser.add_argument("host", help="phone/head unit IP address")
    parser.add_argument("--port", type=int, default=5277)
    parser.add_argument("--timeout", type=float, default=10.0)
    parser.add_argument("--name", default="AiPlay probe", help="device name sent to the receiver")
    parser.add_argument("--channel", type=int, default=None,
                        help="channel id to open (default: first video channel)")
    parser.add_argument("--no-video", action="store_true",
                        help="stop after the channel open; do not stream the test clip")
    parser.add_argument("--clip", default=DEFAULT_CLIP, help="Annex-B H.264 file to stream")
    parser.add_argument("--fast", action="store_true", help="send frames as fast as acks allow")
    args = parser.parse_args()
    try:
        return probe(args.host, args.port, args.timeout, args.name, args.channel,
                     send_video=not args.no_video, clip=args.clip, fast=args.fast)
    except (OSError, EOFError, ValueError, ssl.SSLError) as error:
        print("FAILED: %s" % error)
        print("check: same Wi-Fi network, receiver started in the app, port %d not blocked"
              % args.port)
        return 2


if __name__ == "__main__":
    sys.exit(main())
