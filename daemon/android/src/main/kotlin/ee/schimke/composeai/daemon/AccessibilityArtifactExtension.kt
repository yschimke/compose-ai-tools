package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.render.extensions.DataExtensionConstraints
import ee.schimke.composeai.data.render.extensions.DataExtensionHookKind
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionPhase
import ee.schimke.composeai.data.render.extensions.DataExtensionTarget
import ee.schimke.composeai.data.render.extensions.ExtensionContextData
import ee.schimke.composeai.data.render.extensions.ExtensionPostCaptureContext
import ee.schimke.composeai.data.render.extensions.PostCaptureProcessor
import ee.schimke.composeai.data.render.extensions.RecordingDataProductStore
import ee.schimke.composeai.data.render.extensions.provides
import ee.schimke.composeai.renderer.AccessibilityDataProducts
import ee.schimke.composeai.renderer.AccessibilityHierarchyContextKeys
import ee.schimke.composeai.renderer.AccessibilityHierarchyExtension

/**
 * Always-on post-capture extension that walks the captured `ViewRootForTest` and writes
 * `a11y/atf` and `a11y/hierarchy` sidecars for the rendered preview.
 *
 * Bails out cleanly when the render didn't run in a11y mode: presence of
 * [RenderDataArtifactContextKeys.AccessibilityViewRoot] is the gate, replacing the old
 * `if (runAccessibility) { ... }` wrapper around an inline sidecar block in the engine. Lets
 * the registration list stay flat (registered once; runs on every render) while the engine
 * keeps deciding which renders carry accessibility data.
 *
 * Internally drives [AccessibilityHierarchyExtension] (a typed extension that owns the
 * platform-specific ATF walk) before handing the merged hierarchy + findings to
 * [AccessibilityDataProducer]. The legacy `imageProcessors` escape hatch is threaded through
 * [RenderDataArtifactContextKeys.ImageProcessors]; absent entries default to an empty list.
 */
class AccessibilityArtifactExtension : PostCaptureProcessor {
  override val id: DataExtensionId = ID
  override val hooks: Set<DataExtensionHookKind> = setOf(DataExtensionHookKind.AfterCapture)
  override val constraints: DataExtensionConstraints =
    DataExtensionConstraints(phase = DataExtensionPhase.Capture)
  override val targets: Set<DataExtensionTarget> = setOf(DataExtensionTarget.Android)

  override fun process(context: ExtensionPostCaptureContext) {
    val view =
      context.get(RenderDataArtifactContextKeys.AccessibilityViewRoot)
        ?: return // a11y data not collected for this render — nothing to write
    val rootDir = context.require(RenderDataArtifactContextKeys.RootDir)
    val outputBaseName = context.require(RenderDataArtifactContextKeys.OutputBaseName)
    val density = context.require(RenderDataArtifactContextKeys.RenderDensity)
    val pngFile = context.require(RenderDataArtifactContextKeys.OutputPngFile)
    val isRound = context.get(RenderDataArtifactContextKeys.IsRoundScreen) ?: false
    val imageProcessors =
      context.get(RenderDataArtifactContextKeys.ImageProcessors) ?: emptyList()

    val hierarchyExtension = AccessibilityHierarchyExtension()
    val store = RecordingDataProductStore()
    hierarchyExtension.process(
      ExtensionPostCaptureContext(
        extensionId = hierarchyExtension.id,
        previewId = outputBaseName,
        renderMode = context.renderMode,
        products = store.scopedFor(hierarchyExtension),
        data =
          ExtensionContextData.of(AccessibilityHierarchyContextKeys.ViewRoot provides view),
      )
    )
    val hierarchy = store.require(AccessibilityDataProducts.Hierarchy)
    val findings = store.require(AccessibilityDataProducts.Atf)
    AccessibilityDataProducer.writeArtifacts(
      rootDir = rootDir,
      previewId = outputBaseName,
      findings = findings.findings,
      nodes = hierarchy.nodes,
      density = density,
      pngFile = pngFile,
      isRound = isRound,
      imageProcessors = imageProcessors,
    )
  }

  companion object {
    val ID: DataExtensionId = DataExtensionId("a11y/postCaptureArtifact")

    val factory: RenderDataArtifactExtensionFactory =
      RenderDataArtifactExtensionFactory { _ -> AccessibilityArtifactExtension() }
  }
}
