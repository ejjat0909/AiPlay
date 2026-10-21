#!/usr/bin/env python3
"""
Check what the phone (aa-probe.py) received from the head unit after device-test.sh injected a tap,
a swipe, a key press and a day/night switch into the screen.

    verify-input.py <probe-output> <expected-file> [--audio-log <logcat-file>]

The expected file has lines:  tap X Y   |   swipe X1 Y1 X2 Y2   |   key CODE   |   night
(X/Y are phone coordinates, 0..1279 / 0..719). Exit 0 when every expectation holds.
"""
import re
import sys

TAP_TOLERANCE = 6
SWIPE_TOLERANCE = 10


def main():
    probe_output, expected_file = sys.argv[1], sys.argv[2]
    audio_log = sys.argv[sys.argv.index("--audio-log") + 1] if "--audio-log" in sys.argv else None

    touches, keys, sensors = [], [], []
    for line in open(probe_output, errors="replace"):
        m = re.match(r"EVENT touch (\w+) (\d+),(\d+)#(\d+)", line)
        if m:
            touches.append((m.group(1), int(m.group(2)), int(m.group(3))))
        m = re.match(r"EVENT key (\d+) (down|up)", line)
        if m:
            keys.append((int(m.group(1)), m.group(2)))
        m = re.match(r"EVENT sensor (.*)", line)
        if m:
            sensors.append(m.group(1))

    failures = []

    def report(ok, text):
        print("INPUT CHECK %-4s %s" % ("ok" if ok else "FAIL", text))
        if not ok:
            failures.append(text)

    def near(a, b, tol):
        return abs(a[0] - b[0]) <= tol and abs(a[1] - b[1]) <= tol

    for line in open(expected_file):
        parts = line.split()
        if not parts:
            continue
        kind = parts[0]
        if kind == "tap":
            target = (int(parts[1]), int(parts[2]))
            hits = [t for t in touches if t[0] == "PRESS" and near(t[1:], target, TAP_TOLERANCE)]
            releases = [t for t in touches if t[0] == "RELEASE" and near(t[1:], target, TAP_TOLERANCE)]
            report(bool(hits) and bool(releases),
                   "a tap reached the phone at ~%s (first events %s)"
                   % (target, [t for t in touches][:3]))
        elif kind == "swipe":
            x1, y1, x2, y2 = [int(v) for v in parts[1:5]]
            press = [i for i, t in enumerate(touches)
                     if t[0] == "PRESS" and near(t[1:], (x1, y1), SWIPE_TOLERANCE)]
            ok = False
            drags = 0
            if press:
                start = press[-1]
                tail = touches[start + 1:]
                drags = sum(1 for t in tail if t[0] == "DRAG")
                release = [t for t in tail if t[0] == "RELEASE" and near(t[1:], (x2, y2),
                                                                         SWIPE_TOLERANCE)]
                ok = drags > 0 and bool(release)
            report(ok, "swipe (%d,%d) -> (%d,%d): press, %d drag(s), release (events: %d)"
                   % (x1, y1, x2, y2, drags, len(touches)))
        elif kind == "presses":
            count = sum(1 for t in touches if t[0] == "PRESS")
            report(count == int(parts[1]),
                   "exactly %s presses reached the phone, so a tap on a letterbox bar was not "
                   "forwarded (got %d)" % (parts[1], count))
        elif kind == "screen":
            print("INPUT INFO  " + line.strip())
        elif kind == "key":
            code = int(parts[1])
            seq = [k[1] for k in keys if k[0] == code]
            report(seq[:2] == ["down", "up"], "key %d arrived as down then up (got %s)" % (code, seq))
        elif kind == "night":
            report(any("night=True" in s for s in sensors),
                   "switching the head unit to night mode reached the phone (sensor events: %s)"
                   % sensors)

    if audio_log:
        text = open(audio_log, errors="replace").read()
        for name in ("media-audio", "speech-audio", "system-audio"):
            m = re.search(r"audio: %s: finished, (\d+) frames written, (\d+) played" % name, text)
            report(bool(m) and int(m.group(1)) > 0,
                   "%s was received and written to the audio device (%s)"
                   % (name, "%s frames" % m.group(1) if m else "no 'finished' line"))
            if m:
                written, played = int(m.group(1)), int(m.group(2))
                report(played >= 0.9 * written,
                       "%s: the audio device played %d of %d frames" % (name, played, written))
        ducked = re.search(r"audio: media-audio volume 0\.25", text)
        restored = re.search(r"audio: media-audio volume 1\.0", text)
        report(bool(ducked) and bool(restored),
               "music was ducked while the prompt played and restored afterwards")

    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
