"""Draws the Fire TV banner (320x180) and the launcher icon for SalaTV.

Run from the repo root: python tools/make_art.py
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app" / "src" / "main" / "res"
FONT = ROOT / "app" / "src" / "main" / "assets" / "outfit.ttf"
LAMP = (255, 183, 102)
INK = (11, 13, 18)


def font(size, weight):
    f = ImageFont.truetype(str(FONT), size)
    try:
        f.set_variation_by_axes([weight])
    except Exception:
        pass
    return f


def glow_bg(w, h, cx, cy, radius):
    img = Image.new("RGB", (w, h), INK)
    glow = Image.new("RGB", (w, h), INK)
    d = ImageDraw.Draw(glow)
    d.ellipse((cx - radius, cy - radius, cx + radius, cy + radius), fill=(120, 72, 30))
    glow = glow.filter(ImageFilter.GaussianBlur(radius * 0.6))
    return Image.blend(img, glow, 0.85)


def tv_glyph(d, x, y, w, stroke):
    h = w * 0.62
    d.rounded_rectangle((x, y, x + w, y + h), radius=w * 0.1, outline=LAMP, width=stroke)
    d.line((x + w * 0.3, y + h + w * 0.16, x + w * 0.7, y + h + w * 0.16), fill=LAMP, width=stroke)
    d.line((x + w * 0.5, y + h, x + w * 0.5, y + h + w * 0.16), fill=LAMP, width=stroke)


def banner():
    s = 4
    w, h = 320 * s, 180 * s
    img = glow_bg(w, h, int(w * 0.22), int(h * 0.5), int(h * 0.55))
    d = ImageDraw.Draw(img)
    tv_glyph(d, w * 0.08, h * 0.34, w * 0.17, 7 * s // 2)
    f = font(int(50 * s), 700)
    d.text((w * 0.31, h * 0.5), "SalaTV", font=f, fill=(236, 238, 243), anchor="lm")
    img.resize((320, 180), Image.LANCZOS).save(RES / "drawable-xhdpi" / "banner.png", optimize=True)


def icon():
    s = 4
    n = 96 * s
    img = glow_bg(n, n, n // 2, n // 2, int(n * 0.42))
    mask = Image.new("L", (n, n), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, n, n), radius=n * 0.22, fill=255)
    d = ImageDraw.Draw(img)
    tv_glyph(d, n * 0.2, n * 0.24, n * 0.6, 9 * s // 2)
    out = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    out.resize((96, 96), Image.LANCZOS).save(RES / "mipmap-xhdpi" / "ic_launcher.png", optimize=True)


if __name__ == "__main__":
    banner()
    icon()
    print("banner.png e ic_launcher.png listos")
