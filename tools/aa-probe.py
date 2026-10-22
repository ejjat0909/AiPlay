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

What the probe then does, as a phone would:

    sensor   open, ask for night mode and driving status, check the indications
    input    open, bind a set of keys; later it RECEIVES the touches and keys the head unit sends
    audio    open media/speech/system, audio-focus request, stream a tone on each
    video    open, setup, focus, start, stream the H.264 test clip, all of it concurrently

    python3 tools/aa-probe.py 192.168.1.50
    python3 tools/aa-probe.py 192.168.1.50 --hold 20 --stop-on night   # wait for touches/keys

Exit code 0 means every CHECK line passed. The probe's own TLS identity is a throwaway test
certificate (tools/fixtures/probe-phone.pem). Standard library only.
"""
import argparse
import collections
import math
import os
import select
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
AV_CHANNELS = (VIDEO, MEDIA_AUDIO, SPEECH_AUDIO, SYSTEM_AUDIO)

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
NAVIGATION_FOCUS_REQUEST = 0x000D
NAVIGATION_FOCUS_RESPONSE = 0x000E
SHUTDOWN_REQUEST = 0x000F
SHUTDOWN_RESPONSE = 0x0010
AUDIO_FOCUS_REQUEST = 0x0012
AUDIO_FOCUS_RESPONSE = 0x0013

# The three messages aasdk sends in the clear even after TLS is up.
ALWAYS_PLAIN = (VERSION_REQUEST, SSL_HANDSHAKE, AUTH_COMPLETE)

MESSAGE_NAMES = {
    VERSION_REQUEST: "VERSION_REQUEST", VERSION_RESPONSE: "VERSION_RESPONSE",
    SSL_HANDSHAKE: "SSL_HANDSHAKE", AUTH_COMPLETE: "AUTH_COMPLETE",
    SERVICE_DISCOVERY_REQUEST: "SERVICE_DISCOVERY_REQUEST",
    SERVICE_DISCOVERY_RESPONSE: "SERVICE_DISCOVERY_RESPONSE",
    CHANNEL_OPEN_REQUEST: "CHANNEL_OPEN_REQUEST", CHANNEL_OPEN_RESPONSE: "CHANNEL_OPEN_RESPONSE",
    PING_REQUEST: "PING_REQUEST", PING_RESPONSE: "PING_RESPONSE",
    NAVIGATION_FOCUS_REQUEST: "NAVIGATION_FOCUS_REQUEST",
    NAVIGATION_FOCUS_RESPONSE: "NAVIGATION_FOCUS_RESPONSE",
    SHUTDOWN_REQUEST: "SHUTDOWN_REQUEST", SHUTDOWN_RESPONSE: "SHUTDOWN_RESPONSE",
    AUDIO_FOCUS_REQUEST: "AUDIO_FOCUS_REQUEST", AUDIO_FOCUS_RESPONSE: "AUDIO_FOCUS_RESPONSE",
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

# Input channel (InputChannelMessageIdsEnum.proto) and sensor channel (SensorChannelMessageIdsEnum.proto)
IN_EVENT_INDICATION, IN_BINDING_REQUEST, IN_BINDING_RESPONSE = 0x8001, 0x8002, 0x8003
SN_START_REQUEST, SN_START_RESPONSE, SN_EVENT_INDICATION = 0x8001, 0x8002, 0x8003
INPUT_NAMES = {IN_EVENT_INDICATION: "INPUT_EVENT_INDICATION", IN_BINDING_REQUEST: "BINDING_REQUEST",
               IN_BINDING_RESPONSE: "BINDING_RESPONSE"}
SENSOR_NAMES = {SN_START_REQUEST: "SENSOR_START_REQUEST", SN_START_RESPONSE: "SENSOR_START_RESPONSE",
                SN_EVENT_INDICATION: "SENSOR_EVENT_INDICATION"}
SENSOR_NIGHT, SENSOR_DRIVING = 10, 13
TOUCH_ACTIONS = {0: "PRESS", 1: "RELEASE", 2: "DRAG"}

# Audio focus (AudioFocusTypeEnum / AudioFocusStateEnum)
FOCUS_GAIN_NAVI = 3
FOCUS_STATE_GAIN = 1

# What the head unit must advertise for each audio channel (OpenAuto ServiceFactory.cpp).
AUDIO_FORMATS = {MEDIA_AUDIO: (48000, 16, 2), SPEECH_AUDIO: (16000, 16, 1), SYSTEM_AUDIO: (16000, 16, 1)}
KEYS_TO_BIND = [0x13, 0x14, 0x15, 0x16, 0x17]     # dpad + enter


def message_name(channel, control, message_id):
    if channel == CONTROL or control:
        return MESSAGE_NAMES.get(message_id, "UNKNOWN_0x%04X" % message_id)
    if channel in AV_CHANNELS:
        return AV_NAMES.get(message_id, "0x%04X" % message_id)
    if channel == INPUT:
        return INPUT_NAMES.get(message_id, "0x%04X" % message_id)
    if channel == SENSOR:
        return SENSOR_NAMES.get(message_id, "0x%04X" % message_id)
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


class Message:
    """A reassembled message plus how it travelled."""

    def __init__(self, channel, control, message_id, payload, encrypted, flags):
        self.channel, self.control, self.id = channel, control, message_id
        self.payload, self.encrypted, self.flags = payload, encrypted, flags


class Link:
    """The TCP connection: an incremental frame parser, so reading never blocks the sending."""

    def __init__(self, sock, tls):
        self.sock = sock
        self.tls = tls
        self.raw = bytearray()
        self.queue = collections.deque()
        self._asm = None            # [channel, buffer, total, encrypted, first_flags]
        self.closed = False

    def _parse(self):
        while True:
            if len(self.raw) < 2:
                return
            channel, flags = self.raw[0], self.raw[1]
            frame_type = flags & FRAME_BULK
            size_bytes = 6 if frame_type == FRAME_FIRST else 2
            if len(self.raw) < 2 + size_bytes:
                return
            if frame_type == FRAME_FIRST:
                size, total = struct.unpack(">HI", bytes(self.raw[2:8]))
            else:
                size, total = struct.unpack(">H", bytes(self.raw[2:4]))[0], None
            if len(self.raw) < 2 + size_bytes + size:
                return
            body = bytes(self.raw[2 + size_bytes:2 + size_bytes + size])
            del self.raw[:2 + size_bytes + size]
            encrypted = bool(flags & FLAG_ENCRYPTED)
            if encrypted:
                if self.tls is None or not self.tls.done:
                    raise ValueError("head unit sent an encrypted frame before TLS completed")
                body = self.tls.decrypt(body)
            if frame_type == FRAME_FIRST:
                self._asm = [channel, body, total, encrypted, flags]
            elif frame_type == FRAME_BULK:
                self._emit(channel, body, None, encrypted, flags)
                continue
            else:
                if self._asm is None:
                    raise ValueError("continuation frame without a FIRST frame")
                self._asm[1] += body
                self._asm[3] = self._asm[3] or encrypted
            if frame_type == FRAME_LAST:
                channel, buffer, total, enc, first_flags = self._asm
                self._asm = None
                self._emit(channel, buffer, total, enc, first_flags)

    def _emit(self, channel, buffer, total, encrypted, flags):
        if total is not None and len(buffer) != total:
            raise ValueError("message size mismatch: got %d, expected %d (the FIRST frame's total "
                             "must be the plaintext size)" % (len(buffer), total))
        if len(buffer) < 2:
            raise ValueError("message shorter than a message id")
        message_id = struct.unpack(">H", buffer[:2])[0]
        self.queue.append(Message(channel, bool(flags & FLAG_CONTROL), message_id, buffer[2:],
                                  encrypted, flags))

    def poll(self, timeout):
        """Reads whatever has arrived (waiting up to `timeout`), parses it; returns messages."""
        ready, _, _ = select.select([self.sock], [], [], max(0.0, timeout))
        if ready:
            data = self.sock.recv(65536)
            if not data:
                self.closed = True
                raise EOFError("head unit closed the connection")
            self.raw += data
            self._parse()
        return self.queue

    def next(self, timeout):
        deadline = time.time() + timeout
        while not self.queue:
            remaining = deadline - time.time()
            if remaining <= 0:
                raise TimeoutError("no message from the head unit within %.1fs" % timeout)
            self.poll(remaining)
        return self.queue.popleft()


# ---- decoding -------------------------------------------------------------------------------

def parse_channels(payload):
    """ServiceDiscoveryResponse -> [(channel_id, kind, detail)]."""
    channels = []
    for field, wire, value in parse_fields(payload):
        if field != 1 or wire != 2:
            continue
        channel_id, kind, detail = 0, "unknown", None
        for sub_field, sub_wire, sub_value in parse_fields(value):
            if sub_field == 1 and sub_wire == 0:
                channel_id = sub_value
            elif sub_wire == 2 and sub_field in (2, 3, 4, 5, 6, 8, 12):
                kind = {2: "sensor", 3: "av", 4: "input", 5: "av-input", 6: "bluetooth",
                        8: "navigation", 12: "vendor"}[sub_field]
                if sub_field == 3:
                    detail = {"stream": None, "audio_type": None, "config": None}
                    for av_field, av_wire, av_value in parse_fields(sub_value):
                        if av_field == 1 and av_wire == 0:
                            detail["stream"] = av_value
                        elif av_field == 2 and av_wire == 0:
                            detail["audio_type"] = av_value
                        elif av_field == 3 and av_wire == 2:
                            cfg = {f: v for f, w, v in parse_fields(av_value)}
                            detail["config"] = (cfg.get(1), cfg.get(2), cfg.get(3))
                elif sub_field == 2:
                    detail = [dict((f, v) for f, w, v in parse_fields(s)).get(1)
                              for f, w, s in parse_fields(sub_value) if f == 1]
                elif sub_field == 4:
                    keys = [v for f, w, v in parse_fields(sub_value) if f == 1 and w == 0]
                    detail = {"keys": keys}
        channels.append((channel_id, kind, detail))
    return channels


def parse_touch(payload):
    """InputEventIndication -> dict for touch or button events."""
    out = {}
    for field, wire, value in parse_fields(payload):
        if field == 1:
            out["timestamp"] = value
        elif field == 3:
            points, action, index = [], None, 0
            for f, w, v in parse_fields(value):
                if f == 1:
                    loc = {ff: vv for ff, ww, vv in parse_fields(v)}
                    points.append((loc.get(1, 0), loc.get(2, 0), loc.get(3, 0)))
                elif f == 2:
                    index = v
                elif f == 3:
                    action = v
            out["touch"] = {"points": points, "action": action, "index": index}
        elif field == 4:
            buttons = []
            for f, w, v in parse_fields(value):
                if f == 1:
                    b = {ff: vv for ff, ww, vv in parse_fields(v)}
                    buttons.append((b.get(1, 0), bool(b.get(2, 0))))
            out["buttons"] = buttons
    return out


def describe(m):
    if m.id == VERSION_REQUEST and m.channel == CONTROL and len(m.payload) >= 4:
        major, minor = struct.unpack(">HH", m.payload[:4])
        return "raw version %d.%d" % (major, minor)
    if m.id == SSL_HANDSHAKE:
        return "%d byte(s) of TLS handshake" % len(m.payload)
    fields = list(parse_fields(m.payload)) if m.payload else []
    if m.channel == CONTROL and m.id == SERVICE_DISCOVERY_RESPONSE:
        text = {f: v.decode("utf-8", "replace") for f, w, v in fields if w == 2 and f != 1}
        return "%d channel(s), head unit=%r, sw=%r" % (
            len(parse_channels(m.payload)), text.get(2, ""), text.get(10, ""))
    values = {f: v for f, w, v in fields}
    if m.id == CHANNEL_OPEN_RESPONSE:
        return "status=%s (%s)" % (values.get(1, "?"), "OK" if values.get(1) == 0 else "FAIL")
    if m.channel == CONTROL and m.id == AUTH_COMPLETE:
        return "status=%s" % values.get(1, "?")
    if m.channel == CONTROL and m.id == AUDIO_FOCUS_RESPONSE:
        return "audio_focus_state=%s" % values.get(1, "?")
    if m.channel in AV_CHANNELS and not m.control:
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
    if m.channel == INPUT and m.id == IN_BINDING_RESPONSE:
        return "status=%s (%s)" % (values.get(1, "?"), "OK" if values.get(1) == 0 else "FAIL")
    if m.channel == SENSOR and m.id == SN_START_RESPONSE:
        return "status=%s (%s)" % (values.get(1, "?"), "OK" if values.get(1) == 0 else "FAIL")
    if m.channel == SENSOR and m.id == SN_EVENT_INDICATION:
        return describe_sensor(m.payload)
    return "%d field(s), %d byte(s)" % (len(fields), len(m.payload))


def describe_sensor(payload):
    parts = []
    for field, wire, value in parse_fields(payload):
        inner = {f: v for f, w, v in parse_fields(value)}
        if field == 10:
            parts.append("night=%s" % bool(inner.get(1, 0)))
        elif field == 13:
            parts.append("driving_status=%s" % inner.get(1, 0))
        else:
            parts.append("field%d" % field)
    return ", ".join(parts) or "empty"


# ---- H.264 and PCM sources ------------------------------------------------------------------

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


def tone(rate, channels, frequency, start_s, duration_s):
    """16-bit little-endian PCM sine, `duration_s` long, starting at phase of `start_s`."""
    count = int(rate * duration_s)
    first = int(rate * start_s)
    out = bytearray()
    for n in range(first, first + count):
        sample = int(12000 * math.sin(2 * math.pi * frequency * n / rate))
        out += struct.pack("<h", sample) * channels
    return bytes(out)


# ---- probe ----------------------------------------------------------------------------------

class WireError(Exception):
    pass


class Probe:
    def __init__(self, host, port, timeout, quiet_frames=True):
        sock = socket.create_connection((host, port), timeout=timeout)
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        sock.settimeout(timeout)
        self.timeout = timeout
        self.tls = PhoneTls(PROBE_CERT)
        self.link = Link(sock, self.tls)
        self.violations = []
        self.checks = []
        self.events = []            # decoded input/sensor events received while streaming
        self.pings_answered = 0

    # -- io
    def send(self, channel, control, message_id, payload=b"", quiet=False, plain=False):
        use_tls = self.tls if (self.tls.done and not plain) else None
        self.link.sock.sendall(encode_message(channel, control, message_id, payload, use_tls))
        if not quiet:
            print("-> %-12s %-26s %d byte(s)%s"
                  % (CHANNEL_NAMES.get(channel, channel),
                     message_name(channel, control, message_id), len(payload),
                     "  [encrypted]" if use_tls else ""))

    def audit(self, m):
        """What aasdk does, checked on every message from the head unit."""
        if m.channel == CONTROL and m.control:
            self.violations.append("%s on channel 0 had the CONTROL flag set; aasdk sends channel-0 "
                                   "messages as SPECIFIC" % message_name(m.channel, m.control, m.id))
        should_be_plain = m.channel == CONTROL and m.id in ALWAYS_PLAIN
        if self.tls.done and m.encrypted == should_be_plain:
            self.violations.append("%s was %s after TLS was up" % (
                message_name(m.channel, m.control, m.id),
                "encrypted" if m.encrypted else "in the clear"))

    def show(self, m, quiet=False):
        self.audit(m)
        # The head unit pings every 5 s and drops a phone that leaves two unanswered, as OpenAuto does.
        if m.channel == CONTROL and m.id == PING_REQUEST:
            self.send(CONTROL, False, PING_RESPONSE, m.payload, quiet=True)
            self.pings_answered += 1
            return
        if not quiet:
            print("<- %-12s %-26s %s%s"
                  % (CHANNEL_NAMES.get(m.channel, m.channel),
                     message_name(m.channel, m.control, m.id), describe(m),
                     "  [encrypted]" if m.encrypted else ""))

    def receive(self, quiet=False, expect=None):
        """Next message that is not an input event / unsolicited sensor update."""
        while True:
            m = self.link.next(self.timeout)
            self.show(m, quiet=quiet)
            if m.channel == CONTROL and m.id == PING_REQUEST:
                continue                    # answered in show(); never the reply the caller wants
            if m.channel == INPUT and m.id == IN_EVENT_INDICATION:
                self.record_input(m)
                continue
            if expect is not None and not expect(m):
                # an unrelated sensor update may legitimately arrive first
                if m.channel == SENSOR and m.id == SN_EVENT_INDICATION:
                    self.record_sensor(m)
                    continue
            return m

    def check(self, name, ok, detail=""):
        self.checks.append((name, ok, detail))
        print("CHECK %-4s %s%s" % ("ok" if ok else "FAIL", name, (" — " + detail) if detail else ""))

    def record_input(self, m):
        event = parse_touch(m.payload)
        self.events.append(("input", event))
        if "touch" in event:
            t = event["touch"]
            print("EVENT touch %s %s" % (TOUCH_ACTIONS.get(t["action"], t["action"]),
                                         " ".join("%d,%d#%d" % p for p in t["points"])))
        for code, pressed in event.get("buttons", []):
            print("EVENT key %d %s" % (code, "down" if pressed else "up"))

    def record_sensor(self, m):
        text = describe_sensor(m.payload)
        self.events.append(("sensor", text))
        print("EVENT sensor %s" % text)

    # -- stages
    def open_channel(self, channel):
        self.send(channel, True, CHANNEL_OPEN_REQUEST, pb_varint(1, 0) + pb_varint(2, channel))
        m = self.receive()
        values = {f: v for f, w, v in parse_fields(m.payload)} if m.id == CHANNEL_OPEN_RESPONSE else {}
        ok = values.get(1) == 0 and m.channel == channel and m.control
        if values.get(1) == 0 and (m.channel != channel or not m.control):
            self.violations.append("channel open response should be CONTROL-typed on channel %d" % channel)
        return ok

    def connect(self, device_name):
        m = self.receive()
        if m.id != VERSION_REQUEST or m.channel != CONTROL:
            raise WireError("expected VERSION_REQUEST first on the control channel")
        if len(m.payload) != 4:
            raise WireError("VERSION_REQUEST payload is %d bytes; aasdk sends 4 raw bytes "
                            "[major u16][minor u16]" % len(m.payload))
        if m.flags != FRAME_BULK:
            raise WireError("VERSION_REQUEST flags are 0x%02X; aasdk sends 0x03" % m.flags)
        self.send(CONTROL, False, VERSION_RESPONSE, struct.pack(">HHH", 1, 7, 0), plain=True)
        while True:
            m = self.receive()
            if m.id == SSL_HANDSHAKE:
                reply = self.tls.handshake(m.payload)
                if reply:
                    self.send(CONTROL, False, SSL_HANDSHAKE, reply, plain=True)
                continue
            if m.id == AUTH_COMPLETE:
                if not self.tls.done:
                    raise WireError("AUTH_COMPLETE arrived before the TLS handshake finished")
                break
            raise WireError("unexpected %s during the handshake"
                            % message_name(m.channel, m.control, m.id))
        print("     tls up: %s / %s" % (self.tls.obj.version(), self.tls.obj.cipher()[0]))
        self.send(CONTROL, False, SERVICE_DISCOVERY_REQUEST,
                  pb_string(4, device_name) + pb_string(5, "AiPlay probe"))
        m = self.receive()
        if m.id != SERVICE_DISCOVERY_RESPONSE:
            raise WireError("expected SERVICE_DISCOVERY_RESPONSE")
        return parse_channels(m.payload)

    def sensors(self):
        if not self.open_channel(SENSOR):
            self.check("sensor channel opens", False)
            return
        results = {}
        for sensor_type in (SENSOR_NIGHT, SENSOR_DRIVING):
            self.send(SENSOR, False, SN_START_REQUEST, pb_varint(1, sensor_type) + pb_varint(2, 1000))
            response = self.receive(expect=lambda x: x.id == SN_START_RESPONSE)
            event = self.receive()
            if response.id != SN_START_RESPONSE or event.id != SN_EVENT_INDICATION:
                self.check("sensor %d answered with response then event" % sensor_type, False)
                return
            results[sensor_type] = (dict((f, v) for f, w, v in parse_fields(response.payload)).get(1),
                                    describe_sensor(event.payload))
        self.check("night mode sensor delivered", results[SENSOR_NIGHT][0] == 0
                   and "night=" in results[SENSOR_NIGHT][1], results[SENSOR_NIGHT][1])
        self.check("driving status sensor delivered", results[SENSOR_DRIVING][0] == 0
                   and "driving_status=0" in results[SENSOR_DRIVING][1], results[SENSOR_DRIVING][1])

    def bind_input(self):
        if not self.open_channel(INPUT):
            self.check("input channel opens", False)
            return
        body = b"".join(pb_varint(1, code) for code in KEYS_TO_BIND)
        self.send(INPUT, False, IN_BINDING_REQUEST, body)
        m = self.receive(expect=lambda x: x.id == IN_BINDING_RESPONSE)
        status = dict((f, v) for f, w, v in parse_fields(m.payload)).get(1)
        self.check("input binding accepted", m.id == IN_BINDING_RESPONSE and status == 0)

    def audio_focus(self):
        self.send(CONTROL, False, AUDIO_FOCUS_REQUEST, pb_varint(1, FOCUS_GAIN_NAVI))
        m = self.receive(expect=lambda x: x.id == AUDIO_FOCUS_RESPONSE)
        state = dict((f, v) for f, w, v in parse_fields(m.payload)).get(1)
        self.check("audio focus granted", m.id == AUDIO_FOCUS_RESPONSE and state == FOCUS_STATE_GAIN,
                   "state=%s" % state)
        self.send(CONTROL, False, NAVIGATION_FOCUS_REQUEST, pb_varint(1, 1))
        m = self.receive(expect=lambda x: x.id == NAVIGATION_FOCUS_RESPONSE)
        self.check("navigation focus answered", m.id == NAVIGATION_FOCUS_RESPONSE)

    def setup_av(self, channel):
        if not self.open_channel(channel):
            self.check("%s opens" % CHANNEL_NAMES[channel], False)
            return None
        self.send(channel, False, AV_SETUP_REQUEST, pb_varint(1, 0))
        m = self.receive(expect=lambda x: x.id == AV_SETUP_RESPONSE)
        values = {f: v for f, w, v in parse_fields(m.payload)}
        if m.id != AV_SETUP_RESPONSE or values.get(1) != AV_SETUP_OK:
            self.check("%s setup accepted" % CHANNEL_NAMES[channel], False)
            return None
        return max(1, values.get(2, 1))

    def stream(self, units, fast, with_audio):
        """Video at 30 fps plus audio, concurrently, respecting every channel's ack window."""
        window = {}
        for channel in AV_CHANNELS:
            if channel == VIDEO or with_audio:
                window[channel] = self.setup_av(channel)
        if window.get(VIDEO) is None:
            return False
        window = {c: w for c, w in window.items() if w is not None}

        self.send(VIDEO, False, AV_VIDEO_FOCUS_REQUEST,
                  pb_varint(1, 0) + pb_varint(2, FOCUS_FOCUSED) + pb_varint(3, 1))
        m = self.receive(expect=lambda x: x.id == AV_VIDEO_FOCUS_INDICATION)
        if m.id != AV_VIDEO_FOCUS_INDICATION or dict((f, v) for f, w, v in
                                                     parse_fields(m.payload)).get(1) != FOCUS_FOCUSED:
            print("     video focus was not granted")
            return False

        session = {VIDEO: 1, MEDIA_AUDIO: 2, SPEECH_AUDIO: 3, SYSTEM_AUDIO: 4}
        for channel in window:
            self.send(channel, False, AV_START_INDICATION, pb_varint(1, session[channel]) + pb_varint(2, 0))

        # What each channel will send, as (due time in seconds, payload).
        plan = {c: collections.deque() for c in window}
        for index, unit in enumerate(units):
            plan[VIDEO].append((index / 30.0, unit))
        clip_seconds = len(units) / 30.0
        chunk_s = 0.02
        if MEDIA_AUDIO in window:
            for n in range(int(clip_seconds / chunk_s)):
                plan[MEDIA_AUDIO].append((n * chunk_s, tone(48000, 2, 440, n * chunk_s, chunk_s)))
        if SPEECH_AUDIO in window and clip_seconds >= 1.5:
            for n in range(int(0.8 / chunk_s)):         # a prompt from t=0.5s to t=1.3s
                plan[SPEECH_AUDIO].append((0.5 + n * chunk_s, tone(16000, 1, 880, n * chunk_s, chunk_s)))
        if SYSTEM_AUDIO in window:
            for n in range(int(0.2 / chunk_s)):         # one short beep
                plan[SYSTEM_AUDIO].append((0.2 + n * chunk_s, tone(16000, 1, 1320, n * chunk_s, chunk_s)))

        outstanding = {c: 0 for c in window}
        sent = {c: 0 for c in window}
        acked = {c: 0 for c in window}
        started_at = {}
        stopped = set()
        started = time.time()
        first_logged = set()
        while any(plan[c] for c in window) or any(outstanding.values()):
            now = time.time() - started
            progressed = False
            for channel in window:
                queue = plan[channel]
                if not queue:
                    if channel != VIDEO and channel not in stopped and outstanding[channel] == 0 \
                            and sent[channel] > 0:
                        self.send(channel, False, AV_STOP_INDICATION)
                        stopped.add(channel)
                    continue
                due, payload = queue[0]
                if (fast or due <= now) and outstanding[channel] < window[channel]:
                    queue.popleft()
                    stamp = int(due * 1000000) if channel != VIDEO else sent[channel] * 33333
                    self.send(channel, False, AV_MEDIA_WITH_TIMESTAMP, struct.pack(">Q", stamp) + payload,
                              quiet=channel in first_logged)
                    first_logged.add(channel)
                    outstanding[channel] += 1
                    sent[channel] += 1
                    progressed = True
            next_due = min([plan[c][0][0] for c in window if plan[c]] or [now + 0.05])
            wait = 0.0 if progressed else max(0.001, min(0.02, next_due - (time.time() - started)))
            self.link.poll(wait if (not fast or not progressed) else 0.0)
            while self.link.queue:
                m = self.link.queue.popleft()
                self.show(m, quiet=True)
                if m.id == AV_MEDIA_ACK and m.channel in outstanding and not m.control:
                    outstanding[m.channel] -= 1
                    acked[m.channel] += 1
                elif m.channel == INPUT and m.id == IN_EVENT_INDICATION:
                    self.record_input(m)
                elif m.channel == SENSOR and m.id == SN_EVENT_INDICATION:
                    self.record_sensor(m)
            if time.time() - started > 60 + clip_seconds * 4:
                print("     timed out waiting for acks: outstanding=%s" % outstanding)
                return False
        for channel in window:
            if channel != VIDEO and channel not in stopped:
                self.send(channel, False, AV_STOP_INDICATION)
        elapsed = time.time() - started
        print("     sent %d picture(s), %d acked, %.1fs (%.1f fps)"
              % (sent[VIDEO], acked[VIDEO], elapsed, sent[VIDEO] / elapsed if elapsed else 0))
        for channel in window:
            if channel != VIDEO:
                print("     %s: sent %d chunk(s), %d acked" % (CHANNEL_NAMES[channel], sent[channel],
                                                              acked[channel]))
        ok = acked[VIDEO] == len(units)
        for channel in window:
            if channel != VIDEO:
                ok = ok and acked[channel] == sent[channel] and sent[channel] > 0
        self.check("every video and audio message was acked", ok)
        return ok

    def hold(self, seconds, stop_on):
        """Stay connected, printing whatever the head unit sends (touches, keys, night mode)."""
        print("HOLD-START %.0f" % seconds)
        sys.stdout.flush()
        deadline = time.time() + seconds
        night_seen = False
        while time.time() < deadline:
            try:
                self.link.poll(0.2)
            except TimeoutError:
                continue
            while self.link.queue:
                m = self.link.queue.popleft()
                self.show(m, quiet=True)
                if m.channel == INPUT and m.id == IN_EVENT_INDICATION:
                    self.record_input(m)
                elif m.channel == SENSOR and m.id == SN_EVENT_INDICATION:
                    self.record_sensor(m)
                    if "night=True" in describe_sensor(m.payload):
                        night_seen = True
            sys.stdout.flush()
            if stop_on == "night" and night_seen:
                break
        print("HOLD-END")
        sys.stdout.flush()


def run(args):
    probe = Probe(args.host, args.port, args.timeout)
    print("connected  : plain tcp (TLS runs inside the protocol)")
    ok = False
    try:
        channels = probe.connect(args.name)
        advertised = {}
        for channel_id, kind, detail in channels:
            advertised[channel_id] = (kind, detail)
            extra = ""
            if kind == "av" and detail:
                extra = " stream_type=%s" % detail["stream"]
                if detail["config"]:
                    extra += " audio=%s Hz/%s-bit/%s ch" % detail["config"]
            elif kind == "sensor":
                extra = " sensors=%s" % detail
            elif kind == "input":
                extra = " keys=%s" % detail["keys"]
            print("     channel %-3d %-11s%s" % (channel_id, kind, extra))
        ok = True

        probe.send(CONTROL, False, PING_REQUEST, pb_varint(1, 1234567))
        probe.receive()

        # --- what was advertised
        for channel, (rate, bits, ch_count) in AUDIO_FORMATS.items():
            kind, detail = advertised.get(channel, (None, None))
            probe.check("%s advertised as %d Hz/%d ch" % (CHANNEL_NAMES[channel], rate, ch_count),
                        kind == "av" and detail and detail["config"] == (rate, bits, ch_count),
                        str(detail["config"] if detail else None))
        probe.check("input advertises only forwarded keys (no HOME/BACK)",
                    INPUT in advertised and 3 not in advertised[INPUT][1]["keys"]
                    and 4 not in advertised[INPUT][1]["keys"])

        if args.channel is not None:      # explicit single-channel mode (as before)
            if not probe.open_channel(args.channel):
                print("     receiver refused channel %d" % args.channel)
                return 1
            print("     channel %d opened" % args.channel)
        else:
            if SENSOR in advertised:
                probe.sensors()
            if INPUT in advertised:
                probe.bind_input()
            if any(c in advertised for c in AUDIO_FORMATS):
                probe.audio_focus()
            if VIDEO in advertised and not args.no_video:
                with open(args.clip, "rb") as handle:
                    units = split_access_units(handle.read())
                if not units:
                    print("     %s contains no H.264 pictures" % args.clip)
                    return 1
                print("     clip %s: %d picture(s)" % (os.path.basename(args.clip), len(units)))
                if not probe.stream(units, args.fast, not args.no_audio):
                    ok = False
                if args.hold > 0:
                    probe.hold(args.hold, args.stop_on)
                    if args.hold >= 6:
                        probe.check("the head unit's keep-alive pings were answered",
                                    probe.pings_answered >= 1, "%d ping(s)" % probe.pings_answered)
                probe.send(VIDEO, False, AV_STOP_INDICATION)
    except WireError as error:
        print("WIRE MISMATCH: %s" % error)
        ok = False
    finally:
        try:
            probe.send(CONTROL, False, SHUTDOWN_REQUEST)
        except OSError:
            pass
        probe.link.sock.close()

    print()
    failed = [name for name, passed, _ in probe.checks if not passed]
    if probe.violations:
        for violation in sorted(set(probe.violations)):
            print("WIRE MISMATCH: %s" % violation)
        print("RESULT: the head unit does not match aasdk's wire behaviour")
        return 1
    if failed:
        print("RESULT: %d check(s) failed: %s" % (len(failed), "; ".join(failed)))
        return 1
    print("RESULT: %s" % ("handshake complete over in-band TLS; sensor, input, audio and video all "
                          "worked — receiver speaks Android Auto"
                          if ok and probe.checks else "handshake complete over in-band TLS — "
                          "receiver speaks Android Auto" if ok else "handshake incomplete"))
    return 0 if ok else 1


def main():
    parser = argparse.ArgumentParser(description="Probe an AiPlay Android Auto receiver")
    parser.add_argument("host", help="phone/head unit IP address")
    parser.add_argument("--port", type=int, default=5277)
    parser.add_argument("--timeout", type=float, default=15.0)
    parser.add_argument("--name", default="AiPlay probe", help="device name sent to the receiver")
    parser.add_argument("--channel", type=int, default=None,
                        help="only open this channel id, then stop (skips sensor/input/audio/video)")
    parser.add_argument("--no-video", action="store_true", help="do not stream the test clip")
    parser.add_argument("--no-audio", action="store_true", help="stream video only")
    parser.add_argument("--clip", default=DEFAULT_CLIP, help="Annex-B H.264 file to stream")
    parser.add_argument("--fast", action="store_true", help="send as fast as acks allow")
    parser.add_argument("--hold", type=float, default=0.0,
                        help="after streaming, stay connected this many seconds and print the "
                             "touches/keys/night-mode changes the head unit sends")
    parser.add_argument("--stop-on", choices=("night",), default=None,
                        help="end the hold early once this event arrives")
    args = parser.parse_args()
    try:
        return run(args)
    except (OSError, EOFError, ValueError, TimeoutError, ssl.SSLError) as error:
        print("FAILED: %s" % error)
        print("check: same Wi-Fi network, receiver started in the app, port %d not blocked"
              % args.port)
        return 2


if __name__ == "__main__":
    sys.exit(main())
