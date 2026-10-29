@file:Suppress("RestrictedApiAndroidX")

package com.example.sampleremotecompose

import androidx.compose.remote.creation.profile.RcPlatformProfiles
import androidx.compose.remote.tooling.preview.RemoteContentPreview
import androidx.compose.remote.tooling.preview.RemotePreviewWrapper
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewWrapper
import ee.schimke.composeai.daemon.RemoteEmbeddedPreviewWrapper
import ee.schimke.composeai.daemon.RemoteViewPreviewWrapper
import ee.schimke.composeai.preview.AnimatedPreview
import ee.schimke.composeai.preview.InteractionPreview
import ee.schimke.composeai.preview.MotionFormat

// Two ways to preview a Remote Compose component: same pixels, different export behaviour. 200×200
// frames a single button; enlarge for bigger components.

// Approach 1 — call `RemoteContentPreview(profile = ...) { ... }` inside the `@Preview` body, as
// upstream's `remote-material3/samples` do. Needs no `@PreviewWrapper` support, but exposes no
// recorded document (no `.rc` data product), and pins upstream's View-hosted `RemoteComposePlayer`,
// which the a11y lane sees as a single unlabelled item; `composePreview.rcPlayer` can't reach a
// direct call. Approach 2, or `RemoteOverridablePreview(profile) { ... }`, use the configured
// player instead (by default the embedded Compose player, which carries semantics).

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

// Approach 2 — `@PreviewWrapper(RemotePreviewWrapper::class)` (ui-tooling-preview 1.11.0-beta01+)
// on a composable that only emits remote content; tooling wraps the body.
//
// With `:data-remotecompose-connector` on the runtime classpath, the renderer substitutes the
// connector's `RemoteOverridablePreviewWrapper` (`RemoteComposeWrapperSubstitution`), which wires
// `renderNow.overrides.remoteCompose.namedValues` into the player (so
// `rememberNamedRemoteString("label", "Tap me")` follows overrides) and records the document as an
// `.rc` data product. Use this form when the document itself must be collected.

@Preview(showBackground = true, widthDp = 200, heightDp = 200)
@PreviewWrapper(RemotePreviewWrapper::class)
@Composable
fun RemoteButtonWithBorderPreview() {
  Container { RemoteButtonWithBorder() }
}

/**
 * One second of the Remote Material 3 indeterminate progress indicator's document-driven animation.
 * A fixed duration because it is infinite; `showCurves` off because the moving values live in the
 * document, not Compose's animation inspector. Uses the View-backed player;
 * [RemoteIndeterminateCircularProgressIndicatorEmbeddedPreview] uses the embedded Compose player.
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
 * Companion preview for [RemoteButtonWithNamedLabel]: renders `"Tap me"` by default, and
 * `renderNow.overrides.remoteCompose.namedValues = {"label": ...}` swaps the label live without
 * rebuilding the document.
 */
@Preview(showBackground = true, widthDp = 200, heightDp = 200)
@PreviewWrapper(RemotePreviewWrapper::class)
@Composable
fun RemoteButtonWithNamedLabelPreview() {
  Container { RemoteButtonWithNamedLabel() }
}

/**
 * Preview for [RemoteShaderGradient], a gradient-shader fill. Like the named-label preview,
 * `namedValues = {"shaderColor": ...}` recolours the shader live through the existing override
 * mechanism.
 */
@Preview(showBackground = true, widthDp = 200, heightDp = 200)
@PreviewWrapper(RemotePreviewWrapper::class)
@Composable
fun RemoteShaderGradientPreview() {
  Container { RemoteShaderGradient() }
}

/**
 * [RemoteCube3d]: a drag-rotatable, lit cube defined wholly in the document (matrix, projection,
 * culling, shading and touch). No background, so the capture is transparent around the cube.
 */
@Preview(widthDp = 240, heightDp = 240)
@PreviewWrapper(RemotePreviewWrapper::class)
@Composable
fun RemoteCube3dPreview() {
  RemoteCube3d()
}

/** [RemoteCube3d] turned to the back-right and seen from below: the same document, other faces. */
@Preview(widthDp = 240, heightDp = 240)
@PreviewWrapper(RemotePreviewWrapper::class)
@Composable
fun RemoteCube3dFromBelowPreview() {
  RemoteCube3d(initialYaw = 200f, initialPitch = -30f)
}

/**
 * [RemoteCube3d] spinning at 90°/s, captured as an animation. Pinned to the AndroidX View player:
 * the embedded player in the pinned rc-players (2.17.5) keeps a `MatrixVectorMath` product at its
 * first value, which rc-players 2.21.0 fixes, so on the default player this would capture one pose
 * eleven times until the daemon and rc-players pins move together.
 */
@Preview(widthDp = 240, heightDp = 240)
@PreviewWrapper(RemoteViewPreviewWrapper::class)
@AnimatedPreview(durationMs = 1000, frameIntervalMs = 100, showCurves = false)
@Composable
fun RemoteCube3dSpinningPreview() {
  RemoteCube3d(spinDegreesPerSecond = 90f)
}

/**
 * [RemoteWallCard]: the 2D document `spatial/wall/scene.json` projects onto a wall. Transparent
 * outside its rounded card, like the surface it lands on.
 */
@Preview(widthDp = 320, heightDp = 200)
@PreviewWrapper(RemotePreviewWrapper::class)
@Composable
fun RemoteWallCardPreview() {
  RemoteWallCard()
}

/** [RemoteTexturedCube]: a bitmap texture mapped onto each visible face. */
@Preview(widthDp = 240, heightDp = 240)
@PreviewWrapper(RemotePreviewWrapper::class)
@Composable
fun RemoteTexturedCubePreview() {
  RemoteTexturedCube()
}

/**
 * Interacting with [RemoteTexturedCube]: two taps turn it half way round on a spring, a tap on the
 * swatch swaps the texture, and a last tap turns it once more. Every change is document state moved
 * by a document action; no host code runs.
 */
@Preview(widthDp = 240, heightDp = 240)
@PreviewWrapper(RemotePreviewWrapper::class)
@InteractionPreview(
  targets = [0, 0, 1, 0],
  gapMs = 900,
  frameIntervalMs = 33,
  format = MotionFormat.Gif,
  caption = "Tap the cube to turn it on a spring; tap the swatch to swap its texture.",
)
@Composable
fun RemoteTexturedCubeInteractionPreview() {
  RemoteTexturedCube()
}
