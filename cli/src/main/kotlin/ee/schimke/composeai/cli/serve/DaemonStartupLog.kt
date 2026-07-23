package ee.schimke.composeai.cli.serve

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A small, thread-safe ring buffer of **recent daemon startup failures** — the render/live daemon a
 * session tried to (re)open but couldn't. Every registry-driven daemon (re)launch funnels through
 * [ServeCommand.openHost], whose failure was previously swallowed to a silent `null`; recording the
 * reason here gives the `/status` page (and its machine-readable `/status.json`, e.g. for a Home
 * Assistant sensor) a durable, bounded view of what has been going wrong without scraping stderr.
 *
 * Bounded on purpose: only the most recent [capacity] failures are kept, so a persistently-broken
 * catalog can't grow this without limit. Newest-first ([recent]).
 */
class DaemonStartupLog(
  private val capacity: Int = DEFAULT_CAPACITY,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  /** One recorded failure: when it happened, which session, and a one-line reason. */
  data class Failure(val atEpochMillis: Long, val session: String, val reason: String)

  private val lock = ReentrantLock()
  // Oldest at the head, newest at the tail; capped at [capacity] (drop oldest on overflow).
  private val entries = ArrayDeque<Failure>()

  /**
   * Record that [session]'s daemon failed to start, with [reason] (a throwable message or a
   * pre-launch diagnostic). The reason is collapsed to its first non-blank line and length-capped
   * so a stack-trace-y message stays a single readable row on the status page.
   */
  fun record(session: String, reason: String?) {
    val at = clock()
    val oneLine =
      reason
        ?.lineSequence()
        ?.map { it.trim() }
        ?.firstOrNull { it.isNotEmpty() }
        ?.let { if (it.length > MAX_REASON_CHARS) it.take(MAX_REASON_CHARS) + "…" else it }
        ?: "unknown error"
    lock.withLock {
      entries.addLast(Failure(at, session, oneLine))
      while (entries.size > capacity) entries.removeFirst()
    }
  }

  /** The recorded failures, **newest first**. A snapshot copy — safe to iterate off-lock. */
  fun recent(): List<Failure> = lock.withLock { entries.toList().asReversed() }

  /** True when nothing has failed (yet) — the healthy case. */
  fun isEmpty(): Boolean = lock.withLock { entries.isEmpty() }

  companion object {
    /** How many recent failures to retain. */
    const val DEFAULT_CAPACITY = 20
    private const val MAX_REASON_CHARS = 500
  }
}
