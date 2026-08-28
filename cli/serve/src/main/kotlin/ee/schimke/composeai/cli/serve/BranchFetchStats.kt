package ee.schimke.composeai.cli.serve

import kotlinx.serialization.Serializable

/**
 * Counters for reads against a delivery branch — the serve-side companion to [RenderPerfStats],
 * covering the *other* thing this server does that can fail on someone else's schedule.
 *
 * ### Why
 *
 * Renders have had failure telemetry for a long time: `/status.json` reports how many failed, why,
 * and when. Branch reads — the lane that actually talks to GitHub — had none. So when every
 * published motion capture stopped loading, the only way to answer "is GitHub rate-limiting us, or
 * did we never publish that file?" was to reproduce it by hand with `curl` against both the server
 * and `raw.githubusercontent.com`. That is a diagnosis a status page should hand you.
 *
 * [BranchFetch] made the distinction *exist*; this makes it **observable**. `throttled` climbing
 * while `notFound` sits still is a rate limit, and reads off a page.
 *
 * ### Shape
 *
 * Deliberately counters plus a small recent-failure ring rather than per-URL detail: a busy server
 * reads thousands of assets, the useful question is "what is happening to us right now", and a URL
 * list is both unbounded and a way to leak a private catalog's paths onto a public status page.
 *
 * Thread-safe; every method is one short critical section. Recording is on the fetch path, so it
 * stays allocation-free in the common (successful) case.
 *
 * ### One thing these deliberately do not show
 *
 * A read counts **once, by how it ended**. The transport retries a transient failure internally
 * (see `httpFetchOutcome`), so a throttle that the retry rescued lands here as `ok` and leaves
 * `throttled` at zero — the counters live above the retry loop, which is also what lets them count
 * an injected transport at all. So `throttled` reads as "throttles we could not ride out", not
 * "throttles we met". Worth closing if the difference ever matters; counting it properly means the
 * transport reporting each attempt, which is a wider seam than this is worth today.
 */
class BranchFetchStats(private val clock: () -> Long = System::currentTimeMillis) {
  private val lock = Any()

  private var attempted = 0L
  private var cached = 0L
  private var ok = 0L
  private var notFound = 0L
  private var throttled = 0L
  private var unavailable = 0L
  private var transport = 0L
  private var tooLarge = 0L
  private var lastThrottleAtEpochMillis: Long? = null
  private var lastFailureAtEpochMillis: Long? = null
  private var lastFailureReason: String? = null

  /**
   * One read the [CatalogBlobPool] answered, so no request was made.
   *
   * Deliberately **not** an [BranchFetch.Ok] and not counted in [attempted]: those describe what
   * happened between this server and the branch host, and folding a cache hit into them would make
   * the cache invisible in exactly the numbers that exist to show what the branch is doing to us —
   * a throttle rate computed over hits plus reads understates itself as the cache warms. Kept
   * beside them because the interesting comparison is `cached` against `attempted`: that ratio is
   * the requests this box stopped making.
   */
  fun recordCached(): Unit = synchronized(lock) { cached++ }

  /** One completed read, however it ended. */
  fun record(outcome: BranchFetch): Unit =
    synchronized(lock) {
      attempted++
      when (outcome) {
        is BranchFetch.Ok -> ok++
        is BranchFetch.NotFound -> notFound++
        is BranchFetch.Throttled -> {
          throttled++
          lastThrottleAtEpochMillis = clock()
        }
        is BranchFetch.Unavailable -> unavailable++
        is BranchFetch.Transport -> transport++
        is BranchFetch.TooLarge -> tooLarge++
      }
      // `lastFailure*` describes the **branch host**, which is what an operator reads it for — and
      // [BranchFetchSnapshot.tooLarge] already says a size refusal is "not a fault of the branch
      // host the way `throttled` and `unavailable` are", naming those three as the ones to alert
      // on. Recording it here contradicted that: a catalog publishing one large asset showed up as
      // the server's last failure, with its reason, next to a timestamp that reads as an incident.
      // It belongs with `notFound` — a fact about what was published, counted and not alarmed on.
      if (
        outcome !is BranchFetch.Ok &&
          outcome !is BranchFetch.NotFound &&
          outcome !is BranchFetch.TooLarge
      ) {
        lastFailureAtEpochMillis = clock()
        lastFailureReason = outcome.summary.take(MAX_REASON_CHARS)
      }
    }

  /** A snapshot for `/status.json`, or null when nothing has been read yet. */
  fun snapshot(): BranchFetchSnapshot? =
    synchronized(lock) {
      if (attempted == 0L && cached == 0L) return null
      BranchFetchSnapshot(
        attempted = attempted,
        cached = cached,
        ok = ok,
        notFound = notFound,
        throttled = throttled,
        unavailable = unavailable,
        transport = transport,
        tooLarge = tooLarge,
        lastThrottleAtEpochMillis = lastThrottleAtEpochMillis,
        lastFailureAtEpochMillis = lastFailureAtEpochMillis,
        lastFailureReason = lastFailureReason,
      )
    }

  companion object {
    /** Failure reasons are bounded before they reach a status page. */
    const val MAX_REASON_CHARS = 200
  }
}

/**
 * Delivery-branch read counters on `/status.json` (`branchFetch`).
 *
 * `notFound` is **not** an error: a catalog legitimately declares assets a given revision never
 * published, and the lane is built to answer that cheaply. The three that mean something is wrong
 * with the *branch host* rather than with the catalog are [throttled], [unavailable] and
 * [transport] — those are the ones to alert on.
 */
@Serializable
data class BranchFetchSnapshot(
  val attempted: Long,
  /**
   * Reads the catalog blob cache answered — requests this server did **not** make.
   *
   * Excluded from [attempted] on purpose (see `BranchFetchStats.recordCached`), so every other
   * field here keeps describing the branch host alone. Climbing while [attempted] flattens across
   * restarts is what a working cache looks like.
   */
  val cached: Long = 0,
  val ok: Long,
  /** Reads the branch answered as genuinely absent. Expected, not a fault. */
  val notFound: Long,
  /** `429`/`403` — the signal that this server is being rate-limited. */
  val throttled: Long,
  /** `5xx` and other refusals. */
  val unavailable: Long,
  /** Never got an answer: timeout, DNS, TLS, reset. */
  val transport: Long,
  /**
   * Reads the branch answered with a body past the envelope the read was given.
   *
   * Not a fault of the branch host the way [throttled] and [unavailable] are — the file arrived, it
   * is simply bigger than this server will carry — but worth its own number rather than being
   * folded into [notFound]: an operator looking at a catalog that serves nothing wants "the
   * producer published something we refuse" separated from "the producer published nothing".
   */
  val tooLarge: Long = 0,
  val lastThrottleAtEpochMillis: Long? = null,
  val lastFailureAtEpochMillis: Long? = null,
  val lastFailureReason: String? = null,
)
