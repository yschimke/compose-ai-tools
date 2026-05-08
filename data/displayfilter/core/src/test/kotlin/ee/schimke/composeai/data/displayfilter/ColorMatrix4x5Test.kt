package ee.schimke.composeai.data.displayfilter

import org.junit.Assert.assertEquals
import org.junit.Test

class ColorMatrix4x5Test {

  @Test
  fun identityLeavesAllChannelsUnchanged() {
    for (argb in samplePixels()) {
      assertEquals(argb, ColorMatrix4x5.Identity.applyToArgb(argb))
    }
  }

  @Test
  fun grayscaleCollapsesPureRedGreenBlueToTheirRec709Luma() {
    val matrix = DisplayFilter.Grayscale.matrix
    // Red (255,0,0) -> luma = 255 * 0.2126 = ~54
    val red = matrix.applyToArgb(0xFFFF0000.toInt())
    assertChannelsEqual(red, alpha = 0xFF, r = 54, g = 54, b = 54)
    // Green (0,255,0) -> luma = 255 * 0.7152 = ~182
    val green = matrix.applyToArgb(0xFF00FF00.toInt())
    assertChannelsEqual(green, alpha = 0xFF, r = 182, g = 182, b = 182)
    // Blue (0,0,255) -> luma = 255 * 0.0722 = ~18
    val blue = matrix.applyToArgb(0xFF0000FF.toInt())
    assertChannelsEqual(blue, alpha = 0xFF, r = 18, g = 18, b = 18)
  }

  @Test
  fun grayscalePreservesAlphaAndPureWhiteAndBlack() {
    val matrix = DisplayFilter.Grayscale.matrix
    assertChannelsEqual(
      matrix.applyToArgb(0xFFFFFFFF.toInt()),
      alpha = 0xFF,
      r = 255,
      g = 255,
      b = 255,
    )
    assertChannelsEqual(matrix.applyToArgb(0xFF000000.toInt()), alpha = 0xFF, r = 0, g = 0, b = 0)
    // Half-alpha grey stays half-alpha grey.
    val translucent = matrix.applyToArgb(0x80808080.toInt())
    assertChannelsEqual(translucent, alpha = 0x80, r = 128, g = 128, b = 128)
  }

  @Test
  fun invertNegatesRgbAndKeepsAlpha() {
    val matrix = DisplayFilter.Invert.matrix
    assertChannelsEqual(matrix.applyToArgb(0xFF000000.toInt()), 0xFF, 255, 255, 255)
    assertChannelsEqual(matrix.applyToArgb(0xFFFFFFFF.toInt()), 0xFF, 0, 0, 0)
    assertChannelsEqual(
      matrix.applyToArgb(0x80123456.toInt()),
      0x80,
      0xFF - 0x12,
      0xFF - 0x34,
      0xFF - 0x56,
    )
  }

  @Test
  fun outputClampsToZeroAnd255() {
    // Matrix that doubles every channel; pure white should stay 255, not roll over.
    val doubler =
      ColorMatrix4x5.ofRows(
        red = floatArrayOf(2f, 0f, 0f, 0f, 0f),
        green = floatArrayOf(0f, 2f, 0f, 0f, 0f),
        blue = floatArrayOf(0f, 0f, 2f, 0f, 0f),
        alpha = floatArrayOf(0f, 0f, 0f, 1f, 0f),
      )
    assertChannelsEqual(doubler.applyToArgb(0xFFFFFFFF.toInt()), 0xFF, 255, 255, 255)
    // Negative-everything matrix; midgrey (127,127,127) stays 0 even with -10 scaling, not wrapped.
    val negator =
      ColorMatrix4x5.ofRows(
        red = floatArrayOf(-10f, 0f, 0f, 0f, 0f),
        green = floatArrayOf(0f, -10f, 0f, 0f, 0f),
        blue = floatArrayOf(0f, 0f, -10f, 0f, 0f),
        alpha = floatArrayOf(0f, 0f, 0f, 1f, 0f),
      )
    assertChannelsEqual(negator.applyToArgb(0xFF7F7F7F.toInt()), 0xFF, 0, 0, 0)
  }

  @Test
  fun deuteranopiaCollapsesGreenIntoRedYellowAxis() {
    // Sanity check: pure green under deuteranopia should desaturate (M cone missing) but NOT
    // become identical to pure red. Just assert it shifts away from pure green.
    val green = DisplayFilter.DeuteranopiaSimulation.matrix.applyToArgb(0xFF00FF00.toInt())
    val r = (green ushr 16) and 0xFF
    val g = (green ushr 8) and 0xFF
    val b = green and 0xFF
    // Red channel should be substantial (Machado coefficient ~0.86 * 255 = ~219)
    assert(r in 200..235) { "Expected R ~219 under deuteranopia, got $r" }
    // Green channel reduced, not zero
    assert(g in 150..200) { "Expected G ~172 under deuteranopia, got $g" }
    // Blue lifts slightly above zero
    assert(b in 0..30) { "Expected small B under deuteranopia, got $b" }
  }

  private fun samplePixels(): IntArray =
    intArrayOf(
      0xFF000000.toInt(),
      0xFFFFFFFF.toInt(),
      0xFFFF0000.toInt(),
      0xFF00FF00.toInt(),
      0xFF0000FF.toInt(),
      0x80123456.toInt(),
      0x00FFFFFF,
    )

  private fun assertChannelsEqual(argb: Int, alpha: Int, r: Int, g: Int, b: Int) {
    assertEquals("alpha", alpha, (argb ushr 24) and 0xFF)
    assertEquals("red", r, (argb ushr 16) and 0xFF)
    assertEquals("green", g, (argb ushr 8) and 0xFF)
    assertEquals("blue", b, argb and 0xFF)
  }
}
