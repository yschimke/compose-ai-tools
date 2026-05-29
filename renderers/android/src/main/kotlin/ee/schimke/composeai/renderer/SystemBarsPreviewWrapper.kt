package ee.schimke.composeai.renderer

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.PreviewWrapperProvider

/**
 * `@PreviewWrapper`-driven entry point for [SystemBarsFrame]. Apply with
 *
 * ```kotlin
 * @PreviewWrapper(SystemBarsPreviewWrapper::class)
 * @Preview(device = "id:pixel_8")
 * @Composable
 * fun MyScreenPreview() { ... }
 * ```
 *
 * Equivalent to wrapping the preview body manually in
 * `SystemBarsFrame(uiMode = 0) { ... }` — useful when a preview doesn't pin
 * `showSystemUi = true` (so the renderer's automatic wrap doesn't fire) but
 * still wants phone-shape chrome around the captured surface, or when the
 * caller wants to compose this wrapper with other `PreviewWrapperProvider`s.
 *
 * Defaults to light-mode chrome: `PreviewWrapperProvider.Wrap` doesn't expose
 * the `@Preview(uiMode = …)` value to the wrapper, so dark-mode behaviour
 * still flows through the renderer's automatic path (where `params.uiMode`
 * is threaded directly into [SystemBarsFrame]).
 *
 * Requires `androidx.compose.ui.tooling.preview` 1.11+ on the consumer
 * classpath — that's the version that introduced `PreviewWrapperProvider`.
 * On older Compose, this class will fail to load if referenced; the
 * implicit `showSystemUi = true` path stays available regardless.
 */
@Suppress("RestrictedApiAndroidX")
class SystemBarsPreviewWrapper : PreviewWrapperProvider {
    @Composable
    override fun Wrap(content: @Composable () -> Unit) {
        SystemBarsFrame(uiMode = 0, content = content)
    }
}
