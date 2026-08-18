@file:Suppress("RestrictedApiAndroidX")
@file:OptIn(androidx.tracing.DelicateTracingApi::class)

package ee.schimke.composeai.rcplayer.trace

import androidx.tracing.Tracer

/**
 * Desktop/JVM tracer: `androidx.tracing` 2.x's [Tracer.global].
 *
 * `androidx.tracing.Trace.beginSection` — the atrace-shaped API `:daemon:android` uses on Android —
 * is an explicit no-op in the desktop actual of `tracing:2.0.0-rc01`, so it is deliberately *not*
 * what this delegates to. [Tracer] is the API that carries on the JVM: the global tracer is a stub
 * reporting every category disabled until an application installs a driver, and
 * `androidx.tracing:tracing-wire`'s `TraceDriver` turns the same spans into Perfetto `TracePacket`s
 * written to a `.perfetto-trace` file. `:rc-player-profile` is the application that does that here;
 * an embedding host can do the same without this module changing.
 *
 * Nothing in this file installs a tracer. Registering a global tracer is a decision that belongs to
 * the process — `Tracer.setGlobalTracer` is documented as "should never be called by libraries" —
 * so the player only ever reads [Tracer.global].
 */
internal actual object RcTracePlatform {
  actual fun isEnabled(category: String): Boolean = Tracer.global.isCategoryEnabled(category)

  actual fun begin(category: String, name: String): Any? =
    Tracer.global.beginSection(category = category, name = name, token = null) {}

  actual fun end(token: Any) {
    (token as AutoCloseable).close()
  }

  actual fun instant(category: String, name: String) {
    Tracer.global.instant(category = category, name = name)
  }

  actual fun counter(category: String, name: String, value: Long) {
    Tracer.global.counter(category = category, name = name).setValue(value)
  }
}

/**
 * No-op on the JVM: whether spans are recorded is the installed `Tracer`'s call, not the player's.
 * Install (or don't install) an `androidx.tracing.wire.TraceDriver` instead.
 */
public actual fun setRcPlatformTracingEnabled(enabled: Boolean): Unit = Unit
