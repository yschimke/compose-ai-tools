package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsPayload
import ee.schimke.composeai.data.render.extensions.DataExtensionConstraints
import ee.schimke.composeai.data.render.extensions.DataExtensionHookKind
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionPhase
import ee.schimke.composeai.data.render.extensions.DataExtensionTarget
import ee.schimke.composeai.data.render.extensions.ExtensionPostCaptureContext
import ee.schimke.composeai.data.render.extensions.PostCaptureProcessor

/**
 * Always-on post-capture extension that writes the `compose/figma-svg` layered SVG for the rendered
 * preview. Combines the two trees the engine already captures: the layout tree (via
 * [RenderDataArtifactContextKeys.LayoutInspectorPreviewContext] — the composable names + container
 * tokens) and the semantics tree (via [RenderDataArtifactContextKeys.SemanticsRoot] — the editable
 * text). Mirrors [LayoutInspectorExtension] + [ComposeSemanticsWireframeExtension]; all three read
 * the same captured frame, so the export stays in lock-step with the JSON snapshots and the
 * wireframe.
 */
class ComposeFigmaSvgExtension : PostCaptureProcessor {
  override val id: DataExtensionId = ID
  override val hooks: Set<DataExtensionHookKind> = setOf(DataExtensionHookKind.AfterCapture)
  override val constraints: DataExtensionConstraints =
    DataExtensionConstraints(phase = DataExtensionPhase.Capture)
  override val targets: Set<DataExtensionTarget> = setOf(DataExtensionTarget.Android)

  override fun process(context: ExtensionPostCaptureContext) {
    val rootDir = context.require(RenderDataArtifactContextKeys.RootDir)
    val outputBaseName = context.require(RenderDataArtifactContextKeys.OutputBaseName)
    // Key off the protocol previewId when present, matching the file-backed registry lookup and the
    // sibling wireframe extension so `data/fetch` finds the SVG.
    val previewId = context.get(RenderDataArtifactContextKeys.PreviewId) ?: outputBaseName
    val previewContext = context.require(RenderDataArtifactContextKeys.LayoutInspectorPreviewContext)
    val density = context.get(RenderDataArtifactContextKeys.Density) ?: 1f
    val layout = LayoutInspectorDataProducer.buildPayload(previewContext, density) ?: return
    val semantics: ComposeSemanticsPayload? =
      context.get(RenderDataArtifactContextKeys.SemanticsRoot)?.let {
        ComposeSemanticsDataProducer.buildPayload(it, density)
      }
    ComposeFigmaSvgDataProducer.writeSvg(
      rootDir = rootDir,
      previewId = previewId,
      layout = layout,
      semantics = semantics,
      density = density,
    )
  }

  companion object {
    val ID: DataExtensionId = DataExtensionId(ComposeFigmaSvgDataProducer.KIND)

    val factory: RenderDataArtifactExtensionFactory =
      RenderDataArtifactExtensionFactory { _ -> ComposeFigmaSvgExtension() }
  }
}
