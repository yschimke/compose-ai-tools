package ee.schimke.composeai.rcplayer.runtime

import ee.schimke.composeai.rcplayer.protocol.RcCanvasLayout
import ee.schimke.composeai.rcplayer.protocol.RcConditionalOperations
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcFloatConstant
import ee.schimke.composeai.rcplayer.protocol.RcFloatFunctionDefine
import ee.schimke.composeai.rcplayer.protocol.RcFloatWord
import ee.schimke.composeai.rcplayer.protocol.RcHeader
import ee.schimke.composeai.rcplayer.protocol.RcHostAction
import ee.schimke.composeai.rcplayer.protocol.RcIdOperation
import ee.schimke.composeai.rcplayer.protocol.RcLayoutContent
import ee.schimke.composeai.rcplayer.protocol.RcLoopOperation
import ee.schimke.composeai.rcplayer.protocol.RcNoArg
import ee.schimke.composeai.rcplayer.protocol.RcOpcodes
import ee.schimke.composeai.rcplayer.protocol.RcRootLayout
import ee.schimke.composeai.rcplayer.protocol.RcRunAction
import ee.schimke.composeai.rcplayer.protocol.RcVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class RcDocumentLinkerTest {
  @Test
  fun linksNestedConditionalAndLoopBodiesImmutably() {
    val conditional =
      RcConditionalOperations(
        RcConditionalOperations.EQUAL,
        RcFloatWord.literal(1f),
        RcFloatWord.literal(1f),
      )
    val loop =
      RcLoopOperation(20, RcFloatWord.literal(0f), RcFloatWord.literal(1f), RcFloatWord.literal(3f))
    val body = RcFloatConstant(30, RcFloatWord.literal(7f))
    val end = RcNoArg(RcOpcodes.CONTAINER_END)
    val document = RcDocument(header, listOf(conditional, loop, body, end, end))

    val outer =
      assertIs<RcLinkedNode.Container>(RcDocumentLinker.link(document).operations.single())
    val inner = assertIs<RcLinkedNode.Container>(outer.children.single())

    assertEquals(conditional, outer.operation)
    assertEquals(loop, inner.operation)
    assertEquals(body, assertIs<RcLinkedNode.Operation>(inner.children.single()).operation)
  }

  @Test
  fun linksRunActionAsAnImmutableActionContainer() {
    val action = RcHostAction(77)
    val document =
      RcDocument(
        RcHeader(RcVersion(1, 0, 0)),
        listOf(RcRunAction, action, RcNoArg(RcOpcodes.CONTAINER_END)),
      )

    val container =
      assertIs<RcLinkedNode.Container>(RcDocumentLinker.link(document).operations.single())

    assertEquals(RcRunAction, container.operation)
    assertEquals(action, assertIs<RcLinkedNode.Operation>(container.children.single()).operation)
  }

  @Test
  fun linksTheFoundationalLayoutTreeAsImmutableContainers() {
    val document =
      RcDocument(
        RcHeader(RcVersion(0, 1, 0)),
        listOf(
          RcRootLayout(1),
          RcLayoutContent(2),
          RcCanvasLayout(3, 30),
          RcNoArg(RcOpcodes.CANVAS_OPERATIONS),
          RcIdOperation(RcOpcodes.DRAW_PATH, 8),
          RcNoArg(RcOpcodes.CONTAINER_END),
          RcNoArg(RcOpcodes.CONTAINER_END),
          RcNoArg(RcOpcodes.CONTAINER_END),
          RcNoArg(RcOpcodes.CONTAINER_END),
        ),
      )

    val root = assertIs<RcLinkedNode.Container>(RcDocumentLinker.link(document).operations.single())
    val content = assertIs<RcLinkedNode.Container>(root.children.single())
    val canvas = assertIs<RcLinkedNode.Container>(content.children.single())
    val operations = assertIs<RcLinkedNode.Container>(canvas.children.single())

    assertIs<RcRootLayout>(root.operation)
    assertIs<RcLayoutContent>(content.operation)
    assertIs<RcCanvasLayout>(canvas.operation)
    assertEquals(RcOpcodes.CANVAS_OPERATIONS, operations.operation.opcode)
  }

  @Test
  fun linksFloatFunctionBodiesWithoutExecutingThemAtTheRoot() {
    val definition = RcFloatFunctionDefine(40, listOf(7, 8))
    val body = RcFloatConstant(9, RcFloatWord.literal(3f))
    val document =
      RcDocument(
        RcHeader(RcVersion(0, 1, 0)),
        listOf(definition, body, RcNoArg(RcOpcodes.CONTAINER_END)),
      )

    val container =
      assertIs<RcLinkedNode.Container>(RcDocumentLinker.link(document).operations.single())

    assertEquals(definition, container.operation)
    assertEquals(body, assertIs<RcLinkedNode.Operation>(container.children.single()).operation)
  }

  private val header = RcHeader(RcVersion(0, 1, 0), modern = false)

  @Test
  fun nestsCanvasOperationsWithoutChangingTheSourceDocument() {
    val operations =
      listOf(
        RcNoArg(RcOpcodes.CANVAS_OPERATIONS),
        RcIdOperation(RcOpcodes.DRAW_PATH, 8),
        RcNoArg(RcOpcodes.CONTAINER_END),
      )
    val document = RcDocument(header, operations)

    val linked = RcDocumentLinker.link(document)
    val container = assertIs<RcLinkedNode.Container>(linked.operations.single())

    assertEquals(RcOpcodes.CANVAS_OPERATIONS, container.operation.opcode)
    assertEquals(
      RcOpcodes.DRAW_PATH,
      assertIs<RcLinkedNode.Operation>(container.children.single()).operation.opcode,
    )
    assertEquals(operations, document.operations)
  }

  @Test
  fun rejectsUnmatchedAndUnclosedContainers() {
    assertFailsWith<RcLinkException> {
      RcDocumentLinker.link(RcDocument(header, listOf(RcNoArg(RcOpcodes.CONTAINER_END))))
    }
    assertFailsWith<RcLinkException> {
      RcDocumentLinker.link(RcDocument(header, listOf(RcNoArg(RcOpcodes.CANVAS_OPERATIONS))))
    }
  }
}
