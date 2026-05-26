package ee.schimke.composeai.daemon

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * "Run incremental discovery for this source path, but defer it until after the next render
 * finishes or a watchdog window elapses." Used by [JsonRpcServer]'s `fileChanged({kind:source})`
 * handler so a save NEVER surfaces a `discoveryUpdated` metadata event before the corresponding
 * render notification — renders are what the user actually looks at; the metadata reconcile is a
 * quiet background pass that only paints the panel when the preview set actually drifted.
 *
 * Two drain triggers race the queue:
 *
 * 1. [JsonRpcServer.emitRenderFinished] calls [drain] after the render notification has flushed, so
 *    the metadata event lands in order behind it.
 * 2. A per-enqueue watchdog calls [drain] after [watchdogMs], so a save against a file with no
 *    focused previews (no `renderNow` follows) still eventually runs the cascade.
 *
 * Both paths poll the same atomic queue; whichever wins runs the scan, the loser sees an empty
 * queue and returns. The dedup is correctness-safe under contention; identity-dedup of enqueued
 * paths isn't worth it (two saves of the same file scan twice, but each scan is scoped and the
 * daemon is bounded by editor cadence).
 */
internal class DeferredDiscoveryQueue(
  private val watchdogMs: Long,
  private val runForPath: (String) -> Unit,
  /**
   * Schedules [action] to run after [delayMs] milliseconds. The default uses a fresh daemon thread
   * per enqueue, matching the prior inline implementation. Tests inject a synchronous scheduler so
   * [enqueue] / [drain] interleavings can be asserted without real wall-clock waits.
   */
  private val watchdogScheduler: (delayMs: Long, action: () -> Unit) -> Unit =
    ::defaultWatchdogScheduler,
) {
  private val pending = ConcurrentLinkedQueue<String>()

  /** Queue [path] for a deferred discovery run and start the watchdog timer. */
  fun enqueue(path: String) {
    pending.add(path)
    watchdogScheduler(watchdogMs) { drain() }
  }

  /**
   * Drain every pending path through [runForPath]. Safe to call from multiple threads; each path is
   * polled atomically so it runs at most once per enqueue.
   */
  fun drain() {
    while (true) {
      val path = pending.poll() ?: return
      runForPath(path)
    }
  }

  /** Test/observability hook — current queue depth. */
  fun pendingCount(): Int = pending.size
}

private fun defaultWatchdogScheduler(delayMs: Long, action: () -> Unit) {
  Thread(
      {
        try {
          Thread.sleep(delayMs)
        } catch (_: InterruptedException) {
          Thread.currentThread().interrupt()
          return@Thread
        }
        action()
      },
      "compose-ai-daemon-discovery-watchdog",
    )
    .apply { isDaemon = true }
    .start()
}
