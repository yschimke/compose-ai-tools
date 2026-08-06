package ee.schimke.composeai.rcplayer.trace

/**
 * Apple targets have no platform tracer wired up.
 *
 * `androidx.tracing:tracing:2.0.0-rc01` publishes no Apple klib, and the native equivalent —
 * `os_signpost` — is a C macro over `_os_signpost_emit_with_name_impl`, so reaching it needs a
 * cinterop `.def` and a build that only produces artifacts on a Mac. That is a real feature, not a
 * line of code, and it is not on the path to the profiling numbers this instrumentation exists for.
 *
 * The span names still reach [RcTrace.recorder], which is common code, so an iOS host can collect
 * and report exactly the same timings as desktop and wasm — it just does not get an Instruments
 * timeline for free.
 */
internal actual object RcTracePlatform {
  actual fun isEnabled(category: String): Boolean = false

  actual fun begin(category: String, name: String): Any? = null

  actual fun end(token: Any): Unit = Unit

  actual fun instant(category: String, name: String): Unit = Unit

  actual fun counter(category: String, name: String, value: Long): Unit = Unit
}

/** No-op: there is no platform tracer on Apple targets to switch. See [RcTracePlatform]. */
public actual fun setRcPlatformTracingEnabled(enabled: Boolean): Unit = Unit
