package ee.schimke.composeai.data.displayfilter

/**
 * Catalogue of post-process color matrices applied to already-captured PNGs. These approximate the
 * same transforms Android's `DisplayTransformManager` uses for accessibility / digital-wellbeing
 * display effects, but applied per-PNG instead of system-wide.
 *
 * Each entry has a stable [id] used as the on-disk filename suffix and as the protocol kind
 * (`displayfilter/<id>`).
 */
enum class DisplayFilter(val id: String, val matrix: ColorMatrix4x5) {

  /**
   * Grayscale / "bedtime mode" — Android's Digital Wellbeing schedule applies a saturation = 0
   * color matrix at the display layer using Rec.709 luminance weights (R * 0.2126 + G * 0.7152 +
   * B * 0.0722). Same weights `android.graphics.ColorMatrix` uses for `setSaturation(0f)`.
   */
  Grayscale(
    id = "grayscale",
    matrix =
      ColorMatrix4x5.ofRows(
        red = floatArrayOf(0.2126f, 0.7152f, 0.0722f, 0f, 0f),
        green = floatArrayOf(0.2126f, 0.7152f, 0.0722f, 0f, 0f),
        blue = floatArrayOf(0.2126f, 0.7152f, 0.0722f, 0f, 0f),
        alpha = floatArrayOf(0f, 0f, 0f, 1f, 0f),
      ),
  ),

  /**
   * Classic color inversion (negate RGB, preserve alpha). Matches Android's "Color inversion"
   * accessibility setting prior to the Smart Invert refinements.
   */
  Invert(
    id = "invert",
    matrix =
      ColorMatrix4x5.ofRows(
        red = floatArrayOf(-1f, 0f, 0f, 0f, 255f),
        green = floatArrayOf(0f, -1f, 0f, 0f, 255f),
        blue = floatArrayOf(0f, 0f, -1f, 0f, 255f),
        alpha = floatArrayOf(0f, 0f, 0f, 1f, 0f),
      ),
  ),

  /**
   * Deuteranopia *simulation* — what a viewer with no functioning M (green) cones would see.
   * Coefficients from Machado, Oliveira & Fernandes (2009), "A Physiologically-based Model for
   * Simulation of Color Vision Deficiency", at severity 1.0. Most common form (~6% of males); a
   * useful default for designers checking that hue isn't carrying signal alone.
   *
   * Note: this is *simulation*, not *correction*. The Android color-correction setting applies a
   * different (error-shifted) matrix that compensates for the deficiency; that one belongs in the
   * a11y bag, not here.
   */
  DeuteranopiaSimulation(
    id = "deuteranopia",
    matrix =
      ColorMatrix4x5.ofRows(
        red = floatArrayOf(0.367322f, 0.860646f, -0.227968f, 0f, 0f),
        green = floatArrayOf(0.280085f, 0.672501f, 0.047413f, 0f, 0f),
        blue = floatArrayOf(-0.011820f, 0.042940f, 0.968881f, 0f, 0f),
        alpha = floatArrayOf(0f, 0f, 0f, 1f, 0f),
      ),
  ),

  /** Protanopia simulation (no L / red cones). Machado 2009, severity 1.0. */
  ProtanopiaSimulation(
    id = "protanopia",
    matrix =
      ColorMatrix4x5.ofRows(
        red = floatArrayOf(0.152286f, 1.052583f, -0.204868f, 0f, 0f),
        green = floatArrayOf(0.114503f, 0.786281f, 0.099216f, 0f, 0f),
        blue = floatArrayOf(-0.003882f, -0.048116f, 1.051998f, 0f, 0f),
        alpha = floatArrayOf(0f, 0f, 0f, 1f, 0f),
      ),
  ),

  /** Tritanopia simulation (no S / blue cones). Machado 2009, severity 1.0. */
  TritanopiaSimulation(
    id = "tritanopia",
    matrix =
      ColorMatrix4x5.ofRows(
        red = floatArrayOf(1.255528f, -0.076749f, -0.178779f, 0f, 0f),
        green = floatArrayOf(-0.078411f, 0.930809f, 0.147602f, 0f, 0f),
        blue = floatArrayOf(0.004733f, 0.691367f, 0.303900f, 0f, 0f),
        alpha = floatArrayOf(0f, 0f, 0f, 1f, 0f),
      ),
  );

  companion object {
    fun fromId(id: String): DisplayFilter? = entries.firstOrNull { it.id == id }
  }
}
