package ee.schimke.composeai.daemon

import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure

/**
 * Platform-safe factory for [androidx.compose.ui.graphics.PathMeasure].
 *
 * This module compiles against Compose Multiplatform's `compose.ui`, which resolves to the
 * **desktop/skiko** variant on this JVM-module compile classpath. `PathMeasure()` is a
 * multiplatform `expect` *top-level function*, so the compiler bakes the call site to the skiko
 * actual's file facade — `SkiaBackedPathMeasure_skikoKt.PathMeasure()`. That class does not exist
 * on Android, and the connector runs on Android too (the Robolectric renderer is the whole Wear
 * lane), so every such call threw `NoClassDefFoundError` there.
 *
 * Both call sites wrap their sampling in `runCatching`, so the throw never surfaced: it silently
 * degraded. A shape whose outline no corner path could describe — a `MaterialShapes` star, a morph,
 * a squircle — resolved no `shapePath` and fell through to a plain `<rect>`, drawn confidently over
 * the correctly-shaped pixels beneath it. That is the same failure mode issue #3254 fixed for the
 * *resolution* order; this is the runtime half of it, and it made that fix unreachable on Android.
 *
 * Only *construction* needs the indirection. `PathMeasure` itself is a common interface with the
 * same shape on both platforms, so once built the ordinary member calls link fine — which is why
 * this resolves the platform file facade reflectively rather than duplicating the sampling logic.
 *
 * [ComposeInternalFieldContractTest] guards the names against a Compose bump.
 */
internal object PlatformPathMeasure {

  /** File facades holding the `PathMeasure()` actual, in the order they are probed. */
  private val FACADES =
    listOf(
      "androidx.compose.ui.graphics.AndroidPathMeasure_androidKt",
      "androidx.compose.ui.graphics.SkiaBackedPathMeasure_skikoKt",
    )

  private val factory: java.lang.reflect.Method? by lazy {
    FACADES.firstNotNullOfOrNull { name ->
      runCatching { Class.forName(name).getMethod("PathMeasure") }.getOrNull()
    }
  }

  /** A fresh measure, or null when neither platform actual is on the runtime classpath. */
  fun create(): PathMeasure? = runCatching { factory?.invoke(null) as? PathMeasure }.getOrNull()

  /**
   * A measure already bound to [path], or null when no platform actual is available. [forceClosed]
   * matches [PathMeasure.setPath].
   */
  fun of(path: Path, forceClosed: Boolean = false): PathMeasure? =
    create()?.apply { setPath(path, forceClosed) }
}
