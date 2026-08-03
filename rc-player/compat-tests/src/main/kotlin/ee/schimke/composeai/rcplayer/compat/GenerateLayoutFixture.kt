@file:Suppress("RestrictedApiAndroidX")

package ee.schimke.composeai.rcplayer.compat

import androidx.compose.remote.core.WireBuffer
import androidx.compose.remote.core.operations.BitmapData
import androidx.compose.remote.core.operations.DrawContent
import androidx.compose.remote.core.operations.DrawRect
import androidx.compose.remote.core.operations.Header
import androidx.compose.remote.core.operations.PaintData
import androidx.compose.remote.core.operations.layout.CanvasContent
import androidx.compose.remote.core.operations.layout.CanvasOperations
import androidx.compose.remote.core.operations.layout.ContainerEnd
import androidx.compose.remote.core.operations.layout.LayoutComponentContent
import androidx.compose.remote.core.operations.layout.RootLayoutComponent
import androidx.compose.remote.core.operations.layout.managers.BoxLayout
import androidx.compose.remote.core.operations.layout.managers.CanvasLayout
import androidx.compose.remote.core.operations.layout.managers.ImageLayout
import androidx.compose.remote.core.operations.layout.managers.RowLayout
import androidx.compose.remote.core.operations.layout.modifiers.DimensionModifierOperation
import androidx.compose.remote.core.operations.layout.modifiers.HeightModifierOperation
import androidx.compose.remote.core.operations.layout.modifiers.WidthModifierOperation
import androidx.compose.remote.core.operations.paint.PaintBundle
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
  RowLayout.apply(buffer, 5, 50, RowLayout.SPACE_EVENLY, RowLayout.CENTER, 0f)
  exactWidth(buffer, 280f)
  exactHeight(buffer, 140f)
  LayoutComponentContent.apply(buffer, 6)
  canvas(buffer, componentId = 7, animationId = 70, width = 54f, height = 76f, 0xff6750a4.toInt())
  canvas(buffer, componentId = 9, animationId = 90, width = 54f, height = 112f, 0xffffd8e4.toInt())
  image(buffer, componentId = 11, animationId = 110, width = 54f, height = 58f)
  ContainerEnd.apply(buffer) // row content
  ContainerEnd.apply(buffer) // row
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
) {
  ImageLayout.apply(buffer, componentId, animationId, 1000, 6, 1f)
  exactWidth(buffer, width)
  exactHeight(buffer, height)
  ContainerEnd.apply(buffer)
}

private fun canvas(
  buffer: WireBuffer,
  componentId: Int,
  animationId: Int,
  width: Float,
  height: Float,
  color: Int,
) {
  CanvasLayout.apply(buffer, componentId, animationId)
  exactWidth(buffer, width)
  exactHeight(buffer, height)
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
