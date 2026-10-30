@file:Suppress("RestrictedApiAndroidX")

package com.example.sampleremotecompose

import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rememberNamedRemoteString
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// A flat 2D Remote Compose document meant to be projected onto a surface in an XR scene — a wall,
// a table top — rather than floated as a panel. It paints only inside its rounded card, so the
// host surface keeps alpha around it, and its values are named strings a host can push without
// re-sending the document. `spatial/wall/scene.json` places it on a wall with [RemoteCube3d]
// floating in front of it. See docs/design/REMOTE_COMPOSE_XR.md.

/** A room-status card for a wall: a title and two named readings the host updates in place. */
@Composable
@RemoteComposable
fun RemoteWallCard() {
  val temperature = rememberNamedRemoteString("temperature", "21.5 °C")
  val lights = rememberNamedRemoteString("lights", "3 lights on")
  RemoteBox(
    modifier =
      RemoteModifier.fillMaxSize()
        .clip(RemoteRoundedCornerShape(28.rdp))
        .background(RemoteColor(Color(0xF01B2330)))
        .padding(24.rdp),
    contentAlignment = RemoteAlignment.CenterStart,
  ) {
    RemoteColumn(verticalArrangement = RemoteArrangement.Center) {
      RemoteText(
        "Living room".rs,
        color = RemoteColor(Color(0xFF9FB3C8)),
        fontSize = 18.rsp,
        fontWeight = 500.rf,
      )
      RemoteText(
        temperature,
        color = RemoteColor(Color.White),
        fontSize = 44.rsp,
        fontWeight = 600.rf,
      )
      RemoteText(lights, color = RemoteColor(Color(0xFFFFC56B)), fontSize = 20.rsp)
    }
  }
}
