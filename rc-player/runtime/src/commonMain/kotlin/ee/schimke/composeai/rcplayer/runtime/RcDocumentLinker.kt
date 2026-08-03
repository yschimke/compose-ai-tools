package ee.schimke.composeai.rcplayer.runtime

import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcOpcodes
import ee.schimke.composeai.rcplayer.protocol.RcOperation

public sealed interface RcLinkedNode {
  public data class Operation(val operation: RcOperation) : RcLinkedNode

  public data class Container(val operation: RcOperation, val children: List<RcLinkedNode>) :
    RcLinkedNode
}

public data class RcLinkedDocument(val source: RcDocument, val operations: List<RcLinkedNode>)

public class RcLinkException(message: String) : IllegalArgumentException(message)

/** Turns AndroidX's flat container-delimited stream into immutable nodes without changing bytes. */
public object RcDocumentLinker {
  private const val MAX_NESTING_DEPTH = 256

  public fun link(document: RcDocument): RcLinkedDocument {
    val root = mutableListOf<RcLinkedNode>()
    val stack = mutableListOf<Frame>()
    var destination = root
    document.operations.forEachIndexed { index, operation ->
      when {
        operation.opcode in containerStartOpcodes -> {
          if (stack.size >= MAX_NESTING_DEPTH) {
            throw RcLinkException(
              "Container nesting exceeds $MAX_NESTING_DEPTH at operation $index"
            )
          }
          val frame = Frame(operation, destination, mutableListOf())
          stack += frame
          destination = frame.children
        }
        operation.opcode == RcOpcodes.CONTAINER_END -> {
          val frame =
            stack.removeLastOrNull()
              ?: throw RcLinkException("Unmatched ContainerEnd at operation $index")
          frame.parent += RcLinkedNode.Container(frame.operation, frame.children.toList())
          destination = frame.parent
        }
        else -> destination += RcLinkedNode.Operation(operation)
      }
    }
    if (stack.isNotEmpty()) {
      throw RcLinkException(
        "Unclosed ${stack.last().operation::class.simpleName} container at end of document"
      )
    }
    return RcLinkedDocument(document, root)
  }

  private data class Frame(
    val operation: RcOperation,
    val parent: MutableList<RcLinkedNode>,
    val children: MutableList<RcLinkedNode>,
  )

  private val containerStartOpcodes =
    setOf(
      RcOpcodes.CANVAS_OPERATIONS,
      RcOpcodes.LAYOUT_CANVAS_CONTENT,
      RcOpcodes.FUNCTION_DEFINE,
      RcOpcodes.LAYOUT_ROOT,
      RcOpcodes.LAYOUT_CONTENT,
      RcOpcodes.LAYOUT_BOX,
      RcOpcodes.LAYOUT_ROW,
      RcOpcodes.LAYOUT_COLUMN,
      RcOpcodes.LAYOUT_CANVAS,
      RcOpcodes.LAYOUT_FIT_BOX,
      RcOpcodes.LAYOUT_IMAGE,
      RcOpcodes.LAYOUT_TEXT,
      RcOpcodes.CORE_TEXT,
    )
}
