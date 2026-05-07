package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.daemon.protocol.DataProductAttachment
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductFacet
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.data.render.pipeline.SamplingPolicy
import ee.schimke.composeai.renderer.uiautomator.UiAutomatorDataProducts
import ee.schimke.composeai.renderer.uiautomator.UiAutomatorHierarchyPayload
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

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
    previewDir
      .resolve(FILE_HIERARCHY)
      .writeText(json.encodeToString(UiAutomatorHierarchyPayload.serializer(), payload))
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
class UiAutomatorDataProductRegistry(private val rootDir: File) : DataProductRegistry {

  private val json = Json { ignoreUnknownKeys = true }

  override val capabilities: List<DataProductCapability> =
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

  override fun fetch(
    previewId: String,
    kind: String,
    params: JsonElement?,
    inline: Boolean,
  ): DataProductRegistry.Outcome {
    val file =
      when (kind) {
        UiAutomatorDataProducer.KIND_HIERARCHY ->
          fileFor(previewId, UiAutomatorDataProducer.FILE_HIERARCHY)
        else -> return DataProductRegistry.Outcome.Unknown
      }
    if (!file.exists()) return DataProductRegistry.Outcome.NotAvailable
    if (inline) {
      val payloadElement: JsonElement =
        try {
          json.parseToJsonElement(file.readText())
        } catch (t: Throwable) {
          return DataProductRegistry.Outcome.FetchFailed(
            message = "could not parse $kind for $previewId: ${t.message}"
          )
        }
      return DataProductRegistry.Outcome.Ok(
        DataFetchResult(
          kind = kind,
          schemaVersion = UiAutomatorDataProducer.SCHEMA_VERSION,
          payload = payloadElement,
        )
      )
    }
    return DataProductRegistry.Outcome.Ok(
      DataFetchResult(
        kind = kind,
        schemaVersion = UiAutomatorDataProducer.SCHEMA_VERSION,
        path = file.absolutePath,
      )
    )
  }

  override fun attachmentsFor(
    previewId: String,
    kinds: Set<String>,
  ): List<DataProductAttachment> {
    val out = mutableListOf<DataProductAttachment>()
    for (kind in kinds) {
      when (kind) {
        UiAutomatorDataProducer.KIND_HIERARCHY -> {
          val file = fileFor(previewId, UiAutomatorDataProducer.FILE_HIERARCHY)
          if (!file.exists()) continue
          out.add(
            DataProductAttachment(
              kind = kind,
              schemaVersion = UiAutomatorDataProducer.SCHEMA_VERSION,
              path = file.absolutePath,
            )
          )
        }
      // Unknown kinds drop out silently — the dispatcher already filtered against
      // `capabilities` before calling this; an unrecognised kind here means the
      // dispatcher's filtering drifted and we'd rather skip than emit garbage.
      }
    }
    return out
  }

  private fun fileFor(previewId: String, fileName: String): File =
    rootDir.resolve(previewId).resolve(fileName)
}
