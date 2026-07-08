@file:Suppress("RestrictedApiAndroidX")

package com.example.designcatalogremotem3

import androidx.compose.remote.creation.compose.action.hostAction
import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteBox
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

// ---------------------------------------------------------------------------
// Remote Compose design-catalog sticker sheet.
//
// Each `@CatalogRemoteModes`-annotated function is one component sticker: the
// remote content wrapped in the module's `RemoteSticker` frame (RemotePreview →
// RemoteDocument → player raster). The function names below are the stable keys
// `catalog.spec.json` joins on. Components mirror the Wear Compose Remote
// Material 3 (`androidx.wear.compose.remote.material3`) + `remote-creation-compose`
// surface exercised by `:samples:remotecompose`, arranged as a sticker sheet.
// ---------------------------------------------------------------------------

// A shared action used by every sample button — `hostAction(...)` is the Remote
// Compose equivalent of `onClick = { ... }`. The two arguments are a remote
// string payload and a remote-float handler id.
private val testAction = hostAction("catalogAction".rs, 1.rf)

// ---------------------------------------------------------------------------
// Buttons — the Remote Material 3 button plus its border / shape variants.
// ---------------------------------------------------------------------------

@CatalogRemoteModes
@Composable
fun FilledRemoteButton() = RemoteSticker {
  RemoteButton(
    onClick = testAction,
    modifier = RemoteModifier.buttonSizeModifier(),
    enabled = true.rb,
    content = { RemoteText("Enabled".rs) },
  )
}

@CatalogRemoteModes
@Composable
fun BorderedRemoteButton() = RemoteSticker {
  RemoteButton(
    onClick = testAction,
    modifier = RemoteModifier.buttonSizeModifier(),
    border = 8.rdp,
    borderColor = RemoteColor(Color.Green),
    content = { RemoteText("Bordered".rs) },
  )
}

@CatalogRemoteModes
@Composable
fun CustomShapeRemoteButton() = RemoteSticker {
  RemoteButton(
    onClick = testAction,
    modifier = RemoteModifier.buttonSizeModifier(),
    shape = RemoteRoundedCornerShape(4.rdp),
    content = { RemoteText("Custom shape".rs) },
  )
}

/**
 * Reads its label from a Remote Compose named-value binding ([rememberNamedRemoteString]). The
 * default render shows `"Tap me"`, so the sticker is a useful static capture; the connector's
 * override path (`renderNow.overrides.remoteCompose.namedValues = {"label": …}`) flips the label
 * live without rebuilding the document — the interactive story the `:data-remotecompose-connector`
 * demonstrates.
 */
@CatalogRemoteModes
@Composable
fun NamedLabelRemoteButton() = RemoteSticker {
  val label = rememberNamedRemoteString("label", "Tap me")
  RemoteButton(
    onClick = testAction,
    modifier = RemoteModifier.buttonSizeModifier(),
    content = { RemoteText(label) },
  )
}

// ---------------------------------------------------------------------------
// Text — the Remote Material 3 text primitive on its own. An explicit dark
// `color` is required: `RemoteText` defaults to a near-white content colour
// (fine inside a button surface or on the shader, invisible on the sticker's
// white `showBackground`).
// ---------------------------------------------------------------------------

@CatalogRemoteModes
@Composable
fun RemoteTextSticker() = RemoteSticker {
  RemoteText("Remote".rs, color = RemoteColor(Color(0xFF1D1B20)))
}

// ---------------------------------------------------------------------------
// Shaders — a document-level gradient fill (`remote-creation-compose` shaders),
// serialised into the RemoteDocument and rasterised by the player rather than an
// app-side `ShaderBrush`. The middle stop is a named-value binding so the
// connector can recolour it live.
// ---------------------------------------------------------------------------

@CatalogRemoteModes
@Composable
fun ShaderGradientSticker() = RemoteSticker {
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
