package ee.schimke.composeai.daemon

import ee.schimke.composeai.cli.AccessibilityFinding
import ee.schimke.composeai.cli.AccessibilityNode
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Desktop counterpart of `:data-a11y-connector`'s `AccessibilityDataProducer` — writes the
 * per-render a11y artefacts [DesktopAccessibilityDataProductRegistry] surfaces.
 *
 * The file names + on-disk layout are byte-identical to the Android producer (so the CLI's
 * `relativeOverlayPath` and the panel resolve them the same way), but the desktop path skips ATF
 * (Android-only): `a11y-atf.json` always carries an empty `findings` array. Writing it
 * unconditionally keeps the `a11y/atf` fetch parseable so the CLI's `anyFetchOk` flips true and the
 * module's report `status` stays `null` (not `atf-unavailable`).
 *
 * One set of files per render under `<rootDir>/<previewId>/`:
 * - `a11y-atf.json` — `{ "findings": [] }` (always empty on desktop).
 * - `a11y-hierarchy.json` — `{ "nodes": AccessibilityNode[] }` extracted from Compose semantics.
 * - `a11y-overlay.png` — the Paparazzi-style annotated screenshot from
 *   [DesktopAccessibilityOverlay]. Absent when `nodes` is empty.
 */
object DesktopAccessibilityDataProducer {

  private val json = Json {
    encodeDefaults = false
    prettyPrint = false
  }

  /** Schema version pinned alongside the on-disk shape (matches the Android producer). */
  const val SCHEMA_VERSION: Int = 1

  const val KIND_ATF: String = "a11y/atf"
  const val KIND_HIERARCHY: String = "a11y/hierarchy"
  const val KIND_OVERLAY: String = "a11y/overlay"

  /** File names under `<rootDir>/<previewId>/` — byte-identical to the Android producer. */
  const val FILE_ATF: String = "a11y-atf.json"
  const val FILE_HIERARCHY: String = "a11y-hierarchy.json"
  const val FILE_OVERLAY: String = "a11y-overlay.png"

  /** [ee.schimke.composeai.daemon.protocol.DataProductExtra.name] for the rendered overlay PNG. */
  const val OVERLAY_EXTRA_NAME: String = "overlay"

  @Serializable private data class AtfPayload(val findings: List<AccessibilityFinding>)

  @Serializable private data class HierarchyPayload(val nodes: List<AccessibilityNode>)

  /**
   * Writes ATF (empty) + hierarchy JSON to `<rootDir>/<previewId>/` and draws the overlay PNG when
   * [nodes] is non-empty and [pngFile] decodes. Idempotent — overwrites prior files.
   */
  fun writeArtifacts(
    rootDir: File,
    previewId: String,
    nodes: List<AccessibilityNode>,
    pngFile: File?,
  ) {
    val previewDir = rootDir.resolve(previewId)
    previewDir.mkdirs()
    previewDir
      .resolve(FILE_ATF)
      .writeText(json.encodeToString(AtfPayload.serializer(), AtfPayload(emptyList())))
    previewDir
      .resolve(FILE_HIERARCHY)
      .writeText(json.encodeToString(HierarchyPayload.serializer(), HierarchyPayload(nodes)))

    val overlayDest = previewDir.resolve(FILE_OVERLAY)
    val written =
      if (pngFile != null) {
        DesktopAccessibilityOverlay.generate(
          sourcePng = pngFile,
          nodes = nodes,
          destPng = overlayDest,
        )
      } else {
        null
      }
    if (written == null) {
      // No overlay produced this render (empty nodes, or a missing/undecodable source PNG). Drop
      // any overlay a previous render left behind so the registry extras + CLI `annotatedPath`
      // (both keyed on the file's existence) can't attach a stale screenshot that no longer matches
      // the current hierarchy.
      overlayDest.delete()
    }
  }
}
