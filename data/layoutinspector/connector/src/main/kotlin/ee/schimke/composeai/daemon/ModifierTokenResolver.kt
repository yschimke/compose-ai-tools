package ee.schimke.composeai.daemon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ModifierInfo
import androidx.compose.ui.platform.InspectableValue
import androidx.compose.ui.unit.Dp
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorGradient
import ee.schimke.composeai.data.layoutinspector.PlaceholderModifiers
import ee.schimke.composeai.data.layoutinspector.insetsPaint
import java.lang.reflect.Field
import java.lang.reflect.Modifier
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
    var paintInset: ComposeSemanticsInsets? = null
    // Compose modifier order is outer→inner. A `padding` seen before any paint modifier
    // (`background`/`paint`/`border`/a shape-bearing `clip`) insets the box those modifiers draw
    // into, so its value is recorded as [paintInset]; a padding after the first paint (which pads
    // content, not paint) is not. Flipped by the paint branches below (issue #2852).
    var sawPaint = false
    var elevation: String? = null
    var minWidth: String? = null
    var minHeight: String? = null
    var opacity = 1.0
    var backgroundGradient: LayoutInspectorGradient? = null
    var borderGradient: LayoutInspectorGradient? = null
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
        graphicsLayerAlpha(info)?.let { opacity *= it }
        shadowElevationDp(mod, elements, density)?.let { dp ->
          if (elevation == null || dp > (elevation.removeSuffix("dp").toDoubleOrNull() ?: 0.0)) {
            elevation = "${dp}dp"
          }
        }
      }

      if (name == "background" || simpleName == "BackgroundElement") {
        sawPaint = true
        if (backgroundColor == null) {
          backgroundColor = backgroundColorHex(mod, elements, inspectable?.valueOverride)
          // A brush background resolves no flat colour; capture the gradient itself so the export
          // emits a real `<linearGradient>` instead of rastering the whole layer (issue #2852).
          if (backgroundColor == null && backgroundGradient == null) {
            backgroundGradient = linearGradient(mod, elements, sizeWidthPx, sizeHeightPx)
          }
        }
      }
      // `Modifier.paint(painter)` with a solid `ColorPainter` — Wear M3's `Button`/`Card`/
      // `FilledTonalButton`/`SwitchButton` fill their container this way (through the wear
      // `surface()` helper's `PainterElement`), NOT via `Modifier.background`, so a plain
      // background
      // match misses every wear container fill and the token-driven figma-svg export drops it. Read
      // the `ColorPainter`'s colour as the fill; bitmap/vector painters (an `Image`/`Icon`'s art)
      // stay unresolved so they keep to the raster path (issue #1985).
      if (name == "paint" || simpleName == "PainterElement") {
        sawPaint = true
        if (backgroundColor == null) backgroundColor = painterColorHex(elements, mod)
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
      if (name == "border" || simpleName.startsWith("BorderModifier")) {
        sawPaint = true
        if (borderColor == null) {
          borderColor = borderColorHex(mod, elements)
          borderWidth = borderWidthDp(mod, elements)
          // Jetsnack's gradient-tinted icon button rings itself with
          // `border(width, Brush.linearGradient(...), CircleShape)`. Before this the brush resolved
          // to no colour and the ring vanished from the export entirely (issue #2852).
          if (borderColor == null && borderGradient == null) {
            borderGradient = linearGradient(mod, elements, sizeWidthPx, sizeHeightPx)
          }
        }
      }
      if (name == "padding" || simpleName.startsWith("PaddingElement")) {
        val insets = paddingInsets(mod, elements, inspectable?.valueOverride)
        if (padding == null) padding = insets
        // Leading padding (before any paint modifier) insets the drawn shape; record the first one
        // so `padding(4.dp).clip(…).border(…).background(…)` draws inside the padded box. A
        // `padding(0.dp)` changes no geometry, so it must not count — otherwise it would suppress
        // the growth heuristic for a node that still needs it (#2852).
        if (!sawPaint && paintInset == null && insets?.insetsPaint() == true) paintInset = insets
      }
      // Shape comes from any shape-bearing modifier: `background(color, shape)`, `clip(shape)`
      // (which Compose routes through `graphicsLayer`), or `border(..., shape)`. A plain rectangle
      // yields null for both fields and is skipped.
      //
      // NOT a placeholder overlay, though: Wear M3 `Modifier.placeholder`/`placeholderShimmer`
      // expose `PlaceholderDefaults.shape` (= `ShapeTokens.CornerFull`, a 50% pill) as an
      // inspectable `shape`, and ride on the *caller's* chain outside the component's own Surface
      // shape — so as the first shape-bearing modifier they hijack the container corner and a
      // placeholdered `TitleCard`/`Button` (modest corner) exports as a full pill (`rx =
      // height/2`).
      // Skip their shape so the real `clip`/`paint`/`background` shape later in the chain wins; the
      // placeholder's own shape isn't lost, it is carried on [resolvePlaceholder]'s state-aware
      // projection, where it describes the placeholder block rather than the container (#2646).
      val nodeShape =
        if (PlaceholderModifiers.isPlaceholderModifier(name, simpleName)) null
        else shapeOf(mod, elements)
      if (nodeShape != null) {
        // A shape-bearing `clip`/`background`/`border` is a paint modifier, so a padding after it
        // no longer insets the drawn shape (issue #2852).
        sawPaint = true
        val effectiveShape = nodeShape.effectiveCornerShape()
        if (cornerRadius == null) cornerRadius = effectiveShape.cornerRadiusWire(minSidePx, density)
        // A `RoundedCornerShape(<px>f)` has no dp `cornerRadius`; capture its raw-pixel radii so
        // the
        // figma-svg export can still round the corner instead of dropping to a sharp rect.
        if (cornerRadius == null && cornerRadiusPx == null) {
          cornerRadiusPx = effectiveShape.cornerRadiusPxWire()
        }
        if (shape == null) shape = effectiveShape.shapeDescriptor()
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
        opacity >= 0.999 &&
        backgroundGradient == null &&
        borderGradient == null &&
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
        paintInset = paintInset,
        elevation = elevation,
        opacity = opacity.takeIf { it < 0.999 },
        backgroundGradient = backgroundGradient,
        borderGradient = borderGradient,
      )
  }

  /**
   * Effective alpha of one graphics-layer modifier.
   *
   * Three shapes, probed in order of how much we can trust them:
   * 1. The **named-parameter** overload (`graphicsLayer(alpha = 0f)`) keeps `alpha` as a real field
   *    on the element, and the inspector also exposes it as a property.
   * 2. The **lambda** overload (`graphicsLayer { alpha = … }`) keeps only the opaque block, so we
   *    evaluate it ourselves against a recording scope ([evaluateLayerBlockAlpha]).
   * 3. Only if neither answered, the coordinator's `graphicsLayerScope`.
   *
   * The ordering is the fix for issue #2853. Probing the coordinator *first* looked like it covered
   * the lambda case, but `graphicsLayerScope` is a **shared static** on `NodeCoordinator` that
   * Compose reuses for whichever layer it updated most recently — so it reports some other node's
   * alpha, and for Jetchat's `RecordButton` (`graphicsLayer { alpha = containerAlpha.value }`, zero
   * when idle) it read back 1 and the export drew an opaque blue circle the PNG doesn't have.
   * Evaluating the block is per-node and exact, which is why it now runs before that fallback
   * rather than after it.
   */
  internal fun graphicsLayerAlpha(info: ModifierInfo): Double? {
    val effective =
      reflectedFloat(info.modifier, "alpha")
        ?: (info.modifier as? InspectableValue)
          ?.inspectableElements
          ?.firstOrNull { it.name == "alpha" }
          ?.value
          ?.let(::floatValue)
        ?: evaluateLayerBlockAlpha(info.modifier)
        ?: reflectedField(info.coordinates, "graphicsLayerScope")?.let {
          reflectedFloat(it, "alpha")
        }
    return effective?.coerceIn(0f, 1f)?.toDouble()
  }

  /**
   * Reads a **linear** gradient brush off [mod]'s `brush` field (or an inspector `brush` element)
   * into the wire [LayoutInspectorGradient] (issue #2852).
   *
   * Compose's `LinearGradient` is internal, so `colors` / `stops` / `start` / `end` are taken
   * reflectively. Offsets are normalised into `0..1` fractions of the node box — SVG's default
   * gradient space — so the emitter needs no size arithmetic; `horizontalGradient` /
   * `verticalGradient` encode "to the far edge" as `Float.POSITIVE_INFINITY`, which resolves to the
   * edge (`1.0`).
   *
   * Returns null for a `SolidColor` (the flat-colour path already covers it) and for any
   * radial/sweep/shader brush, so those keep the raster fallback rather than being emitted as a
   * gradient they aren't.
   */
  internal fun linearGradient(
    mod: Any,
    elements: Map<String, Any?>,
    widthPx: Int,
    heightPx: Int,
  ): LayoutInspectorGradient? {
    val brush =
      elements["brush"]
        ?: runCatching {
            mod.javaClass.getDeclaredField("brush").apply { isAccessible = true }.get(mod)
          }
          .getOrNull()
        ?: return null
    if (brush.javaClass.simpleName != "LinearGradient") return null
    val colors =
      (reflectedField(brush, "colors") as? List<*>)?.mapNotNull { colorWire(it) } ?: return null
    if (colors.size < 2) return null
    @Suppress("UNCHECKED_CAST")
    val stops = (reflectedField(brush, "stops") as? List<Float>)?.takeIf { it.size == colors.size }
    val w = widthPx.toFloat().takeIf { it > 0f } ?: return null
    val h = heightPx.toFloat().takeIf { it > 0f } ?: return null
    val start = reflectedField(brush, "start")
    val end = reflectedField(brush, "end")
    return LayoutInspectorGradient(
      colors = colors,
      stops = stops,
      startX = (offsetAxis(start, 0, 0f) / w).coerceIn(0f, 1f),
      startY = (offsetAxis(start, 1, 0f) / h).coerceIn(0f, 1f),
      // A non-finite endpoint component means "the far edge of the box" on *that* axis, so each
      // one falls back to its own extent. `Brush.linearGradient(colors)` with no explicit
      // endpoints stores `Offset.Infinite` — both axes infinite — which is the diagonal
      // top-left → bottom-right gradient several samples use; falling back to 0 on Y flattened
      // those to horizontal. `horizontalGradient`/`verticalGradient` leave the other axis finite
      // at 0, so they are unaffected.
      endX = (offsetAxis(end, 0, w) / w).coerceIn(0f, 1f),
      endY = (offsetAxis(end, 1, h) / h).coerceIn(0f, 1f),
    )
  }

  /**
   * One axis of a Compose `Offset`. `Offset` is a value class over a packed `Long`, so the
   * reflected field is that packed value rather than an object with accessors. A non-finite
   * component (`Float.POSITIVE_INFINITY`, how `horizontalGradient` spells "the far edge") falls
   * back to [fallback].
   */
  private fun offsetAxis(value: Any?, axis: Int, fallback: Float): Float {
    val packed = (value as? Long) ?: return fallback
    val bits = if (axis == 0) (packed shr 32).toInt() else (packed and 0xFFFFFFFFL).toInt()
    val f = Float.fromBits(bits)
    return if (f.isFinite()) f else fallback
  }

  /** A `Color` value-class instance (or its packed `ULong`) as the `#AARRGGBB` wire string. */
  private fun colorWire(value: Any?): String? {
    val color = value as? Color ?: (value as? Long)?.let { Color(it.toULong()) } ?: return null
    return if (color == Color.Unspecified) null else colorToWireString(color)
  }

  /**
   * Runs a lambda-form `graphicsLayer { … }` block against a recording scope and returns the alpha
   * it assigned, or null when [modifier] carries no such block (or the block can't be run).
   *
   * The scope is a [java.lang.reflect.Proxy] over whatever interface the block expects rather than
   * a hand-written `GraphicsLayerScope` implementation: that interface has gained members across
   * Compose releases, and a proxy records the setters it actually sees while answering every other
   * call with a type-appropriate default. So this keeps working when the interface grows.
   *
   * Re-running the block is safe and is what makes the value *current*: a `graphicsLayer` block is
   * a series of property assignments, and reading the animation state it closes over gives exactly
   * the alpha the frame was drawn with.
   */
  internal fun evaluateLayerBlockAlpha(modifier: Any): Float? {
    val block = layerBlock(modifier) ?: return null
    val recorded = HashMap<String, Any?>()
    // The scope interface by name, not from the lambda's generic signature: a Kotlin lambda erases
    // to a raw `Function1`, so there is no type argument to read back off it.
    val iface =
      runCatching {
          Class.forName(
            "androidx.compose.ui.graphics.GraphicsLayerScope",
            false,
            modifier.javaClass.classLoader,
          )
        }
        .getOrNull()
        ?.takeIf { it.isInterface } ?: return null
    val scope =
      runCatching {
          java.lang.reflect.Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { _, m, args
            ->
            val name = m.name
            when {
              name.startsWith("set") && args != null && args.size == 1 -> {
                recorded[name.removePrefix("set").replaceFirstChar { it.lowercase() }] = args[0]
                null
              }
              // Getters (and anything else) answer with a harmless default so a block that reads
              // back a property it just set, or consults `density`, doesn't blow up mid-evaluation.
              else -> defaultFor(m.returnType, name, recorded)
            }
          }
        }
        .getOrNull() ?: return null
    @Suppress("UNCHECKED_CAST") val invoke = block as? Function1<Any?, Any?> ?: return null
    runCatching { invoke(scope) }.getOrNull() ?: return null
    return recorded["alpha"] as? Float
  }

  /** The `GraphicsLayerScope.() -> Unit` a lambda-form `graphicsLayer` element holds, if any. */
  private fun layerBlock(modifier: Any): Any? =
    generateSequence(modifier.javaClass as Class<*>?) { it.superclass }
      .flatMap { it.declaredFields.asSequence() }
      .firstOrNull { field ->
        kotlin.jvm.functions.Function1::class.java.isAssignableFrom(field.type) &&
          (field.name == "block" || field.name == "layerBlock")
      }
      ?.let { field ->
        runCatching {
            field.isAccessible = true
            field.get(modifier)
          }
          .getOrNull()
      }

  /**
   * A benign return value for a proxied scope call: whatever the block already assigned when it
   * reads a property back, else the type's zero. `density`/`fontScale` answer 1 so a block that
   * converts dp inside itself divides by something sane rather than zero.
   */
  private fun defaultFor(type: Class<*>, name: String, recorded: Map<String, Any?>): Any? {
    val property = name.removePrefix("get").replaceFirstChar { it.lowercase() }
    recorded[property]?.let {
      return it
    }
    GRAPHICS_LAYER_DEFAULTS[property]?.let {
      return it
    }
    return when (type) {
      java.lang.Float.TYPE -> 0f
      java.lang.Boolean.TYPE -> false
      java.lang.Integer.TYPE -> 0
      java.lang.Long.TYPE -> 0L
      else -> null
    }
  }

  /**
   * `GraphicsLayerScope`'s own identity defaults, for a property the block **reads before
   * writing**.
   *
   * A relative assignment (`graphicsLayer { alpha *= fade }`) starts from Compose's default, not
   * from zero — answering 0 there would record `alpha = 0` for every non-zero fade and make the
   * node vanish from the export, which is the same class of bug this evaluator exists to fix.
   * Anything not listed keeps the type's zero, which is the identity for translation and rotation.
   */
  private val GRAPHICS_LAYER_DEFAULTS: Map<String, Any> =
    mapOf(
      "alpha" to 1f,
      "scaleX" to 1f,
      "scaleY" to 1f,
      // Compose's `DefaultCameraDistance`.
      "cameraDistance" to 8f,
      "density" to 1f,
      "fontScale" to 1f,
    )

  private fun reflectedFloat(instance: Any, name: String): Float? =
    reflectedField(instance, name)?.let(::floatValue)

  private fun reflectedField(instance: Any, name: String): Any? =
    generateSequence(instance.javaClass as Class<*>?) { it.superclass }
      .flatMap { it.declaredFields.asSequence() }
      .firstOrNull { it.name == name }
      ?.let { field ->
        runCatching {
            field.isAccessible = true
            field.get(instance)
          }
          .getOrNull()
      }

  /**
   * The shadow elevation of a `graphicsLayer`/`shadow` modifier in dp, or null when it casts no
   * shadow. Two shapes carry it, so both are probed:
   * - A `graphicsLayer { shadowElevation = … }` (what `Surface`/`Card`/`FAB` cast their Material
   *   shadow through) exposes `GraphicsLayerScope.shadowElevation`, a raw **pixel** value — the
   *   inspector reports it in px too, so both the inspector `shadowElevation` element and the
   *   reflected field are divided by [density] to recover dp.
   * - A bare `Modifier.shadow(elevation: Dp)` lowers to a `ShadowGraphicsLayerElement` (also
   *   matched by the `GraphicsLayer` name gate) that keeps its **`Dp` `elevation`** instead —
   *   already dp, so it is read as-is with no `/ density`. Without this fallback such a node
   *   resolved null and the figma-svg export dropped its `feDropShadow` (issue #2357).
   *
   * Zero / non-positive (a clip-only graphicsLayer) returns null.
   *
   * Non-private for the direct unit test in this module.
   */
  internal fun shadowElevationDp(mod: Any, elements: Map<String, Any?>, density: Float): Double? {
    if (density <= 0f) return null
    // `graphicsLayer` shadowElevation — a raw pixel value → recover dp by dividing by density.
    val px =
      floatValue(elements["shadowElevation"])
        ?: runCatching {
            mod.javaClass
              .getDeclaredField("shadowElevation")
              .apply { isAccessible = true }
              .getFloat(mod)
          }
          .getOrNull()
    if (px != null) {
      if (px <= 0f) return null
      return roundedDp(px / density).toDouble()
    }
    // `Modifier.shadow(elevation: Dp)` → `ShadowGraphicsLayerElement.elevation` is a `Dp` (already
    // dp, unlike the px `shadowElevation`), so take it verbatim. `floatValue` reads either the
    // inspector element or the reflected `elevation` field (a `Dp` value class, inlined to a
    // float).
    val elevationDp =
      floatValue(elements["elevation"])
        ?: runCatching {
            mod.javaClass
              .getDeclaredField("elevation")
              .apply { isAccessible = true }
              .let { floatValue(it.get(mod)) }
          }
          .getOrNull()
        ?: return null
    if (elevationDp <= 0f) return null
    return roundedDp(elevationDp).toDouble()
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
   * Follows a delegating painter down to the concrete painter it ultimately draws, by looking for a
   * single field that is itself a `Painter`. Returns [painter] unchanged when it delegates to
   * nothing (the common case) — so a real `ColorPainter`, `BitmapPainter` or `VectorPainter` is
   * handed back as-is and classified normally by the caller.
   *
   * Only a **pure pass-through** wrapper is followed: one whose sole piece of own state is that
   * delegate painter. A wrapper that also holds a shape, a `ColorFilter`, a `Brush`, a
   * `BorderStroke`, an alpha — or a second painter, where which one is the fill would be a guess —
   * is left alone, because each of those changes what the render actually painted and a flat fill
   * recovered past them would be a rectangle in the wrong colour. Wrong colours are worse than the
   * raster fallback, which reproduces the pixels exactly.
   *
   * Reflection can see a painter's *state* but not its `onDraw`, so this cannot prove a wrapper
   * forwards its delegate — a stateless painter that draws something else entirely would still be
   * followed. That residue is accepted: a painter whose only field is another painter and which
   * then ignores it is not a shape any Compose library ships. Depth is bounded so a
   * self-referential painter can't spin.
   */
  private fun unwrapDelegatingPainter(painter: Any): Any {
    var current = painter
    repeat(MAX_PAINTER_UNWRAP_DEPTH) {
      if (current.javaClass.simpleName == "ColorPainter") return current
      val own = ownFields(current.javaClass)
      // A wrapper that also holds paint-altering state — a shape to clip with, a
      // `ColorFilter`/`Brush` to re-tint with, a `BorderStroke`, an alpha — does not paint what
      // its delegate paints, so a flat fill recovered past it would be a rectangle in a colour
      // the render never drew. Wrong colours are worse than the raster fallback, which reproduces
      // the pixels exactly, so those stop the descent. Inert bookkeeping (a cached
      // `intrinsicSize`, a measured extent) doesn't change the paint and is allowed through.
      if (own.any { altersPaint(it) }) return current
      val painterFields = own.filter {
        isPainterType(it.type) && it.type.name != current.javaClass.name
      }
      // Two painters: which one is the fill would be a guess, so leave it alone as well. A local
      // or anonymous painter that *captures* another painter (an overlay drawn by its `onDraw`)
      // holds it in a compiler-generated field, so captures count here too — otherwise a captured
      // second painter would be invisible and the one declared delegate would be reported as the
      // fill even though the capture changes what is drawn.
      if (painterFields.size != 1) return current
      // The delegate itself is a declared field, though: a capture is never the thing the wrapper
      // forwards to.
      val next =
        painterFields
          .single()
          .takeUnless { it.isSynthetic }
          ?.let { field ->
            runCatching {
                field.isAccessible = true
                field.get(current)
              }
              .getOrNull()
          } ?: return current
      current = next
    }
    return current
  }

  /**
   * A painter's own instance state: the fields declared below `Painter` in its hierarchy, skipping
   * `Painter`'s own bookkeeping (its cached paint, layout direction and the like).
   *
   * Compiler-generated fields are **kept**. A local or anonymous painter holds whatever it captured
   * in a synthetic field, and a captured `Painter` or `ColorFilter` affects the pixels exactly as
   * much as a declared one does — filtering synthetics out would hide that state from both the
   * paint-altering check and the ambiguity check. Only statics are dropped, since they are shared
   * class state rather than this instance's.
   */
  private fun ownFields(type: Class<*>): List<Field> =
    generateSequence(type as Class<*>?) { it.superclass }
      .takeWhile { it.name != "androidx.compose.ui.graphics.painter.Painter" }
      .flatMap { it.declaredFields.asSequence() }
      .filterNot { Modifier.isStatic(it.modifiers) }
      .toList()

  /**
   * True when [field] is state that changes what a painter puts on screen relative to what it
   * delegates to.
   *
   * Detected two ways, because Compose spells these both as objects and as inlined value classes:
   * by *type* for the paint concepts that have one (`Brush`, `ColorFilter`, `Shape`, …), and by
   * *name* for the scalars that don't — a `Color` and a `Size` are both an inlined `long`, so only
   * the name distinguishes a tint from a cached extent.
   */
  private fun altersPaint(field: Field): Boolean {
    if (supertypes(field.type).any { it in PAINT_ALTERING_TYPES }) return true
    val name = field.name.lowercase(Locale.US)
    return PAINT_KNOB_NAMES.any { name.contains(it) }
  }

  /** [type] and every class/interface it inherits from, by binary name. */
  private fun supertypes(type: Class<*>): Sequence<String> = sequence {
    yield(type.name)
    type.superclass?.let { yieldAll(supertypes(it)) }
    type.interfaces.forEach { yieldAll(supertypes(it)) }
  }

  private val PAINT_ALTERING_TYPES =
    setOf(
      "androidx.compose.ui.graphics.Brush",
      "androidx.compose.ui.graphics.ColorFilter",
      "androidx.compose.ui.graphics.ColorProducer",
      "androidx.compose.ui.graphics.Outline",
      "androidx.compose.ui.graphics.Paint",
      "androidx.compose.ui.graphics.Shape",
      "androidx.compose.foundation.BorderStroke",
    )

  private val PAINT_KNOB_NAMES =
    listOf("alpha", "opacity", "tint", "color", "colour", "filter", "brush", "shape", "border")

  /** True when [type] is (or extends) Compose's `Painter`. */
  private fun isPainterType(type: Class<*>): Boolean =
    generateSequence(type as Class<*>?) { it.superclass }
      .any { it.name == "androidx.compose.ui.graphics.painter.Painter" }

  /** Bound on [unwrapDelegatingPainter]'s descent; real wrappers nest one or two deep. */
  private const val MAX_PAINTER_UNWRAP_DEPTH = 4

  /**
   * Test seam for [painterColorHex]'s painter half: resolves the flat fill a `Modifier.paint`
   * painter would contribute, with no modifier element map to fall back on.
   */
  internal fun painterFillHexForTest(painter: Any): String? =
    painterColorHex(mapOf("painter" to painter), Any())

  /**
   * Resolves a painter-based container fill (`Modifier.paint(painter, alpha, colorFilter)`) as ARGB
   * hex. Only a solid [androidx.compose.ui.graphics.painter.ColorPainter] yields a colour — the
   * fill Wear M3 surfaces apply for their container — while bitmap / vector / gradient painters (an
   * `Image`/`Icon`'s art) return null so they stay on the raster path rather than collapsing to a
   * bogus flat rectangle.
   *
   * `Modifier.paint` also carries an `alpha` multiplier and an optional `colorFilter` that Compose
   * applies at draw time ([androidx.compose.ui.draw.PainterElement]; the wear `surface()` element
   * carries neither). A `colorFilter` re-tints the fill in ways a flat `#AARRGGBB` can't represent,
   * so a filtered paint is skipped entirely; the `alpha` is folded into the emitted colour's alpha
   * channel so a semi-transparent paint doesn't export as an opaque rectangle. Both are read from
   * the inspector [elements] when present, else reflected off the element's backing fields, and
   * both default to their no-op values (alpha 1, no filter) when the element doesn't expose them.
   */
  private fun painterColorHex(elements: Map<String, Any?>, mod: Any): String? {
    // A colorFilter re-tints the painter at draw time — a flat fill token can't reproduce it.
    val colorFilter =
      elements["colorFilter"]
        ?: runCatching {
            mod.javaClass.getDeclaredField("colorFilter").apply { isAccessible = true }.get(mod)
          }
          .getOrNull()
    if (colorFilter != null) return null
    val painter =
      elements["painter"]
        ?: runCatching {
            mod.javaClass.getDeclaredField("painter").apply { isAccessible = true }.get(mod)
          }
          .getOrNull()
        ?: return null
    // A Wear M3 scaling list (`TransformingLazyColumn` + `SurfaceTransformation`) doesn't fill its
    // cards with a bare `ColorPainter` — it wraps that painter in a
    // `androidx.wear.compose.material3.lazy.BackgroundPainter`, which morphs the container shape as
    // the item scales through the curved edges. That wrapper stringifies to a class name, so an
    // un-unwrapped resolver leaves `backgroundColor` null and the whole card (title + subtitle
    // included) rasterises as one opaque `<image>` — the labels get baked into pixels instead of
    // staying editable `<text>`. The wrapper holds the real fill on its `backgroundPainter` field,
    // so unwrap to it and resolve the flat colour from there; the export then draws the card as a
    // vector fill with editable text. Only the colour is recovered (not the per-item morph/scale) —
    // an image/gradient-backed card still has a non-`ColorPainter` base and correctly falls through
    // to the raster path below.
    val fillPainter =
      if (painter.javaClass.simpleName == "BackgroundPainter") {
        // A BackgroundPainter also carries a BorderStroke (its `border` field) — an OutlinedCard /
        // bordered surface morphs its outline through the same wrapper. We only recover the inner
        // fill colour here, not the border (nor the wrapper's morphing shape), so resolving a
        // bordered wrapper would make `backgroundColor` non-null, skip the `<image>` fallback, and
        // drop the outline from the vector export. Leave bordered wrappers unresolved so the raster
        // path preserves the full pixels (fill + border); only a borderless wrapper — the common
        // filled TitleCard/Card — collapses to a flat vector fill with editable text.
        val border =
          runCatching {
              painter.javaClass
                .getDeclaredField("border")
                .apply { isAccessible = true }
                .get(painter)
            }
            .getOrNull()
        if (border != null) return null
        runCatching {
            painter.javaClass
              .getDeclaredField("backgroundPainter")
              .apply { isAccessible = true }
              .get(painter)
          }
          .getOrNull() ?: return null
      } else {
        // Any *other* delegating painter is unwrapped structurally rather than by name (issue
        // #2615). Wear's scaling list wraps a surface's fill in more than one shape depending on
        // the component — a `FilledIconButton` or a transformed `SurfaceTransformation` card does
        // not necessarily arrive as `BackgroundPainter` — and each unrecognised wrapper collapsed
        // its whole container to a raster, taking the editable subtree with it: exactly the
        // "transformed surfaces absent, reduced to isolated raster/icon leaves" symptom. Following
        // a single painter-typed field down to a `ColorPainter` recovers the flat fill without
        // needing to know the wrapper's class.
        unwrapDelegatingPainter(painter)
      }
    if (fillPainter.javaClass.simpleName != "ColorPainter") return null
    val baseArgb =
      runCatching {
          val field = fillPainter.javaClass.getDeclaredField("color").apply { isAccessible = true }
          val packed = field.getLong(fillPainter).toULong()
          if (packed and 0xFFFFFFFFuL != 0uL) return null
          (packed shr 32).toInt()
        }
        .getOrNull() ?: return null
    if (baseArgb == 0) return null
    // Fold `Modifier.paint`'s alpha multiplier into the colour's alpha channel (default 1 =
    // opaque).
    val alpha =
      (floatValue(elements["alpha"])
          ?: runCatching {
              mod.javaClass.getDeclaredField("alpha").apply { isAccessible = true }.getFloat(mod)
            }
            .getOrNull()
          ?: 1f)
        .coerceIn(0f, 1f)
    val argb =
      if (alpha >= 1f) baseArgb
      else {
        val a = ((baseArgb ushr 24) and 0xFF) * alpha
        val newA = a.roundToInt().coerceIn(0, 255)
        if (newA == 0) return null
        (baseArgb and 0x00FFFFFF) or (newA shl 24)
      }
    return "#${String.format(Locale.US, "%08X", argb)}"
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

  /**
   * Resolves the content-loading placeholder a node's modifier chain declares (issue #2646) — the
   * state-aware counterpart of [resolve]'s container tokens.
   *
   * The identity of a placeholder modifier lives in [PlaceholderModifiers]; what this adds is the
   * part the modifier chain alone can't tell the exporter: whether the placeholder is currently
   * **visible**, read off its `PlaceholderState`, plus the placeholder's own colour and shape (the
   * shape [resolve] deliberately refuses as a container corner). A `placeholder` block and a
   * `placeholderShimmer` sweep on the same chain collapse to one projection — the block wins, since
   * it is what an active placeholder actually paints — with the shimmer contributing its own state
   * only when the block left it unknown.
   *
   * Returns null for the overwhelming majority of nodes: no placeholder modifier on the chain.
   */
  fun resolvePlaceholder(
    modifierInfo: List<ModifierInfo>,
    sizeWidthPx: Int,
    sizeHeightPx: Int,
    density: Float,
  ): LayoutInspectorPlaceholder? =
    resolvePlaceholderElements(modifierInfo.map { it.modifier }, sizeWidthPx, sizeHeightPx, density)

  /**
   * [resolvePlaceholder] over the bare modifier *elements* — everything it needs, since a
   * placeholder carries no per-entry coordinates. Split out so unit tests can feed fake elements
   * without minting a `LayoutCoordinates`.
   */
  internal fun resolvePlaceholderElements(
    elements: List<Any>,
    sizeWidthPx: Int,
    sizeHeightPx: Int,
    density: Float,
  ): LayoutInspectorPlaceholder? {
    var kind: String? = null
    var visible: Boolean? = null
    var colorArgb: String? = null
    var cornerRadius: String? = null
    var cornerRadiusPx: String? = null
    var shape: String? = null
    val minSidePx = minOf(sizeWidthPx, sizeHeightPx)
    for (mod in elements) {
      val inspectable = mod as? InspectableValue
      val inspected = inspectable?.inspectableElements?.associate { it.name to it.value }.orEmpty()
      // Two ways in, because the two placeholder modifiers lower differently:
      //  - `placeholderShimmer` has its own `PlaceholderShimmerElement`, matched by name/class;
      //  - `placeholder` is a bare `drawWithContent { … }.graphicsLayer { … }`, recognised only by
      //    the origin of the lambda it carries (`…material3.PlaceholderKt`), found by scanning what
      //    the element captured.
      val captured = capturedValues(mod)
      val modKind =
        PlaceholderModifiers.kindOf(inspectable?.nameFallback, mod.javaClass.simpleName)
          ?: PlaceholderModifiers.KIND_PLACEHOLDER.takeIf {
            captured.any { v -> PlaceholderModifiers.isPlaceholderOrigin(v.javaClass.name) }
          }
          ?: continue
      // The block (`placeholder`) is the authoritative source; a shimmer-only chain still reports,
      // so a shimmering-but-not-blocked node isn't silently dropped.
      val authoritative = kind != PlaceholderModifiers.KIND_PLACEHOLDER
      if (authoritative) kind = modKind
      placeholderVisible(captured)?.let { if (authoritative || visible == null) visible = it }
      if (authoritative || colorArgb == null) {
        placeholderColorHex(mod, inspected, captured)?.let { colorArgb = it }
      }
      val phShape =
        (shapeOf(mod, inspected) ?: captured.filterIsInstance<Shape>().firstOrNull())
          ?.effectiveCornerShape()
      if (phShape != null && (authoritative || cornerRadius == null)) {
        cornerRadius = phShape.cornerRadiusWire(minSidePx, density)
        cornerRadiusPx = if (cornerRadius == null) phShape.cornerRadiusPxWire() else null
        shape = phShape.shapeDescriptor()
      }
    }
    return kind?.let {
      LayoutInspectorPlaceholder(
        kind = it,
        visible = visible,
        colorArgb = colorArgb,
        cornerRadius = cornerRadius,
        cornerRadiusPx = cornerRadiusPx,
        shape = shape,
      )
    }
  }

  /**
   * True when this single modifier element *is* placeholder chrome — either the shimmer's own
   * element or one of the anonymous `drawWithContent` / `graphicsLayer` entries
   * `Modifier.placeholder` lowers to (recognised through the origin of the lambda it captured).
   *
   * Per-entry, unlike [resolvePlaceholderElements]'s per-node projection: the export needs to drop
   * the placeholder's own pass-through draw *without* dropping an unrelated `Modifier.drawBehind`
   * sharing the chain, whose pixels really are in the frame.
   */
  fun isPlaceholderElement(mod: Any): Boolean {
    val inspectable = mod as? InspectableValue
    if (
      PlaceholderModifiers.isPlaceholderModifier(
        inspectable?.nameFallback,
        mod.javaClass.simpleName,
      )
    ) {
      return true
    }
    return capturedValues(mod).any { PlaceholderModifiers.isPlaceholderOrigin(it.javaClass.name) }
  }

  /**
   * Everything a modifier element carries that a placeholder's identity/state could hide in: the
   * element itself, its own field values, and the field values of *those* — because the block
   * placeholder keeps its `PlaceholderState`, shape and colour inside the `drawWithContent` lambda
   * the element holds, one level deeper than a normal element's inspectable properties.
   *
   * Two levels is the depth that reaches `element → lambda → captured state` and no further, so
   * this stays a bounded read of a handful of fields rather than an object-graph walk.
   */
  private fun capturedValues(mod: Any): List<Any> {
    val out = mutableListOf(mod)
    val direct = fieldValues(mod)
    out.addAll(direct)
    direct.forEach { out.addAll(fieldValues(it)) }
    return out
  }

  private fun fieldValues(target: Any): List<Any> =
    runCatching {
        target.javaClass.declaredFields.mapNotNull { field ->
          runCatching { field.apply { isAccessible = true }.get(target) }.getOrNull()
        }
      }
      .getOrDefault(emptyList())

  /**
   * Whether a placeholder is currently painting over the content, read from the `PlaceholderState`
   * among the modifier's [captured] values. The state's API has moved across Wear releases
   * (`isVisible` today, `isShowContent` — the inverse — earlier), and the property may be backed by
   * a Compose `State` rather than a plain field, so both spellings and both storage shapes are
   * probed. Returns null when no state is found or neither property is readable; the export treats
   * unknown as "not visible" (see [LayoutInspectorPlaceholder.visible]).
   */
  private fun placeholderVisible(captured: List<Any>): Boolean? {
    for (candidate in captured) {
      if (!candidate.javaClass.simpleName.contains("PlaceholderState")) continue
      booleanProperty(candidate, "isVisible")?.let {
        return it
      }
      booleanProperty(candidate, "isShowContent")?.let {
        return !it
      }
    }
    return null
  }

  /**
   * Reads a boolean property off an object by no-arg getter then backing field, unwrapping a
   * Compose `State`/`MutableState` holder (`getValue()`) when that's what the property stores.
   */
  private fun booleanProperty(target: Any, name: String): Boolean? {
    val raw =
      runCatching { target.javaClass.getMethod(name).invoke(target) }.getOrNull()
        ?: reflectField(target, name)
        ?: return null
    return unwrapBoolean(raw)
  }

  private fun unwrapBoolean(raw: Any): Boolean? =
    when (raw) {
      is Boolean -> raw
      is androidx.compose.runtime.State<*> -> raw.value as? Boolean
      else -> null
    }

  private fun reflectField(target: Any, name: String): Any? {
    var cls: Class<*>? = target.javaClass
    while (cls != null && cls != Any::class.java) {
      val value =
        runCatching { cls.getDeclaredField(name).apply { isAccessible = true }.get(target) }
          .getOrNull()
      if (value != null) return value
      cls = cls.superclass
    }
    return null
  }

  /**
   * The placeholder block's colour. `Modifier.placeholderShimmer(state, shape, color)` projects
   * `color` through the inspector; the block placeholder captures it as a packed-long `Color` on
   * its draw lambda, so the [captured] values are scanned for a `color`-named long field as the
   * fallback.
   */
  private fun placeholderColorHex(
    mod: Any,
    elements: Map<String, Any?>,
    captured: List<Any>,
  ): String? {
    (elements["color"] as? Color)?.let {
      return if (it == Color.Unspecified) null else colorToWireString(it)
    }
    captured.forEach { candidate ->
      colorFieldHex(candidate)?.let {
        return it
      }
    }
    return null
  }

  /**
   * A `Color`-valued field on [target] — the value class inlines to a packed `long`, whose low 32
   * bits carry the colour space (non-zero for anything but sRGB, which is left unresolved rather
   * than mis-decoded).
   *
   * Fields are matched by name (`color`, `$color`) so an unrelated long is never read as a colour —
   * *except* on a class that is itself part of a placeholder implementation, where the name is
   * gone: the block placeholder's draw lambda is compiled to an `invokedynamic` class whose
   * captures are synthetic `arg$N` fields. There, any long that decodes as an opaque-space colour
   * is taken, which is safe precisely because the enclosing class is already known to be
   * placeholder code.
   */
  private fun colorFieldHex(target: Any): String? {
    val anyLongIsAColor = PlaceholderModifiers.isPlaceholderOrigin(target.javaClass.name)
    val fields =
      runCatching { target.javaClass.declaredFields }
        .getOrNull()
        ?.filter {
          it.type == Long::class.javaPrimitiveType &&
            (anyLongIsAColor || it.name.contains("color", ignoreCase = true))
        } ?: return null
    for (field in fields) {
      val packed =
        runCatching { field.apply { isAccessible = true }.getLong(target) }.getOrNull()?.toULong()
          ?: continue
      if (packed and 0xFFFFFFFFuL != 0uL) continue
      val argb = (packed shr 32).toInt()
      if (argb != 0) return "#${String.format(Locale.US, "%08X", argb)}"
    }
    return null
  }

  /** The inspector `shape` element, or a reflected `shape` field on the modifier element. */
  private fun shapeOf(mod: Any, elements: Map<String, Any?>): Shape? {
    (elements["shape"] as? Shape)?.let {
      return it
    }
    runCatching {
        val field = mod.javaClass.getDeclaredField("shape").apply { isAccessible = true }
        field.get(mod) as? Shape
      }
      .getOrNull()
      ?.let {
        return it
      }
    // A Wear scaling card (`TransformingLazyColumn` + `SurfaceTransformation`) fills through a
    // `Modifier.paint(BackgroundPainter)` whose rounded/morphing shape rides on the *painter*
    // (`BackgroundPainter.shape`), not the modifier — so without this a vectorised card would draw
    // as a sharp rect, losing its corner radius. Surface the wrapper's shape here so the existing
    // corner-radius resolution rounds the exported fill. Best-effort: a non-`CornerBasedShape`
    // (e.g.
    // a bespoke morph shape) yields no corners downstream and the card simply stays square, as
    // before — no regression.
    return runCatching {
        val painter =
          mod.javaClass.getDeclaredField("painter").apply { isAccessible = true }.get(mod)
        if (painter?.javaClass?.simpleName != "BackgroundPainter") return null
        painter.javaClass.getDeclaredField("shape").apply { isAccessible = true }.get(painter)
          as? Shape
      }
      .getOrNull()
  }

  /**
   * Material 3 expressive buttons expose their clipping shape through the anonymous
   * `rememberAnimatedShape` wrapper. The wrapper is a [Shape], but not a `CornerBasedShape`, so its
   * effective corners live on the wrapper's `state.morphedShape` and the ordinary corner getters
   * above cannot see them. Resolve that current shape reflectively rather than coupling this module
   * to Material 3 internals.
   *
   * This is deliberately a narrow fallback: normal corner shapes retain their direct getter path,
   * and an unknown shape without a state exposing `getMorphedShape()` is returned unchanged.
   */
  private fun Shape.effectiveCornerShape(): Shape {
    if (invokeNoArg("getTopStart") != null) return this
    return javaClass.declaredFields
      .asSequence()
      .filter { it.name.endsWith("state", ignoreCase = true) }
      .mapNotNull { field ->
        runCatching {
            field.isAccessible = true
            field.get(this)
          }
          .getOrNull()
      }
      .mapNotNull { state -> state.invokeNoArg("getMorphedShape") as? Shape }
      .firstOrNull() ?: this
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
