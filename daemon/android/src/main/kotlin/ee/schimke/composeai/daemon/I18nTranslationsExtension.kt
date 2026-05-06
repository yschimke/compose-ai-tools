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
 * `i18n/translations` artefact for the rendered preview.
 *
 * Pure post-capture: no Compose-side hook is needed because the producer reads the visible text
 * out of the rendered semantics root rather than recording during composition.
 */
class I18nTranslationsExtension : PostCaptureProcessor {
  override val id: DataExtensionId = ID
  override val hooks: Set<DataExtensionHookKind> = setOf(DataExtensionHookKind.AfterCapture)
  override val constraints: DataExtensionConstraints =
    DataExtensionConstraints(phase = DataExtensionPhase.Capture)
  override val targets: Set<DataExtensionTarget> = setOf(DataExtensionTarget.Android)

  override fun process(context: ExtensionPostCaptureContext) {
    val rootDir = context.require(RenderDataArtifactContextKeys.RootDir)
    val outputBaseName = context.require(RenderDataArtifactContextKeys.OutputBaseName)
    val semanticsRoot = context.require(RenderDataArtifactContextKeys.SemanticsRoot)
    val renderedLocale =
      context.get(RenderDataArtifactContextKeys.RenderedLocale)?.takeIf { it.isNotBlank() }
    I18nTranslationsDataProducer.writeArtifacts(
      rootDir = rootDir,
      previewId = outputBaseName,
      root = semanticsRoot,
      renderedLocale = renderedLocale,
    )
  }

  companion object {
    val ID: DataExtensionId = DataExtensionId(I18nTranslationsDataProducer.KIND)

    val factory: RenderDataArtifactExtensionFactory =
      RenderDataArtifactExtensionFactory { _ -> I18nTranslationsExtension() }
  }
}
