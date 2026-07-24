@file:Suppress("RestrictedApiAndroidX")

package com.example.sampleremotecompose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewWrapper
import androidx.wear.compose.remote.material3.RemoteText
import ee.schimke.composeai.daemon.RemoteOverridablePreviewWrapper

/**
 * The Remote Compose widget "content" — the payload a real Glance Wear widget draws, and whose
 * **encoded RemoteCompose document is the critical artifact** the render pipeline captures as the
 * `<stem>.rcdoc` sidecar (packed into the bundle by `BundlePreviewTask.resolvePreviewIr`).
 */
@Composable
@RemoteComposable
fun RemoteImageWidget() {
  // A solid-ish fill via a two-stop gradient — the same shader path `RemoteShaderGradient` uses,
  // so it serialises cleanly into the RemoteDocument byte stream.
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

/**
 * A Wear widget **shape** wrapper that preserves the encoded RemoteCompose document.
 *
 * This is the crux of framing a Remote Compose widget in its ideal shape without losing the doc.
 * The `.rcdoc` capture is done *by the RemoteCompose wrapper itself* — [RemoteOverridablePreviewWrapper]
 * `.Wrap` runs `captureSingleRemoteDocument` and offers the bytes to `IrSidecarChannel` — and a
 * `@Preview` may carry only **one** `@PreviewWrapper`. A shape wrapper that *replaced* the Remote
 * Compose wrapper would silently drop the `.rcdoc` (verified: the in-body `RemoteContentPreview`
 * previews here produce no sidecar, only the `@PreviewWrapper(RemotePreviewWrapper::class)` ones do).
 *
 * So this wrapper **extends** [RemoteOverridablePreviewWrapper] and clips its rendered output to the
 * widget's ideal shape: `super.Wrap(content)` still captures the document (unclipped — the shape is
 * a host/preview concern, not part of the widget payload), and the outer [clip] just frames the
 * player. One `@PreviewWrapper`, both the encoded doc and the ideal shape.
 */
class SquircleRemoteWidgetWrapper : RemoteOverridablePreviewWrapper() {
  @Composable
  override fun Wrap(content: @Composable () -> Unit) {
    // RoundedCornerShape(45%) reads as a squircle on a square widget; the point here is that the
    // clip frames the shape while super.Wrap still captures the .rcdoc.
    Box(modifier = Modifier.clip(RoundedCornerShape(percent = 45))) { super.Wrap(content) }
  }
}

/**
 * Wear widget preview framed in its ideal (squircle) shape **and** capturing the encoded RemoteCompose
 * document. Mirrors the wear-os-samples `WearWidgetPreview(ImageWidget(), params)` intent, but routes
 * the framing through [SquircleRemoteWidgetWrapper] so the `<stem>.rcdoc` sidecar is still produced —
 * `RemoteWidgetDocCaptureTest` asserts exactly that. Contrast the plain-Compose shape wrappers in
 * `:samples:wear-widget`, which are correct for non-RemoteCompose widgets but would drop the doc here.
 */
@Preview(
  name = "Remote Widget Squircle",
  showBackground = true,
  backgroundColor = 0xFF000000,
  widthDp = 192,
  heightDp = 192,
)
@PreviewWrapper(SquircleRemoteWidgetWrapper::class)
@Composable
fun RemoteWidgetSquirclePreview() {
  Container { RemoteImageWidget() }
}
