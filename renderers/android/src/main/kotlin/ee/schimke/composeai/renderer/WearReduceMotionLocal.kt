package ee.schimke.composeai.renderer

import androidx.compose.runtime.ProvidableCompositionLocal

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
 * the daemon's own; [get] with that loader searches the right classpath.
 *
 * **Caching.** Only the own-loader result is memoised (a stable, process-lifetime loader). Child
 * app classloaders are **disposable** — the daemon swaps them on every source edit
 * (`UserClassLoaderHolder.swap()`) — so they are deliberately NOT cached: a map keyed on them would
 * pin each swapped loader (and all its classes) for the daemon's lifetime, and a weak-key map
 * wouldn't help because the cached value (a Wear `CompositionLocal` instance loaded *by* that
 * loader) transitively strong-references the key. Resolving fresh for a child loader is a handful of
 * reflective calls per `figma-svg-long` export, not per frame, so the cost is negligible.
 */
object WearReduceMotionLocal {
  private val own: ClassLoader? = WearReduceMotionLocal::class.java.classLoader

  /** Boxed nullable so a resolved-to-absent own-loader lookup is memoised too, not re-attempted. */
  private class Holder(val local: ProvidableCompositionLocal<Boolean>?)

  private val ownResult: Holder by lazy { Holder(own?.let(::resolve)) }

  /** Resolve against this class's own loader — the standalone renderer's JUnit classpath. */
  fun get(): ProvidableCompositionLocal<Boolean>? = ownResult.local

  /**
   * Resolve `LocalReduceMotion` from [loader] — pass the child (app) classloader in the daemon,
   * where the user's `wear-compose` isn't on the daemon's own loader. Null [loader] falls back to
   * the own loader. Returns null when Wear Compose Foundation isn't reachable from [loader].
   */
  fun get(loader: ClassLoader?): ProvidableCompositionLocal<Boolean>? {
    // Own loader (or null → own): use the memoised result. A distinct child loader is disposable, so
    // resolve fresh rather than caching it (see the class doc's Caching note).
    if (loader == null || loader === own) return ownResult.local
    return resolve(loader)
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
