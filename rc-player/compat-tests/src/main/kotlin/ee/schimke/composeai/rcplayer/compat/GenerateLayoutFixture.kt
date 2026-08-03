@file:Suppress("RestrictedApiAndroidX")

package ee.schimke.composeai.rcplayer.compat

import androidx.compose.remote.core.WireBuffer
import androidx.compose.remote.core.operations.BitmapData
import androidx.compose.remote.core.operations.ClickArea
import androidx.compose.remote.core.operations.DrawContent
import androidx.compose.remote.core.operations.DrawRect
import androidx.compose.remote.core.operations.HapticFeedback
import androidx.compose.remote.core.operations.Header
import androidx.compose.remote.core.operations.IntegerExpression
import androidx.compose.remote.core.operations.PaintData
import androidx.compose.remote.core.operations.TextData
import androidx.compose.remote.core.operations.layout.CanvasContent
import androidx.compose.remote.core.operations.layout.CanvasOperations
import androidx.compose.remote.core.operations.layout.ClickModifierOperation
import androidx.compose.remote.core.operations.layout.ContainerEnd
import androidx.compose.remote.core.operations.layout.LayoutComponentContent
import androidx.compose.remote.core.operations.layout.MultiClickModifier
import androidx.compose.remote.core.operations.layout.RootLayoutComponent
import androidx.compose.remote.core.operations.layout.TouchCancelModifierOperation
import androidx.compose.remote.core.operations.layout.TouchDownModifierOperation
import androidx.compose.remote.core.operations.layout.TouchUpModifierOperation
import androidx.compose.remote.core.operations.layout.managers.BoxLayout
import androidx.compose.remote.core.operations.layout.managers.CanvasLayout
import androidx.compose.remote.core.operations.layout.managers.CollapsibleRowLayout
import androidx.compose.remote.core.operations.layout.managers.CoreText
import androidx.compose.remote.core.operations.layout.managers.ImageLayout
import androidx.compose.remote.core.operations.layout.managers.RowLayout
import androidx.compose.remote.core.operations.layout.managers.TextStyle
import androidx.compose.remote.core.operations.layout.modifiers.CollapsiblePriorityModifierOperation
import androidx.compose.remote.core.operations.layout.modifiers.DimensionModifierOperation
import androidx.compose.remote.core.operations.layout.modifiers.HeightModifierOperation
import androidx.compose.remote.core.operations.layout.modifiers.HostActionMetadataOperation
import androidx.compose.remote.core.operations.layout.modifiers.HostActionOperation
import androidx.compose.remote.core.operations.layout.modifiers.HostNamedActionOperation
import androidx.compose.remote.core.operations.layout.modifiers.OffsetModifierOperation
import androidx.compose.remote.core.operations.layout.modifiers.RippleModifierOperation
import androidx.compose.remote.core.operations.layout.modifiers.ValueIntegerExpressionChangeActionOperation
import androidx.compose.remote.core.operations.layout.modifiers.WidthModifierOperation
import androidx.compose.remote.core.operations.paint.PaintBundle
import androidx.compose.remote.core.operations.utilities.IntegerExpressionEvaluator
import androidx.compose.remote.core.semantics.AccessibleComponent
import androidx.compose.remote.core.semantics.CoreSemantics
import java.io.File

/** AndroidX-authored browser fixture for layout, DrawContent, and images. */
public fun main(args: Array<String>) {
  val output = File(requireNotNull(args.firstOrNull()) { "output path required" })
  val buffer = WireBuffer()
  Header.apply(buffer, 320, 180, 1f, 0L)
  BitmapData.apply(
    buffer,
    1000,
    BitmapData.TYPE_RAW8888,
    2,
    BitmapData.ENCODING_INLINE,
    2,
    byteArrayOf(
      0xff.toByte(),
      0x67,
      0x50,
      0xff.toByte(),
      0xb8.toByte(),
      0xf3.toByte(),
      0x97.toByte(),
      0xff.toByte(),
      0xff.toByte(),
      0xd8.toByte(),
      0xe4.toByte(),
      0xff.toByte(),
      0x21,
      0x00,
      0x5d,
      0xff.toByte(),
    ),
  )
  TextData.apply(buffer, 1001, "CMP TEXT + IMAGE")
  TextData.apply(buffer, 1004, "Remote Compose preview")
  TextData.apply(buffer, 1005, "Rendered by Compose Multiplatform")
  TextData.apply(buffer, 1006, "save-preview")
  TextData.apply(buffer, 1007, "browser-fixture")
  TextData.apply(buffer, 1008, "Legacy click area")
  TextData.apply(buffer, 1009, "click-area-meta")
  ClickArea.apply(buffer, 85, 1008, 0f, 0f, 320f, 180f, 1009)
  IntegerExpression.apply(buffer, 31, 1 shl 2, intArrayOf(2, 3, IntegerExpressionEvaluator.I_ADD))
  TextStyle.apply(
    buffer,
    1002,
    0xff21005d.toInt(),
    null,
    22f,
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    false,
    null,
  )
  TextStyle.apply(
    buffer,
    1003,
    null,
    null,
    null,
    null,
    null,
    PaintBundle.FONT_BOLD,
    null,
    null,
    CoreText.TEXT_ALIGN_CENTER,
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    false,
    1002,
  )
  RootLayoutComponent.apply(buffer, 1)
  LayoutComponentContent.apply(buffer, 2)

  BoxLayout.apply(buffer, 3, 30, BoxLayout.CENTER, BoxLayout.CENTER)
  exactWidth(buffer, 320f)
  exactHeight(buffer, 180f)
  CanvasOperations.apply(buffer)
  paint(buffer, 0xfff6f2ff.toInt())
  DrawRect.apply(buffer, 0f, 0f, 320f, 180f)
  DrawContent.apply(buffer)
  paint(buffer, 0xff21005d.toInt(), stroke = true, strokeWidth = 6f)
  DrawRect.apply(buffer, 8f, 8f, 312f, 172f)
  ContainerEnd.apply(buffer)

  LayoutComponentContent.apply(buffer, 4)
  CollapsibleRowLayout.apply(buffer, 5, 50, RowLayout.SPACE_BETWEEN, RowLayout.CENTER, 0f)
  exactWidth(buffer, 150f)
  exactHeight(buffer, 140f)
  OffsetModifierOperation.apply(buffer, 0f, 20f)
  LayoutComponentContent.apply(buffer, 6)
  canvas(
    buffer,
    componentId = 7,
    animationId = 70,
    width = 54f,
    height = 76f,
    color = 0xff6750a4.toInt(),
    priority = 1f,
  )
  canvas(
    buffer,
    componentId = 9,
    animationId = 90,
    width = 54f,
    height = 112f,
    color = 0xffffd8e4.toInt(),
    priority = 3f,
  )
  image(buffer, componentId = 11, animationId = 110, width = 54f, height = 58f, priority = 2f)
  ContainerEnd.apply(buffer) // row content
  ContainerEnd.apply(buffer) // row
  CoreText.apply(
    buffer,
    13,
    130,
    1001,
    0xff000000.toInt(),
    -1,
    36f,
    -1f,
    -1f,
    PaintBundle.FONT_NORMAL,
    400f,
    -1,
    CoreText.TEXT_ALIGN_LEFT,
    CoreText.OVERFLOW_CLIP,
    Int.MAX_VALUE,
    0f,
    0f,
    1f,
    CoreText.BREAK_STRATEGY_SIMPLE,
    CoreText.HYPHENATION_FREQUENCY_NONE,
    CoreText.JUSTIFICATION_MODE_NONE,
    false,
    false,
    null,
    null,
    false,
    0,
    1003,
  )
  exactWidth(buffer, 240f)
  exactHeight(buffer, 36f)
  OffsetModifierOperation.apply(buffer, 0f, -60f)
  CoreSemantics.apply(
    buffer,
    1004,
    AccessibleComponent.Role.IMAGE.ordinal.toByte(),
    1001,
    1005,
    AccessibleComponent.Mode.SET.ordinal,
    true,
    true,
  )
  RippleModifierOperation.apply(buffer)
  TouchDownModifierOperation.apply(buffer)
  HostActionOperation.apply(buffer, 79)
  ContainerEnd.apply(buffer)
  TouchUpModifierOperation.apply(buffer)
  HostActionOperation.apply(buffer, 80)
  ContainerEnd.apply(buffer)
  TouchCancelModifierOperation.apply(buffer)
  HostActionOperation.apply(buffer, 81)
  ContainerEnd.apply(buffer)
  MultiClickModifier.apply(buffer, MultiClickModifier.CLICK_TYPE_SINGLE)
  HapticFeedback.apply(buffer, 12)
  HostActionOperation.apply(buffer, 82)
  ContainerEnd.apply(buffer)
  MultiClickModifier.apply(buffer, MultiClickModifier.CLICK_TYPE_LONG)
  HostActionOperation.apply(buffer, 83)
  ContainerEnd.apply(buffer)
  MultiClickModifier.apply(buffer, MultiClickModifier.CLICK_TYPE_DOUBLE)
  HostActionOperation.apply(buffer, 84)
  ContainerEnd.apply(buffer)
  ClickModifierOperation.apply(buffer)
  ValueIntegerExpressionChangeActionOperation.apply(buffer, 23L, 31L)
  HostNamedActionOperation.apply(buffer, 1006, HostNamedActionOperation.INT_TYPE, 23)
  HostActionMetadataOperation.apply(buffer, 78, 1007)
  HostActionOperation.apply(buffer, 77)
  ContainerEnd.apply(buffer) // click actions
  ContainerEnd.apply(buffer) // text
  ContainerEnd.apply(buffer) // box content
  ContainerEnd.apply(buffer) // box
  ContainerEnd.apply(buffer) // root content
  ContainerEnd.apply(buffer) // root

  output.parentFile.mkdirs()
  output.writeBytes(buffer.buffer.copyOf(buffer.size()))
}

private fun image(
  buffer: WireBuffer,
  componentId: Int,
  animationId: Int,
  width: Float,
  height: Float,
  priority: Float,
) {
  ImageLayout.apply(buffer, componentId, animationId, 1000, 6, 1f)
  exactWidth(buffer, width)
  exactHeight(buffer, height)
  CollapsiblePriorityModifierOperation.apply(buffer, 0, priority)
  ContainerEnd.apply(buffer)
}

private fun canvas(
  buffer: WireBuffer,
  componentId: Int,
  animationId: Int,
  width: Float,
  height: Float,
  color: Int,
  priority: Float,
) {
  CanvasLayout.apply(buffer, componentId, animationId)
  exactWidth(buffer, width)
  exactHeight(buffer, height)
  CollapsiblePriorityModifierOperation.apply(buffer, 0, priority)
  LayoutComponentContent.apply(buffer, componentId + 1)
  CanvasContent.apply(buffer, componentId + 100)
  paint(buffer, color)
  DrawRect.apply(buffer, 0f, 0f, width, height)
  ContainerEnd.apply(buffer) // canvas content
  ContainerEnd.apply(buffer) // layout content
  ContainerEnd.apply(buffer) // canvas layout
}

private fun exactWidth(buffer: WireBuffer, value: Float) {
  WidthModifierOperation.apply(buffer, DimensionModifierOperation.Type.EXACT.ordinal, value)
}

private fun exactHeight(buffer: WireBuffer, value: Float) {
  HeightModifierOperation.apply(buffer, DimensionModifierOperation.Type.EXACT.ordinal, value)
}

private fun paint(
  buffer: WireBuffer,
  color: Int,
  stroke: Boolean = false,
  strokeWidth: Float = 1f,
) {
  val paint =
    PaintBundle().apply {
      setColor(color)
      setStyle(if (stroke) PaintBundle.STYLE_STROKE else PaintBundle.STYLE_FILL)
      if (stroke) setStrokeWidth(strokeWidth)
    }
  PaintData.apply(buffer, paint)
}
