@file:Suppress("RestrictedApiAndroidX")

package com.example.sampleremotecompose

import androidx.compose.remote.creation.compose.action.hostAction
import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.shaders.RemoteBrush
import androidx.compose.remote.creation.compose.shaders.linearGradient
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.rb
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rememberNamedRemoteColor
import androidx.compose.remote.creation.compose.state.rememberNamedRemoteString
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.remote.material3.RemoteButton
import androidx.wear.compose.remote.material3.RemoteText
import androidx.wear.compose.remote.material3.buttonSizeModifier

/**
 * Pure-remote composables — each one is the kind of component a real Remote Compose screen is built
 * from. Mirrors the upstream `wear/compose/remote/remote-material3/samples` set, reduced to the
 * three button variants that don't need image-vector / bitmap fixtures.
 *
 * Exposed as the "unit of content" that the two preview approaches in `Previews.kt` wrap
 * differently:
 * 1. wrapper call inside the `@Preview`-annotated UI composable (see [RemoteButtonEnabledPreview]),
 *    and
 * 2. `@PreviewWrapper(RemotePreviewWrapper::class)` applied to a `@Preview`-annotated composable
 *    that only emits remote content (see [RemoteButtonWithBorderPreview]).
 */

// A shared action used by every sample button — `hostAction(...)` is the Remote
// Compose equivalent of `onClick = { ... }`. The two arguments are a remote
// string payload and a remote-float handler id, both hoisted out so the
// per-button code stays focused on layout.
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
 * Reads its label from a Remote Compose named-value binding declared via
 * [rememberNamedRemoteString]. The panel-side Remote Compose editor sets the `label` named value
 * via `interactive/setRemoteCompose` / `renderNow.overrides.remoteCompose`, and the next render
 * picks it up here. Without an override the default `"Tap me"` shows, so the preview is still a
 * useful static screenshot in agent-driven `composePreviewRenderAll` runs.
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
 * A Remote Compose **shader** component: a full-size box painted with a [RemoteBrush] gradient
 * shader (Remote Compose's `shaders` package — `RemoteLinearGradient`/`RemoteRadialGradient`/etc.
 * are the document-level equivalents of Compose's `Brush.linearGradient`). The gradient serialises
 * into the `RemoteDocument` byte stream and is rasterised by the player, not by an app-side
 * `ShaderBrush`.
 *
 * **Shader control** — the middle gradient stop is a [rememberNamedRemoteColor] binding named
 * `shaderColor`, so the panel-side Remote Compose editor (or any caller seeding
 * `renderNow.overrides.remoteCompose.namedValues = {"shaderColor": ColorValue(...)}`) recolours the
 * shader live, without rebuilding the document — the same override path
 * [RemoteButtonWithNamedLabel]'s string label uses, here driving a shader uniform. Without an
 * override the default cyan stop shows, so the preview is a useful static capture in
 * `composePreviewRenderAll` runs.
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

/**
 * Centers [content] inside a remote full-size box. Equivalent to the upstream sample's `Container`;
 * kept private to emphasise that its purpose is preview framing, not production composition.
 */
@Composable
@RemoteComposable
fun Container(content: @Composable @RemoteComposable () -> Unit) {
  RemoteBox(
    modifier = RemoteModifier.fillMaxSize(),
    contentAlignment = RemoteAlignment.Center,
    content = content,
  )
}
