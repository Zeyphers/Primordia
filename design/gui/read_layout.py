"""
Reads design/gui/layout.png and reports where every interactive element of the Basic Gene Lab's
screen has been placed.

The point of this file is that the GUI's geometry lives in one place a human can edit with a mouse.
Slot coordinates otherwise appear three times over — baked into the background art, listed in
GeneLabScreenHandler, and read again by GeneLabScreen — and moving a slot means changing all three
in step. Getting that wrong produces a screen where the highlight sits next to the item rather than
on it, which looks like a rendering bug and is not one.

Usage:
    python design/gui/read_layout.py

Edit design/gui/art.png freely; it is the picture. Edit design/gui/layout.png to say where things
are: move each coloured block so it covers the slot or bar it names. Nothing but the markers may
use these exact colours, and each marker must stay a solid rectangle.
"""

import struct
import sys
import zlib
from pathlib import Path

# One pure colour per element. Slots are 16x16; the three lines can be any size.
MARKERS = {
    "SAMPLE":   (255, 0, 255),
    "FUEL":     (255, 0, 0),
    "REDSTONE": (0, 255, 0),
    "OUTPUT":   (0, 0, 255),
    "LINE1":    (255, 255, 0),
    "LINE2":    (0, 255, 255),
    "LINE3":    (255, 128, 0),
}


def decode(path):
    """Minimal RGBA8 PNG reader — avoids a Pillow dependency for one image."""
    data = Path(path).read_bytes()
    pos, idat, width, height = 8, b"", None, None
    while pos < len(data):
        length = struct.unpack(">I", data[pos:pos + 4])[0]
        tag = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + length]
        if tag == b"IHDR":
            width, height, depth, ctype = struct.unpack(">IIBB", chunk[:10])
            if (depth, ctype) != (8, 6):
                sys.exit(f"{path}: need 8-bit RGBA, got depth={depth} colour type={ctype}")
        elif tag == b"IDAT":
            idat += chunk
        elif tag == b"IEND":
            break
        pos += 12 + length

    raw, bpp, stride = zlib.decompress(idat), 4, width * 4
    rows, prev, i = [], bytearray(stride), 0
    for _ in range(height):
        filt = raw[i]
        i += 1
        line = bytearray(raw[i:i + stride])
        i += stride
        if filt == 1:
            for x in range(bpp, stride):
                line[x] = (line[x] + line[x - bpp]) & 255
        elif filt == 2:
            for x in range(stride):
                line[x] = (line[x] + prev[x]) & 255
        elif filt == 3:
            for x in range(stride):
                a = line[x - bpp] if x >= bpp else 0
                line[x] = (line[x] + ((a + prev[x]) >> 1)) & 255
        elif filt == 4:
            for x in range(stride):
                a = line[x - bpp] if x >= bpp else 0
                b = prev[x]
                c = prev[x - bpp] if x >= bpp else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[x] = (line[x] + pr) & 255
        rows.append([tuple(line[x * 4:x * 4 + 4]) for x in range(width)])
        prev = line
    return width, height, rows


def main():
    here = Path(__file__).parent
    width, height, px = decode(here / "layout.png")

    found, problems = {}, []
    for name, rgb in MARKERS.items():
        hits = [(x, y) for y in range(height) for x in range(width)
                if px[y][x][3] > 0 and px[y][x][:3] == rgb]
        if not hits:
            problems.append(f"{name}: colour rgb{rgb} not found")
            continue
        xs, ys = [p[0] for p in hits], [p[1] for p in hits]
        x0, y0, w, h = min(xs), min(ys), max(xs) - min(xs) + 1, max(ys) - min(ys) + 1
        if len(hits) != w * h:
            problems.append(f"{name}: not a solid rectangle "
                            f"({len(hits)} px in a {w}x{h} box) — clean up stray pixels")
        if name.startswith("LINE") is False and (w, h) != (16, 16):
            problems.append(f"{name}: slots must be 16x16, got {w}x{h}")
        found[name] = (x0, y0, w, h)

    for name, (x, y, w, h) in found.items():
        print(f"  {name:9s} at ({x:3d},{y:3d})  {w}x{h}")

    if problems:
        print("\nPROBLEMS:")
        for p in problems:
            print("  " + p)
        sys.exit(1)

    print("\n--- paste into GeneLabScreenHandler ---")
    for name in ("SAMPLE", "FUEL", "REDSTONE", "OUTPUT"):
        x, y, _, _ = found[name]
        print(f"\tpublic static final int {name}_X = {x}, {name}_Y = {y};")
    lines = [found[f"LINE{i}"] for i in (1, 2, 3)]
    print("\tpublic static final int[] TROUGH_X = {%s};" % ", ".join(str(l[0]) for l in lines))
    print("\tpublic static final int[] TROUGH_Y = {%s};" % ", ".join(str(l[1]) for l in lines))
    print("\tpublic static final int[] TROUGH_W = {%s};" % ", ".join(str(l[2]) for l in lines))
    print("\tpublic static final int[] TROUGH_H = {%s};" % ", ".join(str(l[3]) for l in lines))

    # The window has to be tall enough for whatever was drawn, plus the player's inventory.
    lowest = max(y + h for _, y, _, h in found.values())
    print(f"\n  lowest element ends at y={lowest}; player inventory needs ~94px below that")
    print(f"  suggested BACKGROUND_HEIGHT = {lowest + 94}")


if __name__ == "__main__":
    main()
