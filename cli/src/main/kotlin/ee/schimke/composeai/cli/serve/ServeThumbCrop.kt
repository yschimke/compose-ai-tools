package ee.schimke.composeai.cli.serve

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Server-side thumbnail content-crop for the `serve` catalog pages. A render PNG can be much larger
 * than the component it shows — a Wear sticker is drawn on a fixed 227 dp watch canvas (454×454 px)
 * with the component centred and small, so a raw `<img>` displays a speck floating in empty canvas.
 * The catalog also carries a **content-cropped** figma-svg per component (`figma/<slug>.svg`),
 * whose root `viewBox` is the component's content box and whose root `<g
 * transform="translate(tx,ty)">` places that box within the centred render. Reading those, we clip
 * the PNG to the component box so the card shows the component, not the canvas.
 *
 * This is the server-side port of the client crop the static gallery ships
 * (`scripts/design-artifacts/render-index-html.mjs` — `parseBox` + `frame`): identical maths, but
 * computed once at page build from local files (no per-card `fetch`, no layout flash). Phone /
 * desktop catalogs render tight to the component, so the box already ≈ the render and
 * [computeThumbCrop] returns `null` (no-op) for them — only the framed-in-a-canvas stickers get
 * cropped.
 */
data class ContentCrop(
  /** Clip-window size (px) — the component box scaled to fit [CAP]. */
  val boxW: Int,
  val boxH: Int,
  /** The full render `<img>` size (px) at the same scale; larger than the box, clipped by it. */
  val imgW: Int,
  val imgH: Int,
  /** Negative offsets that shift the render so the component's top-left meets the clip origin. */
  val left: Int,
  val top: Int,
  /**
   * Whether what falls outside the window is hidden.
   *
   * True for a content crop, whose job is to throw the surrounding canvas away — a Wear sticker's
   * watch face is not the component. False for a capture-gutter crop, where the pixels outside the
   * box are the component's own shadow or focus ring: the window is there to make the box line up
   * with its gutter-less neighbours, and hiding the overflow would crop the shadow the gutter was
   * added to keep (m3-catalog#102, then #179). It spills into the grid's gap, which is where a
   * shadow belongs.
   */
  val clip: Boolean = true,
  /**
   * The box width in NATIVE render pixels — the window's 1x ceiling, before [CAP] is applied. Zero
   * when unknown (a hand-assembled crop), which makes the page fall back to a fixed-px window.
   */
  val natBoxW: Int = 0,
  /**
   * The native length of the axis [CAP] bounds — the largest edge for a content crop, the height
   * for a gutter crop. With [natBoxW] this is enough to re-derive the window's width for ANY cap,
   * which is what lets the stylesheet shrink it at a narrow viewport (`width = natBoxW * min(1, cap
   * / natCapAxis)`). Zero when unknown.
   */
  val natCapAxis: Int = 0,
)

/** Largest edge (px) a cropped thumbnail is scaled to — mirrors the static gallery's `cap`. */
private const val CAP = 240

private val TRANSLATE_RE = Regex("""translate\(\s*(-?\d+)\s*,\s*(-?\d+)\s*\)""")
private val VIEWBOX_RE = Regex("""viewBox="0 0 (\d+(?:\.\d+)?) (\d+(?:\.\d+)?)"""")

/**
 * The component's content box in the render's native pixel space, read from a figma-svg: crop
 * origin ([x],[y]) and size ([w]×[h]). The figma-svg is content-cropped — its root `viewBox` is the
 * box size and its root `<g transform="translate(tx,ty)">` places it, so the component's top-left
 * in the render is `(-tx, -ty)`. This is the *unscaled* box (native render pixels);
 * [computeThumbCrop] adds the display scaling on top, while the bundle PNG crop
 * ([ee.schimke.composeai.cli] `bundle split`) uses it at full resolution.
 */
data class SvgContentBox(val x: Int, val y: Int, val w: Int, val h: Int)

/**
 * Parse a figma-svg's content box (root `viewBox` size + `translate` origin) in render pixels, or
 * `null` when the svg carries no parseable `viewBox`. A missing `translate` places the box at the
 * origin.
 */
fun svgContentBox(svgText: String): SvgContentBox? {
  val vb = VIEWBOX_RE.find(svgText) ?: return null
  val w = vb.groupValues[1].toDouble()
  val h = vb.groupValues[2].toDouble()
  if (w <= 0.0 || h <= 0.0) return null
  val tr = TRANSLATE_RE.find(svgText)
  val tx = tr?.groupValues?.get(1)?.toInt() ?: 0
  val ty = tr?.groupValues?.get(2)?.toInt() ?: 0
  return SvgContentBox(x = -tx, y = -ty, w = w.roundToInt(), h = h.roundToInt())
}

/**
 * True when [box] is close enough to a [renderW]×[renderH] render that cropping to it is pointless
 * (a tight phone/desktop capture, or a full-screen Wear component whose box already fills the
 * canvas) — the shared "within 10% on both axes" no-op guard. Consumers that read a pre-cropped PNG
 * then find their box ≈ the image and no-op via this same test.
 */
fun contentBoxFillsRender(box: SvgContentBox, renderW: Int, renderH: Int): Boolean =
  box.w >= renderW * 0.9 && box.h >= renderH * 0.9

/** The smallest box covering both [this] and [other]. */
fun SvgContentBox.union(other: SvgContentBox): SvgContentBox {
  val x1 = min(x, other.x)
  val y1 = min(y, other.y)
  val x2 = max(x + w, other.x + other.w)
  val y2 = max(y + h, other.y + other.h)
  return SvgContentBox(x1, y1, x2 - x1, y2 - y1)
}

/** Clamp [this] to a [renderW]×[renderH] canvas (origin ≥ 0, extent within bounds). */
fun SvgContentBox.clampTo(renderW: Int, renderH: Int): SvgContentBox {
  val nx = x.coerceIn(0, renderW)
  val ny = y.coerceIn(0, renderH)
  return SvgContentBox(nx, ny, max(1, min(x + w, renderW) - nx), max(1, min(y + h, renderH) - ny))
}

/**
 * The tight bounding box of a PNG's **non-transparent** pixels (alpha ≥ [threshold]) in render
 * pixels, or `null` when the image can't be decoded or is fully transparent. This is the render's
 * *actual* drawn extent — including decorations the layout-derived figma box misses, like a focus
 * ring or disabled outline drawn **outside** the component's bounds. Unioning it into the crop box
 * (see [computeThumbCrop]) guarantees the crop never clips real pixels, self-correcting per variant
 * without needing a per-variant figma-svg.
 */
fun pngAlphaBounds(pngBytes: ByteArray, threshold: Int = 16): SvgContentBox? {
  val img = runCatching { ImageIO.read(ByteArrayInputStream(pngBytes)) }.getOrNull() ?: return null
  val w = img.width
  val h = img.height
  if (w <= 0 || h <= 0) return null
  var minX = w
  var minY = h
  var maxX = -1
  var maxY = -1
  for (y in 0 until h) {
    for (x in 0 until w) {
      if ((img.getRGB(x, y) ushr 24 and 0xff) < threshold) continue
      if (x < minX) minX = x
      if (y < minY) minY = y
      if (x > maxX) maxX = x
      if (y > maxY) maxY = y
    }
  }
  if (maxX < 0) return null // fully transparent
  return SvgContentBox(minX, minY, maxX - minX + 1, maxY - minY + 1)
}

/**
 * Compute the crop that frames the component box (read from [svgText]) within a [renderW]×[renderH]
 * render, or `null` when no crop is warranted: the svg has no parseable `viewBox`, the render
 * dimensions are unknown (`<= 0`), or the component box already nearly fills the render (a tight
 * phone/desktop capture — within 10% on both axes). [cap] bounds the displayed size.
 *
 * [contentBounds] (the render's actual non-transparent extent, from [pngAlphaBounds]) is
 * **unioned** into the figma box when supplied, so the crop never clips pixels the layout-derived
 * box misses — a focus ring / disabled outline drawn outside the component's bounds. It only ever
 * *grows* the box (clamped to the render), so a full-screen component whose box already fills the
 * canvas still trips the no-op guard and stays uncropped.
 */
/**
 * The crop that trims a declared `@CaptureGutter` off a [renderW]×[renderH] render, or `null` when
 * there is nothing to trim (no gutter, unknown render dimensions, or a gutter that would leave
 * nothing behind).
 *
 * Unlike [computeThumbCrop] this needs no vector and applies no "already close-cropped" guard: the
 * gutter is not inferred from the pixels, it is a fact the renderer recorded when it grew the
 * canvas for it. The remaining box is the component at exactly the size a gutter-less sibling
 * publishes, which is the whole point — a sheet fitting canvases to a column drew the guttered one
 * ~7% smaller until it could subtract this (m3-catalog#179).
 *
 * The edges are PHYSICAL — whoever published the record resolved the annotation's leading/trailing
 * against the direction the render was composed in, so there is no direction left to guess at here.
 */
fun computeGutterCrop(
  gutterLeft: Int,
  gutterTop: Int,
  gutterRight: Int,
  gutterBottom: Int,
  renderW: Int,
  renderH: Int,
  cap: Int = CAP,
): ContentCrop? {
  if (renderW <= 0 || renderH <= 0) return null
  val left = gutterLeft.coerceAtLeast(0)
  val top = gutterTop.coerceAtLeast(0)
  val right = gutterRight.coerceAtLeast(0)
  val bottom = gutterBottom.coerceAtLeast(0)
  if (left == 0 && top == 0 && right == 0 && bottom == 0) return null
  val boxW = renderW - left - right
  val boxH = renderH - top - bottom
  // A gutter wider than the render it was published against is a record that disagrees with its
  // own image; show the image whole rather than cropping to a guess.
  if (boxW <= 0 || boxH <= 0) return null
  // Capped on HEIGHT alone, unlike [computeThumbCrop]'s largest edge. The card beside this one is a
  // plain `<img>` bounded by the stylesheet's `max-height`, which scales an image on its height and
  // lets width follow; matching that rule is what keeps the two the same size in every column
  // width, and it is the whole point of this window. Capping the largest edge instead would shrink
  // a wide-but-short component (a 249x126 button) that no plain sibling shrinks — the same
  // mismatch this removes, one layer down. A window box carries `aspect-ratio`, so the cap cannot
  // live in CSS: constraining its height there squashes the box rather than scaling it.
  val scale = min(1.0, cap / boxH.toDouble())
  return ContentCrop(
    boxW = max(1, (boxW * scale).roundToInt()),
    boxH = max(1, (boxH * scale).roundToInt()),
    imgW = (renderW * scale).roundToInt(),
    imgH = (renderH * scale).roundToInt(),
    left = (-left * scale).roundToInt(),
    top = (-top * scale).roundToInt(),
    clip = false,
    natBoxW = boxW,
    natCapAxis = boxH,
  )
}

fun computeThumbCrop(
  svgText: String,
  renderW: Int,
  renderH: Int,
  contentBounds: SvgContentBox? = null,
  cap: Int = CAP,
): ContentCrop? {
  if (renderW <= 0 || renderH <= 0) return null
  val svgBox = svgContentBox(svgText) ?: return null
  val box = (contentBounds?.let { svgBox.union(it) } ?: svgBox).clampTo(renderW, renderH)
  // Already close-cropped (the render is tight to the component) → leave it untouched.
  if (contentBoxFillsRender(box, renderW, renderH)) return null
  // Don't upscale past 1× — a tiny component shows at its native pixels, not blown up.
  val scale = min(1.0, cap / max(box.w, box.h).toDouble())
  return ContentCrop(
    boxW = max(1, (box.w * scale).roundToInt()),
    boxH = max(1, (box.h * scale).roundToInt()),
    imgW = (renderW * scale).roundToInt(),
    imgH = (renderH * scale).roundToInt(),
    // `left`/`top` are the render's offset under the clip window: negative of the box origin.
    left = (-box.x * scale).roundToInt(),
    top = (-box.y * scale).roundToInt(),
    natBoxW = box.w,
    natCapAxis = max(box.w, box.h),
  )
}
