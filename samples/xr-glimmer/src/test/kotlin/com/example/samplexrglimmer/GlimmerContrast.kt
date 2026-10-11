package com.example.samplexrglimmer

import java.awt.image.BufferedImage

/**
 * Calibration helpers for the two quantitative rules Studio's Glimmer preview pane checks, from
 * Google's Glimmer guidance:
 *
 * - Contrast: "at least a 70% tone difference between foreground and background using the HCT
 *   color space"; HCT tone is CIELAB L\*, so `ΔL* ≥ 70` ([tone], [STUDIO_MIN_TONE_DIFFERENCE]).
 * - Angular sizing: 30 pixels-per-degree and a 0.6° = 18px minimum text size
 *   (developer.android.com/design/ui/ai-glasses/guides/styles/type) — [PIXELS_PER_DEGREE],
 *   [MIN_TEXT_ANGLE_DEGREES], [minReadableTextPx]; why `AI_GLASSES_DEVICE_SPEC` uses density 1.0.
 *
 * An additive display only adds light ([additivePlus]), so white text clamps to L\* 100 and
 * legibility is the gap to the panel: the backdrop plus Glimmer's translucent [GLIMMER_SURFACE]. On
 * black the gap is wide; on a bright backdrop it collapses.
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
   * Mean tone gap between white text (L\* 100) and the local panel (backdrop + [GLIMMER_SURFACE])
   * across [backdrop]; higher is more readable, [STUDIO_MIN_TONE_DIFFERENCE] is the bar.
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
