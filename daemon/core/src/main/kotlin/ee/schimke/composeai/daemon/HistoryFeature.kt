package ee.schimke.composeai.daemon

/**
 * History feature gate — **post-1.0; deferred to 1.1**.
 *
 * The per-render history archive and the history JSON-RPC surface (`history/list`, `history/read`,
 * `history/diff`, `history/prune`, the `history/diff-regions` data product, the VS Code history
 * panel, the `composeai.daemon.historyDir` sysprop wiring) added more moving parts than we want to
 * cut 1.0 with: PNG + JSON write-back on every render, the git-ref-backed read sources, prune
 * budgets, and the diff UI on the panel side. Until 1.1 those pay no rent — when [ENABLED] is
 * `false`:
 *
 * - `HistoryManager` is never constructed (no `historyDir` reads, no fs touches).
 * - The `history/diff-regions` extension is not registered, so it doesn't show up in
 *   `extensions/list`.
 * - The JSON-RPC server returns `MethodNotFound` for `history/list`, `history/read`,
 *   `history/diff`, `history/prune` — clients see the same shape they would on a daemon that
 *   pre-dates H1.
 * - The VS Code extension hides its history panel and the focus-view history section.
 *
 * **Production / tests.** [ENABLED] reads `composeai.history.enabled` on each access, defaulting to
 * `false`. Production daemons never set the property and pay the gate cost (one `getProperty` per
 * check); test JVMs flip the property to `true` via a shared Gradle `systemProperty` declaration
 * (root `build.gradle.kts` → `allprojects.tasks.withType<Test>`) so the history implementation
 * keeps green and unbroken for the 1.1 re-enable. The implementation files are preserved in place —
 * this is a feature gate, **not a deletion**.
 *
 * Flip the default to `true` (and drop the sysprop indirection) when the 1.1 re-enable lands.
 */
object HistoryFeature {
  /** Override sysprop name; default `false`. */
  const val PROP: String = "composeai.history.enabled"

  @JvmStatic
  val ENABLED: Boolean
    get() = System.getProperty(PROP, "false").toBoolean()
}
