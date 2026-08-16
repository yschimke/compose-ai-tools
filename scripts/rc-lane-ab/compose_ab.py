#!/usr/bin/env python3
"""Compose the comparison images committed under `renders/rc-embedded-lane-ab/`.

The lane directories hold one PNG per document. What a reader needs is the *pair*, labelled, and
for an animation the frames in order — none of which the render harnesses produce. Composing here
rather than by hand is the point: a hand-made composite is the one artifact in an evidence
directory that nothing can regenerate, so it goes stale silently while still looking like a picture
of two lanes.

Usage: compose_ab.py <lane-output-dir> <renders dir>
"""

import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

# Each committed image, as (output name, [document ids], caption). A single id makes a side-by-side
# pair; several make a strip with one column per id, which is how an animation reads.
IMAGES = [
    (
        "toggle-thumb.png",
        [
            f"Toggle_Animation_Light_toggle_anim_light-2b5502c0_{p}"
            for p in ("0pct", "25pct", "50pct", "75pct", "100pct")
        ],
        "switch thumb, five animation frames (issue #3992)",
    ),
    (
        "weather-forecast-text.png",
        ["WeatherForecast_Light_weather_forecast_light-36955f1d"],
        "the worst-scoring document, and visually identical: glyph rasterization",
    ),
    (
        "picture-entity-app-mode.png",
        ["PictureEntity_AppMode_Light_picture_entity_app_mode_light-5174ea1f"],
        "an unloadable image now costs one slot, not the card (issue #3993)",
    ),
]

BACKGROUND = (0x44, 0x44, 0x44)
LABEL = (0xFF, 0xFF, 0xFF)
PAD = 4
LABEL_H = 20


def font():
    for candidate in (
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
        "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf",
    ):
        if Path(candidate).is_file():
            return ImageFont.truetype(candidate, 12)
    return ImageFont.load_default()


def compose(lanes: Path, ids: list[str], scale: int) -> Image.Image:
    """Two rows — view above, embedded below — with one column per id."""
    tiles = {}
    failures = {}
    for lane in ("view", "embedded"):
        for i in ids:
            path = lanes / lane / f"{i}.png"
            if path.is_file():
                tiles[(lane, i)] = Image.open(path).convert("RGB")
                continue
            # A document the player cannot render leaves `<id>.error` and no PNG. That *is* the
            # result worth showing, so it is drawn as the message rather than treated as a broken
            # run — this is exactly what issue #3993 looks like from the outside.
            note = lanes / lane / f"{i}.error"
            if not note.is_file():
                raise SystemExit(f"missing render and no .error beside it: {path}")
            failures[(lane, i)] = note.read_text().strip().splitlines()[0][:80]

    if not tiles:
        raise SystemExit("no lane rendered any of: " + ", ".join(ids))
    w = max(t.width for t in tiles.values()) * scale
    h = max(t.height for t in tiles.values()) * scale
    for key, message in failures.items():
        panel = Image.new("RGB", (w // scale, h // scale), (0x1A, 0x1A, 0x1A))
        ImageDraw.Draw(panel).text((8, 8), "did not render:", fill=(0xFF, 0x60, 0x60), font=font())
        ImageDraw.Draw(panel).text((8, 24), message, fill=(0xFF, 0x60, 0x60), font=font())
        tiles[key] = panel
    out = Image.new(
        "RGB",
        (PAD * 2 + len(ids) * (w + PAD), PAD + 2 * (LABEL_H + h + PAD)),
        BACKGROUND,
    )
    draw = ImageDraw.Draw(out)
    f = font()
    for row, lane in enumerate(("view", "embedded")):
        y = PAD + row * (LABEL_H + h + PAD)
        draw.text((PAD + 2, y + 4), f"{lane} player", fill=LABEL, font=f)
        for col, i in enumerate(ids):
            tile = tiles[(lane, i)]
            if scale != 1:
                tile = tile.resize((tile.width * scale, tile.height * scale), Image.NEAREST)
            out.paste(tile, (PAD + col * (w + PAD), y + LABEL_H))
    return out


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    lanes, renders = Path(sys.argv[1]), Path(sys.argv[2])
    renders.mkdir(parents=True, exist_ok=True)
    for name, ids, caption in IMAGES:
        # Small fixtures are unreadable at 1:1 — the switch is 105x63 — so a strip of them is
        # scaled up with nearest-neighbour, which keeps the pixel edges the defect is visible in.
        scale = 3 if len(ids) > 1 else 1
        compose(lanes, ids, scale).save(renders / name)
        print(f"    {name}  ({caption})")


if __name__ == "__main__":
    main()
