#!/usr/bin/env python3
"""
Render the two home-screen states for docs/states.png.

Left  : what 0.1.2 actually draws on a phone whose Android Auto is installed and enabled (P1).
Right : the session state AiPlay does NOT draw yet — a proposal for 0.2.0, labelled as such.

Geometry, palette and copy mirror HomeActivity.java / DeviceProfile.java so the reference cannot
drift from the code without looking wrong. These are reference renders, not device screenshots.
"""
import os

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOCS = os.path.join(ROOT, "docs")
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
FONT_BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
FONT_MONO = "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf"

BG1 = (10, 16, 32)
SURFACE = (17, 26, 44)
LINE = (30, 44, 69)
TEXT0 = (242, 246, 255)
TEXT1 = (157, 176, 204)
TEXT2 = (95, 114, 146)
ACCENT_A = (34, 211, 238)
ACCENT_B = (59, 130, 246)
OK = (52, 211, 153)
WARN = (245, 158, 11)
BAD = (239, 68, 68)

S = 1.7           # dp -> px
W, H = 428, 941   # the OPPO CPH2739 reports 428x941dp
GUTTER = 16
PAD = 14


def font(size, path=FONT):
    return ImageFont.truetype(path, int(size * S))


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def wrap(text, fnt, max_w):
    words = text.split()
    lines, line = [], ""
    for word in words:
        probe = (line + " " + word).strip()
        if fnt.getlength(probe) <= max_w or not line:
            line = probe
        else:
            lines.append(line)
            line = word
    if line:
        lines.append(line)
    return lines


def ring_gauge(draw, center, radius, width, value, label):
    cx, cy = center
    start, end = 140, 400  # degrees, gap at the bottom
    steps = 120
    span = end - start
    draw.arc([cx - radius, cy - radius, cx + radius, cy + radius],
             start=start, end=end, fill=LINE, width=width)
    filled = span * max(0.0, min(1.0, value / 100.0))
    for i in range(steps):
        t0 = i / float(steps)
        t1 = (i + 1) / float(steps)
        if t0 * span > filled:
            break
        a0 = start + span * t0
        a1 = start + min(span * t1, filled)
        draw.arc([cx - radius, cy - radius, cx + radius, cy + radius],
                 start=a0, end=a1, fill=lerp(ACCENT_A, ACCENT_B, t0), width=width)
    big = font(26, FONT_BOLD)
    draw.text((cx, cy - int(6 * S)), str(value), font=big, fill=TEXT0, anchor="mm")
    draw.text((cx, cy + int(20 * S)), label, font=font(11), fill=TEXT2, anchor="mm")


def pill(draw, x, y, text, fill, fg, padx=10, h=26):
    fnt = font(11)
    w = int(fnt.getlength(text) + 2 * padx * S)
    draw.rounded_rectangle([x - w, y, x, y + int(h * S)], radius=int(h * S / 2),
                           fill=fill, outline=LINE, width=max(1, int(S)))
    draw.text((x - w / 2, y + h * S / 2), text, font=fnt, fill=fg, anchor="mm")
    return w


def header(draw, device_line, running):
    cx, cy, r = int((GUTTER + 17) * S), int((14 + 17) * S), int(17 * S)
    steps = 40
    for i in range(steps):
        t0, t1 = i / steps, (i + 1) / steps
        draw.pieslice([cx - r, cy - r, cx + r, cy + r], start=t0 * 360, end=t1 * 360 + 1,
                      fill=lerp(ACCENT_A, ACCENT_B, t0))
    draw.text((int((GUTTER + 46) * S), int(14 * S)), "AiPlay", font=font(20, FONT_BOLD),
              fill=TEXT0)
    draw.text((int((GUTTER + 46) * S), int(44 * S)), device_line, font=font(11.5), fill=TEXT2)
    x = int((W - GUTTER) * S)
    x -= pill(draw, x, int(20 * S), "Settings", SURFACE, TEXT0)
    pill(draw, x - int(6 * S), int(20 * S), "Diagnostics", SURFACE, TEXT0)


def hero(draw, y, gauge_value, gauge_label, title, body, primary, caption, accent=ACCENT_B):
    x0, w = GUTTER, W - 2 * GUTTER
    body_lines = wrap(body, font(12.5), int((w - 142 - 14) * S))
    h = 14 + 26 + 6 + len(body_lines) * 17 + 10 + 46 + 8 + 14 + 14
    draw.rounded_rectangle([int(x0 * S), int(y * S), int((x0 + w) * S), int((y + h) * S)],
                           radius=int(18 * S), fill=SURFACE, outline=LINE, width=max(1, int(S)))
    ring_gauge(draw, (int((x0 + PAD + 56) * S), int((y + PAD + 56) * S)), int(48 * S),
               int(11 * S), gauge_value, gauge_label)

    col_x = x0 + PAD + 112 + 16
    col_w = w - (col_x - x0) - PAD
    ty = y + 12
    draw.text((int(col_x * S), int(ty * S)), title, font=font(18, FONT_BOLD), fill=TEXT0)
    ty += 26
    for line in body_lines:
        draw.text((int(col_x * S), int(ty * S)), line, font=font(12.5), fill=TEXT1)
        ty += 17
    ty += 10
    draw.rounded_rectangle([int(col_x * S), int(ty * S), int((col_x + col_w) * S),
                            int((ty + 46) * S)], radius=int(23 * S), fill=accent)
    draw.text((int((col_x + col_w / 2) * S), int((ty + 23) * S)), primary,
              font=font(13.5, FONT_BOLD), fill=(6, 12, 24), anchor="mm")
    ty += 46 + 8
    draw.text((int(col_x * S), int(ty * S)), caption, font=font(11.5), fill=TEXT2)
    return y + h


def section(draw, y, title, hint):
    draw.text((int(GUTTER * S), int(y * S)), title, font=font(15, FONT_BOLD), fill=TEXT0)
    draw.text((int((W - GUTTER) * S), int((y + 2) * S)), hint, font=font(11.5), fill=TEXT2,
              anchor="ra")
    return y + 26


def row(draw, y, label, value, state):
    draw.ellipse([int((GUTTER + 2) * S), int((y + 8) * S), int((GUTTER + 10) * S),
                  int((y + 16) * S)], fill=state)
    draw.text((int((GUTTER + 20) * S), int(y * S)), label, font=font(13.5), fill=TEXT1)
    color = TEXT2 if state == TEXT2 else (WARN if state == WARN else (BAD if state == BAD else TEXT1))
    draw.text((int((W - GUTTER) * S), int(y * S)), value, font=font(12.5), fill=color, anchor="ra")
    draw.line([int(GUTTER * S), int((y + 25) * S), int((W - GUTTER) * S), int((y + 25) * S)],
              fill=(LINE[0], LINE[1], LINE[2]), width=1)
    return y + 27


def footer(draw, left, right):
    y = H - 16 - 52
    for i, label in enumerate((left, right)):
        x0 = GUTTER + i * ((W - 2 * GUTTER + 10) / 2)
        x1 = x0 + (W - 2 * GUTTER - 10) / 2
        draw.rounded_rectangle([int(x0 * S), int(y * S), int(x1 * S), int((y + 52) * S)],
                               radius=int(14 * S), fill=SURFACE, outline=LINE,
                               width=max(1, int(S)))
        draw.text((int((x0 + x1) / 2 * S), int((y + 26) * S)), label, font=font(13), fill=TEXT0,
                  anchor="mm")


def panel_current():
    img = Image.new("RGB", (int(W * S), int(H * S)), BG1)
    d = ImageDraw.Draw(img)
    header(d, "CPH2739 (OP5F05L1) · Android 36 · 428x941dp", True)
    y = hero(d, 84, 100, "P1", "Android Auto is ready",
             "This unit already has Android Auto, enabled. No icon? Open it from Settings > "
             "Connected devices, or tap Open to jump to its App info.",
             "Open Android Auto", "Listening on tcp 5277", ACCENT_B)
    y = section(d, y + 18, "Capabilities", "probed on this device")
    rows = [
        ("Android Auto package", "installed 15.6.654484", OK),
        ("Android Auto enabled", "enabled", OK),
        ("Android Auto icon", "no icon (see Settings)", WARN),
        ("Google Play services", "present", OK),
        ("Root shell", "no su binary", BAD),
        ("Shizuku", "not installed", TEXT2),
        ("ADB", "disabled", TEXT2),
        ("USB host", "supported", OK),
        ("Wi-Fi Direct", "supported", OK),
        ("Bluetooth", "supported", OK),
        ("Receiver port", "listening (own) 5277", OK),
    ]
    for label, value, state in rows:
        y = row(d, y, label, value, state)
    footer(d, "ADB recipe", "Stop receiver")
    return img


def panel_connected():
    img = Image.new("RGB", (int(W * S), int(H * S)), BG1)
    d = ImageDraw.Draw(img)
    header(d, "AiPlay-dee2 · session active", True)

    # same hero geometry, but a session banner instead of the gauge label
    x0, w = GUTTER, W - 2 * GUTTER
    y = 84
    body = "Pixel 8 · wireless · TLS 1.2 · handshake complete. Control plane only: video, " \
           "audio and input arrive in 0.2.0."
    body_lines = wrap(body, font(12.5), int((w - 142 - 14) * S))
    h = 14 + 26 + 6 + len(body_lines) * 17 + 10 + 46 + 8 + 14 + 14
    d.rounded_rectangle([int(x0 * S), int(y * S), int((x0 + w) * S), int((y + h) * S)],
                        radius=int(18 * S), fill=SURFACE, outline=LINE, width=max(1, int(S)))
    cx, cy = int((x0 + PAD + 56) * S), int((y + PAD + 56) * S)
    ring_gauge(d, (cx, cy), int(48 * S), int(11 * S), 100, "LIVE")
    d.ellipse([cx - int(7 * S), cy + int(34 * S), cx + int(7 * S), cy + int(48 * S)], fill=OK)

    col_x = x0 + PAD + 112 + 16
    col_w = w - (col_x - x0) - PAD
    ty = y + 12
    d.rounded_rectangle([int(col_x * S), int(ty * S), int((col_x + 74) * S), int((ty + 16) * S)],
                        radius=int(8 * S), fill=(46, 32, 8))
    d.text((int((col_x + 37) * S), int((ty + 8) * S)), "EXPERIMENTAL", font=font(8.5, FONT_BOLD),
           fill=WARN, anchor="mm")
    ty += 22
    d.text((int(col_x * S), int(ty * S)), "Phone connected", font=font(18, FONT_BOLD), fill=TEXT0)
    ty += 26
    for line in body_lines:
        d.text((int(col_x * S), int(ty * S)), line, font=font(12.5), fill=TEXT1)
        ty += 17
    ty += 10
    d.rounded_rectangle([int(col_x * S), int(ty * S), int((col_x + col_w) * S),
                         int((ty + 46) * S)], radius=int(23 * S), fill=ACCENT_B)
    d.text((int((col_x + col_w / 2) * S), int((ty + 23) * S)), "Stop session",
           font=font(13.5, FONT_BOLD), fill=(6, 12, 24), anchor="mm")
    ty += 46 + 8
    d.text((int(col_x * S), int(ty * S)), "14.2 s · 38 KB in · 12 KB out", font=font(11.5),
           fill=TEXT2)

    y = y + h + 18
    y = section(d, y, "Session", "live")
    for label, value, state in [
        ("Transport", "tcp 5277 · TLS 1.2", OK),
        ("Handshake", "complete", OK),
        ("Service discovery", "6 channels", OK),
        ("Video channel", "0.2.0", WARN),
        ("Audio channel", "0.2.0", WARN),
        ("Input channel", "0.2.0", WARN),
    ]:
        y = row(d, y, label, value, state)

    y = section(d, y + 14, "Unit", "probed on this device")
    for label, value, state in [
        ("Android Auto", "15.6.654484 · enabled", OK),
        ("Root shell", "no su binary", BAD),
        ("Receiver port", "listening (own) 5277", OK),
    ]:
        y = row(d, y, label, value, state)
    footer(d, "Session log", "Stop receiver")
    return img


def main():
    current = panel_current()
    connected = panel_connected()

    cap_h, note_h = 34, 26
    gap = 28
    width = 2 * int(W * S) + 3 * gap
    height = cap_h + int(H * S) + note_h + 2 * gap
    canvas = Image.new("RGB", (width, height), (5, 7, 13))
    d = ImageDraw.Draw(canvas)

    def caption(x, text, color):
        d.text((x, gap - 8), text, font=font(13, FONT_BOLD), fill=color)

    left_x = gap
    right_x = 2 * gap + int(W * S)
    caption(left_x, "Now — 0.1.2 on this phone (P1 ready)", TEXT0)
    caption(right_x, "Proposed — phone connected (not in 0.1.2)", WARN)
    canvas.paste(current, (left_x, gap + cap_h - 10))
    canvas.paste(connected, (right_x, gap + cap_h - 10))
    note = ("Reference renders, not screenshots. Left: exactly what HomeActivity draws for "
            "installed+enabled gearhead with no launcher entry. Right: a proposal \u2014 0.1.2 "
            "shows no session state at all.")
    note_font = font(11)
    for i, line in enumerate(wrap(note, note_font, width - 2 * gap)):
        d.text((left_x, gap + cap_h - 10 + int(H * S) + 8 + i * 15), line, font=note_font,
               fill=TEXT2)

    out = os.path.join(DOCS, "states.png")
    canvas.save(out)
    print(out, canvas.size)


if __name__ == "__main__":
    main()
