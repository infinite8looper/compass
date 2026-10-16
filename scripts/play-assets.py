#!/usr/bin/env python3
"""Makes the store listing's pictures in docs/play/ from the screenshot matrix's pictures and the launcher icon.

Usage: python3 -I scripts/play-assets.py <pictures> <font>
  <pictures>  the folder the large round's pictures were written to (MATRIX_OUT of
              `scripts/screenshot-matrix.sh large play trail calibration`): play-compass-large.png,
              play-waypoints-large.png, trail-retracing-large.png, calibrate-large.png, play-settings-ticks-large.png,
              play-settings-colors-large.png
  <font>      Roboto-Regular.ttf, the variable font the emulator's image has (adb pull /system/fonts/Roboto-Regular.ttf)

Writes:
  docs/play/screenshot-N-<what>.png  each picture as it is (454 x 454), made opaque: every pixel laid on black, so
                                     the file is 24-bit RGB with no alpha
  docs/play/icon-512.png             512 x 512, 32-bit RGBA: the launcher icon's layers as the round mask shows
                                     them (the 72 x 72 middle of the 108 x 108 layers): the disc in the background
                                     color (res/values/colors.xml) and the mark (res/drawable/ic_launcher_foreground.xml),
                                     transparent outside the disc
  docs/play/feature-graphic.png      1024 x 500, 24-bit RGB: black, the icon, the store name and the compass picture
Needs Pillow. Talks to no device.
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
RES = os.path.join(ROOT, "app", "src", "main", "res")
OUT = os.path.join(ROOT, "docs", "play")
NAME = "Compass: Waypoints & Trail"
SHOTS = [
    ("play-compass-large.png", "screenshot-1-compass.png"),
    ("play-waypoints-large.png", "screenshot-2-waypoints.png"),
    ("trail-retracing-large.png", "screenshot-3-trail.png"),
    ("calibrate-large.png", "screenshot-4-calibration.png"),
    ("play-settings-ticks-large.png", "screenshot-5-settings.png"),
    ("play-settings-colors-large.png", "screenshot-6-settings-colors.png"),
]
A = "{http://schemas.android.com/apk/res/android}"


def opaque(path):
    """The picture laid on black: no pixel is left partly or wholly transparent."""
    im = Image.open(path).convert("RGBA")
    flat = Image.new("RGB", im.size, (0, 0, 0))
    flat.paste(im, mask=im.getchannel("A"))
    return flat


def hex_color(s):
    s = s.lstrip("#")
    if len(s) == 8:
        s = s[2:]  # #AARRGGBB: the mark's colors are opaque
    return tuple(int(s[i:i + 2], 16) for i in (0, 2, 4))


def icon(size):
    """The launcher icon at [size] px, drawn 4 times larger and scaled down."""
    big = size * 4
    background = ET.parse(os.path.join(RES, "values", "colors.xml")).getroot()
    disc = hex_color(next(c.text for c in background if c.get("name") == "ic_launcher_background"))
    paths = ET.parse(os.path.join(RES, "drawable", "ic_launcher_foreground.xml")).getroot()
    # The round mask shows the middle 72 x 72 of the 108 x 108 viewport.
    scale = big / 72.0
    def at(x, y):
        return ((x - 18) * scale, (y - 18) * scale)
    im = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.ellipse((0, 0, big - 1, big - 1), fill=disc + (255,))
    for p in paths:
        data = p.get(A + "pathData")
        ring = re.match(r"M([\d.]+),([\d.]+)m-([\d.]+),0a", data)
        if ring:  # the circle: a stroke only
            cx, cy, r = map(float, ring.groups())
            w = float(p.get(A + "strokeWidth")) * scale
            x0, y0 = at(cx - r, cy - r)
            x1, y1 = at(cx + r, cy + r)
            d.ellipse((x0 - w / 2, y0 - w / 2, x1 + w / 2, y1 + w / 2), outline=hex_color(p.get(A + "strokeColor")) + (255,), width=round(w))
        else:  # a triangle: M x,y L x,y L x,y Z
            pts = [at(*map(float, xy)) for xy in re.findall(r"([\d.]+),([\d.]+)", data)]
            d.polygon(pts, fill=hex_color(p.get(A + "fillColor")) + (255,))
    return im.resize((size, size), Image.LANCZOS)


def feature_graphic(compass, font_path):
    w, h = 1024, 500
    g = Image.new("RGB", (w, h), (0, 0, 0))
    # The compass picture at the right, its own black running into the background.
    shot = compass.resize((440, 440), Image.LANCZOS)
    g.paste(shot, (w - 440 - 40, (h - 440) // 2))
    # The icon and the name at the left.
    ic = icon(150)
    g.paste(ic, (64, 70), mask=ic.getchannel("A"))
    font = ImageFont.truetype(font_path, 58)
    try:
        font.set_variation_by_axes([500])  # Roboto's weight axis: medium, the app's weight for a label that is on
    except Exception:
        pass
    d = ImageDraw.Draw(g)
    first, second = NAME.split(": ")
    d.text((64, 262), first + ":", font=font, fill=(255, 255, 255))
    d.text((64, 336), second, font=font, fill=(255, 255, 255))
    return g


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    pictures, font_path = sys.argv[1], sys.argv[2]
    os.makedirs(OUT, exist_ok=True)
    for source, name in SHOTS:
        opaque(os.path.join(pictures, source)).save(os.path.join(OUT, name), optimize=True)
        print(name)
    icon(512).save(os.path.join(OUT, "icon-512.png"), optimize=True)
    print("icon-512.png")
    feature_graphic(opaque(os.path.join(pictures, SHOTS[0][0])), font_path).save(os.path.join(OUT, "feature-graphic.png"), optimize=True)
    print("feature-graphic.png")


if __name__ == "__main__":
    main()
