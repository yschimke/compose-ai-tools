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
 * Portable across backends: it reads only the shared [RenderArtifactContextKeys] and the
 * platform-neutral [ComposeSemanticsDataProducer], so both the Android and Desktop daemons register
 * the *same* class (`targets = {Android, Desktop}`). The Android daemon wraps it in a
 * `RenderDataArtifactExtensionFactory` (its always-on list is factory-with-`Context`); the Desktop
 * daemon constructs it directly. Pure post-capture — no Compose-side hook is needed because the
 * producer reads the rendered semantics root directly.
 */
class ComposeSemanticsExtension : PostCaptureProcessor {
  override val id: DataExtensionId = ID
  override val hooks: Set<DataExtensionHookKind> = setOf(DataExtensionHookKind.AfterCapture)
  override val constraints: DataExtensionConstraints =
    DataExtensionConstraints(phase = DataExtensionPhase.Capture)
  override val targets: Set<DataExtensionTarget> =
    setOf(DataExtensionTarget.Android, DataExtensionTarget.Desktop)

  override fun process(context: ExtensionPostCaptureContext) {
    val rootDir = context.require(RenderArtifactContextKeys.RootDir)
    val outputBaseName = context.require(RenderArtifactContextKeys.OutputBaseName)
    val semanticsRoot = context.require(RenderArtifactContextKeys.SemanticsRoot)
    // Deliberately NOT coerced to 1f: an absent density is unknown, and the payload records it as
    // such (schema v14) so a consumer reading bounds against tokens knows not to assume they share
    // a unit. The projection still falls back to 1f for its own percent-corner maths.
    val density = context.get(RenderArtifactContextKeys.Density)
    ComposeSemanticsDataProducer.writeArtifacts(
      rootDir = rootDir,
      previewId = outputBaseName,
      root = semanticsRoot,
      density = density,
    )
  }

  companion object {
    val ID: DataExtensionId = DataExtensionId(ComposeSemanticsDataProducer.KIND)
  }
}
