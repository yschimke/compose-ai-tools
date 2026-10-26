#!/usr/bin/env python3
"""Check that each batch preview of `:samples:sdk-matrix` rendered its own frame.

`samples/sdk-matrix/.../BatchPreviews.kt` declares three consecutive previews, each filled with its
own primary colour and labelled with a letter. On SDK 37 a frame clock that went backwards between
tests once left every preview after the first a uniform #FAFAFA frame with no error (#6229), so a
green render task proved nothing. This reads each PNG back and fails unless its background is the
expected colour and the label drew on it.

Pure standard library (zlib), so the nightly runner needs no Pillow:

    python3 scripts/sdk_matrix_pixel_check.py samples/sdk-matrix/build/compose-previews/renders

Exits 0 when every preview matches, 1 with one line per mismatch otherwise.
"""

from __future__ import annotations

import struct
import sys
import zlib
from pathlib import Path

# Preview function name -> expected background RGB.
EXPECTED = {
    "BatchRedPreview": (255, 0, 0),
    "BatchBluePreview": (0, 0, 255),
    "BatchGreenPreview": (0, 255, 0),
}

# Per-channel slack for the background match; the fill is a flat primary, so this only absorbs
# colour-space rounding.
TOLERANCE = 8

# The white label must change at least this share of pixels; a uniform frame changes none.
MIN_LABEL_FRACTION = 0.002


def decode_png(path: Path) -> tuple[int, int, list[list[tuple[int, int, int]]]]:
    """Decode an 8-bit, non-interlaced RGB/RGBA/greyscale PNG into rows of RGB tuples."""
    data = path.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("not a PNG")
    pos = 8
    width = height = 0
    bit_depth = colour_type = interlace = 0
    idat = bytearray()
    palette: list[tuple[int, int, int]] = []
    while pos < len(data):
        (length,) = struct.unpack(">I", data[pos : pos + 4])
        kind = data[pos + 4 : pos + 8]
        body = data[pos + 8 : pos + 8 + length]
        pos += 12 + length
        if kind == b"IHDR":
            width, height, bit_depth, colour_type, _, _, interlace = struct.unpack(">IIBBBBB", body)
        elif kind == b"PLTE":
            palette = [tuple(body[i : i + 3]) for i in range(0, len(body), 3)]  # type: ignore[misc]
        elif kind == b"IDAT":
            idat += body
        elif kind == b"IEND":
            break
    if bit_depth != 8 or interlace != 0:
        raise ValueError(f"unsupported PNG (bit depth {bit_depth}, interlace {interlace})")
    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}.get(colour_type)
    if channels is None:
        raise ValueError(f"unsupported colour type {colour_type}")
    raw = zlib.decompress(bytes(idat))
    stride = width * channels
    rows: list[list[tuple[int, int, int]]] = []
    previous = bytearray(stride)
    offset = 0
    for _ in range(height):
        filter_type = raw[offset]
        line = bytearray(raw[offset + 1 : offset + 1 + stride])
        offset += 1 + stride
        for i in range(stride):
            left = line[i - channels] if i >= channels else 0
            up = previous[i]
            upper_left = previous[i - channels] if i >= channels else 0
            if filter_type == 1:
                line[i] = (line[i] + left) & 0xFF
            elif filter_type == 2:
                line[i] = (line[i] + up) & 0xFF
            elif filter_type == 3:
                line[i] = (line[i] + ((left + up) >> 1)) & 0xFF
            elif filter_type == 4:
                p = left + up - upper_left
                pa, pb, pc = abs(p - left), abs(p - up), abs(p - upper_left)
                predictor = left if pa <= pb and pa <= pc else (up if pb <= pc else upper_left)
                line[i] = (line[i] + predictor) & 0xFF
        row = []
        for x in range(width):
            px = line[x * channels : (x + 1) * channels]
            if colour_type in (0, 4):
                row.append((px[0], px[0], px[0]))
            elif colour_type == 3:
                row.append(palette[px[0]])
            else:
                row.append((px[0], px[1], px[2]))
        rows.append(row)
        previous = line
    return width, height, rows


def close(a: tuple[int, int, int], b: tuple[int, int, int]) -> bool:
    return all(abs(x - y) <= TOLERANCE for x, y in zip(a, b))


def check(renders: Path) -> list[str]:
    problems = []
    for function, expected in EXPECTED.items():
        matches = sorted(renders.glob(f"*{function}*.png"))
        if len(matches) != 1:
            problems.append(f"{function}: expected one PNG in {renders}, found {len(matches)}")
            continue
        path = matches[0]
        try:
            width, height, rows = decode_png(path)
        except (ValueError, zlib.error) as e:
            problems.append(f"{path.name}: cannot decode ({e})")
            continue
        corners = [rows[1][1], rows[1][width - 2], rows[height - 2][1], rows[height - 2][width - 2]]
        if not all(close(c, expected) for c in corners):
            problems.append(
                f"{path.name}: background {corners[0]} is not {expected} — "
                "the preview did not draw its own frame (#6229)"
            )
            continue
        label = sum(1 for row in rows for px in row if not close(px, expected))
        if label < MIN_LABEL_FRACTION * width * height:
            problems.append(f"{path.name}: uniform {expected} frame, the label never drew")
    return problems


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    problems = check(Path(argv[1]))
    for problem in problems:
        print(f"sdk-matrix pixel check: {problem}", file=sys.stderr)
    if not problems:
        print(f"sdk-matrix pixel check: all {len(EXPECTED)} batch previews drew their own frame.")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
