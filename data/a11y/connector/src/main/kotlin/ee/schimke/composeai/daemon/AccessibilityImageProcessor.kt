package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataProductExtra
import ee.schimke.composeai.renderer.AccessibilityFinding
import ee.schimke.composeai.renderer.AccessibilityNode
import ee.schimke.composeai.renderer.AccessibilityOverlay

/**
 * D2.1 — first concrete [ImageProcessor]. Kept for backwards compatibility with embedders that
 * registered custom [ImageProcessor]s alongside it; the default daemon wiring no longer installs
 * this. Overlay generation now runs through the typed extension graph
 * ([ee.schimke.composeai.renderer.OverlayExtension] inside
 * [runAccessibilityPostCapturePipeline]).
 */
@Deprecated(
  message =
    "Overlay generation now runs through OverlayExtension in the typed extension graph. " +
      "Embedders relying on AccessibilityImageProcessor should migrate to a PostCaptureProcessor " +
      "or stop installing this — it's no longer the default wiring.",
)
class AccessibilityImageProcessor : ImageProcessor {
  override val name: String = "a11y-overlay"

  override fun process(input: ImageProcessorInput): Map<String, List<DataProductExtra>> {
    val ctx = input.context as? AccessibilityImageContext ?: return emptyMap()
    if (ctx.findings.isEmpty() && ctx.nodes.isEmpty()) return emptyMap()
    val previewDir = input.dataDir.resolve(input.previewId).also { it.mkdirs() }
    val dest = previewDir.resolve(OVERLAY_FILE)
    val written =
      AccessibilityOverlay.generate(
        sourcePng = input.pngFile,
        findings = ctx.findings,
        nodes = ctx.nodes,
        destPng = dest,
        isRound = input.isRound,
      ) ?: return emptyMap()
    val extra =
      DataProductExtra(
        name = OVERLAY_NAME,
        path = written.absolutePath,
        mediaType = "image/png",
        sizeBytes = written.length().takeIf { it > 0 },
      )
    // Same overlay attaches to all three a11y kinds so a panel that subscribed to any one of
    // them (most commonly the JSON `a11y/atf`) still gets the picture; the dedicated
    // `a11y/overlay` kind is for clients that just want the PNG without the JSON.
    return mapOf(
      AccessibilityDataProducer.KIND_ATF to listOf(extra),
      AccessibilityDataProducer.KIND_HIERARCHY to listOf(extra),
      AccessibilityDataProducer.KIND_OVERLAY to listOf(extra),
    )
  }

  companion object {
    /**
     * @deprecated Moved to [AccessibilityDataProducer.OVERLAY_EXTRA_NAME] — the constant is a
     *   property of the data product, not the legacy processor. Kept here as a forwarding alias
     *   so embedders that referenced the old name keep compiling.
     */
    @Deprecated(
      message = "Use AccessibilityDataProducer.OVERLAY_EXTRA_NAME.",
      replaceWith = ReplaceWith("AccessibilityDataProducer.OVERLAY_EXTRA_NAME"),
    )
    const val OVERLAY_NAME: String = AccessibilityDataProducer.OVERLAY_EXTRA_NAME

    /** Filename under `<dataDir>/<previewId>/`. Mirrors `a11y-{atf,hierarchy}.json`. */
    const val OVERLAY_FILE: String = "a11y-overlay.png"
  }
}

/**
 * D2.1 — typed [ImageProcessorInput.context] payload for the a11y data product.
 * [AccessibilityDataProducer.writeArtifacts] populates this with the same findings and nodes
 * it just dumped to disk so [AccessibilityImageProcessor] doesn't have to re-parse the JSON
 * to lay out the overlay.
 */
data class AccessibilityImageContext(
  val findings: List<AccessibilityFinding>,
  val nodes: List<AccessibilityNode>,
)
