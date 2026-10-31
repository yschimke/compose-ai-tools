package ee.schimke.composeai.cli

import java.util.concurrent.TimeUnit

/**
 * Notices that the process which started this one has gone, for children that would otherwise run
 * on under PID 1: `build-host --stdio` outlived the preview server that spawned it
 * (yschimke/compose-ai-tools#6364).
 *
 * The parent is captured once, at [start]. It counts as gone when that process is no longer alive
 * (a [ProcessHandle] compares start times, so a reused PID does not read as alive) or when this
 * process has been reparented — to PID 1, launchd, or a subreaper — which happens the moment the
 * parent exits, before anything reaps it.
 */
internal object ParentWatch {

  /** True when [original] was a real parent and it is gone, or [current] is no longer it. */
  fun parentGone(original: ProcessHandle?, current: ProcessHandle?): Boolean {
    if (original == null) return false
    return !original.isAlive || current?.pid() != original.pid()
  }

  /**
   * Poll every [interval] [unit] on a daemon thread and call [onGone] once when the parent is gone.
   * Returns the thread, or null when this process has no parent to watch.
   */
  fun start(interval: Long = 2, unit: TimeUnit = TimeUnit.SECONDS, onGone: () -> Unit): Thread? {
    val self = ProcessHandle.current()
    val original = self.parent().orElse(null) ?: return null
    val thread = Thread {
      try {
        while (!parentGone(original, self.parent().orElse(null))) {
          unit.sleep(interval)
        }
        onGone()
      } catch (_: InterruptedException) {
        // Stopped on purpose.
      }
    }
    thread.name = "compose-preview-parent-watch"
    thread.isDaemon = true
    thread.start()
    return thread
  }
}
