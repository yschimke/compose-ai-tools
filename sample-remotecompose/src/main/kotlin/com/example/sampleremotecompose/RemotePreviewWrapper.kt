package com.example.sampleremotecompose

import androidx.compose.remote.creation.profile.RcPlatformProfiles
import androidx.compose.remote.tooling.preview.RemotePreview
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.PreviewWrapperProvider

/**
 * Local copy of the upstream wrapper:
 *   androidx.compose.remote.tooling.preview.RemotePreviewWrapper
 *
 * Not yet published in `remote-tooling-preview:1.0.0-alpha07`, so we define it
 * here against `PreviewWrapperProvider` from `ui-tooling-preview:1.11.0-rc01`.
 * Once the AndroidX artifact ships it, swap this out and import the official
 * class — the signature is the same.
 *
 * Applied with `@PreviewWrapper(RemotePreviewWrapper::class)` — see
 * [RemoteButtonWithBorderPreview] in `Previews.kt`.
 */
@Suppress("RestrictedApiAndroidX")
class RemotePreviewWrapper : PreviewWrapperProvider {
    @Composable
    override fun Wrap(content: @Composable () -> Unit) {
        RemotePreview(profile = RcPlatformProfiles.ANDROIDX, content = content)
    }
}
