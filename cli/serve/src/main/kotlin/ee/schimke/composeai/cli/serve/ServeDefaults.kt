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
}
