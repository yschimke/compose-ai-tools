package ee.schimke.composeai.cli.serve

import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Bounds concurrent **live** (daemon-backed) stream sessions by a *permit budget* rather than a
 * flat session count, so a cheap desktop CMP daemon and a heavy Robolectric Android daemon don't
 * cost the same seat. [totalPermits] is the whole-box budget (memory-derived on the deployed image,
 * see `deploy/image/entrypoint.sh`); each session acquires permits equal to its backend's
 * **weight** — `1` for a desktop CMP daemon, more for a heavier Android one ([ServeBundleDaemon]'s
 * `ANDROID_LIVE_SEAT_WEIGHT]`). A session that can't get its permits is refused (the caller closes
 * the WebSocket with 1013 "Try Again Later") instead of spawning a daemon that would risk the OOM
 * killer.
 *
 * Why weighting fixes the reported starvation: with a flat cap of 1, a single heavy `wear-m3`
 * Android daemon holds the only seat and turns away the cheap `compose-m3` CMP daemon even though
 * the box has memory for it. Under a budget of 2 with Android weight 2, two CMP sessions coexist,
 * while a lone Android session still runs (its weight is [coerced][acquire] down to the budget so
 * it never deadlocks against a ceiling smaller than its weight).
 *
 * [totalPermits] `<= 0` means **unbounded** — the historical local-`serve` behaviour — and every
 * [acquire] returns a free ticket. A weight `<= 0` (a static snapshot/Wasm session that spawns no
 * daemon) is likewise always free.
 *
 * Thread-safe: the backing [Semaphore] is fair-agnostic like the old flat gate, and each [Ticket]
 * releases its permits at most once.
 */
class LiveSeatLimiter(
  val totalPermits: Int,
  /**
   * Permits carved out of [totalPermits] that **only** [acquireBackground] can draw on — the
   * per-preview lane's guaranteed slice.
   *
   * Without it that lane can be starved to a standstill, and was. On preview.coo.ee the eight
   * resident catalog daemons held all 8 permits with `activeStreams: 0`, so every
   * `acquireBackground` was refused, [ServePerPreviewDaemonPool.get] returned null, and
   * `ServeCatalogLiveHost.renderInternal` turned that `NotFound` into `Busy` — a `503 render busy;
   * retry shortly` on 39 of 39 attempts for all 21 of meshcore-mobile's supplement-module previews,
   * for the life of the server. The catalog's theme prefetch then pinned at 288/372 forever because
   * those 84 targets could never be filled.
   *
   * It is deliberately small — one desktop daemon's worth by default. The point is not to give the
   * lane a fair share, only to make it impossible to starve completely: a preview whose ONLY live
   * lane is its own per-preview bundle (a catalog with a supplement module, where the monolithic
   * daemon simply does not carry the id) has no fallback that renders, unlike a burst replica which
   * can narrow onto the primary.
   */
  val perPreviewReserve: Int = DEFAULT_PER_PREVIEW_RESERVE,
) {
  // The reserve is held in its own semaphore rather than subtracted from a single one, so no
  // amount of general-lane demand can consume it. `acquire` never sees it at all.
  //
  // Carved out ONLY down to [STREAM_RESERVE] — the heaviest single backend — so the general lane
  // can always hold one at its true weight. Take more than that and the budget silently
  // over-admits: on a `--live-seats 2` box a general lane of 1 would coerce an Android stream
  // (weight 2) down to a single permit, and a per-preview daemon could then take the reserved one,
  // putting three permits' worth of processes on a box budgeted for two. The whole point of the
  // weighting is that it does not do that.
  //
  // So a box too small to afford the slice simply doesn't get one, and keeps its old behaviour
  // exactly. That is the correct answer rather than a regression: a box that can barely run one
  // Android daemon cannot run a second daemon of any kind, and the starvation this reserve fixes
  // only arises where there are seats to be hoarded in the first place.
  //
  // Public because it is what `/status` must publish: [perPreviewReserve] is what was *asked for*,
  // and reporting that would state a slice the limiter may not actually hold — on a small box it is
  // clamped to nothing, and an oversized custom value would otherwise be reported as larger than
  // the whole budget. A diagnostic added to make starvation visible must not itself be able to lie.
  val perPreviewPermits: Int =
    if (totalPermits > 0)
      perPreviewReserve.coerceIn(0, (totalPermits - STREAM_RESERVE).coerceAtLeast(0))
    else 0
  /**
   * Permits the general lane (streams, burst replicas) may draw on — the budget minus the slice.
   */
  private val generalPermits: Int = (totalPermits - perPreviewPermits).coerceAtLeast(0)
  private val semaphore: Semaphore? = if (totalPermits > 0) Semaphore(generalPermits) else null
  private val perPreviewSemaphore: Semaphore? =
    if (perPreviewPermits > 0) Semaphore(perPreviewPermits) else null

  /** True when this limiter imposes no bound (`totalPermits <= 0`). */
  val unbounded: Boolean
    get() = semaphore == null

  /**
   * Try to reserve [weight] permits for a live session. Returns a [Ticket] the caller **must**
   * [close][Ticket.close] when the session ends (release the permits), or `null` when the budget is
   * exhausted and the session should be refused.
   *
   * A [weight] `<= 0` (static/no-daemon session) and an [unbounded] limiter both return a
   * zero-permit ticket that always succeeds. A positive [weight] larger than [totalPermits] is
   * coerced down to [totalPermits], so a backend heavier than the whole budget can still run alone
   * rather than being permanently refused.
   */
  fun acquire(weight: Int, verified: Boolean = true, countRefusal: Boolean = true): Ticket? {
    val sem = semaphore ?: return Ticket(0)
    if (weight <= 0) return Ticket(0)
    // Coerced to the GENERAL lane's capacity, not [totalPermits]: the reserve is not available
    // here, so coercing to the whole budget would ask for more permits than this semaphore can
    // ever hold and refuse a heavy backend forever — the deadlock the coercion exists to avoid.
    val permits = weight.coerceIn(1, generalPermits)
    if (sem.tryAcquire(permits)) return Ticket(permits)
    // Seats are reserved before the session is leased (see the stream lane), so a request naming
    // a session the registry doesn't have reaches the budget too. Those are split off rather than
    // dropped: on a public box they are mostly noise anyone could generate, but on a `--revisions`
    // box a valid revision is *legitimately* unknown until its first lease builds it, and its
    // refusals are real demand. Two counters keep both readings honest.
    // Only count callers that actually turn someone away. A caller that degrades instead — the
    // shared replica pool narrows its burst onto a host already in circulation and still serves the
    // render — must not inflate this, or the one number that answers "is the budget too small here"
    // starts reporting throttled batches as refused visitors.
    if (countRefusal) {
      if (verified) refusals.incrementAndGet() else unverifiedRefusals.incrementAndGet()
    }
    return null
  }

  /**
   * Reserve [weight] permits for a **background** holder — a pooled render daemon — leaving at
   * least [STREAM_RESERVE] free for an interactive stream. Returns null (without counting a
   * refusal) when that headroom isn't there.
   *
   * Render pools and streams are not equal claims on the box. A pooled daemon exists to make a
   * *thumbnail* faster and its caller always has a fallback — baked pixels, or the monolithic
   * daemon — while a refused stream is a visitor staring at "Live preview is at capacity". Charging
   * both against one flat budget let the cheap, deferrable work take the last seat and turn the
   * valuable, undeferrable work away: on a `--live-seats 1` box the first pooled daemon refused
   * every stream that followed.
   *
   * Implemented as "take weight + reserve atomically, then hand the reserve straight back", so it
   * can never over-admit under a race — the worst case is a background holder briefly seeing less
   * headroom than really exists and declining, which is the safe direction.
   */
  fun acquireBackground(
    weight: Int,
    reserve: Int = STREAM_RESERVE,
    /**
     * Whether this caller may draw on the per-preview slice. True for the per-preview lane the
     * slice was carved out for; **false** for any other background holder.
     *
     * The shared prefetch pool is background work but it is not per-preview work. Letting it take
     * the slice would hand away the one seat a supplement-only preview is guaranteed — and once the
     * general lane fills to its stream headroom that preview cannot open its only daemon and falls
     * back to Busy/503, which is exactly the starvation the slice was added to prevent.
     */
    dedicatedSlice: Boolean = true,
  ): Ticket? {
    val sem = semaphore ?: return Ticket(0)
    if (weight <= 0) return Ticket(0)
    val permits = weight.coerceIn(1, totalPermits)
    // The dedicated slice first (see above). It exists precisely so a saturated general lane cannot
    // refuse
    // this, so it is taken WITHOUT the stream headroom check — those permits were never available
    // to a stream in the first place, and demanding headroom that by construction cannot exist
    // would make the reserve unusable.
    if (dedicatedSlice) {
      perPreviewSemaphore?.let {
        if (it.tryAcquire(permits)) return Ticket(permits, reserved = true)
      }
    }
    // Otherwise the general lane, still leaving room for an interactive stream.
    if (!sem.tryAcquire(permits + reserve.coerceAtLeast(0))) return null
    if (reserve > 0) sem.release(reserve)
    return Ticket(permits)
  }

  /**
   * Permits currently available across BOTH lanes — for tests/diagnostics and `/status`.
   *
   * Summed rather than reporting the general lane alone, so the figure stays comparable to
   * [totalPermits]: a box with its slice free but its general lane full is not at capacity, and
   * reporting `0 free / 8` there would restate the very confusion this reserve fixes.
   */
  fun availablePermits(): Int =
    semaphore?.let { it.availablePermits() + (perPreviewSemaphore?.availablePermits() ?: 0) }
      ?: Int.MAX_VALUE

  /** Permits free in the per-preview slice alone — lets `/status` show the lane isn't starved. */
  fun perPreviewPermitsAvailable(): Int =
    perPreviewSemaphore?.availablePermits() ?: if (unbounded) Int.MAX_VALUE else 0

  /**
   * How many live sessions this limiter has turned away since startup, monotonic.
   *
   * Deliberately a **counter, not a gauge**: a refusal is an event lasting as long as it takes the
   * caller to give up, while [availablePermits] is a level you happen to sample. On a box with a
   * handful of viewers, polling the level essentially never catches the moment of pressure, so "is
   * the seat budget actually too small here?" was unanswerable from `/status` — you would read a
   * comfortable-looking figure whatever the truth. This is the number that answers it, and it is
   * the evidence any change to the budget (or to evicting an idle daemon in favour of an active
   * one) should be argued from.
   */
  fun refusalCount(): Long = refusals.get()

  /**
   * Refusals for a session id the registry did not have at admission time, monotonic.
   *
   * Two populations share this bucket and only the caller's deployment tells them apart: a request
   * for something that was never here (noise on a public box — anyone can generate it, which is why
   * it must not touch [refusalCount]), and a lazily-created session that is valid but unbuilt, as
   * `--revisions` produces on its first request. Read it alongside [refusalCount] rather than
   * instead of it.
   */
  fun unverifiedRefusalCount(): Long = unverifiedRefusals.get()

  companion object {
    /**
     * Permits [acquireBackground] must leave free for interactive streams. Sized at
     * [ServeBundleDaemon.ANDROID_LIVE_SEAT_WEIGHT] — the most expensive single stream — so one can
     * always start no matter how much render residency has built up.
     */
    const val STREAM_RESERVE: Int = ServeBundleDaemon.ANDROID_LIVE_SEAT_WEIGHT

    /**
     * Default [perPreviewReserve]: one desktop daemon's worth (weight 1).
     *
     * Enough that a catalog whose supplement module carries its own per-preview bundles can always
     * open one, which is the difference between those previews rendering and never rendering at
     * all. Kept to one so the general lane — streams, and the burst replicas that widen a themed
     * batch — gives up as little as possible; a second concurrent per-preview daemon still competes
     * for the general pool exactly as before.
     */
    const val DEFAULT_PER_PREVIEW_RESERVE: Int = 1
  }

  private val refusals = AtomicLong()
  private val unverifiedRefusals = AtomicLong()

  /**
   * A held reservation of [permits] live-seat permits; [close] returns them (idempotent).
   *
   * [reserved] records which pool they came from, so they go back where they came from — returning
   * a reserved permit to the general semaphore would quietly transfer the per-preview lane's slice
   * to the general one, and the starvation this class now prevents would reappear after the first
   * eviction.
   */
  inner class Ticket internal constructor(val permits: Int, private val reserved: Boolean = false) :
    AutoCloseable {
    private val released = AtomicBoolean(false)

    override fun close() {
      if (permits > 0 && released.compareAndSet(false, true)) {
        if (reserved) perPreviewSemaphore?.release(permits) else semaphore?.release(permits)
      }
    }
  }
}
