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
   * True when snippet *compiles* run in a disposable jailed child. False means they run in the
   * serve JVM — which is what an inactive sandbox gets you, and it also makes the compile-slot
   * budget inert (`PlaygroundJailedCompiler.wrap` hands back the in-process compiler untouched).
   */
  val compilerJailed: Boolean,
  /** `--playground-compile-slots`; only meaningful when [compilerJailed]. */
  val compileSlots: Int,
  /** One entry per wired mode, evaluated fresh on each read. */
  val modes: () -> List<Mode>,
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
}
