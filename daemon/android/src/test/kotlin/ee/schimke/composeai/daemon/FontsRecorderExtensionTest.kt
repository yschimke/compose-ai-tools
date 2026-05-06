package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.render.extensions.DataExtensionHookKind
import ee.schimke.composeai.data.render.extensions.DataExtensionTarget
import ee.schimke.composeai.data.render.extensions.ExtensionContextData
import ee.schimke.composeai.data.render.extensions.ExtensionPostCaptureContext
import ee.schimke.composeai.data.render.extensions.RecordingDataProductStore
import ee.schimke.composeai.data.render.extensions.provides
import java.nio.file.Files
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FontsRecorderExtensionTest {

  @Test
  fun `extension declares around-composable plus after-capture hooks`() {
    val extension = FontsRecorderExtension()
    assertEquals("fonts/used", extension.id.value)
    assertEquals(
      setOf(DataExtensionHookKind.AroundComposable, DataExtensionHookKind.AfterCapture),
      extension.hooks,
    )
    assertEquals(setOf(DataExtensionTarget.Android), extension.targets)
    assertEquals(FontsRecorderExtension.ID, extension.id)
  }

  @Test
  fun `process writes empty fonts-used artefact when no fonts were recorded`() {
    val extension = FontsRecorderExtension()
    val rootDir = Files.createTempDirectory("fonts-extension-test").toFile()
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

      val artefact = rootDir.resolve("preview-base").resolve(FontsUsedDataProducer.FILE)
      assertTrue("expected fonts-used.json at $artefact", artefact.exists())
      val payload =
        FontsUsedDataProducer.json.parseToJsonElement(artefact.readText()).jsonObject
      assertTrue("empty recorder should produce empty fonts list", payload["fonts"]!!.jsonArray.isEmpty())
    } finally {
      rootDir.deleteRecursively()
    }
  }

  @Test
  fun `process prefers protocol previewId over outputBaseName when present`() {
    val extension = FontsRecorderExtension()
    val rootDir = Files.createTempDirectory("fonts-extension-protocol-id").toFile()
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
              RenderDataArtifactContextKeys.OutputBaseName provides "fallback-base",
              RenderDataArtifactContextKeys.PreviewId provides "protocol-id",
            ),
        )
      extension.process(context)

      val byProtocolId = rootDir.resolve("protocol-id").resolve(FontsUsedDataProducer.FILE)
      val byBaseName = rootDir.resolve("fallback-base").resolve(FontsUsedDataProducer.FILE)
      assertTrue("expected fonts artefact under protocol previewId: $byProtocolId", byProtocolId.exists())
      assertTrue("must not also write under outputBaseName: $byBaseName", !byBaseName.exists())
    } finally {
      rootDir.deleteRecursively()
    }
  }
}
