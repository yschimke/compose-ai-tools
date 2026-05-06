package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.render.extensions.DataExtensionConstraints
import ee.schimke.composeai.data.render.extensions.DataExtensionHookKind
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionPhase
import ee.schimke.composeai.data.render.extensions.DataExtensionTarget
import ee.schimke.composeai.data.render.extensions.ExtensionPostCaptureContext
import ee.schimke.composeai.data.render.extensions.PostCaptureProcessor

/**
 * Always-on post-capture extension that writes the `layout/inspector` artefact for the rendered
 * preview. Reads the pre-built [PreviewContext] the render engine populates on
 * [RenderDataArtifactContextKeys.LayoutInspectorPreviewContext] — the engine assembles slot
 * tables + semantics root + device dimensions in one place rather than spreading those primitive
 * inputs across context keys.
 */
class LayoutInspectorExtension : PostCaptureProcessor {
  override val id: DataExtensionId = ID
  override val hooks: Set<DataExtensionHookKind> = setOf(DataExtensionHookKind.AfterCapture)
  override val constraints: DataExtensionConstraints =
    DataExtensionConstraints(phase = DataExtensionPhase.Capture)
  override val targets: Set<DataExtensionTarget> = setOf(DataExtensionTarget.Android)

  override fun process(context: ExtensionPostCaptureContext) {
    val rootDir = context.require(RenderDataArtifactContextKeys.RootDir)
    val outputBaseName = context.require(RenderDataArtifactContextKeys.OutputBaseName)
    val previewContext = context.require(RenderDataArtifactContextKeys.LayoutInspectorPreviewContext)
    LayoutInspectorDataProducer.writeArtifacts(
      rootDir = rootDir,
      previewId = outputBaseName,
      previewContext = previewContext,
    )
  }

  companion object {
    val ID: DataExtensionId = DataExtensionId(LayoutInspectorDataProducer.KIND)

    val factory: RenderDataArtifactExtensionFactory =
      RenderDataArtifactExtensionFactory { _ -> LayoutInspectorExtension() }
  }
}
