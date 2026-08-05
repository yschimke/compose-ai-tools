@file:Suppress("RestrictedApiAndroidX")

package com.example.sampleremotecompose

import androidx.compose.remote.creation.profile.RcPlatformProfiles
import androidx.compose.remote.tooling.preview.RemoteContentPreview
import androidx.compose.remote.tooling.preview.RemotePreviewWrapper
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewWrapper
import ee.schimke.composeai.daemon.RemoteEmbeddedPreviewWrapper
import ee.schimke.composeai.preview.AnimatedPreview

/**
 * Two ways to preview a Remote Compose component — same output, different code shape. The
 * component-preview dimensions (200×200) are kept small and square so the rendered PNG frames a
 * single button cleanly; bump `widthDp` / `heightDp` if you add components that need more room.
 */

// ---------------------------------------------------------------------------
// Approach 1 — `RemoteContentPreview(profile = ...) { ... }` called inside the
// `@Preview`-annotated UI composable.
//
// Matches the `remote-material3/samples` pattern, where each `*Preview`
// function wraps its component with an explicit `RemoteContentPreview { Container
// { ... } }`. Verbose for many previews but works today — no reliance on
// the `@PreviewWrapper` tooling annotation (which only exists in
// compose-ui 1.11.0-beta+ and isn't yet understood by Android Studio
// releases paired with stable Compose).
// ---------------------------------------------------------------------------

// churn probe: comment-only edit, no bytecode or pixel change expected.
@Preview(showBackground = true, widthDp = 200, heightDp = 200)
@Composable
fun RemoteButtonEnabledPreview() {
  RemoteContentPreview(profile = RcPlatformProfiles.ANDROIDX) {
    Container { RemoteButtonEnabled() }
  }
}

@Preview(showBackground = true, widthDp = 200, heightDp = 200)
@Composable
fun RemoteButtonWithShapePreview() {
  RemoteContentPreview(profile = RcPlatformProfiles.ANDROIDX) {
    Container { RemoteButtonWithShape() }
  }
}

// ---------------------------------------------------------------------------
// Approach 2 — `@PreviewWrapper(RemotePreviewWrapper::class)` applied to a
// `@Preview`-annotated composable that only emits remote content.
//
// Tooling (Android Studio + our discovery pipeline, once they understand the
// annotation) invokes `RemotePreviewWrapper.Wrap` around the function body,
// so the function itself stays as small as the component it renders. This is
// the new path introduced in the Compose alphas — see `PreviewWrapper.kt` in
// `androidx.compose.ui.tooling.preview` (1.11.0-beta01+).
//
// When `:data-remotecompose-connector` is on the runtime classpath, the
// renderer's wrapper resolver transparently substitutes upstream
// `RemotePreviewWrapper` with the connector's `RemoteOverridablePreviewWrapper`
// (see `RemoteComposeWrapperSubstitution` + its `META-INF/services` file). That
// substitution wires `renderNow.overrides.remoteCompose.namedValues` into the
// running player's `StateUpdater`, so a binding like
// `rememberNamedRemoteString("label", "Tap me")` flips when the daemon seeds an
// override — no annotation change on the preview side.
// ---------------------------------------------------------------------------

@Preview(showBackground = true, widthDp = 200, heightDp = 200)
@PreviewWrapper(RemotePreviewWrapper::class)
@Composable
fun RemoteButtonWithBorderPreview() {
  Container { RemoteButtonWithBorder() }
}

/**
 * Captures the Remote Material 3 indeterminate progress indicator across one second of its
 * document-driven animation. A fixed duration is required because the animation is intentionally
 * infinite, and [AnimatedPreview.showCurves] is disabled because the moving values live in the
 * Remote Compose document rather than Compose UI's animation inspector.
 *
 * The wrapper path also emits the captured `.rc` document. This variant uses the standard
 * View-backed Remote Compose player; [RemoteIndeterminateCircularProgressIndicatorEmbeddedPreview]
 * renders the identical remote content through the embedded Compose player.
 */
@Preview(showBackground = true, widthDp = 200, heightDp = 200)
@PreviewWrapper(RemotePreviewWrapper::class)
@AnimatedPreview(durationMs = 1000, frameIntervalMs = 100, showCurves = false)
@Composable
fun RemoteIndeterminateCircularProgressIndicatorStandardPreview() {
  Container { RemoteIndeterminateCircularProgressIndicator() }
}

/** Embedded-player companion to [RemoteIndeterminateCircularProgressIndicatorStandardPreview]. */
@Preview(showBackground = true, widthDp = 200, heightDp = 200)
@PreviewWrapper(RemoteEmbeddedPreviewWrapper::class)
@AnimatedPreview(durationMs = 1000, frameIntervalMs = 100, showCurves = false)
@Composable
fun RemoteIndeterminateCircularProgressIndicatorEmbeddedPreview() {
  Container { RemoteIndeterminateCircularProgressIndicator() }
}

/**
 * Standard-player preview for the interaction-driven `animateRemoteFloat` technique. Click the
 * indicator (or use `compose-preview record`) to animate progress to the next quarter.
 */
@Preview(showBackground = true, widthDp = 200, heightDp = 200)
@PreviewWrapper(RemotePreviewWrapper::class)
@Composable
fun RemoteAnimatedCircularProgressIndicatorStandardPreview() {
  Container { RemoteAnimatedCircularProgressIndicator() }
}

/** Embedded-player companion to [RemoteAnimatedCircularProgressIndicatorStandardPreview]. */
@Preview(showBackground = true, widthDp = 200, heightDp = 200)
@PreviewWrapper(RemoteEmbeddedPreviewWrapper::class)
@Composable
fun RemoteAnimatedCircularProgressIndicatorEmbeddedPreview() {
  Container { RemoteAnimatedCircularProgressIndicator() }
}

/**
 * Companion preview for [RemoteButtonWithNamedLabel]. Annotated with the same upstream
 * `@PreviewWrapper(RemotePreviewWrapper::class)` as [RemoteButtonWithBorderPreview]; the
 * connector's substitution provider decides at render time whether to swap to the override-aware
 * wrapper. Default render shows `"Tap me"`; the panel-side Remote Compose editor (or any caller
 * passing `renderNow.overrides.remoteCompose.namedValues = {"label": ...}`) swaps that for a live
 * label without rebuilding the document.
 */
@Preview(showBackground = true, widthDp = 200, heightDp = 200)
@PreviewWrapper(RemotePreviewWrapper::class)
@Composable
fun RemoteButtonWithNamedLabelPreview() {
  Container { RemoteButtonWithNamedLabel() }
}

/**
 * Preview for [RemoteShaderGradient] — a Remote Compose gradient-**shader** fill. Uses the same
 * `@PreviewWrapper(RemotePreviewWrapper::class)` path as the named-label preview so the connector's
 * substitution wires the `shaderColor` named value into the running player: the default render
 * shows the static gradient, and `renderNow.overrides.remoteCompose.namedValues = {"shaderColor":
 * ...}` recolours the shader live. This is the "shader control" surfaced through the existing
 * named-value override mechanism rather than a new control type.
 */
@Preview(showBackground = true, widthDp = 200, heightDp = 200)
@PreviewWrapper(RemotePreviewWrapper::class)
@Composable
fun RemoteShaderGradientPreview() {
  Container { RemoteShaderGradient() }
}
