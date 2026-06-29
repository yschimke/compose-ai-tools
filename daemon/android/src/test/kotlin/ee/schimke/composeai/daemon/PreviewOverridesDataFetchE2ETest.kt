package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.bridge.SandboxPreviewOverridesBridge
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * End-to-end verification that the opt-in `previewOverride*` knobs a screen declares *inside the
 * Robolectric sandbox* surface in the host-side `compose/overrides` data product the panel / MCP read
 * via `data/fetch`. This is the Android leg the merged feature deferred — the desktop daemon (no
 * sandbox) already worked, but on Android the declarations were stranded in the sandbox classloader.
 *
 * **Why a cross-classloader bridge.** `PreviewOverrideController` is loaded by the Robolectric sandbox
 * classloader (the `ee.schimke.composeai` namespace is acquired), so the host-side
 * `PreviewOverridesDataProductRegistry` reads a *different, empty* static copy. The fixture's
 * `previewOverride*` calls record into the sandbox-CL controller; `SandboxPreviewOverridesBridge` —
 * in the `ee.schimke.composeai.daemon.bridge` do-not-acquire package (single instance across the
 * boundary, like `SandboxPermissionsBridge`) — carries them across, and the registry reads them back.
 *
 * Mirrors [PermissionsDataFetchE2ETest]; exercises the registry directly with the same render-result
 * wire shape `JsonRpcServer.handleRenderFinished` feeds `extensions.activeDataProducts().onRender(...)`.
 */
class PreviewOverridesDataFetchE2ETest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @After
  fun resetBridge() {
    // JVM-wide singleton; sibling test classes / methods that exercised the sandbox can leak
    // declarations into ours. Wipe every preview scope so each assertion sees only its own render.
    SandboxPreviewOverridesBridge.resetAll()
  }

  @Test
  fun `dataFetch returns the knobs the rendered screen declared`() {
    val outputDir = tempFolder.newFolder("renders-overrides-fetch")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty("roborazzi.test.record", "true")
    val previewId = "overridable-square-fetch"
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = previewId,
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "OverridableSquare",
              widthPx = 32,
              heightPx = 32,
              density = 1.0f,
              outputBaseName = previewId,
            )
          )
      )
    val host = PreviewManifestRouter(manifest = manifest)
    val registry = PreviewOverridesDataProductRegistry()
    host.start()
    SandboxPreviewOverridesBridge.resetAll()
    try {
      // Plain render (no override seed): the always-on `data/overrides` planner installs
      // `LocalPreviewOverrideHost`, and `OverridableSquare`'s `previewOverride*` calls record `fill`
      // + `label` into the sandbox-CL controller, which forwards them to the bridge.
      val result =
        host.submit(RenderRequest.Render(payload = "previewId=$previewId"), timeoutMs = 120_000)
      assertNotNull("pngPath must be populated", result.pngPath)

      // Hand the result to the registry the way `JsonRpcServer.handleRenderFinished` does. The
      // registry's `onRender` reads the bridge snapshot (host-CL read of the sandbox-CL declarations).
      registry.onRender(previewId, result, overrides = null, previewContext = result.previewContext)

      val outcome = registry.fetch(previewId, "compose/overrides", params = null, inline = true)
      val ok = outcome as DataProductRegistry.Outcome.Ok
      val declarations = ok.result.payload!!.jsonObject["declarations"]!!.jsonArray

      // The two declared knobs cross the sandbox boundary, in declaration order, with their types.
      // If this drops, either the controller's bridge forward stopped (`PreviewOverrideController
      // .record`), the bridge package isn't do-not-acquire (`SandboxHoldingRunner`), or the registry's
      // bridge read regressed (`readDeclarationsAcrossClassloaders`).
      val keys = declarations.map { it.jsonObject["key"]!!.jsonPrimitive.content }
      val types = declarations.map { it.jsonObject["type"]!!.jsonPrimitive.content }
      assertEquals(listOf("fill", "label"), keys)
      assertEquals(listOf("color", "string"), types)
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun `dataFetch is NotAvailable for a preview that declared no knobs`() {
    val outputDir = tempFolder.newFolder("renders-overrides-empty")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty("roborazzi.test.record", "true")
    val previewId = "red-square-no-knobs"
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = previewId,
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "RedSquare",
              widthPx = 16,
              heightPx = 16,
              density = 1.0f,
              outputBaseName = previewId,
            )
          )
      )
    val host = PreviewManifestRouter(manifest = manifest)
    val registry = PreviewOverridesDataProductRegistry()
    host.start()
    SandboxPreviewOverridesBridge.resetAll()
    try {
      val result =
        host.submit(RenderRequest.Render(payload = "previewId=$previewId"), timeoutMs = 120_000)
      assertNotNull("pngPath must be populated", result.pngPath)
      registry.onRender(previewId, result, overrides = null, previewContext = result.previewContext)
      assertTrue(
        "a preview with no previewOverride* calls must not advertise compose/overrides",
        registry.fetch(previewId, "compose/overrides", params = null, inline = true)
          is DataProductRegistry.Outcome.NotAvailable,
      )
    } finally {
      host.shutdown()
    }
  }
}
