package ee.schimke.composeai.daemon

import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.CompositionGroup
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.GenericFontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductFacet
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.daemon.protocol.RecordingProbeNode
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsProduct
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorCurvedText
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorProduct
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorVectorGraphic
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorVectorPath
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

/**
 * The family name of a downloadable Google-Fonts face (e.g. `"Orbitron"`), or null for any other
 * font. A `GoogleFontImpl` (`androidx.compose.ui.text.googlefonts`, the `Font(GoogleFont(name), …)`
 * result) is an `AndroidFont` that carries its family name in `getName()` but exposes neither the
 * `identity` (a desktop file path) nor the `resId` (an Android resource) that the other `Font`
 * subtypes do — so without this a branded downloadable face labels as null and the
 * `compose/figma-svg` export collapses it to the Roboto default (the `<text>` names Roboto and
 * `?mode=web` `@import`s Roboto instead of the branded family).
 *
 * Everything here is reflective, so this platform-agnostic module needs no compile dep on the
 * google-fonts artifact and the branch stays unit-testable with a stand-in. The name is read by
 * three routes in descending robustness — declared field, getter, `toString()` — because gating on
 * `getMethods()` alone proved too fragile: across a whole meshcore `:app` render, 846 text nodes
 * captured only generic families and never a single `FontListFontFamily` name, while the font
 * recorder (which reads declared fields) resolved the same faces to `Orbitron` in the same render.
 */
internal fun googleFontFamilyName(font: Any): String? {
  if (!looksLikeGoogleFont(font)) return null
  // The declared field first, deliberately: it is the only route that survives a `getMethods()`
  // that can't resolve every signature on the class. `FontResolverRecorder` reads fields for the
  // same reason and recovers the name from the very renders where this function used to return
  // null — `fonts-used.json` says `Font(GoogleFont("Orbitron", …))` for a preview whose
  // `compose-figma.svg` said Roboto.
  declaredString(font, "name")?.let {
    return it
  }
  reflectedString(font, "getName")?.let {
    return it
  }
  // Last resort: every Compose `Font` names its family in `toString()`, which needs no member
  // lookup at all.
  return googleFontNameFromIdentity(runCatching { font.toString() }.getOrDefault(""))
}

/**
 * Whether [font] is a downloadable Google-Fonts face.
 *
 * Three independent signals, because no single one holds everywhere: the class name, the
 * `toFontRequest()` shape unique to `GoogleFontImpl` (the original check — kept, but no longer the
 * sole gate, since enumerating methods resolves every signature's types and can fail on a classpath
 * where `androidx.core.provider.FontRequest` is absent), and the `GoogleFont("…")` marker in
 * `toString()`. Any one is enough; failing all three means this isn't a downloadable face.
 */
private fun looksLikeGoogleFont(font: Any): Boolean {
  // The exact class, not a substring: a nested/anonymous class merely *enclosed* by something
  // Google-Font-ish carries that name too, and would be mislabelled.
  if (font.javaClass.simpleName == "GoogleFontImpl") return true
  val byToString = runCatching { GOOGLE_FONT_IDENTITY.containsMatchIn(font.toString()) }
  if (byToString.getOrDefault(false)) return true
  return runCatching {
      font.javaClass.methods.any { it.name == "toFontRequest" && it.parameterCount == 0 }
    }
    .getOrDefault(false)
}

/** A non-blank `String` from [font]'s declared field [name], or null. */
private fun declaredString(font: Any, name: String): String? =
  runCatching {
      font.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(font) as? String
    }
    .getOrNull()
    ?.takeIf { it.isNotBlank() }

/** A non-blank `String` from [font]'s zero-arg method [name], or null. */
private fun reflectedString(font: Any, name: String): String? =
  runCatching {
      font.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }?.invoke(font)
        as? String
    }
    .getOrNull()
    ?.takeIf { it.isNotBlank() }

private val GOOGLE_FONT_IDENTITY = Regex("""GoogleFont\("([^"]+)"""")

/**
 * The GoogleFont display name embedded in a desktop face's `identity` — a vendored downloadable
 * face is built as `Font(identity = "Font(GoogleFont(\"Orbitron\", …), …)", data = …)`, so on the
 * desktop (Skiko) render `getIdentity()` returns that whole string. Regex the display name back out
 * so the `compose/figma-svg` `<text>` names `Orbitron` (and `?mode=web` `@import`s it) rather than
 * the raw `Font(GoogleFont("Orbitron", …))` blob — the desktop counterpart to
 * [googleFontFamilyName]'s Android `getName()`. Null for any other identity (a plain file path
 * passes through untouched).
 */
internal fun googleFontNameFromIdentity(identity: String): String? =
  GOOGLE_FONT_IDENTITY.find(identity)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

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
    val layout = cfg.layoutDetails(density)
    return ComposeSemanticsNode(
      nodeId = id.toString(),
      boundsInRoot = boundsInRoot.toWireBounds(),
      label = cfg.label(),
      text = cfg.renderedText(),
      layoutText = layout?.text,
      typography = layout?.typography(),
      textColor = layout?.textColor(),
      textOverflow = layout?.textOverflow(),
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
   * resolved container colour (`Modifier.background`, which `Surface`/`Card` apply), the outline
   * colour (`Modifier.border`), the corner radius / shape of its `background` / `clip` / `border`
   * shape, the `Arrangement` gap of its measure policy, and its `Modifier.padding`. Returns null
   * when the node declares none of them — the common case for pure layout / text nodes.
   *
   * The actual modifier → token resolution lives in [ModifierTokenResolver] so it is computed in
   * one place shared with `layout/inspector` (issue #1903) rather than duplicated per product; this
   * just gathers the per-node inputs (modifier chain, measure policy, measured size, density).
   */
  private fun SemanticsNode.resolvedTokens(density: Float): ComposeSemanticsTokens? {
    val modifiers =
      try {
        layoutInfo.getModifierInfo()
      } catch (_: Throwable) {
        return null
      }
    val measurePolicy =
      runCatching { layoutInfo.javaClass.getMethod("getMeasurePolicy").invoke(layoutInfo) }
        .getOrNull()
    return ModifierTokenResolver.resolve(
      modifierInfo = modifiers,
      measurePolicy = measurePolicy,
      sizeWidthPx = size.width,
      sizeHeightPx = size.height,
      density = density,
    )
  }

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

  private fun SemanticsConfiguration.layoutDetails(density: Float): LayoutTextDetails? {
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
    // Typography is read per *drawn range*, not just the paragraph style: an `AnnotatedString`
    // carries per-range overrides in `spanStyles`, so — like the colour extraction below — reason
    // over every effective face the node draws (see `effectiveSpanStyles`).
    // `distinct().singleOrNull`
    // keeps the unset/`null` value of a range that doesn't specify the property: a node collapses
    // to
    // a field only when *every* drawn range agrees on one concrete value, so a mix of an unstyled
    // (inherited-default) run and a styled span omits the field rather than reporting the span's
    // value as if the node were uniform.
    val spans = results.flatMap { it.effectiveSpanStyles() }
    val uniformFontFamily =
      spans
        .map { fontFamilyLabel(it.fontFamily, it.fontWeight, it.fontStyle) }
        .distinct()
        .singleOrNull()
    // A node with explicit face overrides (for example Jetchat's Karla paragraph plus a monospace
    // code span) is not uniformly one family, but the SVG still needs its paragraph/base face.
    // Keep that base here and carry the effective overrides in `spans` below; returning null made
    // the whole annotated node look unnamed and triggered the global tofu fallback.
    val baseFontFamily =
      results
        .map {
          fontFamilyLabel(
            it.layoutInput.style.fontFamily,
            it.layoutInput.style.fontWeight,
            it.layoutInput.style.fontStyle,
          )
        }
        .distinct()
        .singleOrNull()
    val fontFamily = uniformFontFamily ?: baseFontFamily
    val fontWeight = spans.map { it.fontWeight?.weight }.distinct().singleOrNull()
    val fontStyle = spans.map { fontStyleName(it.fontStyle) }.distinct().singleOrNull()
    val fontVariationSettings =
      spans
        .map { fontVariationLabel(it.fontFamily, it.fontWeight, it.fontStyle, density) }
        .distinct()
        .singleOrNull()
    val fontFeatureSettings =
      spans.map { it.fontFeatureSettings?.takeIf(String::isNotBlank) }.distinct().singleOrNull()
    val letterSpacing = spans.map { it.letterSpacing.toWireTextUnit() }.distinct().singleOrNull()
    // Line height is a paragraph-level property (not carried on `SpanStyle`), so read it per
    // result.
    val lineHeight =
      results
        .mapNotNull { it.layoutInput.style.lineHeight.toWireTextUnit() }
        .distinct()
        .singleOrNull()
    // Paragraph alignment, also paragraph-level (not on `SpanStyle`). The figma-svg export anchors
    // a single-line `<text>` off this (issue #2885); without it `TextAlign.Center` exported
    // left-anchored at the start of the layout bounds, a visible drift on any `fillMaxWidth()`
    // heading.
    val textAlign =
      results.mapNotNull { textAlignName(it.layoutInput.style.textAlign) }.distinct().singleOrNull()
    // Carried alongside it so the export can resolve the *logical* alignments (`start`/`end`),
    // which Compose mirrors under RTL. Read off the layout input rather than inferred from the
    // locale: a composable can flip direction locally via `LocalLayoutDirection`.
    val layoutDirection =
      results
        .mapNotNull { layoutDirectionName(it.layoutInput.layoutDirection) }
        .distinct()
        .singleOrNull()
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
    // Per-line geometry for wrapped text — only for a single [TextLayoutResult] (so line offsets
    // share one origin, the node's top-left) that actually wrapped (2+ lines). Each line's visible
    // substring + left edge + baseline, in px relative to the layout origin, lets the export place
    // one run per line instead of collapsing the string onto a single baseline. An ellipsised line
    // gets the "…" the render draws re-appended (the visible end excludes it).
    val lines =
      results
        .singleOrNull()
        ?.takeIf { it.lineCount >= 2 }
        ?.let { r ->
          val str = r.layoutInput.text.text
          (0 until r.lineCount).map { i ->
            val start = r.getLineStart(i).coerceIn(0, str.length)
            val end = r.getLineEnd(i, true).coerceIn(start, str.length)
            val ellipsis = if (r.isLineEllipsized(i)) "…" else ""
            ComposeSemanticsTextLine(
              text = str.substring(start, end) + ellipsis,
              left = r.getLineLeft(i).roundToInt(),
              baseline = r.getLineBaseline(i).roundToInt(),
              start = start,
              end = end,
            )
          }
        }
    val styledSpans =
      results
        .singleOrNull()
        ?.takeIf { it.layoutInput.text.spanStyles.isNotEmpty() }
        ?.effectiveStyleRanges()
        ?.map { range ->
          ComposeSemanticsTextSpan(
            start = range.start,
            end = range.end,
            fontSize = range.style.fontSize.toWireTextUnit(),
            fontFamily =
              fontFamilyLabel(
                range.style.fontFamily,
                range.style.fontWeight,
                range.style.fontStyle,
              ),
            fontWeight = range.style.fontWeight?.weight,
            fontStyle = fontStyleName(range.style.fontStyle),
            foregroundColor =
              range.style.color.takeIf { it != Color.Unspecified }?.let(::colorToWireString),
          )
        }
    return LayoutTextDetails(
      text = text,
      fontSize = fontSize,
      fontFamily = fontFamily,
      fontWeight = fontWeight,
      fontStyle = fontStyle,
      fontVariationSettings = fontVariationSettings,
      fontFeatureSettings = fontFeatureSettings,
      letterSpacing = letterSpacing,
      lineHeight = lineHeight,
      textAlign = textAlign,
      layoutDirection = layoutDirection,
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
      lines = lines,
      spans = styledSpans,
    )
  }

  /**
   * The effective [SpanStyle]s drawn by this result: each `spanStyle` merged over the paragraph (so
   * a span that sets only, say, `fontWeight` still inherits the paragraph family), plus the bare
   * paragraph style **iff** some of the text is not covered by a span. An uncovered run draws at
   * the (possibly unspecified) paragraph style, which is a distinct face from any span override and
   * must register as ambiguity (issue #1934); but when spans tile the whole string the paragraph
   * base draws nothing, so including it would invent a phantom face and wrongly flag a uniform node
   * as mixed. Lets the typography extraction reason about every face actually drawn.
   */
  private fun TextLayoutResult.effectiveSpanStyles(): List<SpanStyle> {
    return effectiveStyleRanges().map { it.style }
  }

  private data class EffectiveStyleRange(val start: Int, val end: Int, val style: SpanStyle)

  /**
   * Effective style for every drawn UTF-16 interval. Compose spans may overlap: each active style
   * is merged over the paragraph in declaration order, so a colour-only mention keeps the Karla
   * base while a nested code range can explicitly replace it with monospace.
   */
  private fun TextLayoutResult.effectiveStyleRanges(): List<EffectiveStyleRange> {
    val paragraph = layoutInput.style.toSpanStyle()
    val annotated = layoutInput.text
    val length = annotated.text.length
    val spans = annotated.spanStyles
    if (spans.isEmpty() || length <= 0) {
      return listOf(EffectiveStyleRange(0, length, paragraph))
    }
    val boundaries =
      buildSet {
          add(0)
          add(length)
          spans.forEach {
            add(it.start.coerceIn(0, length))
            add(it.end.coerceIn(0, length))
          }
        }
        .sorted()
    val ranges =
      boundaries.zipWithNext().mapNotNull { (start, end) ->
        if (start >= end) return@mapNotNull null
        val effective =
          spans
            .filter { it.start < end && it.end > start }
            .fold(paragraph) { style, span -> style.merge(span.item) }
        EffectiveStyleRange(start, end, effective)
      }
    // Adjacent source ranges with the same effective style need no separate SVG tspan.
    return ranges.fold(mutableListOf()) { merged, range ->
      val previous = merged.lastOrNull()
      if (previous != null && previous.end == range.start && previous.style == range.style) {
        merged[merged.lastIndex] = previous.copy(end = range.end)
      } else {
        merged += range
      }
      merged
    }
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

  /**
   * Resolved typeface identity for the [family] drawn at [weight]/[style] (issue #1934). A
   * [GenericFontFamily] reports its declared name (`"sans-serif"`, `"monospace"`); a
   * [FontListFontFamily] — which carries no family display name — reports the resolved face's
   * stable [identity][fontIdentity] (the matched [Font]'s `identity` / `res/font/<id>`), the only
   * stable per-face handle Compose exposes. Null when the range inherits its family (no explicit
   * `fontFamily`).
   */
  private fun fontFamilyLabel(
    family: FontFamily?,
    weight: FontWeight?,
    style: FontStyle?,
  ): String? =
    when (family) {
      null -> null
      is GenericFontFamily -> family.name.takeIf { it.isNotBlank() }
      is FontListFontFamily -> matchingFont(family, weight, style)?.let(::fontIdentity)
      else -> family.toString().takeIf { it.isNotBlank() }
    }

  private fun fontStyleName(style: FontStyle?): String? =
    when (style) {
      null -> null
      FontStyle.Italic -> "italic"
      else -> "normal"
    }

  /**
   * The variable-font axes actually applied to the face [family] resolves to at [weight]/[style]
   * (issue #1934), formatted as `"<axis> <value>"` pairs sorted by axis tag, e.g. `"opsz 18.0, wght
   * 700.0"`. Only a [FontListFontFamily] carries per-[Font] variation settings; the matched font's
   * `getVariationSettings()` is read reflectively (it lives on the platform `Font` subtypes, not
   * the `Font` interface) so this module stays platform-agnostic. [density] resolves the few axes
   * whose value is density-dependent. Null when the face declares no axes (the common non-variable
   * case).
   */
  private fun fontVariationLabel(
    family: FontFamily?,
    weight: FontWeight?,
    style: FontStyle?,
    density: Float,
  ): String? {
    val fontList = family as? FontListFontFamily ?: return null
    val font = matchingFont(fontList, weight, style) ?: return null
    val settings =
      runCatching {
          font.javaClass.methods
            .firstOrNull { it.name == "getVariationSettings" && it.parameterCount == 0 }
            ?.invoke(font) as? FontVariation.Settings
        }
        .getOrNull() ?: return null
    val resolveDensity = Density(density)
    return settings.settings
      .mapNotNull { setting ->
        runCatching { "${setting.axisName} ${roundAxis(setting.toVariationValue(resolveDensity))}" }
          .getOrNull()
      }
      .distinct()
      .sorted()
      .joinToString(", ")
      .takeIf { it.isNotBlank() }
  }

  /** Round a variable-font axis value to 2 decimals so it reads cleanly (`700.0`, not `699.99`). */
  private fun roundAxis(value: Float): Float = (value * 100f).roundToInt() / 100f

  /**
   * The [Font] in [family] that matches the requested [weight]/[style], so the family label and
   * variation axes describe the face Compose actually resolves — not an arbitrary entry. Mirrors
   * the font resolver: prefer faces whose style (italic/normal) matches, then pick the nearest
   * available weight by the CSS font-matching rule ([chooseWeight]) rather than declaration order,
   * so a family shipping e.g. weights 300/700 resolves a requested 600 to 700 (as Compose does)
   * instead of the first face. `Font.style` is a name-mangled value-class getter, so it is read
   * reflectively.
   */
  private fun matchingFont(
    family: FontListFontFamily,
    weight: FontWeight?,
    style: FontStyle?,
  ): Font? {
    val fonts = family.fonts
    if (fonts.isEmpty()) return null
    val targetWeight = weight?.weight ?: FontWeight.Normal.weight
    val italic = style == FontStyle.Italic
    val candidates = fonts.filter { it.isItalic() == italic }.ifEmpty { fonts }
    val chosenWeight = chooseWeight(candidates.map { it.weight.weight }, targetWeight)
    return candidates.firstOrNull { it.weight.weight == chosenWeight } ?: candidates.firstOrNull()
  }

  private fun Font.isItalic(): Boolean =
    runCatching {
        javaClass.methods
          .firstOrNull { it.name.startsWith("getStyle") && it.parameterCount == 0 }
          ?.invoke(this) as? Int
      }
      .getOrNull() == 1

  /**
   * Stable identity for a resolved [Font]: the platform font's `identity` (a file path / declared
   * name on desktop), falling back to `res/font/<id>` for an Android `ResourceFont`. Both getters
   * live on platform-specific subtypes, so they are read reflectively. Null when neither resolves.
   */
  private fun fontIdentity(font: Font): String? =
    googleFontFamilyName(font)
      ?: runCatching {
          font.javaClass.methods
            .firstOrNull { it.name == "getIdentity" && it.parameterCount == 0 }
            ?.invoke(font) as? String
        }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        // A vendored desktop GoogleFont face carries the `Font(GoogleFont("X", …))` label as its
        // identity; surface the clean display name so the figma-svg names the family, not the blob.
        ?.let { googleFontNameFromIdentity(it) ?: it }
      ?: runCatching {
          font.javaClass.methods
            .firstOrNull { it.name == "getResId" && it.parameterCount == 0 }
            ?.invoke(font) as? Int
        }
        .getOrNull()
        ?.let { "res/font/$it" }

  /** The alignment names the export knows how to act on; anything else is dropped, not guessed. */
  private val WIRE_TEXT_ALIGNS = setOf("left", "right", "center", "justify", "start", "end")

  /**
   * A resolved paragraph [TextAlign][androidx.compose.ui.text.style.TextAlign] as a lowercase wire
   * name (`"center"`, `"end"`, …), or null when the style leaves it unset. Read through
   * `toString()` rather than by comparing against the `TextAlign` constants: the type is an inline
   * value class whose shape moved between Compose versions (nullable, then non-null with an
   * `Unspecified` sentinel), while its string form (`"Center"`, `"Unspecified"`) held steady across
   * both. An unrecognised value — `Unspecified` today, some future addition tomorrow — is dropped
   * rather than guessed at, so the `<text>` keeps its historical left anchor.
   */
  private fun textAlignName(align: Any?): String? {
    val raw = align?.toString()?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    return raw.takeIf { it in WIRE_TEXT_ALIGNS }
  }

  /**
   * A resolved `LayoutDirection` as `"ltr"` / `"rtl"`, or null for anything unrecognised. Read
   * through `toString()` for the same reason as [textAlignName] — the enum's name (`Ltr`/`Rtl`) is
   * the stable part of its surface.
   */
  private fun layoutDirectionName(direction: Any?): String? =
    when (direction?.toString()?.trim()?.lowercase()) {
      "ltr" -> "ltr"
      "rtl" -> "rtl"
      else -> null
    }

  /** A resolved [TextUnit] as `"<value>sp"` / `"<value>em"`; null for unspecified / other types. */
  private fun TextUnit.toWireTextUnit(): String? =
    when (type) {
      TextUnitType.Sp -> "${value}sp"
      TextUnitType.Em -> "${value}em"
      else -> null
    }

  private data class LayoutTextDetails(
    val text: String?,
    val fontSize: String?,
    val fontFamily: String?,
    val fontWeight: Int?,
    val fontStyle: String?,
    val fontVariationSettings: String?,
    val fontFeatureSettings: String?,
    val letterSpacing: String?,
    val lineHeight: String?,
    val textAlign: String?,
    val layoutDirection: String?,
    val foregroundColor: String?,
    val backgroundColor: String?,
    val lineCount: Int?,
    val maxLines: Int?,
    val overflow: String?,
    val truncated: Boolean?,
    val didOverflowWidth: Boolean?,
    val didOverflowHeight: Boolean?,
    val lines: List<ComposeSemanticsTextLine>?,
    val spans: List<ComposeSemanticsTextSpan>?,
  )

  /**
   * Groups the resolved typographic identity into the wire [ComposeSemanticsTypography] object
   * (issues #1934, #1903), or null when the node declares nothing typographic — so a node omits
   * `typography` entirely rather than carrying an all-null object, mirroring how `tokens` behaves.
   */
  private fun LayoutTextDetails.typography(): ComposeSemanticsTypography? =
    if (
      fontSize == null &&
        fontFamily == null &&
        fontWeight == null &&
        fontStyle == null &&
        fontVariationSettings == null &&
        fontFeatureSettings == null &&
        letterSpacing == null &&
        lineHeight == null &&
        textAlign == null
    )
      null
    else
      ComposeSemanticsTypography(
        fontSize = fontSize,
        fontFamily = fontFamily,
        fontWeight = fontWeight,
        fontStyle = fontStyle,
        fontVariationSettings = fontVariationSettings,
        fontFeatureSettings = fontFeatureSettings,
        letterSpacing = letterSpacing,
        lineHeight = lineHeight,
        textAlign = textAlign,
        layoutDirection = layoutDirection,
        spans = spans,
      )

  /** Groups the resolved text colours (issue #1903), or null when the node resolves none. */
  private fun LayoutTextDetails.textColor(): ComposeSemanticsTextColor? =
    if (foregroundColor == null && backgroundColor == null) null
    else ComposeSemanticsTextColor(foreground = foregroundColor, background = backgroundColor)

  /** Groups the resolved line/overflow metrics (issue #1903), or null when the node has none. */
  private fun LayoutTextDetails.textOverflow(): ComposeSemanticsTextOverflow? =
    if (
      lineCount == null &&
        maxLines == null &&
        overflow == null &&
        truncated == null &&
        didOverflowWidth == null &&
        didOverflowHeight == null
    )
      null
    else
      ComposeSemanticsTextOverflow(
        lineCount = lineCount,
        maxLines = maxLines,
        overflow = overflow,
        truncated = truncated,
        didOverflowWidth = didOverflowWidth,
        didOverflowHeight = didOverflowHeight,
        lines = lines,
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
    val role = node.role?.takeIf { it.isNotBlank() }
    // Merged text of the descendants (issue #2519): the container's visible text lives on a child
    // in the unmerged tree (`Button { Text("Add") }`), so carry it here — mirroring Compose's
    // merged semantics — so the flat snapshot can resolve `role`+`text` targets and answer
    // `assert.textEquals` without a live tree. Own text wins over this at resolution time.
    val mergedText = node.mergedDescendantText()
    // Keep a node when it has a stable finder (testTag / rendered text / content description) or is
    // a role-bearing container whose merged descendant text makes it a `role`+`text` finder — a
    // bare `Button { Text("Add") }` carries neither testTag nor own text, and dropping it would
    // make a `role`+`text` assertion fail closed on a control that is plainly on screen.
    if (
      testTag != null ||
        text != null ||
        contentDescription != null ||
        (role != null && mergedText != null)
    ) {
      add(
        RecordingProbeNode(
          testTag = testTag,
          text = text,
          contentDescription = contentDescription,
          role = role,
          clickable = node.clickable,
          mergedText = mergedText,
        )
      )
    }
    node.children.forEach(::visit)
  }
  visit(this@toProbeNodes)
}

/**
 * Merged text of this node's **descendants** (issue #2519), depth-first and newline-joined — the
 * same separator Compose's merged semantics (and the desktop `resolvedNodeText`) use. Excludes the
 * node's own [ComposeSemanticsNode.text] so a resolver can prefer own text and fall back to this;
 * null when no descendant draws text.
 */
private fun ComposeSemanticsNode.mergedDescendantText(): String? {
  val parts = mutableListOf<String>()
  fun collect(n: ComposeSemanticsNode) {
    n.text?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
    n.children.forEach(::collect)
  }
  children.forEach(::collect)
  return parts.joinToString("\n").ifEmpty { null }
}

/**
 * The weight from [available] that the CSS / Compose font-matching algorithm resolves a [target]
 * weight to (issue #1934): an exact match if present, otherwise the nearest available weight by the
 * CSS rule — for `[400, 500]` prefer weights in `[target, 500]` ascending, then lighter descending,
 * then heavier ascending; below 400 prefer lighter then heavier; above 500 prefer heavier then
 * lighter. Returns null only for an empty list. Extracted (and `internal`) so the rule can be unit
 * tested without constructing real `Font` instances.
 */
internal fun chooseWeight(available: List<Int>, target: Int): Int? {
  if (available.isEmpty()) return null
  if (target in available) return target
  val lighter = available.filter { it < target }.maxOrNull()
  val heavier = available.filter { it > target }.minOrNull()
  return when {
    target < 400 -> lighter ?: heavier
    target > 500 -> heavier ?: lighter
    else -> available.filter { it in target..500 }.minOrNull() ?: lighter ?: heavier
  }
}

typealias ComposeSemanticsPayload =
  ee.schimke.composeai.data.layoutinspector.ComposeSemanticsPayload

typealias ComposeSemanticsNode = ee.schimke.composeai.data.layoutinspector.ComposeSemanticsNode

typealias ComposeSemanticsTokens = ee.schimke.composeai.data.layoutinspector.ComposeSemanticsTokens

typealias ComposeSemanticsTypography =
  ee.schimke.composeai.data.layoutinspector.ComposeSemanticsTypography

typealias ComposeSemanticsTextColor =
  ee.schimke.composeai.data.layoutinspector.ComposeSemanticsTextColor

typealias ComposeSemanticsTextOverflow =
  ee.schimke.composeai.data.layoutinspector.ComposeSemanticsTextOverflow

typealias ComposeSemanticsTextLine =
  ee.schimke.composeai.data.layoutinspector.ComposeSemanticsTextLine

typealias ComposeSemanticsTextSpan =
  ee.schimke.composeai.data.layoutinspector.ComposeSemanticsTextSpan

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
    density: Float = 1f,
    fileSystem: FileSystem = SystemFileSystem,
  ) {
    val capture = LayoutInspectorCaptureContext.from(previewContext) ?: return
    write(rootDir, previewId, capture, density, fileSystem)
  }

  /**
   * Desktop / CMP-portable overload (issue #1903): build the inspector tree directly from a
   * captured [root] `SemanticsNode` + composition [slotTables] — the inputs the desktop
   * `RenderEngine` holds after `scene.render()`. The Android path resolves these from a
   * `RootForTest`; desktop has no such handle, so this skips it. `ComposeLayoutInspector` then
   * walks the `LayoutNode` reachable from the semantics root by reflection, identically on both
   * backends — which is what lets `layout/inspector` finally ship on desktop instead of serving a
   * never-written file.
   */
  fun writeArtifacts(
    rootDir: File,
    previewId: String,
    root: SemanticsNode,
    slotTables: List<CompositionData> = emptyList(),
    density: Float = 1f,
    fileSystem: FileSystem = SystemFileSystem,
  ) {
    val capture =
      LayoutInspectorCaptureContext(
        rootSemanticsNode = root,
        slotTables = ExtensionSlotTables.of(slotTables),
      )
    write(rootDir, previewId, capture, density, fileSystem)
  }

  /**
   * Builds the [LayoutInspectorPayload] from a captured [root] semantics node + composition
   * [slotTables] without writing it — the in-memory sibling of [writeArtifacts] that lets a
   * downstream producer (the `compose/figma-svg` export) reuse the same walked tree, with its
   * composable [LayoutInspectorNode.component] names and resolved container tokens, that this
   * product serialises to disk. Returns null when the layout tree can't be reached (same guard as
   * [ComposeLayoutInspector.inspect]).
   */
  fun buildPayload(
    root: SemanticsNode,
    slotTables: List<CompositionData> = emptyList(),
    density: Float = 1f,
  ): LayoutInspectorPayload? {
    val capture =
      LayoutInspectorCaptureContext(
        rootSemanticsNode = root,
        slotTables = ExtensionSlotTables.of(slotTables),
      )
    val layoutRoot = ComposeLayoutInspector.inspect(capture, density) ?: return null
    return LayoutInspectorPayload(root = layoutRoot)
  }

  /**
   * Android in-memory sibling: build the layout payload from the engine-assembled [PreviewContext]
   * (the same input [writeArtifacts] uses), so the `compose/figma-svg` extension can reuse the
   * walked tree without re-reading the serialized file. Returns null when the layout tree can't be
   * reached.
   */
  fun buildPayload(previewContext: PreviewContext, density: Float = 1f): LayoutInspectorPayload? {
    val capture = LayoutInspectorCaptureContext.from(previewContext) ?: return null
    val layoutRoot = ComposeLayoutInspector.inspect(capture, density) ?: return null
    return LayoutInspectorPayload(root = layoutRoot)
  }

  private fun write(
    rootDir: File,
    previewId: String,
    capture: LayoutInspectorCaptureContext,
    density: Float,
    fileSystem: FileSystem,
  ) {
    val layoutRoot = ComposeLayoutInspector.inspect(capture, density) ?: return
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
  /**
   * [density] (dp = px / density) is threaded only to resolve percent-based corner radii
   * (`CircleShape`) into dp on the per-node [LayoutInspectorNode.tokens]; the default of `1f`
   * leaves px-equals-dp captures unchanged, matching [ComposeSemanticsDataProducer.buildPayload].
   */
  fun inspect(context: LayoutInspectorCaptureContext, density: Float = 1f): LayoutInspectorNode? {
    val root = LayoutTreeAccess.rootLayoutNode(context.rootSemanticsNode) ?: return null
    val sources = LayoutSourceIndex(context.slotTables)
    return root.toWireNode(rootCoordinates = null, sources = sources, density = density)
  }

  private fun LayoutNodeFacade.toWireNode(
    rootCoordinates: LayoutCoordinates?,
    sources: LayoutSourceIndex,
    density: Float,
  ): LayoutInspectorNode {
    val rootCoords = rootCoordinates ?: coordinates
    val source = sources.sourceFor(raw)
    // The node's own identity (own `C(...)` or measure-policy class) drives raster/curved matching;
    // the friendly, possibly-inherited label rides separately in `displayName`. Keying matching off
    // the own identity keeps an inherited label like `IconButton` (⊃ `Icon`) from wrongly
    // rasterising a non-opaque wrapper's subtree (#2469 follow-up).
    val ownComponent = source?.ownComponent ?: componentFallback
    val displayComponent = source?.component ?: componentFallback
    // A Wear `CurvedLayout`/`TimeText` draws text along an arc via a `CurvedTextChild` that no
    // LayoutNode represents; pull those runs (string + baseline arc + font) so the export can
    // reproduce them as an SVG `<textPath>` instead of dropping the clock.
    val curvedTexts =
      if (ownComponent.contains("Curved")) CurvedTextExtractor.extract(this) else emptyList()
    // An `Icon`/`Image` backed by an `ImageVector` paints through a `VectorPainter`; pull its path
    // tree (Tier 1) so the figma-svg export emits editable `<path>`s instead of a raster crop.
    // Reflective + best-effort: any failure (or a bitmap/gradient/transformed painter) yields null
    // and the node simply rasters as before.
    val modifiers = modifierInfo
    val children = children.map { it.toWireNode(rootCoords, sources, density) }
    // An `Icon`/`Image`'s `ImageVector` (Tier 1). Failing that, a *leaf* node that paints its
    // chrome
    // via an imperative draw modifier (`Slider`/progress/`Checkbox`/`RadioButton` draw into a bare
    // `Spacer`/`Canvas`) — re-invoke its draw lambda against a recording DrawScope and capture the
    // primitives as editable `<path>`s instead of rasterising. Both land on the same
    // `vectorGraphic`
    // and ride the same export path; anything the recorder can't represent yields null and the node
    // rasters as before. Restricted to childless nodes: a captured `vectorGraphic` makes the export
    // return a leaf (dropping children), so capturing a `Box(Modifier.drawBehind{…}){ Text(…) }`
    // container would delete its content — matching the leaf-only rule the raster-crop path uses.
    // Size the draw capture to the node's *placed* bounds, not its measured `size`. The export
    // scales the captured viewport onto the layer's `bounds` box, so the two must match; a node
    // with
    // `Modifier.minimumInteractiveComponentSize()` (every `RadioButton`/`Checkbox`/`IconButton`)
    // measures to the 48dp touch target while it paints at the smaller visual `bounds`, so sizing
    // the capture to `size` would place the drawn chrome in a 48dp viewport that the export then
    // shrinks onto the ~20dp box — the radio ring came out ~0.4× too small.
    val placedBounds = coordinates.boundsIn(rootCoords)
    val boundsW = placedBounds.right - placedBounds.left
    val boundsH = placedBounds.bottom - placedBounds.top
    // A detached / not-yet-placed node reports `(0,0,0,0)` bounds; `FigmaSvgModel.toLayer` recovers
    // those from the measured `size` (and places the vector against the recovered box), so fall
    // back
    // to `size` here when bounds are zero-area — otherwise a zero-sized viewport drops the chrome.
    val captureW = if (boundsW > 0) boundsW else width
    val captureH = if (boundsH > 0) boundsH else height
    val vectorGraphic =
      VectorGraphicExtractor.extract(this)
        ?: if (children.isEmpty())
          DrawCaptureExtractor.extract(modifiers, captureW, captureH, density)
        else null
    return LayoutInspectorNode(
      nodeId = semanticsId?.toString() ?: identityId,
      component = ownComponent,
      displayName = displayComponent.takeIf { it != ownComponent },
      source = source?.source,
      sourceInfo = source?.sourceInfo,
      bounds = placedBounds,
      size = LayoutInspectorSize(width = width, height = height),
      constraints = constraints,
      placed = placed,
      attached = attached,
      zIndex = zIndex,
      modifiers = modifiers.mapNotNull { info -> info.toWireModifier(rootCoords) },
      // Resolved tokens are computed by the shared resolver (issue #1903) from the same modifier
      // chain + measure policy + measured size this node already carries — `layout/inspector` is
      // the
      // canonical home for the modifier-derived token projection.
      tokens =
        ModifierTokenResolver.resolve(
          modifierInfo = modifiers,
          measurePolicy = measurePolicy,
          sizeWidthPx = width,
          sizeHeightPx = height,
          density = density,
        ),
      curvedTexts = curvedTexts,
      vectorGraphic = vectorGraphic,
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
      inspectable
        ?.inspectableElements
        ?.associate { it.name to it.value.wireValue() }
        .orEmpty()
        .toMutableMap()
    // Desktop/release Compose commonly compiles `BackgroundElement` inspector properties out, so
    // a `Modifier.background(Brush…)` otherwise serialises as an empty modifier and the figma-svg
    // model cannot distinguish a real gradient from an unpainted node. Carry only the presence /
    // identity string of the brush; hybrid export uses it as the signal to crop the complete layer
    // from the rendered frame rather than silently dropping the paint.
    if (
      "brush" !in properties &&
        (name == "background" || modifier.javaClass.simpleName == "BackgroundElement")
    ) {
      runCatching {
          modifier.javaClass.getDeclaredField("brush").apply { isAccessible = true }.get(modifier)
        }
        .getOrNull()
        ?.let { properties["brush"] = it.wireValue() }
    }
    if (
      "alpha" !in properties &&
        (name == "graphicsLayer" || modifier.javaClass.simpleName.contains("GraphicsLayer"))
    ) {
      ModifierTokenResolver.graphicsLayerAlpha(this)?.let { properties["alpha"] = it.toString() }
    }
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

  /**
   * Extracts Wear curved text (a `CurvedLayout`/`TimeText` clock) from a layout node. Curved text
   * is drawn by a `CurvedTextChild` living in the `CurvedLayout`'s internal child tree — invisible
   * to a plain `LayoutNode` walk — so this reflects the tree out of the node's measure policy /
   * draw modifiers and reads each text run's string, baseline arc (`CurvedLayoutInfo`) and font, in
   * root-pixel space, so the export can reproduce it as an SVG `<textPath>`. All reflective and
   * best-effort: any failure yields an empty list and the node simply carries no curved text.
   */
  private object CurvedTextExtractor {
    private const val CURVED_CHILD = "androidx.wear.compose.foundation.CurvedChild"

    fun extract(node: LayoutNodeFacade): List<LayoutInspectorCurvedText> {
      val candidates = ArrayList<Any?>()
      candidates.add(node.measurePolicy)
      node.modifierInfo.forEach { candidates.add(it.modifier) }
      val roots = LinkedHashSet<Any>()
      candidates.filterNotNull().forEach { scan(it, 0, roots, HashSet()) }
      val runs = ArrayList<LayoutInspectorCurvedText>()
      roots.forEach { collect(it, runs) }
      return runs
    }

    private fun isCurvedChild(o: Any): Boolean {
      var c: Class<*>? = o.javaClass
      while (c != null) {
        if (c.name == CURVED_CHILD) return true
        c = c.superclass
      }
      return false
    }

    /**
     * Depth-limited field scan for the root `CurvedChild` held by a measure policy / draw lambda.
     */
    private fun scan(o: Any, depth: Int, out: MutableSet<Any>, seen: MutableSet<Any>) {
      if (depth > 4 || o is String || o is Number || o is Boolean || !seen.add(o)) return
      if (isCurvedChild(o)) {
        out.add(o)
        return
      }
      if (!o.javaClass.name.startsWith("androidx")) return
      o.javaClass.declaredFields.forEach { f ->
        runCatching {
            f.isAccessible = true
            f.get(o)
          }
          .getOrNull()
          ?.let { v -> scan(v, depth + 1, out, seen) }
      }
    }

    /**
     * Walk the curved-child tree (container children + single-child wrappers), emitting text runs.
     */
    private fun collect(child: Any, out: MutableList<LayoutInspectorCurvedText>) {
      if (child.javaClass.simpleName == "CurvedTextChild") toRun(child)?.let(out::add)
      (call(child, "getChildrenInLayoutOrder\$compose_foundation") as? List<*>)?.forEach {
        it?.let { c -> collect(c, out) }
      }
      call(child, "getWrapped")?.let { collect(it, out) }
    }

    private fun toRun(child: Any): LayoutInspectorCurvedText? {
      val text = call(child, "getText") as? String ?: return null
      val info = call(child, "getLayoutInfo\$compose_foundation") ?: return null
      val start = floatCall(info, "getStartAngleRadians") ?: return null
      val sweep = floatCall(info, "getSweepRadians") ?: return null
      val radius = floatCall(info, "getMeasureRadius") ?: return null
      val center = call(info, "getCenterOffset-F1C5BW0") as? Long ?: return null
      val cx = Float.fromBits((center shr 32).toInt()).toDouble()
      val cy = Float.fromBits((center and 0xFFFFFFFFL).toInt()).toDouble()
      val delegate =
        runCatching {
            child.javaClass.getDeclaredField("delegate").apply { isAccessible = true }.get(child)
          }
          .getOrNull()
      val fontSize = delegate?.let { floatField(it, "fontSizePx") } ?: 0.0
      val clockwise = (call(child, "getClockwise") as? Boolean) ?: true
      val paint = delegate?.let { runCatching { field(it, "paint") }.getOrNull() }
      val color = paint?.let { (call(it, "getColor") as? Int)?.let { c -> "#%08X".format(c) } }
      // The resolved weight rides on the paint's Typeface (Android API 28+); TimeText's clock is a
      // medium weight, so without it the `<text>` renders too thin against the render.
      val weight =
        paint
          ?.let { runCatching { call(it, "getTypeface") }.getOrNull() }
          ?.let { tf -> call(tf, "getWeight") as? Int }
          ?.takeIf { it in 1..1000 }
      return LayoutInspectorCurvedText(
        text = text,
        centerXPx = cx,
        centerYPx = cy,
        radiusPx = radius.toDouble(),
        startAngleRadians = start.toDouble(),
        sweepRadians = sweep.toDouble(),
        clockwise = clockwise,
        fontSizePx = fontSize,
        fontWeight = weight,
        colorArgb = color,
      )
    }

    private fun call(o: Any, method: String): Any? =
      runCatching { o.javaClass.getMethod(method).invoke(o) }.getOrNull()

    private fun floatCall(o: Any, method: String): Float? = call(o, method) as? Float

    private fun field(o: Any, name: String): Any? =
      o.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(o)

    private fun floatField(o: Any, name: String): Double? =
      runCatching { (field(o, name) as? Float)?.toDouble() }.getOrNull()
  }

  /**
   * Extracts an editable [LayoutInspectorVectorGraphic] from a node whose `Icon`/`Image` paints an
   * `ImageVector` through a `Modifier.paint(VectorPainter)`. Reflects the painter's live vector
   * tree (`VectorComponent` → `GroupComponent`/`PathComponent`) into SVG path data + solid paints,
   * in the vector's own viewport coordinates. All reflective and best-effort — any failure yields
   * null and the node keeps its raster fallback:
   * - a `BitmapPainter`/`ColorPainter`/other painter (not a `VectorPainter`) → null,
   * - a path with a gradient/brush paint (no resolvable solid colour) is dropped, and a graphic
   *   left with no paintable path → null (matching the vector-vs-raster rule the export already
   *   follows),
   * - a group carrying a non-identity transform (translate/scale/rotate/clip) → null, so a
   *   transformed icon rasters rather than emitting misplaced geometry (kept minimal on purpose).
   *
   * Why reflection here rather than the [DrawCaptureExtractor] recorder that vectorises imperative
   * chrome (`Slider`/`Checkbox`/progress): those controls issue `drawPath`/`drawCircle`/… straight
   * to the `DrawScope`, so re-invoking their draw lambda against a recording scope captures the
   * primitives directly. A `VectorPainter` does NOT — its `onDraw` records the vector into a cached
   * `GraphicsLayer` and then `drawLayer`s it, so a recording scope would only see the opaque layer
   * blit (which the recorder rejects) and never the underlying paths, aborting every icon to
   * raster. Reflecting the live `VectorComponent` tree is the mechanism that actually reaches an
   * `ImageVector`'s geometry; the two extractors are deliberately separate, not a duplication to
   * fold together.
   */
  private object VectorGraphicExtractor {
    fun extract(node: LayoutNodeFacade): LayoutInspectorVectorGraphic? =
      runCatching { extractOrNull(node) }.getOrNull()

    private fun extractOrNull(node: LayoutNodeFacade): LayoutInspectorVectorGraphic? {
      // The `VectorPainter` an `Icon`/`Image` paints with rides in the node's draw modifier — as a
      // `Modifier.paint(painter)` `PainterElement` field, or (depending on the Compose version /
      // wrapping) nested a level inside it. Scan each modifier element's fields shallowly for the
      // painter rather than assume a single exact field name, so the capture survives those shapes.
      val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
      val painter =
        (node.modifierInfo.asSequence().map { it.modifier } + sequenceOf(node.measurePolicy))
          .mapNotNull { candidate -> findVectorPainter(candidate, 0, seen) }
          .firstOrNull()
      if (painter == null) return null
      // An `Icon` recolours its vector with a tint `colorFilter` (Material's `LocalContentColor`
      // default) applied at draw time — the painter tree still holds the *source* path colours.
      // Read
      // that tint so we recolour the paths to what actually renders; a colour filter we can't
      // represent as a flat SrcIn tint (a colour-matrix filter, an unusual blend mode) declines
      // vectorisation so the node rasters at full fidelity rather than emitting the wrong colour.
      val tint =
        when (val t = resolveTint(painter)) {
          UnsupportedTint -> return null
          NoTint -> null
          is SolidTint -> t.argb
        }
      val vector = field(painter, "vector") ?: return null // VectorComponent
      val (vw, vh) = viewport(vector) ?: return null
      if (vw <= 0f || vh <= 0f) return null
      val root = field(vector, "root") ?: return null // GroupComponent
      val paths = ArrayList<LayoutInspectorVectorPath>()
      if (!collect(root, paths) || paths.isEmpty()) return null
      val painted = if (tint != null) paths.map { it.recoloured(tint) } else paths
      return LayoutInspectorVectorGraphic(vw, vh, painted)
    }

    private sealed interface TintResult

    private object NoTint : TintResult

    private object UnsupportedTint : TintResult

    private data class SolidTint(val argb: String) : TintResult

    /** `BlendMode.SrcIn` as it reads back off a `BlendModeColorFilter` (the mode `tint()` uses). */
    private const val BLEND_SRC_IN = 5

    /**
     * The tint an `Icon`/`Image` recolours its vector with. `null` filter ⇒ [NoTint] (keep source
     * colours). A `BlendModeColorFilter` with a `SrcIn` tint ⇒ [SolidTint] (recolour every painted
     * path). Anything else — a different blend mode, a colour-matrix filter, an unreadable colour ⇒
     * [UnsupportedTint], so the caller declines vectorisation and the node rasters instead.
     *
     * The filter can arrive several ways: the external filter the `Icon`/`Image` passed
     * (`colorFilter`), the resolved filter used at the last draw (`currentColorFilter`), or — when
     * a vector carries its own `tintColor` and no external filter is passed — the intrinsic filter.
     * `VectorPainter.intrinsicColorFilter` is a forwarding property backed on the
     * `VectorComponent`, so its `intrinsicColorFilter$delegate` state lives on the vector (older
     * layouts kept it on the painter); probe both so an intrinsically-tinted vector isn't
     * vectorised in its source colours.
     */
    private fun resolveTint(painter: Any): TintResult {
      val vector = runCatching { field(painter, "vector") }.getOrNull()
      val filter =
        runCatching { field(painter, "colorFilter") }.getOrNull()
          ?: runCatching { field(painter, "currentColorFilter") }.getOrNull()
          ?: currentStateValue(
            runCatching { field(painter, "intrinsicColorFilter\$delegate") }.getOrNull()
          )
          ?: currentStateValue(
            runCatching { vector?.let { field(it, "intrinsicColorFilter\$delegate") } }.getOrNull()
          )
          ?: return NoTint
      if (filter.javaClass.simpleName != "BlendModeColorFilter") return UnsupportedTint
      val blend =
        runCatching { field(filter, "blendMode") }.getOrNull() as? Int ?: return UnsupportedTint
      if (blend != BLEND_SRC_IN) return UnsupportedTint
      val argb =
        colorArgb(runCatching { field(filter, "color") }.getOrNull()) ?: return UnsupportedTint
      return SolidTint(argb)
    }

    /**
     * A `Color` value (a packed sRGB Long, or a boxed `Color`) as `#AARRGGBB`; null if unreadable.
     */
    private fun colorArgb(value: Any?): String? {
      val packed =
        (value as? Long ?: longField(value ?: return null, "value") ?: return null).toULong()
      if (packed and 0xFFFFFFFFuL != 0uL) return null // non-sRGB packing we can't read as flat ARGB
      val argb = (packed shr 32).toInt()
      return if (argb == 0) null else "#%08X".format(argb)
    }

    /**
     * Recolours a path's solid fill/stroke to [argb] (the icon tint), leaving unpainted sides bare.
     */
    private fun LayoutInspectorVectorPath.recoloured(argb: String) =
      copy(
        fillArgb = if (fillArgb != null) argb else null,
        strokeArgb = if (strokeArgb != null) argb else null,
      )

    /**
     * Depth-limited search of a modifier element for the `VectorPainter` it draws with. The painter
     * is usually a direct `painter` field on a `Modifier.paint(...)` `PainterElement`, but a
     * version bump or a wrapping node can bury it one level down, so scan declared fields (across
     * the class hierarchy) up to a shallow depth rather than hard-code the path. Identity-tracked
     * to avoid cycles; confined to `androidx` objects so it never wanders into unrelated graphs.
     */
    private fun findVectorPainter(o: Any?, depth: Int, seen: MutableSet<Any>): Any? {
      if (o == null || depth > 2 || o is String || o is Number || o is Boolean) return null
      if (!seen.add(o)) return null
      if (o.javaClass.simpleName == "VectorPainter") return o
      if (!o.javaClass.name.startsWith("androidx")) return null
      var c: Class<*>? = o.javaClass
      while (c != null) {
        for (f in c.declaredFields) {
          val v =
            runCatching {
                f.isAccessible = true
                f.get(o)
              }
              .getOrNull()
          findVectorPainter(v, depth + 1, seen)?.let {
            return it
          }
        }
        c = c.superclass
      }
      return null
    }

    /**
     * The vector's viewport in its own units. Older Compose exposed plain `viewportWidth` /
     * `viewportHeight` floats (or a raw `viewportSize` Long); current Compose keeps it as a
     * `MutableState<Size>` behind `viewportSize$delegate`, so read the delegate's current value and
     * unpack the `Size`. A `Size` value class packs two floats into a Long (width high, height
     * low).
     */
    private fun viewport(vector: Any): Pair<Float, Float>? {
      floatField(vector, "viewportWidth")?.let { w ->
        floatField(vector, "viewportHeight")?.let { h ->
          return w.toFloat() to h.toFloat()
        }
      }
      val packed =
        sizePackedValue(runCatching { field(vector, "viewportSize") }.getOrNull())
          ?: sizePackedValue(
            currentStateValue(runCatching { field(vector, "viewportSize\$delegate") }.getOrNull())
          )
          ?: return null
      val w = Float.fromBits((packed shr 32).toInt())
      val h = Float.fromBits((packed and 0xFFFFFFFFL).toInt())
      return if (w > 0f && h > 0f) w to h else null
    }

    /**
     * The current value held by a Compose `MutableState`, via its value getter or newest record.
     */
    private fun currentStateValue(state: Any?): Any? {
      if (state == null) return null
      runCatching { state.javaClass.getMethod("getValue").invoke(state) }
        .getOrNull()
        ?.let {
          return it
        }
      // Fall back to the newest state record's `value` when reading outside a live snapshot.
      val record = runCatching { field(state, "next") }.getOrNull() ?: return null
      return runCatching { field(record, "value") }.getOrNull()
    }

    /** A packed `Size` Long from a boxed `Size` value class (its `packedValue`) or a raw Long. */
    private fun sizePackedValue(v: Any?): Long? =
      when (v) {
        null -> null
        is Long -> v
        else -> longField(v, "packedValue")
      }

    /**
     * Walks a `VNode` tree collecting `PathComponent`s. Returns false when a group carries a
     * non-identity transform — the caller then drops to raster rather than emit misplaced paths.
     */
    private fun collect(vnode: Any, out: MutableList<LayoutInspectorVectorPath>): Boolean {
      when (vnode.javaClass.simpleName) {
        "GroupComponent" -> {
          if (!isIdentityGroup(vnode)) return false
          val children = field(vnode, "children") as? List<*> ?: return true
          for (child in children) if (child != null && !collect(child, out)) return false
          return true
        }
        "PathComponent" -> {
          // A path with geometry filled or stroked by a brush we can't represent (a
          // gradient/shader,
          // on the fill OR the stroke) fails the whole icon to raster. Gating before `pathOf` also
          // covers a *mixed*-paint path (e.g. a solid fill with a gradient stroke): `pathOf` would
          // otherwise return a partial vector for the solid side and silently drop the gradient
          // one.
          // A path that draws nothing (blank geometry / no paint) is skipped. (#2504 / #2505
          // review.)
          if (hasUnrepresentablePaint(vnode)) return false
          pathOf(vnode)?.let(out::add)
          return true
        }
        else -> return true
      }
    }

    /**
     * True when a path has real geometry painted by a brush we can't lower to a flat colour — a
     * gradient/shader `Brush` (anything that isn't a `SolidColor`). A `SolidColor` we merely can't
     * read (transparent, non-sRGB) is treated as invisible, not unrepresentable.
     */
    private fun hasUnrepresentablePaint(p: Any): Boolean {
      val nodes = runCatching { field(p, "pathData") as? List<*> }.getOrNull()
      if (nodes.isNullOrEmpty()) return false
      fun unrepresentable(name: String): Boolean {
        val brush = runCatching { field(p, name) }.getOrNull() ?: return false
        return brush.javaClass.simpleName != "SolidColor"
      }
      return unrepresentable("fill") || unrepresentable("stroke")
    }

    private fun isIdentityGroup(g: Any): Boolean {
      fun v(name: String, id: Float) = (floatField(g, name)?.toFloat() ?: id) == id
      val clip = runCatching { field(g, "clipPathData") as? List<*> }.getOrNull()
      return v("translationX", 0f) &&
        v("translationY", 0f) &&
        v("scaleX", 1f) &&
        v("scaleY", 1f) &&
        v("rotation", 0f) &&
        v("pivotX", 0f) &&
        v("pivotY", 0f) &&
        (clip == null || clip.isEmpty())
    }

    private fun pathOf(p: Any): LayoutInspectorVectorPath? {
      val nodes = field(p, "pathData") as? List<*> ?: return null
      val d = pathData(nodes)
      if (d.isBlank()) return null
      val fill = brushArgb(runCatching { field(p, "fill") }.getOrNull())
      val stroke = brushArgb(runCatching { field(p, "stroke") }.getOrNull())
      if (fill == null && stroke == null) return null
      val strokeWidth =
        if (stroke != null) (floatField(p, "strokeLineWidth")?.toFloat() ?: 0f) else 0f
      // `PathFillType` is a value class over Int (NonZero = 0, EvenOdd = 1), so the backing field
      // reads back as an Int; fall back to a name match for any boxed representation.
      val fillType = runCatching { field(p, "pathFillType") }.getOrNull()
      val evenOdd =
        when (fillType) {
          is Int -> fillType == 1
          else -> fillType?.toString()?.contains("EvenOdd", ignoreCase = true) == true
        }
      return LayoutInspectorVectorPath(
        pathData = d,
        fillArgb = fill,
        fillAlpha = floatField(p, "fillAlpha")?.toFloat() ?: 1f,
        strokeArgb = stroke,
        strokeWidth = strokeWidth,
        strokeAlpha = floatField(p, "strokeAlpha")?.toFloat() ?: 1f,
        evenOdd = evenOdd,
      )
    }

    /**
     * A `SolidColor` brush's colour as `#AARRGGBB`; null for gradient/none or a non-sRGB packing.
     */
    private fun brushArgb(brush: Any?): String? {
      if (brush == null || brush.javaClass.simpleName != "SolidColor") return null
      val packed = (longField(brush, "value") ?: return null).toULong()
      if (packed and 0xFFFFFFFFuL != 0uL) return null // non-sRGB packing we can't read as flat ARGB
      val argb = (packed shr 32).toInt()
      return if (argb == 0) null else "#%08X".format(argb)
    }

    /** Serialises a `List<PathNode>` (SVG-shaped commands) into an SVG path `d` string. */
    private fun pathData(nodes: List<*>): String {
      val sb = StringBuilder()
      for (n in nodes) {
        if (n == null) continue
        fun g(name: String) = num(floatField(n, name)?.toFloat() ?: 0f)
        fun b(name: String) =
          if (runCatching { field(n, name) as? Boolean }.getOrNull() == true) "1" else "0"
        when (n.javaClass.simpleName) {
          "MoveTo" -> sb.append("M").append(g("x")).append(" ").append(g("y"))
          "RelativeMoveTo" -> sb.append("m").append(g("dx")).append(" ").append(g("dy"))
          "LineTo" -> sb.append("L").append(g("x")).append(" ").append(g("y"))
          "RelativeLineTo" -> sb.append("l").append(g("dx")).append(" ").append(g("dy"))
          "HorizontalTo" -> sb.append("H").append(g("x"))
          "RelativeHorizontalTo" -> sb.append("h").append(g("dx"))
          "VerticalTo" -> sb.append("V").append(g("y"))
          "RelativeVerticalTo" -> sb.append("v").append(g("dy"))
          "CurveTo" ->
            sb.append("C ${g("x1")} ${g("y1")} ${g("x2")} ${g("y2")} ${g("x3")} ${g("y3")}")
          "RelativeCurveTo" ->
            sb.append("c ${g("dx1")} ${g("dy1")} ${g("dx2")} ${g("dy2")} ${g("dx3")} ${g("dy3")}")
          "ReflectiveCurveTo" -> sb.append("S ${g("x1")} ${g("y1")} ${g("x2")} ${g("y2")}")
          "RelativeReflectiveCurveTo" ->
            sb.append("s ${g("dx1")} ${g("dy1")} ${g("dx2")} ${g("dy2")}")
          "QuadTo" -> sb.append("Q ${g("x1")} ${g("y1")} ${g("x2")} ${g("y2")}")
          "RelativeQuadTo" -> sb.append("q ${g("dx1")} ${g("dy1")} ${g("dx2")} ${g("dy2")}")
          "ReflectiveQuadTo" -> sb.append("T ${g("x1")} ${g("y1")}")
          "RelativeReflectiveQuadTo" -> sb.append("t ${g("dx1")} ${g("dy1")}")
          "ArcTo" ->
            sb.append(
              "A ${g("horizontalEllipseRadius")} ${g("verticalEllipseRadius")} ${g("theta")} " +
                "${b("isMoreThanHalf")} ${b("isPositiveArc")} ${g("arcStartX")} ${g("arcStartY")}"
            )
          "RelativeArcTo" ->
            sb.append(
              "a ${g("horizontalEllipseRadius")} ${g("verticalEllipseRadius")} ${g("theta")} " +
                "${b("isMoreThanHalf")} ${b("isPositiveArc")} ${g("arcStartDx")} ${g("arcStartDy")}"
            )
          "Close" -> sb.append("Z")
          else -> return "" // an unknown node type means we can't faithfully serialise — bail
        }
        sb.append(" ")
      }
      return sb.toString().trim()
    }

    /** Compact number: drop a trailing `.0` so `12.0` → `12`, keeping the path string small. */
    private fun num(v: Float): String =
      if (v == v.toLong().toFloat()) v.toLong().toString() else v.toString()

    private fun field(o: Any, name: String): Any? = findField(o.javaClass, name)?.get(o)

    private fun floatField(o: Any, name: String): Double? =
      runCatching { (field(o, name) as? Float)?.toDouble() }.getOrNull()

    private fun longField(o: Any, name: String): Long? =
      runCatching { findField(o.javaClass, name)?.getLong(o) }.getOrNull()

    private fun findField(cls: Class<*>, name: String): java.lang.reflect.Field? {
      var c: Class<*>? = cls
      while (c != null) {
        runCatching {
          return c!!.getDeclaredField(name).apply { isAccessible = true }
        }
        c = c.superclass
      }
      return null
    }
  }

  private data class LayoutSource(
    /**
     * Friendly display label — the node's own `C(...)` name, or the nearest enclosing one. Null
     * when the group carries source info (a `file:line` link worth keeping) but no name at all; the
     * caller then falls back to the measure-policy class for the label.
     */
    val component: String?,
    /**
     * The node's **own** composable identity (its own `C(...)`, or its LayoutNode class when the
     * group carried source info) — never an inherited name. Null when only an enclosing name was
     * available, so the caller falls back to the measure-policy class for identity matching exactly
     * as it did before name inheritance existed.
     */
    val ownComponent: String?,
    val source: String?,
    val sourceInfo: String?,
  )

  private class LayoutSourceIndex(slotTables: ExtensionSlotTables) {
    private val byNode = java.util.IdentityHashMap<Any, LayoutSource>()

    init {
      slotTables.snapshot().forEach { data ->
        data.compositionGroups.forEach { index(it, enclosingName = null) }
      }
    }

    /**
     * Walk the composition-group tree depth-first, carrying the nearest enclosing composable name.
     * A LayoutNode whose own group has no `C(Composable)` marker — a library-internal
     * `Box`/`Row`/`Layout` that a `Button`/`Card`/… builds itself from, which otherwise falls back
     * to its measure-policy class name — inherits the name of the composable that encloses it, so
     * the export layer reads `Button` rather than an anonymous `Box`. A group's own `C(...)` still
     * wins for its own subtree; the inherited name only fills the gaps.
     */
    private fun index(group: CompositionGroup, enclosingName: String?) {
      val sourceInfo = group.sourceInfo
      // The node's own composable name — its own `C(...)`, or null. The raw LayoutNode class
      // (`LayoutNode`) is deliberately NOT used as a fallback: it names nothing a developer wrote
      // and is the same string for every node, so leaving `ownComponent` null lets `toWireNode`
      // fall through to the node's *measure-policy* class instead — a real layout identity
      // (`Column`, `OutlinedTextFieldMeasurePolicy`, …) that both reads like the code and keeps
      // opaque-component raster matching working for controls the token export can't vectorise.
      val ownName = sourceInfo?.componentName()
      // Display label: own `C(...)`, else the nearest enclosing composable. Only a real `C(...)`
      // propagates as the enclosing name to descendants — the label tracks the composable the
      // developer wrote, not Compose's internal layout classes.
      val currentName = ownName ?: enclosingName
      val node = group.node
      // Record an entry when there's a name to carry OR source info to preserve. Skipping a
      // source-info-bearing group would drop its `file:line` source link (the layout-inspector /
      // source-link UI reads it) even though it has no `C(...)` name — so keep the entry and let
      // only `component`/`ownComponent` be null.
      if (node != null && (currentName != null || sourceInfo != null)) {
        byNode[node] =
          LayoutSource(
            component = currentName,
            ownComponent = ownName,
            source = sourceInfo?.sourceLocation(),
            sourceInfo = sourceInfo,
          )
      }
      group.compositionGroups.forEach { index(it, currentName) }
    }

    fun sourceFor(node: Any): LayoutSource? = byNode[node]
  }

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

    val measurePolicy: Any?
      get() = LayoutTreeAccess.measurePolicy(raw)

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
      // The child accessors carry an internal-visibility suffix that differs by build: Android
      // (`compose.ui` aar) mangles to `$ui_release`, the desktop/skiko jar to `$ui` — and the
      // z-sorted accessor has no suffix at all. Try every variant, in draw order first, and
      // coerce the result (a `MutableVector` on desktop, a `List` on Android) to a `List`. Without
      // this the desktop walk silently returned an empty subtree — `layout/inspector` was a lone
      // root node (#1903).
      sequenceOf(
          "getZSortedChildren\$ui_release",
          "getZSortedChildren\$ui",
          "getZSortedChildren",
          "getChildren\$ui_release",
          "getChildren\$ui",
          "getFoldedChildren\$ui_release",
          "getFoldedChildren\$ui",
        )
        .mapNotNull { coerceNodeList(call(node, it)) }
        .firstOrNull { it.isNotEmpty() } ?: emptyList()

    /**
     * Coerce a reflected children accessor's return value to a `List`. Compose returns either a
     * plain `Iterable` or a `MutableVector` (not `Iterable`); the latter exposes `asMutableList()`.
     */
    private fun coerceNodeList(value: Any?): List<Any>? =
      when (value) {
        null -> null
        is Iterable<*> -> value.filterNotNull()
        else -> (call(value, "asMutableList") as? Iterable<*>)?.filterNotNull()
      }

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

    fun measurePolicy(node: Any): Any? = call(node, "getMeasurePolicy")

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
