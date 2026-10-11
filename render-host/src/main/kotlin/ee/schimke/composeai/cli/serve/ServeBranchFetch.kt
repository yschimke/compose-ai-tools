package ee.schimke.composeai.cli.serve

/**
 * Why a delivery-branch read failed. A bare `ByteArray?` conflated "never published" with "GitHub
 * is throttling us", which need opposite handling:
 * - Negative caching: [ServeBundleHost] remembers pinned misses forever, valid only for [NotFound].
 * - Retrying: pointless after a 404, the whole fix after a 429 (honouring `Retry-After`).
 *
 * Transport-agnostic and dependency-free so classification and backoff unit-test without a socket.
 */
public sealed interface BranchFetch {

  /** The bytes, read and size-capped. */
  public class Ok(public val bytes: ByteArray) : BranchFetch

  /** `404`/`410`: definitively no such file. The only outcome a caller may treat as permanent. */
  public data object NotFound : BranchFetch

  /**
   * Rate limited: `429`, or a `403` without the body we asked for (GitHub uses `403` for some rate
   * limits; misreading one costs a retry, the other way caches a throttle as missing).
   * [retryAfterSeconds] is the server's `Retry-After`, when sent.
   */
  public data class Throttled(val retryAfterSeconds: Long?) : BranchFetch

  /**
   * The host is unwell: any `5xx`, or a `4xx` that is neither missing nor throttled. Carries
   * `Retry-After` too (valid on `503`, RFC 9110 §10.2.3), so retries don't burn out inside an
   * outage.
   */
  public data class Unavailable(val status: Int, val retryAfterSeconds: Long? = null) : BranchFetch

  /** Never got an answer: connect/read timeout, DNS, TLS, reset. */
  public data class Transport(val detail: String) : BranchFetch

  /**
   * The file exists but outgrew [limitBytes] before it was fully read; no bytes are returned.
   * Distinct from [NotFound] because some contracts must tell them apart (known-differences answers
   * `too-large`/413 vs `unreadable`/404). Not transient; memoisable only on a pinned `(commit,
   * path)`.
   */
  public data class TooLarge(val limitBytes: Long) : BranchFetch

  /** The bytes, or null for every failure — the shape the pre-existing call sites still want. */
  public val bytesOrNull: ByteArray?
    get() = (this as? Ok)?.bytes

  /**
   * Whether asking again could answer differently: false for [Ok] and [NotFound], true otherwise.
   */
  public val isTransient: Boolean
    get() = this is Throttled || this is Unavailable || this is Transport

  /** A short, log-safe reason. Never includes the URL — callers pair it with their own. */
  public val summary: String
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

  public companion object {
    /** Longest we will ever honour a `Retry-After` for — beyond this, failing fast is kinder. */
    public const val MAX_RETRY_AFTER_SECONDS: Long = 30L

    /** Attempts after the first. Small: a request is waiting behind this. */
    public const val MAX_RETRIES: Int = 2

    /** First backoff step; doubled per attempt. */
    public const val BASE_BACKOFF_MILLIS: Long = 250L

    /**
     * Classify one HTTP status; [retryAfterSeconds] (parsed `Retry-After`) is used where
     * applicable.
     */
    public fun ofStatus(status: Int, retryAfterSeconds: Long? = null): BranchFetch =
      when {
        status == 404 || status == 410 -> NotFound
        status == 429 || status == 403 -> Throttled(retryAfterSeconds?.coerceAtLeast(0))
        else -> Unavailable(status, retryAfterSeconds?.coerceAtLeast(0))
      }

    /**
     * Delay before attempt [attempt] (1-based retries), or null when the outcome shouldn't be
     * retried or attempts are spent. A longer server `Retry-After` wins over the exponential
     * schedule, capped at [MAX_RETRY_AFTER_SECONDS].
     */
    public fun retryDelayMillis(outcome: BranchFetch, attempt: Int): Long? {
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
     * Parse a `Retry-After` header's delta-seconds form only (the HTTP-date form isn't sent by our
     * hosts). Unparseable means absent, falling back to the exponential schedule.
     */
    public fun parseRetryAfter(header: String?): Long? =
      header?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
  }
}
