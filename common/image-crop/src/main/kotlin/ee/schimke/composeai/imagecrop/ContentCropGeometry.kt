package ee.schimke.composeai.imagecrop

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Server-side thumbnail content-crop for the `serve` catalog pages. A Wear sticker draws a small
// component on a 454×454 canvas, so the card clips the PNG to the component box read from the
// catalog's content-cropped figma-svg (root `viewBox` + `translate`). Same maths as the static
// gallery's client crop (`scripts/design-artifacts/render-index-html.mjs`), computed once at page
// build. Tight phone / desktop renders are left alone.

/**
 * The clip window's size, in output pixels. Window, render and offset are distinct types so a
 * transposed argument in [ContentCrop] is a compile error rather than a silently wrong crop.
 */
public data class WindowSize(val w: Int, val h: Int)

/** The full render's size at the window's scale, in the crop's output pixels. See [WindowSize]. */
public data class RenderSize(val w: Int, val h: Int)

/**
 * Where the render sits under the clip window, in output pixels; normally negative on both axes.
 */
public data class CropOffset(val left: Int, val top: Int)

/** A clip window over a render, and the render's position under it. */
public data class ContentCrop(
  /** Clip-window size — the component box scaled to fit [CAP]. */
  val window: WindowSize,
  /** The full render `<img>` size at the same scale; larger than the window, clipped by it. */
  val render: RenderSize,
  /** The shift that brings the component's top-left to the window origin. */
  val offset: CropOffset,
  /**
   * Whether what falls outside the window is hidden: true for a content crop, false for a gutter
   * crop, whose overflow is the component's own shadow or focus ring (m3-catalog#102, #179).
   */
  val clip: Boolean = true,
  /** The window width in native render pixels, before [CAP]; zero when unknown. */
  val nativeWindowW: Int = 0,
  /**
   * The native length of the axis [CAP] bounds, so the stylesheet can re-derive the width for any
   * cap (`nativeWindowW * min(1, cap / nativeCapAxis)`). Zero when unknown.
   */
  val nativeCapAxis: Int = 0,
)

/** Largest edge (px) a cropped thumbnail is scaled to — mirrors the static gallery's `cap`. */
private const val CAP = 240

private val TRANSLATE_RE = Regex("""translate\(\s*(-?\d+)\s*,\s*(-?\d+)\s*\)""")
private val VIEWBOX_RE = Regex("""viewBox="0 0 (\d+(?:\.\d+)?) (\d+(?:\.\d+)?)"""")

/**
 * A content box in native render pixels: origin ([x],[y]) and size ([w]×[h]). Produced from a
 * figma-svg ([svgContentBox]) or a PNG's drawn extent ([pngAlphaBounds]); [computeThumbCrop] adds
 * display scaling, while `bundle split` uses it as-is.
 */
public data class ContentBox(val x: Int, val y: Int, val w: Int, val h: Int)

/**
 * Parse a figma-svg's content box (root `viewBox` size + `translate` origin) in render pixels, or
 * `null` when the svg carries no parseable `viewBox`. A missing `translate` places the box at the
 * origin.
 */
public fun svgContentBox(svgText: String): ContentBox? {
  val vb = VIEWBOX_RE.find(svgText) ?: return null
  val w = vb.groupValues[1].toDouble()
  val h = vb.groupValues[2].toDouble()
  if (w <= 0.0 || h <= 0.0) return null
  val tr = TRANSLATE_RE.find(svgText)
  val tx = tr?.groupValues?.get(1)?.toInt() ?: 0
  val ty = tr?.groupValues?.get(2)?.toInt() ?: 0
  return ContentBox(x = -tx, y = -ty, w = w.roundToInt(), h = h.roundToInt())
}

/** True when [box] is within 10% of the render on both axes, so cropping to it is pointless. */
public fun contentBoxFillsRender(box: ContentBox, renderW: Int, renderH: Int): Boolean =
  box.w >= renderW * 0.9 && box.h >= renderH * 0.9

/** The smallest box covering both [this] and [other]. */
public fun ContentBox.union(other: ContentBox): ContentBox {
  val x1 = min(x, other.x)
  val y1 = min(y, other.y)
  val x2 = max(x + w, other.x + other.w)
  val y2 = max(y + h, other.y + other.h)
  return ContentBox(x1, y1, x2 - x1, y2 - y1)
}

/** Clamp [this] to a [renderW]×[renderH] canvas (origin ≥ 0, extent within bounds). */
public fun ContentBox.clampTo(renderW: Int, renderH: Int): ContentBox {
  val nx = x.coerceIn(0, renderW)
  val ny = y.coerceIn(0, renderH)
  return ContentBox(nx, ny, max(1, min(x + w, renderW) - nx), max(1, min(y + h, renderH) - ny))
}

/**
 * The bounding box of a PNG's pixels with alpha ≥ [threshold], or `null` when undecodable or fully
 * transparent. Unioned into the crop box so decorations drawn outside the layout bounds (a focus
 * ring) are never clipped.
 */
public fun pngAlphaBounds(pngBytes: ByteArray, threshold: Int = 16): ContentBox? {
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
  return ContentBox(minX, minY, maxX - minX + 1, maxY - minY + 1)
}

/**
 * The crop that trims a declared `@CaptureGutter` (physical edges, as recorded by the renderer) off
 * a render, or `null` when there is nothing to trim. Leaves the component at the size a gutter-less
 * sibling publishes (m3-catalog#179).
 */
public fun computeGutterCrop(
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
  // A gutter that disagrees with its own image: show the image whole.
  if (boxW <= 0 || boxH <= 0) return null
  // Capped on height, not the largest edge, to match how the stylesheet's `max-height` scales a
  // plain sibling `<img>`. Can't live in CSS: the window carries `aspect-ratio`.
  val scale = min(1.0, cap / boxH.toDouble())
  return ContentCrop(
    window =
      WindowSize(w = max(1, (boxW * scale).roundToInt()), h = max(1, (boxH * scale).roundToInt())),
    render = RenderSize(w = (renderW * scale).roundToInt(), h = (renderH * scale).roundToInt()),
    offset = CropOffset(left = (-left * scale).roundToInt(), top = (-top * scale).roundToInt()),
    clip = false,
    nativeWindowW = boxW,
    nativeCapAxis = boxH,
  )
}

/**
 * The crop that frames the figma-svg component box within a [renderW]×[renderH] render, or `null`
 * when the svg has no `viewBox`, the dimensions are unknown, or the box already nearly fills the
 * render. [contentBounds] ([pngAlphaBounds]) is unioned in, so the crop only ever grows. [cap]
 * bounds the displayed size.
 */
public fun computeThumbCrop(
  svgText: String,
  renderW: Int,
  renderH: Int,
  contentBounds: ContentBox? = null,
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
    window =
      WindowSize(
        w = max(1, (box.w * scale).roundToInt()),
        h = max(1, (box.h * scale).roundToInt()),
      ),
    render = RenderSize(w = (renderW * scale).roundToInt(), h = (renderH * scale).roundToInt()),
    // The offset is the render's position under the clip window: negative of the box origin.
    offset = CropOffset(left = (-box.x * scale).roundToInt(), top = (-box.y * scale).roundToInt()),
    nativeWindowW = box.w,
    nativeCapAxis = max(box.w, box.h),
  )
}
