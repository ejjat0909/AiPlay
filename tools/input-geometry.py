#!/usr/bin/env python3
"""
Work out where to inject touches for device-test.sh, and what the phone should receive.

    input-geometry.py <screenshot.png> <expect-file>

Prints shell variable assignments (CX, CY, TLX, TLY, BRX, BRY, SX1, SX2, BARX, BARY) and writes the
expectations that verify-input.py checks. The video is 1280x720 letterboxed (centred, 16:9) inside the
screen, so the corners of the *video* are not the corners of the screen on a 1024x600 or 1280x480 panel,
and a tap on a bar must not reach the phone at all.
"""
import struct
import sys

ASPECT = 1280.0 / 720.0


def main():
    png, expect = sys.argv[1], sys.argv[2]
    try:
        data = open(png, "rb").read()
        w, h = struct.unpack(">II", data[16:24])
    except Exception:
        w, h = 1920, 1080
    rw = min(float(w), h * ASPECT)
    rh = rw / ASPECT
    left = (w - rw) / 2.0
    top = (h - rh) / 2.0

    def to_phone(x, y):
        return (int(round((x - left) / rw * 1280)), int(round((y - top) / rh * 720)))

    cx, cy = w // 2, h // 2
    tl = (int(left) + 2, int(top) + 2)
    br = (int(left + rw) - 3, int(top + rh) - 3)
    dx = int(0.15 * rw)
    sx1, sx2 = cx - dx // 2, cx + dx // 2

    bar = ("", "")
    if top >= 4:                       # letterbox bars above and below
        bar = (cx, int(top / 2))
    elif left >= 4:                    # bars left and right
        bar = (int(left / 2), cy)

    p_tl, p_br = to_phone(*tl), to_phone(*br)
    p_s1, p_s2 = to_phone(sx1, cy), to_phone(sx2, cy)
    with open(expect, "w") as out:
        out.write("tap 640 360\n")
        out.write("tap %d %d\n" % p_tl)
        out.write("tap %d %d\n" % p_br)
        out.write("swipe %d %d %d %d\n" % (p_s1[0], p_s1[1], p_s2[0], p_s2[1]))
        out.write("key 22\n")
        out.write("night\n")
        out.write("presses 4\n")        # centre, two corners, swipe start: never the bar tap
        out.write("screen %dx%d video %dx%d at %d,%d bars %s\n"
                  % (w, h, rw, rh, left, top, "yes" if bar[0] != "" else "no"))
    print("CX=%d CY=%d TLX=%d TLY=%d BRX=%d BRY=%d SX1=%d SX2=%d BARX=%s BARY=%s"
          % (cx, cy, tl[0], tl[1], br[0], br[1], sx1, sx2, bar[0], bar[1]))


if __name__ == "__main__":
    main()
