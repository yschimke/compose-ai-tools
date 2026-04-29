package ee.schimke.composeai.daemon

import com.sun.management.OperatingSystemMXBean
import ee.schimke.composeai.daemon.protocol.RenderMetrics
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-host sandbox-lifecycle counters — feed [RenderMetrics.sandboxAgeRenders] /
 * [RenderMetrics.sandboxAgeMs] for B2.3.
 *
 * Constructed once per host lifetime ([RobolectricHost], [DesktopHost],
 * [ee.schimke.composeai.daemon.harness.FakeHost], etc.). [bumpRenderCount] is called by the engine
 * (via [SandboxMeasurement.collect]) once per render-completion. Sandbox recycle (B2.5, not yet
 * landed) will reset both counters via [reset]; until then `sandboxAgeRenders` keeps growing for
 * the whole host lifetime — documented behaviour for B2.3 v1.
 *
 * **Thread-safety.** [renderCount] is an [AtomicLong] so concurrent renders increment safely. The
 * desktop / Android backends serialise renders to a single thread, so contention is rare in
 * practice; the [AtomicLong] is cheap insurance for any future host that submits concurrently.
 */
class SandboxLifecycleStats(
  /** Host-construction time in nanoseconds. Defaults to [System.nanoTime] at instantiation. */
  startNs: Long = System.nanoTime()
) {
  @Volatile private var startNs: Long = startNs

  private val renderCount: AtomicLong = AtomicLong(0)

  /** Wall-clock since host construction, in milliseconds. */
  fun ageMs(): Long = (System.nanoTime() - startNs) / 1_000_000L

  /** Number of renders completed against this host so far. */
  fun renders(): Long = renderCount.get()

  /** Bumps the render counter. Called from the engine after each render-body returns. */
  fun bumpRenderCount(): Long = renderCount.incrementAndGet()

  /**
   * Resets both counters — wired from B2.5's recycle path once that lands. For B2.3 v1 nobody
   * calls this in production; only unit tests use it.
   */
  fun reset() {
    startNs = System.nanoTime()
    renderCount.set(0)
  }
}

/**
 * Per-render measurement helper — collects the four B2.3 metrics into a flat
 * `Map<String, Long>` carrier so the renderer-agnostic [RenderResult.metrics] stays a free-form
 * `Map<String, Long>?` (per the B2.3 brief: "translate flat map → structured `RenderMetrics` in
 * `JsonRpcServer`"). The engine calls [collect] once per render-completion.
 *
 * Measurement cost target: < 10ms per render (B2.3 DoD). Composed of:
 * - One `System.gc()` hint (HotSpot mostly honours this for instrumentation; not load-bearing,
 *   we just want post-render heap to reflect short-lived allocation).
 * - Three `MemoryMXBean` / `Runtime` accessor calls (each O(1)).
 * - One [SandboxLifecycleStats] read + bump.
 *
 * **`nativeHeapMb` is an approximation** — we report the JVM's committed virtual memory size
 * (via [com.sun.management.OperatingSystemMXBean.getCommittedVirtualMemorySize]), divided by 1MB.
 * That is *not* strictly the "native heap" — it covers JVM heap + native libs + mapped files —
 * but it's the closest portable proxy on HotSpot. The daemon's heaviest native-side state is Skia
 * (desktop) and Robolectric's native libs (Android), both of which show up under committed
 * virtual memory. On non-HotSpot JVMs where the cast fails we fall back to 0 with a one-time
 * warn-log; clients see `nativeHeapMb = 0` and that's clearer than a hand-waved heuristic.
 */
object SandboxMeasurement {

  private val nonHotSpotWarned = AtomicBoolean(false)

  /**
   * Runs measurement and returns a flat `Map<String, Long>` with the four B2.3 keys plus the
   * pre-existing `tookMs` (passed in by the caller — the engine has already stopped its render
   * timer by the time we get called).
   *
   * The map's keys are pinned constants on [RenderMetrics.Companion] so all consumers
   * (`JsonRpcServer.renderFinishedFromResult`, the soak tests, etc.) agree on the spelling.
   */
  fun collect(stats: SandboxLifecycleStats, tookMs: Long): Map<String, Long> {
    // Run a single GC hint after the render body so post-render heap reflects what survived this
    // render's short-lived allocations. Yes, `System.gc()` is a hint; HotSpot mostly honours it
    // for instrumentation. We don't over-engineer.
    System.gc()

    val r = Runtime.getRuntime()
    val heapAfterGcMb = ((r.totalMemory() - r.freeMemory()) / (1024L * 1024L))

    val nativeHeapMb: Long =
      try {
        val osBean = ManagementFactory.getOperatingSystemMXBean()
        if (osBean is OperatingSystemMXBean) {
          osBean.committedVirtualMemorySize / (1024L * 1024L)
        } else {
          // Non-HotSpot JVM (e.g. some IBM J9 builds). Documented fallback.
          if (nonHotSpotWarned.compareAndSet(false, true)) {
            System.err.println(
              "compose-ai-daemon: SandboxMeasurement: OperatingSystemMXBean is not " +
                "com.sun.management.OperatingSystemMXBean (got ${osBean.javaClass.name}); " +
                "reporting nativeHeapMb=0 for the rest of this JVM"
            )
          }
          0L
        }
      } catch (t: Throwable) {
        if (nonHotSpotWarned.compareAndSet(false, true)) {
          System.err.println(
            "compose-ai-daemon: SandboxMeasurement: failed to read committedVirtualMemorySize " +
              "(${t.javaClass.simpleName}: ${t.message}); reporting nativeHeapMb=0 for the rest " +
              "of this JVM"
          )
        }
        0L
      }

    val sandboxAgeRenders = stats.bumpRenderCount()
    val sandboxAgeMs = stats.ageMs()

    return mapOf(
      "tookMs" to tookMs,
      RenderMetrics.KEY_HEAP_AFTER_GC_MB to heapAfterGcMb,
      RenderMetrics.KEY_NATIVE_HEAP_MB to nativeHeapMb,
      RenderMetrics.KEY_SANDBOX_AGE_RENDERS to sandboxAgeRenders,
      RenderMetrics.KEY_SANDBOX_AGE_MS to sandboxAgeMs,
    )
  }
}
