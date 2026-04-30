package ee.schimke.composeai.mcp

import java.time.Instant

/**
 * Pluggable seam for the per-preview history feature. **No real implementation in v0** — the
 * concrete behaviour (storage layout, retention policy, diff support, MCP resource shape) is being
 * designed separately and will land behind `docs/daemon/HISTORY.md`. The seam exists now so the
 * supervisor can call [record] on every successful render without conditional code, and so a future
 * PR can swap in the real store without re-plumbing notification routing.
 *
 * **Why an interface in the MCP module instead of `:daemon:core`?** History is currently scoped to
 * MCP consumers — VS Code already has its own `.compose-preview-history/` view, and the PROTOCOL.md
 * daemon contract has no opinion on history. If `HISTORY.md` decides the daemon should own the
 * store, this interface migrates to `:daemon:core` and the MCP module depends on it from there.
 * Until then, keeping it here means no daemon-side code is paying for an undecided API.
 *
 * **Threading.** Implementations must be safe for concurrent calls from any thread — the
 * supervisor's notification router calls [record] from the daemon's reader thread, and history
 * reads (once added as resources/tools) run on the MCP server's request handler thread.
 */
interface HistoryStore {

  /**
   * Records that [pngPath] is the rendered output for [uri] at [timestamp]. The default no-op
   * implementation drops the call. Implementations should be idempotent on the (uri, timestamp) key
   * so a duplicate notification (e.g. supervisor respawn replaying a queued event) doesn't create
   * double entries.
   */
  fun record(uri: PreviewUri, pngPath: String, timestamp: Instant) {}

  /**
   * Lists history entries for [uri], newest first, capped at [limit]. The no-op default returns
   * empty. Once the real impl lands, the MCP `list_history` tool delegates here.
   */
  fun list(uri: PreviewUri, limit: Int = 20): List<Entry> = emptyList()

  /**
   * Reads the PNG bytes of a specific historical entry. Returns null when the entry isn't available
   * (no-op default, retention pruned, etc.).
   */
  fun read(uri: PreviewUri, timestamp: Instant): ByteArray? = null

  /** One row in [list]. */
  data class Entry(val timestamp: Instant, val pngPath: String, val sha256: String?)

  companion object {
    /** No-op store — the v0 default. */
    val NOOP: HistoryStore = object : HistoryStore {}
  }
}
