#!/usr/bin/env python3
"""Generate GeoStamp Camera legacy launcher PNGs from the same design as the
adaptive-icon vector: white location pin + camera lens on a green gradient.
Draws at 512px, downscales to each density. Run from the repo root:
    python3 tools/build_icon.py
"""
import math
import os

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app/src/main/res")

S = 512  # canvas px; 108dp viewport -> K px per dp
K = S / 108.0

START = (0x14, 0xB8, 0x7A)  # #14B87A
END = (0x0B, 0x6E, 0x4F)    # #0B6E4F
PIN = (255, 255, 255)
LENS = (0x0B, 0x6E, 0x4F)

# Pin geometry in the 108dp viewport (matches ic_launcher_foreground.xml):
# round top centre (54, 42.4) r 22.4, tip (54, 84).
CX, CY = 54 * K, 42.4 * K
R = 22.4 * K
TIP_Y = 84 * K
LENS_R = 13 * K
HL = (58.5 * K, 38 * K)
HL_R = 3.5 * K


def gradient(size):
    """Vertical green gradient (bright top -> deep bottom)."""
    g = Image.new("RGB", (1, size))
    px = g.load()
    for y in range(size):
        t = y / (size - 1)
        px[0, y] = tuple(round(START[i] + (END[i] - START[i]) * t) for i in range(3))
    return g.resize((size, size)).convert("RGBA")


def draw_icon():
    img = gradient(S)
    d = ImageDraw.Draw(img)
    # Pin: circle + tangent-sided triangle to the tip (smooth teardrop).
    d.ellipse([CX - R, CY - R, CX + R, CY + R], fill=PIN)
    dx = TIP_Y - CY
    dist = math.hypot(0, dx)
    beta = math.acos(R / dist)  # angle between centre->tip and centre->tangent point
    # tangent points: centre->tip direction (0,1) rotated by +/-beta
    tx = R * math.sin(beta)
    ty = R * math.cos(beta)
    d.polygon([(CX - tx, CY + ty), (CX + tx, CY + ty), (CX, TIP_Y)], fill=PIN)
    # Camera lens + highlight.
    d.ellipse([CX - LENS_R, CY - LENS_R, CX + LENS_R, CY + LENS_R], fill=LENS)
    d.ellipse([HL[0] - HL_R, HL[1] - HL_R, HL[0] + HL_R, HL[1] + HL_R], fill=PIN)
    return img


def round_mask(size):
    m = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(m)
    d.ellipse([0, 0, size, size], fill=255)
    return m


def main():
    base = draw_icon()
    base.save(os.path.join(ROOT, "tools", "icon-512.png"))
    sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for density, px in sizes.items():
        folder = os.path.join(RES, "mipmap-" + density)
        os.makedirs(folder, exist_ok=True)
        small = base.resize((px, px), Image.LANCZOS)
        small.save(os.path.join(folder, "ic_launcher.png"))
        circ = Image.new("RGBA", (px, px), (0, 0, 0, 0))
        circ.paste(small, (0, 0), round_mask(px))
        circ.save(os.path.join(folder, "ic_launcher_round.png"))
        print(density, px, "done")
    print("icon written")


if __name__ == "__main__":
    main()
