package ee.schimke.composeai.daemon

import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.CompositionGroup
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.ModifierInfo
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.InspectableValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnitType
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductFacet
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.daemon.protocol.RecordingProbeNode
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsProduct
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorProduct
import ee.schimke.composeai.data.layoutinspector.SemanticsRefs
import ee.schimke.composeai.data.render.PreviewContext
import ee.schimke.composeai.data.render.extensions.compose.ExtensionSlotTables
import ee.schimke.composeai.data.render.pipeline.SamplingPolicy
import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import java.lang.reflect.Method
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath

/** Producer for `compose/semantics`, a compact SemanticsNode projection for inspector clients. */
object ComposeSemanticsDataProducer {
  const val KIND: String = ComposeSemanticsProduct.KIND
  const val SCHEMA_VERSION: Int = ComposeSemanticsProduct.SCHEMA_VERSION
  const val FILE: String = ComposeSemanticsProduct.FILE

  private val json = Json {
    encodeDefaults = false
    prettyPrint = false
  }

  fun writeArtifacts(
    rootDir: File,
    previewId: String,
    root: SemanticsNode,
    fileSystem: FileSystem = SystemFileSystem,
    density: Float = 1f,
  ) {
    val previewDir = rootDir.resolve(previewId).also { it.mkdirs() }
    val payload = buildPayload(root, density)
    fileSystem.write(previewDir.resolve(FILE).path.toPath()) {
      writeUtf8(json.encodeToString(ComposeSemanticsPayload.serializer(), payload))
    }
  }

  /**
   * Projects a captured semantics [root] into the stable wire model. Public so the wireframe
   * producer (and any other derived view) reuses the exact same projection — label precedence,
   * bounds formatting, merge-mode mapping — rather than re-walking the tree with different rules.
   *
   * [density] is the render density (dp = px / density). It is only needed to express a
   * percent-based corner radius (`CircleShape`) as dp; the default of `1f` leaves px-equals-dp
   * captures (and the token text/colour fields, which carry dp directly) unchanged (issue #1908).
   */
  fun buildPayload(root: SemanticsNode, density: Float = 1f): ComposeSemanticsPayload =
    SemanticsRefs.assign(ComposeSemanticsPayload(root = root.toWireNode(density)))

  private val probeNodesSerializer = ListSerializer(RecordingProbeNode.serializer())

  /**
   * Flatten a captured semantics [root] into the compact probe-node list `record_preview` attaches
   * to a `recording.probe`'s evidence (issue #1786). Reuses [buildPayload] so the testTag / text /
   * role / clickable projection matches the `compose/semantics` data product and target resolution
   * (issue #1784) exactly.
   * [RecordingTestGenerator][ee.schimke.composeai.daemon.RecordingTestGenerator] diffs consecutive
   * probe snapshots into assertions, so only nodes carrying a stable finder (testTag, rendered
   * text, or content description) are kept — everything else is dropped here rather than leaking a
   * finder-less node the generator can't assert on.
   */
  fun probeNodes(root: SemanticsNode): List<RecordingProbeNode> =
    buildPayload(root).root.toProbeNodes()

  /**
   * [probeNodes] serialised to a JSON string. Android captures the probe snapshot **inside** the
   * Robolectric sandbox, where `RecordingProbeNode` is acquired by the instrumenting classloader; a
   * typed list returned across the bridge would arrive as sandbox-loaded objects that fail the
   * host-side `RecordingProbeNode` cast / JSON serialization (the same reason `RenderResult` is
   * copied across, and why the bridge otherwise only passes `java.lang.String`). Crossing as a
   * String (do-not-acquire) sidesteps the boundary; the host re-parses with [decodeProbeNodes].
   */
  fun probeNodesJson(root: SemanticsNode): String =
    json.encodeToString(probeNodesSerializer, probeNodes(root))

  /** Host-side inverse of [probeNodesJson] — re-parse the bridged payload into host DTOs. */
  fun decodeProbeNodes(payload: String): List<RecordingProbeNode> =
    json.decodeFromString(probeNodesSerializer, payload)

  private fun SemanticsNode.toWireNode(density: Float): ComposeSemanticsNode {
    val cfg = config
    val layout = cfg.layoutDetails()
    return ComposeSemanticsNode(
      nodeId = id.toString(),
      boundsInRoot = boundsInRoot.toWireBounds(),
      label = cfg.label(),
      text = cfg.renderedText(),
      layoutText = layout?.text,
      layoutFontSize = layout?.fontSize,
      layoutForegroundColor = layout?.foregroundColor,
      layoutBackgroundColor = layout?.backgroundColor,
      layoutLineCount = layout?.lineCount,
      layoutMaxLines = layout?.maxLines,
      layoutOverflow = layout?.overflow,
      layoutTruncated = layout?.truncated,
      layoutDidOverflowWidth = layout?.didOverflowWidth,
      layoutDidOverflowHeight = layout?.didOverflowHeight,
      editableText = cfg.getOrNull(SemanticsProperties.EditableText)?.text,
      inputText = cfg.getOrNull(SemanticsProperties.InputText)?.text,
      role = cfg.getOrNull(SemanticsProperties.Role)?.toString(),
      testTag = cfg.getOrNull(SemanticsProperties.TestTag),
      mergeMode =
        when {
          cfg.isClearingSemantics -> "clearAndSet"
          cfg.isMergingSemanticsOfDescendants -> "mergeDescendants"
          else -> null
        },
      clickable = cfg.getOrNull(SemanticsActions.OnClick) != null,
      tokens = resolvedTokens(density),
      children = children.map { it.toWireNode(density) },
    )
  }

  /**
   * Projects the design-token data carried by this node's Compose modifiers (issue #1897): the
   * resolved container colour (`Modifier.background`, which `Surface`/`Card` apply), the corner
   * radius of its `background` / `clip` / `border` shape, and its `Modifier.padding`. Returns null
   * when the node declares none of them — the common case for pure layout / text nodes.
   *
   * Modifiers are read off the node's [LayoutInfo.getModifierInfo] entries. The preferred source is
   * each entry's [InspectableValue] projection (the same `nameFallback` / `inspectableElements`
   * surface the layout inspector uses), but some foundation elements (notably `BackgroundElement`)
   * don't populate inspector info on the desktop/skiko build, so each lookup falls back to
   * reflecting the element's backing field. Reflection (rather than a `compose.foundation` compile
   * dependency) also keeps this module foundation-free, matching the layout inspector's approach.
   */
  private fun SemanticsNode.resolvedTokens(density: Float): ComposeSemanticsTokens? {
    val modifiers =
      try {
        layoutInfo.getModifierInfo()
      } catch (_: Throwable) {
        return null
      }
    var backgroundColor: String? = null
    var borderColor: String? = null
    var cornerRadius: String? = null
    var shape: String? = null
    var padding: ComposeSemanticsInsets? = null
    // `CircleShape` / `CornerSize(50%)` resolve to dp against the node's shorter measured side.
    val minSidePx = minOf(size.width, size.height)
    for (info in modifiers) {
      val mod = info.modifier
      val inspectable = mod as? InspectableValue
      val name = inspectable?.nameFallback
      val elements = inspectable?.inspectableElements?.associate { it.name to it.value }.orEmpty()
      val simpleName = mod.javaClass.simpleName

      if (backgroundColor == null && (name == "background" || simpleName == "BackgroundElement")) {
        backgroundColor = backgroundColorHex(mod, elements, inspectable?.valueOverride)
      }
      // `Modifier.border` carries the outline colour `Surface`/`Card`/dividers apply — a role
      // colour
      // (`outline` / `outlineVariant`) a plain `Modifier.background` never sees (issue #1908).
      if (borderColor == null && (name == "border" || simpleName.startsWith("BorderModifier"))) {
        borderColor = borderColorHex(mod, elements)
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
        if (shape == null) shape = nodeShape.shapeDescriptor()
      }
    }
    val gap = arrangementGapWire()
    return if (
      backgroundColor == null &&
        borderColor == null &&
        cornerRadius == null &&
        shape == null &&
        gap == null &&
        padding == null
    )
      null
    else
      ComposeSemanticsTokens(
        backgroundColor = backgroundColor,
        borderColor = borderColor,
        cornerRadius = cornerRadius,
        shape = shape,
        gap = gap,
        padding = padding,
      )
  }

  /**
   * Resolves the inter-child spacing of a `Row`/`Column` from its measure policy (issue #1908).
   * `Arrangement.spacedBy(n)` is stored on the `Row`/`Column`MeasurePolicy as an
   * `Arrangement.HorizontalOrVertical` whose `spacing` `Dp` is kept in a `spacing` float field; the
   * value-class `getSpacing` getter is name-mangled, so the field is read directly. Reflection (not
   * a `compose.foundation` dependency) keeps this module foundation-free, matching the rest of the
   * extractor. Returns null when the layout has no arrangement spacing.
   */
  private fun SemanticsNode.arrangementGapWire(): String? {
    val policy =
      runCatching { layoutInfo.javaClass.getMethod("getMeasurePolicy").invoke(layoutInfo) }
        .getOrNull() ?: return null
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
   * Resolves a `border` modifier's stroke colour as ARGB hex. `Modifier.border` projects its colour
   * through the inspector `color` element even when the brush is a plain `SolidColor`; that's read
   * first, falling back to reflecting the backing `brush` field's `SolidColor.value`. A gradient
   * brush (no single colour) is skipped (issue #1908).
   */
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

  private fun SemanticsConfiguration.label(): String? {
    getOrNull(SemanticsProperties.ContentDescription)
      ?.joinToString(" ")
      ?.takeIf { it.isNotBlank() }
      ?.let {
        return it
      }
    return getOrNull(SemanticsProperties.Text)
      ?.joinToString(" ") { it.text }
      ?.takeIf { it.isNotBlank() }
  }

  private fun SemanticsConfiguration.renderedText(): String? =
    getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }?.takeIf { it.isNotBlank() }

  private fun SemanticsConfiguration.layoutDetails(): LayoutTextDetails? {
    val action = getOrNull(SemanticsActions.GetTextLayoutResult)?.action ?: return null
    val results = mutableListOf<TextLayoutResult>()
    val ok =
      try {
        action(results)
      } catch (_: Throwable) {
        false
      }
    if (!ok && results.isEmpty()) return null
    val text =
      results
        .mapNotNull { it.layoutInput.text.text.takeIf { text -> text.isNotBlank() } }
        .distinct()
        .joinToString(" ")
        .takeIf { it.isNotBlank() }
    val fontSize =
      results
        .map { it.layoutInput.style.fontSize }
        .filter { it.type == TextUnitType.Sp }
        .map { it.value }
        .distinct()
        .singleOrNull()
        ?.let { "${it}sp" }
    val truncated = results.any { it.hasVisualOverflow }
    val didOverflowWidth = results.any { it.didOverflowWidth }
    val didOverflowHeight = results.any { it.didOverflowHeight }
    val lineCount = results.sumOf { it.lineCount }.takeIf { it > 0 }
    val maxLines =
      results
        .map { it.layoutInput.maxLines }
        .filter { it != Int.MAX_VALUE && it > 0 }
        .distinct()
        .singleOrNull()
    val overflow =
      results
        .map { it.layoutInput.overflow.toString() }
        .distinct()
        .singleOrNull()
        ?.takeIf { it.isNotBlank() }
    return LayoutTextDetails(
      text = text,
      fontSize = fontSize,
      foregroundColor =
        unambiguousColor(results.flatMap { it.textColors() })?.let(::colorToWireString),
      backgroundColor =
        unambiguousColor(results.flatMap { it.backgroundColors() })?.let(::colorToWireString),
      lineCount = lineCount,
      maxLines = maxLines,
      overflow = overflow,
      truncated = truncated.takeIf { results.isNotEmpty() },
      didOverflowWidth = didOverflowWidth.takeIf { results.isNotEmpty() },
      didOverflowHeight = didOverflowHeight.takeIf { results.isNotEmpty() },
    )
  }

  private fun TextLayoutResult.textColors(): List<Color> = buildList {
    add(layoutInput.style.color)
    layoutInput.text.spanStyles.forEach { add(it.item.color) }
  }

  private fun TextLayoutResult.backgroundColors(): List<Color> = buildList {
    add(layoutInput.style.background)
    layoutInput.text.spanStyles.forEach { add(it.item.background) }
  }

  private fun unambiguousColor(colors: List<Color>): Color? =
    colors.filter { it != Color.Unspecified }.distinct().singleOrNull()

  private fun colorToWireString(color: Color): String =
    "#${String.format(Locale.US, "%08X", color.toArgb())}"

  private data class LayoutTextDetails(
    val text: String?,
    val fontSize: String?,
    val foregroundColor: String?,
    val backgroundColor: String?,
    val lineCount: Int?,
    val maxLines: Int?,
    val overflow: String?,
    val truncated: Boolean?,
    val didOverflowWidth: Boolean?,
    val didOverflowHeight: Boolean?,
  )

  private fun androidx.compose.ui.geometry.Rect.toWireBounds(): String =
    "${left.toInt()},${top.toInt()},${right.toInt()},${bottom.toInt()}"
}

/**
 * Flatten a projected semantics tree into the compact [RecordingProbeNode] list (issue #1786),
 * keeping only nodes with a stable Compose-test finder. `contentDescription` is recovered from
 * [ComposeSemanticsNode.label] when it carries something other than the rendered [text] — the
 * projection collapses content-description-or-text into `label`, so a label that isn't just echoing
 * `text` is the node's content description.
 */
fun ComposeSemanticsNode.toProbeNodes(): List<RecordingProbeNode> = buildList {
  fun visit(node: ComposeSemanticsNode) {
    val testTag = node.testTag?.takeIf { it.isNotBlank() }
    val text = node.text?.takeIf { it.isNotBlank() }
    val contentDescription = node.label?.takeIf { it.isNotBlank() && it != text }
    if (testTag != null || text != null || contentDescription != null) {
      add(
        RecordingProbeNode(
          testTag = testTag,
          text = text,
          contentDescription = contentDescription,
          role = node.role?.takeIf { it.isNotBlank() },
          clickable = node.clickable,
        )
      )
    }
    node.children.forEach(::visit)
  }
  visit(this@toProbeNodes)
}

typealias ComposeSemanticsPayload =
  ee.schimke.composeai.data.layoutinspector.ComposeSemanticsPayload

typealias ComposeSemanticsNode = ee.schimke.composeai.data.layoutinspector.ComposeSemanticsNode

typealias ComposeSemanticsTokens = ee.schimke.composeai.data.layoutinspector.ComposeSemanticsTokens

typealias ComposeSemanticsInsets = ee.schimke.composeai.data.layoutinspector.ComposeSemanticsInsets

typealias LayoutInspectorPayload = ee.schimke.composeai.data.layoutinspector.LayoutInspectorPayload

typealias LayoutInspectorNode = ee.schimke.composeai.data.layoutinspector.LayoutInspectorNode

typealias LayoutInspectorBounds = ee.schimke.composeai.data.layoutinspector.LayoutInspectorBounds

typealias LayoutInspectorSize = ee.schimke.composeai.data.layoutinspector.LayoutInspectorSize

typealias LayoutInspectorConstraints =
  ee.schimke.composeai.data.layoutinspector.LayoutInspectorConstraints

typealias LayoutInspectorModifier =
  ee.schimke.composeai.data.layoutinspector.LayoutInspectorModifier

/** Producer for `layout/inspector`, backed by Compose's RootForTest/LayoutNode tree. */
object LayoutInspectorDataProducer {
  const val KIND: String = LayoutInspectorProduct.KIND
  const val SCHEMA_VERSION: Int = LayoutInspectorProduct.SCHEMA_VERSION
  const val FILE: String = LayoutInspectorProduct.FILE

  private val json = Json {
    encodeDefaults = false
    prettyPrint = false
  }

  fun writeArtifacts(
    rootDir: File,
    previewId: String,
    previewContext: PreviewContext,
    fileSystem: FileSystem = SystemFileSystem,
  ) {
    val capture = LayoutInspectorCaptureContext.from(previewContext) ?: return
    val layoutRoot = ComposeLayoutInspector.inspect(capture) ?: return
    val previewDir = rootDir.resolve(previewId).also { it.mkdirs() }
    val payload = LayoutInspectorPayload(root = layoutRoot)
    fileSystem.write(previewDir.resolve(FILE).path.toPath()) {
      writeUtf8(json.encodeToString(LayoutInspectorPayload.serializer(), payload))
    }
  }
}

internal data class LayoutInspectorCaptureContext(
  val rootSemanticsNode: Any,
  val slotTables: ExtensionSlotTables = ExtensionSlotTables.Empty,
) {
  companion object {
    fun from(previewContext: PreviewContext): LayoutInspectorCaptureContext? {
      val root = previewContext.inspection.rootForTest as? RootForTest ?: return null
      return LayoutInspectorCaptureContext(
        rootSemanticsNode = root.semanticsOwner.unmergedRootSemanticsNode,
        slotTables =
          ExtensionSlotTables.of(
            previewContext.inspection.slotTables.filterIsInstance<CompositionData>()
          ),
      )
    }
  }
}

/**
 * Domain facade for turning Compose's runtime layout tree into the stable layout-inspector wire
 * model.
 *
 * Callers should not know whether the implementation uses public APIs, internal Compose APIs, or
 * reflection. The public surface is the model we want: inspect a root semantics node and slot table
 * context, get a [LayoutInspectorNode].
 */
internal object ComposeLayoutInspector {
  fun inspect(context: LayoutInspectorCaptureContext): LayoutInspectorNode? {
    val root = LayoutTreeAccess.rootLayoutNode(context.rootSemanticsNode) ?: return null
    val sources = LayoutSourceIndex(context.slotTables)
    return root.toWireNode(rootCoordinates = null, sources = sources)
  }

  private fun LayoutNodeFacade.toWireNode(
    rootCoordinates: LayoutCoordinates?,
    sources: LayoutSourceIndex,
  ): LayoutInspectorNode {
    val rootCoords = rootCoordinates ?: coordinates
    val source = sources.sourceFor(raw)
    val children = children.map { it.toWireNode(rootCoords, sources) }
    return LayoutInspectorNode(
      nodeId = semanticsId?.toString() ?: identityId,
      component = source?.component ?: componentFallback,
      source = source?.source,
      sourceInfo = source?.sourceInfo,
      bounds = coordinates.boundsIn(rootCoords),
      size = LayoutInspectorSize(width = width, height = height),
      constraints = constraints,
      placed = placed,
      attached = attached,
      zIndex = zIndex,
      modifiers = modifierInfo.mapNotNull { info -> info.toWireModifier(rootCoords) },
      children = children,
    )
  }

  private fun ModifierInfo.toWireModifier(
    rootCoordinates: LayoutCoordinates?
  ): LayoutInspectorModifier? {
    val inspectable = modifier as? InspectableValue
    val name =
      inspectable?.nameFallback?.takeIf { it.isNotBlank() } ?: modifier.javaClass.simpleName
    val value = inspectable?.valueOverride?.wireValue()
    val properties =
      inspectable?.inspectableElements?.associate { it.name to it.value.wireValue() }.orEmpty()
    return LayoutInspectorModifier(
      name = name,
      value = value,
      properties = properties,
      bounds = coordinates.boundsIn(rootCoordinates),
    )
  }

  private fun LayoutCoordinates?.boundsIn(
    rootCoordinates: LayoutCoordinates?
  ): LayoutInspectorBounds =
    if (this == null || rootCoordinates == null) {
      LayoutInspectorBounds(0, 0, 0, 0)
    } else {
      val rect =
        try {
          rootCoordinates.localBoundingBoxOf(this, clipBounds = false)
        } catch (_: Throwable) {
          null
        }
      LayoutInspectorBounds(
        left = rect?.left?.roundToInt() ?: 0,
        top = rect?.top?.roundToInt() ?: 0,
        right = rect?.right?.roundToInt() ?: 0,
        bottom = rect?.bottom?.roundToInt() ?: 0,
      )
    }

  private fun Any?.wireValue(): String =
    when (this) {
      null -> "null"
      is String -> this
      is Number,
      is Boolean -> toString()
      else -> toString()
    }

  private data class LayoutSource(
    val component: String,
    val source: String?,
    val sourceInfo: String?,
  )

  private class LayoutSourceIndex(slotTables: ExtensionSlotTables) {
    private val byNode = java.util.IdentityHashMap<Any, LayoutSource>()

    init {
      slotTables
        .snapshot()
        .asSequence()
        .flatMap { it.compositionGroups.asSequence() }
        .flatMap { it.flattenGroups().asSequence() }
        .forEach { group ->
          val node = group.node ?: return@forEach
          val sourceInfo = group.sourceInfo
          if (sourceInfo != null) {
            byNode[node] =
              LayoutSource(
                component = sourceInfo.componentName() ?: node.javaClass.simpleName,
                source = sourceInfo.sourceLocation(),
                sourceInfo = sourceInfo,
              )
          }
        }
    }

    fun sourceFor(node: Any): LayoutSource? = byNode[node]
  }

  private fun CompositionGroup.flattenGroups(): List<CompositionGroup> =
    listOf(this) + compositionGroups.flatMap { it.flattenGroups() }

  private fun String.componentName(): String? =
    Regex("""C\(([^)]+)\)""").find(this)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

  private fun String.sourceLocation(): String? {
    val file = Regex("""([A-Za-z0-9_./-]+\.kt)""").find(this)?.groupValues?.getOrNull(1)
    val line =
      Regex("""@(?:\d+)?L(\d+)""").find(this)?.groupValues?.getOrNull(1)
        ?: Regex("""(?::|@)(\d+)""").find(this)?.groupValues?.getOrNull(1)
    return when {
      file != null && line != null -> "${file.substringAfterLast('/')}:$line"
      file != null -> file.substringAfterLast('/')
      else -> null
    }
  }

  private class LayoutNodeFacade(val raw: Any) {
    val coordinates: LayoutCoordinates?
      get() = LayoutTreeAccess.coordinates(raw)

    val semanticsId: Int?
      get() = LayoutTreeAccess.semanticsId(raw)

    val identityId: String = "${raw.javaClass.name}@${System.identityHashCode(raw).toString(16)}"

    val componentFallback: String
      get() = LayoutTreeAccess.measurePolicyName(raw) ?: raw.javaClass.simpleName

    val width: Int
      get() = LayoutTreeAccess.width(raw)

    val height: Int
      get() = LayoutTreeAccess.height(raw)

    val constraints: LayoutInspectorConstraints?
      get() = LayoutTreeAccess.constraints(raw)

    val placed: Boolean
      get() = LayoutTreeAccess.isPlaced(raw)

    val attached: Boolean
      get() = LayoutTreeAccess.isAttached(raw)

    val zIndex: Float?
      get() = LayoutTreeAccess.zIndex(raw)?.takeIf { it != 0f }

    val modifierInfo: List<ModifierInfo>
      get() = LayoutTreeAccess.modifierInfo(raw)

    val children: List<LayoutNodeFacade>
      get() = LayoutTreeAccess.children(raw).map(::LayoutNodeFacade)
  }

  /** Private adapter over Compose UI implementation details. */
  private object LayoutTreeAccess {
    fun rootLayoutNode(semanticsNode: Any): LayoutNodeFacade? =
      (call(semanticsNode, "getLayoutNode\$ui_release") ?: call(semanticsNode, "getLayoutInfo"))
        ?.let(::LayoutNodeFacade)

    fun coordinates(node: Any): LayoutCoordinates? =
      call(node, "getCoordinates") as? LayoutCoordinates

    fun semanticsId(node: Any): Int? = call(node, "getSemanticsId") as? Int

    fun width(node: Any): Int = call(node, "getWidth") as? Int ?: 0

    fun height(node: Any): Int = call(node, "getHeight") as? Int ?: 0

    fun isPlaced(node: Any): Boolean = call(node, "isPlaced") as? Boolean ?: true

    fun isAttached(node: Any): Boolean = call(node, "isAttached") as? Boolean ?: true

    fun modifierInfo(node: Any): List<ModifierInfo> =
      (call(node, "getModifierInfo") as? Iterable<*>)?.filterIsInstance<ModifierInfo>()
        ?: emptyList()

    fun children(node: Any): List<Any> =
      sequenceOf("getZSortedChildren", "getChildren\$ui_release", "getFoldedChildren\$ui_release")
        .mapNotNull { call(node, it) as? Iterable<*> }
        .firstOrNull()
        ?.filterNotNull() ?: emptyList()

    fun constraints(node: Any): LayoutInspectorConstraints? {
      val delegate = call(node, "getLayoutDelegate\$ui_release") ?: return null
      val constraints = call(delegate, "getLastConstraints-DWUhwKw") ?: return null
      val raw = constraintsLong(constraints) ?: return null
      val minWidth = constraintsValue("getMinWidth-impl", raw) ?: return null
      val minHeight = constraintsValue("getMinHeight-impl", raw) ?: return null
      val maxWidth = constraintsValue("getMaxWidth-impl", raw)
      val maxHeight = constraintsValue("getMaxHeight-impl", raw)
      val infinity = constraintsInfinity()
      return LayoutInspectorConstraints(
        minWidth = minWidth,
        maxWidth = maxWidth?.takeIf { it != infinity },
        minHeight = minHeight,
        maxHeight = maxHeight?.takeIf { it != infinity },
      )
    }

    fun zIndex(node: Any): Float? {
      val delegate = call(node, "getLayoutDelegate\$ui_release") ?: return null
      val measure = call(delegate, "getMeasurePassDelegate\$ui_release") ?: return null
      return call(measure, "getZIndex\$ui_release") as? Float
    }

    fun measurePolicyName(node: Any): String? =
      call(node, "getMeasurePolicy")?.javaClass?.name?.substringAfterLast('.')?.substringBefore('$')

    private fun constraintsLong(value: Any): Long? =
      when (value) {
        is Long -> value
        else -> call(value, "unbox-impl") as? Long
      }

    private fun constraintsValue(name: String, raw: Long): Int? =
      runCatching {
          Class.forName("androidx.compose.ui.unit.Constraints")
            .getMethod(name, java.lang.Long.TYPE)
            .invoke(null, raw) as Int
        }
        .getOrNull()

    private fun constraintsInfinity(): Int =
      Class.forName("androidx.compose.ui.unit.Constraints").getField("Infinity").getInt(null)

    private fun call(receiver: Any, name: String): Any? =
      runCatching {
          val method = receiver.javaClass.findZeroArgMethod(name) ?: return null
          method.isAccessible = true
          method.invoke(receiver)
        }
        .getOrNull()

    private fun Class<*>.findZeroArgMethod(name: String): Method? {
      var current: Class<*>? = this
      while (current != null) {
        current.declaredMethods
          .firstOrNull { it.name == name && it.parameterCount == 0 }
          ?.let {
            return it
          }
        current = current.superclass
      }
      return methods.firstOrNull { it.name == name && it.parameterCount == 0 }
    }
  }
}

/**
 * Registry for `compose/semantics`. Path-transport by default; the inline-fallback read and
 * missing-file → NotAvailable plumbing come from [FileBackedDataProductRegistry].
 */
class ComposeSemanticsDataProductRegistry(private val rootDir: File) :
  FileBackedDataProductRegistry(
    capabilities =
      listOf(
        DataProductCapability(
          kind = ComposeSemanticsDataProducer.KIND,
          schemaVersion = ComposeSemanticsDataProducer.SCHEMA_VERSION,
          transport = DataProductTransport.PATH,
          attachable = true,
          fetchable = true,
          requiresRerender = false,
          displayName = "Compose semantics",
          facets = listOf(DataProductFacet.STRUCTURED),
          mediaTypes = listOf("application/json"),
          sampling = SamplingPolicy.End,
        )
      )
  ) {
  override fun fileFor(previewId: String, kind: String): File? =
    if (kind == ComposeSemanticsDataProducer.KIND)
      rootDir.resolve(previewId).resolve(ComposeSemanticsDataProducer.FILE)
    else null
}

/**
 * Registry for `layout/inspector`. Path-transport by default with the inline-fallback the base
 * class supplies via `inline=true` upgrade.
 */
class LayoutInspectorDataProductRegistry(private val rootDir: File) :
  FileBackedDataProductRegistry(
    capabilities =
      listOf(
        DataProductCapability(
          kind = LayoutInspectorDataProducer.KIND,
          schemaVersion = LayoutInspectorDataProducer.SCHEMA_VERSION,
          transport = DataProductTransport.PATH,
          attachable = true,
          fetchable = true,
          requiresRerender = false,
        )
      )
  ) {
  override fun fileFor(previewId: String, kind: String): File? =
    if (kind == LayoutInspectorDataProducer.KIND)
      rootDir.resolve(previewId).resolve(LayoutInspectorDataProducer.FILE)
    else null
}
