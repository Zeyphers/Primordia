"""
Paints design/gui/splicer_art.png from design/gui/splicer_layout.png.

The layout file says *where* things are; this says what they look like. Generating the art from the
layout rather than drawing it by hand is what keeps the two in step: move a row in the layout, re-run
this, and the well under it moves with it. Nothing here is precious — the output is an ordinary PNG
and is meant to be painted over.

Usage:
    python design/gui/splicer_art.py

Then copy design/gui/splicer_art.png over
src/main/resources/assets/primordia/textures/gui/splicer.png, or hand it back and that happens at
build time.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from splicer_layout import MARKERS  # noqa: E402
from read_layout import decode  # noqa: E402

try:
    from PIL import Image, ImageDraw
except ImportError:
    raise SystemExit("this one needs Pillow: pip install pillow")

HERE = Path(__file__).parent
# The texture is a power of two; the panel occupies the top-left of it.
SHEET = 256

PANEL = (43, 38, 34, 255)
BEVEL_HI = (74, 66, 58, 255)
BEVEL_LO = (24, 21, 19, 255)
WELL = (30, 26, 23, 255)
WELL_HI = (58, 52, 46, 255)
GROOVE = (34, 30, 27, 255)
GROOVE_EDGE = (52, 46, 41, 255)


def markers():
    width, height, pixels = decode(HERE / "splicer_layout.png")
    found = {}
    for y in range(height):
        for x in range(width):
            rgba = pixels[y][x]
            for name, colour in MARKERS.items():
                if rgba[:3] == colour and rgba[3] > 0:
                    box = found.setdefault(name, [x, y, x, y])
                    box[0] = min(box[0], x)
                    box[1] = min(box[1], y)
                    box[2] = max(box[2], x)
                    box[3] = max(box[3], y)
    return width, height, {k: (v[0], v[1], v[2] - v[0] + 1, v[3] - v[1] + 1)
                           for k, v in found.items()}


def recess(d, x, y, w, h, fill=WELL):
    """A sunken panel: dark inside, a light lip along the bottom and right."""
    d.rectangle([x, y, x + w - 1, y + h - 1], fill=fill)
    d.line([(x, y), (x + w - 1, y)], fill=BEVEL_LO)
    d.line([(x, y), (x, y + h - 1)], fill=BEVEL_LO)
    d.line([(x, y + h - 1), (x + w - 1, y + h - 1)], fill=WELL_HI)
    d.line([(x + w - 1, y), (x + w - 1, y + h - 1)], fill=WELL_HI)


def slot(d, x, y):
    recess(d, x - 1, y - 1, 18, 18)


def main():
    width, height, m = markers()
    img = Image.new("RGBA", (SHEET, SHEET), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    # The panel itself.
    d.rectangle([0, 0, width - 1, height - 1], fill=PANEL)
    d.line([(0, 0), (width - 1, 0)], fill=BEVEL_HI)
    d.line([(0, 0), (0, height - 1)], fill=BEVEL_HI)
    d.line([(0, height - 1), (width - 1, height - 1)], fill=BEVEL_LO)
    d.line([(width - 1, 0), (width - 1, height - 1)], fill=BEVEL_LO)

    # The rail groove: a channel from the rows' right edge, along a spine, to the output slot.
    rail = m["RAIL"]
    out = m["OUTPUT"]
    rows = [m["ROW%d" % (i + 1)] for i in range(6)]
    spine_x = rail[0] + rail[2] // 2
    out_cy = out[1] + out[3] // 2

    for row in rows:
        cy = row[1] + row[3] // 2
        d.rectangle([row[0] + row[2], cy - 1, spine_x + 1, cy + 1], fill=GROOVE)
        d.line([(row[0] + row[2], cy - 2), (spine_x + 1, cy - 2)], fill=GROOVE_EDGE)
        d.line([(row[0] + row[2], cy + 2), (spine_x + 1, cy + 2)], fill=GROOVE_EDGE)

    top = min(r[1] + r[3] // 2 for r in rows)
    bottom = max(r[1] + r[3] // 2 for r in rows)
    d.rectangle([spine_x - 1, min(top, out_cy) - 1, spine_x + 1, max(bottom, out_cy) + 1], fill=GROOVE)
    d.rectangle([spine_x, out_cy - 1, out[0] - 1, out_cy + 1], fill=GROOVE)

    # Wells: the six rows, the output, and every inventory slot.
    for row in rows:
        recess(d, row[0], row[1], row[2], row[3])
    slot(d, out[0], out[1])

    inv = m["INV"]
    for r in range(3):
        for c in range(9):
            slot(d, inv[0] + c * 18, inv[1] + r * 18)
    hot = m["HOTBAR"]
    for c in range(9):
        slot(d, hot[0] + c * 18, hot[1])

    img.save(HERE / "splicer_art.png")
    print("wrote", HERE / "splicer_art.png", "panel %dx%d on a %dx%d sheet" % (width, height, SHEET, SHEET))


if __name__ == "__main__":
    main()
