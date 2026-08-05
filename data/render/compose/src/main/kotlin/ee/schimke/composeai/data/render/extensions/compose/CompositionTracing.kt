@file:OptIn(androidx.compose.runtime.InternalComposeTracingApi::class)

package ee.schimke.composeai.data.render.extensions.compose

import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer

/**
 * Composable-level composition tracing, folded into the render trace.
 *
 * Where the engines' own `trace.section(...)` calls answer "how long did `setContent` take", this
 * answers "and where inside it did the time go" — one span per composable, named with the source
 * information the Compose compiler emits (`com.example.MyScreen (MyScreen.kt:42)`).
 *
 * ## Why not `androidx.compose.runtime:runtime-tracing`
 *
 * That artifact is the obvious candidate and is deliberately unused. It is an **Android-only AAR**,
 * and all it contains is a one-class `androidx.startup` initializer that installs a
 * [CompositionTracer] forwarding to `PerfettoSdkTrace`. The actual mechanism — [Composer.setTracer]
 * and the [CompositionTracer] interface — lives in `androidx.compose.runtime`, which every backend
 * already has. Binding it to the render recorder instead of to Perfetto's Android SDK is what makes
 * the same feature work on Compose Desktop, and puts the spans in the structured `render/trace`
 * payload rather than only in a system trace.
 *
 * ## Cost, and why this is opt-in
 *
 * The compiler emits a `traceEventStart`/`traceEventEnd` pair around **every** composable, so even
 * a trivial preview produces tens of spans and a real screen produces thousands — against roughly a
 * dozen for the engine's own phases. That is the point when you are asking this question and pure
 * noise when you are not, so nothing installs it by default. While it is off, the
 * compiler-generated call sites short-circuit on `isTraceInProgress()` before building their `info`
 * string.
 *
 * The recorder's retention cap keeps a pathological composition from growing without bound, and its
 * per-name aggregates accumulate independently of that cap — so even a capped composition trace
 * still reports honest per-composable totals.
 *
 * ## Global, and therefore serialized
 *
 * [Composer.setTracer] is process-global with no getter, so [record] installs, runs, and clears; it
 * cannot restore a tracer someone else installed. Renders are serialized per host, so in practice
 * there is one at a time — a caller rendering concurrently would interleave two compositions into
 * one recorder and should not enable this.
 */
object CompositionTracing {
  /** Category for composition spans, kept distinct from the engine's own phase category. */
  const val CATEGORY: String = "compose.composition"

  /** System property gate. Off by default — see the cost note. */
  const val ENABLED_PROP: String = "composeai.daemon.compositionTrace"

  fun enabled(): Boolean = System.getProperty(ENABLED_PROP) == "true"

  /**
   * Run [block] with composition spans routed to [beginSection] / [endSection].
   *
   * Takes the two callbacks rather than a recorder so this module stays independent of which
   * recorder is on the other end — the daemon engines hold the connector's wrapper type, not the
   * core one, and neither is visible from here.
   *
   * Nested composables nest as spans: the compiler's start/end pairs are properly nested and the
   * recorder tracks depth on its own section stack.
   */
  fun <T> record(
    beginSection: (name: String, category: String) -> Unit,
    endSection: () -> Unit,
    block: () -> T,
  ): T {
    var open = 0
    Composer.setTracer(
      object : CompositionTracer {
        override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
          open += 1
          beginSection(info, CATEGORY)
        }

        override fun traceEventEnd() {
          if (open > 0) {
            open -= 1
            endSection()
          }
        }

        override fun isTraceInProgress(): Boolean = true
      }
    )
    try {
      return block()
    } finally {
      // Close anything a throwing composable left open before handing the recorder back, so the
      // next engine phase starts at the depth it expects rather than nested under a dead frame.
      while (open > 0) {
        open -= 1
        endSection()
      }
      Composer.setTracer(null)
    }
  }
}
