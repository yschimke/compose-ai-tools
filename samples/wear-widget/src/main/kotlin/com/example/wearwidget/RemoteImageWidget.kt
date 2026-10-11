@file:Suppress("RestrictedApiAndroidX")

package com.example.wearwidget

import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.glance.wear.WearWidgetBrush
import androidx.glance.wear.verticalGradient
import androidx.wear.compose.remote.material3.RemoteText

/**
 * The widget's background as a host-drawn [WearWidgetBrush], not a fill inside the content: the
 * host lays content out inside the padded squircle, so a content-painted background would be a
 * square-cornered rectangle inside it. As the document `background`, `WearWidgetContainer` paints
 * it corner-clipped and edge-to-edge (as upstream `wear-os-samples/WearWidget` does).
 */
val RemoteImageWidgetBackground: WearWidgetBrush
  get() = WearWidgetBrush.verticalGradient(listOf(Color(0xFF1E88E5).rc, Color(0xFF1565C0).rc))

/**
 * The widget content as a Remote Compose document (`RemoteBox` / `RemoteText`), captured by
 * [CapturingWearWidgetPreview] into the `<stem>.rc` sidecar. Paints no background of its own (see
 * [RemoteImageWidgetBackground]).
 */
@Composable
@RemoteComposable
fun RemoteImageWidget() {
  RemoteBox(
    modifier = RemoteModifier.fillMaxSize(),
    contentAlignment = RemoteAlignment.Center,
    content = { RemoteText("Widget".rs) },
  )
}
