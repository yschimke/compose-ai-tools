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
 * preview by walking the `LayoutNode` tree reachable from the captured semantics root.
 *
 * Shared across backends (`targets = {Android, Desktop}`) via the CMP-portable
 * [LayoutInspectorDataProducer] primitive overload — the inspector walk needs only the captured
 * [SemanticsRoot][RenderArtifactContextKeys.SemanticsRoot] plus the composition
 * [SlotTables][RenderArtifactContextKeys.SlotTables] and
 * [Density][RenderArtifactContextKeys.Density]. The Android daemon previously fed these wrapped in
 * a `PreviewContext`; that context's device / backend / render-mode fields were never read by the
 * write path (`LayoutInspectorCaptureContext` only extracts the root node + slot tables), so
 * switching to the primitives here is output-equivalent and lets Desktop — which has no
 * `RootForTest` handle to build a `PreviewContext` from — run the very same extension.
 */
class LayoutInspectorExtension : PostCaptureProcessor {
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
    val slotTables = context.get(RenderArtifactContextKeys.SlotTables).orEmpty()
    // Density (dp = px / density) only matters for resolving percent-based corner radii into dp on
    // the per-node `tokens` (#1903); 1f keeps px-equals-dp captures intact.
    val density = context.get(RenderArtifactContextKeys.Density) ?: 1f
    LayoutInspectorDataProducer.writeArtifacts(
      rootDir = rootDir,
      previewId = outputBaseName,
      root = semanticsRoot,
      slotTables = slotTables,
      density = density,
    )
  }

  companion object {
    val ID: DataExtensionId = DataExtensionId(LayoutInspectorDataProducer.KIND)
  }
}
