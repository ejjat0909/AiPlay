#!/usr/bin/env python3
"""
Render design references for docs/.

These mirror the geometry, palette and easing of SplashView.java / HomeActivity.java so the
documentation shows what the code draws. They are reference renders, not device screenshots.
"""
import math
import os
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOCS = os.path.join(ROOT, "docs")
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
FONT_MED = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"

BG0 = (5, 7, 13)
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


def font(size, path=FONT):
    return ImageFont.truetype(path, size)


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def ease_out_cubic(x):
    return 1 - (1 - x) ** 3


def vertical_gradient(size, top, bottom):
    w, h = size
    img = Image.new("RGB", (1, h))
    px = img.load()
    for y in range(h):
        px[0, y] = lerp(top, bottom, y / float(h - 1))
    return img.resize((w, h))


def radial_glow(size, center, radius, color, peak_alpha):
    w, h = size
    layer = Image.new("L", (w, h), 0)
    d = ImageDraw.Draw(layer)
    steps = 48
    for i in range(steps, 0, -1):
        r = radius * i / steps
        alpha = int(peak_alpha * (1 - i / float(steps)) ** 1.6)
        d.ellipse([center[0] - r, center[1] - r, center[0] + r, center[1] + r], fill=alpha)
    layer = layer.filter(ImageFilter.GaussianBlur(radius * 0.08))
    glow = Image.new("RGB", (w, h), color)
    return glow, layer


def draw_splash(width=1280, height=720, t=0.9):
    base = vertical_gradient((width, height), BG0, BG1).convert("RGBA")
    cx, cy = width / 2, height * 0.42
    radius = min(width, height) * 0.17

    glow, mask = radial_glow((width, height), (cx, cy), radius * 3.4, (18, 50, 92), 110)
    base = Image.composite(glow, base, mask)

    ring = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    d = ImageDraw.Draw(ring)
    ms = t * 1600
    ring_progress = min(1.0, ms / 1100.0)
    rotation = 270 * ease_out_cubic(ring_progress)
    sweep = 96 * ease_out_cubic(ring_progress)
    stroke = 3
    box = [cx - radius, cy - radius, cx + radius, cy + radius]

    # glow pass
    for extra, alpha in ((11, 46),):
        d.arc([box[0] - extra / 2, box[1] - extra / 2, box[2] + extra / 2, box[3] + extra / 2],
              -90 + rotation, -90 + rotation + sweep, fill=(34, 211, 238, alpha), width=stroke + extra)
    for i in range(int(sweep)):
        a0 = -90 + rotation + i
        color = lerp(ACCENT_A, ACCENT_B, i / 96.0)
        d.arc(box, a0, a0 + 1.6, fill=color + (255,), width=stroke)
    second = 48 * ease_out_cubic(min(1.0, max(0.0, (ms - 260) / 900.0)))
    for i in range(int(second)):
        a0 = 40 + rotation * 0.0 + i
        color = lerp(ACCENT_A, ACCENT_B, i / 96.0)
        d.arc(box, a0, a0 + 1.6, fill=color + (255,), width=stroke)
    base = Image.alpha_composite(base, ring)

    # glyph
    glyph_progress = min(1.0, max(0.0, (ms - 380) / 520.0))
    if glyph_progress > 0:
        c = 1.70158
        v = glyph_progress - 1
        scale = 0.6 + 0.4 * (1 + (c + 1) * v ** 3 + c * v ** 2)
        if ms > 1200:
            scale *= 1 + 0.03 * math.sin((ms - 1200) / 260 * math.pi)
        size = radius * 0.66 * scale
        left = cx - size * 0.34
        top = cy - size * 0.5
        tri = [(left, top), (left + size * 0.92, cy), (left, top + size)]
        layer = Image.new("RGBA", (width, height), (0, 0, 0, 0))
        dl = ImageDraw.Draw(layer)
        dl.polygon(tri, fill=(255, 255, 255, 255))
        dl.line(tri + [tri[0]], fill=(255, 255, 255, 255), width=9, joint="curve")
        base = Image.alpha_composite(base, layer)

    d = ImageDraw.Draw(base)
    word_progress = min(1.0, max(0.0, (ms - 620) / 420.0))
    if word_progress > 0:
        alpha = ease_out_cubic(word_progress)
        f = font(46, FONT_MED)
        text = "AiPlay"
        tw = d.textlength(text, font=f)
        y = cy + radius * 1.72 + 12 * (1 - alpha)
        d.text((cx - tw / 2, y), text, font=f, fill=TEXT0 + (int(255 * alpha),))

    tag_progress = min(1.0, max(0.0, (ms - 900) / 400.0))
    if tag_progress > 0:
        alpha = ease_out_cubic(tag_progress)
        f = font(17)
        text = "Android Auto  \u00b7  for every head unit"
        tw = d.textlength(text, font=f)
        d.text((cx - tw / 2, cy + radius * 1.72 + 46), text, font=f,
               fill=TEXT1 + (int(255 * alpha),))

    line_progress = min(1.0, max(0.0, (ms - 200) / 1200.0))
    lw = 132
    ly = height * 0.82
    d.line([cx - lw / 2, ly, cx + lw / 2, ly], fill=(255, 255, 255, 34), width=2)
    if line_progress > 0:
        seg = int(lw * line_progress)
        for i in range(seg):
            color = lerp(ACCENT_A, ACCENT_B, i / float(lw))
            d.line([cx - lw / 2 + i, ly, cx - lw / 2 + i + 1, ly], fill=color + (255,), width=2)

    f = font(13)
    d.text((width - 18 - d.textlength("v0.1.0", font=f), height - 26), "v0.1.0", font=f,
           fill=TEXT2 + (255,))

    # vignette
    vign, vmask = radial_glow((width, height), (cx, cy), max(width, height) * 0.78, (0, 0, 0), 200)
    inv = Image.eval(vmask, lambda v: 0 if v < 40 else v)
    base = Image.composite(Image.new("RGB", (width, height), (0, 0, 0)).convert("RGBA"),
                           base, inv.point(lambda v: int(v * 0.55)))
    return base.convert("RGB")


def rounded(d, box, radius, fill=None, outline=None, width=1):
    d.rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=width)


def draw_home(width=1024, height=600):
    img = Image.new("RGB", (width, height), BG1)
    d = ImageDraw.Draw(img)
    pad = 20

    # header
    d.ellipse([pad, pad, pad + 34, pad + 34], fill=ACCENT_A)
    d.ellipse([pad + 6, pad + 6, pad + 28, pad + 28], fill=ACCENT_B)
    d.text((pad + 46, pad + 2), "AiPlay", font=font(26), fill=TEXT0)
    d.text((pad + 46, pad + 32), "BYD DiLink 5.1 \u00b7 Android 10 \u00b7 1024x600dp",
           font=font(14), fill=TEXT2)

    for label, x in (("Diagnostics", width - 210), ("Settings", width - 100)):
        rounded(d, [x, pad + 2, x + (100 if label == "Diagnostics" else 80), pad + 36], 18,
                fill=SURFACE, outline=LINE)
        d.text((x + 14, pad + 11), label, font=font(14), fill=TEXT1)

    # hero card
    top = 84
    rounded(d, [pad, top, width - pad, top + 210], 18, fill=SURFACE, outline=LINE)
    cx, cy, r = pad + 90, top + 105, 56
    d.arc([cx - r, cy - r, cx + r, cy + r], 135, 405, fill=(27, 39, 64), width=9)
    d.arc([cx - r, cy - r, cx + r, cy + r], 135, 135 + 270 * 0.62, fill=ACCENT_A, width=9)
    d.text((cx - 24, cy - 26), "62", font=font(38), fill=TEXT0)
    d.text((cx - 20, cy + 20), "P2", font=font(13), fill=TEXT2)

    tx = cx + r + 36
    d.text((tx, top + 26), "Android Auto is disabled", font=font(24), fill=TEXT0)
    d.text((tx, top + 64), "Android Auto is installed on this unit but turned off for user 0.",
           font=font(14), fill=TEXT1)
    d.text((tx, top + 84), "One command turns it back on.", font=font(14), fill=TEXT1)

    rounded(d, [tx, top + 122, tx + 300, top + 178], 14, fill=ACCENT_B)
    d.text((tx + 24, top + 140), "Enable Android Auto", font=font(17), fill=(255, 255, 255))
    d.text((tx, top + 188), "Receiver stopped", font=font(12), fill=TEXT2)

    # capabilities
    y = top + 236
    d.text((pad, y), "Capabilities", font=font(17), fill=TEXT0)
    d.text((width - pad - 130, y + 3), "probed on this device", font=font(12), fill=TEXT2)
    rows = [
        ("Android Auto package", "installed 12.3", OK),
        ("Android Auto enabled", "disabled for user 0", BAD),
        ("Google Play services", "present", OK),
        ("Root shell", "no su binary", BAD),
        ("Wi-Fi Direct", "supported", OK),
    ]
    y += 30
    for label, value, color in rows:
        d.ellipse([pad, y + 8, pad + 8, y + 16], fill=color)
        d.text((pad + 20, y + 3), label, font=font(15), fill=TEXT1)
        vw = d.textlength(value, font=font(14))
        d.text((width - pad - vw, y + 4), value, font=font(14),
               fill=WARN if color == BAD else TEXT2)
        d.line([pad, y + 30, width - pad, y + 30], fill=(255, 255, 255, 20))
        y += 32
    d.line([pad, y + 30, width - pad, y + 30], fill=(255, 255, 255, 20))

    # footer buttons
    by = height - 62
    half = (width - 3 * pad) / 2
    rounded(d, [pad, by, pad + half, by + 46], 14, fill=SURFACE, outline=LINE)
    label = "ADB recipe"
    d.text((pad + half / 2 - d.textlength(label, font=font(15)) / 2, by + 14), label,
           font=font(15), fill=TEXT0)
    x2 = pad + half + pad
    rounded(d, [x2, by, width - pad, by + 46], 14, fill=SURFACE, outline=LINE)
    label = "Stop receiver"
    d.text((x2 + (width - pad - x2) / 2 - d.textlength(label, font=font(15)) / 2, by + 14),
           label, font=font(15), fill=TEXT0)
    return img


def main():
    os.makedirs(DOCS, exist_ok=True)
    splash = draw_splash()
    splash.save(os.path.join(DOCS, "splash-preview.png"))
    splash.resize((640, 360), Image.LANCZOS).save(os.path.join(DOCS, "splash-preview-small.png"))
    home = draw_home()
    home.save(os.path.join(DOCS, "home-preview.png"))
    print("wrote splash-preview.png, home-preview.png")


if __name__ == "__main__":
    main()
