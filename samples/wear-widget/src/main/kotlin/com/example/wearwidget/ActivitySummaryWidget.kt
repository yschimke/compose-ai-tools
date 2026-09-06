// Generated from a Compose UI builder design. Do not edit by hand.
@file:Suppress("RestrictedApi")

package com.example.wearwidget

import android.content.Context
import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteRow
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.glance.wear.GlanceWearWidget
import androidx.glance.wear.WearWidgetBrush
import androidx.glance.wear.WearWidgetData
import androidx.glance.wear.WearWidgetDocument
import androidx.glance.wear.core.WearWidgetParams
import androidx.glance.wear.tooling.preview.SquircleLargeWidgetPreviewParams
import androidx.glance.wear.tooling.preview.WearWidgetPreview
import androidx.glance.wear.verticalGradient
import androidx.wear.compose.remote.material3.RemoteMaterialTheme
import androidx.wear.compose.remote.material3.RemoteText

@RemoteComposable
@Composable
fun ActivitySummaryWidgetContent() {
  RemoteMaterialTheme {
    RemoteBox(
      modifier = RemoteModifier.fillMaxSize(),
      contentAlignment = RemoteAlignment.Center,
    ) {
      RemoteColumn(
        modifier = RemoteModifier.fillMaxWidth(),
        verticalArrangement = RemoteArrangement.spacedBy(2.rdp),
      ) {
        RemoteText(
          text = "TUESDAY".rs,
          modifier = RemoteModifier.fillMaxWidth(),
          color = Color(0xFF9BE7F0).rc,
          fontSize = 12.rsp,
          textAlign = TextAlign.Center,
          maxLines = 1,
        )
        RemoteRow(
          modifier = RemoteModifier.fillMaxWidth(),
          horizontalArrangement = RemoteArrangement.spacedBy(6.rdp),
        ) {
          RemoteText(
            text = "8,412".rs,
            color = RemoteMaterialTheme.colorScheme.onSurface,
            fontSize = 32.rsp,
          )
          RemoteText(text = "steps".rs, color = Color(0xFF9BE7F0).rc, fontSize = 13.rsp)
        }
        RemoteText(
          text = "68% of daily goal".rs,
          modifier = RemoteModifier.fillMaxWidth(),
          color = Color(0xFFB8C8CB).rc,
          fontSize = 11.rsp,
          textAlign = TextAlign.Center,
          maxLines = 1,
        )
      }
    }
  }
}

class ActivitySummaryWidget : GlanceWearWidget() {
  override suspend fun provideWidgetData(
    context: Context,
    params: WearWidgetParams,
  ): WearWidgetData {
    val background =
      WearWidgetBrush.verticalGradient(listOf(Color(0xFF00363D).rc, Color(0xFF004F58).rc))
    return WearWidgetDocument(background = background) { ActivitySummaryWidgetContent() }
  }
}

@Preview(name = "Squircle Preview")
@Composable
fun ActivitySummaryWidgetSquirclePreview(
  @PreviewParameter(SquircleLargeWidgetPreviewParams::class) params: WearWidgetParams
) = WearWidgetPreview(ActivitySummaryWidget(), params)
