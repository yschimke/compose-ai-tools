package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductExtra
import ee.schimke.composeai.daemon.protocol.DataProductFacet
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.data.render.pipeline.SamplingPolicy
import java.io.File
import kotlinx.serialization.json.JsonElement

/**
 * Desktop (overlay-only) `a11y` data-product registry, modelled on
 * [ComposeSemanticsDataProductRegistry] (same desktop file precedent) and the Android
 * `AccessibilityDataProductRegistry`. Advertises the three a11y kinds the desktop producer writes:
 *
 * - `a11y/atf` (INLINE) — always-empty findings (ATF is Android-only) so the CLI's per-preview
 *   `a11y/atf` fetch parses and `anyFetchOk` flips true, keeping the module report `status` null.
 * - `a11y/hierarchy` (PATH) — the extracted Compose-semantics nodes.
 * - `a11y/overlay` (PATH) — the Paparazzi-style annotated PNG.
 *
 * `a11y/touchTargets` is **not** advertised on desktop (no ATF-derived touch-target findings).
 *
 * **Re-render gating.** The desktop producer writes these artefacts on every render whose
 * `renderMode == "a11y"` (see [RenderEngine.renderOnce]). A missing artefact therefore means the
 * latest render didn't run in a11y mode, so [missingOutcome] returns
 * [DataProductRegistry.Outcome.RequiresRerender] and [renderModeFor] tells the dispatcher to re-run
 * in `a11y` mode — the probe confirmed the desktop dispatcher honours renderMode-driven re-render
 * (the `mode=a11y` payload key threads through [RenderSpec.renderMode]).
 *
 * `rootDir` mirrors `RenderEngine`'s `dataDir` (`<outputDir.parent>/data`). Wired by [DaemonMain].
 */
class DesktopAccessibilityDataProductRegistry(private val rootDir: File) :
  FileBackedDataProductRegistry(
    capabilities =
      listOf(
        DataProductCapability(
          kind = DesktopAccessibilityDataProducer.KIND_ATF,
          schemaVersion = DesktopAccessibilityDataProducer.SCHEMA_VERSION,
          transport = DataProductTransport.INLINE,
          attachable = true,
          fetchable = true,
          requiresRerender = true,
          displayName = "Accessibility findings",
          facets =
            listOf(
              DataProductFacet.STRUCTURED,
              DataProductFacet.CHECK,
              DataProductFacet.DIAGNOSTIC,
            ),
          sampling = SamplingPolicy.End,
        ),
        DataProductCapability(
          kind = DesktopAccessibilityDataProducer.KIND_HIERARCHY,
          schemaVersion = DesktopAccessibilityDataProducer.SCHEMA_VERSION,
          transport = DataProductTransport.PATH,
          attachable = true,
          fetchable = true,
          requiresRerender = true,
          displayName = "Accessibility hierarchy",
          facets = listOf(DataProductFacet.STRUCTURED),
          mediaTypes = listOf("application/json"),
          sampling = SamplingPolicy.End,
        ),
        DataProductCapability(
          kind = DesktopAccessibilityDataProducer.KIND_OVERLAY,
          schemaVersion = DesktopAccessibilityDataProducer.SCHEMA_VERSION,
          transport = DataProductTransport.PATH,
          attachable = true,
          fetchable = true,
          requiresRerender = true,
          displayName = "Accessibility overlay",
          facets =
            listOf(DataProductFacet.ARTIFACT, DataProductFacet.IMAGE, DataProductFacet.OVERLAY),
          mediaTypes = listOf("image/png"),
          sampling = SamplingPolicy.End,
        ),
      )
  ) {

  private val knownKinds =
    setOf(
      DesktopAccessibilityDataProducer.KIND_ATF,
      DesktopAccessibilityDataProducer.KIND_HIERARCHY,
      DesktopAccessibilityDataProducer.KIND_OVERLAY,
    )

  override fun fileFor(previewId: String, kind: String): File? {
    val fileName =
      when (kind) {
        DesktopAccessibilityDataProducer.KIND_ATF -> DesktopAccessibilityDataProducer.FILE_ATF
        DesktopAccessibilityDataProducer.KIND_HIERARCHY ->
          DesktopAccessibilityDataProducer.FILE_HIERARCHY
        DesktopAccessibilityDataProducer.KIND_OVERLAY ->
          DesktopAccessibilityDataProducer.FILE_OVERLAY
        else -> return null
      }
    return rootDir.resolve(previewId).resolve(fileName)
  }

  /**
   * A missing artefact means the latest render didn't run in a11y mode; the dispatcher reacts by
   * queueing a re-render with `mode=a11y` and re-invoking fetch, which then finds the
   * freshly-written file.
   */
  override fun missingOutcome(previewId: String, kind: String): DataProductRegistry.Outcome =
    DataProductRegistry.Outcome.RequiresRerender("a11y")

  /** Every advertised kind here is produced only under a11y-mode renders. */
  override fun renderModeFor(kind: String): String? = if (kind in knownKinds) "a11y" else null

  /** The overlay kind is a PNG — never parse it as JSON, even on `inline = true`. */
  override fun allowInlineUpgrade(kind: String): Boolean =
    kind != DesktopAccessibilityDataProducer.KIND_OVERLAY

  /**
   * The overlay PNG rides as an `extras` entry on every a11y kind so a panel that subscribed to any
   * of them gets the picture without a follow-up `data/fetch`. Skips when the overlay file isn't on
   * disk for [previewId] (empty-nodes previews produce no overlay).
   */
  override fun extras(
    previewId: String,
    kind: String,
    payload: JsonElement?,
  ): List<DataProductExtra>? {
    val overlay = rootDir.resolve(previewId).resolve(DesktopAccessibilityDataProducer.FILE_OVERLAY)
    if (!overlay.exists()) return null
    return listOf(
      DataProductExtra(
        name = DesktopAccessibilityDataProducer.OVERLAY_EXTRA_NAME,
        path = overlay.absolutePath,
        mediaType = "image/png",
        sizeBytes = overlay.length().takeIf { it > 0 },
      )
    )
  }
}
