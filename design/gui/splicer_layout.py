"""
Reads design/gui/splicer_layout.png and reports where every element of the Splicing Bench's screen
has been placed.

Same idea as read_layout.py next door, and for the same reason: the screen's geometry otherwise
appears twice over — listed in SplicerMenu for the slots, read again by SplicerScreen for the rows,
the rail and the wells — and moving something means changing both in step. Getting that wrong gives
a screen where the click target sits next to the row it highlights, which looks like a rendering bug
and is not one.

Usage:
    python design/gui/splicer_layout.py

Edit design/gui/splicer_layout.png to say where things go: move each coloured block so it covers the
row, slot or rail it names. Nothing but the markers may use these exact colours, and each marker must
stay a solid rectangle. The canvas size is the screen size, so resizing the image resizes the GUI.

What each marker means:

    ROW1..ROW6  the six trait rows, top to bottom. Click targets; text is drawn inside them.
    RAIL        the track the progress line runs down, from the rows' right edge to the output.
    OUTPUT      the 16x16 slot the finished serum appears in.
    INV         the player's 3x9 inventory block. Must be 162x54, or the slots will not line up.
    HOTBAR      the player's hotbar. Must be 162x18.
    TITLE       an 8x8 nub at the top-left of where the screen title is drawn.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from read_layout import decode  # noqa: E402  - the dependency-free PNG reader next door

MARKERS = {
    "ROW1":   (255, 0, 255),
    "ROW2":   (255, 0, 128),
    "ROW3":   (255, 0, 0),
    "ROW4":   (255, 128, 0),
    "ROW5":   (255, 255, 0),
    "ROW6":   (128, 255, 0),
    "OUTPUT": (0, 0, 255),
    "RAIL":   (0, 255, 255),
    "INV":    (0, 255, 128),
    "HOTBAR": (0, 128, 255),
    "TITLE":  (128, 0, 255),
}

# What the mod expects a marker to measure, where it is not free to be any size.
REQUIRED = {
    "OUTPUT": (16, 16),
    "INV": (162, 54),
    "HOTBAR": (162, 18),
    "TITLE": (8, 8),
}


def main():
    path = Path(__file__).parent / "splicer_layout.png"
    if not path.exists():
        print("missing", path)
        return 1

    width, height, pixels = decode(path)
    print("canvas %dx%d  (this is the screen size)" % (width, height))
    print()

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

    problems = []
    for name in MARKERS:
        if name not in found:
            problems.append("%s is missing from the image" % name)
            continue
        x0, y0, x1, y1 = found[name]
        w, h = x1 - x0 + 1, y1 - y0 + 1
        note = ""
        want = REQUIRED.get(name)
        if want and (w, h) != want:
            note = "  <-- should be %dx%d" % want
            problems.append("%s is %dx%d, expected %dx%d" % (name, w, h, want[0], want[1]))
        print("%-7s x=%-4d y=%-4d w=%-4d h=%-4d%s" % (name, x0, y0, w, h, note))

    if problems:
        print()
        print("problems:")
        for line in problems:
            print("  -", line)
        return 1

    print()
    print("all good - hand the png back and this becomes the screen")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
