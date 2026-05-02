package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataProductExtra
import java.io.File

/**
 * D2.1 — pluggable post-render hook for the daemon's render loop. An [ImageProcessor] receives the
 * just-captured PNG and writes derived files — typically annotated PNGs like the Paparazzi-style
 * accessibility overlay — under the data-product directory tree.
 *
 * Each output is reported back as a [DataProductExtra] tagged with the kind it should attach to (so
 * e.g. `a11y/atf` rides with its overlay PNG even when the caller fetched the JSON inline) and a
 * stable [DataProductExtra.name] the registry uses as the cache key. Processors stay pure-data —
 * the registry decides whether the extras end up on the wire based on the client's subscription
 * set.
 *
 * **Where this fits.** The Gradle / CLI path keeps using
 * [`ee.schimke.composeai.renderer.AccessibilityChecker.writePerPreviewReport`][] (in
 * `:data-a11y-core`)'s built-in overlay bake; processors are the daemon-mode replacement that lets
 * clients drive their own a11y panel UI without a second baked PNG. Same generator either way — see
 * `AccessibilityOverlay`.
 *
 * **Where the interface lives.** In `:daemon:core` because it's the framework-level seam (used by
 * `RenderEngine`); concrete implementations live alongside their data-product connector module —
 * e.g. `AccessibilityImageProcessor` in `:data-a11y-connector`.
 *
 * Implementations are stateless across renders and **must not** retain references to
 * [ImageProcessorInput.pngFile] or any [java.io.File] handed to them — the daemon recycles
 * `<dataDir>` between renders.
 */
interface ImageProcessor {
  /** Producer-stable identifier for the processor. Surfaces in error logs. */
  val name: String

  /**
   * Runs against the just-captured render. Returns the per-kind extras the processor wrote; the
   * registry attaches them to matching `data/fetch` results and `renderFinished` attachments. An
   * empty map means "this render didn't have anything to add" (e.g. an a11y processor on a render
   * whose mode skipped a11y).
   */
  fun process(input: ImageProcessorInput): Map<String, List<DataProductExtra>>
}

/**
 * Per-render inputs handed to every [ImageProcessor]. Generic shape — `:daemon:core` doesn't know
 * about a11y or any other specific data product. Producers that need to forward typed context to
 * their image processor (e.g. `AccessibilityDataProducer` handing a list of ATF findings to
 * `AccessibilityImageProcessor`) drop a connector-defined value into [context] and let the
 * processor downcast.
 *
 * - `previewId` — the previewId the just-captured render is for.
 * - `pngFile` — absolute path of the primary capture (already on disk).
 * - `dataDir` — the per-preview output root (`<rootDir>/<previewId>/`).
 * - `isRound` — whether the rendered device is a round Wear / circular display.
 * - `context` — opaque per-producer payload. Each known producer pairs with an [ImageProcessor]
 *   that knows the concrete shape and downcasts (e.g. `AccessibilityImageProcessor` expects an
 *   `AccessibilityImageContext`). `null` when the producer doesn't need to forward anything.
 */
data class ImageProcessorInput(
  val previewId: String,
  val pngFile: File,
  val dataDir: File,
  val isRound: Boolean,
  val context: Any? = null,
)
