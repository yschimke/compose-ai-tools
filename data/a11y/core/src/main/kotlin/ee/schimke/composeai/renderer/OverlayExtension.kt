package ee.schimke.composeai.renderer

import ee.schimke.composeai.data.render.extensions.CommonDataProducts
import ee.schimke.composeai.data.render.extensions.DataExtensionConstraints
import ee.schimke.composeai.data.render.extensions.DataExtensionHookKind
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionPhase
import ee.schimke.composeai.data.render.extensions.DataExtensionTarget
import ee.schimke.composeai.data.render.extensions.DataProductKey
import ee.schimke.composeai.data.render.extensions.ExtensionPostCaptureContext
import ee.schimke.composeai.data.render.extensions.PostCaptureProcessor
import java.io.File

/**
 * Renders the Paparazzi-style annotated PNG overlay from a hierarchy + ATF findings + the captured
 * image. Android-only today (the [AccessibilityOverlay] generator uses Android `Bitmap`/`Canvas`);
 * a Desktop variant would target [DataExtensionTarget.Desktop] without conflicting because the
 * planner's target filter selects exactly one provider per platform.
 *
 * Inputs: hierarchy, ATF findings, captured image. Output: [AccessibilityOverlayArtifact] pointing
 * at the written PNG. The output directory and round-clip flag are passed via `attributes` (see
 * [OUTPUT_DIRECTORY_ATTRIBUTE], [IS_ROUND_ATTRIBUTE]) — those are render-host-controlled paths
 * rather than typed products so the extension itself stays a pure consumer of the typed graph.
 */
class OverlayExtension : PostCaptureProcessor {
  override val id: DataExtensionId = DataExtensionId(EXTENSION_ID)
  override val hooks: Set<DataExtensionHookKind> = setOf(DataExtensionHookKind.AfterRender)
  override val constraints: DataExtensionConstraints =
    DataExtensionConstraints(phase = DataExtensionPhase.Publish)
  override val inputs: Set<DataProductKey<*>> =
    setOf(
      AccessibilityDataProducts.Hierarchy,
      AccessibilityDataProducts.Atf,
      CommonDataProducts.ImageArtifact,
    )
  override val outputs: Set<DataProductKey<*>> = setOf(AccessibilityDataProducts.Overlay)
  override val targets: Set<DataExtensionTarget> = setOf(DataExtensionTarget.Android)

  override fun process(context: ExtensionPostCaptureContext) {
    val hierarchy = context.products.require(AccessibilityDataProducts.Hierarchy)
    val findings = context.products.require(AccessibilityDataProducts.Atf)
    val image = context.products.require(CommonDataProducts.ImageArtifact)
    val outputDir =
      context.attributes[OUTPUT_DIRECTORY_ATTRIBUTE] as? File
        ?: error("OverlayExtension requires '$OUTPUT_DIRECTORY_ATTRIBUTE' in attributes.")
    val isRound = context.attributes[IS_ROUND_ATTRIBUTE] as? Boolean ?: false
    val sourcePng = File(image.path)
    val destPng = outputDir.resolve(OVERLAY_FILE_NAME)
    val written =
      AccessibilityOverlay.generate(
        sourcePng = sourcePng,
        findings = findings.findings,
        nodes = hierarchy.nodes,
        destPng = destPng,
        isRound = isRound,
      ) ?: return
    context.products.put(
      AccessibilityDataProducts.Overlay,
      AccessibilityOverlayArtifact(path = written.absolutePath),
    )
  }

  companion object {
    const val EXTENSION_ID: String = "a11y-overlay"
    const val OUTPUT_DIRECTORY_ATTRIBUTE: String = "a11y-overlay.outputDirectory"
    const val IS_ROUND_ATTRIBUTE: String = "a11y-overlay.isRound"
    const val OVERLAY_FILE_NAME: String = "a11y-overlay.png"
  }
}
