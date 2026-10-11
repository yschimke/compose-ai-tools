package ee.schimke.composeai.cli.serve

import java.io.File

/**
 * The cheap, durable state of a serve session: everything needed to (re)open its daemon-backed
 * [ServeRenderHost] without rebuilding. Produced once by a [ServeSessionFactory] and kept across
 * suspend/resume, so an idle session can release its daemon and resume from this alone. A trusted
 * catalog may also retain its `previews × declaredThemes` PNG cache here.
 */
public data class ServeSessionState(
  /** `build/compose-previews/daemon-launch.json` the daemon relaunches from. */
  val descriptor: File,
  val workspaceRoot: File,
  val workspaceName: String,
  val previews: List<ServePreview>,
  /** Human label for the tenant (e.g. the module's Gradle path, or `module@rev`). */
  val label: String,
  /** App-declared `@ThemeCatalog` themes for the viewer's Theme selector. */
  val declaredThemes: List<ServeTheme> = emptyList(),
  /**
   * Catalog-id → daemon-preview-id aliases for a trusted-catalog live session: published routes use
   * slug ids (`button-filled__ideal__default__dark`), the daemon descriptor ids
   * (`FilledButton_Dark`). Empty when ids already match. See [bakedFallback].
   */
  val previewAliases: Map<String, String> = emptyMap(),
  /**
   * Factory for a baked-PNG host covering catalog ids the daemon can't render, wrapped with the
   * daemon host in a [ServeCatalogLiveHost] by [openHost][ServeCommand]. Rebuilt on each resume.
   * Null for plain sessions.
   */
  val bakedFallback: (() -> ServeHost)? = null,
  /**
   * Per-preview live lane for catalogs whose branch ships per-preview FULL bundles: returns a
   * pooled host that re-renders only that preview, or null to fall back to the monolithic daemon.
   * [ServeCatalogLiveHost] tries it first. The pool is command-owned and survives suspend/resume.
   */
  val perPreviewResolve: ((daemonId: String) -> ServeHost?)? = null,
  /** Probe whether a published, hydrated per-preview bundle is actually available. */
  val executableBundleAvailable: ((daemonId: String) -> Boolean)? = null,
  /** Resolve a hydrated, self-contained per-preview bundle for download. */
  val executableBundleProvider: ((daemonId: String) -> ByteArray?)? = null,
  /** Live upstream stream count across the pooled per-preview daemons (see [perPreviewResolve]). */
  val perPreviewStreamCount: () -> Int = { 0 },
  /**
   * Render-latency snapshots of the pooled per-preview daemons, folded into `/status` `renderStats`
   * (that lane serves most renders).
   */
  val perPreviewRenderStats: () -> List<RenderPerfSnapshot> = { emptyList() },
  /** Occupancy snapshots of the pooled per-preview daemons, surfaced on `/status.json`. */
  val perPreviewPoolStats: () -> List<DaemonPoolSnapshot> = { emptyList() },
  /**
   * Closes per-preview daemons idle past the window, returning how many; the pooled half of
   * [ServeHost.releaseIdleDaemons].
   */
  val perPreviewReapIdle: (idleMillis: Long) -> Int = { 0 },
  /**
   * Rendered PNG cache retained for this catalog generation across daemon suspend/resume cycles.
   */
  val catalogThemeCache: CatalogThemeCache? = null,
  /**
   * Whole-server idle clock for background optimization (null = active); wrapped by
   * [ServeBackgroundWork.idleClock] so catalog loads read as active.
   */
  val serverIdleMillis: () -> Long? = { Long.MAX_VALUE },
  /** Server-wide admission for background catalog work (see [ServeBackgroundWork]). */
  val backgroundWork: ServeBackgroundWork = ServeBackgroundWork(),
  /**
   * Called when the registry removes this session entirely (GC of a long-idle suspended fork), not
   * on suspend. The project-mode factory ([ServeRevisionFactory]) prunes the revision's worktree.
   * Best-effort and idempotent; runs under `runCatching`.
   */
  val reclaim: (() -> Unit)? = null,
  /**
   * Cost of this session's live daemon in [LiveSeatLimiter] permits: 1 by default,
   * [ServeBundleDaemon.ANDROID_LIVE_SEAT_WEIGHT] for Android, so heavy catalogs can't starve cheap
   * ones. Only used when `--live-seats` is enforced; static sessions take no seat.
   */
  val liveSeatWeight: Int = 1,
)
