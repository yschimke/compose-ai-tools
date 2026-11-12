package ee.schimke.composeai.rcjvm

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.rccreation.dwf.DwfComplicationSeed
import ee.schimke.composeai.rccreation.dwf.DwfParser
import ee.schimke.composeai.rccreation.dwf.DwfRenderOptions
import ee.schimke.composeai.rccreation.dwf.DwfWeatherSeed
import ee.schimke.composeai.rccreation.dwf.captureDwfRemoteDocumentBytes
import ee.schimke.composeai.rcplayer.compose.RcTypefaceLoader
import ee.schimke.composeai.rcplayer.runtime.RcNamedValue
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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
    year: Int = 2026,
    month: Int = 11,
    dayOfMonth: Int = 18,
    options: DwfRenderOptions = DwfRenderOptions(),
    seeds: Map<String, RcNamedValue> = emptyMap(),
    exportArtifacts: Boolean = true,
  ): Pair<BufferedImage, BufferedImage> {
    val resDir = sampleResDir(sampleName)
    val xmlFile = File(resDir, "raw/watchface.xml")
    val refFile = File(resDir, "drawable/preview.png")
    assertThat(xmlFile.isFile).isTrue()
    assertThat(refFile.isFile).isTrue()

    val resolver = DwfResourceDirectoryResolver(listOf(resDir))
    val doc = DwfParser.parse(xmlFile.readText(), resolver)
    val rcBytes = runBlocking { captureDwfRemoteDocumentBytes(doc, resolver, options) }
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
        year = year,
        month = month,
        dayOfMonth = dayOfMonth,
      )
    val rendered = checkNotNull(ImageIO.read(ByteArrayInputStream(pngBytes)))
    val rawReference = checkNotNull(ImageIO.read(refFile))
    val reference = scaleTo450(rawReference)
    assertThat(rendered.width).isEqualTo(reference.width)
    assertThat(rendered.height).isEqualTo(reference.height)

    val exportDir = System.getProperty("dwf.validation.exportDir")?.takeIf { it.isNotBlank() }
    if (exportDir != null && exportArtifacts) {
      val outDir = File(exportDir).apply { mkdirs() }
      ImageIO.write(rendered, "PNG", File(outDir, "matched__$sampleName.png"))
      val defaultRcBytes = runBlocking {
        captureDwfRemoteDocumentBytes(doc, resolver, DwfRenderOptions())
      }
      val (defHour, defMinute, defSecond) = parsePreviewHms(doc.previewTime)
      val defaultPngBytes =
        renderDwfDocumentToPng(
          bytes = defaultRcBytes,
          widthPx = doc.width.coerceAtLeast(1),
          heightPx = doc.height.coerceAtLeast(1),
          hour = defHour,
          minute = defMinute,
          second = defSecond,
        )
      File(outDir, "dwf__$sampleName.png").writeBytes(defaultPngBytes)
      val sim28 = computePixelSimilarity(rendered, reference, channelTolerance = 28)
      val sim32 = computePixelSimilarity(rendered, reference, channelTolerance = 32)
      val sim48 = computePixelSimilarity(rendered, reference, channelTolerance = 48)
      println(
        "DWF_METRIC sample=$sampleName sim28=${"%.4f".format(sim28)} sim32=${"%.4f".format(sim32)} sim48=${"%.4f".format(sim48)}",
      )
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
  fun bundledRobotoFlexTypefaceIsLoadedAndChangesRenderedGlyphs() {
    val loader = checkNotNull(dwfTypefaces()) { "Expected bundled dwf-fonts Roboto Flex loader" }
    assertThat(loader.families).contains("default")
    assertThat(loader.families).contains("roboto flex")
    assertThat(loader.typeface("default")).isNotNull()

    val resDir = sampleResDir("SimpleDigital")
    val resolver = DwfResourceDirectoryResolver(listOf(resDir))
    val doc = DwfParser.parse(File(resDir, "raw/watchface.xml").readText(), resolver)
    val rcBytes = runBlocking { captureDwfRemoteDocumentBytes(doc, resolver, DwfRenderOptions()) }

    val robotoPng =
      renderDwfDocumentToPng(
        bytes = rcBytes,
        widthPx = 450,
        heightPx = 450,
        hour = 8,
        minute = 7,
        second = 0,
        typefaces = loader,
      )
    val fallbackPng =
      renderDwfDocumentToPng(
        bytes = rcBytes,
        widthPx = 450,
        heightPx = 450,
        hour = 8,
        minute = 7,
        second = 0,
        typefaces = RcTypefaceLoader.Empty,
      )
    assertThat(robotoPng.toList()).isNotEqualTo(fallbackPng.toList())
  }

  @Test
  fun photosMaskMatchesOfficialReferenceRender() {
    val (rendered, reference) =
      compileAndRenderSample(
        sampleName = "PhotosMask",
        hour = 10,
        minute = 8,
        second = 0,
        options = DwfRenderOptions(is24HourDefault = false),
      )
    val similarity = computePixelSimilarity(rendered, reference, channelTolerance = 28)
    assertThat(similarity).isAtLeast(0.95)
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
        options = DwfRenderOptions(is24HourDefault = false),
      )
    val similarity = computePixelSimilarity(rendered, reference, channelTolerance = 28)
    assertThat(similarity).isAtLeast(0.95)
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
    assertRgbNear(reference.getRGB(225, 65), rendered.getRGB(225, 65), tolerance = 6)
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
    assertThat(similarity).isAtLeast(0.92)
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
        options =
          DwfRenderOptions(
            is24HourDefault = false,
            flavorDefault = "flavorTwo",
            heartRateDefault = -1f,
            complicationDefaults =
              mapOf(
                0 to
                  DwfComplicationSeed(
                    type = "SHORT_TEXT",
                    title = "Steps",
                    text = "60",
                  ),
              ),
          ),
      )
    val similarity = computePixelSimilarity(rendered, reference, channelTolerance = 32)
    assertThat(similarity).isAtLeast(0.95)
    assertThat(countNonBlackPixelsInBand(rendered, 75, 140)).isGreaterThan(250)
    assertThat(countNonBlackPixelsInBand(rendered, 170, 260)).isGreaterThan(1500)
    assertThat(countNonBlackPixelsInBand(rendered, 280, 392)).isGreaterThan(400)
  }

  @Test
  fun complicationsWithFakeComplicationValuesMatchesOfficialReferenceRender() {
    val defaultWithoutFakes =
      compileAndRenderSample(
          sampleName = "Complications",
          hour = 10,
          minute = 8,
          second = 32,
          options = DwfRenderOptions(),
          exportArtifacts = false,
        )
        .first
    val (rendered, reference) =
      compileAndRenderSample(
        sampleName = "Complications",
        hour = 10,
        minute = 8,
        second = 32,
        options =
          DwfRenderOptions(
            complicationDefaults =
              mapOf(
                0 to
                  DwfComplicationSeed(
                    type = "PHOTO_IMAGE",
                    photoImage = "builtin:photo_aquarium",
                    text = "",
                    title = "",
                  ),
                1 to
                  DwfComplicationSeed(
                    type = "RANGED_VALUE",
                    title = "Title",
                    text = "w/Title",
                    value = 60f,
                    min = 0f,
                    max = 100f,
                    rangedValueColors = listOf(0xFF00FFFF.toInt(), 0xFF00FF00.toInt()),
                    rangedValueColorsInterpolate = true,
                  ),
                2 to
                  DwfComplicationSeed(
                    type = "SMALL_IMAGE",
                    smallImage = "builtin:watch_board",
                    text = "",
                    title = "",
                  ),
                3 to
                  DwfComplicationSeed(
                    type = "WEIGHTED_ELEMENTS",
                    text = "w/Icon",
                    title = "",
                    monochromaticImage = "builtin:battery",
                    weightedElementsColors =
                      listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt()),
                    weightedElementsWeights = listOf(3f, 3f, 2f),
                  ),
                4 to
                  DwfComplicationSeed(
                    type = "LONG_TEXT",
                    title = "Long Title",
                    text = "Text w/ Icon & Title",
                    monochromaticImage = "builtin:battery",
                  ),
              ),
          ),
      )

    val withoutFakesSim =
      computePixelSimilarity(defaultWithoutFakes, reference, channelTolerance = 48)
    val withFakesSim = computePixelSimilarity(rendered, reference, channelTolerance = 48)
    println(
      "DWF_METRIC sample=Complications_delta withoutFakes=${"%.4f".format(withoutFakesSim)} withFakes=${"%.4f".format(withFakesSim)}",
    )
    assertThat(withFakesSim).isGreaterThan(withoutFakesSim)
    assertThat(countNonBlackPixelsInBand(rendered, 45, 158)).isGreaterThan(1500)
    assertThat(countNonBlackPixelsInBand(rendered, 169, 282)).isGreaterThan(3000)
    assertThat(countNonBlackPixelsInBand(rendered, 310, 380)).isGreaterThan(1200)
  }

  @Test
  fun weatherMatchesOfficialReferenceRenderLayoutAndIcons() {
    val (rendered, reference) =
      compileAndRenderSample(
        sampleName = "Weather",
        hour = 14,
        minute = 31,
        second = 48,
        year = 2026,
        month = 6,
        dayOfMonth = 29,
        options =
          DwfRenderOptions(
            is24HourDefault = false,
            weatherDefault =
              DwfWeatherSeed(
                isAvailable = true,
                isDay = true,
                condition = 8,
                conditionName = "Sunny",
                temperature = 50f,
                temperatureUnit = 0,
                lastUpdatedText = "14:28",
                hours =
                  mapOf(
                    1 to DwfWeatherSeed.Hour(condition = 8, temperature = 52f, isDay = true),
                    2 to DwfWeatherSeed.Hour(condition = 8, temperature = 55f, isDay = true),
                    3 to DwfWeatherSeed.Hour(condition = 8, temperature = 57f, isDay = true),
                    4 to DwfWeatherSeed.Hour(condition = 8, temperature = 60f, isDay = true),
                  ),
                days =
                  mapOf(
                    1 to
                      DwfWeatherSeed.Day(
                        conditionDay = 8,
                        conditionNight = 1,
                        temperatureHigh = 64f,
                        temperatureLow = 47f,
                        chanceOfPrecipitation = 0,
                        chanceOfPrecipitationNight = 0,
                      ),
                  ),
              ),
          ),
      )
    val similarity = computePixelSimilarity(rendered, reference, channelTolerance = 32)
    assertThat(similarity).isAtLeast(0.88)
    val bands = listOf(35 to 68, 70 to 175, 185 to 235, 240 to 335, 345 to 385, 390 to 425)
    for ((y0, y1) in bands) {
      val refCount = countNonBlackPixelsInBand(reference, y0, y1)
      val renCount = countNonBlackPixelsInBand(rendered, y0, y1)
      assertThat(refCount).isGreaterThan(200)
      assertThat(renCount).isGreaterThan(200)
    }
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

  @Test
  fun renderDwfPreviewsAppliesFakesJsonConfiguration() {
    val tmp = Files.createTempDirectory("dwf-fakes-test-").toFile()
    try {
      val resDir = sampleResDir("Flavors")
      val previewsJson = File(tmp, "previews.json")
      previewsJson.writeText(
        """
        {
          "previews": [
            {
              "id": "flavors_test",
              "functionName": "flavors",
              "params": { "name": "Flavors", "dwfAssetPath": "raw/watchface.xml" },
              "captures": [ { "renderOutput": "renders/flavors_test.png" } ]
            }
          ]
        }
        """
          .trimIndent(),
      )
      val fakesFile = File(tmp, "fakes.json")
      val fakes =
        DwfFakesConfig(
          flavor = "flavorTwo",
          previewTime = "02:11:00",
          is24Hour = false,
          heartRate = -1f,
          complications =
            mapOf(
              0 to
                DwfFakeComplication(
                  type = "SHORT_TEXT",
                  title = "Steps",
                  text = "60",
                ),
            ),
        )
      fakesFile.writeText(Json.encodeToString(DwfFakesConfig.serializer(), fakes))

      val outWithoutFakes = File(tmp, "renders-default")
      val outWithFakes = File(tmp, "renders-fakes")
      assertThat(renderDwfPreviews(previewsJson, outWithoutFakes, listOf(resDir))).isEqualTo(1)
      assertThat(
          renderDwfPreviews(previewsJson, outWithFakes, listOf(resDir), fakesJson = fakesFile),
        )
        .isEqualTo(1)

      val defaultBytes = File(outWithoutFakes, "flavors_test.png").readBytes()
      val fakesBytes = File(outWithFakes, "flavors_test.png").readBytes()
      assertThat(fakesBytes.toList()).isNotEqualTo(defaultBytes.toList())
    } finally {
      tmp.deleteRecursively()
    }
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
