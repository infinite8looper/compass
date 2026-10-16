#!/usr/bin/env python3
"""Usage: python3 -I scripts/readout-gaps.py <picture.png> [...]

Measures the compass's readout in a screenshot: the lines of ink inside the circle the cardinal letters sweep
(0.47 of the dial radius, the dial filling the screen), the rows of pixels with no ink between them, and how close
the ink comes to that circle. A pixel is ink when one of its channels is more than THRESHOLD above the
background's (the commonest color inside the circle); anti-aliased edges count, so a gap is the clear air between
two lines. Prints one line per picture: each line of ink as top-bottom in pixels from the top of the screen, the
gaps between them, the smallest gap, and the least distance from any ink to the circle. A picture of several
frames side by side is measured frame by frame (each frame as wide as the picture is tall).
Needs Pillow.
"""
import sys
from collections import Counter

from PIL import Image

READOUT_RADIUS = 0.47
THRESHOLD = 24


def measure(im):
    w, h = im.size
    cx, cy, r = w / 2, h / 2, min(w, h) / 2
    rr = READOUT_RADIUS * r
    px = im.load()
    inside = []
    for y in range(int(cy - rr), int(cy + rr) + 1):
        dy = y + 0.5 - cy
        if dy * dy > rr * rr:
            continue
        half = (rr * rr - dy * dy) ** 0.5
        for x in range(int(cx - half), int(cx + half) + 1):
            dx = x + 0.5 - cx
            if dx * dx + dy * dy <= rr * rr:
                inside.append((x, y))
    bg = Counter(px[x, y] for x, y in inside).most_common(1)[0][0]
    ink_rows = {}
    margin = None
    for x, y in inside:
        p = px[x, y]
        if max(p[k] - bg[k] for k in range(3)) > THRESHOLD:
            ink_rows.setdefault(y, 0)
            ink_rows[y] += 1
            d = rr - ((x + 0.5 - cx) ** 2 + (y + 0.5 - cy) ** 2) ** 0.5
            margin = d if margin is None else min(margin, d)
    runs = []
    for y in sorted(ink_rows):
        if runs and y == runs[-1][1] + 1:
            runs[-1][1] = y
        else:
            runs.append([y, y])
    gaps = [b[0] - a[1] - 1 for a, b in zip(runs, runs[1:])]
    return runs, gaps, margin


def main():
    if len(sys.argv) < 2:
        print(__doc__.strip().splitlines()[0], file=sys.stderr)
        sys.exit(1)
    for path in sys.argv[1:]:
        im = Image.open(path).convert("RGB")
        w, h = im.size
        frames = [im] if w <= h else [im.crop((i * h, 0, (i + 1) * h, h)) for i in range(w // h)]
        for i, f in enumerate(frames):
            runs, gaps, margin = measure(f)
            name = path if len(frames) == 1 else f"{path}[{i + 1}]"
            lines = " ".join(f"{a}-{b}" for a, b in runs)
            least = min(gaps) if gaps else None
            print(f"{name}: lines {lines} | gaps {gaps} | smallest gap {least} px | ink to circle {margin:.1f} px" if margin is not None
                  else f"{name}: no ink inside the readout circle")


if __name__ == "__main__":
    main()
