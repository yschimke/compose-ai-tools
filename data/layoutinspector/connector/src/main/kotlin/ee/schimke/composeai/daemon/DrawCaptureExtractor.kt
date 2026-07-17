package ee.schimke.composeai.daemon

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawContext
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ModifierInfo
import androidx.compose.ui.platform.InspectableValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorVectorGraphic
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorVectorPath
import kotlin.math.cos
import kotlin.math.sin

/**
 * Captures the chrome a node draws **imperatively** (a `Slider` track/thumb, a determinate progress
 * bar/arc, a `Checkbox` box+tick, a `RadioButton` ring+dot) by re-invoking its draw lambda against
 * a recording [DrawScope] and translating the primitive calls into editable SVG `<path>`s — the
 * same [LayoutInspectorVectorGraphic] an `Icon`'s `ImageVector` produces, so it rides the existing
 * vector-export pipeline instead of the opaque raster crop.
 *
 * The draw lambda is read from the draw modifier the same way the token resolver reads container
 * tokens: preferring the modifier's [InspectableValue] projection (`properties["onDraw"]`, the
 * public inspector contract) and falling back to reflecting the element's backing `Function1` field
 * only when the inspector info isn't populated (as it isn't for some foundation elements on skiko).
 *
 * The capture is faithful, not ported: the re-invoked lambda draws with the component's *own*
 * resolved colours and its settled value/state (captured in the lambda's closure), so the vectors
 * match whatever the render actually drew — any theme, any Compose version. Anything the recorder
 * can't represent (a transform block, `drawImage`, `drawIntoCanvas`) throws and the node falls back
 * to the raster crop, exactly as before — so a control is never mis-drawn.
 */
internal object DrawCaptureExtractor {

  /** The draw modifiers whose lambda paints chrome the token export can't otherwise see. */
  private val DRAW_MODIFIER_NAMES = setOf("drawBehind", "drawWithContent", "drawWithCache")
  private val LAMBDA_PROPERTY_NAMES = setOf("onDraw", "onBuildDrawCache")

  fun extract(
    modifiers: List<ModifierInfo>,
    width: Int,
    height: Int,
    density: Float,
  ): LayoutInspectorVectorGraphic? {
    if (width <= 0 || height <= 0) return null
    for (info in modifiers) {
      val onDraw = drawLambda(info.modifier) ?: continue
      captureDraw(onDraw, width, height, density)?.let {
        return it
      }
    }
    return null
  }

  /**
   * Re-invokes [onDraw] against a recording [DrawScope] sized to [width]×[height] and returns the
   * captured primitives as a [LayoutInspectorVectorGraphic], or null when nothing was drawn or the
   * lambda used something the recorder can't represent (a transform block, `drawImage`, …).
   */
  internal fun captureDraw(
    onDraw: DrawScope.() -> Unit,
    width: Int,
    height: Int,
    density: Float,
  ): LayoutInspectorVectorGraphic? {
    val rec = RecordingDrawScope(width.toFloat(), height.toFloat(), density)
    val ok = runCatching { rec.onDraw() }.isSuccess
    if (!ok || rec.paths.isEmpty()) return null
    return LayoutInspectorVectorGraphic(
      viewportWidth = width.toFloat(),
      viewportHeight = height.toFloat(),
      paths = rec.paths.toList(),
    )
  }

  /**
   * The `DrawScope.() -> Unit` a draw modifier paints with — from its [InspectableValue] projection
   * first, else a reflected `Function1` backing field. `drawWithCache`'s `onBuildDrawCache` returns
   * a `DrawResult` rather than being a draw lambda, so it isn't re-invokable here and is skipped.
   */
  @Suppress("UNCHECKED_CAST")
  private fun drawLambda(modifier: Any): (DrawScope.() -> Unit)? {
    val inspectable = modifier as? InspectableValue
    val name = inspectable?.nameFallback ?: modifier.javaClass.simpleName
    val looksLikeDraw =
      DRAW_MODIFIER_NAMES.any { name.contains(it, ignoreCase = true) } ||
        modifier.javaClass.simpleName.let { s ->
          s.contains("DrawBehind") || s.contains("DrawWithContent")
        }
    if (!looksLikeDraw) return null
    // Prefer the public inspector projection (properties["onDraw"]).
    inspectable
      ?.inspectableElements
      ?.firstOrNull { it.name in LAMBDA_PROPERTY_NAMES }
      ?.value
      ?.let { if (it is Function1<*, *>) return it as DrawScope.() -> Unit }
    // Fall back to the first Function1 backing field (skiko doesn't always populate inspector
    // info).
    val field =
      modifier.javaClass.declaredFields.firstOrNull {
        Function1::class.java.isAssignableFrom(it.type)
      } ?: return null
    field.isAccessible = true
    val fn = field.get(modifier)
    return if (fn is Function1<*, *>) fn as DrawScope.() -> Unit else null
  }

  private fun fmt(v: Float): String {
    val r = Math.round(v * 100) / 100.0
    return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
  }

  private fun argb(color: Color): String = "#%08X".format(color.toArgb())

  /** A recording [DrawScope] that appends each primitive as an SVG `<path>` in node-local px. */
  private class RecordingDrawScope(
    private val w: Float,
    private val h: Float,
    override val density: Float,
  ) : DrawScope {
    val paths = mutableListOf<LayoutInspectorVectorPath>()
    override val fontScale: Float = 1f
    override val layoutDirection: LayoutDirection = LayoutDirection.Ltr

    // A transform block / clip drops to the canvas or transform, which we don't support — accessing
    // either throws, so the whole capture is caught and the node falls back to its raster crop.
    override val drawContext: DrawContext =
      object : DrawContext {
        override var size: Size = Size(w, h)
        override var canvas: Canvas
          get() = throw UnsupportedOperationException("transform/clip not captured")
          set(_) {}

        override val transform
          get() = throw UnsupportedOperationException("transform/clip not captured")
      }

    // A non-solid brush (a gradient / bitmap shader) can't be a flat SVG paint. Abort the whole
    // capture — rather than silently dropping just this op — so a lambda that mixes a gradient with
    // a
    // solid primitive falls back to the faithful raster crop instead of exporting only the part we
    // understood (the all-or-raster guarantee).
    private fun solidColor(brush: Brush): Color =
      (brush as? SolidColor)?.value ?: throw UnsupportedOperationException("non-solid brush")

    // Only a default stroke (butt cap, miter join, no path effect) maps cleanly to an SVG `<path>`.
    // A round/square cap, a bevel/round join, or a dash effect would render as a *different* shape
    // (a round-capped line paints longer, a dashed path becomes solid), so abort → raster crop.
    private fun assertPlainStroke(cap: StrokeCap, join: StrokeJoin, effect: PathEffect?) {
      if (cap != StrokeCap.Butt || join != StrokeJoin.Miter || effect != null) {
        throw UnsupportedOperationException("unsupported stroke style")
      }
    }

    private fun add(d: String, color: Color, style: DrawStyle, alpha: Float) {
      val hex = argb(color)
      if (style is Stroke) {
        assertPlainStroke(style.cap, style.join, style.pathEffect)
        paths.add(
          LayoutInspectorVectorPath(
            pathData = d,
            strokeArgb = hex,
            strokeWidth = style.width,
            strokeAlpha = alpha,
          )
        )
      } else {
        paths.add(LayoutInspectorVectorPath(pathData = d, fillArgb = hex, fillAlpha = alpha))
      }
    }

    private fun rectPath(tl: Offset, size: Size): String {
      val l = tl.x
      val t = tl.y
      val r = tl.x + size.width
      val b = tl.y + size.height
      return "M${fmt(l)},${fmt(t)} H${fmt(r)} V${fmt(b)} H${fmt(l)} Z"
    }

    private fun roundRectPath(tl: Offset, size: Size, cr: CornerRadius): String {
      val l = tl.x
      val t = tl.y
      val w = size.width
      val h = size.height
      val rx = cr.x.coerceIn(0f, w / 2f)
      val ry = cr.y.coerceIn(0f, h / 2f)
      return buildString {
        append("M${fmt(l + rx)},${fmt(t)} ")
        append("H${fmt(l + w - rx)} ")
        append("A${fmt(rx)},${fmt(ry)} 0 0 1 ${fmt(l + w)},${fmt(t + ry)} ")
        append("V${fmt(t + h - ry)} ")
        append("A${fmt(rx)},${fmt(ry)} 0 0 1 ${fmt(l + w - rx)},${fmt(t + h)} ")
        append("H${fmt(l + rx)} ")
        append("A${fmt(rx)},${fmt(ry)} 0 0 1 ${fmt(l)},${fmt(t + h - ry)} ")
        append("V${fmt(t + ry)} ")
        append("A${fmt(rx)},${fmt(ry)} 0 0 1 ${fmt(l + rx)},${fmt(t)} Z")
      }
    }

    private fun ellipsePath(cx: Float, cy: Float, rx: Float, ry: Float): String =
      "M${fmt(cx - rx)},${fmt(cy)} " +
        "A${fmt(rx)},${fmt(ry)} 0 1 0 ${fmt(cx + rx)},${fmt(cy)} " +
        "A${fmt(rx)},${fmt(ry)} 0 1 0 ${fmt(cx - rx)},${fmt(cy)} Z"

    private fun arcPath(
      tl: Offset,
      size: Size,
      startDeg: Float,
      sweepDeg: Float,
      useCenter: Boolean,
    ): String {
      val rx = size.width / 2f
      val ry = size.height / 2f
      val cx = tl.x + rx
      val cy = tl.y + ry
      fun pt(deg: Float): Pair<Float, Float> {
        val a = Math.toRadians(deg.toDouble())
        return (cx + rx * cos(a)).toFloat() to (cy + ry * sin(a)).toFloat()
      }
      val sweep = sweepDeg.coerceIn(-359.999f, 359.999f)
      val (sx, sy) = pt(startDeg)
      val (ex, ey) = pt(startDeg + sweep)
      val largeArc = if (kotlin.math.abs(sweep) > 180f) 1 else 0
      val sweepFlag = if (sweep >= 0f) 1 else 0
      return buildString {
        append("M${fmt(sx)},${fmt(sy)} ")
        append("A${fmt(rx)},${fmt(ry)} 0 $largeArc $sweepFlag ${fmt(ex)},${fmt(ey)}")
        if (useCenter) append(" L${fmt(cx)},${fmt(cy)} Z")
      }
    }

    private fun sampledPath(path: Path): String {
      val pm = PathMeasure()
      pm.setPath(path, false)
      val len = pm.length
      if (len <= 0f) return ""
      val step = 1.5f
      val sb = StringBuilder()
      var d = 0f
      var first = true
      while (d <= len) {
        val p = pm.getPosition(d)
        sb.append(if (first) "M" else " L").append(fmt(p.x)).append(",").append(fmt(p.y))
        first = false
        d += step
      }
      val end = pm.getPosition(len)
      sb.append(" L").append(fmt(end.x)).append(",").append(fmt(end.y))
      return sb.toString()
    }

    override fun drawRect(
      color: Color,
      topLeft: Offset,
      size: Size,
      alpha: Float,
      style: DrawStyle,
      colorFilter: ColorFilter?,
      blendMode: androidx.compose.ui.graphics.BlendMode,
    ) = add(rectPath(topLeft, size), color, style, alpha)

    override fun drawRect(
      brush: Brush,
      topLeft: Offset,
      size: Size,
      alpha: Float,
      style: DrawStyle,
      colorFilter: ColorFilter?,
      blendMode: androidx.compose.ui.graphics.BlendMode,
    ) {
      add(rectPath(topLeft, size), solidColor(brush), style, alpha)
    }

    override fun drawRoundRect(
      color: Color,
      topLeft: Offset,
      size: Size,
      cornerRadius: CornerRadius,
      style: DrawStyle,
      alpha: Float,
      colorFilter: ColorFilter?,
      blendMode: androidx.compose.ui.graphics.BlendMode,
    ) = add(roundRectPath(topLeft, size, cornerRadius), color, style, alpha)

    override fun drawRoundRect(
      brush: Brush,
      topLeft: Offset,
      size: Size,
      cornerRadius: CornerRadius,
      alpha: Float,
      style: DrawStyle,
      colorFilter: ColorFilter?,
      blendMode: androidx.compose.ui.graphics.BlendMode,
    ) {
      add(roundRectPath(topLeft, size, cornerRadius), solidColor(brush), style, alpha)
    }

    override fun drawCircle(
      color: Color,
      radius: Float,
      center: Offset,
      alpha: Float,
      style: DrawStyle,
      colorFilter: ColorFilter?,
      blendMode: androidx.compose.ui.graphics.BlendMode,
    ) = add(ellipsePath(center.x, center.y, radius, radius), color, style, alpha)

    override fun drawCircle(
      brush: Brush,
      radius: Float,
      center: Offset,
      alpha: Float,
      style: DrawStyle,
      colorFilter: ColorFilter?,
      blendMode: androidx.compose.ui.graphics.BlendMode,
    ) {
      add(ellipsePath(center.x, center.y, radius, radius), solidColor(brush), style, alpha)
    }

    override fun drawOval(
      color: Color,
      topLeft: Offset,
      size: Size,
      alpha: Float,
      style: DrawStyle,
      colorFilter: ColorFilter?,
      blendMode: androidx.compose.ui.graphics.BlendMode,
    ) =
      add(
        ellipsePath(
          topLeft.x + size.width / 2,
          topLeft.y + size.height / 2,
          size.width / 2,
          size.height / 2,
        ),
        color,
        style,
        alpha,
      )

    override fun drawOval(
      brush: Brush,
      topLeft: Offset,
      size: Size,
      alpha: Float,
      style: DrawStyle,
      colorFilter: ColorFilter?,
      blendMode: androidx.compose.ui.graphics.BlendMode,
    ) {
      add(
        ellipsePath(
          topLeft.x + size.width / 2,
          topLeft.y + size.height / 2,
          size.width / 2,
          size.height / 2,
        ),
        solidColor(brush),
        style,
        alpha,
      )
    }

    override fun drawArc(
      color: Color,
      startAngle: Float,
      sweepAngle: Float,
      useCenter: Boolean,
      topLeft: Offset,
      size: Size,
      alpha: Float,
      style: DrawStyle,
      colorFilter: ColorFilter?,
      blendMode: androidx.compose.ui.graphics.BlendMode,
    ) = add(arcPath(topLeft, size, startAngle, sweepAngle, useCenter), color, style, alpha)

    override fun drawArc(
      brush: Brush,
      startAngle: Float,
      sweepAngle: Float,
      useCenter: Boolean,
      topLeft: Offset,
      size: Size,
      alpha: Float,
      style: DrawStyle,
      colorFilter: ColorFilter?,
      blendMode: androidx.compose.ui.graphics.BlendMode,
    ) {
      add(
        arcPath(topLeft, size, startAngle, sweepAngle, useCenter),
        solidColor(brush),
        style,
        alpha,
      )
    }

    override fun drawLine(
      color: Color,
      start: Offset,
      end: Offset,
      strokeWidth: Float,
      cap: StrokeCap,
      pathEffect: PathEffect?,
      alpha: Float,
      colorFilter: ColorFilter?,
      blendMode: androidx.compose.ui.graphics.BlendMode,
    ) {
      assertPlainStroke(cap, StrokeJoin.Miter, pathEffect)
      val d = "M${fmt(start.x)},${fmt(start.y)} L${fmt(end.x)},${fmt(end.y)}"
      paths.add(
        LayoutInspectorVectorPath(
          pathData = d,
          strokeArgb = argb(color),
          strokeWidth = strokeWidth,
          strokeAlpha = alpha,
        )
      )
    }

    override fun drawLine(
      brush: Brush,
      start: Offset,
      end: Offset,
      strokeWidth: Float,
      cap: StrokeCap,
      pathEffect: PathEffect?,
      alpha: Float,
      colorFilter: ColorFilter?,
      blendMode: androidx.compose.ui.graphics.BlendMode,
    ) {
      val color = solidColor(brush)
      assertPlainStroke(cap, StrokeJoin.Miter, pathEffect)
      val d = "M${fmt(start.x)},${fmt(start.y)} L${fmt(end.x)},${fmt(end.y)}"
      paths.add(
        LayoutInspectorVectorPath(
          pathData = d,
          strokeArgb = argb(color),
          strokeWidth = strokeWidth,
          strokeAlpha = alpha,
        )
      )
    }

    override fun drawPath(
      path: Path,
      color: Color,
      alpha: Float,
      style: DrawStyle,
      colorFilter: ColorFilter?,
      blendMode: androidx.compose.ui.graphics.BlendMode,
    ) {
      val d = sampledPath(path)
      if (d.isNotEmpty()) add(d, color, style, alpha)
    }

    override fun drawPath(
      path: Path,
      brush: Brush,
      alpha: Float,
      style: DrawStyle,
      colorFilter: ColorFilter?,
      blendMode: androidx.compose.ui.graphics.BlendMode,
    ) {
      val color = solidColor(brush)
      val d = sampledPath(path)
      if (d.isNotEmpty()) add(d, color, style, alpha)
    }

    // --- Unsupported: bitmaps and point clouds can't be vectorised → throw → raster fallback. ---
    override fun drawImage(
      image: ImageBitmap,
      topLeft: Offset,
      alpha: Float,
      style: DrawStyle,
      colorFilter: ColorFilter?,
      blendMode: androidx.compose.ui.graphics.BlendMode,
    ): Unit = throw UnsupportedOperationException("drawImage not captured")

    override fun drawImage(
      image: ImageBitmap,
      srcOffset: IntOffset,
      srcSize: IntSize,
      dstOffset: IntOffset,
      dstSize: IntSize,
      alpha: Float,
      style: DrawStyle,
      colorFilter: ColorFilter?,
      blendMode: androidx.compose.ui.graphics.BlendMode,
      filterQuality: FilterQuality,
    ): Unit = throw UnsupportedOperationException("drawImage not captured")

    @Deprecated("older overload", level = DeprecationLevel.HIDDEN)
    override fun drawImage(
      image: ImageBitmap,
      srcOffset: IntOffset,
      srcSize: IntSize,
      dstOffset: IntOffset,
      dstSize: IntSize,
      alpha: Float,
      style: DrawStyle,
      colorFilter: ColorFilter?,
      blendMode: androidx.compose.ui.graphics.BlendMode,
    ): Unit = throw UnsupportedOperationException("drawImage not captured")

    override fun drawPoints(
      points: List<Offset>,
      pointMode: PointMode,
      color: Color,
      strokeWidth: Float,
      cap: StrokeCap,
      pathEffect: PathEffect?,
      alpha: Float,
      colorFilter: ColorFilter?,
      blendMode: androidx.compose.ui.graphics.BlendMode,
    ): Unit = throw UnsupportedOperationException("drawPoints not captured")

    override fun drawPoints(
      points: List<Offset>,
      pointMode: PointMode,
      brush: Brush,
      strokeWidth: Float,
      cap: StrokeCap,
      pathEffect: PathEffect?,
      alpha: Float,
      colorFilter: ColorFilter?,
      blendMode: androidx.compose.ui.graphics.BlendMode,
    ): Unit = throw UnsupportedOperationException("drawPoints not captured")
  }
}
