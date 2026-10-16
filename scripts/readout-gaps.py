#!/usr/bin/env python3
"""Usage: python3 -I scripts/readout-gaps.py <picture.png> [...]

Measures the compass's readout in a screenshot: the lines of ink inside the circle the cardinal letters sweep
(0.47 of the dial radius, the dial filling the screen), the rows of pixels with no ink between them, and how close
the ink comes to that circle. A pixel is ink when one of its channels is more than THRESHOLD above the
background's (the commonest color inside the circle); anti-aliased edges count, so a gap is the clear air between
two lines. Prints one line per picture: each line of ink as top-bottom in pixels from the top of the screen, the
gaps between them (rows of pixels with no ink, whatever the sideways place), the smallest gap, the shortest
distance between the ink of two neighboring lines (sideways place counted: a descender beside a tick, not over it,
is further from it than the gap says), and the least distance from any ink to the circle. A picture of several
frames side by side is measured frame by frame (each frame as wide as the picture is tall).
Limits: only ink inside the circle is seen, so ink wholly outside it is not measured, and ink that crosses it shows
as a distance to the circle near 0. The lines are not named; which line is which is read from the picture. A gap is
whole rows of pixels, which can be one fewer than the continuous air between the same two lines that the letter
heights in nav/ReadoutRows.kt give (rowGap): 2.5 px of air can leave one clear row (nav/ReadoutRows.kt clearRows).
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
    ink = {}
    margin = None
    for x, y in inside:
        p = px[x, y]
        if max(p[k] - bg[k] for k in range(3)) > THRESHOLD:
            ink_rows.setdefault(y, 0)
            ink_rows[y] += 1
            ink.setdefault(y, []).append(x)
            d = rr - ((x + 0.5 - cx) ** 2 + (y + 0.5 - cy) ** 2) ** 0.5
            margin = d if margin is None else min(margin, d)
    runs = []
    for y in sorted(ink_rows):
        if runs and y == runs[-1][1] + 1:
            runs[-1][1] = y
        else:
            runs.append([y, y])
    gaps = [b[0] - a[1] - 1 for a, b in zip(runs, runs[1:])]
    near = []      # the shortest distance between the ink of two neighboring lines, from pixel edge to pixel edge
    for a, b in zip(runs, runs[1:]):
        low = [(x, y) for y in range(max(a[0], a[1] - 8), a[1] + 1) for x in ink.get(y, [])]
        high = [(x, y) for y in range(b[0], min(b[1], b[0] + 8) + 1) for x in ink.get(y, [])]
        d = min((max(abs(p[0] - q[0]) - 1, 0) ** 2 + (q[1] - p[1] - 1) ** 2) ** 0.5 for p in low for q in high)
        near.append(round(d, 1))
    return runs, gaps, near, margin


def main():
    if len(sys.argv) < 2:
        print(__doc__.strip().splitlines()[0], file=sys.stderr)
        sys.exit(1)
    for path in sys.argv[1:]:
        im = Image.open(path).convert("RGB")
        w, h = im.size
        frames = [im] if w <= h else [im.crop((i * h, 0, (i + 1) * h, h)) for i in range(w // h)]
        for i, f in enumerate(frames):
            runs, gaps, near, margin = measure(f)
            name = path if len(frames) == 1 else f"{path}[{i + 1}]"
            lines = " ".join(f"{a}-{b}" for a, b in runs)
            least = min(gaps) if gaps else None
            print(f"{name}: lines {lines} | gaps {gaps} | smallest gap {least} px | nearest ink {near} | ink to circle {margin:.1f} px" if margin is not None
                  else f"{name}: no ink inside the readout circle")


if __name__ == "__main__":
    main()
