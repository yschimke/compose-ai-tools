package ee.schimke.composeai.rcplayer.trace

/**
 * The per-target tracer the player writes to.
 *
 * This is the seam that exists because `androidx.tracing:tracing:2.x` — a Kotlin Multiplatform
 * library — publishes only `androidJvm` and `jvm` variants. `commonMain` therefore cannot reference
 * it, and each target supplies whatever its platform actually offers:
 * - **desktop / JVM** — `androidx.tracing.Tracer.global`. A stub until an application installs a
 *   driver, at which point spans become Perfetto `TracePacket`s (see `:rc-player-profile`).
 * - **wasmJs** — the browser's User Timing API (`performance.mark` / `performance.measure`), so the
 *   same span names show up on the DevTools performance timeline. Off unless the host turns it on.
 * - **iOS** — a no-op. `os_signpost` would need a cinterop def and an Apple-only build; the
 *   [RcTraceRecorder] path still works there, which is what the summary numbers come from.
 *
 * [isEnabled] must be cheap: it is called once per candidate span on the disabled path.
 */
internal expect object RcTracePlatform {
  fun isEnabled(category: String): Boolean

  /** Open a span and return a token to hand back to [end], or `null` if nothing was opened. */
  fun begin(category: String, name: String): Any?

  fun end(token: Any)

  fun instant(category: String, name: String)

  fun counter(category: String, name: String, value: Long)
}

/**
 * Turn the platform tracer on or off, where the target has a switch the player owns.
 *
 * Only wasmJs does: the browser's User Timing buffer is shared with the page, so the player leaves
 * marks off until the host asks for them. On desktop the answer belongs to `androidx.tracing` — a
 * process installs a `TraceDriver` (or does not) and the driver's `isCategoryEnabled` decides — and
 * on Apple targets there is no platform tracer to switch, so both are no-ops. [RcTrace.recorder] is
 * the switch that works identically everywhere.
 */
public expect fun setRcPlatformTracingEnabled(enabled: Boolean)
