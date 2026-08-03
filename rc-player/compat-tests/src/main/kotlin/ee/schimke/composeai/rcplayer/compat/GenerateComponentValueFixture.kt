@file:Suppress("RestrictedApiAndroidX")

package ee.schimke.composeai.rcplayer.compat

import androidx.compose.remote.core.WireBuffer
import androidx.compose.remote.core.operations.ComponentValue
import androidx.compose.remote.core.operations.DrawRect
import androidx.compose.remote.core.operations.Header
import androidx.compose.remote.core.operations.PaintData
import androidx.compose.remote.core.operations.Utils
import androidx.compose.remote.core.operations.layout.ContainerEnd
import androidx.compose.remote.core.operations.layout.LayoutComponentContent
import androidx.compose.remote.core.operations.layout.RootLayoutComponent
import androidx.compose.remote.core.operations.layout.managers.CanvasLayout
import androidx.compose.remote.core.operations.layout.modifiers.DimensionModifierOperation
import androidx.compose.remote.core.operations.layout.modifiers.HeightModifierOperation
import androidx.compose.remote.core.operations.layout.modifiers.OffsetModifierOperation
import androidx.compose.remote.core.operations.layout.modifiers.WidthModifierOperation
import androidx.compose.remote.core.operations.paint.PaintBundle
import java.io.File

/** Alpha16 AndroidX-authored browser fixture whose drawing depends on ComponentValue geometry. */
public fun main(args: Array<String>) {
  val output = File(requireNotNull(args.firstOrNull()) { "output path required" })
  val buffer = WireBuffer()
  Header.apply(buffer, 80, 60, 1f, 0L)
  RootLayoutComponent.apply(buffer, -2)
  LayoutComponentContent.apply(buffer, -3)
  CanvasLayout.apply(buffer, -4, 40)
  WidthModifierOperation.apply(buffer, DimensionModifierOperation.Type.EXACT.ordinal, 40f)
  HeightModifierOperation.apply(buffer, DimensionModifierOperation.Type.EXACT.ordinal, 30f)
  OffsetModifierOperation.apply(buffer, 7f, 9f)
  LayoutComponentContent.apply(buffer, -6)
  ComponentValue.apply(buffer, ComponentValue.WIDTH, -6, 42)
  ComponentValue.apply(buffer, ComponentValue.HEIGHT, -6, 43)
  val paint = PaintBundle().apply { setColor(0xff00ff00.toInt()) }
  PaintData.apply(buffer, paint)
  DrawRect.apply(buffer, 0f, 0f, Utils.asNan(42), Utils.asNan(43))
  ContainerEnd.apply(buffer) // layout content
  ContainerEnd.apply(buffer) // canvas layout
  ContainerEnd.apply(buffer) // root content
  ContainerEnd.apply(buffer) // root layout

  output.parentFile?.mkdirs()
  output.writeBytes(buffer.buffer.copyOf(buffer.size()))
}
