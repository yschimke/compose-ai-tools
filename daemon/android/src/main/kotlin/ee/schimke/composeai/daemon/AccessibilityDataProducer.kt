package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.daemon.protocol.DataProductAttachment
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.renderer.AccessibilityFinding
import ee.schimke.composeai.renderer.AccessibilityNode
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * D2 — writes the per-render a11y artefacts the data-product registry surfaces.
 *
 * One pair of files per render under `<rootDir>/<previewId>/`:
 *
 * - `a11y-atf.json` — `{ "findings": AccessibilityFinding[] }`. Inline-transport kind
 *   (`a11y/atf`) parses this into `payload`. Always written, even when `findings` is empty,
 *   so the registry can distinguish "no findings on this render" from "a11y didn't run".
 * - `a11y-hierarchy.json` — `{ "nodes": AccessibilityNode[] }`. Path-transport kind
 *   (`a11y/hierarchy`) returns this file's absolute path; VS Code's webview reads it.
 *
 * On-disk layout pinned by [docs/daemon/DATA-PRODUCTS.md](../../../../../../../docs/daemon/DATA-PRODUCTS.md)
 * § "On-disk layout".
 */
object AccessibilityDataProducer {

  private val json = Json {
    encodeDefaults = false
    prettyPrint = false
  }

  /** Schema version pinned alongside the on-disk shape. Bumped when the shape changes. */
  const val SCHEMA_VERSION: Int = 1

  /** `a11y/atf` — findings array. */
  const val KIND_ATF: String = "a11y/atf"

  /** `a11y/hierarchy` — accessibility-relevant nodes with bounds + label + states. */
  const val KIND_HIERARCHY: String = "a11y/hierarchy"

  /** File names under `<rootDir>/<previewId>/`. */
  const val FILE_ATF: String = "a11y-atf.json"
  const val FILE_HIERARCHY: String = "a11y-hierarchy.json"

  @Serializable internal data class AtfPayload(val findings: List<AccessibilityFinding>)

  @Serializable internal data class HierarchyPayload(val nodes: List<AccessibilityNode>)

  /**
   * Writes both files to `<rootDir>/<previewId>/` (creating the directory tree if needed).
   * Idempotent — overwrites prior files. Called from [RenderEngine.render]'s a11y branch
   * with the result of [ee.schimke.composeai.renderer.AccessibilityChecker.analyze].
   */
  fun writeArtifacts(
    rootDir: File,
    previewId: String,
    findings: List<AccessibilityFinding>,
    nodes: List<AccessibilityNode>,
  ) {
    val previewDir = rootDir.resolve(previewId)
    previewDir.mkdirs()
    previewDir
      .resolve(FILE_ATF)
      .writeText(json.encodeToString(AtfPayload.serializer(), AtfPayload(findings)))
    previewDir
      .resolve(FILE_HIERARCHY)
      .writeText(json.encodeToString(HierarchyPayload.serializer(), HierarchyPayload(nodes)))
  }
}

/**
 * D2 — [DataProductRegistry] implementation that surfaces `a11y/atf` (inline) and
 * `a11y/hierarchy` (path) by reading the JSON files [AccessibilityDataProducer] writes during
 * each render. The renderer-side producer always writes when daemon-mode a11y is active; this
 * registry decides whether the data ends up on the wire based on the dispatcher's subscription
 * bookkeeping.
 *
 * `attachable: true` for both kinds — they ride `renderFinished.dataProducts` when the client
 * has subscribed. `fetchable: true` for both — pull-on-demand reads from the same files. Neither
 * kind triggers a re-render: the producer always runs in daemon a11y mode, so the JSON is on
 * disk for any preview that has rendered at least once.
 *
 * `rootDir` mirrors `RenderEngine`'s `dataDir` (defaults to `<outputDir.parent>/data`). Wired by
 * [DaemonMain].
 */
class AccessibilityDataProductRegistry(private val rootDir: File) : DataProductRegistry {

  private val json = Json { ignoreUnknownKeys = true }

  override val capabilities: List<DataProductCapability> =
    listOf(
      DataProductCapability(
        kind = AccessibilityDataProducer.KIND_ATF,
        schemaVersion = AccessibilityDataProducer.SCHEMA_VERSION,
        transport = DataProductTransport.INLINE,
        attachable = true,
        fetchable = true,
        requiresRerender = false,
      ),
      DataProductCapability(
        kind = AccessibilityDataProducer.KIND_HIERARCHY,
        schemaVersion = AccessibilityDataProducer.SCHEMA_VERSION,
        transport = DataProductTransport.PATH,
        attachable = true,
        fetchable = true,
        requiresRerender = false,
      ),
    )

  override fun fetch(
    previewId: String,
    kind: String,
    params: JsonElement?,
    inline: Boolean,
  ): DataProductRegistry.Outcome {
    val (file, transport) =
      when (kind) {
        AccessibilityDataProducer.KIND_ATF ->
          fileFor(previewId, AccessibilityDataProducer.FILE_ATF) to DataProductTransport.INLINE
        AccessibilityDataProducer.KIND_HIERARCHY ->
          fileFor(previewId, AccessibilityDataProducer.FILE_HIERARCHY) to
            DataProductTransport.PATH
        else -> return DataProductRegistry.Outcome.Unknown
      }
    if (!file.exists()) return DataProductRegistry.Outcome.NotAvailable
    val payloadElement: JsonElement? =
      try {
        json.parseToJsonElement(file.readText())
      } catch (t: Throwable) {
        return DataProductRegistry.Outcome.FetchFailed(
          message = "could not parse $kind for $previewId: ${t.message}"
        )
      }
    val result =
      when {
        inline ->
          DataFetchResult(
            kind = kind,
            schemaVersion = AccessibilityDataProducer.SCHEMA_VERSION,
            payload = payloadElement,
          )
        transport == DataProductTransport.INLINE ->
          // Even when the caller didn't ask for inline, the `inline` transport kind has no
          // separate `path` representation. Returning the parsed payload keeps the API
          // self-consistent: `transport='inline'` ⇒ payload always set.
          DataFetchResult(
            kind = kind,
            schemaVersion = AccessibilityDataProducer.SCHEMA_VERSION,
            payload = payloadElement,
          )
        else ->
          DataFetchResult(
            kind = kind,
            schemaVersion = AccessibilityDataProducer.SCHEMA_VERSION,
            path = file.absolutePath,
          )
      }
    return DataProductRegistry.Outcome.Ok(result)
  }

  override fun attachmentsFor(
    previewId: String,
    kinds: Set<String>,
  ): List<DataProductAttachment> {
    val out = mutableListOf<DataProductAttachment>()
    for (kind in kinds) {
      when (kind) {
        AccessibilityDataProducer.KIND_ATF -> {
          val file = fileFor(previewId, AccessibilityDataProducer.FILE_ATF)
          if (!file.exists()) continue
          val parsed =
            try {
              json.parseToJsonElement(file.readText())
            } catch (t: Throwable) {
              System.err.println(
                "AccessibilityDataProductRegistry: parse $kind failed for $previewId: ${t.message}"
              )
              continue
            }
          out.add(
            DataProductAttachment(
              kind = kind,
              schemaVersion = AccessibilityDataProducer.SCHEMA_VERSION,
              payload = parsed,
            )
          )
        }
        AccessibilityDataProducer.KIND_HIERARCHY -> {
          val file = fileFor(previewId, AccessibilityDataProducer.FILE_HIERARCHY)
          if (!file.exists()) continue
          out.add(
            DataProductAttachment(
              kind = kind,
              schemaVersion = AccessibilityDataProducer.SCHEMA_VERSION,
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
