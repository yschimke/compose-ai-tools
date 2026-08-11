#!/usr/bin/env python3
"""Compose the committed `renders/rc-text-metrics/*-three-lanes.png` strips.

Each strip puts the same fixture side by side across `java`, `cmp-android` and `cmp-jvm`, in that
order, with the lane name written above its render. The order is fixed and `java` is first because
it is the reference lane the design doc reads everything against — a strip whose columns moved
would silently invalidate every "the third column is wider" sentence written about it.

Called by `render-strips.sh`, which is what supplies a directory of freshly rendered lanes. Running
this alone against stale lane output will happily compose stale strips, which is the failure mode
the shell wrapper exists to prevent.
"""

from __future__ import annotations

import sys
import os
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFont

LANES = ("java", "cmp-android", "cmp-jvm")

MARGIN = 16
GAP = 8
LABEL_HEIGHT = 18
BACKGROUND = (24, 26, 30)
LABEL_COLOR = (208, 212, 220)

# The strips worth committing, and the fixtures each one stacks. Everything else in the fixture
# set is rendered but not composed: these seven are the ones the README and the PR body argue from,
# and a strip nobody cites is a file that only ever goes stale.
STRIPS: dict[str, list[str]] = {
    "card-three-lanes": ["text-metrics-card"],
    "weight-sweep-three-lanes": ["text-metrics-weight-sweep"],
    "layout-single-ellipsis-three-lanes": ["text-metrics-layout-single-ellipsis"],
    "layout-wrap-ellipsis-three-lanes": ["text-metrics-layout-wrap-ellipsis"],
    "layout-single-modes-three-lanes": [
        "text-metrics-layout-single-clip",
        "text-metrics-layout-single-visible",
        "text-metrics-layout-single-ellipsis",
        "text-metrics-layout-single-start-ellipsis",
        "text-metrics-layout-single-middle-ellipsis",
    ],
    "layout-paragraph-modes-three-lanes": [
        "text-metrics-layout-wrap-clip",
        "text-metrics-layout-wrap-ellipsis",
        "text-metrics-layout-wrap-justify",
    ],
    "alignment-ltr-vs-rtl-three-lanes": [
        "text-metrics-layout-align-start",
        "text-metrics-layout-align-end",
        "text-metrics-layout-align-start-rtl",
        "text-metrics-layout-align-end-rtl",
    ],
    "core-text-decorations-three-lanes": [
        "text-metrics-layout-style-underline",
        "text-metrics-layout-style-strikethrough",
    ],
    "core-text-paragraph-properties-three-lanes": [
        "text-metrics-layout-paragraph-break-high-quality",
        "text-metrics-layout-paragraph-break-balanced",
        "text-metrics-layout-paragraph-hyphenation-normal",
        "text-metrics-layout-paragraph-justification-inter-word",
    ],
    "core-text-autosize-three-lanes": ["text-metrics-layout-style-autosize-bounded"],
}


LABEL_FONT = os.environ.get(
    "RC_TEXT_METRICS_LABEL_FONT", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
)
LABEL_FONT_SIZE = 13

# `RcTextMetricDocuments` pins every layout probe's text component to 300x120 at the origin. Inset
# one pixel to exclude the box border: Java View and Compose rasterise that stroke differently, and
# it is not text. The rightmost interior pixel remains, so a clipped partial glyph still counts.
LAYOUT_BOX = (1, 1, 299, 119)


def layout_parity(lanes_dir: Path) -> None:
    """Print exact single-line/paragraph pixel residuals against AndroidX Java."""
    reference = lanes_dir / "java"
    for lane in LANES[1:]:
        for group in ("single", "wrap"):
            fixtures = sorted(reference.glob(f"text-metrics-layout-{group}-*.png"))
            changed = 0
            pixels = 0
            for fixture in fixtures:
                expected = Image.open(fixture).convert("RGB").crop(LAYOUT_BOX)
                actual = Image.open(lanes_dir / lane / fixture.name).convert("RGB").crop(LAYOUT_BOX)
                difference = ImageChops.difference(expected, actual)
                changed += sum(pixel != (0, 0, 0) for pixel in difference.getdata())
                pixels += expected.width * expected.height
            percent = changed * 100 / pixels if pixels else 0
            print(
                f"    layout parity {lane:11} {group:6}: "
                f"{changed}/{pixels} changed pixels ({percent:.3f}%)"
            )


def load_font() -> ImageFont.FreeTypeFont:
    """One font, or nothing.

    A fallback chain would be friendlier and wrong. These strips are *committed*, so a host with a
    different font package would rewrite every one of them with the lane labels reshaped, producing
    a diff that looks like the renders moved when only the caption did. Failing here is noisy; a
    silent five-file pixel diff on an unrelated PR is worse.
    """
    if not Path(LABEL_FONT).exists():
        raise SystemExit(
            f"missing {LABEL_FONT}, which the committed strips are labelled with.\n"
            "  apt-get install fonts-dejavu-core\n"
            "Deliberately not falling back to another face: it would rewrite every tracked strip."
        )
    return ImageFont.truetype(LABEL_FONT, LABEL_FONT_SIZE)


def compose(lanes_dir: Path, fixtures: list[str], font: ImageFont.FreeTypeFont) -> Image.Image:
    rows = []
    for fixture in fixtures:
        row = [Image.open(lanes_dir / lane / f"{fixture}.png").convert("RGB") for lane in LANES]
        rows.append(row)

    cell_w = max(image.width for row in rows for image in row)
    row_heights = [max(image.height for image in row) for row in rows]

    width = MARGIN * 2 + cell_w * len(LANES) + GAP * (len(LANES) - 1)
    height = MARGIN * 2 + sum(h + LABEL_HEIGHT for h in row_heights) + GAP * (len(rows) - 1)

    strip = Image.new("RGB", (width, height), BACKGROUND)
    draw = ImageDraw.Draw(strip)

    y = MARGIN
    for row, row_height in zip(rows, row_heights):
        for column, image in enumerate(row):
            x = MARGIN + column * (cell_w + GAP)
            draw.text((x, y + 2), LANES[column], fill=LABEL_COLOR, font=font)
            strip.paste(image, (x, y + LABEL_HEIGHT))
        y += LABEL_HEIGHT + row_height + GAP

    return strip


def main(argv: list[str]) -> int:
    # `--check` exists so the shell wrapper can validate this script's prerequisites *before* it
    # spends two minutes rendering. Keeping the check here rather than restating the font path in
    # the wrapper means the two cannot disagree about what is required.
    if len(argv) == 2 and argv[1] == "--check":
        load_font()
        return 0

    if len(argv) != 3:
        print(f"usage: {argv[0]} <lane-output-dir> <strip-output-dir>", file=sys.stderr)
        print(f"       {argv[0]} --check", file=sys.stderr)
        return 2

    lanes_dir, out_dir = Path(argv[1]), Path(argv[2])
    out_dir.mkdir(parents=True, exist_ok=True)
    font = load_font()

    layout_parity(lanes_dir)

    for name, fixtures in STRIPS.items():
        strip = compose(lanes_dir, fixtures, font)
        target = out_dir / f"{name}.png"
        strip.save(target)
        print(f"    {target.name}  {strip.width}x{strip.height}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
