package ee.schimke.composeai.rcjvm

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.rccreation.dwf.DwfParser
import ee.schimke.composeai.rccreation.dwf.DwfRenderOptions
import ee.schimke.composeai.rccreation.dwf.DwfVariables
import ee.schimke.composeai.rccreation.dwf.captureDwfRemoteDocumentBytes
import ee.schimke.composeai.rcplayer.runtime.RcNamedValue
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Test

class DwfWearOsSamplesValidationTest {

  private fun sampleResDir(sampleName: String): File {
    val url =
      checkNotNull(
        javaClass.classLoader.getResource("wear-os-samples/$sampleName/raw/watchface.xml"),
      ) {
        "Missing wear-os-samples/$sampleName/raw/watchface.xml test resource"
      }
    return File(url.toURI()).parentFile.parentFile
  }

  private fun compileAndRenderSample(
    sampleName: String,
    hour: Int,
    minute: Int,
    second: Int,
    seeds: Map<String, RcNamedValue> = emptyMap(),
  ): Pair<BufferedImage, BufferedImage> {
    val resDir = sampleResDir(sampleName)
    val xmlFile = File(resDir, "raw/watchface.xml")
    val refFile = File(resDir, "drawable/preview.png")
    assertThat(xmlFile.isFile).isTrue()
    assertThat(refFile.isFile).isTrue()

    val resolver = DwfResourceDirectoryResolver(listOf(resDir))
    val doc = DwfParser.parse(xmlFile.readText(), resolver)
    val rcBytes = runBlocking { captureDwfRemoteDocumentBytes(doc, resolver, DwfRenderOptions()) }
    assertThat(rcBytes.size).isGreaterThan(128)

    val pngBytes =
      renderDwfDocumentToPng(
        bytes = rcBytes,
        widthPx = 450,
        heightPx = 450,
        hour = hour,
        minute = minute,
        second = second,
        seeds = seeds,
      )
    val rendered = checkNotNull(ImageIO.read(ByteArrayInputStream(pngBytes)))
    val rawReference = checkNotNull(ImageIO.read(refFile))
    val reference = scaleTo450(rawReference)
    assertThat(rendered.width).isEqualTo(reference.width)
    assertThat(rendered.height).isEqualTo(reference.height)

    val exportDir = System.getProperty("dwf.validation.exportDir")?.takeIf { it.isNotBlank() }
    if (exportDir != null) {
      val outDir = File(exportDir).apply { mkdirs() }
      ImageIO.write(rendered, "PNG", File(outDir, "matched__$sampleName.png"))
    }
    return rendered to reference
  }

  private fun scaleTo450(src: BufferedImage): BufferedImage {
    if (src.width == 450 && src.height == 450) return src
    val dst = BufferedImage(450, 450, BufferedImage.TYPE_INT_ARGB)
    val g = dst.createGraphics()
    try {
      g.setRenderingHint(
        java.awt.RenderingHints.KEY_INTERPOLATION,
        java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR,
      )
      g.drawImage(src, 0, 0, 450, 450, null)
    } finally {
      g.dispose()
    }
    return dst
  }

  @Test
  fun photosMaskMatchesOfficialReferenceRender() {
    val (rendered, reference) =
      compileAndRenderSample(
        sampleName = "PhotosMask",
        hour = 10,
        minute = 8,
        second = 0,
        seeds = mapOf(DwfVariables.IS_24_HOUR to RcNamedValue.Integer(0)),
      )
    val similarity = computePixelSimilarity(rendered, reference, channelTolerance = 28)
    assertThat(similarity).isAtLeast(0.92)
    // Verify the hexagon mask clips the corners of the photo box (e.g. (110, 110) is pure black)
    // while the center of the photo (225, 225) matches the default_image.png content.
    assertRgbNear(reference.getRGB(110, 110), rendered.getRGB(110, 110), tolerance = 8)
    assertRgbNear(reference.getRGB(225, 225), rendered.getRGB(225, 225), tolerance = 20)
  }

  @Test
  fun photosMultiMatchesOfficialReferenceRender() {
    val (rendered, reference) =
      compileAndRenderSample(
        sampleName = "PhotosMulti",
        hour = 10,
        minute = 8,
        second = 0,
        seeds = mapOf(DwfVariables.IS_24_HOUR to RcNamedValue.Integer(0)),
      )
    val similarity = computePixelSimilarity(rendered, reference, channelTolerance = 28)
    assertThat(similarity).isAtLeast(0.92)
    // Center of photo at (225, 225) and outside border at (60, 225) match reference
    assertRgbNear(reference.getRGB(225, 225), rendered.getRGB(225, 225), tolerance = 20)
    assertRgbNear(reference.getRGB(60, 225), rendered.getRGB(60, 225), tolerance = 8)
  }

  @Test
  fun simpleAnalogMatchesOfficialReferenceRender() {
    val (rendered, reference) =
      compileAndRenderSample(
        sampleName = "SimpleAnalog",
        hour = 10,
        minute = 43,
        second = 8,
      )
    val similarity = computePixelSimilarity(rendered, reference, channelTolerance = 24)
    assertThat(similarity).isAtLeast(0.93)
    // Annulus purple ring at (225, 65) matches reference (#411C75)
    assertRgbNear(reference.getRGB(225, 65), rendered.getRGB(225, 65), tolerance = 6)
    // Inner cutout hole at (225, 150) is pure black (#000000) just like reference
    assertRgbNear(reference.getRGB(225, 150), rendered.getRGB(225, 150), tolerance = 4)
  }

  @Test
  fun simpleDigitalMatchesOfficialReferenceRender() {
    val (rendered, reference) =
      compileAndRenderSample(
        sampleName = "SimpleDigital",
        hour = 8,
        minute = 7,
        second = 0,
      )
    val similarity = computePixelSimilarity(rendered, reference, channelTolerance = 28)
    assertThat(similarity).isAtLeast(0.85)
    // Both time band (y=130..195) and greeting band (y=240..280) contain bright white glyphs
    assertThat(countBrightPixelsInBand(rendered, 130, 195)).isGreaterThan(1500)
    assertThat(countBrightPixelsInBand(reference, 130, 195)).isGreaterThan(1500)
    assertThat(countBrightPixelsInBand(rendered, 240, 280)).isGreaterThan(600)
    assertThat(countBrightPixelsInBand(reference, 240, 280)).isGreaterThan(600)
  }

  @Test
  fun flavorsPresetTwoMatchesOfficialReferenceRender() {
    val (rendered, reference) =
      compileAndRenderSample(
        sampleName = "Flavors",
        hour = 2,
        minute = 11,
        second = 0,
        seeds =
          mapOf(
            DwfVariables.IS_24_HOUR to RcNamedValue.Integer(0),
            DwfVariables.configKey("themeColor") to RcNamedValue.Integer(2),
            DwfVariables.colorThemeKey("themeColor", 0) to RcNamedValue.Color(0xFFD65780.toInt()),
            DwfVariables.colorThemeKey("themeColor", 1) to RcNamedValue.Color(0xFFEE9480.toInt()),
            DwfVariables.colorThemeKey("themeColor", 2) to RcNamedValue.Color(0xFFEFD2CB.toInt()),
            DwfVariables.configKey("clockAppearance") to RcNamedValue.Integer(2),
            DwfVariables.configKey("showHr") to RcNamedValue.Integer(1),
            DwfVariables.HEART_RATE to RcNamedValue.FloatValue(0f),
            DwfVariables.complicationTextKey(0) to RcNamedValue.Text("60"),
          ),
      )
    val similarity = computePixelSimilarity(rendered, reference, channelTolerance = 32)
    assertThat(similarity).isAtLeast(0.88)
    // Both rendered and reference have the pink/salmon HR band (y=75..140), pink clock band
    // (y=170..260),
    // and bottom complication circle (y=280..392)
    assertThat(countNonBlackPixelsInBand(rendered, 75, 140)).isGreaterThan(300)
    assertThat(countNonBlackPixelsInBand(rendered, 170, 260)).isGreaterThan(1500)
    assertThat(countNonBlackPixelsInBand(rendered, 280, 392)).isGreaterThan(400)
  }

  @Test
  fun complicationsMatchesOfficialReferenceRender() {
    val (rendered, reference) =
      compileAndRenderSample(
        sampleName = "Complications",
        hour = 10,
        minute = 8,
        second = 32,
      )
    // Top complication slot (x=169, y=45, w=113, h=113) ring stroke at (225, 47) uses #DBBBF5
    // (themeColor.0) and matches between rendered and reference
    assertThat(countNonBlackPixelsInBand(rendered, 45, 158)).isGreaterThan(1000)
    assertThat(countNonBlackPixelsInBand(reference, 45, 158)).isGreaterThan(1000)
    // Middle band (left/right complications + digital clock, y=169..282)
    assertThat(countNonBlackPixelsInBand(rendered, 169, 282)).isGreaterThan(2000)
    assertThat(countNonBlackPixelsInBand(reference, 169, 282)).isGreaterThan(2000)
    // Bottom long-text complication slot (y=310..380)
    assertThat(countNonBlackPixelsInBand(rendered, 310, 380)).isGreaterThan(800)
    assertThat(countNonBlackPixelsInBand(reference, 310, 380)).isGreaterThan(800)
  }

  @Test
  fun weatherMatchesOfficialReferenceRenderLayoutAndIcons() {
    val (rendered, reference) =
      compileAndRenderSample(
        sampleName = "Weather",
        hour = 14,
        minute = 31,
        second = 48,
        seeds =
          mapOf(
            DwfVariables.IS_24_HOUR to RcNamedValue.Integer(0),
            DwfVariables.WEATHER_TEMPERATURE_UNIT to RcNamedValue.Integer(0),
            DwfVariables.WEATHER_CONDITION to RcNamedValue.Integer(8),
            DwfVariables.WEATHER_CONDITION_NAME to RcNamedValue.Text("Sunny"),
            DwfVariables.WEATHER_TEMPERATURE to RcNamedValue.FloatValue(70f),
          ),
      )
    // Verify all 6 vertical layout bands of Weather (date header, large time, current condition +
    // icon,
    // 4-column hourly forecast icons + temps, daily high/low pill, and footer) are populated just
    // like reference
    val bands = listOf(35 to 68, 70 to 175, 185 to 235, 240 to 335, 345 to 385, 390 to 425)
    for ((y0, y1) in bands) {
      val refCount = countNonBlackPixelsInBand(reference, y0, y1)
      val renCount = countNonBlackPixelsInBand(rendered, y0, y1)
      assertThat(refCount).isGreaterThan(200)
      assertThat(renCount).isGreaterThan(200)
    }
    // Verify current weather condition icon in center (x=205..255, y=185..232) has non-black icon
    // pixels
    var iconPixels = 0
    for (y in 185..232) {
      for (x in 205..255) {
        val rgb = rendered.getRGB(x, y)
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        if (r + g + b > 60) iconPixels++
      }
    }
    assertThat(iconPixels).isGreaterThan(200)
  }

  private fun computePixelSimilarity(
    a: BufferedImage,
    b: BufferedImage,
    channelTolerance: Int,
  ): Double {
    var matching = 0
    var total = 0
    val cx = a.width / 2
    val cy = a.height / 2
    val radiusSq = (cx - 4) * (cx - 4)
    for (y in 0 until a.height) {
      for (x in 0 until a.width) {
        val dx = x - cx
        val dy = y - cy
        if (dx * dx + dy * dy > radiusSq) continue
        total++
        val ca = a.getRGB(x, y)
        val cb = b.getRGB(x, y)
        val aa = (ca ushr 24) and 0xFF
        val ab = (cb ushr 24) and 0xFF
        if (aa < 16 && ab < 16) {
          matching++
          continue
        }
        val ra = (ca ushr 16) and 0xFF
        val ga = (ca ushr 8) and 0xFF
        val ba = ca and 0xFF
        val rb = (cb ushr 16) and 0xFF
        val gb = (cb ushr 8) and 0xFF
        val bb = cb and 0xFF
        if (
          abs(ra - rb) <= channelTolerance &&
            abs(ga - gb) <= channelTolerance &&
            abs(ba - bb) <= channelTolerance
        ) {
          matching++
        }
      }
    }
    return matching.toDouble() / total.coerceAtLeast(1).toDouble()
  }

  private fun assertRgbNear(expectedArgb: Int, actualArgb: Int, tolerance: Int) {
    val er = (expectedArgb ushr 16) and 0xFF
    val eg = (expectedArgb ushr 8) and 0xFF
    val eb = expectedArgb and 0xFF
    val ar = (actualArgb ushr 16) and 0xFF
    val ag = (actualArgb ushr 8) and 0xFF
    val ab = actualArgb and 0xFF
    assertThat(abs(er - ar)).isAtMost(tolerance)
    assertThat(abs(eg - ag)).isAtMost(tolerance)
    assertThat(abs(eb - ab)).isAtMost(tolerance)
  }

  private fun countBrightPixelsInBand(img: BufferedImage, yStart: Int, yEnd: Int): Int {
    var count = 0
    for (y in yStart..yEnd.coerceAtMost(img.height - 1)) {
      for (x in 0 until img.width) {
        val c = img.getRGB(x, y)
        val r = (c ushr 16) and 0xFF
        val g = (c ushr 8) and 0xFF
        val b = c and 0xFF
        if (r > 160 && g > 160 && b > 160) count++
      }
    }
    return count
  }

  private fun countNonBlackPixelsInBand(img: BufferedImage, yStart: Int, yEnd: Int): Int {
    var count = 0
    for (y in yStart..yEnd.coerceAtMost(img.height - 1)) {
      for (x in 0 until img.width) {
        val c = img.getRGB(x, y)
        val a = (c ushr 24) and 0xFF
        val r = (c ushr 16) and 0xFF
        val g = (c ushr 8) and 0xFF
        val b = c and 0xFF
        if (a > 128 && (r + g + b) > 45) count++
      }
    }
    return count
  }

  private fun countNonBgPixels(img: BufferedImage, bgArgb: Int): Int {
    val br = (bgArgb ushr 16) and 0xFF
    val bg = (bgArgb ushr 8) and 0xFF
    val bb = bgArgb and 0xFF
    var count = 0
    for (y in 0 until img.height) {
      for (x in 0 until img.width) {
        val c = img.getRGB(x, y)
        val a = (c ushr 24) and 0xFF
        if (a < 128) continue
        val r = (c ushr 16) and 0xFF
        val g = (c ushr 8) and 0xFF
        val b = c and 0xFF
        if (abs(r - br) > 16 || abs(g - bg) > 16 || abs(b - bb) > 16) count++
      }
    }
    return count
  }
}
