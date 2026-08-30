#!/usr/bin/env python3
"""Generates pathfinder's launcher icon from exact serpentine geometry.

The logo is a dashed S-trail on a 3x3 lattice: the path starts at the circle
(bottom-left lattice point), runs right along the bottom row, turns up through
a semicircle, runs left along the middle row, turns up through a second
semicircle, and runs right along the top row to the X (top-right lattice
point).

Everything is derived in closed form from the parameters below:

- straights have length 2*GRID, turns are semicircles of radius GRID/2, so
  the total path length is exact: GRID * (6 + pi);
- dashes are placed by true arc length: uniform dash length and an exact,
  equal visible gap between every pair of pill bodies, flush against the
  circle and X clearances at either end (VectorDrawable has no
  stroke-dasharray, so each dash is baked as an individual subpath; round
  line caps give the pills, extending STROKE/2 past each dash endpoint, so
  centerline gap = GAP + STROKE);
- the circle is centered on the path start point and the X is centered on the
  path end point, with sizes derived from the stroke width.

Outputs (rewritten in place; do not hand-edit):
- app/src/main/res/drawable/ic_launcher_foreground.xml  (orange on the white
  adaptive-icon background from values/ic_launcher_colors.xml)
- app/src/main/res/drawable/ic_launcher_monochrome.xml  (themed icons)
- build/logo-preview.svg                                (visual check only)

Usage: python3 tools/generate-logo.py
"""

import math
from pathlib import Path

# ---------------------------------------------------------------- parameters

VIEWPORT = 108.0        # adaptive-icon viewport is 108x108
CENTER = VIEWPORT / 2   # lattice center
GRID = 24.0             # lattice spacing (row spacing = turn diameter)

STROKE = 7.0            # serpentine dash stroke width
GAP = 6.0               # exact visible gap between pill bodies (edge to edge)
N_DASHES = 7            # dash count; dash length is derived

# Dashes use STROKE; the circle ring and X arms stay bolder (X_STROKE).
CIRCLE_R = 8.0          # ring centerline radius
X_HALF = 8.0            # half the X arm length
X_STROKE = 10.0

COLOR = "#000000"       # GrapheneOS style: black glyph on white circle
MONO_COLOR = "#000000"  # themed-icon paint

# Uniform shrink about the viewport center: GrapheneOS glyphs occupy roughly
# 60-65% of the launcher disc, so the whole logo (lattice, strokes, circle,
# X) is scaled down from its design size.
SCALE = 0.6
GRID *= SCALE
STROKE *= SCALE
GAP *= SCALE
CIRCLE_R *= SCALE
X_HALF *= SCALE
X_STROKE *= SCALE
CIRCLE_STROKE = X_STROKE

# ------------------------------------------------------------------- lattice

X0, X2 = CENTER - GRID, CENTER + GRID        # left/right lattice columns
Y0, Y1, Y2 = CENTER - GRID, CENTER, CENTER + GRID  # top/mid/bottom rows
TURN_R = GRID / 2

# Path segments in travel direction: circle -> X. Arcs are parameterized by
# center, radius, start angle, and signed sweep in screen coordinates
# (y down, angles increase clockwise; `sweep` is the SVG sweep flag).
SEGMENTS = [
    ("line", (X0, Y2), (X2, Y2)),
    ("arc", (X2, (Y1 + Y2) / 2), TURN_R, math.pi / 2, -math.pi, 0),
    ("line", (X2, Y1), (X0, Y1)),
    ("arc", (X0, (Y0 + Y1) / 2), TURN_R, math.pi / 2, math.pi, 1),
    ("line", (X0, Y0), (X2, Y0)),
]


def seg_length(seg):
    if seg[0] == "line":
        (x1, y1), (x2, y2) = seg[1], seg[2]
        return math.hypot(x2 - x1, y2 - y1)
    _, _, r, _, sweep_len, _ = seg
    return r * abs(sweep_len)


LENGTHS = [seg_length(s) for s in SEGMENTS]
TOTAL = sum(LENGTHS)
assert abs(TOTAL - GRID * (6 + math.pi)) < 1e-9


def point_at(s):
    """Point on the serpentine at arc length s from the circle."""
    for seg, length in zip(SEGMENTS, LENGTHS):
        if s <= length or seg is SEGMENTS[-1]:
            t = min(max(s, 0.0), length)
            if seg[0] == "line":
                (x1, y1), (x2, y2) = seg[1], seg[2]
                k = t / length
                return x1 + (x2 - x1) * k, y1 + (y2 - y1) * k
            _, (cx, cy), r, a0, sweep_len, _ = seg
            a = a0 + math.copysign(t / r, sweep_len)
            return cx + r * math.cos(a), cy + r * math.sin(a)
        s -= length
    raise AssertionError("unreachable")


def subpath_data(s1, s2):
    """SVG/VectorDrawable path data for the serpentine subrange [s1, s2]."""
    parts = []
    x, y = point_at(s1)
    parts.append(f"M{fmt(x)},{fmt(y)}")
    pos = 0.0
    for seg, length in zip(SEGMENTS, LENGTHS):
        lo, hi = pos, pos + length
        pos = hi
        if hi <= s1 or lo >= s2:
            continue
        x, y = point_at(min(hi, s2))
        if seg[0] == "line":
            parts.append(f"L{fmt(x)},{fmt(y)}")
        else:
            _, _, r, _, _, sweep = seg
            # Any dash piece is far shorter than half a turn, so large-arc=0.
            parts.append(f"A{fmt(r)},{fmt(r)} 0 0 {sweep} {fmt(x)},{fmt(y)}")
    return "".join(parts)


def fmt(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


# ------------------------------------------------------------------- layout

# Dashes span [s_start, s_end]: flush clearances around the circle ring and
# the X, each with one visible GAP of breathing room. Round caps extend
# STROKE/2 past each dash endpoint, so centerline offsets add STROKE/2 and
# the centerline gap between dash subranges is GAP + STROKE.
S_START = CIRCLE_R + CIRCLE_STROKE / 2 + GAP + STROKE / 2
S_END = TOTAL - (X_HALF + X_STROKE / 2 + GAP + STROKE / 2)
GAP_C = GAP + STROKE  # centerline gap between dash subranges
DASH = ((S_END - S_START) - (N_DASHES - 1) * GAP_C) / N_DASHES
assert DASH > 0, "parameters leave no room for dashes"

dashes_data = "".join(
    subpath_data(S_START + i * (DASH + GAP_C), S_START + i * (DASH + GAP_C) + DASH)
    for i in range(N_DASHES)
)

# Ring as two semicircular arcs; centered exactly on the path start point.
circle_data = (
    f"M{fmt(X0)},{fmt(Y2 - CIRCLE_R)}"
    f"A{fmt(CIRCLE_R)},{fmt(CIRCLE_R)} 0 1 1 {fmt(X0)},{fmt(Y2 + CIRCLE_R)}"
    f"A{fmt(CIRCLE_R)},{fmt(CIRCLE_R)} 0 1 1 {fmt(X0)},{fmt(Y2 - CIRCLE_R)}Z"
)

# X centered exactly on the path end point.
cx, cy = X2, Y0
h = X_HALF
x_data = (
    f"M{fmt(cx - h)},{fmt(cy - h)}L{fmt(cx + h)},{fmt(cy + h)}"
    f"M{fmt(cx + h)},{fmt(cy - h)}L{fmt(cx - h)},{fmt(cy + h)}"
)

# ------------------------------------------------------------------- output

VECTOR_TEMPLATE = """<?xml version="1.0" encoding="utf-8"?>
<!-- GENERATED by tools/generate-logo.py — do not edit by hand.
     Serpentine on a 3x3 lattice (GRID={grid}): dashes are uniform
     ({dash} long, {gap} gaps, by arc length); circle and X are centered
     exactly on the path endpoints. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">

    <!-- Dashed serpentine trail -->
    <path
        android:fillColor="@android:color/transparent"
        android:pathData="{dashes}"
        android:strokeColor="{color}"
        android:strokeLineCap="round"
        android:strokeWidth="{stroke}" />

    <!-- Origin circle -->
    <path
        android:fillColor="@android:color/transparent"
        android:pathData="{circle}"
        android:strokeColor="{color}"
        android:strokeWidth="{circle_stroke}" />

    <!-- X marks the spot -->
    <path
        android:fillColor="@android:color/transparent"
        android:pathData="{x}"
        android:strokeColor="{color}"
        android:strokeLineCap="round"
        android:strokeWidth="{x_stroke}" />
</vector>
"""


def render_vector(color):
    return VECTOR_TEMPLATE.format(
        grid=fmt(GRID), dash=fmt(DASH), gap=fmt(GAP),
        dashes=dashes_data, circle=circle_data, x=x_data,
        color=color, stroke=fmt(STROKE),
        circle_stroke=fmt(CIRCLE_STROKE), x_stroke=fmt(X_STROKE),
    )


def render_svg():
        return f"""<svg xmlns="http://www.w3.org/2000/svg" width="432" height="432" viewBox="0 0 108 108">
  <rect width="108" height="108" fill="#FFFFFF"/>
  <path d="{dashes_data}" fill="none" stroke="{COLOR}" stroke-width="{STROKE}" stroke-linecap="round"/>
  <path d="{circle_data}" fill="none" stroke="{COLOR}" stroke-width="{CIRCLE_STROKE}"/>
  <path d="{x_data}" fill="none" stroke="{COLOR}" stroke-width="{X_STROKE}" stroke-linecap="round"/>
</svg>
"""


def main():
    root = Path(__file__).resolve().parent.parent
    res = root / "app/src/main/res"
    (res / "drawable/ic_launcher_foreground.xml").write_text(render_vector(COLOR))
    (res / "drawable/ic_launcher_monochrome.xml").write_text(render_vector(MONO_COLOR))
    build = root / "build"
    build.mkdir(exist_ok=True)
    (build / "logo-preview.svg").write_text(render_svg())
    print(f"path length {TOTAL:.3f}; {N_DASHES} dashes of {DASH:.3f} with {GAP:.3f} gaps")
    print("wrote ic_launcher_foreground.xml, ic_launcher_monochrome.xml, build/logo-preview.svg")


if __name__ == "__main__":
    main()
