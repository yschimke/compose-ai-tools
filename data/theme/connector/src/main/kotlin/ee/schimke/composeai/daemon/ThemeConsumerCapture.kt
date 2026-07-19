package ee.schimke.composeai.daemon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.InspectableValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import ee.schimke.composeai.data.theme.NodeThemeFacts
import ee.schimke.composeai.data.theme.ResolvedThemeTokens
import ee.schimke.composeai.data.theme.ThemeConsumer
import ee.schimke.composeai.data.theme.ThemeConsumerAttribution
import ee.schimke.composeai.data.theme.TypographyToken

/**
 * Reads per-node resolved facts off the rendered semantics tree so each node can be attributed to
 * the `compose/theme` tokens it consumed (#1847).
 *
 * It mirrors `ComposeSemanticsDataProducer`'s text-layout extraction — same unmerged tree, same
 * `nodeId = SemanticsNode.id`, same colour formatting — so `compose/theme.consumers` joins
 * `compose/semantics` by `nodeId` without reconciling two projections.
 *
 * Fact extraction is split from attribution on purpose: the live Compose tree is only valid while
 * the scene is held, so callers run [extractFacts] during capture and feed the result to the pure
 * [ThemeConsumerAttribution] afterwards (the resolved tokens it matches against are only assembled
 * once the render completes). [consumersFor] is the one-shot convenience when both are available at
 * once.
 */
object ThemeConsumerCapture {
  /** Walk the live semantics tree and pull each text node's resolved colours + typography. */
  fun extractFacts(root: Any?): List<NodeThemeFacts> {
    val semanticsRoot = root.toSemanticsRoot() ?: return emptyList()
    val facts = mutableListOf<NodeThemeFacts>()
    // The tree is only valid while the scene is held; guard so a torn-down node never throws out of
    // the render path.
    runCatching { collect(semanticsRoot, inheritedBackground = null, out = facts) }
    return facts
  }

  fun consumersFor(root: Any?, resolved: ResolvedThemeTokens): List<ThemeConsumer> =
    ThemeConsumerAttribution.attribute(extractFacts(root), resolved)

  private fun Any?.toSemanticsRoot(): SemanticsNode? =
    when (this) {
      is SemanticsNode -> this
      is RootForTest -> runCatching { semanticsOwner.unmergedRootSemanticsNode }.getOrNull()
      else -> null
    }

  /**
   * Depth-first walk threading the nearest ancestor background down so a text node whose own (text)
   * background is transparent can still disambiguate its foreground against the container it sits
   * in.
   */
  private fun collect(
    node: SemanticsNode,
    inheritedBackground: String?,
    out: MutableList<NodeThemeFacts>,
  ) {
    val facts = node.config.layoutThemeFacts()
    val effectiveBackground = facts?.background ?: inheritedBackground
    // Text signal (colour/typography) rides the `TextLayoutResult`; the node's clip/container shape
    // rides its modifiers instead — a `Surface`/`Card` is a *non-text* node, so shape is the reason
    // to emit it. `#1847` covered the text legs; this adds the shape leg (the third of the M3
    // triad).
    val hasText = facts != null && (facts.foreground != null || facts.textStyle != null)
    val shape = node.layoutShape()
    if (hasText || shape != null) {
      out +=
        NodeThemeFacts(
          nodeId = node.id.toString(),
          foregroundColor = facts?.foreground,
          // Background is only a text-disambiguation signal; a shape-only container carries none.
          backgroundColor = if (hasText) effectiveBackground else null,
          textStyle = facts?.textStyle,
          shape = shape,
        )
    }
    node.children.forEach { collect(it, effectiveBackground, out) }
  }

  /**
   * The node's resolved clip/container [Shape] as a `Shape.toString()` — matched by
   * [ThemeConsumerAttribution] against the theme's shape scale — read off its modifiers the way
   * `ModifierTokenResolver` reads the layout inspector's shape token. A node with no shape-bearing
   * modifier (the common case for plain layout/text nodes) yields `null`.
   */
  private fun SemanticsNode.layoutShape(): String? {
    val modifiers =
      runCatching { layoutInfo.getModifierInfo().map { it.modifier } }.getOrNull() ?: return null
    return shapeStringOf(modifiers)
  }

  /**
   * The `Shape.toString()` declared by the first shape-bearing modifier — `background(color,
   * shape)`, `clip(shape)` (routed through `graphicsLayer`), or `border(..., shape)` — or `null`
   * when none carries one. Reads the inspector `shape` element first, then a reflected `shape`
   * field, foundation-free, mirroring `ModifierTokenResolver.shapeOf`. `internal` so the extraction
   * is unit-testable against a real modifier chain without a render.
   */
  internal fun shapeStringOf(modifiers: List<Any>): String? {
    for (mod in modifiers) {
      val fromElement =
        (mod as? InspectableValue)?.inspectableElements?.firstOrNull { it.name == "shape" }?.value
          as? Shape
      val shape =
        fromElement
          ?: runCatching {
              mod.javaClass.getDeclaredField("shape").apply { isAccessible = true }.get(mod)
                as? Shape
            }
            .getOrNull()
      // `Modifier.background(color)` defaults its shape to `RectangleShape`; a plain rectangle is
      // never a theme shape role, so skip it and keep scanning for a real (rounded/cut) shape.
      if (shape != null && shape != RectangleShape) return shape.toString()
    }
    return null
  }

  private fun SemanticsConfiguration.layoutThemeFacts(): LayoutThemeFacts? {
    val action = getOrNull(SemanticsActions.GetTextLayoutResult)?.action ?: return null
    val results = mutableListOf<TextLayoutResult>()
    val ok =
      try {
        action(results)
      } catch (_: Throwable) {
        false
      }
    if (!ok && results.isEmpty()) return null
    if (results.isEmpty()) return null
    val foreground = unambiguousColor(results.flatMap { it.textColors() })?.hexArgb()
    val background = unambiguousColor(results.flatMap { it.backgroundColors() })?.hexArgb()
    val textStyle = results.map { it.layoutInput.style }.distinct().singleOrNull()?.token()
    if (foreground == null && background == null && textStyle == null) return null
    return LayoutThemeFacts(foreground = foreground, background = background, textStyle = textStyle)
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

  private data class LayoutThemeFacts(
    val foreground: String?,
    val background: String?,
    val textStyle: TypographyToken?,
  )
}
