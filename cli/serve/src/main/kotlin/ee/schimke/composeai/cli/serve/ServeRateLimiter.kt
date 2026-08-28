package ee.schimke.composeai.cli.serve

/**
 * A **per-caller** budget for an expensive serve lane: a token bucket for the request *rate* plus a
 * counter for *concurrent* work, keyed by whoever is asking (issue #3214).
 *
 * Everything the playground already bounds — compile slots, the compile timeout, the request-body
 * cap, live seats, the token store's size and TTL — bounds *simultaneous resource use across the
 * whole host*. None of it is a per-caller budget, so two clients issuing back-to-back 180-second
 * compiles hold every slot indefinitely and everyone else gets "the playground is busy compiling".
 * That is the gap this closes: not lifetime (the sandbox TTL already reclaims a wedged JVM) and not
 * total capacity, but **fair sharing**.
 *
 * Deliberately its own type rather than a `PlaygroundRateLimiter`: `/docs` uploads have capacity
 * caps and likewise no rate limit, and the shape needed there is the same one.
 *
 * ## The bucket
 *
 * [permitsPerWindow] tokens refilling continuously over [windowSeconds], capacity equal to the same
 * number — so a caller may burst their whole minute's budget at once and then waits, which is what
 * an editor's Run button actually looks like. The refill is computed from elapsed time on read
 * rather than by a timer, so an idle host does no work and a caller who steps away is fully
 * refilled when they return.
 *
 * ## The concurrency counter
 *
 * [maxConcurrent] bounds what one caller may hold *at once*, and it is the half that answers the
 * issue's complaint directly: with the host's compile slots at 2 and this at 1, one caller cannot
 * hold both. It is acquired and released around the work, so it is only ever released by the caller
 * that took it — see [Decision.Admitted.release], which is idempotent.
 *
 * ## Key-space growth
 *
 * A public host is keyed partly by client address, which an attacker chooses. The map is therefore
 * bounded at [maxKeys]: admitting a new key first sweeps every entry that is **indistinguishable
 * from a fresh one** (nothing in flight and a fully refilled bucket), which costs a caller nothing
 * to lose. If that frees nothing, the host has [maxKeys] callers actively spending budget right now
 * and a new key is refused rather than growing the map — under a key-space spray that is the honest
 * answer, and the alternative is unbounded memory chosen by the attacker.
 */
class ServeRateLimiter(
  /**
   * Tokens per caller per [windowSeconds], and the bucket's capacity (so a full burst is allowed).
   */
  private val permitsPerWindow: Int,
  private val windowSeconds: Long = 60,
  /** How much work one caller may hold at once. */
  private val maxConcurrent: Int = 1,
  /** Distinct callers tracked before new ones are refused; see the class KDoc. */
  private val maxKeys: Int = DEFAULT_MAX_KEYS,
  private val clock: () -> Long = System::currentTimeMillis,
) {

  /** The outcome of asking for permission to start one unit of work. */
  sealed interface Decision {
    /**
     * Go ahead. [release] MUST be called when the work finishes (including on failure); it is
     * idempotent, so a `finally` that runs twice cannot hand the caller back a permit they no
     * longer hold.
     */
    class Admitted(private val onRelease: () -> Unit) : Decision {
      private var released = false

      fun release() {
        synchronized(this) {
          if (released) return
          released = true
        }
        onRelease()
      }
    }

    /**
     * Refused. [retryAfterSeconds] is what the caller should be told to wait — a real number for
     * the rate bound (when the next token lands) and a short nudge for the concurrency bound (which
     * clears when their own in-flight work does, at a time nobody here can predict).
     */
    data class Throttled(val retryAfterSeconds: Long, val reason: String) : Decision
  }

  private class Bucket(var tokens: Double, var lastRefillMillis: Long, var inFlight: Int)

  private val buckets = HashMap<String, Bucket>()

  private val refillPerMilli: Double = permitsPerWindow.toDouble() / (windowSeconds * 1000.0)

  /**
   * Take one permit for [key], or say why not. Cheap and non-blocking — a refused caller is told to
   * come back rather than parked, because parking is what turns a rate problem into a thread
   * problem.
   */
  fun tryAcquire(key: String): Decision {
    synchronized(this) {
      val now = clock()
      val bucket =
        buckets[key]
          ?: run {
            if (buckets.size >= maxKeys) {
              sweepIdle(now)
              if (buckets.size >= maxKeys) {
                return Decision.Throttled(
                  retryAfterSeconds = windowSeconds,
                  reason =
                    "the server is tracking $maxKeys active callers already; try again shortly",
                )
              }
            }
            Bucket(tokens = permitsPerWindow.toDouble(), lastRefillMillis = now, inFlight = 0)
              .also { buckets[key] = it }
          }

      refill(bucket, now)

      if (bucket.inFlight >= maxConcurrent) {
        return Decision.Throttled(
          retryAfterSeconds = CONCURRENCY_RETRY_AFTER_SECONDS,
          reason =
            "you already have ${bucket.inFlight} request(s) in flight (limit $maxConcurrent); " +
              "wait for one to finish",
        )
      }
      if (bucket.tokens < 1.0) {
        // When the next whole token lands, rounded up so a "retry after 0" can never bounce.
        val waitMillis = ((1.0 - bucket.tokens) / refillPerMilli).toLong()
        return Decision.Throttled(
          retryAfterSeconds = ((waitMillis + 999) / 1000).coerceAtLeast(1),
          reason = "rate limit: $permitsPerWindow request(s) per ${windowSeconds}s per caller",
        )
      }

      bucket.tokens -= 1.0
      bucket.inFlight++
      return Decision.Admitted { release(key) }
    }
  }

  /** Callers currently holding at least one permit — for `/status.json`. */
  fun activeCallers(): Int = synchronized(this) { buckets.count { it.value.inFlight > 0 } }

  /** Distinct callers tracked right now, against [maxKeys]. */
  fun trackedCallers(): Int = synchronized(this) { buckets.size }

  private fun release(key: String) {
    synchronized(this) {
      val bucket = buckets[key] ?: return
      if (bucket.inFlight > 0) bucket.inFlight--
    }
  }

  private fun refill(bucket: Bucket, now: Long) {
    val elapsed = now - bucket.lastRefillMillis
    if (elapsed <= 0) return
    bucket.lastRefillMillis = now
    bucket.tokens =
      (bucket.tokens + elapsed * refillPerMilli).coerceAtMost(permitsPerWindow.toDouble())
  }

  /**
   * Drop every entry that carries no state a fresh one wouldn't: nothing in flight, bucket full.
   * Recreating such an entry gives its caller exactly what they had, so this is free to do.
   */
  private fun sweepIdle(now: Long) {
    val iterator = buckets.entries.iterator()
    while (iterator.hasNext()) {
      val bucket = iterator.next().value
      refill(bucket, now)
      if (bucket.inFlight == 0 && bucket.tokens >= permitsPerWindow) iterator.remove()
    }
  }

  companion object {
    /**
     * Distinct callers tracked. Comfortably above any real audience for a repo-access-gated
     * playground, and small enough that a spray of forged keys costs the host kilobytes rather than
     * memory pressure.
     */
    const val DEFAULT_MAX_KEYS = 4096

    /**
     * What a concurrency refusal advises. Unlike the rate bound there is no computable answer — the
     * caller's own in-flight work clears when it clears — so this is a nudge sized to "a compile is
     * running", not a promise.
     */
    const val CONCURRENCY_RETRY_AFTER_SECONDS = 5L
  }
}
