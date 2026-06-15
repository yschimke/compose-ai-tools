package ee.schimke.composeai.daemon

/**
 * History feature gate.
 *
 * The per-render history archive and the history JSON-RPC surface (`history/list`, `history/read`,
 * `history/diff`, `history/prune`, the `history/diff-regions` data product, the VS Code history
 * panel, the `composeai.daemon.historyDir` sysprop wiring) are **on by default** — the daemon
 * records each changed render to the local history archive and serves the `history/…` methods. When
 * [ENABLED] is `false`:
 *
 * - `HistoryManager` is never constructed (no `historyDir` reads, no fs touches).
 * - The `history/diff-regions` extension is not registered, so it doesn't show up in
 *   `extensions/list`.
 * - The JSON-RPC server returns `MethodNotFound` for `history/list`, `history/read`,
 *   `history/diff`, `history/prune` — clients see the same shape they would on a daemon that
 *   pre-dates H1.
 * - The VS Code extension hides its history panel and the focus-view history section.
 *
 * [ENABLED] reads `composeai.history.enabled` on each access, defaulting to `true`. The sysprop
 * stays as an explicit off-switch (`-Dcomposeai.history.enabled=false`) for anyone who wants to opt
 * out of history recording. `history/diff` carries its own additional experimental gate (see
 * `JsonRpcServer`), so flipping this on does not by itself enable diffing.
 */
object HistoryFeature {
  /** Override sysprop name; default `true`. */
  const val PROP: String = "composeai.history.enabled"

  @JvmStatic
  val ENABLED: Boolean
    get() = System.getProperty(PROP, "true").toBoolean()
}
