package ee.schimke.composeai.renderer

import androidx.compose.runtime.ProvidableCompositionLocal
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves Wear Compose's `LocalReduceMotion` by reflection so the renderer can honour
 * `@ScrollingPreview(..., reduceMotion = true)` without taking a compile-time dependency on
 * `androidx.wear.compose:compose-foundation`.
 *
 * Wear Compose Foundation 1.5+ exposes it as:
 * ```
 * package androidx.wear.compose.foundation
 * val LocalReduceMotion: ProvidableCompositionLocal<Boolean>
 * ```
 *
 * The backing JVM member is `CompositionLocalsKt.getLocalReduceMotion()`. When the consumer module
 * isn't a Wear module (class not on the classpath being searched) the lookup returns `null` and the
 * caller skips the provider — reduceMotion becomes a no-op, which is harmless for non-Wear
 * scrollables where `TransformingLazyColumn`-style edge scaling doesn't apply.
 *
 * **Classloader.** The standalone renderer runs the consumer's Wear classes on its own JUnit JVM
 * classpath, so the no-arg [get] (which searches this class's own loader) resolves them. The
 * daemon, though, loads the user's app — and its `wear-compose` — on a **child** classloader, not
 * the daemon's own; [get] with that loader searches the right classpath. Results are cached per
 * loader so the reflection cost amortises across every preview in a shard.
 */
object WearReduceMotionLocal {
  private val cache = ConcurrentHashMap<ClassLoader, Holder>()

  /** Boxed nullable so a resolved-to-absent lookup is cached too, not re-attempted every call. */
  private class Holder(val local: ProvidableCompositionLocal<Boolean>?)

  /** Resolve against this class's own loader — the standalone renderer's JUnit classpath. */
  fun get(): ProvidableCompositionLocal<Boolean>? = get(WearReduceMotionLocal::class.java.classLoader)

  /**
   * Resolve `LocalReduceMotion` from [loader] — pass the child (app) classloader in the daemon,
   * where the user's `wear-compose` isn't on the daemon's own loader. Null [loader] falls back to
   * the system loader. Returns null when Wear Compose Foundation isn't reachable from [loader].
   */
  fun get(loader: ClassLoader?): ProvidableCompositionLocal<Boolean>? {
    val effective = loader ?: ClassLoader.getSystemClassLoader() ?: return null
    return cache.getOrPut(effective) { Holder(resolve(effective)) }.local
  }

  @Suppress("UNCHECKED_CAST")
  private fun resolve(loader: ClassLoader): ProvidableCompositionLocal<Boolean>? =
    runCatching {
        val clazz =
          Class.forName("androidx.wear.compose.foundation.CompositionLocalsKt", false, loader)
        val method = clazz.getDeclaredMethod("getLocalReduceMotion")
        method.invoke(null) as ProvidableCompositionLocal<Boolean>
      }
      .getOrNull()
}
