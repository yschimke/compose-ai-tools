# Render-lane parity — compose-m3 and wear-m3

"Switching lanes should change font antialiasing, not layout." This is the measured
state of that promise across every delivery lane the preview server offers for the two
in-repo design catalogs.

Every number below was produced in one session against a local `compose-preview serve`
carrying the **published** `design-artifacts/compose-m3` and `design-artifacts/wear-m3`
branches, with the desktop live daemon, the Android (Robolectric) live daemon, and a
locally built Wasm tier all attached:

```bash
./gradlew :cli:installDist :cli:packageAndroidDaemon \
          :samples:cmp-wasm-catalog:wasmCatalogDist
unzip cli/build/distributions/compose-preview-android-daemon-*.zip -d /tmp/ad

ANDROID_HOME=/opt/android-sdk \
JAVA_OPTS=-Dcomposeai.cli.libDaemonAndroidDir=/tmp/ad/lib-daemon-android \
cli/build/install/compose-preview/bin/compose-preview serve \
  --catalogs compose-m3,wear-m3 --allow-render-trusted --live-seats 4 \
  --wasm-dir compose-m3=samples/cmp-wasm-catalog/build/wasmDist \
  --trust-store deploy/image/trust/producers.json --port 8899
```

The viewer was then driven through its lane toggles with Playwright, recording each
lane's intrinsic buffer size and the on-screen rect of the element showing it.

## Which lanes each catalog actually has

| Lane | `compose-m3` | `wear-m3` |
| --- | --- | --- |
| Snapshot PNG (baked, from the delivery branch) | ✅ | ✅ |
| On-demand `/render/<id>.png` (daemon, static) | ✅ desktop | ✅ Android |
| Live stream (`#cp-live-toggle`, daemon-pushed frames) | ✅ desktop | ✅ Android |
| In-browser Wasm (`#cp-wasm-btn`) | ✅ `:samples:cmp-wasm-catalog` | ❌ (Wear Compose is Android-only) |
| SVG export (`#cp-svg-toggle`, `compose/figma-svg`) | ✅ | ✅ |

Capture density differs per catalog and is **not** a lane property: `compose-m3` renders at
**2.625** (the AS phone default — its `@Preview`s carry `density = 2.625`), `wear-m3` at
**2.0**. So a `compose-m3` PNG is 2.625 px/dp in every lane, a `wear-m3` PNG 2.0 px/dp.

## Measured frame sizes

Sizes are the lane's own output in pixels. `=` means identical to the snapshot PNG.

### compose-m3 (CMP desktop)

| Preview | Snapshot PNG | Live (daemon) | Wasm | SVG |
| --- | --- | --- | --- | --- |
| `button-filled` | 300×210 | = | = | **248×137** |
| `card-slots` | 653×253 | = | = | **601×201** |
| `textfield-outlined` | 819×252 | = | = | **767×179** |
| `text-serif` | 510×147 | = | = | **458×95** |
| `slider` | 662×210 | = | = | **599×148** |
| `progress-circular` | 189×189 | = | = | **137×137** |
| `template-appscaffold` | 1078×2399 | = | = | **1110×2431** |

### wear-m3 (Android / Robolectric)

| Preview | Snapshot PNG | Live (daemon) | SVG |
| --- | --- | --- | --- |
| `button-filled` | 165×136 | **454×454** | = |
| `card` | 454×160 | **454×454** | = |
| `text-maxlines-truncated` | 312×106 | **454×454** | = |
| `progress-circular` | 176×176 | **454×454** | = |
| `template-timetext-largeround` | 454×454 | 454×454 | **486×486** |
| `layout-list-largeround` | 454×454 | 454×454 | **486×486** |

## What's already right

**Desktop snapshot ⇄ desktop live is pixel-identical.** Not "close" — the only differing
pixels in the whole stage are the corner backend badge that changes from `▪ Snapshot` to
`▶ Live`. Same frame, same position, same rasterisation.

![compose-m3 Button — snapshot, live, and their amplified difference (only the badge differs)](../renders/lane-parity/compose-m3-button-snapshot-vs-live.png)

**Snapshot ⇄ Wasm keeps geometry to the pixel, and only antialiasing moves.** The
`CatalogApp` contain-fit contract holds: the sticker's dp geometry survives the trip into
the browser, and the diff is a one-pixel halo on every glyph and curve. The self-hosted
`fonts.json` faces mean even the `text-serif` specimen sets identically — the exact
"trivial font rendering difference" the lane swap is supposed to be limited to.

![compose-m3 text-serif — snapshot vs Wasm: identical glyph positions, outline-only difference](../renders/lane-parity/compose-m3-text-serif-snapshot-vs-wasm.png)

**Android and CMP desktop agree on the same sticker.** `FilledButtonFocused` exists twice —
Robolectric in `:samples:design-catalog-m3-android`, CMP desktop in
`:samples:design-catalog-m3`. Both render a 351×210 frame with the button's drawn extent at
(42,53)/(41,53), 267×105 vs 268×105 — a one-pixel antialiasing edge. The visible difference
is the M3 inset focus ring, which only androidx `material3` has (that's *why* the supplement
module exists), plus the opaque vs transparent sticker background.

![FilledButtonFocused — Robolectric (top), CMP desktop (middle), difference (bottom)](../renders/lane-parity/compose-m3-focusring-android-vs-desktop.png)

## Three divergences worth knowing about

### 1. The wear-m3 live lane streams the whole watch face, not the sticker

Every wrap-content Wear component sticker streams at **454×454** — the large-round device
frame (227 dp × 2) — regardless of the size the same preview bakes and the size the *static*
`/render/<id>.png` daemon path returns (verified: on-demand `/render` gives 165×136, 454×160,
312×106, matching the baked PNGs exactly; only the stream lane is square).

The viewer contain-fits the stream buffer inside the snapshot's rendered box rather than
distorting it, so the visible result is that the component **shrinks and re-centres** the
moment Live is enabled. For `card` the box is 454×160 and the square buffer fits to 160×160 —
the card is drawn at **0.35×** its snapshot size and moves 147 px right.

![wear-m3 Card — snapshot (top) vs live (middle): the same card at roughly a third of the size](../renders/lane-parity/wear-m3-card-snapshot-vs-live.png)

Full-screen Wear previews (`template-*`, `layout-list-*`) are already 454×454, so they
switch perfectly — which is why this is easy to miss.

The desktop daemon does not have the problem: its live buffer matches the wrap-content frame
in all seven `compose-m3` samples. The seam is the Android interactive/stream path capturing
the sandbox window instead of applying the wrap-content crop that
`RenderEngine`'s static path performs (`spec.wrapWidth` / `spec.wrapHeight`, see
[`RenderEngine.kt`](../daemon/android/src/main/kotlin/ee/schimke/composeai/daemon/RenderEngine.kt)).

### 2. The SVG lane is cropped to drawn content, the PNG lane to the captured frame

`compose/figma-svg` sizes its canvas as **drawn-content extent + a fixed 16 px margin on each
side** ([`FigmaSvgModel.DEFAULT_PADDING`](../data/layoutinspector/core/src/main/kotlin/ee/schimke/composeai/data/layoutinspector/FigmaSvgModel.kt)),
emitted as a single `translate(16 - minX, 16 - minY)`. The PNG is the captured composable
frame — the composable's *layout* box (which can exceed its drawn extent, e.g. a Button's
48 dp touch target around a 40 dp capsule) plus the sticker's own padding.

The two agree only by coincidence. `wear-m3` component stickers use 8 dp padding, which at
density 2.0 is exactly the SVG's 16 px — so all four component samples match to the pixel.
`compose-m3` uses 16 dp at density 2.625 (42 px), so every sticker's SVG is 52 px smaller in
each dimension, and the content lands up to 26 px away from where the PNG put it. Round Wear
full-screen previews go the other way: content already fills 454×454, so the SVG grows to
486×486 (symmetrically, so nothing actually moves — just the box).

This margin being **fixed pixels rather than dp** is the underlying reason the mismatch scales
with density.

![compose-m3 card-slots — PNG (top) vs SVG (middle): the SVG box is 52 px smaller, and the clipped second text line reappears](../renders/lane-parity/compose-m3-card-slots-snapshot-vs-svg.png)

That screenshot also shows the SVG lane *un-clipping* content the raster lanes clip: the
`card-slots` sticker overflows its measured box, so "Supporting text" is cut mid-glyph in the
PNG, in the live stream, and in Wasm — all three agree — while the SVG, rebuilt from the
layout tree, draws the full line. The clipping is a catalog-side bug, not a lane difference;
the SVG lane just makes it visible.

### 3. The Wasm tier paints a surface the baked sticker does not

`CatalogApp` renders its sticker `Surface` at `MaterialTheme.colorScheme.surface` unless the
viewer passes `background=off`, while the desktop `CatalogStickerFrame` uses
`Color.Transparent` ("component stickers render on a TRANSPARENT surface so each reads as a
silhouette on the viewer's backing"). The `CatalogApp` KDoc claims parity — "same `Surface`
default colour" — but that stopped being true when the desktop frame went transparent.

The visible effect is a `#FFFBFF` panel appearing behind the component the moment Wasm is
enabled. Geometry is unaffected.

![compose-m3 Button — snapshot vs Wasm: identical button, plus a surface panel the snapshot doesn't have](../renders/lane-parity/compose-m3-button-snapshot-vs-wasm.png)

## Reproducing

`renders/lane-parity/` holds the strips above (snapshot / other lane / 6× amplified
difference). To regenerate, stand up the server as at the top of this file and drive the
viewer's `#cp-svg-toggle`, `#cp-live-toggle` and `#cp-wasm-btn` with a headless browser,
screenshotting `.cp-stage` in each lane; the sizes table comes straight from
`/render/<id>.png` and `/render/<id>.svg` (the SVG's root `width`/`height` and its
`translate`, from which the drawn-content extent inside the PNG frame is recovered).

One measurement gotcha: read `img.naturalWidth` only after the SVG blob has finished
decoding. A probe taken too soon reports the previous PNG's dimensions and makes the SVG lane
look like it matches.
