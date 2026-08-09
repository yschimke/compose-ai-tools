package com.example.sampleandroid

import com.google.common.truth.Truth.assertThat
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Test

/**
 * Assertion half of the preview-mode matrix (issue #3082). [PreviewModeMatrix.kt] declares one
 * `@Preview` per mode; this test reads the PNGs `:samples:android:composePreviewRenderAll` produced
 * for them (the module sets `renderBeforeUnitTests`, so `check` renders first) and pins each to the
 * geometry **Android Studio** resolves for the same annotation.
 *
 * The numbers below are not read back from our own resolver — they are written out as `dp × density`
 * from Studio's own device catalog, so the test says "we match Studio", not "we match ourselves". The
 * two rounding rules are the ones the pipeline actually implements:
 * - an explicit **fixed** axis (`widthDp`/`heightDp`) rounds half-up to Studio's whole-pixel frame;
 * - a **wrapped** axis is the composable's measured size, rounded up to the enclosing pixel.
 *
 * Together with the fixture this is the answer to "do we match Studio by default?": a regression in
 * `DeviceDimensions.resolveForRender`, in the Robolectric qualifier plumbing, or in the multipreview
 * walk fails here instead of silently shipping differently-shaped pixels.
 */
class PreviewModeMatrixTest {

  private val rendersDir = File("build/compose-previews/renders")

  /**
   * `functionName_sanitisedPreviewName` → expected PNG size in pixels.
   *
   * Density is Studio's 420dpi (2.625×) default unless the device pins one.
   */
  private val expectedSizes =
    mapOf(
      // Both axes wrap: the probe's own 160×80dp at 2.625× = 420×210.
      "MatrixComponentWrapPreview_Component_wrap" to (420 to 210),
      // Both axes fixed: 200×100dp at 2.625× = 525 × roundHalfUp(262.5) = 525×263.
      "MatrixFixedBothAxesPreview_Fixed_both_axes" to (525 to 263),
      // Width fixed (240dp → 630px), height still wraps to the probe's 80dp → 210px.
      "MatrixFixedWidthOnlyPreview_Fixed_width_only" to (630 to 210),
      // 120×60dp at 2.625× = 315 × roundHalfUp(157.5).
      "MatrixBackgroundColorPreview_Background_colour" to (315 to 158),
      // The uiMode / fontScale / locale trio all keep the same 200×100dp frame — those params
      // change what is drawn, never the canvas.
      "MatrixLightPreview_Day" to (525 to 263),
      "MatrixNightPreview_Night" to (525 to 263),
      "MatrixFontScalePreview_Font_scale_2x" to (525 to 263),
      "MatrixLocalePreview_German" to (525 to 263),
      // Device ids resolve to Studio's catalog geometry, both axes fixed.
      // Pixel 5: 393×851dp @2.75×.
      "MatrixPhoneDevicePreview_Phone" to (1080 to 2340),
      // Wear small round: 192×192dp @2.0×.
      "MatrixWearDevicePreview_Wear" to (384 to 384),
      // Pixel Fold, natural orientation: 841×701dp @2.625×.
      "MatrixFoldableDevicePreview_Foldable" to (2207 to 1840),
      // TV 1080p: 960×540dp @2.0×.
      "MatrixTvDevicePreview_TV" to (1920 to 1080),
      // spec: grammar with an explicit dpi= term → 360×640dp @2.0×.
      "MatrixDeviceSpecPreview_Device_spec" to (720 to 1280),
      // orientation=portrait on a landscape spec — AndroidX's own @PreviewScreenSizes "Tablet"
      // string. 1280×800dp @1.5× rotated is 800×1280dp; we used to render it 1920×1200 landscape
      // because only `orientation=landscape` was honoured (issue #3547).
      "MatrixRotatedDeviceSpecPreview_Rotated_device_spec" to (1200 to 1920),
      // spec:parent= + orientation= — Small Phone (360×640dp @2.0×) rotated to landscape, so the
      // frame is 640×360dp. `parent=` used to be unread, collapsing this to the 400×800dp default
      // (2100×1050 once rotated).
      "MatrixParentDeviceSpecPreview_Parent_device_spec" to (1280 to 720),
      // showSystemUi with no device promotes to the default phone frame: 400×800dp @2.625×.
      "MatrixSystemUiPreview_System_UI" to (1050 to 2100),
    )

  @Test
  fun `every preview mode renders at the size Android Studio resolves`() {
    val mismatches =
      expectedSizes.mapNotNull { (stem, expected) ->
        val file = File(rendersDir, "$stem.png")
        if (!file.exists()) return@mapNotNull "$stem: no PNG rendered"
        val img = ImageIO.read(file) ?: return@mapNotNull "$stem: unreadable PNG"
        val actual = img.width to img.height
        if (actual == expected) null else "$stem: expected ${expected.pretty()}, got ${actual.pretty()}"
      }
    assertThat(mismatches).isEmpty()
  }

  @Test
  fun `uiMode night renders darker pixels than its day sibling`() {
    // Same canvas, different configuration — the pair is what proves `uiMode` reached the render
    // rather than just that a dark-looking preview exists.
    val day = readRender("MatrixLightPreview_Day")
    val night = readRender("MatrixNightPreview_Night")
    assertThat(meanLuminance(night)).isLessThan(meanLuminance(day))
  }

  @Test
  fun `backgroundColor paints the declared colour behind the composable`() {
    // The probe is a small black box centred in a larger frame, so the corners are pure background.
    val img = readRender("MatrixBackgroundColorPreview_Background_colour")
    listOf(0 to 0, img.width - 1 to 0, 0 to img.height - 1, img.width - 1 to img.height - 1).forEach {
      (x, y) ->
      val argb = img.getRGB(x, y)
      assertThat((argb ushr 24) and 0xff).isEqualTo(0xff)
      assertThat(argb and 0xffffff).isEqualTo(0x00FF00)
    }
  }

  @Test
  fun `multipreview annotations fan out to one render each`() {
    // AndroidX's multipreview annotations and an app-declared meta-annotation are all walked the
    // same way, so each contributes one PNG per nested @Preview. Counts are pinned to the compose
    // BOM the sample builds against; a BOM bump that changes a ladder should surface here rather
    // than silently changing what reviewers see.
    val fanOut =
      mapOf(
        // @PreviewLightDark
        "MatrixLightDarkMultiPreview" to listOf("Dark", "Light"),
        // @PreviewFontScale — the seven-stop accessibility ladder (85% … 200%)
        "MatrixFontScaleMultiPreview" to listOf("100", "115", "130", "150", "180", "200", "85"),
        // @PreviewScreenSizes — Studio's reference screens, including the two landscape variants
        "MatrixScreenSizesMultiPreview" to
          listOf(
            "Desktop",
            "Phone",
            "Phone_Landscape",
            "Tablet",
            "Tablet_Landscape",
            "Unfolded_Foldable",
          ),
        // The app-declared meta-annotation
        "MatrixMetaAnnotationMultiPreview" to listOf("Meta_phone", "Meta_watch"),
      )
    fanOut.forEach { (function, expected) ->
      val renders = rendersFor(function)
      assertThat(renders.map { it.name.removePrefix("${function}_").removeSuffix(".png") })
        .containsExactlyElementsIn(expected)
        .inOrder()
      // Every fan-out entry must be a real, readable image — an empty or zero-sized capture would
      // otherwise pass a pure filename count.
      renders.forEach { file ->
        val img = ImageIO.read(file)
        assertThat(img).isNotNull()
        assertThat(img.width).isGreaterThan(0)
        assertThat(img.height).isGreaterThan(0)
      }
    }
  }

  @Test
  fun `PreviewScreenSizes renders its tablet screen portrait and its sibling landscape`() {
    // AndroidX spells its "Tablet" entry `spec:width=1280dp,height=800dp,dpi=240,orientation=
    // portrait` and its "Tablet - Landscape" sibling as the same string without the rotation. With
    // `orientation=portrait` dropped at resolve-time the two were the same landscape pixels, so the
    // fan-out counted six screens while showing five (issue #3547). The pair is the assertion: same
    // frame, transposed.
    val tablet = readRender("MatrixScreenSizesMultiPreview_Tablet")
    val landscape = readRender("MatrixScreenSizesMultiPreview_Tablet_Landscape")
    assertThat(tablet.height).isGreaterThan(tablet.width)
    assertThat(landscape.width).isGreaterThan(landscape.height)
    assertThat(tablet.width).isEqualTo(landscape.height)
    assertThat(tablet.height).isEqualTo(landscape.width)
  }

  @Test
  fun `mixed fixed-wrap showBackground fills the whole fixed frame`() {
    // Studio measures the fixed width tightly even when height wraps, so the preferred-size probe
    // itself fills the full fixed axis.
    val img = readRender("MatrixFixedWidthOnlyPreview_Fixed_width_only")
    val argb = img.getRGB(img.width - 2, 2)
    assertThat((argb ushr 24) and 0xff).isEqualTo(0xff)
    assertThat(argb and 0xffffff).isEqualTo(0x3366FF)
  }

  @Test
  fun `PreviewLightDark fans out into two visibly different captures`() {
    // A fan-out that renders the same pixels twice would still satisfy a filename count, so pin the
    // outcome: the probe colours itself from `isSystemInDarkTheme()`, and the dark entry must land
    // darker. This is what proves each nested @Preview's params reach the render, not just its name.
    val light = readRender("MatrixLightDarkMultiPreview_Light")
    val dark = readRender("MatrixLightDarkMultiPreview_Dark")
    assertThat(meanLuminance(dark)).isLessThan(meanLuminance(light))
  }

  @Test
  fun `the app-declared meta-annotation resolves each nested device independently`() {
    // The two nested @Preview entries name different devices, so the fan-out must produce two
    // differently-shaped captures — this is what distinguishes a real transitive multipreview walk
    // from one that renders the same spec twice.
    val sizes = rendersFor("MatrixMetaAnnotationMultiPreview").map { ImageIO.read(it) }.map { it.width to it.height }
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
