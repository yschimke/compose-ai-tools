package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.bridge.SandboxPermissionsBridge
import ee.schimke.composeai.daemon.protocol.PermissionGrantStateOverride
import ee.schimke.composeai.daemon.protocol.PermissionsOverride
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * End-to-end verification that a render driven through the sandbox surfaces the
 * `ContextCompat.checkSelfPermission(...)` queries the screen issued into the `compose/permissions`
 * data product the panel reads via `data/fetch`. Closes issue #1400 Part 3 step 3.
 *
 * The pixel-flip leg ([PermissionsOverrideIntegrationTest]) already pins the grant-write side of
 * `renderNow.overrides.permissions` end-to-end — a granted override produces the green branch on
 * the first composition. The previously-missing leg is the read-back: a render that fires
 * `ContextCompat.checkSelfPermission(CAMERA)` inside the sandbox must land CAMERA in the host-side
 * [PermissionsDataProductRegistry]'s `data/fetch` payload.
 *
 * **Why a cross-classloader bridge.** [PermissionsController] is loaded by the Robolectric sandbox
 * classloader (the `ee.schimke.composeai.daemon` package is acquired). The host-side
 * [PermissionsDataProductRegistry] is loaded by the daemon classloader. Each classloader has its
 * own static `PermissionsController` instance, so the shadow's sandbox-side `recordQuery(...)`
 * writes are invisible to the host-CL registry. [SandboxPermissionsBridge] sits in the
 * `ee.schimke.composeai.daemon.bridge` package (registered as `doNotAcquirePackage` on
 * [SandboxHoldingRunner]), so it's a single instance shared across the boundary — the controller
 * forwards `recordQuery` to it reflectively, and the registry reads it back the same way.
 *
 * **Out of scope.** This test does NOT go through `JsonRpcServer.handleDataFetch` — the JSON-RPC
 * envelope leg is already covered by `:daemon:core`'s `DataFetchRerenderTest` and the registry
 * dispatch shape doesn't change here. We exercise the registry directly to keep the test focused on
 * the cross-classloader path that #1400 calls out, and use the same render-result wire shape the
 * JSON-RPC server would feed to `extensions.activeDataProducts().onRender(...)`.
 */
class PermissionsDataFetchE2ETest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
  }

  @After
  fun resetBridge() {
    // Bridge is a JVM-wide singleton; previous test methods or sibling test classes that
    // exercised the sandbox can leak queries into ours. Reset every preview scope here AND between
    // render submissions below so each assertion sees only the queries from the render it gated.
    SandboxPermissionsBridge.resetAll()
  }

  @Test
  fun `dataFetch returns the permissions the rendered screen queried`() {
    val outputDir = tempFolder.newFolder("renders-permissions-fetch")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty("roborazzi.test.record", "true")
    val previewId = "permission-gated-fetch"
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = previewId,
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "PermissionGatedSquare",
              widthPx = 32,
              heightPx = 32,
              density = 1.0f,
              outputBaseName = previewId,
            )
          )
      )
    val host = PreviewManifestRouter(manifest = manifest)
    val registry = PermissionsDataProductRegistry()
    host.start()
    try {
      // Push CAMERA = GRANTED through the same wire shape the panel produces. The around-composable
      // primes ShadowApplication grants pre-composition (the seed path proven by
      // `PermissionsOverrideIntegrationTest`), and the `ContextCompat.checkSelfPermission(CAMERA)`
      // read in `PermissionGatedSquare` flows through `ShadowContextWrapperPermissionTracker`,
      // which records CAMERA against the sandbox-CL controller AND the cross-CL bridge.
      val overrideBag =
        PreviewOverrides(
          permissions =
            PermissionsOverride(
              grants = mapOf("android.permission.CAMERA" to PermissionGrantStateOverride.GRANTED)
            )
        )
      val payload = "previewId=$previewId;overrides=${encodeOverridesBag(overrideBag)}"
      SandboxPermissionsBridge.resetAll()
      val result = host.submit(RenderRequest.Render(payload = payload), timeoutMs = 120_000)

      // Pixel correctness — sanity check that the override actually drove the granted branch.
      // If this drops, the failure isn't the data-fetch path; it's the override-application path
      // (see `PermissionsOverrideIntegrationTest` for the dedicated regression).
      assertNotNull("pngPath must be populated", result.pngPath)
      val img =
        ByteArrayInputStream(File(result.pngPath!!).readBytes()).use { ImageIO.read(it) }
          ?: error("PNG failed to decode")
      val greenPct = pixelMatchPct(img, expectedRgb = 0x66BB6A, perChannelTolerance = 8)
      assertTrue(
        "render should land on the granted (green) branch; got ${"%.2f".format(greenPct * 100)}% green",
        greenPct >= 0.95,
      )

      // Hand the rendered result to the registry the same way `JsonRpcServer.handleRenderFinished`
      // does. The registry's `onRender` reads the bridge's snapshot (host-CL read of the
      // cross-classloader queries the sandbox-CL shadow wrote) and captures the payload for the
      // preview id.
      registry.onRender(previewId, result, overrideBag, previewContext = result.previewContext)

      val outcome = registry.fetch(previewId, "compose/permissions", params = null, inline = true)
      val ok = outcome as DataProductRegistry.Outcome.Ok
      val obj = ok.result.payload!!.jsonObject

      // Queried — the assertion #1400 part 3 step 3 calls out. CAMERA must appear because the
      // shadow caught it. If this drops, either:
      //  - `ShadowContextWrapperPermissionTracker` isn't wired (check `SandboxHoldingRunner
      //    .getExtraShadows`),
      //  - the controller's bridge forward stopped running (check `PermissionsController
      //    .recordQuery`'s `bridgeForwarder?.recordQuery` call),
      //  - or the registry's bridge read regressed (check `PermissionsDataProductRegistry
      //    .readQueriedAcrossClassloaders`).
      val queried = obj["queried"]!!.jsonArray.map { it.jsonPrimitive.content }
      assertEquals(
        "data/fetch?kind=compose/permissions should report CAMERA as queried",
        listOf("android.permission.CAMERA"),
        queried,
      )

      // Grants leg — the host-side planner already seeds `PermissionsController.grants.value` in
      // the host CL (it constructs `PermissionsOverrideExtension(seed=…)` whose `init {
      // PermissionsController.set(seed) }` block runs host-side), so the registry's
      // `overrides?.permissions?.grants + controller.grants.value` returns the override regardless
      // of the cross-classloader bridge. Asserted here so a future refactor that moves the seed
      // to sandbox-side construction doesn't silently break the grants leg too.
      val grants = obj["grants"]!!.jsonObject
      assertEquals("granted", grants["android.permission.CAMERA"]?.jsonPrimitive?.content)
    } finally {
      host.shutdown()
    }
  }

  private fun encodeOverridesBag(bag: PreviewOverrides): String {
    val bytes = json.encodeToString(PreviewOverrides.serializer(), bag).toByteArray(Charsets.UTF_8)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
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
