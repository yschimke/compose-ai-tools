package ee.schimke.composeai.daemon

import androidx.activity.ComponentActivity
import ee.schimke.composeai.data.render.extensions.DataExtensionHookKind
import ee.schimke.composeai.data.render.extensions.DataExtensionPhase
import ee.schimke.composeai.data.render.extensions.DataExtensionTarget
import ee.schimke.composeai.data.render.extensions.ExtensionContextData
import ee.schimke.composeai.data.render.extensions.ExtensionPostCaptureContext
import ee.schimke.composeai.data.render.extensions.RecordingDataProductStore
import ee.schimke.composeai.data.render.extensions.provides
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins [NavigationExtension] — the Android-only post-capture extension that emits the
 * `data/navigation` artefact for the rendered preview.
 *
 * Robolectric is required because `process()` writes a real `Intent` + `OnBackPressedDispatcher`
 * snapshot; the activity is built with `Robolectric.buildActivity(ComponentActivity::class)` so we
 * exercise the same code path the production held-rule loop hits.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NavigationExtensionTest {

  @Test
  fun `extension declares after-capture hook only`() {
    val extension = NavigationExtension()
    assertEquals("data/navigation", extension.id.value)
    assertEquals(setOf(DataExtensionHookKind.AfterCapture), extension.hooks)
    assertEquals(DataExtensionPhase.Capture, extension.constraints.phase)
    assertEquals(setOf(DataExtensionTarget.Android), extension.targets)
  }

  @Test
  fun `process skips silently when the held activity is missing`() {
    // Defensive: hosts that don't carry a held activity (a hypothetical desktop-on-Android target)
    // shouldn't strand the post-capture pass with an IllegalStateException — the extension just
    // emits no artefact and lets `attachmentsFor` see a missing file. Mirrors the I18n / Compose
    // semantics extensions which DO require their key — the difference is intentional.
    val extension = NavigationExtension()
    val rootDir = Files.createTempDirectory("navigation-extension-test").toFile()
    try {
      val store = RecordingDataProductStore()
      val context =
        ExtensionPostCaptureContext(
          extensionId = extension.id,
          previewId = null,
          renderMode = null,
          products = store.scopedFor(extension),
          data =
            ExtensionContextData.of(
              RenderDataArtifactContextKeys.RootDir provides rootDir,
              RenderDataArtifactContextKeys.OutputBaseName provides "preview-base",
            ),
        )
      extension.process(context)
      assertFalse(
        "no navigation.json must be written when the activity context key is absent",
        rootDir.resolve("preview-base").resolve("navigation.json").exists(),
      )
    } finally {
      rootDir.deleteRecursively()
    }
  }

  @Test
  fun `process writes navigation json when the held activity is present`() {
    val extension = NavigationExtension()
    val rootDir = Files.createTempDirectory("navigation-extension-write-test").toFile()
    val activityController = Robolectric.buildActivity(ComponentActivity::class.java).setup()
    try {
      val store = RecordingDataProductStore()
      val context =
        ExtensionPostCaptureContext(
          extensionId = extension.id,
          previewId = null,
          renderMode = null,
          products = store.scopedFor(extension),
          data =
            ExtensionContextData.of(
              RenderDataArtifactContextKeys.RootDir provides rootDir,
              RenderDataArtifactContextKeys.OutputBaseName provides "preview-base",
              RenderDataArtifactContextKeys.HeldActivity provides activityController.get(),
            ),
        )
      extension.process(context)

      val artefact = rootDir.resolve("preview-base").resolve("navigation.json")
      assertTrue("navigation.json must be written next to the preview", artefact.exists())
      val body = artefact.readText()
      assertNotNull("payload must include onBackPressed state", body)
      assertTrue(
        "payload must include the hasEnabledCallbacks field",
        body.contains("hasEnabledCallbacks"),
      )
    } finally {
      activityController.close()
      rootDir.deleteRecursively()
    }
  }
}
