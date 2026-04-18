package ee.schimke.composeai.renderer

import androidx.compose.runtime.ProvidableCompositionLocal

/**
 * Resolves Compose's `LocalScrollCaptureInProgress` by reflection so the
 * renderer compiles and runs against older Compose UI versions that predate
 * the system long-screenshot signal.
 *
 * Compose UI 1.7+ exposes the providable form as:
 *
 * ```
 * package androidx.compose.ui.platform
 * internal val LocalProvidableScrollCaptureInProgress: ProvidableCompositionLocal<Boolean>
 * ```
 *
 * The backing JVM member is `CompositionLocalsKt.getLocalProvidableScrollCaptureInProgress()`.
 * When the consumer's Compose UI is older, the lookup returns `null` and the
 * caller skips the provider — scrolling-preview stitched captures fall back
 * to whatever transient UI the composable draws naturally (e.g. Wear's
 * `ScreenScaffold` scroll indicator remains visible). That's a graceful
 * degradation rather than a classload failure.
 *
 * Cached after the first call: reflection lookup cost amortises across every
 * preview in the shard.
 */
internal object ScrollCaptureInProgressLocal {
    private val cached: ProvidableCompositionLocal<Boolean>? by lazy { resolve() }

    fun get(): ProvidableCompositionLocal<Boolean>? = cached

    @Suppress("UNCHECKED_CAST")
    private fun resolve(): ProvidableCompositionLocal<Boolean>? = runCatching {
        val clazz = Class.forName("androidx.compose.ui.platform.CompositionLocalsKt")
        val method = clazz.getDeclaredMethod("getLocalProvidableScrollCaptureInProgress")
        method.invoke(null) as ProvidableCompositionLocal<Boolean>
    }.getOrNull()
}
