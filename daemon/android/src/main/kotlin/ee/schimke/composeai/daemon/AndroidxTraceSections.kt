package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.render.PerfettoTraceDataProducer

/**
 * `androidx.tracing`-backed [PerfettoTraceDataProducer.TraceSectionBackend] — mirrors every
 * render-phase span the daemon already times (`classloader:loadPreviewClass`,
 * `compose:setContent`, `compose:advanceClock`, `render:captureRoboImage`, `dataArtifact:*`, …)
 * onto `android.os.Trace` sections, so those phases line up with the framework's and Compose's own
 * sections in an atrace-level capture instead of living only in the daemon's private
 * chrome-trace JSON.
 *
 * **Opt-in** via `-Dcomposeai.daemon.atrace=true` (profiling runs only). Robolectric routes
 * `android.os.Trace` through `ShadowTrace`, which accumulates section history in static state
 * with no test-lifecycle resets in a long-lived daemon — an always-on default would be a slow
 * leak, so the backend installs only when asked for.
 *
 * Capture recipes once enabled:
 * - Robolectric's own reporting (`PerfStatsCollector`, `ShadowTrace`) picks the sections up
 *   directly inside the sandbox.
 * - With `androidx.compose.runtime:runtime-tracing` on the consumer classpath (the daemon already
 *   detects it — see `TraceMetadata.composeRuntimeTracingOnClasspath`), recomposition spans and
 *   these phase spans share one timeline.
 * - A wall-clock profiler (async-profiler/JFR per the cold-render handover §4) can be correlated
 *   against the same section names via the `compose-ai-daemon` stderr markers.
 *
 * Must only be installed from **inside the sandbox** (see [installIfEnabled]'s call site in
 * `RobolectricHost.SandboxRunner`): on the host side of the classloader boundary
 * `android.os.Trace` is the android.jar stub and every call throws. Both entry points are
 * additionally try/caught by the callers ([PerfettoTraceDataProducer.Recorder.section] and
 * [traced]) so a tracer failure can never fail a render.
 */
internal object AndroidxTraceSections : PerfettoTraceDataProducer.TraceSectionBackend {

  /** Sysprop turning the atrace mirror on. Default off — see the class KDoc for why. */
  const val ENABLED_PROP: String = "composeai.daemon.atrace"

  /** `android.os.Trace` truncates section names beyond this; cut cleanly instead. */
  private const val MAX_SECTION_NAME_LENGTH = 127

  fun enabled(): Boolean = System.getProperty(ENABLED_PROP) == "true"

  /**
   * Registers this backend on [PerfettoTraceDataProducer.sectionBackend] when [enabled]. Idempotent
   * — called from the sandbox-side engine init so the registration lands on the sandbox
   * classloader's copy of the producer (the same copy the render-path [Recorder]s read).
   */
  fun installIfEnabled() {
    if (enabled()) PerfettoTraceDataProducer.sectionBackend = this
  }

  override fun begin(name: String) {
    androidx.tracing.Trace.beginSection(name.take(MAX_SECTION_NAME_LENGTH))
  }

  override fun end() {
    androidx.tracing.Trace.endSection()
  }

  /**
   * Failsafe section wrapper for sandbox-side call sites outside the [Recorder] flow (e.g. the
   * whole-render span in `SandboxRunner.dispatchRender`). No-op when [enabled] is false.
   */
  fun <T> traced(name: String, block: () -> T): T {
    if (!enabled()) return block()
    val began =
      try {
        begin(name)
        true
      } catch (_: Throwable) {
        false
      }
    try {
      return block()
    } finally {
      if (began) {
        try {
          end()
        } catch (_: Throwable) {}
      }
    }
  }
}
