package ee.schimke.composeai.cli.serve

/**
 * Server defaults shared by the two halves of `serve`.
 *
 * These were `ServeCommand`'s private companion. Seven of the twelve are read while parsing flags
 * (a default is what a missing flag means) and eleven are read by the server itself, so once the
 * command and the server sit on opposite sides of a module boundary the constants belong to neither
 * — they belong to the contract between them, which is this module.
 */
public object ServeDefaults {
  const val COMPONENT_PROTOCOL_MARKER = "compose-preview-components.json"
  const val DEFAULT_PORT = 8723
  const val DEFAULT_IDLE_EXIT_SECONDS = 60L

  /**
   * Default catalog re-check cadence (10 min). Fresh enough that a regenerated design-artifacts
   * branch reaches a running server within minutes, and — via `git ls-remote` (no API rate limit) —
   * cheap enough to poll every watched catalog at this cadence indefinitely.
   */
  const val DEFAULT_CATALOG_REFRESH_SECONDS = 600L

  /**
   * Requests per minute per address on the two ungated `/agent-access/…` routes. A well-behaved
   * agent polls every three seconds — 20/min — so this leaves room for the ask, a couple of retries
   * and a `whoami`, while keeping an anonymous caller from churning the request map.
   */
  const val DEFAULT_AGENT_GRANT_RATE_LIMIT = 40

  /**
   * Grant-route requests one caller may hold at once. More than one is normal (an agent polls while
   * its human reads the approval page); this only stops a single address pinning threads.
   */
  const val AGENT_GRANT_CALLER_CONCURRENCY = 4
  const val DEFAULT_CATALOG_FEED_IDLE_SECONDS = 7L * 24 * 60 * 60

  /**
   * Floor between catalog-blob sweeps. Comfortably shorter than the pool's own grace window (so a
   * sweep is never the thing that delays a reclaim) and long enough that a burst of catalog
   * publications runs one census rather than one each.
   */
  const val CATALOG_BLOB_SWEEP_INTERVAL_MILLIS = 5L * 60 * 1000

  /**
   * Compiles per minute per caller. Sized for a person using the editor, not for a script: a
   * deliberate Run every six seconds sustained is already brisk, and the bucket lets a burst of ten
   * through back-to-back before it starts pacing. Raise it for a busy shared host; 0 turns the
   * limiter off entirely.
   */
  const val DEFAULT_PLAYGROUND_RATE_LIMIT = 10

  /**
   * Image uploads per minute per GitHub account. Sized for the actual caller — an agent pushing a
   * PR's worth of before/after renders in one go — so the whole batch lands in a burst and a
   * runaway loop is paced rather than allowed to churn the store. 0 turns the budget off.
   */
  const val DEFAULT_IMAGE_RATE_LIMIT = 60

  /**
   * Why the image lane didn't start. One constant because two places say it: [openImageLane] when
   * the rest of the server is coming up anyway, and the nothing-to-serve exit, whose own message
   * would otherwise be the only thing an operator of a pure image host ever sees — and it reads as
   * "you didn't ask for a lane" when what happened is "the lane you asked for needs one more
   * argument".
   */
  const val IMAGE_LANE_NO_REPO =
    "serve: --accept-images needs a repository to check uploader access against. Pass " +
      "--image-upload-repo <owner/repo> (or configure --github-auth-repo). Image lane disabled."

  /**
   * Uploads one account may have in flight at once. One: each upload is a memory write that
   * finishes in milliseconds, so serialising a single caller costs nothing and keeps a batch from
   * occupying every request thread the host has.
   */
  const val IMAGE_CALLER_CONCURRENCY = 1

  // ---- defaults the CLI half also needs ----
  //
  // Each is `const val X = <the class that owns it>.<its constant>`: one value, two names, and no
  // way for them to drift. The alias earns its place because the CLI legitimately needs the number
  // twice — `--help` prints it ("default: 900s") and an unset flag has to become it. Reaching into
  // `ServeImageStore` for a number is the CLI depending on the server; going through here is the
  // CLI depending on the contract, which is what this object is for.
  //
  // The constants stay declared beside the class whose policy they express — `DEFAULT_TTL_SECONDS`
  // reads correctly inside the image store and would read as noise here — so this re-exports them
  // rather than moving them.
  public const val CATALOG_BLOB_POOL_MAX_BYTES: Long = CatalogBlobPool.DEFAULT_MAX_BYTES
  public const val THEME_CACHE_MAX_BYTES: Long = ThemeCacheStore.DEFAULT_MAX_BYTES
  public const val IMAGE_TTL_SECONDS: Long = ServeImageStore.DEFAULT_TTL_SECONDS
  public const val IMAGE_MAX_TOTAL_BYTES: Long = ServeImageStore.DEFAULT_MAX_TOTAL_BYTES
  public const val IMAGE_MAX_IMAGES: Int = ServeImageStore.DEFAULT_MAX_IMAGES
  public const val DOC_TTL_SECONDS: Long = ServeDocStore.DEFAULT_TTL_SECONDS
  public const val CATALOG_MAX_IMAGES: Int = ServeCatalogStore.DEFAULT_MAX_IMAGES
  public const val CATALOG_REPO: String = ServeCatalogStore.DEFAULT_REPO
  public const val CATALOG_BRANCH_PREFIX: String = ServeCatalogStore.DEFAULT_BRANCH_PREFIX
  public const val HISTORY_BRANCH: String = ServeProjectHistory.DEFAULT_BRANCH
  public const val MAX_DERIVED_CONCURRENT_RENDERS: Int =
    ServeBackgroundWork.MAX_DERIVED_CONCURRENT_RENDERS
  public const val AGENT_GRANT_HARD_MAX_TTL_SECONDS: Long =
    ServeAgentGrantStore.HARD_MAX_GRANT_TTL_SECONDS
  public const val AGENT_GRANT_MAX_ACTIVE: Int = ServeAgentGrantStore.DEFAULT_MAX_ACTIVE_GRANTS
  public const val AGENT_GRANT_MAX_TTL_SECONDS: Long =
    ServeAgentGrantStore.DEFAULT_MAX_GRANT_TTL_SECONDS
  public const val PLAYGROUND_SANDBOX_TTL_SECONDS: Long = PlaygroundSandbox.DEFAULT_TTL_SECONDS
  public const val PLAYGROUND_SANDBOX_PIDS: Int = PlaygroundSandbox.DEFAULT_PIDS
  public const val PLAYGROUND_SANDBOX_MEMORY_MB: Int = PlaygroundSandbox.DEFAULT_MEMORY_MB
  public const val PLAYGROUND_SANDBOX_CPUS: Double = PlaygroundSandbox.DEFAULT_CPUS
  public const val PLAYGROUND_COMPILE_SLOTS: Int = PlaygroundJailedCompiler.DEFAULT_COMPILE_SLOTS
  public const val PLAYGROUND_EDIT_LEASE_TTL_MILLIS: Long =
    PlaygroundCompileService.DEFAULT_EDIT_LEASE_TTL_MILLIS
  public const val PLAYGROUND_CATALOG_LIMIT: Int = PlaygroundCatalogTargets.DEFAULT_LIMIT
  public const val HOST_LOOPBACK: String = ServeUrls.LOOPBACK
  public const val HOST_ALL_INTERFACES: String = ServeUrls.ALL_INTERFACES
}
