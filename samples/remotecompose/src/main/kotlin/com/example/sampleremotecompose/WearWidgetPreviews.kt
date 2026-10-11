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
 * The Remote Compose widget content, whose encoded document is captured as the `<stem>.rc` sidecar
 * and packed by `BundlePreviewTask.resolvePreviewIr`.
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
 * A Wear widget shape wrapper that preserves the encoded RemoteCompose document. A `@Preview` takes
 * only one `@PreviewWrapper`, and [RemoteOverridablePreviewWrapper] is what captures the `.rc`, so
 * replacing it would drop the sidecar. Instead this extends it: `super.Wrap(content)` captures the
 * (unclipped) document and the outer [clip] frames the player.
 */
class SquircleRemoteWidgetWrapper : RemoteOverridablePreviewWrapper() {
  // The applier check reads this override as RemoteCompose-targeted and `Box` as a UI composable.
  // Framing is exactly the host/preview concern this wrapper exists to add around the captured
  // document, so the mismatch is the design rather than a mistake.
  @Suppress("COMPOSE_APPLIER_CALL_MISMATCH")
  @Composable
  override fun Wrap(content: @Composable () -> Unit) {
    // RoundedCornerShape(45%) reads as a squircle on a square widget; the point here is that the
    // clip frames the shape while super.Wrap still captures the .rc.
    Box(modifier = Modifier.clip(RoundedCornerShape(percent = 45))) { super.Wrap(content) }
  }
}

/**
 * A Wear widget preview in its squircle shape that still produces the `<stem>.rc` sidecar
 * (`RemoteWidgetDocCaptureTest` asserts it). The plain-Compose shape wrappers in
 * `:samples:wear-widget` would drop the document here.
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
