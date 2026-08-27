package ee.schimke.composeai.cli.serve

/**
 * Why a read against a delivery branch failed, when it did.
 *
 * ### Why this exists
 *
 * Every branch read used to answer `ByteArray?`. A 404, a 429, a 503 and a socket timeout were all
 * the same `null`, and that single value travelled all the way to the reader: the Motion lane's
 * "The recorded interaction could not be loaded" was the server's whole vocabulary for "this was
 * never published" *and* "GitHub is throttling us right now". Those want opposite handling — one is
 * a permanent fact worth remembering, the other is a reason to wait and ask again — and no caller
 * could tell them apart because the information was destroyed at the bottom of the stack.
 *
 * The distinction is load-bearing in two specific places:
 * - **Negative caching.** [ServeBundleHost] remembers pinned misses forever, on the reasoning that
 *   `(commit, path)` is immutable so "no such file" can never stop being true. That reasoning holds
 *   for [NotFound] and for nothing else: memoising a throttle turns a blip into a permanent hole
 *   that only a restart clears.
 * - **Retrying.** Asking again after a 404 is waste. Asking again after a 429 is the entire fix,
 *   provided the wait honours what the server asked for.
 *
 * Deliberately transport-agnostic and dependency-free so the classification and the backoff policy
 * unit-test without a socket.
 */
sealed interface BranchFetch {

  /** The bytes, read and size-capped. */
  class Ok(val bytes: ByteArray) : BranchFetch

  /**
   * The branch answered, definitively, that there is no such file — `404`/`410`.
   *
   * The only outcome a caller may treat as permanent. Everything else below is a statement about
   * *now*.
   */
  data object NotFound : BranchFetch

  /**
   * Rate limited — `429`, or a `403` that carries no body we asked for. [retryAfterSeconds] is the
   * server's own `Retry-After` when it sent one, which is always a better number than any we'd
   * invent.
   *
   * `403` lands here rather than under a "forbidden" case on purpose: GitHub answers `403` for some
   * rate-limit conditions, and the cost of guessing wrong in this direction is one wasted retry,
   * where guessing wrong in the other direction caches a throttle as a missing asset.
   */
  data class Throttled(val retryAfterSeconds: Long?) : BranchFetch

  /**
   * The branch host is unwell — any `5xx`, or a `4xx` that is neither missing nor throttled.
   *
   * Carries [retryAfterSeconds] for the same reason [Throttled] does: `Retry-After` is defined on
   * `503` as much as on `429` (RFC 9110 §10.2.3), and a host that tells you when it will be back is
   * giving you a better number than any schedule you'd invent. Dropping it here meant a `503`
   * asking for ten seconds got 250 ms and 500 ms instead, spending both retries inside the outage
   * and then reporting the asset as missing — the exact confusion this type exists to end.
   */
  data class Unavailable(val status: Int, val retryAfterSeconds: Long? = null) : BranchFetch

  /** Never got an answer: connect/read timeout, DNS, TLS, reset. */
  data class Transport(val detail: String) : BranchFetch

  /**
   * The branch has the file and it is **past the envelope this read was given** — the body outgrew
   * [limitBytes] before it was fully read, so no bytes are handed back.
   *
   * A distinct case rather than a `null`, because "there is no such file" and "the file is bigger
   * than we will carry" are opposite facts about the branch, and one writer at least has a contract
   * that must tell them apart: `compose-preview-known-differences/v1` answers `too-large`/413 for
   * an over-sized document or artifact and `unreadable`/404 for an absent one. Collapsed into
   * [NotFound] — which is what discarding the outcome does — an asset refused by size is reported
   * as one the producer never published, which is both a different verdict and one that hides why.
   *
   * Not transient: the file will be exactly as oversized on the next attempt, so there is nothing
   * to retry. Not permanent-cacheable either, in the sense [NotFound] is — the branch ref may
   * publish a smaller file tomorrow — but every caller that memoises does so on a pinned `(commit,
   * path)`, where the size is as immutable as the bytes.
   */
  data class TooLarge(val limitBytes: Long) : BranchFetch

  /** The bytes, or null for every failure — the shape the pre-existing call sites still want. */
  val bytesOrNull: ByteArray?
    get() = (this as? Ok)?.bytes

  /**
   * Whether asking again could plausibly answer differently. False for [Ok] (nothing to ask) and
   * for [NotFound] (the answer will not change), true for the three "right now" outcomes.
   */
  val isTransient: Boolean
    get() = this is Throttled || this is Unavailable || this is Transport

  /** A short, log-safe reason. Never includes the URL — callers pair it with their own. */
  val summary: String
    get() =
      when (this) {
        is Ok -> "ok"
        is NotFound -> "not found"
        is Throttled -> "throttled" + (retryAfterSeconds?.let { " (retry after ${it}s)" } ?: "")
        is Unavailable ->
          "unavailable ($status)" + (retryAfterSeconds?.let { " (retry after ${it}s)" } ?: "")
        is Transport -> "transport: $detail"
        is TooLarge -> "too large (over $limitBytes bytes)"
      }

  companion object {
    /** Longest we will ever honour a `Retry-After` for — beyond this, failing fast is kinder. */
    const val MAX_RETRY_AFTER_SECONDS = 30L

    /** Attempts after the first. Small: a request is waiting behind this. */
    const val MAX_RETRIES = 2

    /** First backoff step; doubled per attempt. */
    const val BASE_BACKOFF_MILLIS = 250L

    /**
     * Classify one HTTP status.
     *
     * [retryAfterSeconds] is the parsed `Retry-After` header, or null. Only consulted for the
     * statuses that can carry one.
     */
    fun ofStatus(status: Int, retryAfterSeconds: Long? = null): BranchFetch =
      when {
        status == 404 || status == 410 -> NotFound
        status == 429 || status == 403 -> Throttled(retryAfterSeconds?.coerceAtLeast(0))
        else -> Unavailable(status, retryAfterSeconds?.coerceAtLeast(0))
      }

    /**
     * How long to wait before attempt [attempt] (1-based, so `1` is the first *retry*), or null
     * when this outcome should not be retried at all or the attempts are spent.
     *
     * A server-supplied `Retry-After` wins over the exponential schedule when it is longer — it is
     * the only party that knows when it will serve again — but is capped at
     * [MAX_RETRY_AFTER_SECONDS] so a hostile or confused header cannot park a request thread for
     * minutes.
     */
    fun retryDelayMillis(outcome: BranchFetch, attempt: Int): Long? {
      if (!outcome.isTransient) return null
      if (attempt < 1 || attempt > MAX_RETRIES) return null
      val backoff = BASE_BACKOFF_MILLIS shl (attempt - 1)
      val asked =
        when (outcome) {
          is Throttled -> outcome.retryAfterSeconds
          is Unavailable -> outcome.retryAfterSeconds
          else -> null
        }
      val requested = asked?.coerceAtMost(MAX_RETRY_AFTER_SECONDS)?.times(1000L)
      return maxOf(backoff, requested ?: 0L)
    }

    /**
     * Parse a `Retry-After` header. Only the delta-seconds form — the HTTP-date form is legal but
     * needs a clock and a parse to answer the same question, and no branch host we read sends it.
     * An unparseable value is simply absent, which falls back to the exponential schedule.
     */
    fun parseRetryAfter(header: String?): Long? = header?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
  }
}
