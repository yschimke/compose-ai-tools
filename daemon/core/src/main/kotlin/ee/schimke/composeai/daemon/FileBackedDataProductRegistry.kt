package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.daemon.protocol.DataProductAttachment
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductExtra
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Reusable base for the "per-previewId, per-kind, file-on-disk" data-product shape that most D2
 * producers follow: the renderer writes `<rootDir>/<previewId>/<file>`, the registry serves the
 * same file back via `data/fetch` and `renderFinished.dataProducts` attachments.
 *
 * Concretely, every existing file-backed registry was repeating the same skeleton: dispatch on
 * `kind`, resolve a file path, return `NotAvailable` if missing, otherwise return `PATH` (or read +
 * parse for `INLINE`), then mirror the same plumbing in `attachmentsFor`. This base captures that
 * skeleton so each concrete connector is the minimum surface — usually just the [capabilities] list
 * and a [fileFor] dispatch.
 *
 * **What the base handles:**
 * - Kind dispatch against [capabilities]; unknown kinds → [Outcome.Unknown].
 * - Missing file → [missingOutcome] (default [Outcome.NotAvailable]; the a11y registry overrides
 *   this to return [Outcome.RequiresRerender] so the dispatcher can queue a re-render).
 * - Transport routing: `PATH` returns `path`; `INLINE` reads via [readInlinePayload];
 *   `BOTH`/`PATH + inline=true` upgrade to a read.
 * - `attachmentsFor` mirrors `fetch` exactly — same file existence check, same payload-vs-path
 *   selection, same [extras] hook.
 *
 * **What it deliberately doesn't handle** — subclasses still implement directly:
 * - Subscription bookkeeping ([onSubscribe] / [onUnsubscribe]) — only
 *   `AccessibilityDataProductRegistry` needs it today and the state shape varies.
 * - Per-render metadata snapshotting ([onRender] overload picking up overrides) — the strings
 *   registry caches locale/font-scale per render so the fetched payload can stamp them in.
 * - Payload synthesis from a sibling kind's file (the strings registry derives from the semantics
 *   file, not its own).
 */
abstract class FileBackedDataProductRegistry(
  final override val capabilities: List<DataProductCapability>
) : DataProductRegistry {

  private val byKind: Map<String, DataProductCapability> = capabilities.associateBy { it.kind }

  /**
   * On-disk file backing the artefact for `(previewId, kind)`. Concrete subclasses typically
   * resolve as `rootDir.resolve(previewId).resolve(<file name per kind>)`. Returning `null` is
   * equivalent to "registry doesn't own this kind" and produces [Outcome.Unknown] — usually
   * unreachable when [capabilities] is the only source of truth, but defensive.
   */
  protected abstract fun fileFor(previewId: String, kind: String): File?

  /**
   * Outcome returned when [fileFor] resolves to a missing file. Default is [Outcome.NotAvailable]
   * which maps to "the producer hasn't run yet (or didn't compute this kind this pass)". Override
   * to return [Outcome.RequiresRerender] for kinds whose `capabilities[].requiresRerender` is true
   * — the dispatcher reacts by queueing a re-render in the specified mode and re-invoking [fetch].
   * See [AccessibilityDataProductRegistry] for the current concrete consumer.
   */
  protected open fun missingOutcome(previewId: String, kind: String): DataProductRegistry.Outcome =
    DataProductRegistry.Outcome.NotAvailable

  /**
   * Decode the inline-transport payload from [file]. Default reads as a free-form JsonElement — the
   * most common case. Override when the on-disk format is a typed [kotlinx.serialization]-tagged
   * class (the fonts registry uses `FontsUsedDataProducer.readPayload(...)` so the deserialiser is
   * owned by the producer) or when the payload is synthesised rather than read directly.
   *
   * Returning `null` means "the file is structurally fine but there's nothing worth surfacing" —
   * treated the same as missing-file by [fetch] and [attachmentsFor].
   */
  protected open fun readInlinePayload(previewId: String, kind: String, file: File): JsonElement? =
    DEFAULT_JSON.parseToJsonElement(file.readText())

  /**
   * Optional extras attached alongside the payload — e.g. [DisplayFilterDataProductRegistry]
   * surfaces per-variant PNG paths under the variants-manifest payload. Default returns `null` —
   * most registries don't need it.
   *
   * `payload` is the decoded inline payload when one was read this call; for pure-PATH attaches it
   * is `null`. Subclasses that need to read the file regardless can fetch it via [fileFor] again.
   */
  protected open fun extras(
    previewId: String,
    kind: String,
    payload: JsonElement?,
  ): List<DataProductExtra>? = null

  override fun fetch(
    previewId: String,
    kind: String,
    params: JsonElement?,
    inline: Boolean,
  ): DataProductRegistry.Outcome {
    val cap = byKind[kind] ?: return DataProductRegistry.Outcome.Unknown
    val file = fileFor(previewId, kind) ?: return DataProductRegistry.Outcome.Unknown
    if (!file.exists()) return missingOutcome(previewId, kind)
    val shouldInline = cap.transport == DataProductTransport.INLINE || inline
    return if (shouldInline) {
      val payload =
        try {
          readInlinePayload(previewId, kind, file)
        } catch (t: Throwable) {
          return DataProductRegistry.Outcome.FetchFailed(
            message = "could not parse $kind for $previewId: ${t.message}"
          )
        } ?: return missingOutcome(previewId, kind)
      DataProductRegistry.Outcome.Ok(
        DataFetchResult(
          kind = kind,
          schemaVersion = cap.schemaVersion,
          payload = payload,
          extras = extras(previewId, kind, payload),
        )
      )
    } else {
      DataProductRegistry.Outcome.Ok(
        DataFetchResult(
          kind = kind,
          schemaVersion = cap.schemaVersion,
          path = file.absolutePath,
          extras = extras(previewId, kind, payload = null),
        )
      )
    }
  }

  override fun attachmentsFor(previewId: String, kinds: Set<String>): List<DataProductAttachment> {
    val out = mutableListOf<DataProductAttachment>()
    for (kind in kinds) {
      val cap = byKind[kind] ?: continue
      val file = fileFor(previewId, kind) ?: continue
      if (!file.exists()) continue
      when (cap.transport) {
        DataProductTransport.INLINE,
        DataProductTransport.BOTH -> {
          val payload =
            try {
              readInlinePayload(previewId, kind, file)
            } catch (t: Throwable) {
              System.err.println(
                "${this::class.simpleName}: parse $kind failed for $previewId: ${t.message}"
              )
              continue
            } ?: continue
          out +=
            DataProductAttachment(
              kind = kind,
              schemaVersion = cap.schemaVersion,
              payload = payload,
              extras = extras(previewId, kind, payload),
            )
        }
        DataProductTransport.PATH -> {
          out +=
            DataProductAttachment(
              kind = kind,
              schemaVersion = cap.schemaVersion,
              path = file.absolutePath,
              extras = extras(previewId, kind, payload = null),
            )
        }
      }
    }
    return out
  }

  companion object {
    private val DEFAULT_JSON = Json { ignoreUnknownKeys = true }
  }
}
