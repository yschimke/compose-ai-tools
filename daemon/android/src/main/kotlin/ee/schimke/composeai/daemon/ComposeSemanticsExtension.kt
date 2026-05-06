package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.render.extensions.DataExtensionConstraints
import ee.schimke.composeai.data.render.extensions.DataExtensionHookKind
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionPhase
import ee.schimke.composeai.data.render.extensions.DataExtensionTarget
import ee.schimke.composeai.data.render.extensions.ExtensionPostCaptureContext
import ee.schimke.composeai.data.render.extensions.PostCaptureProcessor

/**
 * Always-on post-capture extension that walks the captured semantics tree and writes the
 * `compose/semantics` artefact for the rendered preview.
 *
 * Pure post-capture: no Compose-side hook is needed because the producer reads the rendered
 * semantics root directly. Mirrors the [I18nTranslationsExtension] shape.
 */
class ComposeSemanticsExtension : PostCaptureProcessor {
  override val id: DataExtensionId = ID
  override val hooks: Set<DataExtensionHookKind> = setOf(DataExtensionHookKind.AfterCapture)
  override val constraints: DataExtensionConstraints =
    DataExtensionConstraints(phase = DataExtensionPhase.Capture)
  override val targets: Set<DataExtensionTarget> = setOf(DataExtensionTarget.Android)

  override fun process(context: ExtensionPostCaptureContext) {
    val rootDir = context.require(RenderDataArtifactContextKeys.RootDir)
    val outputBaseName = context.require(RenderDataArtifactContextKeys.OutputBaseName)
    val semanticsRoot = context.require(RenderDataArtifactContextKeys.SemanticsRoot)
    ComposeSemanticsDataProducer.writeArtifacts(
      rootDir = rootDir,
      previewId = outputBaseName,
      root = semanticsRoot,
    )
  }

  companion object {
    val ID: DataExtensionId = DataExtensionId(ComposeSemanticsDataProducer.KIND)

    val factory: RenderDataArtifactExtensionFactory =
      RenderDataArtifactExtensionFactory { _ -> ComposeSemanticsExtension() }
  }
}
