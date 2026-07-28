package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.render.extensions.DataExtensionConstraints
import ee.schimke.composeai.data.render.extensions.DataExtensionHookKind
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionPhase
import ee.schimke.composeai.data.render.extensions.DataExtensionTarget
import ee.schimke.composeai.data.render.extensions.ExtensionPostCaptureContext
import ee.schimke.composeai.data.render.extensions.PostCaptureProcessor

/**
 * Always-on post-capture extension that writes the `compose/figma-svg` layered SVG for the rendered
 * preview — the design-fidelity export, not the schematic wireframe. Combines the two trees the
 * engine captures: the layout tree (composable names + container tokens) and the semantics tree
 * (editable text), both derived from the same captured root + slot tables.
 *
 * Shared across backends (`targets = {Android, Desktop}`) via the CMP-portable
 * [LayoutInspectorDataProducer] / [ComposeSemanticsDataProducer] primitive overloads (the same root
 * + slot tables both backends' inline paths used — see [LayoutInspectorExtension]). The only
 *   per-backend bit is the [fontResolver]: each daemon injects its own (the desktop resolver keeps
 *   the embedded face on when the fidelity harness is measuring; Android has no fidelity pass). The
 *   round-Wear clip is threaded through [RenderArtifactContextKeys.RoundClip] (Android derives it
 *   from the preview device; desktop leaves it `false`), and the desktop-only fidelity scoring
 *   stays in the desktop engine, running after this extension has written the SVG.
 */
class ComposeFigmaSvgExtension(private val fontResolver: () -> FigmaFontResolver?) :
  PostCaptureProcessor {
  override val id: DataExtensionId = ID
  override val hooks: Set<DataExtensionHookKind> = setOf(DataExtensionHookKind.AfterCapture)
  override val constraints: DataExtensionConstraints =
    DataExtensionConstraints(phase = DataExtensionPhase.Capture)
  override val targets: Set<DataExtensionTarget> =
    setOf(DataExtensionTarget.Android, DataExtensionTarget.Desktop)

  override fun process(context: ExtensionPostCaptureContext) {
    val rootDir = context.require(RenderArtifactContextKeys.RootDir)
    val outputBaseName = context.require(RenderArtifactContextKeys.OutputBaseName)
    // Key off the protocol previewId when present, matching the file-backed registry lookup and the
    // sibling extensions so `data/fetch` finds the SVG.
    val previewId = context.get(RenderArtifactContextKeys.PreviewId) ?: outputBaseName
    val semanticsRoot = context.require(RenderArtifactContextKeys.SemanticsRoot)
    val slotTables = context.get(RenderArtifactContextKeys.SlotTables).orEmpty()
    val density = context.get(RenderArtifactContextKeys.Density) ?: 1f
    val fontScale = context.get(RenderArtifactContextKeys.FontScale) ?: 1f
    val frameImage = context.get(RenderArtifactContextKeys.OutputPng)
    val roundClip = context.get(RenderArtifactContextKeys.RoundClip) ?: false
    // The background the render painted behind the composable, when the preview opted into one.
    // Threaded rather than re-derived so the SVG's bottom layer is the same colour the PNG shows
    // (issue #2884) — including the device-masked Wear case, where it fills the watch face.
    val previewBackground =
      context.get(RenderArtifactContextKeys.PreviewBackground)?.takeIf { it.isNotBlank() }
    val layout =
      LayoutInspectorDataProducer.buildPayload(semanticsRoot, slotTables, density) ?: return
    val semantics = ComposeSemanticsDataProducer.buildPayload(semanticsRoot, density)
    ComposeFigmaSvgDataProducer.writeSvg(
      rootDir = rootDir,
      previewId = previewId,
      layout = layout,
      semantics = semantics,
      density = density,
      fontScale = fontScale,
      frameImage = frameImage,
      fontResolver = fontResolver(),
      roundClip = roundClip,
      deviceBackground = previewBackground,
    )
  }

  companion object {
    val ID: DataExtensionId = DataExtensionId(ComposeFigmaSvgDataProducer.KIND)
  }
}
