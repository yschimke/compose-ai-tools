@file:Suppress("RestrictedApiAndroidX")

package com.example.sampleremotecompose

import androidx.compose.remote.creation.profile.RcPlatformProfiles
import androidx.compose.remote.tooling.preview.RemotePreview
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewWrapper

/**
 * Two ways to preview a Remote Compose component — same output, different
 * code shape. The component-preview dimensions (200×200) are kept small and
 * square so the rendered PNG frames a single button cleanly; bump
 * `widthDp` / `heightDp` if you add components that need more room.
 */

// ---------------------------------------------------------------------------
// Approach 1 — `RemotePreview(profile = ...) { ... }` called inside the
// `@Preview`-annotated UI composable.
//
// Matches the `remote-material3/samples` pattern, where each `*Preview`
// function wraps its component with an explicit `RemotePreview { Container
// { ... } }`. Verbose for many previews but works today — no reliance on
// the `@PreviewWrapper` tooling annotation (which only exists in
// compose-ui 1.11.0-beta+ and isn't yet understood by Android Studio
// releases paired with stable Compose).
// ---------------------------------------------------------------------------

@Preview(showBackground = true, widthDp = 200, heightDp = 200)
@Composable
fun RemoteButtonEnabledPreview() {
    RemotePreview(profile = RcPlatformProfiles.ANDROIDX) {
        Container { RemoteButtonEnabled() }
    }
}

@Preview(showBackground = true, widthDp = 200, heightDp = 200)
@Composable
fun RemoteButtonWithShapePreview() {
    RemotePreview(profile = RcPlatformProfiles.ANDROIDX) {
        Container { RemoteButtonWithShape() }
    }
}

// ---------------------------------------------------------------------------
// Approach 2 — `@PreviewWrapper(RemotePreviewWrapper::class)` applied to a
// `@Preview`-annotated composable that only emits remote content.
//
// Tooling (Android Studio + our discovery pipeline, once they understand the
// annotation) invokes [RemotePreviewWrapper.Wrap] around the function body,
// so the function itself stays as small as the component it renders. This is
// the new path introduced in the Compose alphas — see `PreviewWrapper.kt` in
// `androidx.compose.ui.tooling.preview` (1.11.0-beta01+).
// ---------------------------------------------------------------------------

@Preview(showBackground = true, widthDp = 200, heightDp = 200)
@PreviewWrapper(RemotePreviewWrapper::class)
@Composable
fun RemoteButtonWithBorderPreview() {
    Container { RemoteButtonWithBorder() }
}
