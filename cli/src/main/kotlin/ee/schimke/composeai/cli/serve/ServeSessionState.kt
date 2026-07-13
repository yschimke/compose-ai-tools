package ee.schimke.composeai.cli.serve

import java.io.File

/**
 * The cheap, durable "instance state" of a serve session — everything needed to (re)open its
 * daemon-backed [ServeRenderHost] *without* rebuilding. Produced once by a [ServeSessionFactory]
 * (the expensive discover/build step) and retained across suspend/resume, so an idle session can
 * release its daemon subprocess and be brought back from this state alone — like an Activity
 * restoring from saved instance state rather than being recreated from scratch.
 *
 * Everything here already persists on disk (the `daemon-launch.json` descriptor + the discovered
 * preview list), so holding it costs almost nothing while the daemon is suspended.
 */
data class ServeSessionState(
  /** `build/compose-previews/daemon-launch.json` the daemon relaunches from. */
  val descriptor: File,
  val workspaceRoot: File,
  val workspaceName: String,
  val previews: List<ServePreview>,
  /** Human label for the tenant (e.g. the module's Gradle path, or `module@rev`). */
  val label: String,
  /**
   * App-declared `@ThemeCatalog` themes (module-global) surfaced in the viewer's Theme selector.
   */
  val declaredThemes: List<ServeTheme> = emptyList(),
  /**
   * Optional **catalog-id → daemon-preview-id** alias map, set only for a trusted-catalog live
   * session ([ServeCatalogStore] / [ServeBundleDaemon]). The daemon knows previews by their
   * function-based descriptor id (`FilledButton_Dark`), but the published catalog links and image
   * routes use the componentId-slug id (`button-filled__ideal__default__dark`). This maps the
   * latter to the former so the live host answers the published URLs. Empty for plain project /
   * revision sessions (whose ids already match). See [bakedFallback].
   */
  val previewAliases: Map<String, String> = emptyMap(),
  /**
   * Optional factory for a **baked-PNG fallback host** covering catalog ids the daemon can't render
   * (e.g. the Android-only inset focus-ring variant, absent from the desktop bundle). Set only for
   * a trusted-catalog live session: [openHost][ServeCommand] wraps the daemon [ServeRenderHost] and
   * this fallback in a [ServeCatalogLiveHost] so browsing, deep links, and thumbnails resolve to
   * the baked catalog exactly as before while the mapped ids gain a live lane. Rebuilt on each
   * resume (the baked dir persists), so suspend/resume is preserved. Null for plain sessions.
   */
  val bakedFallback: (() -> ServeHost)? = null,
  /**
   * Optional **per-preview live lane** resolver, set only for a trusted-catalog live-bundle session
   * whose branch ships per-preview FULL bundles ([ServeCatalogStore]). Given a daemon-preview id it
   * returns a daemon-backed host that re-renders **only that one preview** from its own bundle,
   * pooled with idle LRU eviction, or null when none is available. [openHost][ServeCommand] hands
   * it to the [ServeCatalogLiveHost] as the default render lane (tried before the monolithic
   * daemon), so the small per-preview bundles are exercised routinely; a null result falls back to
   * the monolithic daemon, so the session never regresses. The pool is owned by the command (closed
   * at server shutdown) and outlives suspend/resume, so this stays valid across re-opens. Null for
   * plain sessions and for a branch that ships only the monolithic bundle.
   */
  val perPreviewResolve: ((daemonId: String) -> ServeHost?)? = null,
  /** Live upstream stream count across the pooled per-preview daemons (see [perPreviewResolve]). */
  val perPreviewStreamCount: () -> Int = { 0 },
  /**
   * Optional reclaim hook invoked when the registry **removes** this session entirely — the
   * second-level GC of a long-idle *suspended* forked session (issue #2022), NOT ordinary
   * suspend/resume. Set by the project-mode factory ([ServeRevisionFactory]) to prune the
   * revision's git worktree from disk once the session is reclaimed; null for sessions with nothing
   * on-disk to reclaim (the pinned checkout, bundle/catalog hosts). Best-effort and expected to be
   * idempotent — the registry runs it under `runCatching`.
   */
  val reclaim: (() -> Unit)? = null,
  /**
   * Cost of this session's live daemon in **live-seat permits** ([LiveSeatLimiter]). Defaults to
   * `1` (a desktop CMP daemon, and every plain project / revision session). A trusted-catalog live
   * session sets it higher for a heavier backend — an Android/Robolectric daemon costs
   * [ServeBundleDaemon.ANDROID_LIVE_SEAT_WEIGHT] — so one heavy catalog can't starve several cheap
   * ones out of a flat seat count. Only consulted when [ServeHttpServer] enforces a live-seat
   * budget (`--live-seats`); ignored for static (snapshot/Wasm) sessions, which take no seat.
   */
  val liveSeatWeight: Int = 1,
)
