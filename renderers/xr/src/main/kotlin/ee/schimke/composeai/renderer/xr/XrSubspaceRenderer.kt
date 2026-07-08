package ee.schimke.composeai.renderer.xr

import androidx.activity.ComponentActivity
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.reflect.getDeclaredComposableMethod
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import ee.schimke.composeai.daemon.ComposeSemanticsDataProducer
import java.io.File

/**
 * Renders one `@XrSubspacePreview` to a `scene.json` — the execution half of the XR producer that
 * the (separate) `:renderer-xr` Robolectric render task drives per discovered preview.
 *
 * It reflects the preview's `@Composable` function (the same way the Compose `@Preview` renderer
 * does — [getDeclaredComposableMethod] + invoke through the current [currentComposer]), composes
 * its `Subspace` on [rule], then hands off to
 * [SubspaceSceneRecorder.recordAll] + [SubspaceSceneWriter].
 *
 * The caller owns the Robolectric environment: it must enable the
 * [SubspaceSceneRecorder.XR_SPATIAL_FEATURE] system feature **before** calling this (so `Subspace`
 * takes its spatial path), exactly as the render task / the tests do. Each panel's content is
 * rasterised to its `<tag>.png` texture next to the `scene.json`, so the viewer shows the real
 * panels.
 */
public object XrSubspaceRenderer {

  /**
   * Composes the `@Composable` preview [functionName] on [className], records the subspace layout,
   * and writes `scene.json` into [outputDir]. Returns the written file.
   */
  public fun render(
    rule: AndroidComposeTestRule<*, ComponentActivity>,
    className: String,
    functionName: String,
    previewId: String,
    outputDir: File,
  ): File {
    val clazz = Class.forName(className)
    val method = clazz.getDeclaredComposableMethod(functionName)
    // Kotlin `private`/`internal` previews compile to inaccessible JVM methods; open them up so the
    // reflective invoke below succeeds (mirrors the Compose `@Preview` renderer).
    runCatching { method.asMethod().isAccessible = true }
    val receiver = resolveReceiver(clazz)

    // Pre-create + configure the offline XR Session with device tracking + a seeded viewer head
    // pose
    // BEFORE setContent, so `rotateToLookAtUser` (the billboard modifier) can source a head pose
    // and
    // face the viewer instead of crashing on an uninitialised `arDevice`. Harmless for previews
    // that
    // don't use it — it just hands `Subspace` a ready-configured session. See FakeXrHeadPose.
    FakeXrHeadPose.install(rule)

    rule.setContent { method.invoke(currentComposer, receiver) }
    rule.waitForIdle()

    val recorded = SubspaceSceneRecorder.recordAllWithViews(rule, previewId = previewId)
    SubspaceSceneWriter.captureViewTextures(outputDir, recorded.scene.panels, recorded.panelViews)
    val sceneFile = SubspaceSceneWriter.writeScene(outputDir, recorded.scene)

    // Unified 3D-over-2D semantics tree (`compose/spatial-semantics`): the real multi-panel layout
    // with each panel carrying its 2D `ComposeSemanticsNode` tree, projected by the daemon-side
    // connector (the same projection `compose/semantics` + the wireframe use, supplied here rather
    // than imported so the projection stays single-sourced). Best-effort and isolated — a
    // projection
    // failure must never strand the `scene.json`/textures the compositor needs, so a panel whose
    // semantics can't be read just lands with a null `panelContent`.
    runCatching {
        val tree =
          SubspaceSceneRecorder.recordTree(rule, previewId = previewId) {
            ComposeSemanticsDataProducer.buildPayload(it).root
          }
        SubspaceSceneWriter.writeSemanticsTree(outputDir, tree)
      }
      .onFailure {
        System.err.println(
          "XrSubspaceRenderer: spatial-semantics write failed for $previewId: " +
            "${it.javaClass.simpleName}: ${it.message}"
        )
      }

    return sceneFile
  }

  /**
   * The JVM receiver for the preview method: a Kotlin `object`'s `INSTANCE`, else a fresh
   * nullary-ctor instance for a regular class, else `null` for a top-level function (which compiles
   * to a static method on the file's synthetic `…Kt` class). Mirrors `ComposeViewAdapter` / the
   * Compose `@Preview` renderer's `resolvePreviewReceiver`.
   */
  private fun resolveReceiver(clazz: Class<*>): Any? {
    runCatching { clazz.getField("INSTANCE").get(null) }
      .getOrNull()
      ?.let {
        return it
      }
    return runCatching {
        val ctor = clazz.getDeclaredConstructor()
        ctor.isAccessible = true
        ctor.newInstance()
      }
      .getOrNull()
  }
}
