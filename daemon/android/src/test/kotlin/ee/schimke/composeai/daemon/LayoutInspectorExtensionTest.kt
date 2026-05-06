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
import org.junit.Assert.assertThrows
import org.junit.Test

class LayoutInspectorExtensionTest {

  @Test
  fun `extension declares after-capture hook only`() {
    val extension = LayoutInspectorExtension()
    assertEquals("layout/inspector", extension.id.value)
    assertEquals(setOf(DataExtensionHookKind.AfterCapture), extension.hooks)
    assertEquals(DataExtensionPhase.Capture, extension.constraints.phase)
    assertEquals(setOf(DataExtensionTarget.Android), extension.targets)
  }

  @Test
  fun `process fails fast when the layout-inspector preview context is missing`() {
    val extension = LayoutInspectorExtension()
    val rootDir = Files.createTempDirectory("layout-inspector-extension-test").toFile()
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
      assertThrows(IllegalStateException::class.java) { extension.process(context) }
    } finally {
      rootDir.deleteRecursively()
    }
  }
}
