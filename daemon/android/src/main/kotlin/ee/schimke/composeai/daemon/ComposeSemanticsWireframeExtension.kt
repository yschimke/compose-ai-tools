package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.render.extensions.DataExtensionConstraints
import ee.schimke.composeai.data.render.extensions.DataExtensionHookKind
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionPhase
import ee.schimke.composeai.data.render.extensions.DataExtensionTarget
import ee.schimke.composeai.data.render.extensions.ExtensionPostCaptureContext
import ee.schimke.composeai.data.render.extensions.PostCaptureProcessor

/**
 * Always-on post-capture extension that derives the `compose/semantics-wireframe` artefacts from
 * the rendered semantics root — the SVG (backend-agnostic, via
 * [ComposeSemanticsWireframeDataProducer]) plus the baked PNG (via [AndroidSemanticsWireframe]).
 * Mirrors [ComposeSemanticsExtension]; both read the same captured root, so the wireframe and the
 * JSON snapshot stay in lock-step.
 */
class ComposeSemanticsWireframeExtension : PostCaptureProcessor {
  override val id: DataExtensionId = ID
  override val hooks: Set<DataExtensionHookKind> = setOf(DataExtensionHookKind.AfterCapture)
  override val constraints: DataExtensionConstraints =
    DataExtensionConstraints(phase = DataExtensionPhase.Capture)
  override val targets: Set<DataExtensionTarget> = setOf(DataExtensionTarget.Android)

  override fun process(context: ExtensionPostCaptureContext) {
    val rootDir = context.require(RenderDataArtifactContextKeys.RootDir)
    // Key the per-preview directory off the protocol `previewId` when present, falling back to the
    // renderer output base name — matching the file-backed registry lookup (renderFinished
    // attachments and `data/fetch` resolve `<dataDir>/<protocol previewId>/`), the desktop
    // wireframe
    // producer, and the `FontsRecorderExtension` precedent. They coincide on the common path; this
    // keeps the SVG/PNG findable when a render carries a previewId distinct from the output name.
    val outputBaseName = context.require(RenderDataArtifactContextKeys.OutputBaseName)
    val previewId = context.get(RenderDataArtifactContextKeys.PreviewId) ?: outputBaseName
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
    // Unified spatial-semantics tree (`compose/spatial-semantics`) — the degenerate single-panel
    // case for an ordinary preview: one `panel` at identity pose carrying this same 2D tree. The XR
    // batch render writes the real multi-panel tree to the same file. Derived from the same
    // captured
    // root so it stays in lock-step with the wireframe + the `compose/semantics` snapshot.
    SpatialSemanticsDataProducer.writeSinglePanel(
      rootDir = rootDir,
      previewId = previewId,
      payload = payload,
    )
  }

  companion object {
    val ID: DataExtensionId = DataExtensionId(ComposeSemanticsWireframeDataProducer.KIND)

    val factory: RenderDataArtifactExtensionFactory = RenderDataArtifactExtensionFactory { _ ->
      ComposeSemanticsWireframeExtension()
    }
  }
}
