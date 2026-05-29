package ee.schimke.composeai.renderer

import androidx.compose.runtime.ProvidableCompositionLocal

/**
 * Resolves Wear Compose's `LocalReduceMotion` by reflection so the renderer can
 * honour `@ScrollingPreview(..., reduceMotion = true)` without taking a
 * compile-time dependency on `androidx.wear.compose:compose-foundation`.
 *
 * Wear Compose Foundation 1.5+ exposes it as:
 *
 * ```
 * package androidx.wear.compose.foundation
 * val LocalReduceMotion: ProvidableCompositionLocal<Boolean>
 * ```
 *
 * The backing JVM member is `CompositionLocalsKt.getLocalReduceMotion()`.
 * When the consumer module isn't a Wear module (class not on the test JVM
 * classpath) the lookup returns `null` and the caller skips the provider —
 * reduceMotion becomes a no-op, which is harmless for non-Wear scrollables
 * where `TransformingLazyColumn`-style edge scaling doesn't apply.
 *
 * Cached after the first call: reflection lookup cost amortises across every
 * preview in the shard.
 */
internal object WearReduceMotionLocal {
    private val cached: ProvidableCompositionLocal<Boolean>? by lazy { resolve() }

    fun get(): ProvidableCompositionLocal<Boolean>? = cached

    @Suppress("UNCHECKED_CAST")
    private fun resolve(): ProvidableCompositionLocal<Boolean>? = runCatching {
        val clazz = Class.forName("androidx.wear.compose.foundation.CompositionLocalsKt")
        val method = clazz.getDeclaredMethod("getLocalReduceMotion")
        method.invoke(null) as ProvidableCompositionLocal<Boolean>
    }.getOrNull()
}
