package ee.schimke.composeai.cli.serve

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

/**
 * Compute the crop that frames the component box (read from [svgText]) within a [renderW]×[renderH]
 * render, or `null` when no crop is warranted: the svg has no parseable `viewBox`, the render
 * dimensions are unknown (`<= 0`), or the component box already nearly fills the render (a tight
 * phone/desktop capture — within 10% on both axes). [cap] bounds the displayed size.
 */
fun computeThumbCrop(svgText: String, renderW: Int, renderH: Int, cap: Int = CAP): ContentCrop? {
  if (renderW <= 0 || renderH <= 0) return null
  val box = svgContentBox(svgText) ?: return null
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
  )
}
