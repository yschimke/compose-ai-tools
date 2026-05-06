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

class I18nTranslationsExtensionTest {

  @Test
  fun `extension declares after-capture hook only`() {
    val extension = I18nTranslationsExtension()
    assertEquals("i18n/translations", extension.id.value)
    assertEquals(setOf(DataExtensionHookKind.AfterCapture), extension.hooks)
    assertEquals(DataExtensionPhase.Capture, extension.constraints.phase)
    assertEquals(setOf(DataExtensionTarget.Android), extension.targets)
  }

  @Test
  fun `process fails fast when the captured semantics root is missing`() {
    val extension = I18nTranslationsExtension()
    val rootDir = Files.createTempDirectory("i18n-extension-test").toFile()
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
      // No SemanticsRoot key present — the extension must surface a clear error rather than
      // silently emit a half-populated artefact.
      assertThrows(IllegalStateException::class.java) { extension.process(context) }
    } finally {
      rootDir.deleteRecursively()
    }
  }
}
