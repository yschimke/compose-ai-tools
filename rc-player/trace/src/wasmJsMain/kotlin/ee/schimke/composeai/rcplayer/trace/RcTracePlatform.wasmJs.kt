@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package ee.schimke.composeai.rcplayer.trace

/**
 * Browser tracer: the
 * [User Timing API][https://developer.mozilla.org/docs/Web/API/Performance_API].
 *
 * `androidx.tracing:tracing:2.0.0-rc01` publishes no wasmJs klib, so this target cannot delegate to
 * it. `performance.mark` / `performance.measure` is the closest equivalent the platform actually
 * has, and it is the *right* equivalent: the entries it produces show up on the DevTools
 * performance timeline under the same names the desktop player writes into Perfetto, so a span
 * learned in one place is recognisable in the other.
 *
 * Off until [setRcPlatformTracingEnabled] turns it on. The wasm host wires that to a `?rcTrace=1`
 * query parameter (see `:rc-player-wasm`'s `Main.kt`) rather than leaving marks accumulating in
 * every page load — the browser's performance buffer is finite and shared with the page's own
 * instrumentation.
 */
internal actual object RcTracePlatform {
  internal var enabled: Boolean = false
  private var sequence: Int = 0

  actual fun isEnabled(category: String): Boolean = enabled

  actual fun begin(category: String, name: String): Any? {
    // The mark name has to be unique per open span: `performance.measure` resolves a start mark by
    // name and would otherwise pair a nested span's end with an outer span's start.
    val markName = "$name#${sequence++}"
    performanceMark(markName)
    return WasmSpan(category = category, name = name, startMark = markName)
  }

  actual fun end(token: Any) {
    val span = token as WasmSpan
    performanceMeasure("${span.category}/${span.name}", span.startMark)
    performanceClearMarks(span.startMark)
  }

  actual fun instant(category: String, name: String) {
    performanceMark("$category/$name")
  }

  actual fun counter(category: String, name: String, value: Long) {
    // User Timing has no counter track. A named mark per sample is the closest analogue, and it
    // keeps the value visible on the same timeline as the spans.
    performanceMark("$category/$name=$value")
  }

  private class WasmSpan(val category: String, val name: String, val startMark: String)
}

public actual fun setRcPlatformTracingEnabled(enabled: Boolean) {
  RcTracePlatform.enabled = enabled
}

private fun performanceMark(name: String): Unit =
  js("{ try { performance.mark(name); } catch (e) {} }")

private fun performanceMeasure(name: String, startMark: String): Unit =
  js("{ try { performance.measure(name, startMark); } catch (e) {} }")

private fun performanceClearMarks(name: String): Unit =
  js("{ try { performance.clearMarks(name); } catch (e) {} }")
