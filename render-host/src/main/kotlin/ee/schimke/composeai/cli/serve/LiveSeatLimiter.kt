package ee.schimke.composeai.cli.serve

import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Bounds concurrent live (daemon-backed) sessions by a permit budget rather than a flat count, so a
 * cheap desktop CMP daemon and a heavy Robolectric Android daemon don't cost the same seat. Each
 * session acquires permits equal to its backend's weight; one that can't is refused (the caller
 * closes the WebSocket with 1013) instead of spawning a daemon that risks the OOM killer.
 *
 * [totalPermits] `<= 0` means unbounded, and a weight `<= 0` (a session that spawns no daemon) is
 * always free.
 */
public class LiveSeatLimiter(
  public val totalPermits: Int,
  /**
   * Permits carved out of [totalPermits] that only [acquireBackground] can draw on, so resident
   * daemons can never starve the per-preview lane completely. A preview whose only live lane is its
   * own per-preview bundle (a catalog supplement module) has no other fallback that renders.
   */
  public val perPreviewReserve: Int = DEFAULT_PER_PREVIEW_RESERVE,
) {
  // Held in its own semaphore so general-lane demand can never consume it. Carved out only down to
  // [STREAM_RESERVE] so the general lane can always hold the heaviest backend at its true weight;
  // a box too small for the slice gets none. Public because `/status` must report what is actually
  // held, not what was asked for.
  public val perPreviewPermits: Int =
    if (totalPermits > 0)
      perPreviewReserve.coerceIn(0, (totalPermits - STREAM_RESERVE).coerceAtLeast(0))
    else 0
  /** Permits the general lane (streams, burst replicas) may draw on. */
  private val generalPermits: Int = (totalPermits - perPreviewPermits).coerceAtLeast(0)
  private val semaphore: Semaphore? = if (totalPermits > 0) Semaphore(generalPermits) else null
  private val perPreviewSemaphore: Semaphore? =
    if (perPreviewPermits > 0) Semaphore(perPreviewPermits) else null

  /** True when this limiter imposes no bound (`totalPermits <= 0`). */
  public val unbounded: Boolean
    get() = semaphore == null

  /**
   * Try to reserve [weight] permits for a live session. Returns a [Ticket] the caller must
   * [close][Ticket.close] when the session ends, or `null` when the budget is exhausted. A weight
   * larger than the general lane is coerced down to it, so a heavy backend can still run alone.
   */
  public fun acquire(weight: Int, verified: Boolean = true, countRefusal: Boolean = true): Ticket? {
    val sem = semaphore ?: return Ticket(0)
    if (weight <= 0) return Ticket(0)
    // Coerced to the general lane's capacity: the reserve is unavailable here, and asking for more
    // than this semaphore can hold would refuse a heavy backend forever.
    val permits = weight.coerceIn(1, generalPermits)
    if (sem.tryAcquire(permits)) return Ticket(permits)
    // Unverified (unknown session id) refusals are counted apart: on a public box they are noise,
    // on a `--revisions` box they are real demand. Callers that degrade instead of refusing pass
    // countRefusal = false so throttled batches don't read as refused visitors.
    if (countRefusal) {
      if (verified) refusals.incrementAndGet() else unverifiedRefusals.incrementAndGet()
    }
    return null
  }

  /**
   * Reserve [weight] permits for a background holder (a pooled render daemon), leaving at least
   * [reserve] free for an interactive stream. Returns null, without counting a refusal, when that
   * headroom isn't there: background work always has a fallback, a refused stream does not.
   *
   * Takes weight + reserve atomically and hands the reserve straight back, so a race can only make
   * a background holder decline, never over-admit.
   */
  public fun acquireBackground(
    weight: Int,
    reserve: Int = STREAM_RESERVE,
    /**
     * Whether this caller may draw on the per-preview slice. False for the shared prefetch pool,
     * which would otherwise take the one seat a supplement-only preview is guaranteed.
     */
    dedicatedSlice: Boolean = true,
  ): Ticket? {
    val sem = semaphore ?: return Ticket(0)
    if (weight <= 0) return Ticket(0)
    val permits = weight.coerceIn(1, totalPermits)
    // The dedicated slice skips the stream-headroom check: its permits were never available to a
    // stream, so demanding headroom there would make the reserve unusable.
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
   * Permits currently available across both lanes, so the figure stays comparable to [totalPermits]
   * in `/status`.
   */
  public fun availablePermits(): Int =
    semaphore?.let { it.availablePermits() + (perPreviewSemaphore?.availablePermits() ?: 0) }
      ?: Int.MAX_VALUE

  /** Permits free in the per-preview slice alone — lets `/status` show the lane isn't starved. */
  public fun perPreviewPermitsAvailable(): Int =
    perPreviewSemaphore?.availablePermits() ?: if (unbounded) Int.MAX_VALUE else 0

  /**
   * Live sessions refused since startup. A counter rather than a gauge: sampling [availablePermits]
   * almost never catches the moment of pressure.
   */
  public fun refusalCount(): Long = refusals.get()

  /**
   * Refusals for a session id the registry did not have at admission time: either noise, or a
   * valid-but-unbuilt `--revisions` session. Read alongside [refusalCount].
   */
  public fun unverifiedRefusalCount(): Long = unverifiedRefusals.get()

  public companion object {
    /** Permits [acquireBackground] leaves free: the most expensive single stream. */
    public const val STREAM_RESERVE: Int = ServeBundleDaemon.ANDROID_LIVE_SEAT_WEIGHT

    /** Default [perPreviewReserve]: one desktop daemon's worth. */
    public const val DEFAULT_PER_PREVIEW_RESERVE: Int = 1
  }

  private val refusals = AtomicLong()
  private val unverifiedRefusals = AtomicLong()

  /**
   * A held reservation of [permits]; [close] returns them, idempotently, to the pool they came from
   * (returning a [reserved] permit to the general lane would leak the per-preview slice).
   */
  public inner class Ticket
  internal constructor(public val permits: Int, private val reserved: Boolean = false) :
    AutoCloseable {
    private val released = AtomicBoolean(false)

    override fun close() {
      if (permits > 0 && released.compareAndSet(false, true)) {
        if (reserved) perPreviewSemaphore?.release(permits) else semaphore?.release(permits)
      }
    }
  }
}
