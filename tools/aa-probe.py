#!/usr/bin/env python3
"""
AiPlay receiver probe — speak real Android Auto to a head unit running AiPlay.

This is the client (phone) side of docs/PROTOCOL.md: TLS, then the control exchange, then a
channel open for whichever channel the head unit advertises. It prints every message in both
directions, so the receiver can be proven without a car:

    python3 tools/aa-probe.py 192.168.1.50
    python3 tools/aa-probe.py 192.168.1.50 --port 5277 --verbose

Exit code 0 means the receiver completed version negotiation, service discovery and the channel
open it was asked to perform. Standard library only.

Wire format (aasdk): [channel u8][flags u8][size u16 BE][payload], with a six-byte size field
(size u16 + total u32) on the FIRST frame of a split message, and the message id as the first two
bytes of the payload.
"""
import argparse
import socket
import ssl
import struct
import sys

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
AUTH_COMPLETE = 0x0004
SERVICE_DISCOVERY_REQUEST = 0x0005
SERVICE_DISCOVERY_RESPONSE = 0x0006
CHANNEL_OPEN_REQUEST = 0x0007
CHANNEL_OPEN_RESPONSE = 0x0008
PING_REQUEST = 0x000B
PING_RESPONSE = 0x000C
SHUTDOWN_REQUEST = 0x000F
SHUTDOWN_RESPONSE = 0x0010

MESSAGE_NAMES = {
    VERSION_REQUEST: "VERSION_REQUEST", VERSION_RESPONSE: "VERSION_RESPONSE",
    AUTH_COMPLETE: "AUTH_COMPLETE", SERVICE_DISCOVERY_REQUEST: "SERVICE_DISCOVERY_REQUEST",
    SERVICE_DISCOVERY_RESPONSE: "SERVICE_DISCOVERY_RESPONSE",
    CHANNEL_OPEN_REQUEST: "CHANNEL_OPEN_REQUEST", CHANNEL_OPEN_RESPONSE: "CHANNEL_OPEN_RESPONSE",
    PING_REQUEST: "PING_REQUEST", PING_RESPONSE: "PING_RESPONSE",
    SHUTDOWN_REQUEST: "SHUTDOWN_REQUEST", SHUTDOWN_RESPONSE: "SHUTDOWN_RESPONSE",
}


def message_name(channel, control, message_id):
    if control:
        return MESSAGE_NAMES.get(message_id, "UNKNOWN_0x%04X" % message_id)
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


# ---- framing --------------------------------------------------------------------------------

def encode_message(channel, control, message_id, payload=b""):
    message = struct.pack(">H", message_id) + payload
    flags_base = FLAG_CONTROL if control else 0
    if len(message) <= MAX_FRAME_PAYLOAD:
        return struct.pack(">BBH", channel, flags_base | FRAME_BULK, len(message)) + message
    out = bytearray()
    offset = 0
    while offset < len(message):
        size = min(MAX_FRAME_PAYLOAD, len(message) - offset)
        first = offset == 0
        last = offset + size >= len(message)
        frame_type = FRAME_FIRST if first else (FRAME_LAST if last else FRAME_MIDDLE)
        out += struct.pack(">BBH", channel, flags_base | frame_type, size)
        if first:
            out += struct.pack(">I", len(message))
        out += message[offset:offset + size]
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


def recv_message(sock):
    """Reads one reassembled message: (channel, control, message_id, payload)."""
    buffer = b""
    total = None
    channel = control = 0
    while True:
        header = recv_exact(sock, 2)
        channel, flags = header[0], header[1]
        if flags & FLAG_ENCRYPTED:
            raise ValueError("peer sent an encrypted frame; not supported")
        frame_type = flags & FRAME_BULK
        control = bool(flags & FLAG_CONTROL)
        if frame_type == FRAME_FIRST:
            size, total_size = struct.unpack(">HI", recv_exact(sock, 6))
            total = total_size
        else:
            size = struct.unpack(">H", recv_exact(sock, 2))[0]
            if frame_type != FRAME_BULK and total is None:
                raise ValueError("continuation frame without a FIRST frame")
        buffer += recv_exact(sock, size) if size else b""
        if frame_type in (FRAME_BULK, FRAME_LAST):
            if total is not None and len(buffer) != total:
                raise ValueError("message size mismatch: got %d, expected %d"
                                 % (len(buffer), total))
            break
    if len(buffer) < 2:
        raise ValueError("message shorter than a message id")
    message_id = struct.unpack(">H", buffer[:2])[0]
    return channel, control, message_id, buffer[2:]


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


def describe(channel, control, message_id, payload):
    fields = list(parse_fields(payload)) if payload else []
    if control and message_id == VERSION_RESPONSE:
        values = {f: v for f, w, v in fields}
        return "version %s.%s status=%s" % (values.get(1, "?"), values.get(2, "?"),
                                            values.get(3, "?"))
    if control and message_id == SERVICE_DISCOVERY_RESPONSE:
        text = {f: v.decode("utf-8", "replace") for f, w, v in fields if w == 2 and f != 1}
        channels = parse_channels(payload)
        return "%d channel(s), head unit=%r, model=%r, sw=%r" % (
            len(channels), text.get(2, ""), text.get(3, ""), text.get(10, ""))
    if control and message_id == CHANNEL_OPEN_RESPONSE:
        values = {f: v for f, w, v in fields}
        return "status=%s (%s)" % (values.get(1, "?"), "OK" if values.get(1) == 0 else "FAIL")
    if control and message_id == AUTH_COMPLETE:
        values = {f: v for f, w, v in fields}
        return "status=%s" % values.get(1, "?")
    return "%d field(s), %d byte(s)" % (len(fields), len(payload))


# ---- probe ----------------------------------------------------------------------------------

def probe(host, port, timeout, verbose, device_name, open_channel):
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE

    raw = socket.create_connection((host, port), timeout=timeout)
    raw.settimeout(timeout)
    sock = context.wrap_socket(raw)
    print("connected  : tls %s / %s" % (sock.version(), sock.cipher()[0]))

    def send(channel, control, message_id, payload=b""):
        sock.sendall(encode_message(channel, control, message_id, payload))
        print("-> %-10s %-26s %d byte(s)"
              % (CHANNEL_NAMES.get(channel, channel), message_name(channel, control, message_id),
                 len(payload)))

    def receive():
        channel, control, message_id, payload = recv_message(sock)
        print("<- %-10s %-26s %s"
              % (CHANNEL_NAMES.get(channel, channel), message_name(channel, control, message_id),
                 describe(channel, control, message_id, payload)))
        return channel, control, message_id, payload

    ok = False
    try:
        channel, control, message_id, payload = receive()
        if message_id != VERSION_REQUEST:
            print("unexpected first message; expected VERSION_REQUEST on the control channel")
            return 1

        send(CONTROL, True, VERSION_RESPONSE,
             pb_varint(1, 1) + pb_varint(2, 6) + pb_varint(3, 0))
        channel, control, message_id, payload = receive()
        if message_id != AUTH_COMPLETE:
            print("expected AUTH_COMPLETE after the version response")
            return 1

        send(CONTROL, True, SERVICE_DISCOVERY_REQUEST,
             pb_string(4, device_name) + pb_string(5, "AiPlay probe"))
        channel, control, message_id, payload = receive()
        if message_id != SERVICE_DISCOVERY_RESPONSE:
            print("expected SERVICE_DISCOVERY_RESPONSE")
            return 1

        channels = parse_channels(payload)
        for channel_id, kind, stream_type in channels:
            extra = "" if stream_type is None else " stream_type=%d" % stream_type
            print("     channel %-3d %-11s%s" % (channel_id, kind, extra))
        ok = True

        send(CONTROL, True, PING_REQUEST, pb_varint(1, 1234567))
        receive()

        target = open_channel
        if target is None:
            video = [c for c, kind, st in channels if kind == "av" and st == 3]
            target = video[0] if video else (channels[0][0] if channels else None)
        if target is None:
            print("     head unit advertised no channels to open")
        else:
            send(CONTROL, True, CHANNEL_OPEN_REQUEST, pb_varint(1, 1) + pb_varint(2, target))
            channel, control, message_id, payload = receive()
            if message_id == CHANNEL_OPEN_RESPONSE:
                values = {f: v for f, w, v in parse_fields(payload)}
                if values.get(1) == 0:
                    print("     channel %d opened" % target)
                else:
                    print("     receiver refused channel %d (status %s) — expected until the "
                          "video channel lands" % (target, values.get(1)))
    finally:
        try:
            send(CONTROL, True, SHUTDOWN_REQUEST)
        except OSError:
            pass
        sock.close()

    print()
    print("RESULT: %s" % ("handshake complete — receiver speaks Android Auto"
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
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()
    try:
        return probe(args.host, args.port, args.timeout, args.verbose, args.name, args.channel)
    except (OSError, EOFError, ValueError) as error:
        print("FAILED: %s" % error)
        print("check: same Wi-Fi network, receiver started in the app, port %d not blocked"
              % args.port)
        return 2


if __name__ == "__main__":
    sys.exit(main())
