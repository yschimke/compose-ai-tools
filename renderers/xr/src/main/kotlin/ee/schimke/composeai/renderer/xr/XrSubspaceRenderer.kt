package ee.schimke.composeai.renderer.xr

import androidx.activity.ComponentActivity
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.reflect.getDeclaredComposableMethod
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import java.io.File

/**
 * Renders one `@XrSubspacePreview` to a `scene.json` — the execution half of the XR producer that
 * the (separate) `:renderer-xr` Robolectric render task drives per discovered preview.
 *
 * It reflects the preview's `@Composable` function (the same way the Compose `@Preview` renderer
 * does — [getDeclaredComposableMethod] + invoke through the current [currentComposer]), composes its
 * `Subspace` on [rule], then hands off to [SubspaceSceneRecorder.recordAll] + [SubspaceSceneWriter].
 *
 * The caller owns the Robolectric environment: it must enable the
 * [SubspaceSceneRecorder.XR_SPATIAL_FEATURE] system feature **before** calling this (so `Subspace`
 * takes its spatial path), exactly as the render task / the tests do. Geometry-only: panel textures
 * aren't captured here — the emitted scene carries `<tag>.png` paths the viewer renders as
 * placeholders until the texture pass lands.
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

    rule.setContent { method.invoke(currentComposer, receiver) }
    rule.waitForIdle()

    val scene = SubspaceSceneRecorder.recordAll(rule, previewId = previewId)
    return SubspaceSceneWriter.writeScene(outputDir, scene)
  }

  /**
   * The JVM receiver for the preview method: a Kotlin `object`'s `INSTANCE`, else a fresh
   * nullary-ctor instance for a regular class, else `null` for a top-level function (which compiles
   * to a static method on the file's synthetic `…Kt` class). Mirrors `ComposeViewAdapter` /
   * the Compose `@Preview` renderer's `resolvePreviewReceiver`.
   */
  private fun resolveReceiver(clazz: Class<*>): Any? {
    runCatching { clazz.getField("INSTANCE").get(null) }.getOrNull()?.let { return it }
    return runCatching {
        val ctor = clazz.getDeclaredConstructor()
        ctor.isAccessible = true
        ctor.newInstance()
      }
      .getOrNull()
  }
}
