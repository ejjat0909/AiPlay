#!/usr/bin/env python3
"""Generate AiPlay launcher icons. Uses Pillow; run once, commit the PNGs."""
import math
import os
import sys

try:
    from PIL import Image, ImageDraw
except ImportError:
    sys.exit("pillow is required: pip install pillow")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

BG = (10, 16, 32, 255)
GLOW = (18, 32, 58, 255)
CYAN = (34, 211, 238, 255)
BLUE = (59, 130, 246, 255)
WHITE = (255, 255, 255, 255)


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(4))


def draw_icon(size):
    scale = 8
    s = size * scale
    img = Image.new("RGBA", (s, s), BG)
    d = ImageDraw.Draw(img)

    # soft radial glow
    for i in range(28, 0, -1):
        r = int(s * (0.16 + 0.012 * i))
        alpha = int(70 * (1 - i / 28.0))
        d.ellipse([s / 2 - r, s / 2 - r, s / 2 + r, s / 2 + r], fill=(18, 50, 92, alpha))

    ring = s * 0.30
    width = max(2, int(s * 0.045))
    # two arcs, gradient approximated by segments
    for start, end in ((200, 320), (20, 80)):
        steps = 48
        for i in range(steps):
            a0 = math.radians(start + (end - start) * i / steps)
            a1 = math.radians(start + (end - start) * (i + 1) / steps) + 0.01
            color = lerp(CYAN, BLUE, i / float(steps))
            d.arc([s / 2 - ring, s / 2 - ring, s / 2 + ring, s / 2 + ring],
                  math.degrees(a0), math.degrees(a1), fill=color, width=width)

    # play glyph
    cx, cy = s / 2, s / 2
    t = ring * 0.62
    points = [(cx - t * 0.42, cy - t), (cx + t * 1.02, cy), (cx - t * 0.42, cy + t)]
    d.polygon(points, fill=WHITE)

    return img.resize((size, size), Image.LANCZOS)


def main():
    for bucket, size in SIZES.items():
        folder = os.path.join(ROOT, "app", "res", "mipmap-" + bucket)
        os.makedirs(folder, exist_ok=True)
        icon = draw_icon(size)
        icon.save(os.path.join(folder, "ic_launcher.png"))
        icon.save(os.path.join(folder, "ic_launcher_round.png"))
        print("wrote", folder, size)
    # store icon for the README
    docs = os.path.join(ROOT, "docs")
    os.makedirs(docs, exist_ok=True)
    draw_icon(512).save(os.path.join(docs, "icon.png"))


if __name__ == "__main__":
    main()
