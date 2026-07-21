package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsPayload
import ee.schimke.composeai.data.render.extensions.DataExtensionConstraints
import ee.schimke.composeai.data.render.extensions.DataExtensionHookKind
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionPhase
import ee.schimke.composeai.data.render.extensions.DataExtensionTarget
import ee.schimke.composeai.data.render.extensions.ExtensionPostCaptureContext
import ee.schimke.composeai.data.render.extensions.PostCaptureProcessor
import java.io.File

/**
 * Always-on post-capture extension that derives the `compose/semantics-wireframe` artefacts from
 * the rendered semantics root — the SVG (backend-agnostic, via
 * [ComposeSemanticsWireframeDataProducer]) plus the baked PNG — and the degenerate single-panel
 * `compose/spatial-semantics` tree. All three come from the same captured root so they stay in
 * lock-step with the `compose/semantics` snapshot.
 *
 * Shared across backends (`targets = {Android, Desktop}`); the two per-backend bits are injected so
 * the class stays platform-neutral:
 * - **[pngGenerator]** — the wireframe raster is baked with a platform toolkit (Robolectric canvas
 *   on Android, Skia on Desktop). Each daemon passes its own `SemanticsWireframe.generate`.
 * - **[densityAware]** — whether the payload resolves density-dependent tokens (percent corner
 *   radii → dp, #1908). Desktop passes `true` (real `spec.density`); Android passes `false`
 *   (`density = 1f`), preserving each backend's current output exactly. Unifying the two — making
 *   Android density-aware here too — is a deliberate follow-up gated on visual review, not folded
 *   into this refactor.
 */
class ComposeSemanticsWireframeExtension(
  private val pngGenerator: (ComposeSemanticsPayload, File) -> Unit,
  private val densityAware: Boolean,
) : PostCaptureProcessor {
  override val id: DataExtensionId = ID
  override val hooks: Set<DataExtensionHookKind> = setOf(DataExtensionHookKind.AfterCapture)
  override val constraints: DataExtensionConstraints =
    DataExtensionConstraints(phase = DataExtensionPhase.Capture)
  override val targets: Set<DataExtensionTarget> =
    setOf(DataExtensionTarget.Android, DataExtensionTarget.Desktop)

  override fun process(context: ExtensionPostCaptureContext) {
    val rootDir = context.require(RenderArtifactContextKeys.RootDir)
    val outputBaseName = context.require(RenderArtifactContextKeys.OutputBaseName)
    // Key off the protocol previewId when present, falling back to the renderer output base name —
    // matching the file-backed registry lookup (`data/fetch` resolves `<rootDir>/<previewId>/`).
    val previewId = context.get(RenderArtifactContextKeys.PreviewId) ?: outputBaseName
    val semanticsRoot = context.require(RenderArtifactContextKeys.SemanticsRoot)
    val payload =
      if (densityAware) {
        ComposeSemanticsDataProducer.buildPayload(
          semanticsRoot,
          context.get(RenderArtifactContextKeys.Density) ?: 1f,
        )
      } else {
        ComposeSemanticsDataProducer.buildPayload(semanticsRoot)
      }
    ComposeSemanticsWireframeDataProducer.writeSvg(
      rootDir = rootDir,
      previewId = previewId,
      payload = payload,
    )
    pngGenerator(
      payload,
      rootDir.resolve(previewId).resolve(ComposeSemanticsWireframeDataProducer.FILE_PNG),
    )
    // Unified spatial-semantics tree (`compose/spatial-semantics`) — the degenerate single-panel
    // case for an ordinary preview: one `panel` at identity pose carrying this same 2D tree. The XR
    // batch render writes the real multi-panel tree to the same file.
    SpatialSemanticsDataProducer.writeSinglePanel(
      rootDir = rootDir,
      previewId = previewId,
      payload = payload,
    )
  }

  companion object {
    val ID: DataExtensionId = DataExtensionId(ComposeSemanticsWireframeDataProducer.KIND)
  }
}
