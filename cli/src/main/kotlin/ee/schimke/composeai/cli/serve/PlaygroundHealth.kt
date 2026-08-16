package ee.schimke.composeai.cli.serve

/**
 * What the playground lane looks like from the outside, for `/status.json`.
 *
 * The playground has more ways to be *half* up than any other lane on the host, and until now none
 * of them were observable without shell access to the box: the admission gate can let the lane
 * serve on either of two postures ([PlaygroundPublicGate]), a configured jail may be silently
 * failing to contain anything, a mode's classpath resolves lazily and may not have resolved yet
 * (issue #3212), and the compiler runs jailed or in-process depending on whether a sandbox is
 * active. An operator away from the box — or reading `/status.json` from a monitor — could see only
 * that `/playground` answered 503, never *why*.
 *
 * Every field here is therefore a question someone asks when the playground misbehaves, answered
 * without signing in. In particular [probe] is how you find out whether the configured jail
 * actually launches **on this host**: under the repo-access posture a jail that fails its preflight
 * does not refuse the lane, so this is the only place the failure surfaces after the startup log
 * has scrolled away.
 *
 * Read cheaply and without side effects: [modes] reports each mode's *memoized* resolution state
 * ([PlaygroundClasspathSupplier.isResolved]) rather than forcing a resolve, so polling `/status`
 * never unpacks a bundle on the request path.
 */
data class PlaygroundHealth(
  /** The gate's `Allow.detail` — which posture admitted the lane, in the operator's words. */
  val admittedBy: String,
  /** The configured sandbox profile id (`none`, `unshare`, `bwrap`, `strict`, `custom`). */
  val sandboxProfile: String,
  /** False for `none`: no jail argv, and **no JVM caps or hard TTL either**. */
  val sandboxActive: Boolean,
  /**
   * True when the configured jail could not launch here and was dropped, keeping the JVM caps
   * (`PlaygroundSandbox.droppingJail`). Snippets are capped but **uncontained** — the operator
   * asked for a jail and this host cannot give them one, which is worth seeing without reading a
   * startup log.
   */
  val jailDropped: Boolean,
  /** Per-snippet-JVM heap/CPU/pid budget. Only applied when [sandboxActive]. */
  val sandboxMemoryMb: Int,
  val sandboxCpus: Double,
  val sandboxTtlSeconds: Long,
  /**
   * The startup containment preflight, when one ran (`--public` + an active sandbox). Null means it
   * was never attempted, which is expected on a token-gated host or with no sandbox — not a
   * failure.
   */
  val probe: PlaygroundSandboxProbe.Report?,
  /**
   * True when snippet *compiles* run in a disposable **jailed** child.
   *
   * False covers two different states, told apart by [jailDropped]: with it false the compiles run
   * in the serve JVM (an inactive sandbox, which also makes the compile-slot budget inert, since
   * `PlaygroundJailedCompiler.wrap` hands back the in-process compiler untouched); with it true
   * they still run in a disposable, capped, slot-limited child — just not behind a jail.
   */
  val compilerJailed: Boolean,
  /** `--playground-compile-slots`; only meaningful when [compilerJailed]. */
  val compileSlots: Int,
  /** One entry per wired mode, evaluated fresh on each read. */
  val modes: () -> List<Mode>,
  /**
   * The runtime catalog selector, or null when this host pins its bundles instead (`--playground`
   * absent). Its own half-up state is a question operators ask: a selector that offers nothing
   * because no catalog has loaded yet looks identical from outside to one whose catalogs all
   * declare a backend this host cannot render.
   */
  val catalogSelector: (() -> CatalogSelector)? = null,
  /** Stateful editing trial state and cumulative process-lifetime counters. */
  val editing: (() -> Editing)? = null,
) {
  /**
   * A wired playground mode. "Wired" means configured with a bundle source *and* backed by an
   * available render backend — it does not mean the classpath has resolved, which for a served
   * catalog happens on first use.
   */
  data class Mode(
    /** `CMP`, `ANDROID`, `REMOTE_COMPOSE`. */
    val mode: String,
    /** How the bundle was named — a path, or `served catalog '<system>'`. */
    val source: String,
    /**
     * True once the compile classpath has resolved. False on a freshly started host whose catalog
     * hasn't loaded yet (expected, self-healing) **or** on one whose bundle never materialised
     * (not). [PlaygroundHealth]'s `admittedBy` plus the startup log distinguish them.
     */
    val resolved: Boolean,
  )

  /** The runtime catalog selector's state — what it currently offers, and what it has spent. */
  data class CatalogSelector(
    /** Catalogs offerable right now: loaded, with a backend this host can render. */
    val offered: List<String>,
    /**
     * How many of those hold a resolved compile classpath. Each is an unpacked bundle held for the
     * process's life, so this is the number that matters against [limit].
     */
    val resolved: Int,
    /** `--playground-catalog-limit`. At [resolved] == [limit] a new catalog is refused. */
    val limit: Int,
  )

  data class Editing(
    val enabled: Boolean,
    val active: Boolean,
    val expiresAtEpochMs: Long? = null,
    val lastRevision: Long? = null,
    val acquisitions: Long,
    val compileAttempts: Long,
    val incrementalCompiles: Long,
    val fullFallbacks: Long,
    val lastCompileMillis: Long? = null,
  )
}
