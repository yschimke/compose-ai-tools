package ee.schimke.composeai.rcplayer.runtime

import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcFloatConstant
import ee.schimke.composeai.rcplayer.protocol.RcFloatFunctionDefine
import ee.schimke.composeai.rcplayer.protocol.RcFloatWord
import ee.schimke.composeai.rcplayer.protocol.RcHeader
import ee.schimke.composeai.rcplayer.protocol.RcIdOperation
import ee.schimke.composeai.rcplayer.protocol.RcNoArg
import ee.schimke.composeai.rcplayer.protocol.RcOpcodes
import ee.schimke.composeai.rcplayer.protocol.RcVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class RcDocumentLinkerTest {
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
