@file:Suppress("RestrictedApiAndroidX")

package com.example.sampleremotecompose

import androidx.compose.remote.creation.compose.action.hostAction
import androidx.compose.remote.creation.compose.action.valueChange
import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.shaders.RemoteBrush
import androidx.compose.remote.creation.compose.shaders.linearGradient
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.animateRemoteFloat
import androidx.compose.remote.creation.compose.state.rb
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rememberMutableRemoteFloat
import androidx.compose.remote.creation.compose.state.rememberNamedRemoteColor
import androidx.compose.remote.creation.compose.state.rememberNamedRemoteString
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.wear.compose.remote.material3.RemoteButton
import androidx.wear.compose.remote.material3.RemoteCircularProgressIndicator
import androidx.wear.compose.remote.material3.RemoteText
import androidx.wear.compose.remote.material3.buttonSizeModifier

// Pure-remote composables mirroring upstream's `remote-material3/samples` (the button variants that
// need no image fixtures). `Previews.kt` wraps them both ways: a wrapper call inside the `@Preview`
// ([RemoteButtonEnabledPreview]) and `@PreviewWrapper(RemotePreviewWrapper::class)`
// ([RemoteButtonWithBorderPreview]).

// `hostAction(...)` is Remote Compose's `onClick = { ... }`: a remote string payload and a
// remote-float handler id.
private val testAction = hostAction("testAction".rs, 1.rf)

@Composable
@RemoteComposable
fun RemoteButtonEnabled() {
  RemoteButton(
    onClick = testAction,
    modifier = RemoteModifier.buttonSizeModifier(),
    enabled = true.rb,
    content = { RemoteText("Enabled".rs) },
  )
}

@Composable
@RemoteComposable
fun RemoteButtonWithBorder() {
  RemoteButton(
    onClick = testAction,
    modifier = RemoteModifier.buttonSizeModifier(),
    border = 8.rdp,
    borderColor = RemoteColor(Color.Green),
  ) {
    RemoteText("Bordered".rs)
  }
}

/**
 * Reads its label from a [rememberNamedRemoteString] binding, which the panel editor or
 * `renderNow.overrides.remoteCompose` can set; defaults to `"Tap me"`.
 */
@Composable
@RemoteComposable
fun RemoteButtonWithNamedLabel() {
  val label = rememberNamedRemoteString("label", "Tap me")
  RemoteButton(
    onClick = testAction,
    modifier = RemoteModifier.buttonSizeModifier(),
    content = { RemoteText(label) },
  )
}

@Composable
@RemoteComposable
fun RemoteButtonWithShape() {
  RemoteButton(
    onClick = testAction,
    modifier = RemoteModifier.buttonSizeModifier(),
    shape = RemoteRoundedCornerShape(4.rdp),
    content = { RemoteText("Custom shape".rs) },
  )
}

/**
 * The indeterminate Remote Material 3 progress indicator, whose motion is encoded in the
 * [androidx.compose.remote.player.core.RemoteDocument] (arc expressions over Remote Compose's
 * continuous-time variable). Kept pure-remote so both players are exercised without an app-side
 * animation masking a stalled document clock.
 */
@Composable
@RemoteComposable
fun RemoteIndeterminateCircularProgressIndicator() {
  RemoteCircularProgressIndicator(modifier = RemoteModifier.size(72.rdp))
}

/**
 * AndroidX's `RemoteCircularProgressIndicatorAnimatedSample` technique: a click changes a remote
 * value and [animateRemoteFloat] animates the indicator to its next quarter.
 */
@Composable
@RemoteComposable
fun RemoteAnimatedCircularProgressIndicator() {
  val progress = rememberMutableRemoteFloat { 0.25f.rf }
  val animatedProgress = animateRemoteFloat(progress, 0.25f)
  val advanceProgress = valueChange(progress, ((progress + 0.25f) % 1f).createReference())

  RemoteCircularProgressIndicator(
    progress = animatedProgress,
    modifier = RemoteModifier.size(72.rdp).clickable(action = advanceProgress, role = Role.Button),
  )
}

/**
 * A full-size box painted with a [RemoteBrush] gradient shader, serialised into the document and
 * rasterised by the player. The middle stop is a [rememberNamedRemoteColor] named `shaderColor`, so
 * `namedValues = {"shaderColor": ColorValue(...)}` recolours it live; defaults to cyan.
 */
@Composable
@RemoteComposable
fun RemoteShaderGradient() {
  val shaderColor = rememberNamedRemoteColor("shaderColor", Color(0xFF7DE2FF))
  val brush =
    RemoteBrush.linearGradient(
      listOf(RemoteColor(Color(0xFF101820)), shaderColor, RemoteColor(Color(0xFFFFB86C)))
    )
  RemoteBox(
    modifier = RemoteModifier.fillMaxSize().background(brush),
    contentAlignment = RemoteAlignment.Center,
    content = { RemoteText("Shader".rs) },
  )
}

/** Centers [content] in a remote full-size box (upstream's `Container`), for preview framing. */
@Composable
@RemoteComposable
fun Container(content: @Composable @RemoteComposable () -> Unit) {
  RemoteBox(
    modifier = RemoteModifier.fillMaxSize(),
    contentAlignment = RemoteAlignment.Center,
    content = content,
  )
}
