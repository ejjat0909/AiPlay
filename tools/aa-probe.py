#!/usr/bin/env python3
"""
AiPlay receiver probe — talk Android Auto to a phone or head unit running AiPlay.

This is the client side of the handshake described in docs/PROTOCOL.md. It connects over TLS,
performs the AA control exchange, and prints every frame in both directions, so you can prove the
receiver works without a car:

    python3 tools/aa-probe.py 192.168.1.50
    python3 tools/aa-probe.py 192.168.1.50 --port 5277 --verbose

Exit code 0 means the receiver completed version negotiation and service discovery.
No third-party packages: standard library only.
"""
import argparse
import socket
import ssl
import struct
import sys

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

NAMES = {
    VERSION_REQUEST: "VERSION_REQUEST", VERSION_RESPONSE: "VERSION_RESPONSE",
    AUTH_COMPLETE: "AUTH_COMPLETE", SERVICE_DISCOVERY_REQUEST: "SERVICE_DISCOVERY_REQUEST",
    SERVICE_DISCOVERY_RESPONSE: "SERVICE_DISCOVERY_RESPONSE",
    CHANNEL_OPEN_REQUEST: "CHANNEL_OPEN_REQUEST", CHANNEL_OPEN_RESPONSE: "CHANNEL_OPEN_RESPONSE",
    PING_REQUEST: "PING_REQUEST", PING_RESPONSE: "PING_RESPONSE",
    SHUTDOWN_REQUEST: "SHUTDOWN_REQUEST", SHUTDOWN_RESPONSE: "SHUTDOWN_RESPONSE",
}


def name_of(msg_type):
    return NAMES.get(msg_type, "UNKNOWN_0x%04X" % msg_type)


# ---- protobuf (just enough) ----------------------------------------------------------------

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


# ---- framing -------------------------------------------------------------------------------

def send_frame(sock, msg_type, payload=b""):
    sock.sendall(struct.pack(">HH", len(payload), msg_type) + payload)


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


def recv_frame(sock):
    header = recv_exact(sock, 4)
    length, msg_type = struct.unpack(">HH", header)
    payload = recv_exact(sock, length) if length else b""
    return msg_type, payload


def describe(msg_type, payload):
    """One-line human summary of a control message."""
    fields = list(parse_fields(payload)) if payload else []
    if msg_type == VERSION_RESPONSE:
        values = {f: v for f, w, v in fields}
        return "version %s.%s status=%s" % (
            values.get(1, "?"), values.get(2, "?"), values.get(3, "?"))
    if msg_type == SERVICE_DISCOVERY_RESPONSE:
        channels = [v for f, w, v in fields if f == 1]
        text = {f: v.decode("utf-8", "replace") for f, w, v in fields if w == 2 and f != 1}
        return "%d channel(s), head unit=%r, model=%r, sw=%r" % (
            len(channels), text.get(2, ""), text.get(3, ""), text.get(10, ""))
    if msg_type == CHANNEL_OPEN_RESPONSE:
        values = {f: v for f, w, v in fields}
        return "status=%s (%s)" % (values.get(1, "?"),
                                   "OK" if values.get(1) == 0 else "FAIL")
    if msg_type == AUTH_COMPLETE:
        values = {f: v for f, w, v in fields}
        return "status=%s" % values.get(1, "?")
    return "%d field(s), %d byte(s)" % (len(fields), len(payload))


# ---- probe ---------------------------------------------------------------------------------

def probe(host, port, timeout, verbose, head_unit_name):
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE

    raw = socket.create_connection((host, port), timeout=timeout)
    raw.settimeout(timeout)
    sock = context.wrap_socket(raw)
    print("connected  : tls %s / %s" % (sock.version(), sock.cipher()[0]))
    peer = sock.getpeercert()
    if peer:
        print("cert       : %s" % (peer.get("subject"),))

    ok = False
    try:
        msg_type, payload = recv_frame(sock)
        print("<- %-26s %s" % (name_of(msg_type), describe(msg_type, payload)))
        if msg_type != VERSION_REQUEST:
            print("unexpected first frame; expected VERSION_REQUEST")
            return 1

        send_frame(sock, VERSION_RESPONSE, pb_varint(1, 1) + pb_varint(2, 6) + pb_varint(3, 0))
        print("-> %-26s version 1.6 status=0" % "VERSION_RESPONSE")

        msg_type, payload = recv_frame(sock)
        print("<- %-26s %s" % (name_of(msg_type), describe(msg_type, payload)))
        if msg_type != AUTH_COMPLETE:
            print("expected AUTH_COMPLETE after version response")
            return 1

        send_frame(sock, SERVICE_DISCOVERY_REQUEST,
                   pb_string(4, head_unit_name) + pb_string(5, "AiPlay probe"))
        print("-> %-26s device=%r" % ("SERVICE_DISCOVERY_REQUEST", head_unit_name))

        msg_type, payload = recv_frame(sock)
        print("<- %-26s %s" % (name_of(msg_type), describe(msg_type, payload)))
        if msg_type != SERVICE_DISCOVERY_RESPONSE:
            print("expected SERVICE_DISCOVERY_RESPONSE")
            return 1
        if verbose:
            for field, wire, value in parse_fields(payload):
                if wire == 2 and field != 1:
                    print("     field %-2d = %r" % (field, value.decode("utf-8", "replace")))
        ok = True

        send_frame(sock, PING_REQUEST, pb_varint(1, 1234567))
        print("-> %-26s timestamp=1234567" % "PING_REQUEST")
        msg_type, payload = recv_frame(sock)
        print("<- %-26s %s" % (name_of(msg_type), describe(msg_type, payload)))

        send_frame(sock, CHANNEL_OPEN_REQUEST, pb_varint(1, 1) + pb_varint(2, 1))
        print("-> %-26s channel 1 priority 1" % "CHANNEL_OPEN_REQUEST")
        msg_type, payload = recv_frame(sock)
        print("<- %-26s %s" % (name_of(msg_type), describe(msg_type, payload)))
        if msg_type == CHANNEL_OPEN_RESPONSE and payload and payload[1] == 1:
            print("note       : receiver refused the channel — expected in 0.1.0 "
                  "(no video/audio channel yet)")
    finally:
        try:
            send_frame(sock, SHUTDOWN_REQUEST)
            print("-> %-26s" % "SHUTDOWN_REQUEST")
        except OSError:
            pass
        sock.close()

    print()
    print("RESULT: %s" % ("handshake complete — receiver is speaking Android Auto" if ok
                          else "handshake incomplete"))
    return 0 if ok else 1


def main():
    parser = argparse.ArgumentParser(description="Probe an AiPlay Android Auto receiver")
    parser.add_argument("host", help="phone/head unit IP address")
    parser.add_argument("--port", type=int, default=5277)
    parser.add_argument("--timeout", type=float, default=10.0)
    parser.add_argument("--name", default="AiPlay probe", help="device name sent to the receiver")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()
    try:
        return probe(args.host, args.port, args.timeout, args.verbose, args.name)
    except (OSError, EOFError, ValueError) as error:
        print("FAILED: %s" % error)
        print("check: same Wi-Fi network, receiver started in the app, port %d not blocked"
              % args.port)
        return 2


if __name__ == "__main__":
    sys.exit(main())
