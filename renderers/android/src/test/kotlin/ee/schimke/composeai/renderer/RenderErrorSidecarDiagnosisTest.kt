package ee.schimke.composeai.renderer

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Android sidecar's `diagnosis` field — the one actionable sentence for a failure that is about
 * the *render classpath* rather than about the preview's own code.
 *
 * The desktop renderer has carried this field since the native-load work (issue #3690); the Android
 * path wrote every failure as bare exception + stack trace. That reads badly for the Glance
 * version-floor failure (compose-ai-tools#5056), which takes out every app-widget preview in a
 * module at once and whose stack trace points at our renderer rather than at anything the project
 * did — exactly the case a one-line diagnosis exists for.
 */
class RenderErrorSidecarDiagnosisTest {

  private fun writeSidecar(e: Throwable): String {
    val dir = Files.createTempDirectory("render-error-sidecar").toFile()
    val png = File(dir, "GlanceWidget.png")
    RenderErrorSidecar.write(png, e)
    return RenderErrorSidecar.pathFor(png).readText()
  }

  @Test
  fun `carries the Glance version-floor sentence as the diagnosis`() {
    val json =
      writeSidecar(
        GlanceComposerUnavailableException(
          "No usable Glance app-widget composer on the render classpath. Upgrade to 1.2.0."
        )
      )

    assertTrue(json, json.contains("\"diagnosis\":\"No usable Glance app-widget composer"))
    assertTrue(json, json.contains("Upgrade to 1.2.0."))
    assertTrue(json, json.contains("\"exception\":\"ee.schimke.composeai.renderer."))
  }

  @Test
  fun `finds the diagnosis through a wrapping exception`() {
    // The renderer wraps per-preview failures on the way out of the Robolectric harness; a
    // diagnosis buried one cause deep is still the diagnosis.
    val json =
      writeSidecar(
        RuntimeException(
          "Failed to render GlanceWidget",
          GlanceComposerUnavailableException("Upgrade glance-appwidget to 1.2.0 or newer."),
        )
      )

    assertTrue(json, json.contains("\"diagnosis\":\"Upgrade glance-appwidget to 1.2.0 or newer.\""))
  }

  @Test
  fun `leaves the field off an ordinary preview throw`() {
    val json = writeSidecar(IllegalStateException("lateinit property viewModel is not initialized"))

    assertFalse(json, json.contains("\"diagnosis\""))
    assertTrue(json, json.contains("lateinit property viewModel"))
  }
}
