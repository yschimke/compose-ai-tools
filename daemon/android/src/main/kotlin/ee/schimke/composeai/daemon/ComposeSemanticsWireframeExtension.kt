package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.render.extensions.DataExtensionConstraints
import ee.schimke.composeai.data.render.extensions.DataExtensionHookKind
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionPhase
import ee.schimke.composeai.data.render.extensions.DataExtensionTarget
import ee.schimke.composeai.data.render.extensions.ExtensionPostCaptureContext
import ee.schimke.composeai.data.render.extensions.PostCaptureProcessor

/**
 * Always-on post-capture extension that derives the `compose/semantics-wireframe` artefacts from the
 * rendered semantics root — the SVG (backend-agnostic, via [ComposeSemanticsWireframeDataProducer])
 * plus the baked PNG (via [AndroidSemanticsWireframe]). Mirrors [ComposeSemanticsExtension]; both
 * read the same captured root, so the wireframe and the JSON snapshot stay in lock-step.
 */
class ComposeSemanticsWireframeExtension : PostCaptureProcessor {
  override val id: DataExtensionId = ID
  override val hooks: Set<DataExtensionHookKind> = setOf(DataExtensionHookKind.AfterCapture)
  override val constraints: DataExtensionConstraints =
    DataExtensionConstraints(phase = DataExtensionPhase.Capture)
  override val targets: Set<DataExtensionTarget> = setOf(DataExtensionTarget.Android)

  override fun process(context: ExtensionPostCaptureContext) {
    val rootDir = context.require(RenderDataArtifactContextKeys.RootDir)
    val previewId = context.require(RenderDataArtifactContextKeys.OutputBaseName)
    val semanticsRoot = context.require(RenderDataArtifactContextKeys.SemanticsRoot)
    val payload = ComposeSemanticsDataProducer.buildPayload(semanticsRoot)
    ComposeSemanticsWireframeDataProducer.writeSvg(
      rootDir = rootDir,
      previewId = previewId,
      payload = payload,
    )
    AndroidSemanticsWireframe.generate(
      payload = payload,
      destPng = rootDir.resolve(previewId).resolve(ComposeSemanticsWireframeDataProducer.FILE_PNG),
    )
  }

  companion object {
    val ID: DataExtensionId = DataExtensionId(ComposeSemanticsWireframeDataProducer.KIND)

    val factory: RenderDataArtifactExtensionFactory =
      RenderDataArtifactExtensionFactory { _ -> ComposeSemanticsWireframeExtension() }
  }
}
