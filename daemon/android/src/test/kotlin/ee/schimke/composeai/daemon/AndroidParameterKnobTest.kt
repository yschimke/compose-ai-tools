package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.bridge.SandboxPreviewOverridesBridge
import ee.schimke.composeai.daemon.protocol.PreviewOverrideValue
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * End-to-end proof for the **parameter-knob** override format on the Robolectric backend — the
 * Android counterpart of `:daemon:desktop`'s
 * `OverrideIntegrationTest.parameterKnobSeedsOneParameter`.
 *
 * Two things have to hold, and only one of them is shared with desktop.
 *
 * **The preview has to resolve at all.** `getDeclaredComposableMethod(name)` matches only
 * `(Composer, int)`; [KnobbedSquare] compiles to `(Long, Long, String, Composer, changed,
 * default)`, so the bare lookup throws `NoSuchMethodException` before composition and the render
 * produces an `.error.json` rather than a PNG.
 *
 * **The knobs have to survive the sandbox boundary.** This backend composes inside a Robolectric
 * classloader that never sees the host-side `RenderSpec` — it parses the payload string
 * [RobolectricHost.reshapeRenderPayload] emits. So the host must encode a `knobs=` token and the
 * sandbox must parse it back, or the render sees a preview with no declared knobs and drops every
 * seed for one in silence. That round trip is what this test exercises that the desktop twin
 * cannot.
 *
 * Only `topArgb` is seeded, and the assertion checks **both** bands: the top proves the seed bound,
 * the bottom proves the unseeded position still ran its compiled default expression rather than
 * being passed a zero. That is the defaults mask doing its job, and it is the whole reason a client
 * can edit one knob of a preview that declares three without sending the other two.
 */
class AndroidParameterKnobTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun parameterKnobSeedCrossesTheSandboxAndRepaintsOneBand() {
    val outputDir = tempFolder.newFolder("parameter-knob-renders")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)

    val host = RobolectricHost(previewSpecResolver = previewSpecResolver())
    host.start()
    try {
      val blue = 0xFF42A5F5L
      val result =
        host.submit(
          RenderRequest.Render(
            payload =
              "previewId=$KNOBBED_PREVIEW_ID;overrides=${encodeTextBag("topArgb" to blue.toString())}"
          ),
          timeoutMs = 120_000,
        )
      assertNotNull("pngPath must be populated", result.pngPath)
      val png = decode(File(result.pngPath!!))
      val bluePct = pixelMatchPct(png, expectedRgb = 0x42A5F5, perChannelTolerance = 8)
      val greenPct = pixelMatchPct(png, expectedRgb = 0x66BB6A, perChannelTolerance = 8)
      assertTrue(
        "the seeded `topArgb` knob must repaint the top band blue; got " +
          "${"%.2f".format(bluePct * 100)}% blue — if 0, the `knobs=` token didn't reach the " +
          "sandbox or the seed didn't bind to the parameter",
        bluePct >= 0.4,
      )
      assertTrue(
        "the unseeded `bottomArgb` knob must keep its compiled default green; got " +
          "${"%.2f".format(greenPct * 100)}% green — the defaults mask dropped the default",
        greenPct >= 0.4,
      )
    } finally {
      host.shutdown()
    }
  }

  /**
   * The unseeded baseline. Without it the test above could pass on a renderer that ignored the seed
   * entirely and happened to paint blue for an unrelated reason.
   */
  @Test
  fun parameterKnobPreviewRendersItsDefaultsUnseeded() {
    val outputDir = tempFolder.newFolder("parameter-knob-default-renders")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)

    val host = RobolectricHost(previewSpecResolver = previewSpecResolver())
    host.start()
    try {
      val result =
        host.submit(
          RenderRequest.Render(payload = "previewId=$KNOBBED_PREVIEW_ID"),
          timeoutMs = 120_000,
        )
      assertNotNull("pngPath must be populated", result.pngPath)
      val png = decode(File(result.pngPath!!))
      assertTrue(
        "an unseeded parameter-knob preview must render its author defaults (red top band)",
        pixelMatchPct(png, expectedRgb = 0xEF5350, perChannelTolerance = 8) >= 0.4,
      )
    } finally {
      host.shutdown()
    }
  }

  /**
   * The declaration half, end to end on the backend where it has the furthest to travel: the knob's
   * **default** is read out of the class file by discovery, encoded into the `knobs=` payload token
   * by the host, parsed back inside the Robolectric sandbox, recorded as a
   * `PreviewOverrideDeclaration`, forwarded across the classloader boundary by the bridge, and read
   * back host-side by the registry that answers `data/fetch?kind=compose/overrides`.
   *
   * Every one of those hops is a place a default can quietly become nothing, and none of them
   * throws when it does — the render still succeeds and the viewer just shows no control. That is
   * why this asserts the fetched payload rather than any single hop.
   */
  @Test
  fun parameterKnobsReachDataFetchAsDeclarationsAcrossTheSandbox() {
    val outputDir = tempFolder.newFolder("parameter-knob-declared-renders")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty("roborazzi.test.record", "true")

    val host = RobolectricHost(previewSpecResolver = previewSpecResolver())
    val registry = PreviewOverridesDataProductRegistry()
    host.start()
    SandboxPreviewOverridesBridge.resetAll()
    try {
      val blue = 0xFF42A5F5L
      val result =
        host.submit(
          RenderRequest.Render(
            payload =
              "previewId=$KNOBBED_PREVIEW_ID;overrides=${encodeTextBag("topArgb" to blue.toString())}"
          ),
          timeoutMs = 120_000,
        )
      assertNotNull("pngPath must be populated", result.pngPath)

      // Hand the result to the registry the way `JsonRpcServer.handleRenderFinished` does.
      registry.onRender(
        KNOBBED_PREVIEW_ID,
        result,
        overrides = null,
        previewContext = result.previewContext,
      )
      val outcome =
        registry.fetch(KNOBBED_PREVIEW_ID, "compose/overrides", params = null, inline = true)
      val ok = outcome as DataProductRegistry.Outcome.Ok
      val declarations = ok.result.payload!!.jsonObject["declarations"]!!.jsonArray

      assertEquals(
        listOf("topArgb", "bottomArgb", "label"),
        declarations.map { it.jsonObject["key"]!!.jsonPrimitive.content },
      )
      // `Long` has no declaration type of its own, so both colours ride as text.
      assertEquals(
        listOf("string", "string", "string"),
        declarations.map { it.jsonObject["type"]!!.jsonPrimitive.content },
      )
      // The pair a control needs: what the author wrote, and what is in force. The seeded knob's
      // `current` is the seed; the unseeded one's is its own default, which is also what rendered.
      val current = declarations.associate { entry ->
        entry.jsonObject["key"]!!.jsonPrimitive.content to
          entry.jsonObject["current"]!!.jsonObject["value"]!!.jsonPrimitive.content
      }
      val default = declarations.associate { entry ->
        entry.jsonObject["key"]!!.jsonPrimitive.content to
          entry.jsonObject["default"]!!.jsonObject["value"]!!.jsonPrimitive.content
      }
      assertEquals(blue.toString(), current["topArgb"])
      assertEquals("4293874512", default["topArgb"])
      assertEquals("4284922730", current["bottomArgb"])
      assertEquals("hi", current["label"])
    } finally {
      host.shutdown()
    }
  }

  private fun previewSpecResolver(): (String) -> RenderSpec? = { previewId ->
    when (previewId) {
      KNOBBED_PREVIEW_ID ->
        RenderSpec(
          previewId = KNOBBED_PREVIEW_ID,
          className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
          functionName = "KnobbedSquare",
          widthPx = 32,
          heightPx = 32,
          density = 1.0f,
          showBackground = true,
          outputBaseName = KNOBBED_PREVIEW_ID,
          // Exactly what `renderSpecFromInfo` reads out of `previews.json` for this preview.
          knobs =
            listOf(
              PreviewKnobDto("topArgb", 0, "LONG", "4293874512"),
              PreviewKnobDto("bottomArgb", 1, "LONG", "4284922730"),
              PreviewKnobDto("label", 2, "STRING", "hi"),
            ),
        )
      else -> null
    }
  }

  /**
   * A `namedOverrides` bag of plain **text** values — the shape a parameter-knob seed takes. A
   * `ColorValue` has no parameter-knob equivalent (`Color` is not a seedable kind), so a colour
   * seed is deliberately dropped by `PreviewKnobSeeds` and cannot drive one.
   */
  private fun encodeTextBag(vararg entries: Pair<String, String>): String {
    val json = Json { encodeDefaults = false }
    val bag =
      PreviewOverrides(
        namedOverrides = entries.associate { (k, v) -> k to PreviewOverrideValue.StringValue(v) }
      )
    return Base64.getUrlEncoder()
      .withoutPadding()
      .encodeToString(
        json.encodeToString(PreviewOverrides.serializer(), bag).toByteArray(Charsets.UTF_8)
      )
  }

  private fun decode(file: File): java.awt.image.BufferedImage {
    require(file.exists()) { "expected capture at ${file.absolutePath}" }
    return ByteArrayInputStream(file.readBytes()).use { ImageIO.read(it) }
      ?: error("ImageIO refused to decode capture: ${file.absolutePath}")
  }

  private fun pixelMatchPct(
    img: java.awt.image.BufferedImage,
    expectedRgb: Int,
    perChannelTolerance: Int,
  ): Double {
    val expR = (expectedRgb shr 16) and 0xFF
    val expG = (expectedRgb shr 8) and 0xFF
    val expB = expectedRgb and 0xFF
    var matches = 0L
    for (y in 0 until img.height) {
      for (x in 0 until img.width) {
        val rgb = img.getRGB(x, y)
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        if (
          kotlin.math.abs(r - expR) <= perChannelTolerance &&
            kotlin.math.abs(g - expG) <= perChannelTolerance &&
            kotlin.math.abs(b - expB) <= perChannelTolerance
        ) {
          matches++
        }
      }
    }
    return matches.toDouble() / (img.width * img.height)
  }

  private companion object {
    const val KNOBBED_PREVIEW_ID = "knobbed-square"
  }
}
