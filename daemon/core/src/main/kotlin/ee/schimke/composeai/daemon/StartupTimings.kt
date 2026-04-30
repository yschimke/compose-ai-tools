package ee.schimke.composeai.daemon

import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wall-clock timeline of the daemon's interesting boot events.
 *
 * Emit a [mark] at every stage that contributes meaningfully to startup latency. Each mark prints a
 * single stderr line `compose-ai-daemon: [+<elapsedMs>ms] <label>` and is buffered for [summary] so
 * the full timeline is reproducible after the fact. The reference clock is the JVM start time (via
 * [ManagementFactory.getRuntimeMXBean]) so events seen on the worker thread, the JSON-RPC thread,
 * and inside the Robolectric sandbox all line up against one shared origin.
 *
 * The format is human-readable on purpose — operators reading daemon stderr (or the editor's
 * "daemon log" output channel) shouldn't need a JSON parser to see "step 3 took 8 seconds." For
 * machine consumption a future change can wire [marks] into a `daemonTimings` JSON-RPC
 * notification.
 *
 * Reasons to add a mark:
 *
 * - Some external resource is being acquired (Maven download, JIT warm-up).
 * - Some bounded amount of work runs proportional to the project (classpath scan, classloader
 *   instrumentation).
 * - A protocol-visible state changes (`initialize` accepted, sandbox ready, first render done).
 *
 * Reasons NOT to add a mark:
 *
 * - Every queue insertion / dequeue (too noisy; spam dilutes the signal).
 * - Anything inside the steady-state render path (this is a *startup* timeline; render-time metrics
 *   already flow through `renderFinished.metrics`).
 *
 * See [docs/daemon/STARTUP.md](../../../../../../docs/daemon/STARTUP.md) for the analysis of where
 * the time actually goes and the menu of options to attack each stage.
 */
object StartupTimings {

  data class Mark(val elapsedMs: Long, val label: String, val thread: String)

  /**
   * JVM start instant via [java.lang.management.RuntimeMXBean.getStartTime]. Cheaper to read than
   * `ProcessHandle.current().info().startInstant()` and consistent across threads.
   */
  val jvmStartMs: Long = ManagementFactory.getRuntimeMXBean().startTime

  /** Sysprop knob to suppress stderr emission. Marks are still buffered for [summary]. */
  const val QUIET_PROP: String = "composeai.daemon.startupQuiet"

  private val quiet: Boolean
    get() = System.getProperty(QUIET_PROP) == "true"

  private val buffer = ConcurrentLinkedQueue<Mark>()
  private val summarised = AtomicBoolean(false)

  fun elapsedMs(): Long = System.currentTimeMillis() - jvmStartMs

  /**
   * Record a labelled instant on the timeline. Thread-safe. Emits one stderr line unless
   * [QUIET_PROP] is set; always retained for [summary].
   */
  fun mark(label: String) {
    val mark = Mark(elapsedMs(), label, Thread.currentThread().name)
    buffer.add(mark)
    if (!quiet) {
      System.err.println("compose-ai-daemon: [+${mark.elapsedMs}ms] ${mark.label}")
    }
  }

  /** All marks recorded so far, in insertion order. */
  fun marks(): List<Mark> = buffer.toList()

  /**
   * Emit a multi-line summary of the timeline. Idempotent — first caller wins; subsequent calls are
   * no-ops to avoid duplicate output when multiple paths converge on "I'm warm now."
   */
  fun summary() {
    if (!summarised.compareAndSet(false, true)) return
    if (quiet) return
    val ms = marks()
    val width = ms.maxOfOrNull { "${it.elapsedMs}".length } ?: 0
    System.err.println("compose-ai-daemon: startup timeline:")
    var prev = 0L
    for (m in ms) {
      val delta = m.elapsedMs - prev
      val labelCol = "${m.elapsedMs}".padStart(width)
      val deltaCol = "+${delta}ms".padStart(8)
      System.err.println("  [${labelCol}ms] ${deltaCol}  ${m.label}  (${m.thread})")
      prev = m.elapsedMs
    }
  }

  /**
   * Test seam — drop the buffered marks. Production code never needs this; tests that exercise
   * multiple [JsonRpcServer] runs in one JVM use it to keep timelines comparable.
   */
  internal fun reset() {
    buffer.clear()
    summarised.set(false)
  }
}
