@file:Suppress("RestrictedApiAndroidX", "INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package ee.schimke.composeai.wear.preview

import android.content.Context
import android.os.Bundle
import androidx.compose.remote.creation.compose.capture.captureSingleRemoteDocument
import androidx.compose.remote.creation.compose.capture.createCreationDisplayInfo
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteOffset
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.drawWithContent
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.RemotePaint
import androidx.compose.remote.creation.compose.state.RemoteState
import androidx.compose.remote.creation.compose.state.asRemoteDp
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.glance.wear.GlanceWearProfiles
import androidx.glance.wear.WearWidgetBrush
import androidx.glance.wear.WearWidgetData
import androidx.glance.wear.color
import androidx.glance.wear.core.WearWidgetParams
import androidx.glance.wear.core.WearWidgetRawContent
import androidx.glance.wear.isEmpty

/**
 * Upstream's `WearWidgetDocument`, with the host frame left to the device.
 *
 * Upstream's container (`androidx.glance.wear.composable.WearWidgetContainer`) writes the host's
 * padding and corner radius into the document as constants at capture time, so a document captured
 * for one host shape — a squircle's 8dp insets and 26dp corners — keeps them when it plays in
 * another, such as a round host's 35dp/16dp stadium. This one declares them as the named floats in
 * [HostFrame] instead, each defaulting to the [params] it was captured with, so a device that plays
 * the document can write in its own host's values (`hardcodeNamedValues` in `rc-player-edit`, then
 * `adaptTo` to fold them into the literals a widget player reads) and one capture fits every host.
 * Played as it is, it draws exactly what upstream's capture would.
 *
 * Everything else is upstream's: the background brush chain drawn into the rounded rect (default
 * `surfaceContainerLow` when [background] is empty), the content inset by the padding, and the
 * `WEAR_WIDGETS` capture profile for [params]' renderer version.
 */
class HostFramedWearWidgetDocument(
  private val background: WearWidgetBrush,
  private val content: @RemoteComposable @Composable () -> Unit,
) : WearWidgetData {

  override suspend fun captureRawContent(
    context: Context,
    params: WearWidgetParams,
    isInspectionMode: Boolean,
  ): WearWidgetRawContent {
    val density = context.resources.displayMetrics.density
    val document =
      captureSingleRemoteDocument(
        context = context,
        creationDisplayInfo =
          createCreationDisplayInfo(
            context = context,
            size =
              Size(
                width = (params.widthDp * density).toInt().toFloat(),
                height = (params.heightDp * density).toInt().toFloat(),
              ),
            isInspectionMode = isInspectionMode,
          ),
        profile = GlanceWearProfiles.wearWidgets(params.rendererVersion.supportedOperations),
      ) {
        HostFramedWearWidgetContainer(params, background, content)
      }
    // Upstream's extras: the layout's pending intents, by key.
    val pendingIntents =
      Bundle().apply {
        document.pendingIntents.forEach { key, intent -> putParcelable("$key", intent) }
      }
    return WearWidgetRawContent(
      rcDocument = document.bytes,
      extras = Bundle().apply { putParcelable(PENDING_INTENT_KEY, pendingIntents) },
    )
  }

  /**
   * The host frame values a [HostFramedWearWidgetDocument] declares, in dp, each a `USER` float
   * named [valueName] (`USER:<valueName>` in the document).
   */
  enum class HostFrame(val valueName: String) {
    HorizontalPadding("wear.widget.horizontalPaddingDp"),
    VerticalPadding("wear.widget.verticalPaddingDp"),
    CornerRadius("wear.widget.cornerRadiusDp");

    /** This value in [params]: what a device writes in for its own host. */
    fun of(params: WearWidgetParams): Float =
      when (this) {
        HorizontalPadding -> params.horizontalPaddingDp
        VerticalPadding -> params.verticalPaddingDp
        CornerRadius -> params.cornerRadiusDp
      }

    companion object {
      /** Every host frame value of [params], by name: the map a device hardcodes. */
      fun valuesOf(params: WearWidgetParams): Map<String, Float> = entries.associate {
        it.valueName to it.of(params)
      }
    }
  }

  private companion object {
    /** Upstream's `WearWidgetCapture.PENDING_INTENT_KEY`, which the host reads the intents from. */
    const val PENDING_INTENT_KEY = "pending_intents"
  }
}

// Forked from androidx.wear.compose.material3.ColorScheme.surfaceContainerLow, as upstream does.
private val surfaceContainerLowColor: RemoteColor = Color(red = 39, green = 36, blue = 48).rc

/** Upstream's `WearWidgetContainer`, its padding and corner radius the named [HostFrame] values. */
@RemoteComposable
@Composable
private fun HostFramedWearWidgetContainer(
  params: WearWidgetParams,
  background: WearWidgetBrush,
  content: @RemoteComposable @Composable () -> Unit,
) {
  fun named(frame: HostFramedWearWidgetDocument.HostFrame): RemoteFloat =
    RemoteFloat.createNamedRemoteFloat(frame.valueName, frame.of(params), RemoteState.Domain.User)
  val horizontalPadding = named(HostFramedWearWidgetDocument.HostFrame.HorizontalPadding)
  val verticalPadding = named(HostFramedWearWidgetDocument.HostFrame.VerticalPadding)
  val cornerRadius = named(HostFramedWearWidgetDocument.HostFrame.CornerRadius).asRemoteDp()
  val backgroundWithDefault =
    if (background.isEmpty()) WearWidgetBrush.color(surfaceContainerLowColor) else background
  RemoteBox(
    modifier =
      RemoteModifier.fillMaxSize().drawWithContent {
        val cornerRadiusOffset = RemoteOffset(cornerRadius.toPx(), cornerRadius.toPx())
        val paint = RemotePaint()
        backgroundWithDefault.foldIn(Unit) { _, element ->
          with(element.brush) { applyTo(paint, size) }
          drawRoundRect(paint = paint, cornerRadius = cornerRadiusOffset)
        }
        drawContent()
      }
  ) {
    RemoteBox(
      modifier =
        RemoteModifier.fillMaxSize()
          .padding(
            horizontal = horizontalPadding.asRemoteDp(),
            vertical = verticalPadding.asRemoteDp(),
          ),
      content = content,
    )
  }
}
