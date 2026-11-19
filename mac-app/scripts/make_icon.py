#!/usr/bin/env python3
"""Generate the Agent HUD app icon.

Outputs (relative to mac-app/):
  Resources/AppIcon-1024.png   master PNG
  Resources/AppIcon.iconset/   all sizes (via sips)
  Resources/AppIcon.icns       (via iconutil)

Design: macOS squircle tile, deep blue-black vertical gradient, a round black watch
face with a thin track ring, an orbit ring carrying three beads (Claude orange, Codex
lavender->blue, Antigravity blue with a warm cap) and an off-white ">_" glyph.
Everything is drawn at 4x and downsampled for clean anti-aliasing.

Usage: python3 scripts/make_icon.py
"""
import math
import os
import shutil
import subprocess
import sys

from PIL import Image, ImageChops, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
RES = os.path.join(ROOT, "Resources")

SS = 4                 # supersampling factor
N = 1024 * SS          # working canvas size


def s(v):
    """Scale a 1024-space value to the working canvas."""
    return v * SS


def hex_rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def lerp(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(len(a)))


def squircle_mask(size, inset, exponent=5.0):
    """Superellipse mask approximating the macOS Big Sur+ icon shape."""
    mask = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(mask)
    cx = cy = size / 2
    r = size / 2 - inset
    pts = []
    steps = 2000
    for i in range(steps):
        t = 2 * math.pi * i / steps
        ct, st = math.cos(t), math.sin(t)
        x = cx + r * math.copysign(abs(ct) ** (2 / exponent), ct)
        y = cy + r * math.copysign(abs(st) ** (2 / exponent), st)
        pts.append((x, y))
    d.polygon(pts, fill=255)
    return mask


def vertical_gradient(size, top, bottom):
    grad = Image.new("RGB", (1, size))
    for y in range(size):
        grad.putpixel((0, y), lerp(top, bottom, y / (size - 1)))
    return grad.resize((size, size))


def diagonal_gradient(size, c1, c2):
    """Top-left -> bottom-right gradient square."""
    g = Image.new("RGB", (size, size))
    px = g.load()
    for y in range(size):
        for x in range(size):
            t = (x + y) / (2 * (size - 1))
            px[x, y] = lerp(c1, c2, t)
    return g


def circle_mask(size, cx, cy, r):
    m = Image.new("L", (size, size), 0)
    ImageDraw.Draw(m).ellipse((cx - r, cy - r, cx + r, cy + r), fill=255)
    return m


def paste_bead(canvas, cx, cy, r, fill_img_or_color, shadow=True):
    """Draw a bead (filled circle) with a soft drop shadow and a small specular highlight."""
    size = canvas.size[0]
    if shadow:
        sh = Image.new("L", (size, size), 0)
        ImageDraw.Draw(sh).ellipse((cx - r, cy - r + s(6), cx + r, cy + r + s(6)), fill=150)
        sh = sh.filter(ImageFilter.GaussianBlur(s(10)))
        canvas.paste((0, 0, 0), (0, 0), sh)
    m = circle_mask(size, cx, cy, r)
    if isinstance(fill_img_or_color, Image.Image):
        layer = Image.new("RGB", (size, size))
        bx, by = int(cx - r), int(cy - r)
        layer.paste(fill_img_or_color.resize((int(2 * r), int(2 * r))), (bx, by))
        canvas.paste(layer, (0, 0), m)
    else:
        canvas.paste(fill_img_or_color, (0, 0), m)
    # subtle top highlight
    hl = Image.new("L", (size, size), 0)
    ImageDraw.Draw(hl).ellipse((cx - r * 0.55, cy - r * 0.78, cx + r * 0.25, cy - r * 0.18), fill=45)
    hl = hl.filter(ImageFilter.GaussianBlur(r * 0.18))
    hl = ImageChops.multiply(hl, m)
    canvas.paste((255, 255, 255), (0, 0), hl)


def build():
    canvas = Image.new("RGB", (N, N), (0, 0, 0))

    # --- background tile -------------------------------------------------------------
    bg = vertical_gradient(N, hex_rgb("#111A30"), hex_rgb("#04060D"))
    tile_mask = squircle_mask(N, inset=s(100))
    canvas.paste(bg, (0, 0), tile_mask)

    # faint radial glow behind the face
    glow = Image.new("L", (N, N), 0)
    gr = s(360)
    ImageDraw.Draw(glow).ellipse((N / 2 - gr, N / 2 - gr, N / 2 + gr, N / 2 + gr), fill=60)
    glow = glow.filter(ImageFilter.GaussianBlur(s(90)))
    glow = ImageChops.multiply(glow, tile_mask)
    canvas.paste(hex_rgb("#2E3C78"), (0, 0), glow)

    d = ImageDraw.Draw(canvas)
    cx = cy = N / 2

    # --- orbit ring ----------------------------------------------------------------------
    orbit_r = s(318)
    ow = s(9)
    d.ellipse((cx - orbit_r, cy - orbit_r, cx + orbit_r, cy + orbit_r),
              outline=hex_rgb("#2C375C"), width=ow)

    # --- watch face ----------------------------------------------------------------------
    face_r = s(232)
    sh = Image.new("L", (N, N), 0)
    ImageDraw.Draw(sh).ellipse((cx - face_r, cy - face_r + s(10), cx + face_r, cy + face_r + s(10)), fill=200)
    sh = sh.filter(ImageFilter.GaussianBlur(s(18)))
    canvas.paste((0, 0, 0), (0, 0), sh)
    d.ellipse((cx - face_r, cy - face_r, cx + face_r, cy + face_r), fill=hex_rgb("#030407"))
    # bezel edge
    d.ellipse((cx - face_r, cy - face_r, cx + face_r, cy + face_r), outline=hex_rgb("#1C2234"), width=s(5))
    # thin dark track ring inside the face
    track_r = s(196)
    d.ellipse((cx - track_r, cy - track_r, cx + track_r, cy + track_r),
              outline=hex_rgb("#1A2033"), width=s(7))

    # --- ">_" glyph ----------------------------------------------------------------------
    off_white = hex_rgb("#F3EFE7")
    lw = s(34)
    # chevron
    gx, gy = cx - s(78), cy - s(6)
    p1 = (gx, gy - s(62))
    p2 = (gx + s(66), gy)
    p3 = (gx, gy + s(62))
    d.line([p1, p2, p3], fill=off_white, width=lw, joint="curve")
    for p in (p1, p3):
        d.ellipse((p[0] - lw / 2, p[1] - lw / 2, p[0] + lw / 2, p[1] + lw / 2), fill=off_white)
    # underscore
    ux1, ux2, uy = cx + s(14), cx + s(96), gy + s(56)
    d.line([(ux1, uy), (ux2, uy)], fill=off_white, width=lw)
    for x in (ux1, ux2):
        d.ellipse((x - lw / 2, uy - lw / 2, x + lw / 2, uy + lw / 2), fill=off_white)

    # --- beads on the orbit --------------------------------------------------------------
    bead_r = s(50)

    def on_orbit(deg):
        a = math.radians(deg)
        return cx + orbit_r * math.cos(a), cy - orbit_r * math.sin(a)

    # Claude orange: upper right
    bx, by = on_orbit(40)
    paste_bead(canvas, bx, by, bead_r, hex_rgb("#DA7756"))

    # Codex lavender -> blue: lower right
    bx, by = on_orbit(-75)
    codex = diagonal_gradient(256, hex_rgb("#BFB5FE"), hex_rgb("#4347FF"))
    paste_bead(canvas, bx, by, bead_r, codex)

    # Antigravity blue with warm cap: left
    bx, by = on_orbit(165)
    paste_bead(canvas, bx, by, bead_r, hex_rgb("#3186FF"))
    # warm cap: a gradient crescent across the top of the bead
    cap_grad = Image.new("RGB", (N, N))
    cg = Image.new("RGB", (256, 1))
    for x in range(256):
        cg.putpixel((x, 0), lerp(hex_rgb("#F15D39"), hex_rgb("#E2A82A"), x / 255))
    cg = cg.resize((int(2 * bead_r), int(2 * bead_r)))
    cap_grad.paste(cg, (int(bx - bead_r), int(by - bead_r)))
    bead_m = circle_mask(N, bx, by, bead_r)
    cut = circle_mask(N, bx, by + bead_r * 0.58, bead_r * 1.08)
    cap_m = ImageChops.subtract(bead_m, cut)
    canvas.paste(cap_grad, (0, 0), cap_m)

    # --- subtle inner edge highlight on the tile -------------------------------------------
    edge = ImageChops.subtract(tile_mask, squircle_mask(N, inset=s(103)))
    edge = ImageChops.multiply(edge, vertical_gradient(N, (90, 90, 90), (0, 0, 0)).convert("L"))
    canvas.paste((255, 255, 255), (0, 0), edge)

    # --- final: apply tile alpha, downsample ---------------------------------------------
    rgba = canvas.convert("RGBA")
    rgba.putalpha(tile_mask)
    # soft drop shadow under the tile (macOS icons carry their own shadow)
    shadow = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    sm = tile_mask.filter(ImageFilter.GaussianBlur(s(14)))
    sm = sm.point(lambda v: int(v * 0.45))
    shadow.paste((0, 0, 0, 255), (0, s(10)), sm)
    out = Image.alpha_composite(shadow, rgba)
    return out.resize((1024, 1024), Image.LANCZOS)


def main():
    os.makedirs(RES, exist_ok=True)
    master = os.path.join(RES, "AppIcon-1024.png")
    build().save(master)
    print("wrote", master)

    iconset = os.path.join(RES, "AppIcon.iconset")
    shutil.rmtree(iconset, ignore_errors=True)
    os.makedirs(iconset)
    for base in (16, 32, 128, 256, 512):
        for scale in (1, 2):
            px = base * scale
            name = f"icon_{base}x{base}{'@2x' if scale == 2 else ''}.png"
            subprocess.run(["sips", "-z", str(px), str(px), master, "--out", os.path.join(iconset, name)],
                           check=True, stdout=subprocess.DEVNULL)
    icns = os.path.join(RES, "AppIcon.icns")
    subprocess.run(["iconutil", "-c", "icns", iconset, "-o", icns], check=True)
    print("wrote", icns)


if __name__ == "__main__":
    sys.exit(main())
