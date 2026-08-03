@file:Suppress("RestrictedApiAndroidX")

package ee.schimke.composeai.rcplayer.compat

import androidx.compose.remote.core.WireBuffer
import androidx.compose.remote.core.operations.DrawRect
import androidx.compose.remote.core.operations.Header
import androidx.compose.remote.core.operations.PaintData
import androidx.compose.remote.core.operations.TextData
import androidx.compose.remote.core.operations.TouchExpression
import androidx.compose.remote.core.operations.Utils
import androidx.compose.remote.core.operations.layout.CanvasContent
import androidx.compose.remote.core.operations.layout.ContainerEnd
import androidx.compose.remote.core.operations.layout.LayoutComponentContent
import androidx.compose.remote.core.operations.layout.RootLayoutComponent
import androidx.compose.remote.core.operations.layout.managers.CanvasLayout
import androidx.compose.remote.core.operations.layout.managers.ColumnLayout
import androidx.compose.remote.core.operations.layout.modifiers.DimensionModifierOperation
import androidx.compose.remote.core.operations.layout.modifiers.HeightModifierOperation
import androidx.compose.remote.core.operations.layout.modifiers.ScrollModifierOperation
import androidx.compose.remote.core.operations.layout.modifiers.WidthModifierOperation
import androidx.compose.remote.core.operations.paint.PaintBundle
import androidx.compose.remote.core.semantics.AccessibleComponent
import androidx.compose.remote.core.semantics.CoreSemantics
import java.io.File

/** AndroidX-authored browser fixture for a real overflowing vertical scroll container. */
public fun main(args: Array<String>) {
  val output = File(requireNotNull(args.firstOrNull()) { "output path required" })
  val buffer = WireBuffer()
  Header.apply(buffer, 80, 40, 1f, 0L)
  TextData.apply(buffer, 100, "Scrollable preview")
  RootLayoutComponent.apply(buffer, 1)
  LayoutComponentContent.apply(buffer, 2)
  ColumnLayout.apply(buffer, 3, 30, ColumnLayout.START, ColumnLayout.TOP, 0f)
  exactWidth(buffer, 80f)
  exactHeight(buffer, 40f)
  CoreSemantics.apply(
    buffer,
    100,
    AccessibleComponent.Role.CAROUSEL.ordinal.toByte(),
    0,
    0,
    AccessibleComponent.Mode.SET.ordinal,
    true,
    false,
  )
  ScrollModifierOperation.apply(buffer, 0, Utils.asNan(41), Utils.asNan(42), Utils.asNan(43))
  TouchExpression.apply(
    buffer,
    41,
    0f,
    0f,
    Utils.asNan(42),
    Utils.asNan(44),
    0,
    floatArrayOf(Utils.asNan(14)),
    TouchExpression.STOP_INSTANTLY,
    floatArrayOf(),
    floatArrayOf(),
  )
  ContainerEnd.apply(buffer) // scroll
  LayoutComponentContent.apply(buffer, 4)
  canvas(buffer, 5, 50, 0xffff0000.toInt())
  canvas(buffer, 6, 60, 0xff0000ff.toInt())
  ContainerEnd.apply(buffer) // column content
  ContainerEnd.apply(buffer) // column
  ContainerEnd.apply(buffer) // root content
  ContainerEnd.apply(buffer) // root
  output.parentFile.mkdirs()
  output.writeBytes(buffer.buffer.copyOf(buffer.size()))
}

private fun canvas(buffer: WireBuffer, componentId: Int, animationId: Int, color: Int) {
  CanvasLayout.apply(buffer, componentId, animationId)
  exactWidth(buffer, 80f)
  exactHeight(buffer, 40f)
  LayoutComponentContent.apply(buffer, componentId + 100)
  CanvasContent.apply(buffer, componentId + 200)
  PaintData.apply(
    buffer,
    PaintBundle().apply {
      setColor(color)
      setStyle(PaintBundle.STYLE_FILL)
    },
  )
  DrawRect.apply(buffer, 0f, 0f, 80f, 40f)
  ContainerEnd.apply(buffer)
  ContainerEnd.apply(buffer)
  ContainerEnd.apply(buffer)
}

private fun exactWidth(buffer: WireBuffer, value: Float) {
  WidthModifierOperation.apply(buffer, DimensionModifierOperation.Type.EXACT.ordinal, value)
}

private fun exactHeight(buffer: WireBuffer, value: Float) {
  HeightModifierOperation.apply(buffer, DimensionModifierOperation.Type.EXACT.ordinal, value)
}
