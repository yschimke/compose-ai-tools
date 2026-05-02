package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataProductExtra
import ee.schimke.composeai.renderer.AccessibilityFinding
import ee.schimke.composeai.renderer.AccessibilityNode
import ee.schimke.composeai.renderer.AccessibilityOverlay

/**
 * D2.1 — first concrete [ImageProcessor]. When the render ran in a11y mode and produced any
 * findings or nodes, generates the Paparazzi-style annotated overlay via
 * [AccessibilityOverlay.generate] and attaches it to `a11y/atf`, `a11y/hierarchy`, and the
 * dedicated `a11y/overlay` kind as the `overlay` extra.
 *
 * Output path: `<dataDir>/<previewId>/a11y-overlay.png`. Same naming convention the JSON
 * artefacts use (slash-to-dash on the kind), so the registry's reverse mapping stays
 * mechanical.
 *
 * Reads its typed payload off [ImageProcessorInput.context] — the producer
 * ([AccessibilityDataProducer.writeArtifacts]) hands an [AccessibilityImageContext] in
 * there. A render whose context is missing or has a different shape gets an empty map back,
 * matching the "this render skipped a11y" no-op case.
 */
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
    /** [DataProductExtra.name] used for the rendered overlay PNG. */
    const val OVERLAY_NAME: String = "overlay"

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
