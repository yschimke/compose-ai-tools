package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.PermissionGrantStateOverride
import ee.schimke.composeai.daemon.protocol.PermissionsOverride
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlinx.serialization.json.Json
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * End-to-end verification that `renderNow.overrides.permissions` actually drives a
 * permission-gated composable to its granted branch. Mirrors the
 * [OverrideIntegrationTest.uiModeOverrideFlipsDarkAwareComposable] shape — render the same
 * fixture twice with different overrides and assert the bytes differ in the expected way —
 * but targets the runtime-permissions surface added in #1370 / #1374 / #1381 / #1395 and
 * unblocks the panel-side override-toggle UI from #1400 part 2.
 *
 * The override is built panel-side as a [PreviewOverrides] bag, base64-serialised by
 * [JsonRpcServer.encodeRenderPayload] into the wire payload's `overrides=<b64>` token, and
 * round-tripped on the renderer side via [RenderEngine]'s `decodePreviewOverrides`. The
 * planner ([PermissionsPreviewOverrideExtension]) lifts the override into a
 * [PermissionsOverrideExtension], whose around-composable seeds Robolectric's
 * `ShadowApplication.grantPermissions/denyPermissions`. From there the standard Android path
 * (`Context.checkSelfPermission(...)` → `ContextWrapper.checkPermission(String, int, int)`)
 * returns the requested value — including through the
 * [ShadowContextWrapperPermissionTracker] shadow, which forwards to the real implementation
 * before recording the query. Without all five rungs the pixels never flip.
 *
 * Encoding the bag directly (rather than going through [JsonRpcServer]'s renderNow path) keeps
 * the test focused on the renderer-side leg and avoids the JSON-RPC plumbing — the encoder
 * leg is covered separately by [PermissionsOverrideEncodingTest] in `:daemon:core`. Together
 * the two tests pin the full panel → daemon → renderer → shadow chain.
 *
 * Out of scope here: asserting `data/fetch?kind=compose/permissions` returns the queried
 * list — that capture happens inside the sandbox's [PermissionsController] static state, and
 * the daemon-side `PermissionsDataProductRegistry` reads it from there. Cross-classloader
 * read-out from the JUnit runner would need a dedicated surfacing channel; the unit-level
 * `PermissionsDataProductTest.onRender captures override grants plus controller queries`
 * pins the registry's behaviour for now. Pixel correctness is the strongest single E2E
 * signal: it can only succeed if every rung worked.
 */
class PermissionsOverrideIntegrationTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
  }

  @Test
  fun permissionsOverrideGrantsCameraAndFlipsTheCompositionToTheGrantedBranch() {
    val outputDir = tempFolder.newFolder("renders-permissions")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty("roborazzi.test.record", "true")
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = "permission-gated",
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "PermissionGatedSquare",
              widthPx = 32,
              heightPx = 32,
              density = 1.0f,
              outputBaseName = "permission-gated",
            )
          )
      )
    val host = PreviewManifestRouter(manifest = manifest)
    host.start()
    try {
      // Default render — no override sent. Robolectric's manifest baseline denies CAMERA,
      // so the fixture lands on the red branch.
      val denied = renderAndDecode(host, "previewId=permission-gated", "denied")
      val deniedRedPct = pixelMatchPct(denied, expectedRgb = 0xEF5350, perChannelTolerance = 8)
      assertTrue(
        "default render should be mostly red (denied branch); got" +
          " ${"%.2f".format(deniedRedPct * 100)}% red",
        deniedRedPct >= 0.95,
      )

      // Override pushes CAMERA = GRANTED through the same wire shape the panel produces.
      // The around-composable's `init` seeds the controller (which pushes the grant into
      // `ShadowApplication.grantPermissions(...)`) BEFORE the first composition starts, so the
      // very first `checkSelfPermission` read in the composition returns GRANTED and the
      // composition lands on the green branch on this render — no warm-up second render needed.
      val grantedPayload =
        "previewId=permission-gated;overrides=" +
          encodeOverridesBag(
            PreviewOverrides(
              permissions =
                PermissionsOverride(
                  grants =
                    mapOf(
                      "android.permission.CAMERA" to
                        PermissionGrantStateOverride.GRANTED
                    )
                )
            )
          )
      val granted = renderAndDecode(host, grantedPayload, "granted")
      val grantedGreenPct =
        pixelMatchPct(granted, expectedRgb = 0x66BB6A, perChannelTolerance = 8)
      assertTrue(
        "override-applied render should be mostly green (granted branch); got" +
          " ${"%.2f".format(grantedGreenPct * 100)}% green. If this dropped, the wire bag may" +
          " no longer carry `permissions`, the planner may be skipping its always-on contract," +
          " or the shadow tracker may be intercepting without forwarding to the real" +
          " ShadowApplication grant state.",
        grantedGreenPct >= 0.95,
      )

      // Override flipped back to DENIED → red again. Proves the round-trip is symmetric;
      // a stuck "always grants" wiring would still produce green here and the test would fail.
      val deniedPayload =
        "previewId=permission-gated;overrides=" +
          encodeOverridesBag(
            PreviewOverrides(
              permissions =
                PermissionsOverride(
                  grants =
                    mapOf(
                      "android.permission.CAMERA" to PermissionGrantStateOverride.DENIED
                    )
                )
            )
          )
      val deniedAgain = renderAndDecode(host, deniedPayload, "denied-after-grant")
      val deniedAgainRedPct =
        pixelMatchPct(deniedAgain, expectedRgb = 0xEF5350, perChannelTolerance = 8)
      assertTrue(
        "explicit DENIED override should land on the red branch; got" +
          " ${"%.2f".format(deniedAgainRedPct * 100)}% red",
        deniedAgainRedPct >= 0.95,
      )
    } finally {
      host.shutdown()
    }
  }

  /**
   * Encodes a [PreviewOverrides] bag the way [JsonRpcServer.encodeRenderPayload] does — UTF-8 JSON
   * → URL-safe base64 (no padding). The renderer's `decodePreviewOverrides` mirror in
   * `RenderEngine.kt` reverses this; the round-trip is what production daemons use.
   */
  private fun encodeOverridesBag(bag: PreviewOverrides): String {
    val bytes =
      json.encodeToString(PreviewOverrides.serializer(), bag).toByteArray(Charsets.UTF_8)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
  }

  private fun renderAndDecode(
    host: PreviewManifestRouter,
    payload: String,
    label: String,
  ): java.awt.image.BufferedImage {
    val request = RenderRequest.Render(payload = payload)
    val result = host.submit(request, timeoutMs = 120_000)
    assertNotNull("$label: pngPath must be populated", result.pngPath)
    val pngFile = File(result.pngPath!!)
    assertTrue("$label: rendered PNG must exist", pngFile.exists())
    return ByteArrayInputStream(pngFile.readBytes()).use { ImageIO.read(it) }
      ?: error("$label: PNG failed to decode")
  }

  /**
   * Fraction of pixels matching [expectedRgb] within [perChannelTolerance] per channel. Mirrors
   * the helper in [OverrideIntegrationTest] verbatim; kept private here rather than promoted so
   * the two tests stay independent.
   */
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
          abs(r - expR) <= perChannelTolerance &&
            abs(g - expG) <= perChannelTolerance &&
            abs(b - expB) <= perChannelTolerance
        ) {
          matches++
        }
      }
    }
    val total = img.width.toLong() * img.height.toLong()
    return matches.toDouble() / total.toDouble()
  }
}
