package ee.schimke.composeai.data.render

import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.sqrt

/**
 * The **device-frame clip**: the region of a capture that is actually screen.
 *
 * ### Why this is not the same question as the capture's alpha
 *
 * A round Wear render is a circle in a square PNG, and the corners are transparent. That much is
 * already true by the time anyone looks at one — [ee.schimke.composeai.daemon.DeviceClipExtension]
 * wraps the composition in the clip *before* the device background paints, so the background is
 * circular too. But transparency alone does not say **why** a pixel is clear, and the two reasons
 * need opposite treatment:
 *
 * - A `showBackground = false` **sticker** is transparent because the alpha is the product. A
 *   consumer should put a [PreviewBackdrop] behind all of it, edge to edge, so the artwork reads.
 * - A round device's **corners** are transparent because there is no screen there. Putting a ground
 *   behind those is a lie about the hardware: it draws the watch as a square.
 *
 * Nothing downstream could tell those apart, so every surface treated the second as the first. The
 * cost is worst exactly where it is least visible: a Wear catalog declares black backgrounds, so a
 * black round screen was composited onto a black square stage and the device boundary disappeared
 * completely — measured pixel-identical on three of this repo's own Wear templates.
 *
 * ### Why a shape rather than a boolean
 *
 * `isRound` alone forces every consumer to re-derive the circle from whatever dimensions it happens
 * to hold, in its own units, and they will not agree — the same class of drift [PreviewBackdrop]
 * exists to end. The shape is resolved once, in dp, and each consumer maps it into its own space.
 *
 * Everything here is in **dp**, the space `@Preview` states its device in. A consumer showing a
 * capture at some other scale maps the shape by the same factor it maps the image.
 */
public object PreviewClip {

  /** The region of the capture that is screen. */
  public sealed interface Shape {

    /**
     * A round watch face.
     *
     * Carried as centre-and-radius rather than as a bounding box because that is what both
     * consumers need — a CSS `circle()` and a canvas `arc()` — and because a square device whose
     * width and height differ by a rounding step should still clip to one circle rather than to an
     * ellipse. [radiusDp] is therefore half the SHORTER side.
     */
    public data class Circle(
      public val centerXDp: Double,
      public val centerYDp: Double,
      public val radiusDp: Double,
    ) : Shape
  }

  /**
   * The clip for one preview, or null when the whole capture is screen.
   *
   * Null is the overwhelmingly common answer — every phone, desktop and sticker preview — and it
   * means "do not clip", not "unknown". A caller that cannot supply dimensions gets null too: a
   * round device whose size never resolved is not a reason to invent a circle, and the un-clipped
   * square is exactly the behaviour every consumer had before this existed.
   *
   * @param isRound whether the resolved device is a round one.
   * @param widthDp the device width in dp, when known.
   * @param heightDp the device height in dp, when known.
   */
  public fun resolve(isRound: Boolean, widthDp: Double?, heightDp: Double?): Shape? {
    if (!isRound || widthDp == null || heightDp == null) return null
    if (widthDp <= 0.0 || heightDp <= 0.0) return null
    return Shape.Circle(
      centerXDp = widthDp / 2.0,
      centerYDp = heightDp / 2.0,
      radiusDp = minOf(widthDp, heightDp) / 2.0,
    )
  }

  /**
   * The clip as a CSS `clip-path` value, sized to a box of [boxWidthDp] × [boxHeightDp].
   *
   * Percentages rather than pixels: the surfaces that show a capture scale it to fit their own
   * stage — a thumbnail, a compare panel, a zoomed viewer — and a percentage clip rides that scale
   * for free, where a px radius resolved at publish time would be right at exactly one zoom level.
   *
   * The box is passed in rather than taken from the shape because a consumer may letterbox: the
   * element being clipped is the stage, whose aspect need not be the device's.
   */
  public fun cssClipPath(shape: Shape, boxWidthDp: Double, boxHeightDp: Double): String? {
    if (boxWidthDp <= 0.0 || boxHeightDp <= 0.0) return null
    return when (shape) {
      is Shape.Circle -> {
        // `closest-side` would be the obvious spelling and is a trap: it hugs the ELEMENT, so a
        // stage with any padding — or one letterboxing a non-square device — would clip to the
        // stage's inscribed circle rather than to the watch face. The radius is stated outright
        // instead, through the percentage basis CSS actually defines for a circle: a percentage
        // resolves against `sqrt(w² + h²) / sqrt(2)`, not against either side. Get that wrong and
        // the clip is subtly too big on every non-square box, which reads as a slightly cropped
        // bezel rather than as a bug.
        val basis = hypot(boxWidthDp, boxHeightDp) / sqrt(2.0)
        if (basis <= 0.0) return null
        val r = percent(shape.radiusDp / basis)
        val cx = percent(shape.centerXDp / boxWidthDp)
        val cy = percent(shape.centerYDp / boxHeightDp)
        "circle($r at $cx $cy)"
      }
    }
  }

  /** A 0–1 fraction as a CSS percentage, trimmed so the common half-way case reads `50%`. */
  private fun percent(fraction: Double): String {
    val rounded = round(fraction * 100.0 * 100.0) / 100.0
    return if (rounded == floor(rounded)) "${rounded.toInt()}%" else "$rounded%"
  }
}
