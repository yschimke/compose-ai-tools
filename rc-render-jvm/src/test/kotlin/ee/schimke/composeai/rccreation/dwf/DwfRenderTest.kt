package ee.schimke.composeai.rccreation.dwf

import androidx.compose.foundation.layout.size
import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.core.RemoteComposeBuffer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.rcplayer.compose.LocalRcTimeSource
import ee.schimke.composeai.rcplayer.compose.RcComposePlayer
import ee.schimke.composeai.rcplayer.runtime.RcNamedValue
import ee.schimke.composeai.rcplayer.runtime.RcTimeSnapshot
import ee.schimke.composeai.rcplayer.runtime.RcTimeSource
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap

class DwfRenderTest {
  private val watchFaceXml =
    """
    <WatchFace width="450" height="450" clipShape="CIRCLE">
      <Metadata key="PREVIEW_TIME" value="10:10:30" />
      <UserConfigurations>
        <ColorConfiguration id="accent" defaultValue="cyan">
          <ColorOption id="cyan" colors="#FF00E5FF" />
        </ColorConfiguration>
      </UserConfigurations>
      <Scene backgroundColor="#FF10141C">
        <Variant mode="AMBIENT" target="backgroundColor" value="#FF000000" />
        <PartDraw x="20" y="20" width="410" height="410">
          <Arc centerX="205" centerY="205" width="380" height="380" startAngle="0" endAngle="180">
            <Transform target="endAngle" value="clamp([STEP_PERCENT] * 3.6, 0, 360)" />
            <Stroke color="[CONFIGURATION.accent.0]" thickness="16" cap="ROUND" />
          </Arc>
        </PartDraw>
        <PartImage x="209" y="64" width="32" height="32" resource="badge_dot" />
        <DigitalClock x="125" y="120" width="200" height="54">
          <TimeText format="HH:mm:ss" hourFormat="TWENTY_FOUR" align="CENTER">
            <Font family="SYNC_TO_DEVICE" size="36" weight="BOLD" color="#FFFFFFFF" />
          </TimeText>
        </DigitalClock>
        <ComplicationSlot x="150" y="295" width="150" height="64" slotId="1"
            supportedTypes="SHORT_TEXT" defaultSystemProvider="STEP_COUNT" defaultSystemProviderType="SHORT_TEXT">
          <Complication type="SHORT_TEXT">
            <PartText x="0" y="0" width="150" height="64">
              <Text align="CENTER">
                <Font size="24" weight="MEDIUM" color="#FF80D8FF">
                  <Parameter expression="[COMPLICATION.TEXT]" />
                </Font>
              </Text>
            </PartText>
          </Complication>
        </ComplicationSlot>
        <AnalogClock x="0" y="0" width="450" height="450">
          <HourHand x="217" y="115" width="16" height="130" pivotX="0.5" pivotY="0.85" />
          <MinuteHand x="219" y="75" width="12" height="170" pivotX="0.5" pivotY="0.88" />
          <SecondHand x="222" y="55" width="6" height="195" pivotX="0.5" pivotY="0.87" />
        </AnalogClock>
      </Scene>
    </WatchFace>
    """
      .trimIndent()

  @Test
  fun rendersWatchFaceAndReactsToClockAmbientStepAndThemeVariables() = runBlocking {
    val badge = ImageBitmap(16, 16)
    Canvas(badge)
      .drawCircle(
        center = androidx.compose.ui.geometry.Offset(8f, 8f),
        radius = 7f,
        paint = Paint().apply { color = Color(0xFFFFC107) },
      )
    val resolver = DwfMapResourceResolver(images = mapOf("badge_dot" to badge))
    val rcBytes = captureDwfRemoteDocumentBytes(xml = watchFaceXml, resources = resolver)
    assertTrue(rcBytes.size > 200, "Expected non-trivial captured .rc payload")

    // Verify AndroidX CoreDocument (`remote-core`) decodes the captured watch face bytes cleanly,
    // guaranteeing compatibility with AndroidX's View RemoteComposePlayer as well as
    // RcComposePlayer.
    val androidxCore = CoreDocument()
    androidxCore.initFromBuffer(RemoteComposeBuffer.fromInputStream(ByteArrayInputStream(rcBytes)))
    assertTrue(
      androidxCore.operations.firstOrNull() is androidx.compose.remote.core.operations.Header,
      "Expected Header operation first, got ${androidxCore.operations}",
    )
    assertNotNull(androidxCore.rootLayoutComponent, "Expected decoded AndroidX RootLayoutComponent")
    assertTrue(
      androidxCore.toNestedString().lineSequence().count() > 20,
      "Expected decoded nested AndroidX operations",
    )

    val frame1010 = renderFrame(rcBytes, hour = 10, minute = 10, second = 30)
    val frame0345 = renderFrame(rcBytes, hour = 3, minute = 45, second = 0)
    val frameAmbient =
      renderFrame(
        rcBytes,
        hour = 10,
        minute = 10,
        second = 30,
        overrides = mapOf(DwfVariables.IS_AMBIENT to RcNamedValue.BooleanValue(true)),
      )
    val frameHighStepsAndReThemed =
      renderFrame(
        rcBytes,
        hour = 10,
        minute = 10,
        second = 30,
        overrides =
          mapOf(
            DwfVariables.STEP_COUNT to RcNamedValue.Integer(9200),
            DwfVariables.colorThemeKey("accent", 0) to RcNamedValue.Color(0xFFFF1744.toInt()),
            DwfVariables.complicationTextKey(1) to RcNamedValue.Text("9.2K"),
          ),
      )

    System.getProperty("dwf.writeEvidenceDir")
      ?.takeIf { it.isNotBlank() }
      ?.let { outPath ->
        val outDir = File(outPath).apply { mkdirs() }
        File(outDir, "dwf-10-10-30-interactive.png")
          .writeBytes(checkNotNull(encodeDwfPng(frame1010.asComposeImageBitmap())))
        File(outDir, "dwf-03-45-00-interactive.png")
          .writeBytes(checkNotNull(encodeDwfPng(frame0345.asComposeImageBitmap())))
        File(outDir, "dwf-10-10-30-ambient.png")
          .writeBytes(checkNotNull(encodeDwfPng(frameAmbient.asComposeImageBitmap())))
        File(outDir, "dwf-high-steps-rethemed.png")
          .writeBytes(checkNotNull(encodeDwfPng(frameHighStepsAndReThemed.asComposeImageBitmap())))
      }

    // Non-blank circular watch face with transparent corners outside the circle clip.
    assertEqualsArgbAlpha(0, frame1010.getColor(4, 4))
    assertTrue(countNonDarkPixels(frame1010) > 1500, "Expected visible watch face pixels")

    // Changing time rotates hands and updates HH:mm:ss digital clock.
    assertNotEquals(
      pixelDigest(frame1010),
      pixelDigest(frame0345),
      "Changing clock from 10:10:30 to 03:45:00 must change rendered pixels",
    )

    // Ambient mode hides the second hand and switches background to pure #000000.
    assertNotEquals(
      pixelDigest(frame1010),
      pixelDigest(frameAmbient),
      "Ambient mode must change rendered watch face pixels",
    )

    // Updating step count, theme color, and complication text mutates rendered output without
    // re-capturing.
    assertNotEquals(
      pixelDigest(frame1010),
      pixelDigest(frameHighStepsAndReThemed),
      "Live host variable overrides must update rendered pixels",
    )
  }

  @Test
  fun rendersRobotoFlexWeightedStrokesFakeComplicationsAndSafeLiveEllipsis() = runBlocking {
    val xml =
      """
      <WatchFace width="450" height="450" clipShape="CIRCLE">
        <Scene backgroundColor="#FF000000">
          <PartText x="125" y="45" width="200" height="44">
            <Text align="CENTER">
              <Font family="SYNC_TO_DEVICE" size="32" weight="SEMI_BOLD" color="#FFFFFFFF">
                <Parameter expression="[HEART_RATE] != null ? [HEART_RATE] : &quot;--&quot;" />
              </Font>
            </Text>
          </PartText>
          <ComplicationSlot x="169" y="100" width="113" height="113" slotId="1"
              supportedTypes="RANGED_VALUE">
            <BoundingOval x="0" y="0" width="113" height="113" />
            <Complication type="RANGED_VALUE">
              <PartDraw x="0" y="0" width="113" height="113">
                <Arc centerX="56.5" centerY="56.5" width="100" height="100" startAngle="180" endAngle="420">
                  <WeightedStroke colors="[COMPLICATION.RANGED_VALUE_COLORS]" thickness="10"
                      interpolate="[COMPLICATION.RANGED_VALUE_COLORS_INTERPOLATE]" cap="ROUND" />
                </Arc>
              </PartDraw>
            </Complication>
          </ComplicationSlot>
          <ComplicationSlot x="169" y="220" width="113" height="113" slotId="2"
              supportedTypes="WEIGHTED_ELEMENTS">
            <BoundingOval x="0" y="0" width="113" height="113" />
            <Complication type="WEIGHTED_ELEMENTS">
              <PartDraw x="0" y="0" width="113" height="113">
                <Arc centerX="56.5" centerY="56.5" width="100" height="100" startAngle="0" endAngle="360">
                  <WeightedStroke colors="[COMPLICATION.WEIGHTED_ELEMENTS_COLORS]"
                      weights="[COMPLICATION.WEIGHTED_ELEMENTS_WEIGHTS]" discreteGap="8" thickness="10" />
                </Arc>
              </PartDraw>
              <PartImage x="40" y="40" width="32" height="32">
                <Image resource="[COMPLICATION.MONOCHROMATIC_IMAGE]" />
              </PartImage>
            </Complication>
          </ComplicationSlot>
          <ComplicationSlot x="90" y="345" width="270" height="68" slotId="3"
              supportedTypes="LONG_TEXT">
            <BoundingRoundBox x="0" y="0" width="270" height="68" cornerRadiusX="20" cornerRadiusY="20" />
            <Complication type="LONG_TEXT">
              <PartText x="54" y="18" width="162" height="32">
                <Text align="START" ellipsis="TRUE">
                  <Font family="SYNC_TO_DEVICE" size="20" color="#FFFFFFFF">
                    <Parameter expression="[COMPLICATION.TEXT]" />
                  </Font>
                </Text>
              </PartText>
            </Complication>
          </ComplicationSlot>
        </Scene>
      </WatchFace>
      """
        .trimIndent()

    val rcBytes =
      captureDwfRemoteDocumentBytes(
        xml = xml,
        options =
          DwfRenderOptions(
            heartRateDefault = -1f,
            complicationDefaults =
              mapOf(
                1 to
                  DwfComplicationSeed(
                    type = "RANGED_VALUE",
                    value = 60f,
                    min = 0f,
                    max = 100f,
                    rangedValueColors = listOf(0xFF00FFFF.toInt(), 0xFF00FF00.toInt()),
                    rangedValueColorsInterpolate = true,
                  ),
                2 to
                  DwfComplicationSeed(
                    type = "WEIGHTED_ELEMENTS",
                    monochromaticImage = "builtin:battery",
                    weightedElementsColors =
                      listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt()),
                    weightedElementsWeights = listOf(3f, 3f, 2f),
                  ),
                3 to
                  DwfComplicationSeed(
                    type = "LONG_TEXT",
                    text = "Text w/ Icon & Title",
                  ),
              ),
          ),
      )

    val frameSystemFont = renderFrame(rcBytes, hour = 10, minute = 8, second = 32)
    assertTrue(countNonDarkPixels(frameSystemFont) > 500)

    // Verify short live complication text on an ellipsis="TRUE" node never throws
    // IndexOutOfBoundsException and updates rendered pixels cleanly.
    val frameShortLiveText =
      renderFrame(
        rcBytes,
        hour = 10,
        minute = 8,
        second = 32,
        overrides =
          mapOf(
            DwfVariables.complicationTextKey(3) to RcNamedValue.Text("OK"),
            DwfVariables.HEART_RATE to RcNamedValue.FloatValue(78f),
          ),
      )
    assertNotEquals(pixelDigest(frameSystemFont), pixelDigest(frameShortLiveText))

    val requestedFamilies = mutableSetOf<String>()
    val recordingLoader =
      object : ee.schimke.composeai.rcplayer.compose.RcTypefaceLoader {
        override val families: Set<String> = setOf("default")

        override fun typeface(
          family: String,
          variations: ee.schimke.composeai.rcplayer.compose.RcFontVariations?,
        ): androidx.compose.ui.text.font.FontFamily? {
          requestedFamilies += family
          return null
        }
      }
    renderFrame(rcBytes, hour = 10, minute = 8, second = 32, typefaces = recordingLoader)
    assertTrue(
      "default" in requestedFamilies,
      "Expected SYNC_TO_DEVICE text to query 'default' family on RcTypefaceLoader, got $requestedFamilies",
    )

    val classLoader = DwfRenderTest::class.java.classLoader
    assertNotNull(
      classLoader.getResource("dwf-fonts/fonts.json"),
      "Expected staged dwf-fonts/fonts.json on test runtime classpath",
    )
    val robotoLoader =
      ee.schimke.composeai.rcplayer.compose
        .RcManifestTypefaceLoader { url ->
          val normalized = url.trimStart('/')
          classLoader.getResourceAsStream(normalized)?.use { it.readAllBytes() }
            ?: error("Missing classpath font resource: $normalized")
        }
        .load("dwf-fonts")
    val frameRoboto =
      renderFrame(rcBytes, hour = 10, minute = 8, second = 32, typefaces = robotoLoader)
    assertTrue(countNonDarkPixels(frameRoboto) > 500)
    assertNotEquals(
      pixelDigest(frameSystemFont),
      pixelDigest(frameRoboto),
      "Roboto Flex must render distinct Wear OS glyphs compared to fallback system sans",
    )
  }

  private fun assertEqualsArgbAlpha(expectedAlpha: Int, argb: Int) {
    val actualAlpha = (argb ushr 24) and 0xFF
    assertEquals(expectedAlpha, actualAlpha)
  }

  private fun renderFrame(
    rcBytes: ByteArray,
    hour: Int,
    minute: Int,
    second: Int,
    overrides: Map<String, RcNamedValue> = emptyMap(),
    typefaces: ee.schimke.composeai.rcplayer.compose.RcTypefaceLoader? = null,
  ): Bitmap {
    val fixedTime =
      object : RcTimeSource {
        override fun currentTimeMillis(): Long = 1_763_000_000_000L

        override fun snapshot(epochMillis: Long): RcTimeSnapshot =
          RcTimeSnapshot(
            epochMillis = epochMillis,
            year = 2026,
            month = 11,
            dayOfMonth = 18,
            dayOfYear = 322,
            hour = hour,
            minute = minute,
            second = second,
            isoDayOfWeek = 3,
            offsetSeconds = 0,
          )
      }
    val scene = ImageComposeScene(width = 450, height = 450, density = Density(1f))
    try {
      val stateMap = mutableStateMapOf<String, RcNamedValue>().apply { putAll(overrides) }
      scene.setContent {
        CompositionLocalProvider(LocalRcTimeSource provides fixedTime) {
          if (typefaces == null) {
            RcComposePlayer(
              bytes = rcBytes,
              modifier = Modifier.size(450.dp, 450.dp),
              namedValues = stateMap,
            )
          } else {
            RcComposePlayer(
              bytes = rcBytes,
              modifier = Modifier.size(450.dp, 450.dp),
              namedValues = stateMap,
              typefaces = typefaces,
            )
          }
        }
      }
      val skiaImage = scene.render(0L)
      return Bitmap.makeFromImage(skiaImage)
    } finally {
      scene.close()
    }
  }

  private fun countNonDarkPixels(bitmap: Bitmap): Int {
    var count = 0
    for (y in 0 until bitmap.height step 2) {
      for (x in 0 until bitmap.width step 2) {
        val c = bitmap.getColor(x, y)
        val a = (c ushr 24) and 0xFF
        val r = (c ushr 16) and 0xFF
        val g = (c ushr 8) and 0xFF
        val b = c and 0xFF
        if (a > 32 && (r > 32 || g > 32 || b > 32)) count++
      }
    }
    return count
  }

  private fun pixelDigest(bitmap: Bitmap): Long {
    var h = 1125899906842597L
    for (y in 0 until bitmap.height step 3) {
      for (x in 0 until bitmap.width step 3) {
        h = 31L * h + bitmap.getColor(x, y).toLong()
      }
    }
    return h
  }
}
