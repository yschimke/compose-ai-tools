@file:Suppress("RestrictedApiAndroidX")

package ee.schimke.composeai.rcplayer.compat

import androidx.compose.remote.core.WireBuffer
import androidx.compose.remote.core.operations.BitmapData
import androidx.compose.remote.core.operations.ColorAttribute
import androidx.compose.remote.core.operations.ColorConstant
import androidx.compose.remote.core.operations.ColorExpression
import androidx.compose.remote.core.operations.ColorTheme
import androidx.compose.remote.core.operations.DataDynamicListFloat
import androidx.compose.remote.core.operations.DataListIds
import androidx.compose.remote.core.operations.DrawBitmap
import androidx.compose.remote.core.operations.DrawBitmapInt
import androidx.compose.remote.core.operations.DrawBitmapScaled
import androidx.compose.remote.core.operations.DrawCircle
import androidx.compose.remote.core.operations.DrawLine
import androidx.compose.remote.core.operations.DrawPath
import androidx.compose.remote.core.operations.DrawRect
import androidx.compose.remote.core.operations.DrawText
import androidx.compose.remote.core.operations.DrawTextOnPath
import androidx.compose.remote.core.operations.FloatExpression
import androidx.compose.remote.core.operations.FloatFunctionCall
import androidx.compose.remote.core.operations.FloatFunctionDefine
import androidx.compose.remote.core.operations.Header
import androidx.compose.remote.core.operations.ImageAttribute
import androidx.compose.remote.core.operations.IntegerExpression
import androidx.compose.remote.core.operations.PaintData
import androidx.compose.remote.core.operations.PathData
import androidx.compose.remote.core.operations.PathExpression
import androidx.compose.remote.core.operations.RootContentBehavior
import androidx.compose.remote.core.operations.TextAttribute
import androidx.compose.remote.core.operations.TextData
import androidx.compose.remote.core.operations.TextLookupInt
import androidx.compose.remote.core.operations.TextMeasure
import androidx.compose.remote.core.operations.UpdateDynamicFloatList
import androidx.compose.remote.core.operations.Utils
import androidx.compose.remote.core.operations.layout.ContainerEnd
import androidx.compose.remote.core.operations.paint.PaintBundle
import androidx.compose.remote.core.operations.utilities.AnimatedFloatExpression
import androidx.compose.remote.core.operations.utilities.IntegerExpressionEvaluator
import java.io.File
import java.util.Base64

/** Produces a visual smoke fixture exclusively through AndroidX Java writer APIs. */
public fun main(args: Array<String>) {
  val output = File(requireNotNull(args.firstOrNull()) { "output path required" })
  val buffer = WireBuffer()
  Header.apply(buffer, 320, 180, 1f, 0L)
  RootContentBehavior.apply(
    buffer,
    RootContentBehavior.NONE,
    RootContentBehavior.ALIGNMENT_CENTER,
    RootContentBehavior.SIZING_SCALE,
    RootContentBehavior.SCALE_FIT,
  )

  val background =
    PaintBundle().apply {
      setColor(0xfff6f2ff.toInt())
      setStyle(PaintBundle.STYLE_FILL)
    }
  PaintData.apply(buffer, background)
  DrawRect.apply(buffer, 0f, 0f, 320f, 180f)

  val accent =
    PaintBundle().apply {
      setColor(0xff6750a4.toInt())
      setStyle(PaintBundle.STYLE_FILL)
    }
  PaintData.apply(buffer, accent)
  DrawRect.apply(buffer, 36f, 34f, 284f, 146f)

  val circle =
    PaintBundle().apply {
      setColor(0xffffd8e4.toInt())
      setStyle(PaintBundle.STYLE_FILL)
    }
  PaintData.apply(buffer, circle)
  FloatExpression.apply(buffer, 95, floatArrayOf(20f, 18f, AnimatedFloatExpression.ADD), null)
  DrawCircle.apply(buffer, 160f, 90f, Utils.asNan(95))

  val line =
    PaintBundle().apply {
      setColor(0xff21005d.toInt())
      setStyle(PaintBundle.STYLE_STROKE)
      setStrokeWidth(6f)
    }
  PaintData.apply(buffer, line)
  DrawLine.apply(buffer, 112f, 90f, 208f, 90f)

  val pathPaint =
    PaintBundle().apply {
      setColor(0xffb8f397.toInt())
      setStyle(PaintBundle.STYLE_FILL)
    }
  PaintData.apply(buffer, pathPaint)
  PathData.apply(
    buffer,
    90,
    floatArrayOf(
      PathData.MOVE_NAN,
      50f,
      52f,
      PathData.LINE_NAN,
      0f,
      0f,
      72f,
      88f,
      PathData.LINE_NAN,
      0f,
      0f,
      28f,
      88f,
      PathData.CLOSE_NAN,
      PathData.DONE_NAN,
    ),
  )
  DrawPath.apply(buffer, 90)

  val expressionPaint =
    PaintBundle().apply {
      setColor(0xffffd8e4.toInt())
      setStyle(PaintBundle.STYLE_STROKE)
      setStrokeWidth(5f)
    }
  PaintData.apply(buffer, expressionPaint)
  PathExpression.apply(
    buffer,
    94,
    floatArrayOf(AnimatedFloatExpression.VAR1),
    floatArrayOf(
      AnimatedFloatExpression.VAR1,
      .05f,
      AnimatedFloatExpression.MUL,
      AnimatedFloatExpression.SIN,
      20f,
      AnimatedFloatExpression.MUL,
      120f,
      AnimatedFloatExpression.ADD,
    ),
    80f,
    240f,
    17f,
    0,
  )
  DrawPath.apply(buffer, 94)

  TextData.apply(buffer, 91, "CMP WASM")
  val textPaint =
    PaintBundle().apply {
      setColor(0xffffffff.toInt())
      setStyle(PaintBundle.STYLE_FILL)
      setTextSize(18f)
      setTextStyle(1, 600, false)
    }
  PaintData.apply(buffer, textPaint)
  TextMeasure.apply(buffer, 92, 91, TextMeasure.MEASURE_WIDTH)
  TextData.apply(buffer, 93, "PATH")
  TextAttribute.apply(buffer, 95, 93, TextAttribute.TEXT_LENGTH)
  FloatExpression.apply(
    buffer,
    96,
    floatArrayOf(Utils.asNan(95), 2f, AnimatedFloatExpression.MUL),
    null,
  )
  DrawCircle.apply(buffer, 270f, 32f, Utils.asNan(96))
  val pixel =
    Base64.getDecoder()
      .decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACAQMAAABIeJ9nAAAAIGNIUk0AAHomAACAhAAA+gAAAIDoAAB1MAAA6mAAADqYAAAXcJy6UTwAAAAGUExURf8Abv///5rktCEAAAABYktHRAH/Ai3eAAAAB3RJTUUH6ggCFygueZAHngAAACV0RVh0ZGF0ZTpjcmVhdGUAMjAyNi0wOC0wMlQyMzo0MDo0NiswMDowMPuL5LUAAAAldEVYdGRhdGU6bW9kaWZ5ADIwMjYtMDgtMDJUMjM6NDA6NDYrMDA6MDCK1lwJAAAAKHRFWHRkYXRlOnRpbWVzdGFtcAAyMDI2LTA4LTAyVDIzOjQwOjQ2KzAwOjAw3cN91gAAAAxJREFUCNdjYGBgAAAABAABJzQnCgAAAABJRU5ErkJggg=="
      )
  BitmapData.apply(buffer, 97, 2, 2, pixel)
  DrawBitmap.apply(buffer, 97, 270f, 50f, 300f, 80f, 0)
  DrawBitmapInt.apply(buffer, 97, 0, 0, 2, 2, 240, 85, 265, 110, 0)
  DrawBitmapScaled.apply(
    buffer,
    97,
    0f,
    0f,
    2f,
    2f,
    270f,
    85f,
    310f,
    105f,
    DrawBitmapScaled.SCALE_FIT,
    1f,
    0,
  )
  val rgba =
    byteArrayOf(
      0x00,
      0x80.toByte(),
      0xff.toByte(),
      0xff.toByte(),
      0x00,
      0x80.toByte(),
      0xff.toByte(),
      0xff.toByte(),
      0x00,
      0x80.toByte(),
      0xff.toByte(),
      0xff.toByte(),
      0x00,
      0x80.toByte(),
      0xff.toByte(),
      0xff.toByte(),
    )
  BitmapData.apply(
    buffer,
    98,
    BitmapData.TYPE_RAW8888,
    2.toShort(),
    BitmapData.ENCODING_INLINE,
    2.toShort(),
    rgba,
  )
  DrawBitmap.apply(buffer, 98, 200f, 85f, 225f, 110f, 0)
  BitmapData.apply(
    buffer,
    99,
    BitmapData.TYPE_RAW8,
    2.toShort(),
    BitmapData.ENCODING_INLINE,
    2.toShort(),
    byteArrayOf(-1, -1, -1, -1),
  )
  DrawBitmap.apply(buffer, 99, 210f, 115f, 235f, 140f, 0)
  ImageAttribute.apply(buffer, 100, 98, ImageAttribute.IMAGE_WIDTH, null)
  FloatExpression.apply(
    buffer,
    101,
    floatArrayOf(
      Utils.asNan(100),
      10f,
      AnimatedFloatExpression.MUL,
      10f,
      AnimatedFloatExpression.ADD,
    ),
    null,
  )
  DrawBitmap.apply(buffer, 98, 10f, 10f, Utils.asNan(101), 30f, 0)
  DrawTextOnPath.apply(buffer, 93, 94, 0f, -6f)
  DrawText.apply(buffer, 91, 0, -1, 0, -1, 112f, 58f, false)
  ColorConstant.apply(buffer, 104, 0xff4080c0.toInt())
  ColorAttribute.apply(buffer, 105, 104, ColorAttribute.COLOR_BRIGHTNESS)
  FloatExpression.apply(
    buffer,
    106,
    floatArrayOf(Utils.asNan(105), 10f, AnimatedFloatExpression.MUL),
    null,
  )
  val colorAttributePaint =
    PaintBundle().apply {
      setColor(0xff0061a4.toInt())
      setStyle(PaintBundle.STYLE_FILL)
    }
  PaintData.apply(buffer, colorAttributePaint)
  DrawCircle.apply(buffer, 45f, 20f, Utils.asNan(106))
  ColorExpression.apply(
    buffer,
    107,
    ColorExpression.COLOR_COLOR_INTERPOLATE.toInt(),
    0xffff0000.toInt(),
    0xff0000ff.toInt(),
    .5f,
  )
  val expressionColorPaint =
    PaintBundle().apply {
      setColorId(107)
      setStyle(PaintBundle.STYLE_FILL)
    }
  PaintData.apply(buffer, expressionColorPaint)
  DrawRect.apply(buffer, 55f, 10f, 75f, 30f)
  ColorTheme.apply(buffer, 108, 0, 0.toShort(), 0.toShort(), 0xffffcc00.toInt(), 0xff006e1c.toInt())
  val themeColorPaint =
    PaintBundle().apply {
      setColorId(108)
      setStyle(PaintBundle.STYLE_FILL)
    }
  PaintData.apply(buffer, themeColorPaint)
  DrawRect.apply(buffer, 80f, 10f, 100f, 30f)

  // The selected label is driven by an AndroidX-authored integer expression (3 - 2 = 1).
  TextData.apply(buffer, 109, "BAD")
  TextData.apply(buffer, 110, "INT")
  DataListIds.apply(buffer, 111, intArrayOf(109, 110))
  IntegerExpression.apply(buffer, 112, 1 shl 2, intArrayOf(3, 2, IntegerExpressionEvaluator.I_SUB))
  TextLookupInt.apply(buffer, 113, 111, 112)
  val integerExpressionPaint =
    PaintBundle().apply {
      setColor(0xff21005d.toInt())
      setStyle(PaintBundle.STYLE_FILL)
      setTextSize(16f)
      setTextStyle(1, 700, false)
    }
  PaintData.apply(buffer, integerExpressionPaint)
  DrawText.apply(buffer, 113, 0, -1, 0, -1, 106f, 27f, false)

  val dynamicListId = 0x200072
  DataDynamicListFloat.apply(buffer, dynamicListId, 1f)
  UpdateDynamicFloatList.apply(buffer, dynamicListId, 0f, 8f)
  FloatExpression.apply(
    buffer,
    114,
    floatArrayOf(Utils.asNan(dynamicListId), 0f, AnimatedFloatExpression.A_DEREF),
    null,
  )
  val dynamicListPaint =
    PaintBundle().apply {
      setColor(0xffff6f00.toInt())
      setStyle(PaintBundle.STYLE_FILL)
    }
  PaintData.apply(buffer, dynamicListPaint)
  DrawCircle.apply(buffer, 160f, 20f, Utils.asNan(114))

  val functionId = 0x400073
  FloatFunctionDefine.apply(buffer, functionId, intArrayOf(115, 116))
  FloatExpression.apply(
    buffer,
    117,
    floatArrayOf(Utils.asNan(115), Utils.asNan(116), AnimatedFloatExpression.ADD),
    null,
  )
  ContainerEnd.apply(buffer)
  FloatFunctionCall.apply(buffer, functionId, floatArrayOf(5f, 4f))
  val functionPaint =
    PaintBundle().apply {
      setColor(0xff00a6a6.toInt())
      setStyle(PaintBundle.STYLE_FILL)
    }
  PaintData.apply(buffer, functionPaint)
  DrawCircle.apply(buffer, 185f, 20f, Utils.asNan(117))

  output.parentFile.mkdirs()
  output.writeBytes(buffer.buffer.copyOf(buffer.size()))
}
