package ee.schimke.composeai.daemon

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsPayload
import ee.schimke.composeai.data.layoutinspector.WireframeModel
import ee.schimke.composeai.data.layoutinspector.WireframeStyle
import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * `android.graphics` raster baker for the semantics wireframe — the Android counterpart of
 * [DesktopSemanticsWireframe]. Draws the shared [WireframeModel] (geometry + style from
 * `:data-layoutinspector-core`) onto a [Bitmap] and writes a PNG, so both backends emit a
 * pixel-matching `compose-semantics-wireframe.png`.
 *
 * Needs **no source screenshot** — the wireframe is a pure schematic on a white ground, baked from
 * the semantics tree alone, at 2× density to match the renderer. android.graphics ↔ AWT mapping
 * mirrors how `AccessibilityOverlay` relates to `DesktopAccessibilityOverlay`: `DashPathEffect` for
 * the `clearAndSet` dash, `Paint.measureText` for label fitting, `paint.alpha` for the translucent
 * clickable fill, and `Bitmap.compress(PNG)` through the Okio sink for the write.
 */
object AndroidSemanticsWireframe {

  /** Supersampling factor — matches the renderer's 2× density. */
  private const val SCALE = 2

  fun generate(
    payload: ComposeSemanticsPayload,
    destPng: File,
    padding: Int = 16,
    fileSystem: FileSystem = SystemFileSystem,
  ): File? = generate(WireframeModel.from(payload, padding), destPng, fileSystem)

  fun generate(
    model: WireframeModel,
    destPng: File,
    fileSystem: FileSystem = SystemFileSystem,
  ): File? =
    try {
      val bitmap = render(model)
      destPng.parentFile?.mkdirs()
      fileSystem.write(destPng.path.toPath()) {
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream())
      }
      bitmap.recycle()
      destPng
    } catch (t: Throwable) {
      // A bake failure must not strand the render — the SVG is still written, and a missing PNG
      // degrades to "SVG only" rather than failing the whole data product.
      System.err.println(
        "[compose-wireframe] android bake failed: ${t.javaClass.simpleName}: ${t.message}"
      )
      null
    }

  private fun render(model: WireframeModel): Bitmap {
    val width = (model.width * SCALE).coerceAtLeast(1)
    val height = (model.height * SCALE).coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(opaque(WireframeStyle.ground))
    canvas.scale(SCALE.toFloat(), SCALE.toFloat())

    val textPaint =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = WireframeStyle.fontSize.toFloat()
        typeface = Typeface.SANS_SERIF
      }
    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    val ascent = textPaint.fontMetrics.ascent

    for (box in model.boxes) {
      val x = (box.left + model.tx).toFloat()
      val y = (box.top + model.ty).toFloat()
      val right = x + box.width
      val bottom = y + box.height
      val color = opaque(WireframeStyle.strokeColor(box))

      if (box.clickable) {
        fillPaint.color = opaque(WireframeStyle.clickAccent)
        fillPaint.alpha = (WireframeStyle.clickFillOpacity * 255).toInt()
        canvas.drawRect(x, y, right, bottom, fillPaint)
      }

      strokePaint.color = color
      strokePaint.strokeWidth = WireframeStyle.strokeWidth(box).toFloat()
      strokePaint.pathEffect =
        if (box.clearAndSet) DashPathEffect(WireframeStyle.clearAndSetDash, 0f) else null
      canvas.drawRect(x, y, right, bottom, strokePaint)

      val label = box.label
      if (label != null && box.width > WireframeStyle.fontSize) {
        textPaint.color = color
        val text = fitLabel(label, box.width - 4, textPaint)
        if (text.isNotEmpty()) {
          canvas.drawText(text, x + 2, y - ascent + 1, textPaint)
        }
      }
    }
    return bitmap
  }

  /** Precise label fit using [Paint.measureText] (the model's estimate is only an upper bound). */
  private fun fitLabel(text: String, maxWidthPx: Int, paint: Paint): String {
    if (maxWidthPx <= 0) return ""
    if (paint.measureText(text) <= maxWidthPx) return text
    val ellipsis = "…"
    val ellipsisWidth = paint.measureText(ellipsis)
    if (ellipsisWidth > maxWidthPx) return ""
    var end = text.length
    while (end > 0 && paint.measureText(text, 0, end) + ellipsisWidth > maxWidthPx) end--
    return if (end <= 0) "" else text.substring(0, end) + ellipsis
  }

  /** `0xRRGGBB` → opaque ARGB. */
  private fun opaque(rgb: Int): Int = 0xFF000000.toInt() or (rgb and 0xFFFFFF)
}
