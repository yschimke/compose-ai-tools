package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataProductExtra
import ee.schimke.composeai.renderer.AccessibilityChecker
import ee.schimke.composeai.renderer.AccessibilityFinding
import ee.schimke.composeai.renderer.AccessibilityNode
import ee.schimke.composeai.renderer.AccessibilityOverlay
import java.io.File

/**
 * D2.1 — pluggable post-render hook for the daemon's render loop. An [ImageProcessor]
 * receives the just-captured PNG and the renderer-side a11y artefacts (when a11y mode is on)
 * and writes derived files — typically annotated PNGs like the Paparazzi-style accessibility
 * overlay — under the data-product directory tree.
 *
 * Each output is reported back as a [DataProductExtra] tagged with the kind it should attach
 * to (so `a11y/atf` rides with its overlay PNG even when the caller fetched the JSON inline)
 * and a stable [DataProductExtra.name] the registry uses as the cache key. Processors stay
 * pure-data — the registry decides whether the extras end up on the wire based on the
 * client's subscription set.
 *
 * **Where this fits.** The Gradle / CLI path keeps using
 * [AccessibilityChecker.writePerPreviewReport]'s built-in overlay bake (see
 * `AccessibilityChecker.kt` § `writePerPreviewReport`); processors are the daemon-mode
 * replacement that lets clients drive their own a11y panel UI without a second baked PNG.
 * Same generator either way — see [AccessibilityOverlay] for the visual language.
 *
 * Implementations are stateless across renders and **must not** retain references to
 * [ImageProcessorInput.pngFile] or any [java.io.File] handed to them — the daemon recycles
 * `<dataDir>` between renders.
 */
interface ImageProcessor {
  /** Producer-stable identifier for the processor. Surfaces in error logs. */
  val name: String

  /**
   * Runs against the just-captured render. Returns the per-kind extras the processor wrote;
   * the registry attaches them to matching `data/fetch` results and `renderFinished`
   * attachments. An empty map means "this render didn't have anything to add" (e.g. an a11y
   * processor on a render whose mode skipped a11y).
   */
  fun process(input: ImageProcessorInput): Map<String, List<DataProductExtra>>
}

/**
 * Per-render inputs handed to every [ImageProcessor]. `pngFile` is the absolute path of the
 * primary capture (already on disk); `dataDir` is the per-preview output root
 * (`<rootDir>/<previewId>/`); `accessibility` carries the ATF + hierarchy result when a11y
 * mode ran for this render, or `null` when it didn't.
 */
data class ImageProcessorInput(
  val previewId: String,
  val pngFile: File,
  val dataDir: File,
  val isRound: Boolean,
  val accessibility: AccessibilityResult? = null,
) {
  /** Adapter onto [AccessibilityChecker.Result] kept here so processors don't depend on it. */
  data class AccessibilityResult(
    val findings: List<AccessibilityFinding>,
    val nodes: List<AccessibilityNode>,
  )
}

/**
 * D2.1 — first concrete [ImageProcessor]. When the render ran in a11y mode and produced any
 * findings or nodes, generates the Paparazzi-style annotated overlay via
 * [AccessibilityOverlay.generate] and attaches it to `a11y/atf` and `a11y/hierarchy` as the
 * `overlay` extra. Standalone consumers can also fetch the dedicated `a11y/overlay` kind that
 * the registry advertises.
 *
 * Output path: `<dataDir>/<previewId>/a11y-overlay.png`. Same naming convention the JSON
 * artefacts use (slash-to-dash on the kind), so the registry's reverse mapping stays
 * mechanical.
 */
class AccessibilityImageProcessor : ImageProcessor {
  override val name: String = "a11y-overlay"

  override fun process(input: ImageProcessorInput): Map<String, List<DataProductExtra>> {
    val a11y = input.accessibility ?: return emptyMap()
    if (a11y.findings.isEmpty() && a11y.nodes.isEmpty()) return emptyMap()
    val previewDir = input.dataDir.resolve(input.previewId).also { it.mkdirs() }
    val dest = previewDir.resolve(OVERLAY_FILE)
    val written =
      AccessibilityOverlay.generate(
        sourcePng = input.pngFile,
        findings = a11y.findings,
        nodes = a11y.nodes,
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
    // Same overlay attaches to both a11y kinds so a panel that only fetched one kind still
    // gets the picture; the dedicated `a11y/overlay` kind is for clients that just want the
    // PNG without the JSON.
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
