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
 * The Wear widget's **background**, expressed as the host-drawn [WearWidgetBrush] rather than a fill
 * painted inside the content.
 *
 * This is the split a Glance Wear widget has to respect. The widget host draws the squircle
 * container — rounded background + padding — and the widget's content is laid out *inside* that
 * frame, already inset by the padding. A content composable that paints its own full-bleed
 * background therefore paints a **square-cornered rectangle inside the rounded container**: it
 * cannot reach the corners (the padding holds it off) and it is not clipped to the corner radius
 * (the container clips its own background, not arbitrary content draws). Declaring the fill as the
 * document's `background` instead hands it to `WearWidgetContainer`, which paints it as the
 * container's own surface — corner-clipped, edge-to-edge, no inner rectangle.
 *
 * Upstream's `wear-os-samples/WearWidget` does exactly this (`WearWidgetDocument(background =
 * WearWidgetBrush.color(...))`), and so do the `WidgetContainer*Remote` stickers in
 * `:samples:design-catalog-remote-m3`.
 */
val RemoteImageWidgetBackground: WearWidgetBrush
  get() =
    WearWidgetBrush.verticalGradient(listOf(Color(0xFF1E88E5).rc, Color(0xFF1565C0).rc))

/**
 * The Wear widget's content — a **Remote Compose** document (`RemoteBox` / `RemoteText`, the same
 * primitives a real Glance Wear widget draws). Its serialised `RemoteDocument` byte stream is the
 * widget's exported value; [CapturingWearWidgetPreview] captures it into the `<stem>.rc` sidecar.
 *
 * Deliberately paints **no background of its own** — see [RemoteImageWidgetBackground] for why that
 * is the whole point. The content fills the container's padded content slot and centres its label
 * on whatever surface the host painted underneath.
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
