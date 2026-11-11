"""Encodes a PNG as a Charta card blob (``.mccard``) in this pack's MYAV format.

Charta registers a card image from ``data/<ns>/images/card/<id>.mccard`` and only then does
``FullColorCards`` bind the matching ``assets/<ns>/fullcard/<id>.png`` over it. A card with art but no
blob therefore has no texture id at all and draws as the purple-black missing texture.

A blob is a ``MYAV`` marker, the image size as two big-endian ints, then a gzip stream of one byte per
pixel: low six bits are a ``CardImage.COLOR_PALETTE`` index and the top two an ``ALPHA_PALETTE`` index.
The header is what lets the pack ship art at its native size instead of Charta's 25x35 box.

    python tools/make_card_blob.py <in.png> <out.mccard>
"""

import gzip
import struct
import sys

from PIL import Image

COLOR_PALETTE = [
    0x000000, 0x252525, 0x494949, 0x6E6E6E, 0x929292, 0xB7B7B7, 0xDCDCDC, 0xFFFFFF,
    0x7F0000, 0xB20000, 0xE30000, 0xFF0000, 0xFF5353, 0xFF7575, 0xFF9898, 0xFFBABA,
    0x7F3F00, 0xB25800, 0xE37000, 0xFF7F00, 0xFFA953, 0xFFBA75, 0xFFCB98, 0xFFDCBA,
    0x7F7F00, 0xB2B200, 0xE3E300, 0xFFFF00, 0xFFFF53, 0xFFFF75, 0xFFFF98, 0xFFFFBA,
    0x007F00, 0x00B200, 0x00E300, 0x00FF00, 0x53FF53, 0x75FF75, 0x98FF98, 0xBAFFBA,
    0x007F7F, 0x00B2B2, 0x00E3E3, 0x00FFFF, 0x53FFFF, 0x75FFFF, 0x98FFFF, 0xBAFFFF,
    0x00007F, 0x0000B2, 0x0000E3, 0x0000FF, 0x5353FF, 0x7575FF, 0x9898FF, 0xBABAFF,
    0x7F007F, 0xB200B2, 0xE300E3, 0xFF00FF, 0xFF53FF, 0xFF75FF, 0xFF98FF, 0xFFBAFF,
]
ALPHA_PALETTE = [0, 85, 170, 255]
MAGIC = b"MYAV"


def nearest_color(rgb):
    r1, g1, b1 = (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF
    best, best_distance = 0, 1 << 30
    for index, value in enumerate(COLOR_PALETTE):
        r2, g2, b2 = (value >> 16) & 0xFF, (value >> 8) & 0xFF, value & 0xFF
        distance = (r1 - r2) ** 2 + (g1 - g2) ** 2 + (b1 - b2) ** 2
        if distance < best_distance:
            best, best_distance = index, distance
    return best


def nearest_alpha(alpha):
    best, best_distance = 0, 1 << 30
    for index, value in enumerate(ALPHA_PALETTE):
        distance = abs(alpha - value)
        if distance < best_distance:
            best, best_distance = index, distance
    return best


def encode(path):
    image = Image.open(path).convert("RGBA")
    width, height = image.size
    pixels = bytearray(width * height)
    data = image.load()
    for y in range(height):
        row = y * width
        for x in range(width):
            r, g, b, a = data[x, y]
            # Charta reads (colour != 0, alpha index 0) back as fully opaque, so a transparent pixel has to
            # be the all-zero byte or the card grows a black frame.
            if a < 8:
                pixels[row + x] = 0
            else:
                pixels[row + x] = (nearest_alpha(a) << 6) | nearest_color((r << 16) | (g << 8) | b)
    header = MAGIC + struct.pack(">II", width, height)
    # mtime=0 keeps the file byte-for-byte reproducible.
    return header + gzip.compress(bytes(pixels), mtime=0)


def main():
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    with open(sys.argv[2], "wb") as out:
        out.write(encode(sys.argv[1]))
    print("wrote", sys.argv[2])


if __name__ == "__main__":
    main()
