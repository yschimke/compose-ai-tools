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

/**
 * Pins the portability contract of the shared [ComposeSemanticsExtension]: an after-capture,
 * capture-phase processor targeting *both* backends, so the Android daemon (via a factory wrapper)
 * and the Desktop daemon (constructed directly) register the same class instead of each carrying
 * their own `compose/semantics` production. Lives here next to the moved class; replaces the former
 * `:daemon:android` copy.
 */
class ComposeSemanticsExtensionTest {
  @Test
  fun `is an after-capture capture-phase processor targeting both backends`() {
    val ext = ComposeSemanticsExtension()
    assertEquals(ComposeSemanticsDataProducer.KIND, ext.id.value)
    assertEquals(setOf(DataExtensionHookKind.AfterCapture), ext.hooks)
    assertEquals(DataExtensionPhase.Capture, ext.constraints.phase)
    assertEquals(setOf(DataExtensionTarget.Android, DataExtensionTarget.Desktop), ext.targets)
  }

  @Test
  fun `process fails fast when the captured semantics root is missing`() {
    val ext = ComposeSemanticsExtension()
    val rootDir = Files.createTempDirectory("compose-semantics-extension-test").toFile()
    try {
      val store = RecordingDataProductStore()
      val context =
        ExtensionPostCaptureContext(
          extensionId = ext.id,
          previewId = null,
          renderMode = null,
          products = store.scopedFor(ext),
          data =
            ExtensionContextData.of(
              RenderArtifactContextKeys.RootDir provides rootDir,
              RenderArtifactContextKeys.OutputBaseName provides "preview-base",
            ),
        )
      assertThrows(IllegalStateException::class.java) { ext.process(context) }
    } finally {
      rootDir.deleteRecursively()
    }
  }
}
