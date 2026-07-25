@file:Suppress("RestrictedApiAndroidX")

package com.example.wearwidget

import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.shaders.RemoteBrush
import androidx.compose.remote.creation.compose.shaders.linearGradient
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.remote.material3.RemoteText

/**
 * The Wear widget's content — a **Remote Compose** document (`RemoteBox` / `RemoteText`, the same
 * primitives a real Glance Wear widget draws). Its serialised `RemoteDocument` byte stream is the
 * widget's exported value; [CapturingWearWidgetPreview] captures it into the `<stem>.rc` sidecar.
 */
@Composable
@RemoteComposable
fun RemoteImageWidget() {
  val fill =
    RemoteBrush.linearGradient(
      listOf(RemoteColor(Color(0xFF1E88E5)), RemoteColor(Color(0xFF1565C0)))
    )
  RemoteBox(
    modifier = RemoteModifier.fillMaxSize().background(fill),
    contentAlignment = RemoteAlignment.Center,
    content = { RemoteText("Widget".rs) },
  )
}
