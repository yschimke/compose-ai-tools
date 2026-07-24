@file:Suppress("RestrictedApiAndroidX")

package com.example.wearwidget

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.PreviewWrapperProvider
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * `PreviewWrapperProvider` that frames a Wear widget preview in its **ideal squircle shape** — the
 * plugin-native equivalent of the wear-os-samples `WearWidgetPreview(content, params)` chrome. The
 * wrapped content (a fixed-size widget) is clipped to a [SquircleShape], so the rendered PNG shows
 * the widget masked to a squircle with the preview background showing through the corners.
 *
 * Applied via `@PreviewWrapperClass("com.example.wearwidget.SquircleWidgetWrapper")` on a preview;
 * requires ui-tooling-preview 1.11+ (the `PreviewWrapperProvider` interface) on the classpath.
 *
 * **RemoteCompose widgets need a different wrapper.** These plain-Compose shape wrappers are correct
 * for widgets built from ordinary composables. A *RemoteCompose* widget's value is its encoded
 * document (the `<stem>.rcdoc` sidecar), which is captured only by the RemoteCompose wrapper
 * (`RemoteOverridablePreviewWrapper.Wrap` → `captureSingleRemoteDocument`). Since a `@Preview` may
 * carry only one `@PreviewWrapper`, framing a RemoteCompose widget in a shape must use a wrapper that
 * **extends** the RemoteCompose wrapper (so the doc is preserved) — see
 * `:samples:remotecompose`'s `SquircleRemoteWidgetWrapper` and `RemoteWidgetDocCaptureTest`. A plain
 * shape wrapper here would render the same pixels but drop the `.rcdoc`.
 */
class SquircleWidgetWrapper : PreviewWrapperProvider {
  @Composable
  override fun Wrap(content: @Composable () -> Unit) {
    Box(modifier = Modifier.clip(SquircleShape()), contentAlignment = Alignment.Center) { content() }
  }
}

/**
 * `PreviewWrapperProvider` for the **rectangular** Wear widget shape: clips the widget to a rounded
 * rectangle, the ideal frame for wide widget footprints. Sibling of [SquircleWidgetWrapper].
 */
class RectangularWidgetWrapper : PreviewWrapperProvider {
  @Composable
  override fun Wrap(content: @Composable () -> Unit) {
    Box(modifier = Modifier.clip(RoundedCornerShape(28.dp)), contentAlignment = Alignment.Center) {
      content()
    }
  }
}
