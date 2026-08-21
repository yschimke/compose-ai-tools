# A round device's stage stops at the bezel

Giving the reference-compare page a per-preview ground ([#4375]) fixed a dark-first catalog's
invisible stickers and introduced a quieter version of the same fault one device class over.

A Wear capture is a **circle in a square PNG** — the renderer clips it, so the corners are
transparent by the time anyone sees one. Painting the resolved backdrop across the whole panel fills
those corners back in, which draws the watch as a rectangle.

## Not cosmetic: the boundary was measurably gone

Every round preview in `samples/design-catalog-wear-m3` declares
`@Preview(backgroundColor = 0xFF000000)`, so `PreviewBackdrop` resolves **black** through
`PREVIEW_BACKGROUND_COLOR` — not the catalog's `#1C1B1F` stage. The screens are near-black too.

Sampling each render's own ring of pixels just inside the device edge, against that black stage:

| Render | Device edge | Distance from the stage |
| --- | --- | --- |
| `PageIndicatorScaffoldTemplate_Small_Round` | `(0, 0, 0)` | **0.0** |
| `PageIndicatorScaffoldTemplate_Large_Round` | `(0, 0, 0)` | **0.0** |
| `PageIndicatorScaffoldTemplate_Extra_Large_Round` | `(0, 0, 0)` | **0.0** |
| `CardScaling_Large_Round` (scroll top) | `(5, 4, 6)` | 8.8 |
| `ScalingListSticker_Extra_Large_Round` | `(11, 10, 12)` | 19.1 |
| `EdgeButtonSticker_Small_Round` | `(18, 16, 21)` | 32.0 |

Three of the fourteen were **pixel-identical** to their stage. Not "hard to see" — the same colour,
so the device had no visible edge at all.

## Before / after

The committed `serve-reference-compare-round-device` fixture, captured by the preview harness. The
stub render reproduces the real case: transparent corners, a near-black face.

![Reference-compare page for a round device, before and after](https://raw.githubusercontent.com/yschimke/compose-ai-tools/0bc6e0c7810f29d85e5ad90049651b9ef4a7b872/docs/design/evidence/wear-device-clip/reference-compare-round.png)

## Where the circle is stated

`PreviewClip` resolves it once, in dp, next to `PreviewBackdrop` and for the same reason: the
daemon's `render/deviceClip` product already computed a circle and nothing downstream could see it,
so every consumer that wanted one would have re-derived it in its own units. The daemon's product
now delegates to the resolver rather than carrying its own copy.

Two details are load-bearing and neither is obvious:

**The clip goes on the image, not the panel.** `.cp-compare-shot img` sizes `width: auto; height:
auto`, so the image element's box carries the render's own square and a circle stated against the
device lands exactly on the device's edge. The panel is a different rectangle in a different place;
clipping it would produce a circle that merely looks plausible. The panel keeps the page's
checkerboard, so the bezel reads as the edge of the hardware rather than as more stage.

**Roundness comes from the device string alone.** `DeviceDimensions.resolve` returns early with
`isRound = false` the moment it is handed explicit `widthDp`/`heightDp`, and every Wear preview in
this catalog states **both** a `device =` and its dp. Asking it for both at once reports the entire
set as square, which would leave the feature silently inert on exactly the previews it exists for.
`ServeDeviceFrame.from` therefore takes dimensions from the annotation and shape from the device,
the same split the daemon's product uses.

## Scope

Only the reference-compare page paints the resolved per-preview backdrop today — the compare wall
and the landing cards use fixed `#111113` / theme plates — so it is the only surface that had a
shape to get wrong. When those surfaces take a per-preview ground they should take
`PreviewClip.cssClipPath` with it.

**The fidelity scorer is not changed here.** It has no notion of the clip either: `contentBox` boxes
a circle to its bounding square, so about 21.5% of every round comparison plane (`1 − π/4`) is corner
that belongs to neither component. That is a real effect on Wear scores and its own change — masking
the plane needs the clip plumbed into `cli/serve-web`, and it moves published numbers, which this
does not.

[#4375]: https://github.com/yschimke/compose-ai-tools/pull/4375
