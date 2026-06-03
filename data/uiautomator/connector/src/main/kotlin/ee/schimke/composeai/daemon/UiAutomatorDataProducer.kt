package ee.schimke.composeai.daemon

import ee.schimke.composeai.io.SystemFileSystem
import okio.Path.Companion.toPath
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductFacet
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.data.render.pipeline.SamplingPolicy
import ee.schimke.composeai.renderer.uiautomator.UiAutomatorDataProducts
import ee.schimke.composeai.renderer.uiautomator.UiAutomatorHierarchyPayload
import java.io.File
import kotlinx.serialization.json.Json

/**
 * D2 — writes the per-render UIAutomator hierarchy artefact the data-product registry surfaces
 * (#874).
 *
 * Single file per render under `<rootDir>/<previewId>/`:
 * - `uia-hierarchy.json` — `{ "nodes": UiAutomatorHierarchyNode[] }`. Path-transport kind
 *   (`uia/hierarchy`) returns this file's absolute path; the JSON shape matches
 *   `UiAutomatorHierarchyPayload` so a downstream client can deserialise it directly.
 *
 * Always written after a successful render so the registry can distinguish "no actionable
 * nodes on this preview" (file present, `nodes: []`) from "preview never rendered" (file
 * missing).
 */
object UiAutomatorDataProducer {

  private val json = Json {
    encodeDefaults = false
    prettyPrint = false
  }

  /** Schema version pinned alongside the on-disk shape. Bumped when the shape changes. */
  const val SCHEMA_VERSION: Int = UiAutomatorDataProducts.SCHEMA_VERSION

  /** `uia/hierarchy` — actionable Compose semantics nodes filtered for `uia.*` selectors. */
  const val KIND_HIERARCHY: String = UiAutomatorDataProducts.KIND_HIERARCHY

  /** File name under `<rootDir>/<previewId>/`. */
  const val FILE_HIERARCHY: String = "uia-hierarchy.json"

  /**
   * Writes the hierarchy payload to `<rootDir>/<previewId>/uia-hierarchy.json`. Idempotent —
   * overwrites any prior file. Caller drives the `SemanticsNode` walk through
   * [`UiAutomatorHierarchyExtractor`][ee.schimke.composeai.renderer.uiautomator.UiAutomatorHierarchyExtractor]
   * so the actionable-filter / `includeNonActionable` / `merged` knobs stay in one place.
   */
  fun writeArtifacts(rootDir: File, previewId: String, payload: UiAutomatorHierarchyPayload) {
    val previewDir = rootDir.resolve(previewId)
    previewDir.mkdirs()
    SystemFileSystem.write(previewDir.resolve(FILE_HIERARCHY).path.toPath()) {
      writeUtf8(json.encodeToString(UiAutomatorHierarchyPayload.serializer(), payload))
    }
  }
}

/**
 * D2 — [DataProductRegistry] implementation that surfaces `uia/hierarchy` (path-transport) by
 * reading the JSON file [UiAutomatorDataProducer] writes during each render. Mirrors
 * [`AccessibilityDataProductRegistry`][AccessibilityDataProductRegistry]'s shape — single kind,
 * path-and-inline both fine, no overlay extras.
 *
 * `attachable: true` so the kind rides `renderFinished.dataProducts` when the client has
 * subscribed; `fetchable: true` for pull-on-demand reads from the same file. Doesn't trigger a
 * re-render: the producer always runs in interactive-android mode, so the JSON is on disk for
 * any preview that has rendered at least once.
 *
 * `rootDir` mirrors `RenderEngine`'s `dataDir`. Wired by [DaemonMain].
 */
class UiAutomatorDataProductRegistry(private val rootDir: File) :
  FileBackedDataProductRegistry(
    capabilities =
      listOf(
        DataProductCapability(
          kind = UiAutomatorDataProducer.KIND_HIERARCHY,
          schemaVersion = UiAutomatorDataProducer.SCHEMA_VERSION,
          transport = DataProductTransport.PATH,
          attachable = true,
          fetchable = true,
          requiresRerender = false,
          displayName = "UIAutomator hierarchy",
          facets = listOf(DataProductFacet.STRUCTURED),
          mediaTypes = listOf("application/json"),
          sampling = SamplingPolicy.End,
        )
      )
  ) {
  override fun fileFor(previewId: String, kind: String): File? =
    if (kind == UiAutomatorDataProducer.KIND_HIERARCHY)
      rootDir.resolve(previewId).resolve(UiAutomatorDataProducer.FILE_HIERARCHY)
    else null
}
