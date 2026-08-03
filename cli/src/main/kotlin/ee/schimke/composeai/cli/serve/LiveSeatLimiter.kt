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
class LiveSeatLimiter(val totalPermits: Int) {
  private val semaphore: Semaphore? = if (totalPermits > 0) Semaphore(totalPermits) else null

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
  fun acquire(weight: Int, countRefusal: Boolean = true): Ticket? {
    val sem = semaphore ?: return Ticket(0)
    if (weight <= 0) return Ticket(0)
    val permits = weight.coerceIn(1, totalPermits)
    if (sem.tryAcquire(permits)) return Ticket(permits)
    // [countRefusal] is false for an attempt that was never going to be served anyway — a request
    // naming a session this box doesn't have. Seats are reserved before the session is leased (see
    // the stream lane), so such requests do reach the budget; letting them move [refusalCount]
    // would mean anyone could manufacture the evidence a capacity decision rests on.
    if (countRefusal) refusals.incrementAndGet()
    return null
  }

  /** Permits currently available — for tests/diagnostics. */
  fun availablePermits(): Int = semaphore?.availablePermits() ?: Int.MAX_VALUE

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

  private val refusals = AtomicLong()

  /** A held reservation of [permits] live-seat permits; [close] returns them (idempotent). */
  inner class Ticket internal constructor(val permits: Int) : AutoCloseable {
    private val released = AtomicBoolean(false)

    override fun close() {
      if (permits > 0 && released.compareAndSet(false, true)) semaphore?.release(permits)
    }
  }
}
