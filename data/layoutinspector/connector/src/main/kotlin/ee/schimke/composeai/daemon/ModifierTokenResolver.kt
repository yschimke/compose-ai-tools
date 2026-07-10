package ee.schimke.composeai.daemon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ModifierInfo
import androidx.compose.ui.platform.InspectableValue
import androidx.compose.ui.unit.Dp
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Single home for the modifier → design-token resolution (issue #1903 consolidation).
 *
 * Before this, [ComposeSemanticsDataProducer] re-walked `getModifierInfo()` with its own private
 * copy of this logic to populate the `tokens` it mirrors onto `compose/semantics` (#1897, #1908),
 * while `layout/inspector` — the product that actually *models* per-node modifiers — carried only
 * the raw, unresolved modifier chain. The resolved `{backgroundColor, borderColor, cornerRadius,
 * shape, gap, padding}` projection is modifier-derived, so it belongs in one place that both
 * products feed their per-node inputs into rather than being duplicated per product.
 *
 * The inputs are deliberately the lowest common denominator both backends and both products can
 * supply by reflection, so this stays foundation-free (no `compose.foundation` compile dependency,
 * matching the layout inspector's approach):
 * - [modifierInfo] — the node's [ModifierInfo] entries (`LayoutInfo.getModifierInfo()`).
 * - [measurePolicy] — the node's measure policy object (for `Arrangement.spacedBy` gap), or null.
 * - [sizeWidthPx] / [sizeHeightPx] — the node's measured size in px (for percent-corner → dp).
 * - [density] — the render density (dp = px / density), for percent-corner → dp.
 */
internal object ModifierTokenResolver {

  /**
   * Resolves the design tokens declared by a node's modifiers + measure policy. Returns null when
   * the node declares none of them — the common case for pure layout / text nodes.
   *
   * The preferred source for each value is the entry's [InspectableValue] projection (the same
   * `nameFallback` / `inspectableElements` surface the layout inspector reads), but some foundation
   * elements (notably `BackgroundElement`) don't populate inspector info on the desktop/skiko
   * build, so each lookup falls back to reflecting the element's backing field.
   */
  fun resolve(
    modifierInfo: List<ModifierInfo>,
    measurePolicy: Any?,
    sizeWidthPx: Int,
    sizeHeightPx: Int,
    density: Float,
  ): ComposeSemanticsTokens? {
    var backgroundColor: String? = null
    var borderColor: String? = null
    var borderWidth: String? = null
    var cornerRadius: String? = null
    var cornerRadiusPx: String? = null
    var shape: String? = null
    var padding: ComposeSemanticsInsets? = null
    var elevation: String? = null
    var minWidth: String? = null
    var minHeight: String? = null
    // `CircleShape` / `CornerSize(50%)` resolve to dp against the node's shorter measured side.
    val minSidePx = minOf(sizeWidthPx, sizeHeightPx)
    for (info in modifierInfo) {
      val mod = info.modifier
      val inspectable = mod as? InspectableValue
      val name = inspectable?.nameFallback
      val elements = inspectable?.inspectableElements?.associate { it.name to it.value }.orEmpty()
      val simpleName = mod.javaClass.simpleName

      // `Surface`/`Card`/`FAB` cast their Material drop shadow via
      // `graphicsLayer { shadowElevation = … }`. Capture the largest shadow elevation on the node
      // so
      // the figma-svg export can emit a matching `feDropShadow` (a node may carry two
      // graphicsLayers
      // — a clip and the shadow). Skipped when zero (a clip-only graphicsLayer).
      if (name == "graphicsLayer" || simpleName.contains("GraphicsLayer")) {
        shadowElevationDp(mod, elements, density)?.let { dp ->
          if (elevation == null || dp > (elevation!!.removeSuffix("dp").toDoubleOrNull() ?: 0.0)) {
            elevation = "${dp}dp"
          }
        }
      }

      if (backgroundColor == null && (name == "background" || simpleName == "BackgroundElement")) {
        backgroundColor = backgroundColorHex(mod, elements, inspectable?.valueOverride)
      }
      // `Modifier.paint(painter)` with a solid `ColorPainter` — Wear M3's `Button`/`Card`/
      // `FilledTonalButton`/`SwitchButton` fill their container this way (through the wear
      // `surface()` helper's `PainterElement`), NOT via `Modifier.background`, so a plain
      // background
      // match misses every wear container fill and the token-driven figma-svg export drops it. Read
      // the `ColorPainter`'s colour as the fill; bitmap/vector painters (an `Image`/`Icon`'s art)
      // stay unresolved so they keep to the raster path (issue #1985).
      if (backgroundColor == null && (name == "paint" || simpleName == "PainterElement")) {
        backgroundColor = painterColorHex(elements["painter"], mod)
      }
      // `Modifier.defaultMinSize(minWidth, minHeight)` — an M3 `Badge` measures its background at
      // this min box even when its narrow content is placed smaller, so the figma-svg export grows
      // the drawn shape to it. Read the `Dp` min constraints (already dp, unlike px
      // `shadowElevation`
      // — `Dp.Unspecified` / non-positive values are dropped).
      if (name == "defaultMinSize" || simpleName.contains("UnspecifiedConstraints")) {
        if (minWidth == null) minWidth = dpConstraint(elements["minWidth"], mod, "minWidth")
        if (minHeight == null) minHeight = dpConstraint(elements["minHeight"], mod, "minHeight")
      }
      // `Modifier.border` carries the outline colour `Surface`/`Card`/dividers apply — a role
      // colour
      // (`outline` / `outlineVariant`) a plain `Modifier.background` never sees (issue #1908).
      if (borderColor == null && (name == "border" || simpleName.startsWith("BorderModifier"))) {
        borderColor = borderColorHex(mod, elements)
        borderWidth = borderWidthDp(mod, elements)
      }
      if (padding == null && (name == "padding" || simpleName.startsWith("PaddingElement"))) {
        padding = paddingInsets(mod, elements, inspectable?.valueOverride)
      }
      // Shape comes from any shape-bearing modifier: `background(color, shape)`, `clip(shape)`
      // (which Compose routes through `graphicsLayer`), or `border(..., shape)`. A plain rectangle
      // yields null for both fields and is skipped.
      val nodeShape = shapeOf(mod, elements)
      if (nodeShape != null) {
        if (cornerRadius == null) cornerRadius = nodeShape.cornerRadiusWire(minSidePx, density)
        // A `RoundedCornerShape(<px>f)` has no dp `cornerRadius`; capture its raw-pixel radii so
        // the
        // figma-svg export can still round the corner instead of dropping to a sharp rect.
        if (cornerRadius == null && cornerRadiusPx == null) {
          cornerRadiusPx = nodeShape.cornerRadiusPxWire()
        }
        if (shape == null) shape = nodeShape.shapeDescriptor()
      }
    }
    val gap = arrangementGapWire(measurePolicy)
    return if (
      backgroundColor == null &&
        borderColor == null &&
        cornerRadius == null &&
        cornerRadiusPx == null &&
        shape == null &&
        gap == null &&
        padding == null &&
        elevation == null &&
        minWidth == null &&
        minHeight == null
    )
      null
    else
      ComposeSemanticsTokens(
        backgroundColor = backgroundColor,
        borderColor = borderColor,
        borderWidth = borderWidth,
        minWidth = minWidth,
        minHeight = minHeight,
        cornerRadius = cornerRadius,
        cornerRadiusPx = cornerRadiusPx,
        shape = shape,
        gap = gap,
        padding = padding,
        elevation = elevation,
      )
  }

  /**
   * The shadow elevation of a `graphicsLayer` modifier in dp, or null when it casts no shadow.
   * `GraphicsLayerScope.shadowElevation` is a raw **pixel** value (a `Modifier.shadow(elevation:
   * Dp)` converts dp→px before setting it), and the inspector reports it in px too — so both the
   * inspector `shadowElevation` element and the reflected field are divided by [density] to recover
   * dp. Zero (a clip-only graphicsLayer) returns null.
   */
  private fun shadowElevationDp(mod: Any, elements: Map<String, Any?>, density: Float): Double? {
    if (density <= 0f) return null
    val px =
      floatValue(elements["shadowElevation"])
        ?: runCatching {
            mod.javaClass
              .getDeclaredField("shadowElevation")
              .apply { isAccessible = true }
              .getFloat(mod)
          }
          .getOrNull()
        ?: return null
    if (px <= 0f) return null
    return roundedDp(px / density).toDouble()
  }

  /** Reads a bare Float/Double, or a value class's `value` float (e.g. a `Dp`), as a Float. */
  private fun floatValue(raw: Any?): Float? =
    when (raw) {
      is Float -> raw
      is Double -> raw.toFloat()
      else ->
        runCatching {
            raw?.javaClass?.getDeclaredField("value")?.apply { isAccessible = true }?.getFloat(raw)
          }
          .getOrNull()
    }

  /**
   * Resolves the inter-child spacing of a `Row`/`Column` from its [measurePolicy] (issue #1908).
   * `Arrangement.spacedBy(n)` is stored on the `Row`/`Column`MeasurePolicy as an
   * `Arrangement.HorizontalOrVertical` whose `spacing` `Dp` is kept in a `spacing` float field; the
   * value-class `getSpacing` getter is name-mangled, so the field is read directly. Returns null
   * when the layout has no measure policy or no arrangement spacing.
   */
  private fun arrangementGapWire(measurePolicy: Any?): String? {
    val policy = measurePolicy ?: return null
    var cls: Class<*>? = policy.javaClass
    while (cls != null && cls != Any::class.java) {
      for (field in cls.declaredFields) {
        val value =
          runCatching { field.apply { isAccessible = true }.get(policy) }.getOrNull() ?: continue
        if (!value.javaClass.name.startsWith("androidx.compose.foundation.layout.Arrangement"))
          continue
        val spacing =
          runCatching {
              value.javaClass
                .getDeclaredField("spacing")
                .apply { isAccessible = true }
                .getFloat(value)
            }
            .getOrNull() ?: continue
        if (spacing > 0f) return "${spacing}dp"
      }
      cls = cls.superclass
    }
    return null
  }

  /**
   * Resolves a `background` modifier's fill colour as ARGB hex. Reads the inspector `color` element
   * / `valueOverride` when present; otherwise reflects `BackgroundElement`'s `color` field — a
   * [Color] value class stored as its packed `ULong`. For sRGB colours (the common case) the ARGB
   * is the high 32 bits; brushes and non-sRGB packings are skipped rather than mis-decoded.
   */
  private fun backgroundColorHex(
    mod: Any,
    elements: Map<String, Any?>,
    valueOverride: Any?,
  ): String? {
    ((elements["color"] ?: valueOverride) as? Color)?.let {
      return if (it == Color.Unspecified) null else colorToWireString(it)
    }
    return runCatching {
        val field = mod.javaClass.getDeclaredField("color").apply { isAccessible = true }
        val packed = field.getLong(mod).toULong()
        // sRGB packs the colour space id (non-zero) into the low 32 bits as 0; anything else is a
        // wide-gamut/unspecified packing we can't read as a plain ARGB hex.
        if (packed and 0xFFFFFFFFuL != 0uL) return null
        val argb = (packed shr 32).toInt()
        if (argb == 0) null else "#${String.format(Locale.US, "%08X", argb)}"
      }
      .getOrNull()
  }

  /**
   * Resolves a painter-based container fill (`Modifier.paint(painter)`) as ARGB hex. Only a solid
   * [androidx.compose.ui.graphics.painter.ColorPainter] yields a colour — the fill Wear M3 surfaces
   * apply for their container — while bitmap / vector / gradient painters (an `Image`/`Icon`'s art)
   * return null so they stay on the raster path rather than collapsing to a bogus flat rectangle.
   * Reads the inspector `painter` element (the live painter object) when present, else reflects the
   * element's backing `painter` field; the `ColorPainter`'s `color` is a [Color] value class stored
   * as its packed `ULong`, decoded exactly like [backgroundColorHex].
   */
  private fun painterColorHex(painterElement: Any?, mod: Any): String? {
    val painter =
      painterElement
        ?: runCatching {
            mod.javaClass.getDeclaredField("painter").apply { isAccessible = true }.get(mod)
          }
          .getOrNull()
        ?: return null
    if (painter.javaClass.simpleName != "ColorPainter") return null
    return runCatching {
        val field = painter.javaClass.getDeclaredField("color").apply { isAccessible = true }
        val packed = field.getLong(painter).toULong()
        if (packed and 0xFFFFFFFFuL != 0uL) return null
        val argb = (packed shr 32).toInt()
        if (argb == 0) null else "#${String.format(Locale.US, "%08X", argb)}"
      }
      .getOrNull()
  }

  /**
   * Resolves a `border` modifier's stroke colour as ARGB hex. `Modifier.border` projects its colour
   * through the inspector `color` element even when the brush is a plain `SolidColor`; that's read
   * first, falling back to reflecting the backing `brush` field's `SolidColor.value`. A gradient
   * brush (no single colour) is skipped (issue #1908).
   */
  /**
   * Resolves a `border` modifier's stroke width in dp. `Modifier.border(width: Dp, …)` stores its
   * `Dp` (already in dp, unlike the px `shadowElevation`) on a `width` field / inspector element.
   * Returns null when it can't be read (falls back to the export's 1dp hairline default) or when
   * the width is ≤ 0.
   */
  private fun borderWidthDp(mod: Any, elements: Map<String, Any?>): String? {
    val dp =
      floatValue(elements["width"])
        ?: runCatching {
            mod.javaClass.getDeclaredField("width").apply { isAccessible = true }.getFloat(mod)
          }
          .getOrNull()
        ?: return null
    if (dp <= 0f) return null
    return "${roundedDp(dp)}dp"
  }

  /**
   * A `defaultMinSize` min constraint (a `Dp`, already in dp) as a `"…dp"` string, or null when it
   * is `Dp.Unspecified` (`Float.NaN`) or ≤ 0. Reads the inspector element first, else the
   * modifier's backing field ([field], `minWidth` / `minHeight`).
   */
  private fun dpConstraint(element: Any?, mod: Any, field: String): String? {
    val dp =
      floatValue(element)
        ?: runCatching {
            mod.javaClass.getDeclaredField(field).apply { isAccessible = true }.getFloat(mod)
          }
          .getOrNull()
        ?: return null
    if (dp.isNaN() || dp <= 0f) return null
    return "${roundedDp(dp)}dp"
  }

  private fun borderColorHex(mod: Any, elements: Map<String, Any?>): String? {
    (elements["color"] as? Color)?.let {
      return if (it == Color.Unspecified) null else colorToWireString(it)
    }
    return runCatching {
        val brush =
          mod.javaClass.getDeclaredField("brush").apply { isAccessible = true }.get(mod)
            ?: return null
        if (brush.javaClass.simpleName != "SolidColor") return null
        val value =
          brush.javaClass.getDeclaredField("value").apply { isAccessible = true }.getLong(brush)
        val packed = value.toULong()
        if (packed and 0xFFFFFFFFuL != 0uL) return null
        val argb = (packed shr 32).toInt()
        if (argb == 0) null else "#${String.format(Locale.US, "%08X", argb)}"
      }
      .getOrNull()
  }

  /**
   * Reads padding from a `padding` modifier. `Modifier.padding(all)` reports the value through
   * [InspectableValue.valueOverride], the per-edge and horizontal/vertical overloads through named
   * [elements]; when inspector info is absent the four `Dp` fields (`start`/`top`/`end`/`bottom`)
   * are reflected off `PaddingElement`. The `PaddingValues` overload is left unresolved.
   */
  private fun paddingInsets(
    mod: Any,
    elements: Map<String, Any?>,
    valueOverride: Any?,
  ): ComposeSemanticsInsets? {
    fun el(key: String): String? = (elements[key] as? Dp)?.toWireDp()
    val all = el("all") ?: (valueOverride as? Dp)?.toWireDp()
    if (all != null) return ComposeSemanticsInsets(start = all, top = all, end = all, bottom = all)
    val horizontal = el("horizontal")
    val vertical = el("vertical")
    val start = el("start") ?: horizontal ?: reflectDp(mod, "start")
    val top = el("top") ?: vertical ?: reflectDp(mod, "top")
    val end = el("end") ?: horizontal ?: reflectDp(mod, "end")
    val bottom = el("bottom") ?: vertical ?: reflectDp(mod, "bottom")
    if (start == null && top == null && end == null && bottom == null) return null
    return ComposeSemanticsInsets(start = start, top = top, end = end, bottom = bottom)
  }

  /** The inspector `shape` element, or a reflected `shape` field on the modifier element. */
  private fun shapeOf(mod: Any, elements: Map<String, Any?>): Shape? {
    (elements["shape"] as? Shape)?.let {
      return it
    }
    return runCatching {
        val field = mod.javaClass.getDeclaredField("shape").apply { isAccessible = true }
        field.get(mod) as? Shape
      }
      .getOrNull()
  }

  /**
   * Resolves the dp corner radius of a [Shape] without a `compose.foundation` compile dependency.
   * `CornerBasedShape` exposes four `CornerSize` corners via no-arg getters. A dp-based
   * `CornerSize` (`DpCornerSize`) stores its `Dp` in a `size` field (inlined to a float) and is
   * emitted verbatim; a percent-based `CornerSize` (`PercentCornerSize`, what `CircleShape` and
   * `CornerSize(50%)` use) is resolved against [minSidePx] / [density] so a circular avatar reports
   * its effective dp radius (issue #1908). A uniform shape emits one value; otherwise the four
   * corners are emitted comma-separated. Returns null for non-corner shapes and for pixel corners
   * (`PxCornerSize`, `RoundedCornerShape(12f)`), which can't be expressed as a fixed dp.
   */
  private fun Shape.cornerRadiusWire(minSidePx: Int, density: Float): String? {
    val corners =
      listOf("getTopStart", "getTopEnd", "getBottomEnd", "getBottomStart").map { getter ->
        cornerSizeDp(invokeNoArg(getter), minSidePx, density)
      }
    if (corners.any { it == null }) return null
    val values = corners.filterNotNull()
    return if (values.distinct().size == 1) "${values.first()}dp"
    else values.joinToString(",") { "${it}dp" }
  }

  /**
   * Raw-pixel counterpart to [cornerRadiusWire] for a shape built from pixel corners
   * (`RoundedCornerShape(20f)` → four `PxCornerSize`). Emits one value for a uniform shape, four
   * comma-separated otherwise, each with a `px` suffix (`"20.0px"`). Returns null unless **every**
   * corner is a pixel corner — a dp/percent shape is already carried by [cornerRadiusWire] /
   * [shapeDescriptor], so this stays signal for the case those two drop.
   */
  private fun Shape.cornerRadiusPxWire(): String? {
    val corners =
      listOf("getTopStart", "getTopEnd", "getBottomEnd", "getBottomStart").map { getter ->
        cornerSizePx(invokeNoArg(getter))
      }
    if (corners.any { it == null }) return null
    val values = corners.filterNotNull()
    return if (values.distinct().size == 1) "${values.first()}px"
    else values.joinToString(",") { "${it}px" }
  }

  private fun cornerSizePx(corner: Any?): Float? {
    corner ?: return null
    // `PxCornerSize` (`RoundedCornerShape(12f)`) stores its pixel radius in a `size` field.
    if (corner.javaClass.simpleName != "PxCornerSize") return null
    return runCatching {
        val field = corner.javaClass.getDeclaredField("size").apply { isAccessible = true }
        (field.get(corner) as? Float)
      }
      .getOrNull()
  }

  private fun cornerSizeDp(corner: Any?, minSidePx: Int, density: Float): Float? {
    corner ?: return null
    return when (corner.javaClass.simpleName) {
      // A dp corner stores its `Dp` (inlined float) directly.
      "DpCornerSize" ->
        runCatching {
            val field = corner.javaClass.getDeclaredField("size").apply { isAccessible = true }
            when (val raw = field.get(corner)) {
              is Float -> raw
              is Dp -> raw.value
              else -> null
            }
          }
          .getOrNull()
      // A percent corner is a fraction of the shorter side: `px = minSide * percent/100`, then dp.
      "PercentCornerSize" ->
        cornerPercent(corner)?.let { pct ->
          if (minSidePx <= 0 || density <= 0f) null
          else roundedDp((minSidePx * pct / 100f) / density)
        }
      // `PxCornerSize` (`RoundedCornerShape(12f)`) stores pixels we can't turn into a fixed dp.
      else -> null
    }
  }

  /**
   * The percent (`50.0` for `CircleShape`) stored in a `PercentCornerSize`. The backing field is
   * `percent` (its `toString` renders `"CornerSize(size = 50.0%)"`, but that label is not the field
   * name); fall back to `size` defensively in case a future Compose renames it.
   */
  private fun cornerPercent(corner: Any?): Float? {
    corner ?: return null
    if (corner.javaClass.simpleName != "PercentCornerSize") return null
    return sequenceOf("percent", "size")
      .mapNotNull { name ->
        runCatching {
            corner.javaClass.getDeclaredField(name).apply { isAccessible = true }.getFloat(corner)
          }
          .getOrNull()
      }
      .firstOrNull()
  }

  /**
   * Round a computed dp to 2 decimals so percent-derived radii read cleanly (`18.0`, not `17.99`).
   */
  private fun roundedDp(value: Float): Float = (value * 100f).roundToInt() / 100f

  /**
   * Shape-family descriptor for shapes whose radius isn't a single dp number (issue #1908):
   * `"circle"` for a `CircleShape` / all-`CornerSize(50%)` rounded shape, `"cut"` for a
   * `CutCornerShape`. Null for a plain rectangle or an ordinary dp `RoundedCornerShape` (its radius
   * is already carried by [cornerRadiusWire]), so the descriptor stays signal, not noise.
   */
  private fun Shape.shapeDescriptor(): String? {
    if (javaClass.simpleName == "CutCornerShape") return "cut"
    val corners =
      listOf("getTopStart", "getTopEnd", "getBottomEnd", "getBottomStart").map {
        cornerPercent(invokeNoArg(it))
      }
    if (corners.all { it != null && it >= 50f }) return "circle"
    return null
  }

  private fun reflectDp(mod: Any, field: String): String? =
    runCatching {
        val value =
          mod.javaClass.getDeclaredField(field).apply { isAccessible = true }.getFloat(mod)
        if (value.isNaN()) null else "${value}dp"
      }
      .getOrNull()

  private fun Any.invokeNoArg(name: String): Any? =
    runCatching { javaClass.getMethod(name).invoke(this) }.getOrNull()

  private fun Dp.toWireDp(): String = "${value}dp"

  private fun colorToWireString(color: Color): String =
    "#${String.format(Locale.US, "%08X", color.toArgb())}"
}
