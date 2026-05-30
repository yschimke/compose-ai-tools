# Mosaic image-protocol support — RFC

This document is the consumer-side write-up of what Mosaic would need to ship so
[`:tui-cli`](.) (and any other Mosaic consumer with a similar shape — file browsers, image
viewers, video previews) can render rasters without painting them as half-block ASCII
glyphs. It maps to the existing upstream issues
[**#621 (Kitty graphics protocol)**](https://github.com/JakeWharton/mosaic/issues/621) and
[**#897 (Sixel)**](https://github.com/JakeWharton/mosaic/issues/897); the goal here is to
sketch a single API that covers both plus iTerm2 OSC 1337, with a graceful fallback so the
consumer never has to branch on protocol identity.

Concrete prior art on the consumer side: this repo's
[`tui-cli/src/main/kotlin/ee/schimke/composeai/tui/image/AnsiImage.kt`](src/main/kotlin/ee/schimke/composeai/tui/image/AnsiImage.kt)
implements both a half-block truecolor renderer and a grayscale ASCII renderer. The
half-block path produces correct bytes — they just can't be passed to a Mosaic `Text`
because Mosaic counts every escape character as a visible glyph. The grayscale path is the
only thing actually used in the TUI today.

## TL;DR

Add **one composable** to Mosaic:

```kotlin
@Composable
fun Image(
  painter: ImagePainter,
  modifier: Modifier = Modifier,
  alignment: Alignment = Alignment.TopStart,
  contentScale: ContentScale = ContentScale.Fit,
  fallback: ImageFallback = ImageFallback.HalfBlocks,
)
```

Where `ImagePainter` is a sealed type covering at least raster bitmaps and on-disk PNG /
JPEG / WebP paths, and `Image` picks its emission strategy at render time based on
`LocalTerminalState.current.capabilities`. Consumers don't see the protocol; Mosaic does
the detection and the dispatch.

## Why it's hard today

The single load-bearing constraint is **layout-engine width tracking**. Mosaic's
`MosaicNodeLayer` measures a `Text("…")` by counting code points; everything downstream
(parent `Box`, `Row` arrangement, `Modifier.padding`, scroll bounds) keys off that count.
Any glyph stream that contains raw escape sequences inflates the count and desyncs the
layout cache.

Concrete failure path observed in `:tui-cli`:

```kotlin
// AnsiImage.render produces lines like "\e[38;2;128;200;64;48;2;…m▀▀▀▀…\e[0m"
// — 40 visible cells but ~600 code points.
for (line in AnsiImage.render(file, maxCols = 40, maxRows = 30)) {
  Text(line) // Mosaic measures this as width=600, parent Row spills, layout breaks.
}
```

So the fix isn't "add a Sixel encoder" or "add a Kitty graphics encoder". The encoders are
straightforward — both have public protocol specs and pre-existing JVM libraries. The fix
is teaching Mosaic that **certain glyph streams occupy a known cell rectangle whose width
is decoupled from their byte length.**

## Proposed API

### 1. `ImagePainter` — the source

```kotlin
sealed interface ImagePainter {
  /** Suggested cell footprint; honoured when `Modifier.size` isn't set. */
  val intrinsicSize: IntSize?

  /** Lazy raster — caller owns the pixel buffer. */
  data class Bitmap(
    val argb: IntArray,
    val width: Int,
    val height: Int,
    override val intrinsicSize: IntSize? = null,
  ) : ImagePainter

  /** Path on disk; Mosaic decodes via `javax.imageio` / equivalent. */
  data class File(
    val path: kotlin.io.path.Path,
    override val intrinsicSize: IntSize? = null,
  ) : ImagePainter

  /** Already-encoded byte stream for a specific protocol. Escape hatch for
   *  consumers that already have a sixel/Kitty/iTerm2 payload in hand and just
   *  want Mosaic to splice it into the cell grid at the right position. */
  data class Encoded(
    val protocol: ImageProtocol,
    val bytes: ByteArray,
    override val intrinsicSize: IntSize,
  ) : ImagePainter
}

enum class ImageProtocol { KittyGraphics, Sixel, ITerm2Inline, HalfBlocks }
```

### 2. `Image` — the composable

```kotlin
@Composable
fun Image(
  painter: ImagePainter,
  modifier: Modifier = Modifier,
  alignment: Alignment = Alignment.TopStart,
  contentScale: ContentScale = ContentScale.Fit,
  fallback: ImageFallback = ImageFallback.HalfBlocks,
)
```

`contentScale` should at minimum cover `Fit`, `Fill`, `Crop`, `None` — Compose semantics.

### 3. Capability-driven dispatch

`Terminal.Capabilities` already exists; it currently carries individual booleans for
`kittyGraphics`, `kittyKeyboard`, `kittyTextSizingScale`, etc. The Kitty graphics probe
already runs at startup and `terminal/KittyGraphicsEvent` already decodes the response —
**Mosaic detects Kitty graphics support today, it just doesn't expose an API that uses
it.** Extend `Capabilities` with the missing protocol fields:

```kotlin
interface Capabilities {
  // … existing fields …
  val kittyGraphics: Boolean        // ALREADY EXISTS
  val sixel: Boolean                // new — DA1 response parameter 4
  val iTerm2InlineImages: Boolean   // new — DA1 secondary response / $TERM_PROGRAM=iTerm.app
}
```

Populate it during the existing startup handshake. The Kitty graphics protocol has a
deterministic probe (issue [`\e_Gi=31,s=1,v=1,a=q,t=d,f=24;AAAA\e\\`](https://sw.kovidgoyal.net/kitty/graphics-protocol/#detecting-support));
Sixel announces via DA1 (`\e[c` → response with `4` in the parameters); iTerm2 announces
via the OSC 1337 capability response.

`Image` then picks the first protocol from a preference order intersected with
`capabilities.graphicsProtocols`, falling back to `ImageFallback.HalfBlocks` (the
shipped-in-Mosaic equivalent of today's `AnsiImage.render`) when none match.

```kotlin
private fun pickProtocol(caps: Set<ImageProtocol>): ImageProtocol =
  PREFERENCE_ORDER.firstOrNull { it in caps } ?: ImageProtocol.HalfBlocks

private val PREFERENCE_ORDER = listOf(
  ImageProtocol.KittyGraphics, // best fidelity, in-place updates, z-index
  ImageProtocol.Sixel,         // wide support, lossy palette
  ImageProtocol.ITerm2Inline,  // iTerm2 only, simple
  ImageProtocol.HalfBlocks,    // universal floor
)
```

## Layout-engine integration (the hard part)

Three nodes in `MosaicNodeLayer` need to know about images.

**Measurement.** A new `ImageNode` carries a `Terminal.Size`-style `(cols, rows)` cell
footprint computed once from the painter's pixel dimensions, the cell metrics
(`Terminal.Size.cellWidth` / `cellHeight` — already populated on terminals that report it
via XTWINOPS `\e[16t` / `\e[14t`), and the `contentScale`. **Width measurement does NOT
look at the encoded byte stream at all** — that's the central invariant. The layout pass
treats the image node as a sized rectangle, full stop.

**Drawing.** `DrawScope` needs a new operation:

```kotlin
interface DrawScope {
  // … existing methods …
  fun drawImage(
    bytes: ByteArray,
    protocol: ImageProtocol,
    topLeft: IntOffset,
    size: IntSize,
  )
}
```

Implementations:

- `Kitty`: emit `\e_Gf=…,s=…,v=…,a=T,C=1,X=col,Y=row;<base64>\e\\` once per recomposition.
  Reuse Kitty image IDs across recompositions when the payload hash is unchanged so
  long-lived previews don't re-upload bytes.
- `Sixel`: emit the sixel stream prefixed by absolute cursor positioning (`\e[<row>;<col>H`).
  Sixel renders below-the-cursor by default; the positioning escape is what places it.
- `iTerm2`: `\e]1337;File=inline=1;width=Ncc;height=Mcc;preserveAspectRatio=0:<b64>\a`.
- `HalfBlocks`: route through the existing text path — emit one `▀` per cell with the
  appropriate truecolor fg/bg. This is the only branch that needs no protocol bytes; it's
  also what makes the fallback "free."

**Diffing.** Mosaic's frame-to-frame diff suppresses redundant writes. For Kitty
graphics, the right granularity is "did the image ID change?"; for Sixel and
HalfBlocks, "did the (bytes, size, topLeft) triple change?". Both are content-hash
comparisons; the existing diff pipeline just needs a new node-kind branch.

## Resize behaviour

When the terminal resizes (Mosaic 0.18 already pushes a new `TerminalState`), `Image`
recomposes with the new container size and re-derives the cell rectangle. For
`KittyGraphics`, this means deleting the previous placement (`\e_Ga=d,d=i,i=<id>\e\\`)
before drawing the new one — otherwise the old image stays on screen at the original
position. For Sixel and iTerm2 the next frame's repaint pass already clears the cells
(both protocols draw within the cell grid, so the standard "wipe these cells"
machinery covers them).

The proposed scope for an initial PR is to handle this for KittyGraphics only and
document that Sixel / iTerm2 images "leak" on resize until the next full repaint — a
known limitation rather than a blocker.

## Out of scope (initial PR)

- **Animation**: APNG / WebP-animated / GIF playback through `Image`. Static rasters
  first; animation lands as a second-PR `AnimatedImage` composable that ticks frames.
- **Vector painters**: SVG → cell raster. Consumers can rasterise to PNG and pass the
  result; built-in SVG support is a separate concern.
- **Z-ordering / overlap**: Kitty supports it natively; Sixel and HalfBlocks don't. Pin
  initial behaviour to "paint in document order, last write wins" and revisit when a
  real use case appears.

## Backwards compatibility

`Image` is a new symbol — no existing consumer is affected. `Terminal.Capabilities`
gains one field; the data class's generated `copy` / `equals` evolve, but the public
constructor is already declared with `kotlin.jvm.internal.DefaultConstructorMarker`
overloads so adding a defaulted field is binary-compatible.

The half-block fallback is identical in spirit (one `▀` per cell, truecolor fg/bg) to
the hand-rolled `AnsiImage.render` this consumer used to ship — and the consumer side
already routes through the fork's `Image` (Kitty → half-block → ASCII auto-fallback),
so `AnsiImage.kt` has been deleted from the tree. The acceptance test fired the day the
fork's `Image` landed: `LIMITATIONS.md` items 2 and 3 collapsed into "use the upstream
API." Filing this RFC upstream is what closes the loop for downstream consumers that
don't want to pin against a fork.

## Sketched implementation order

1. Add `ImageProtocol` enum + extend `Terminal.Capabilities`. Wire the existing
   capability handshake to populate it. Add no new composable yet — this is just
   detection.
2. Add `DrawScope.drawImage` with a `HalfBlocks` implementation only. Add the `Image`
   composable, hardwired to `HalfBlocks`. Consumers can replace their ASCII renderer
   immediately even though no real protocol is wired up.
3. Add the `KittyGraphics` `DrawScope.drawImage` branch + ID-recycling cache. This is
   the highest-fidelity path and the one the original [#621](https://github.com/JakeWharton/mosaic/issues/621)
   asks for.
4. Add the `Sixel` branch — closes [#897](https://github.com/JakeWharton/mosaic/issues/897).
5. Add the iTerm2 branch.

Each step is independently shippable and each adds value to consumers — step 2 alone is
already enough to unblock our TUI.

## Open questions for upstream

- **Should `ImagePainter.File` resolve lazily or eagerly?** Lazy keeps the composition
  cheap when the same painter is used across many recompositions, but eager catches
  decode errors at composition time where the consumer can fall back to a placeholder.
- **Where does the cell-pixel-size data come from on terminals that don't reply to
  XTWINOPS?** Conservative fallback: assume `cellWidth = 8`, `cellHeight = 16`, document
  that Sixel sizing will be off on those terminals. Kitty's protocol takes pixel
  dimensions directly so it's unaffected.
- **What's the deletion strategy for half-block images?** Today every recomposition
  repaints every cell, so an image that goes away gets cleared by the cells it occupied
  being repainted as something else. If Mosaic ever ships a "draw region directly"
  optimisation, image-shaped cells need to be tracked as dirty.

---

If we're going to ship this, this consumer is happy to write a first-cut PR against
upstream covering steps 1 + 2 (capability detection + `Image` composable + half-block
backend). Steps 3–5 want someone closer to the Kitty / Sixel / iTerm2 surfaces to land
cleanly.
