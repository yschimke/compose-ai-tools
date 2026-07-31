package com.example.samplecmp

import com.google.common.truth.Truth.assertThat
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Test

/**
 * Assertion half of the desktop preview-mode matrix (issue #3082) — the `ImageComposeScene`
 * counterpart of `:samples:android`'s `PreviewModeMatrixTest`.
 *
 * The expected sizes below are **the same numbers the Android test asserts**, and that is the
 * point: a `@Preview` shaped a given way resolves to one dp frame, so both backends must land on
 * the same pixel canvas even though one applies it through Robolectric resource qualifiers and the
 * other through `Density` on a Skia scene. Any drift between the two renderers — or away from
 * Android Studio, whose catalog these numbers come from — fails here.
 *
 * The module's `test` task depends on `composePreviewRenderAll` (see this sample's
 * `build.gradle.kts`), so `:samples:cmp:check` renders before asserting.
 */
class PreviewModeMatrixTest {

  private val rendersDir = File("build/compose-previews/renders")

  /**
   * `functionName_sanitisedPreviewName` → expected PNG size in pixels, as `dp × density` from
   * Studio's device catalog. A fixed axis is `floor(dp × density)`; a wrapped axis is the
   * composable's measured size.
   */
  private val expectedSizes =
    mapOf(
      // Wrapped both axes: the probe's 160×80dp at Studio's 2.625× default.
      "MatrixComponentWrapPreview_Component_wrap" to (420 to 210),
      // 200×100dp fixed.
      "MatrixFixedBothAxesPreview_Fixed_both_axes" to (525 to 262),
      // 240dp fixed width, wrapped height.
      "MatrixFixedWidthOnlyPreview_Fixed_width_only" to (630 to 210),
      // 120×60dp fixed.
      "MatrixBackgroundColorPreview_Background_colour" to (315 to 157),
      // uiMode / fontScale / locale change what is drawn, never the canvas.
      "MatrixLightPreview_Day" to (525 to 262),
      "MatrixNightPreview_Night" to (525 to 262),
      "MatrixFontScalePreview_Font_scale_2x" to (525 to 262),
      "MatrixLocalePreview_German" to (525 to 262),
      // Pixel 5: 393×851dp @2.75×.
      "MatrixPhoneDevicePreview_Phone" to (1080 to 2340),
      // Wear small round: 192×192dp @2.0×.
      "MatrixWearDevicePreview_Wear" to (384 to 384),
      // Pixel Fold, natural orientation: 841×701dp @2.625×.
      "MatrixFoldableDevicePreview_Foldable" to (2207 to 1840),
      // spec: grammar with dpi=320 → 360×640dp @2.0×.
      "MatrixDeviceSpecPreview_Device_spec" to (720 to 1280),
    )

  @Test
  fun `every preview mode renders at the size Android Studio resolves`() {
    val mismatches = expectedSizes.mapNotNull { (stem, expected) ->
      val file = File(rendersDir, "$stem.png")
      if (!file.exists()) return@mapNotNull "$stem: no PNG rendered"
      val img = ImageIO.read(file) ?: return@mapNotNull "$stem: unreadable PNG"
      val actual = img.width to img.height
      if (actual == expected) null
      else "$stem: expected ${expected.pretty()}, got ${actual.pretty()}"
    }
    assertThat(mismatches).isEmpty()
  }

  @Test
  fun `uiMode night renders darker pixels than its day sibling`() {
    // Desktop applies uiMode through LocalSystemTheme rather than a resource qualifier; the
    // observable outcome must be the same.
    val day = readRender("MatrixLightPreview_Day")
    val night = readRender("MatrixNightPreview_Night")
    assertThat(meanLuminance(night)).isLessThan(meanLuminance(day))
  }

  @Test
  fun `backgroundColor paints the declared colour behind the composable`() {
    val img = readRender("MatrixBackgroundColorPreview_Background_colour")
    listOf(0 to 0, img.width - 1 to 0, 0 to img.height - 1, img.width - 1 to img.height - 1)
      .forEach { (x, y) ->
        val argb = img.getRGB(x, y)
        assertThat((argb ushr 24) and 0xff).isEqualTo(0xff)
        assertThat(argb and 0xffffff).isEqualTo(0x00FF00)
      }
  }

  @Test
  fun `multipreview annotations fan out to one render each`() {
    val fanOut =
      mapOf(
        "MatrixLightDarkMultiPreview" to listOf("Dark", "Light"),
        "MatrixFontScaleMultiPreview" to listOf("100", "115", "130", "150", "180", "200", "85"),
        "MatrixMetaAnnotationMultiPreview" to listOf("Meta_phone", "Meta_watch"),
      )
    fanOut.forEach { (function, expected) ->
      val renders = rendersFor(function)
      assertThat(renders.map { it.name.removePrefix("${function}_").removeSuffix(".png") })
        .containsExactlyElementsIn(expected)
        .inOrder()
      renders.forEach { file ->
        val img = ImageIO.read(file)
        assertThat(img).isNotNull()
        assertThat(img.width).isGreaterThan(0)
        assertThat(img.height).isGreaterThan(0)
      }
    }
  }

  @Test
  fun `mixed fixed-wrap showBackground leaves the fixed axis unpainted - issue 3092`() {
    // Known desktop/Android divergence the matrix surfaced: with one axis fixed and the other
    // wrapping, `showBackground = true` only covers the *measured content*, so the rest of the
    // fixed frame stays transparent. Android (and Studio) fill the whole frame — its sibling test
    // asserts the opaque-white corner for the same fixture.
    //
    // Pinned to the current pixel deliberately, as a change detector: when #3092 lands this test
    // fails and gets flipped to match the Android expectation, rather than the fix quietly
    // changing rendered output nobody was watching.
    val img = readRender("MatrixFixedWidthOnlyPreview_Fixed_width_only")
    val alphaAtFixedEdge = (img.getRGB(img.width - 2, 2) ushr 24) and 0xff
    assertThat(alphaAtFixedEdge).isEqualTo(0)
  }

  @Test
  fun `PreviewLightDark fans out into two visibly different captures`() {
    // A fan-out that renders the same pixels twice would still satisfy a filename count, so pin the
    // outcome: the probe colours itself from `isSystemInDarkTheme()`, and the dark entry must land
    // darker. This is what proves each nested @Preview's params reach the render, not just its
    // name.
    val light = readRender("MatrixLightDarkMultiPreview_Light")
    val dark = readRender("MatrixLightDarkMultiPreview_Dark")
    assertThat(meanLuminance(dark)).isLessThan(meanLuminance(light))
  }

  @Test
  fun `the app-declared meta-annotation resolves each nested device independently`() {
    val sizes =
      rendersFor("MatrixMetaAnnotationMultiPreview")
        .map { ImageIO.read(it) }
        .map { it.width to it.height }
    assertThat(sizes).containsExactly(1080 to 2340, 384 to 384)
  }

  private fun rendersFor(functionName: String): List<File> =
    (rendersDir.listFiles() ?: emptyArray())
      .filter { it.name.startsWith("${functionName}_") && it.name.endsWith(".png") }
      .sortedBy { it.name }

  private fun readRender(stem: String): BufferedImage {
    val file = File(rendersDir, "$stem.png")
    assertThat(file.exists()).isTrue()
    return ImageIO.read(file)
  }

  private fun meanLuminance(img: BufferedImage): Double {
    var total = 0.0
    var count = 0
    for (y in 0 until img.height step 4) {
      for (x in 0 until img.width step 4) {
        val argb = img.getRGB(x, y)
        val r = (argb shr 16) and 0xff
        val g = (argb shr 8) and 0xff
        val b = argb and 0xff
        total += 0.2126 * r + 0.7152 * g + 0.0722 * b
        count++
      }
    }
    return total / count
  }

  private fun Pair<Int, Int>.pretty() = "${first}x$second"
}
