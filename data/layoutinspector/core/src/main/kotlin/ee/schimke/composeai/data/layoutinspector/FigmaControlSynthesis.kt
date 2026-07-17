package ee.schimke.composeai.data.layoutinspector

import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Synthesises the editable-vector geometry of a Material control whose chrome is drawn
 * *imperatively* — a `Slider`'s track + thumb, a determinate `LinearProgressIndicator` /
 * `CircularProgressIndicator` bar/arc, a `Checkbox`'s box + tick, a `RadioButton`'s ring + dot.
 * Those are painted through a `Canvas`/`drawBehind` the token export can't read, so the figma-svg
 * export previously fell back to an opaque raster crop (which renders blank in any sanitised
 * `<img>` context — GitHub blob view, `file://`). This turns them into real
 * `<rect>`/`<circle>`/`<path>` layers instead.
 *
 * Two inputs make faithful (not guessed) geometry possible:
 * - the control's captured [ComposeSemanticsControl] **state** — the slider value / progress
 *   fraction (`ProgressBarRangeInfo`), the checkbox toggle (`ToggleableState`), the radio selected
 *   flag — which lives in semantics, not in the geometry-only layout tree; and
 * - the render's resolved **theme colours** (a role → `#AARRGGBB` map), so the primary/track/tick
 *   paints match the render in both light and dark rather than a hardcoded palette.
 *
 * The contract is deliberately conservative: [synthesize] returns null (→ the caller keeps the
 * exact raster behaviour) unless the state *and* every theme colour it needs are present. So a
 * capture that predates state/colour wiring, or a control whose colours didn't resolve, is never
 * mis-drawn — it degrades to the faithful raster crop exactly as before.
 *
 * The control *kind* is inferred from the captured state plus the box aspect ratio, not the
 * composable name (which varies — `SliderKt`, a bare `Spacer` for a `drawBehind` bar, a measure
 * policy), keeping this free of the per-component name-matching the rest of the export avoids: a
 * `toggle` ⇒ checkbox, a `selected` ⇒ radio, a `progress` in a square box ⇒ circular, in a tall box
 * ⇒ slider, in a thin wide box ⇒ linear.
 */
internal object FigmaControlSynthesis {

  private enum class Kind {
    CHECKBOX,
    RADIO,
    SLIDER,
    LINEAR_PROGRESS,
    CIRCULAR_PROGRESS,
  }

  /** A resolved theme role colour, or null when the render's scheme didn't carry that role. */
  fun interface ColorResolver {
    fun role(name: String): FigmaSvgColor?
  }

  /**
   * The synthesized control subtree placed on [bounds], or null when [state] + [colors] are
   * insufficient to draw it faithfully (the caller then keeps the raster crop). [name] labels the
   * emitted `<g>`; [density] converts the Material dp specs into the render's px space.
   */
  fun synthesize(
    name: String,
    bounds: LayoutInspectorBounds,
    state: ComposeSemanticsControl,
    colors: ColorResolver,
    density: Float,
  ): FigmaSvgLayer? {
    val w = bounds.right - bounds.left
    val h = bounds.bottom - bounds.top
    if (w <= 0 || h <= 0) return null
    return when (kindOf(state, w, h, density)) {
      Kind.CHECKBOX -> checkbox(name, bounds, state.toggle, colors, density)
      Kind.RADIO -> radio(name, bounds, state.selected == true, colors, density)
      Kind.SLIDER -> slider(name, bounds, state.progress, colors, density)
      Kind.LINEAR_PROGRESS -> linearProgress(name, bounds, state.progress, colors, density)
      Kind.CIRCULAR_PROGRESS -> circularProgress(name, bounds, state.progress, colors, density)
      null -> null
    }
  }

  private fun kindOf(state: ComposeSemanticsControl, w: Int, h: Int, density: Float): Kind? {
    if (state.toggle != null) return Kind.CHECKBOX
    if (state.selected != null) return Kind.RADIO
    if (state.progress == null) return null
    // A value/progress control: distinguish circular (square-ish box) from the two horizontal bars,
    // and a slider (a tall touch box around a thin track) from a bare linear bar, by aspect ratio.
    val squareSlack = px(8.0, density)
    if (kotlin.math.abs(w - h) <= squareSlack) return Kind.CIRCULAR_PROGRESS
    if (w < h) return Kind.CIRCULAR_PROGRESS // a portrait box is never a horizontal bar
    // Horizontal box. A slider carries a tall (touch-sized) box around its 4dp track; a linear
    // indicator's box is barely thicker than the track itself.
    return if (h >= px(16.0, density)) Kind.SLIDER else Kind.LINEAR_PROGRESS
  }

  // --- Checkbox: an 18dp rounded box; checked fills primary + an onPrimary tick, unchecked is a
  // 2dp
  // onSurfaceVariant outline, indeterminate fills primary + an onPrimary dash. ---
  private fun checkbox(
    name: String,
    bounds: LayoutInspectorBounds,
    toggle: String?,
    colors: ColorResolver,
    density: Float,
  ): FigmaSvgLayer? {
    val side = px(18.0, density).coerceAtLeast(1)
    val radius = (2.0 * density).coerceAtLeast(1.0)
    val stroke = (2.0 * density).coerceAtLeast(1.0)
    val box = centered(bounds, side, side)
    val primary = colors.role("primary")
    val onPrimary = colors.role("onPrimary")
    val outline = colors.role("onSurfaceVariant") ?: colors.role("outline")
    return when (toggle) {
      "on" -> {
        if (primary == null || onPrimary == null) return null
        FigmaSvgLayer(
          name = name,
          left = box.left,
          top = box.top,
          right = box.right,
          bottom = box.bottom,
          fill = primary,
          cornerRadiiPx = listOf(radius, radius, radius, radius),
          children = listOf(tick(box, onPrimary, stroke)),
        )
      }
      "indeterminate" -> {
        if (primary == null || onPrimary == null) return null
        FigmaSvgLayer(
          name = name,
          left = box.left,
          top = box.top,
          right = box.right,
          bottom = box.bottom,
          fill = primary,
          cornerRadiiPx = listOf(radius, radius, radius, radius),
          children = listOf(dash(box, onPrimary, stroke)),
        )
      }
      "off" -> {
        if (outline == null) return null
        FigmaSvgLayer(
          name = name,
          left = box.left,
          top = box.top,
          right = box.right,
          bottom = box.bottom,
          stroke = outline,
          strokeWidthPx = stroke,
          cornerRadiiPx = listOf(radius, radius, radius, radius),
        )
      }
      else -> null
    }
  }

  /** The check mark as a stroked polyline in the box's own px viewport (Material's tick path). */
  private fun tick(
    box: LayoutInspectorBounds,
    color: FigmaSvgColor,
    stroke: Double,
  ): FigmaSvgLayer {
    val s = (box.right - box.left).toDouble()
    fun x(f: Double) = fmt(f * s)
    // Three points of the M3 checkmark, as fractions of the box side.
    val d = "M ${x(0.22)} ${x(0.52)} L ${x(0.42)} ${x(0.71)} L ${x(0.78)} ${x(0.30)}"
    return vectorLeaf(box, "Checkmark", d, color, stroke)
  }

  /** The indeterminate dash — a horizontal stroke across the box centre. */
  private fun dash(
    box: LayoutInspectorBounds,
    color: FigmaSvgColor,
    stroke: Double,
  ): FigmaSvgLayer {
    val s = (box.right - box.left).toDouble()
    fun x(f: Double) = fmt(f * s)
    val d = "M ${x(0.25)} ${x(0.5)} L ${x(0.75)} ${x(0.5)}"
    return vectorLeaf(box, "Dash", d, color, stroke)
  }

  // --- Radio: a 20dp ring; selected adds a primary dot, unselected is an onSurfaceVariant ring.
  // ---
  private fun radio(
    name: String,
    bounds: LayoutInspectorBounds,
    selected: Boolean,
    colors: ColorResolver,
    density: Float,
  ): FigmaSvgLayer? {
    val side = px(20.0, density).coerceAtLeast(1)
    val stroke = (2.0 * density).coerceAtLeast(1.0)
    val ringColor = if (selected) colors.role("primary") else colors.role("onSurfaceVariant")
    val ring = centered(bounds, side, side)
    val ringColorResolved = ringColor ?: colors.role("outline") ?: return null
    val children =
      if (selected) {
        val primary = colors.role("primary") ?: return null
        val dot = px(10.0, density).coerceAtLeast(1)
        listOf(centeredCircle(ring, dot, primary, "Dot"))
      } else {
        emptyList()
      }
    return FigmaSvgLayer(
      name = name,
      left = ring.left,
      top = ring.top,
      right = ring.right,
      bottom = ring.bottom,
      stroke = ringColorResolved,
      strokeWidthPx = stroke,
      circle = true,
      children = children,
    )
  }

  // --- Slider: a 4dp track (primary active left of the thumb, surfaceVariant inactive right) with
  // a
  // 20dp primary thumb at the value position. ---
  private fun slider(
    name: String,
    bounds: LayoutInspectorBounds,
    progress: Float?,
    colors: ColorResolver,
    density: Float,
  ): FigmaSvgLayer? {
    val value = (progress ?: return null).coerceIn(0f, 1f)
    val primary = colors.role("primary") ?: return null
    val inactive = colors.role("surfaceVariant") ?: colors.role("secondaryContainer") ?: return null
    val trackH = px(4.0, density).coerceAtLeast(1)
    val thumb = px(20.0, density).coerceAtLeast(trackH)
    val cy = (bounds.top + bounds.bottom) / 2
    val trackTop = cy - trackH / 2
    // The track is inset by the thumb radius so the thumb centre stays inside the box at either
    // end.
    val inset = thumb / 2
    val trackLeft = bounds.left + inset
    val trackRight = bounds.right - inset
    val trackW = (trackRight - trackLeft).coerceAtLeast(0)
    val thumbCx = trackLeft + (value * trackW).roundToInt()
    val r = trackH / 2.0
    val radii = listOf(r, r, r, r)
    val active =
      FigmaSvgLayer(
        name = "ActiveTrack",
        left = trackLeft,
        top = trackTop,
        right = thumbCx,
        bottom = trackTop + trackH,
        fill = primary,
        cornerRadiiPx = radii,
      )
    val rest =
      FigmaSvgLayer(
        name = "InactiveTrack",
        left = thumbCx,
        top = trackTop,
        right = trackRight,
        bottom = trackTop + trackH,
        fill = inactive,
        cornerRadiiPx = radii,
      )
    val knob =
      centeredCircle(LayoutInspectorBounds(thumbCx, cy, thumbCx, cy), thumb, primary, "Thumb")
    return FigmaSvgLayer(
      name = name,
      left = bounds.left,
      top = bounds.top,
      right = bounds.right,
      bottom = bounds.bottom,
      children = listOf(rest, active, knob),
    )
  }

  // --- Linear progress: a full-width surfaceVariant track with a primary active portion. ---
  private fun linearProgress(
    name: String,
    bounds: LayoutInspectorBounds,
    progress: Float?,
    colors: ColorResolver,
    density: Float,
  ): FigmaSvgLayer? {
    val value = (progress ?: return null).coerceIn(0f, 1f)
    val primary = colors.role("primary") ?: return null
    val track = colors.role("surfaceVariant") ?: colors.role("secondaryContainer") ?: return null
    val w = bounds.right - bounds.left
    val r = (bounds.bottom - bounds.top) / 2.0
    val radii = listOf(r, r, r, r)
    val activeRight = bounds.left + (value * w).roundToInt()
    val trackLayer =
      FigmaSvgLayer(
        name = "Track",
        left = bounds.left,
        top = bounds.top,
        right = bounds.right,
        bottom = bounds.bottom,
        fill = track,
        cornerRadiiPx = radii,
      )
    val activeLayer =
      FigmaSvgLayer(
        name = "Progress",
        left = bounds.left,
        top = bounds.top,
        right = activeRight,
        bottom = bounds.bottom,
        fill = primary,
        cornerRadiiPx = radii,
      )
    return FigmaSvgLayer(
      name = name,
      left = bounds.left,
      top = bounds.top,
      right = bounds.right,
      bottom = bounds.bottom,
      children = listOf(trackLayer, activeLayer),
    )
  }

  // --- Circular progress: a primary arc sweeping the progress fraction from the top (M3's
  // determinate indicator draws only the active arc; the default track is transparent). ---
  private fun circularProgress(
    name: String,
    bounds: LayoutInspectorBounds,
    progress: Float?,
    colors: ColorResolver,
    density: Float,
  ): FigmaSvgLayer? {
    val value = (progress ?: return null).coerceIn(0f, 1f)
    val primary = colors.role("primary") ?: return null
    val w = bounds.right - bounds.left
    val h = bounds.bottom - bounds.top
    val stroke = (4.0 * density).coerceAtLeast(1.0)
    val cx = w / 2.0
    val cy = h / 2.0
    val radius = (minOf(w, h) - stroke) / 2.0
    if (radius <= 0.0) return null
    val d = arcPath(cx, cy, radius, value)
    return vectorLeaf(bounds, name, d, primary, stroke)
  }

  /**
   * An SVG arc `d` sweeping [fraction] of a full turn clockwise from the top (12 o'clock), centred
   * at ([cx],[cy]) with [radius], in the layer's own px viewport. A full turn is emitted as two
   * half arcs (a single 360° arc degenerates to a no-op because start == end).
   */
  private fun arcPath(cx: Double, cy: Double, radius: Double, fraction: Float): String {
    fun point(t: Double): Pair<Double, Double> {
      val a = -Math.PI / 2 + t * 2 * Math.PI // -90° = top, increasing angle = clockwise (y-down)
      return (cx + radius * cos(a)) to (cy + radius * sin(a))
    }
    val start = point(0.0)
    if (fraction >= 1f) {
      val mid = point(0.5)
      return "M ${fmt(start.first)} ${fmt(start.second)} " +
        "A ${fmt(radius)} ${fmt(radius)} 0 0 1 ${fmt(mid.first)} ${fmt(mid.second)} " +
        "A ${fmt(radius)} ${fmt(radius)} 0 0 1 ${fmt(start.first)} ${fmt(start.second)}"
    }
    val end = point(fraction.toDouble())
    val largeArc = if (fraction > 0.5f) 1 else 0
    return "M ${fmt(start.first)} ${fmt(start.second)} " +
      "A ${fmt(radius)} ${fmt(radius)} 0 $largeArc 1 ${fmt(end.first)} ${fmt(end.second)}"
  }

  /** A stroke-only [FigmaSvgVector] leaf whose viewport is the layer's own px box (unit scale). */
  private fun vectorLeaf(
    bounds: LayoutInspectorBounds,
    name: String,
    pathData: String,
    color: FigmaSvgColor,
    strokeWidth: Double,
  ): FigmaSvgLayer {
    val w = (bounds.right - bounds.left).toFloat().coerceAtLeast(1f)
    val h = (bounds.bottom - bounds.top).toFloat().coerceAtLeast(1f)
    return FigmaSvgLayer(
      name = name,
      left = bounds.left,
      top = bounds.top,
      right = bounds.right,
      bottom = bounds.bottom,
      vector =
        FigmaSvgVector(
          viewportWidth = w,
          viewportHeight = h,
          paths =
            listOf(
              FigmaSvgVectorPath(
                pathData = pathData,
                fillArgb = null,
                strokeArgb = color.hex,
                strokeWidth = strokeWidth.toFloat(),
                strokeAlpha = color.opacity.toFloat(),
              )
            ),
        ),
    )
  }

  /** A filled circle of diameter [dia] centred on [box]'s centre. */
  private fun centeredCircle(
    box: LayoutInspectorBounds,
    dia: Int,
    color: FigmaSvgColor,
    name: String,
  ): FigmaSvgLayer {
    val cx = (box.left + box.right) / 2
    val cy = (box.top + box.bottom) / 2
    val r = dia / 2
    return FigmaSvgLayer(
      name = name,
      left = cx - r,
      top = cy - r,
      right = cx - r + dia,
      bottom = cy - r + dia,
      fill = color,
      circle = true,
    )
  }

  /** A [w]×[h] box centred on [bounds]'s centre. */
  private fun centered(bounds: LayoutInspectorBounds, w: Int, h: Int): LayoutInspectorBounds {
    val cx = (bounds.left + bounds.right) / 2
    val cy = (bounds.top + bounds.bottom) / 2
    val left = cx - w / 2
    val top = cy - h / 2
    return LayoutInspectorBounds(left = left, top = top, right = left + w, bottom = top + h)
  }

  private fun px(dp: Double, density: Float): Int = (dp * density).roundToInt()

  private fun fmt(v: Double): String {
    val r = (v * 100).roundToInt() / 100.0
    return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
  }
}
