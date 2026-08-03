package ee.schimke.composeai.rcplayer.runtime

import ee.schimke.composeai.rcplayer.protocol.RcBackgroundModifier
import ee.schimke.composeai.rcplayer.protocol.RcBorderModifier
import ee.schimke.composeai.rcplayer.protocol.RcBoxLayout
import ee.schimke.composeai.rcplayer.protocol.RcCanvasContent
import ee.schimke.composeai.rcplayer.protocol.RcCanvasLayout
import ee.schimke.composeai.rcplayer.protocol.RcClipRectModifier
import ee.schimke.composeai.rcplayer.protocol.RcColumnLayout
import ee.schimke.composeai.rcplayer.protocol.RcDimensionConstraintsModifier
import ee.schimke.composeai.rcplayer.protocol.RcFitBoxLayout
import ee.schimke.composeai.rcplayer.protocol.RcHeightInModifier
import ee.schimke.composeai.rcplayer.protocol.RcHeightModifier
import ee.schimke.composeai.rcplayer.protocol.RcLayoutContent
import ee.schimke.composeai.rcplayer.protocol.RcOffsetModifier
import ee.schimke.composeai.rcplayer.protocol.RcOpcodes
import ee.schimke.composeai.rcplayer.protocol.RcOperation
import ee.schimke.composeai.rcplayer.protocol.RcPaddingModifier
import ee.schimke.composeai.rcplayer.protocol.RcRootLayout
import ee.schimke.composeai.rcplayer.protocol.RcRoundedClipRectModifier
import ee.schimke.composeai.rcplayer.protocol.RcRowLayout
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

  public data class FitBox(
    val operation: RcFitBoxLayout,
    override val modifiers: RcLayoutModifiers,
    val content: Content,
    val canvasOperations: List<RcLinkedNode>?,
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
    val seenIds = mutableSetOf<Int>()
    return parse(roots.single(), seenIds) as RcLayoutNode.Root
  }

  private fun parse(container: RcLinkedNode.Container, seenIds: MutableSet<Int>): RcLayoutNode {
    val modifiers = modifiers(container)
    val node =
      when (val operation = container.operation) {
        is RcRootLayout ->
          RcLayoutNode.Root(
            operation.componentId,
            modifiers,
            childComponents(container, seenIds),
            canvasOperations(container),
          )
        is RcLayoutContent ->
          RcLayoutNode.Content(
            operation.componentId,
            modifiers,
            childComponents(container, seenIds),
          )
        is RcCanvasLayout -> {
          RcLayoutNode.Canvas(
            operation.componentId,
            operation.animationId,
            modifiers,
            canvasOperations(container),
            optionalContent(container, seenIds),
          )
        }
        is RcCanvasContent -> RcLayoutNode.CanvasContent(operation.componentId, container.children)
        is RcBoxLayout ->
          RcLayoutNode.Box(
            operation,
            modifiers,
            requiredContent(container, seenIds),
            canvasOperations(container),
          )
        is RcRowLayout ->
          RcLayoutNode.Row(
            operation,
            modifiers,
            requiredContent(container, seenIds),
            canvasOperations(container),
          )
        is RcColumnLayout ->
          RcLayoutNode.Column(
            operation,
            modifiers,
            requiredContent(container, seenIds),
            canvasOperations(container),
          )
        is RcFitBoxLayout ->
          RcLayoutNode.FitBox(
            operation,
            modifiers,
            requiredContent(container, seenIds),
            canvasOperations(container),
          )
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
  ): List<RcLayoutNode> =
    container.children
      .filterIsInstance<RcLinkedNode.Container>()
      .filter { it.operation.isLayoutComponent() }
      .map { parse(it, seenIds) }

  private fun optionalContent(
    container: RcLinkedNode.Container,
    seenIds: MutableSet<Int>,
  ): RcLayoutNode.Content? {
    val contents =
      container.children.filterIsInstance<RcLinkedNode.Container>().filter {
        it.operation is RcLayoutContent
      }
    if (contents.size > 1) {
      throw RcLayoutException("Component has ${contents.size} LayoutComponentContent children")
    }
    return contents.singleOrNull()?.let { parse(it, seenIds) as RcLayoutNode.Content }
  }

  private fun requiredContent(
    container: RcLinkedNode.Container,
    seenIds: MutableSet<Int>,
  ): RcLayoutNode.Content =
    optionalContent(container, seenIds)
      ?: throw RcLayoutException(
        "${container.operation::class.simpleName} requires LayoutComponentContent"
      )

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
      this is RcFitBoxLayout

  private fun RcLinkedNode.containsLayoutComponent(): Boolean =
    this is RcLinkedNode.Container &&
      (operation.isLayoutComponent() || children.any { it.containsLayoutComponent() })
}
