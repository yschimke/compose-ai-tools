package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.daemon.protocol.DataProductAttachment
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import kotlinx.serialization.json.JsonElement

/**
 * Producer-side seam for the D1 data-product surface (see
 * [docs/daemon/DATA-PRODUCTS.md](../../../../../../../docs/daemon/DATA-PRODUCTS.md)).
 *
 * The dispatcher ([JsonRpcServer]) doesn't know how to build any payload itself — it just routes
 * `data/fetch`, `data/subscribe`, `data/unsubscribe`, and the attach-on-`renderFinished` path
 * through this registry. A default-empty implementation ([Empty]) keeps the protocol surface open
 * while no kind is wired (the D1 contract: methods exist, every kind is `DataProductUnknown`).
 *
 * Real implementations live next to the renderer-side machinery that produces the data — the
 * Android-side a11y producer (`renderer-android`) is the first concrete consumer in D2. They
 * register at daemon-main construction time, not here.
 */
interface DataProductRegistry {
  /**
   * Kinds the daemon can produce. Surfaced via `initialize.capabilities.dataProducts` so clients
   * can grey out unavailable panels at handshake time.
   */
  val capabilities: List<DataProductCapability>

  /**
   * Fast-path lookup: does the registry know this kind at all? Used by the subscribe path to reject
   * unknown kinds before bookkeeping takes any memory. Default scans [capabilities]; producers with
   * many kinds may override with a hash lookup.
   */
  fun isKnown(kind: String): Boolean = capabilities.any { it.kind == kind }

  /**
   * Pull-on-demand fetch for one `(previewId, kind)` pair against the latest render. The dispatcher
   * catches the four documented failure shapes via [Outcome] and translates each to its wire-error
   * code.
   *
   * `params` carries per-kind options (e.g. `nodeId` for `layout/inspector`); `inline` mirrors the
   * `data/fetch.inline` flag — `true` asks the registry to inline the payload (or `bytes` for blob
   * kinds), `false` lets it return a `path` for cheap local-client read-from-disk.
   */
  fun fetch(previewId: String, kind: String, params: JsonElement?, inline: Boolean): Outcome

  /**
   * Build the attachment list for [previewId]'s pending `renderFinished` across the supplied
   * [kinds] (the union of the client's subscriptions for this preview plus the global
   * `attachDataProducts` set). Returns an empty list when nothing's available — kinds that have no
   * on-disk artefact for this render simply drop out of the attachment.
   *
   * Always called *after* the render produced the PNG, so producers can read whatever the renderer
   * wrote to disk during the same pass.
   */
  fun attachmentsFor(previewId: String, kinds: Set<String>): List<DataProductAttachment>

  /**
   * Producer-side subscription lifecycle hook. Called by the dispatcher when a client issues a
   * successful `data/subscribe` for `(previewId, kind)`. [params] carries the per-kind subscription
   * option bag — `compose/recomposition` reads `{ frameStreamId, mode }` from it; stateless kinds
   * see `null`.
   *
   * Default body is a no-op so existing producers (`a11y/atf`, `a11y/hierarchy`, `Empty`) need no
   * change. Producers that maintain per-subscription state — recomposition counters, slot-table
   * snapshots, anything that has to reset when the panel opens — override this to install the
   * bookkeeping, then tear down in [onUnsubscribe].
   *
   * Idempotent on the wire: a re-subscribe (same `(previewId, kind)`) calls this again with the
   * latest `params`. Producers that need "reset on re-subscribe" semantics use that as the signal.
   */
  fun onSubscribe(previewId: String, kind: String, params: JsonElement?) {}

  /**
   * Producer-side subscription teardown. Fires from `data/unsubscribe`, from a `setVisible` that
   * drops [previewId] (subscriptions are sticky-while-visible per the spec), and from a daemon
   * shutdown. Default no-op; producers with per-subscription state should clear it here.
   */
  fun onUnsubscribe(previewId: String, kind: String) {}

  /**
   * Tagged outcome of a [fetch]. The dispatcher maps each case to its wire-error counterpart in
   * `JsonRpcServer.handleDataFetch`.
   */
  sealed interface Outcome {
    data class Ok(val result: DataFetchResult) : Outcome

    /** Kind not advertised by this registry → `DataProductUnknown` (-32020). */
    data object Unknown : Outcome

    /** Preview has never rendered → `DataProductNotAvailable` (-32021). */
    data object NotAvailable : Outcome

    /** Producer-side failure → `DataProductFetchFailed` (-32022). */
    data class FetchFailed(val message: String, val errorKind: String? = null) : Outcome

    /** Re-render budget tripped → `DataProductBudgetExceeded` (-32023). */
    data object BudgetExceeded : Outcome

    /**
     * D3 — the latest pass didn't compute the kind and producing it requires a fresh render in the
     * named [mode] (DATA-PRODUCTS.md § "Re-render semantics"). The dispatcher
     * ([JsonRpcServer.handleDataFetch]) reacts by:
     *
     * 1. Queueing a re-render of just `previewId` in [mode], emitting a normal
     *    `renderStarted`/`renderFinished` so the panel UI updates the PNG if it changed.
     * 2. Bounding the wait by the per-request budget (`composeai.daemon.dataFetchRerenderBudgetMs`,
     *    default 30000ms). On budget exceeded the dispatcher returns [Outcome.BudgetExceeded]'s
     *    wire error (`-32023`) — but per the spec the render is *not* cancelled, the fetch just
     *    gives up waiting for it.
     * 3. Re-invoking [fetch] once the render lands so the registry can return [Ok] (or another
     *    failure) against the now-current pass.
     *
     * `mode` is a renderer-side mode tag (e.g. `"a11y"`, `"recomposition"`); the dispatcher
     * forwards it through the host payload's `mode=<mode>` key so the renderer-agnostic seam stays
     * stringly-typed. Producers pick the smallest mode that produces the kind — different kinds MAY
     * share a mode, in which case a follow-up D-step can opportunistically piggy-back fetches
     * against an already-queued re-render.
     */
    data class RequiresRerender(val mode: String) : Outcome
  }

  companion object {
    /**
     * The pre-D2 default — daemon advertises no kinds, every `data/fetch` returns
     * [Outcome.Unknown], every `data/subscribe` short-circuits to the same. Used by in-process
     * tests, the harness's fake-mode scenarios, and the daemon-main path when no producer has been
     * wired yet (during D1 rollout).
     */
    val Empty: DataProductRegistry =
      object : DataProductRegistry {
        override val capabilities: List<DataProductCapability> = emptyList()

        override fun fetch(
          previewId: String,
          kind: String,
          params: JsonElement?,
          inline: Boolean,
        ): Outcome = Outcome.Unknown

        override fun attachmentsFor(
          previewId: String,
          kinds: Set<String>,
        ): List<DataProductAttachment> = emptyList()
      }
  }
}
