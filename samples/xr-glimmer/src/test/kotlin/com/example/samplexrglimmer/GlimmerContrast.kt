package com.example.samplexrglimmer

import java.awt.image.BufferedImage

/**
 * Calibration helpers that encode the two quantitative rules Android Studio's Glimmer preview
 * pane exists to check, so our previews can be measured against them instead of merely *looking*
 * additive. Both numbers come straight from Google's public Glimmer guidance:
 *
 *  - **Contrast.** The official skill (`android/skills` →
 *    `xr/display-glasses-with-jetpack-compose-glimmer`) mandates *"at least a 70% tone difference
 *    between foreground and background using the HCT color space."* HCT's **T** (tone) channel is
 *    *defined* as CIELAB **L\*** (identical 0–100 scale), so "70% tone difference" is `ΔL* ≥ 70`.
 *    [tone] computes L\* from an sRGB pixel; [STUDIO_MIN_TONE_DIFFERENCE] is the 70 bar.
 *  - **Angular sizing.** The type guidance
 *    (developer.android.com/design/ui/ai-glasses/guides/styles/type) pins the display at
 *    **30 pixels-per-degree** and a minimum readable text size of **0.6° = 18px** (restated as
 *    18sp in the skill). [PIXELS_PER_DEGREE], [MIN_TEXT_ANGLE_DEGREES] and [minReadableTextPx]
 *    capture that; they also justify the density-1.0 calibration of `AI_GLASSES_DEVICE_SPEC`
 *    (the 18sp == 18px == 0.6° identity only holds at density 1.0).
 *
 * Additive-display physics: a real glasses display can only *add* light. The preview reproduces
 * that with `BlendMode.Plus` over the env backdrop ([additivePlus]); white UI light added onto any
 * background clamps to white (L\* 100), so the legibility question is the tone gap between the
 * white text and the **panel** — the env pixel with Glimmer's translucent `surface` tint added on
 * top ([GLIMMER_SURFACE]). On true black (additive-zero) the panel stays dark and the gap is wide;
 * on a bright/busy backdrop the panel rides up toward white and the gap collapses.
 */
internal object GlimmerContrast {

  /** Studio's contrast bar: ≥70% HCT tone difference (== ΔL\* ≥ 70). */
  const val STUDIO_MIN_TONE_DIFFERENCE: Double = 70.0

  /** Glimmer display calibration: 30 pixels per visual degree. */
  const val PIXELS_PER_DEGREE: Double = 30.0

  /** Minimum readable text angle; 0.6° × 30 PPD == 18px == 18sp at density 1.0. */
  const val MIN_TEXT_ANGLE_DEGREES: Double = 0.6

  /** Glimmer `surface` token (#262626) — the translucent tint added behind list content. */
  val GLIMMER_SURFACE: Int = 0xFF262626.toInt()

  /** Best-case Glimmer content/text: full white light. Even this fails on busy backdrops. */
  val GLIMMER_TEXT: Int = 0xFFFFFFFF.toInt()

  /** Minimum readable text size in px for a given PPD — `angle × PPD`. */
  fun minReadableTextPx(angleDegrees: Double = MIN_TEXT_ANGLE_DEGREES): Double =
    angleDegrees * PIXELS_PER_DEGREE

  /** `BlendMode.Plus`: per-channel sum clamped to 255 — the op an additive display performs. */
  fun additivePlus(bg: Int, ui: Int): Int {
    val r = (((bg ushr 16) and 0xFF) + ((ui ushr 16) and 0xFF)).coerceAtMost(255)
    val g = (((bg ushr 8) and 0xFF) + ((ui ushr 8) and 0xFF)).coerceAtMost(255)
    val b = ((bg and 0xFF) + (ui and 0xFF)).coerceAtMost(255)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
  }

  /** HCT tone (== CIELAB L\*, 0–100) of an sRGB pixel. */
  fun tone(argb: Int): Double {
    val r = linear((argb ushr 16) and 0xFF)
    val g = linear((argb ushr 8) and 0xFF)
    val b = linear(argb and 0xFF)
    val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
    val f = if (y > 0.008856) Math.cbrt(y) else 7.787 * y + 16.0 / 116.0
    return 116.0 * f - 16.0
  }

  /** |ΔTone| between two sRGB pixels. */
  fun toneDifference(fg: Int, bg: Int): Double = Math.abs(tone(fg) - tone(bg))

  /**
   * Mean legibility tone-gap of white Glimmer text over [backdrop]: average, across every pixel,
   * of the tone difference between the white text (additive-clamped to L\* 100) and the local
   * panel (backdrop pixel + [GLIMMER_SURFACE] added). Higher = more readable;
   * [STUDIO_MIN_TONE_DIFFERENCE] is the pass bar.
   */
  fun meanTextToneGap(backdrop: BufferedImage): Double {
    var sum = 0.0
    var n = 0L
    for (y in 0 until backdrop.height) {
      for (x in 0 until backdrop.width) {
        val bg = backdrop.getRGB(x, y)
        sum += toneDifference(additivePlus(bg, GLIMMER_TEXT), additivePlus(bg, GLIMMER_SURFACE))
        n++
      }
    }
    return sum / n
  }

  /** Additive-zero (pure black) baseline gap: white text over the bare `surface` tint. */
  fun additiveZeroToneGap(): Double {
    val black = 0xFF000000.toInt()
    val text = additivePlus(black, GLIMMER_TEXT)
    val panel = additivePlus(black, GLIMMER_SURFACE)
    return toneDifference(text, panel)
  }

  private fun linear(c8: Int): Double {
    val c = c8 / 255.0
    return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
  }
}
