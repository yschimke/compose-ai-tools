package ee.schimke.composeai.rcplayer.runtime

import ee.schimke.composeai.rcplayer.protocol.RcBoxLayout
import ee.schimke.composeai.rcplayer.protocol.RcCanvasLayout
import ee.schimke.composeai.rcplayer.protocol.RcColumnLayout
import ee.schimke.composeai.rcplayer.protocol.RcFitBoxLayout
import ee.schimke.composeai.rcplayer.protocol.RcHeightModifier
import ee.schimke.composeai.rcplayer.protocol.RcLayoutContent
import ee.schimke.composeai.rcplayer.protocol.RcOpcodes
import ee.schimke.composeai.rcplayer.protocol.RcOperation
import ee.schimke.composeai.rcplayer.protocol.RcPaddingModifier
import ee.schimke.composeai.rcplayer.protocol.RcRootLayout
import ee.schimke.composeai.rcplayer.protocol.RcRowLayout
import ee.schimke.composeai.rcplayer.protocol.RcWidthModifier

public data class RcLayoutModifiers(
  val width: RcWidthModifier? = null,
  val height: RcHeightModifier? = null,
  /** AndroidX applies padding modifiers cumulatively, in wire order. */
  val padding: List<RcPaddingModifier> = emptyList(),
)

public sealed interface RcLayoutNode {
  public val componentId: Int
  public val animationId: Int?
  public val modifiers: RcLayoutModifiers

  public data class Root(
    override val componentId: Int,
    override val modifiers: RcLayoutModifiers,
    val children: List<RcLayoutNode>,
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
    val paintBlocks: List<List<RcLinkedNode>>,
    val content: Content?,
  ) : RcLayoutNode

  public data class Box(
    val operation: RcBoxLayout,
    override val modifiers: RcLayoutModifiers,
    val content: Content,
  ) : RcLayoutNode {
    override val componentId: Int = operation.componentId
    override val animationId: Int = operation.animationId
  }

  public data class Row(
    val operation: RcRowLayout,
    override val modifiers: RcLayoutModifiers,
    val content: Content,
  ) : RcLayoutNode {
    override val componentId: Int = operation.componentId
    override val animationId: Int = operation.animationId
  }

  public data class Column(
    val operation: RcColumnLayout,
    override val modifiers: RcLayoutModifiers,
    val content: Content,
  ) : RcLayoutNode {
    override val componentId: Int = operation.componentId
    override val animationId: Int = operation.animationId
  }

  public data class FitBox(
    val operation: RcFitBoxLayout,
    override val modifiers: RcLayoutModifiers,
    val content: Content,
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
    if (roots.isEmpty()) return null
    if (roots.size != 1) throw RcLayoutException("Document has ${roots.size} layout roots")
    val seenIds = mutableSetOf<Int>()
    return parse(roots.single(), seenIds) as RcLayoutNode.Root
  }

  private fun parse(container: RcLinkedNode.Container, seenIds: MutableSet<Int>): RcLayoutNode {
    val modifiers = modifiers(container)
    val node =
      when (val operation = container.operation) {
        is RcRootLayout ->
          RcLayoutNode.Root(operation.componentId, modifiers, childComponents(container, seenIds))
        is RcLayoutContent ->
          RcLayoutNode.Content(
            operation.componentId,
            modifiers,
            childComponents(container, seenIds),
          )
        is RcCanvasLayout -> {
          val paintBlocks =
            container.children
              .filterIsInstance<RcLinkedNode.Container>()
              .filter { it.operation.opcode == RcOpcodes.CANVAS_OPERATIONS }
              .map { it.children }
          RcLayoutNode.Canvas(
            operation.componentId,
            operation.animationId,
            modifiers,
            paintBlocks,
            optionalContent(container, seenIds),
          )
        }
        is RcBoxLayout ->
          RcLayoutNode.Box(operation, modifiers, requiredContent(container, seenIds))
        is RcRowLayout ->
          RcLayoutNode.Row(operation, modifiers, requiredContent(container, seenIds))
        is RcColumnLayout ->
          RcLayoutNode.Column(operation, modifiers, requiredContent(container, seenIds))
        is RcFitBoxLayout ->
          RcLayoutNode.FitBox(operation, modifiers, requiredContent(container, seenIds))
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
    )
  }

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
      this is RcBoxLayout ||
      this is RcRowLayout ||
      this is RcColumnLayout ||
      this is RcFitBoxLayout
}
