package ee.schimke.composeai.daemon

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
import org.junit.Test

class AccessibilityArtifactExtensionTest {

  @Test
  fun `extension declares after-capture hook only`() {
    val extension = AccessibilityArtifactExtension()
    assertEquals("a11y/postCaptureArtifact", extension.id.value)
    assertEquals(setOf(DataExtensionHookKind.AfterCapture), extension.hooks)
    assertEquals(DataExtensionPhase.Capture, extension.constraints.phase)
    assertEquals(setOf(DataExtensionTarget.Android), extension.targets)
  }

  @Test
  fun `process is a no-op when the accessibility view-root key is absent`() {
    val extension = AccessibilityArtifactExtension()
    val rootDir = Files.createTempDirectory("a11y-extension-test").toFile()
    try {
      val store = RecordingDataProductStore()
      val context =
        ExtensionPostCaptureContext(
          extensionId = extension.id,
          previewId = null,
          renderMode = null,
          products = store.scopedFor(extension),
          // Required keys present except for AccessibilityViewRoot — the gate. Extension must
          // bail without throwing or writing any sidecar.
          data =
            ExtensionContextData.of(
              RenderDataArtifactContextKeys.RootDir provides rootDir,
              RenderDataArtifactContextKeys.OutputBaseName provides "preview-base",
              RenderDataArtifactContextKeys.RenderDensity provides 2.0f,
              RenderDataArtifactContextKeys.OutputPngFile provides rootDir.resolve("preview.png"),
              RenderDataArtifactContextKeys.IsRoundScreen provides false,
            ),
        )

      extension.process(context)

      assertFalse(
        "no a11y artefact should be written without the view-root gate key",
        rootDir.walkTopDown().any { it.isFile },
      )
    } finally {
      rootDir.deleteRecursively()
    }
  }
}
