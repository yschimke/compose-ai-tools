package ee.schimke.composeai.rcplayer.runtime

import ee.schimke.composeai.rcplayer.protocol.RcBackgroundModifier
import ee.schimke.composeai.rcplayer.protocol.RcBorderModifier
import ee.schimke.composeai.rcplayer.protocol.RcBoxLayout
import ee.schimke.composeai.rcplayer.protocol.RcCanvasContent
import ee.schimke.composeai.rcplayer.protocol.RcCanvasLayout
import ee.schimke.composeai.rcplayer.protocol.RcClipRectModifier
import ee.schimke.composeai.rcplayer.protocol.RcColumnLayout
import ee.schimke.composeai.rcplayer.protocol.RcCoreText
import ee.schimke.composeai.rcplayer.protocol.RcDimensionConstraintsModifier
import ee.schimke.composeai.rcplayer.protocol.RcFitBoxLayout
import ee.schimke.composeai.rcplayer.protocol.RcFlowLayout
import ee.schimke.composeai.rcplayer.protocol.RcGraphicsLayerModifier
import ee.schimke.composeai.rcplayer.protocol.RcHeightInModifier
import ee.schimke.composeai.rcplayer.protocol.RcHeightModifier
import ee.schimke.composeai.rcplayer.protocol.RcImageLayout
import ee.schimke.composeai.rcplayer.protocol.RcLayoutContent
import ee.schimke.composeai.rcplayer.protocol.RcOffsetModifier
import ee.schimke.composeai.rcplayer.protocol.RcOpcodes
import ee.schimke.composeai.rcplayer.protocol.RcOperation
import ee.schimke.composeai.rcplayer.protocol.RcPaddingModifier
import ee.schimke.composeai.rcplayer.protocol.RcRootLayout
import ee.schimke.composeai.rcplayer.protocol.RcRoundedClipRectModifier
import ee.schimke.composeai.rcplayer.protocol.RcRowLayout
import ee.schimke.composeai.rcplayer.protocol.RcTextLayout
import ee.schimke.composeai.rcplayer.protocol.RcTextStyle
import ee.schimke.composeai.rcplayer.protocol.RcTextStyleProperty
import ee.schimke.composeai.rcplayer.protocol.RcVisibilityModifier
import ee.schimke.composeai.rcplayer.protocol.RcWidthInModifier
import ee.schimke.composeai.rcplayer.protocol.RcWidthModifier
import ee.schimke.composeai.rcplayer.protocol.RcZIndexModifier

public data class RcLayoutModifiers(
  val width: RcWidthModifier? = null,
  val height: RcHeightModifier? = null,
  /** AndroidX applies padding modifiers cumulatively, in wire order. */
  val padding: List<RcPaddingModifier> = emptyList(),
  /** Paint decorators retain wire order because nesting changes their result. */
  val paintDecorators: List<RcOperation> = emptyList(),
  /** Placement and stacking modifiers retain wire order and compose cumulatively. */
  val placementModifiers: List<RcOperation> = emptyList(),
  /** Extra size constraints are evaluated before the component's requested dimensions. */
  val dimensionConstraints: List<RcOperation> = emptyList(),
  val visibility: RcVisibilityModifier? = null,
  val graphicsLayer: RcGraphicsLayerModifier? = null,
)

public sealed interface RcLayoutNode {
  public val componentId: Int
  public val animationId: Int?
  public val modifiers: RcLayoutModifiers

  public data class Root(
    override val componentId: Int,
    override val modifiers: RcLayoutModifiers,
    val children: List<RcLayoutNode>,
    val canvasOperations: List<RcLinkedNode>?,
  ) : RcLayoutNode {
    override val animationId: Int? = null
  }

  public data class Content(
    override val componentId: Int,
    override val modifiers: RcLayoutModifiers,
    val children: List<RcLayoutNode>,
  ) : RcLayoutNode {
    override val animationId: Int? = null
  }

  public data class Canvas(
    override val componentId: Int,
    override val animationId: Int,
    override val modifiers: RcLayoutModifiers,
    val canvasOperations: List<RcLinkedNode>?,
    val content: Content?,
  ) : RcLayoutNode

  public data class CanvasContent(
    override val componentId: Int,
    val operations: List<RcLinkedNode>,
  ) : RcLayoutNode {
    override val animationId: Int? = null
    override val modifiers: RcLayoutModifiers = RcLayoutModifiers()
  }

  public data class Box(
    val operation: RcBoxLayout,
    override val modifiers: RcLayoutModifiers,
    val content: Content,
    val canvasOperations: List<RcLinkedNode>?,
  ) : RcLayoutNode {
    override val componentId: Int = operation.componentId
    override val animationId: Int = operation.animationId
  }

  public data class Row(
    val operation: RcRowLayout,
    override val modifiers: RcLayoutModifiers,
    val content: Content,
    val canvasOperations: List<RcLinkedNode>?,
  ) : RcLayoutNode {
    override val componentId: Int = operation.componentId
    override val animationId: Int = operation.animationId
  }

  public data class Column(
    val operation: RcColumnLayout,
    override val modifiers: RcLayoutModifiers,
    val content: Content,
    val canvasOperations: List<RcLinkedNode>?,
  ) : RcLayoutNode {
    override val componentId: Int = operation.componentId
    override val animationId: Int = operation.animationId
  }

  public data class Flow(
    val operation: RcFlowLayout,
    override val modifiers: RcLayoutModifiers,
    val content: Content,
    val canvasOperations: List<RcLinkedNode>?,
  ) : RcLayoutNode {
    override val componentId: Int = operation.componentId
    override val animationId: Int = operation.animationId
  }

  public data class FitBox(
    val operation: RcFitBoxLayout,
    override val modifiers: RcLayoutModifiers,
    val content: Content,
    val canvasOperations: List<RcLinkedNode>?,
  ) : RcLayoutNode {
    override val componentId: Int = operation.componentId
    override val animationId: Int = operation.animationId
  }

  public data class Image(val operation: RcImageLayout, override val modifiers: RcLayoutModifiers) :
    RcLayoutNode {
    override val componentId: Int = operation.componentId
    override val animationId: Int = operation.animationId
  }

  public data class Text(val operation: RcTextLayout, override val modifiers: RcLayoutModifiers) :
    RcLayoutNode {
    override val componentId: Int = operation.componentId
    override val animationId: Int = operation.animationId
  }

  public data class CoreText(
    val operation: RcCoreText,
    override val modifiers: RcLayoutModifiers,
    val resolvedStyle: List<RcTextStyleProperty>,
  ) : RcLayoutNode {
    override val componentId: Int = operation.componentId
    override val animationId: Int = operation.animationId
  }
}

public class RcLayoutException(message: String) : IllegalArgumentException(message)

/**
 * Extracts the immutable component tree from linked wire containers. Unlike AndroidX `inflate()`,
 * this never moves operations between mutable lists or installs parent pointers.
 */
public object RcLayoutTree {
  public fun build(document: RcLinkedDocument): RcLayoutNode.Root? {
    val roots =
      document.operations.filterIsInstance<RcLinkedNode.Container>().filter {
        it.operation is RcRootLayout
      }
    if (roots.isEmpty()) {
      if (document.operations.any { it.containsLayoutComponent() }) {
        throw RcLayoutException("Layout component appears outside a RootLayoutComponent")
      }
      return null
    }
    if (roots.size != 1) throw RcLayoutException("Document has ${roots.size} layout roots")
    val styles =
      document.source.operations.filterIsInstance<RcTextStyle>().mapNotNull { style ->
        style.styleId?.let { it to style }
      }
    if (styles.map { it.first }.distinct().size != styles.size) {
      throw RcLayoutException("Duplicate TextStyle id")
    }
    val stylesById = styles.toMap()
    val seenIds = mutableSetOf<Int>()
    return parse(roots.single(), seenIds, stylesById) as RcLayoutNode.Root
  }

  private fun parse(
    container: RcLinkedNode.Container,
    seenIds: MutableSet<Int>,
    styles: Map<Int, RcTextStyle>,
  ): RcLayoutNode {
    val modifiers = modifiers(container)
    val node =
      when (val operation = container.operation) {
        is RcRootLayout ->
          RcLayoutNode.Root(
            operation.componentId,
            modifiers,
            childComponents(container, seenIds, styles),
            canvasOperations(container),
          )
        is RcLayoutContent ->
          RcLayoutNode.Content(
            operation.componentId,
            modifiers,
            childComponents(container, seenIds, styles),
          )
        is RcCanvasLayout -> {
          RcLayoutNode.Canvas(
            operation.componentId,
            operation.animationId,
            modifiers,
            canvasOperations(container),
            optionalContent(container, seenIds, styles),
          )
        }
        is RcCanvasContent -> RcLayoutNode.CanvasContent(operation.componentId, container.children)
        is RcBoxLayout ->
          RcLayoutNode.Box(
            operation,
            modifiers,
            requiredContent(container, seenIds, styles),
            canvasOperations(container),
          )
        is RcRowLayout ->
          RcLayoutNode.Row(
            operation,
            modifiers,
            requiredContent(container, seenIds, styles),
            canvasOperations(container),
          )
        is RcColumnLayout ->
          RcLayoutNode.Column(
            operation,
            modifiers,
            requiredContent(container, seenIds, styles),
            canvasOperations(container),
          )
        is RcFlowLayout ->
          RcLayoutNode.Flow(
            operation,
            modifiers,
            requiredContent(container, seenIds, styles),
            canvasOperations(container),
          )
        is RcFitBoxLayout ->
          RcLayoutNode.FitBox(
            operation,
            modifiers,
            requiredContent(container, seenIds, styles),
            canvasOperations(container),
          )
        is RcImageLayout -> RcLayoutNode.Image(operation, modifiers)
        is RcTextLayout -> RcLayoutNode.Text(operation, modifiers)
        is RcCoreText ->
          RcLayoutNode.CoreText(operation, modifiers, resolveTextStyle(operation, styles))
        else -> throw RcLayoutException("Opcode ${operation.opcode} is not a layout component")
      }
    if (!seenIds.add(node.componentId)) {
      throw RcLayoutException("Duplicate layout component id ${node.componentId}")
    }
    return node
  }

  private fun childComponents(
    container: RcLinkedNode.Container,
    seenIds: MutableSet<Int>,
    styles: Map<Int, RcTextStyle>,
  ): List<RcLayoutNode> =
    container.children
      .filterIsInstance<RcLinkedNode.Container>()
      .filter { it.operation.isLayoutComponent() }
      .map { parse(it, seenIds, styles) }

  private fun optionalContent(
    container: RcLinkedNode.Container,
    seenIds: MutableSet<Int>,
    styles: Map<Int, RcTextStyle>,
  ): RcLayoutNode.Content? {
    val contents =
      container.children.filterIsInstance<RcLinkedNode.Container>().filter {
        it.operation is RcLayoutContent
      }
    if (contents.size > 1) {
      throw RcLayoutException("Component has ${contents.size} LayoutComponentContent children")
    }
    return contents.singleOrNull()?.let { parse(it, seenIds, styles) as RcLayoutNode.Content }
  }

  private fun requiredContent(
    container: RcLinkedNode.Container,
    seenIds: MutableSet<Int>,
    styles: Map<Int, RcTextStyle>,
  ): RcLayoutNode.Content =
    optionalContent(container, seenIds, styles)
      ?: throw RcLayoutException(
        "${container.operation::class.simpleName} requires LayoutComponentContent"
      )

  private fun resolveTextStyle(
    operation: RcCoreText,
    styles: Map<Int, RcTextStyle>,
  ): List<RcTextStyleProperty> {
    if (operation.componentId < 0 || operation.animationId < 0) {
      throw RcLayoutException("CoreText requires component and animation ids")
    }
    val merged = linkedMapOf<Int, RcTextStyleProperty>()
    operation.textStyleId?.let { styleId ->
      resolveTextStyle(styleId, styles, linkedSetOf()).forEach { merged[it.id] = it }
    }
    operation.properties
      .filterNot { it.id == 1 || it.id == 2 || it.id == 24 }
      .forEach { merged[it.id] = it }
    return merged.values.toList()
  }

  private fun resolveTextStyle(
    styleId: Int,
    styles: Map<Int, RcTextStyle>,
    visiting: MutableSet<Int>,
  ): List<RcTextStyleProperty> {
    if (!visiting.add(styleId)) throw RcLayoutException("Cyclic TextStyle parent at id $styleId")
    val style = styles[styleId] ?: throw RcLayoutException("Missing TextStyle id $styleId")
    val merged = linkedMapOf<Int, RcTextStyleProperty>()
    style.parentStyleId
      ?.takeUnless { it == -1 }
      ?.let { parentId ->
        resolveTextStyle(parentId, styles, visiting).forEach { merged[it.id] = it }
      }
    style.properties
      .filterNot { it.id == 1 || it.id == 2 || it.id == 23 || it.id == 24 }
      .forEach { merged[it.id] = it }
    visiting.remove(styleId)
    return merged.values.toList()
  }

  private fun modifiers(container: RcLinkedNode.Container): RcLayoutModifiers {
    val operations =
      container.children.filterIsInstance<RcLinkedNode.Operation>().map { it.operation }
    return RcLayoutModifiers(
      width = operations.singleModifier<RcWidthModifier>(container.operation),
      height = operations.singleModifier<RcHeightModifier>(container.operation),
      padding = operations.filterIsInstance<RcPaddingModifier>(),
      paintDecorators =
        operations.filter {
          it is RcBackgroundModifier ||
            it is RcBorderModifier ||
            it is RcClipRectModifier ||
            it is RcRoundedClipRectModifier
        },
      placementModifiers = operations.filter { it is RcOffsetModifier || it is RcZIndexModifier },
      dimensionConstraints =
        operations.filter {
          it is RcWidthInModifier ||
            it is RcHeightInModifier ||
            it is RcDimensionConstraintsModifier
        },
      visibility = operations.singleModifier<RcVisibilityModifier>(container.operation),
      graphicsLayer = operations.singleModifier<RcGraphicsLayerModifier>(container.operation),
    )
  }

  /** CoreDocument assigns the last CanvasOperations container to its enclosing component. */
  private fun canvasOperations(container: RcLinkedNode.Container): List<RcLinkedNode>? =
    container.children
      .filterIsInstance<RcLinkedNode.Container>()
      .lastOrNull { it.operation.opcode == RcOpcodes.CANVAS_OPERATIONS }
      ?.children

  private inline fun <reified T : RcOperation> List<RcOperation>.singleModifier(
    component: RcOperation
  ): T? {
    val matches = filterIsInstance<T>()
    if (matches.size > 1) {
      throw RcLayoutException(
        "${component::class.simpleName} has ${matches.size} ${T::class.simpleName} operations"
      )
    }
    return matches.singleOrNull()
  }

  private fun RcOperation.isLayoutComponent(): Boolean =
    this is RcRootLayout ||
      this is RcLayoutContent ||
      this is RcCanvasLayout ||
      this is RcCanvasContent ||
      this is RcBoxLayout ||
      this is RcRowLayout ||
      this is RcColumnLayout ||
      this is RcFlowLayout ||
      this is RcFitBoxLayout ||
      this is RcImageLayout ||
      this is RcTextLayout ||
      this is RcCoreText

  private fun RcLinkedNode.containsLayoutComponent(): Boolean =
    this is RcLinkedNode.Container &&
      (operation.isLayoutComponent() || children.any { it.containsLayoutComponent() })
}
