package ee.schimke.composeai.rcplayer.runtime

import ee.schimke.composeai.rcplayer.protocol.RcBoxLayout
import ee.schimke.composeai.rcplayer.protocol.RcCanvasLayout
import ee.schimke.composeai.rcplayer.protocol.RcDimensionType
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcFloatWord
import ee.schimke.composeai.rcplayer.protocol.RcHeader
import ee.schimke.composeai.rcplayer.protocol.RcLayoutContent
import ee.schimke.composeai.rcplayer.protocol.RcNoArg
import ee.schimke.composeai.rcplayer.protocol.RcOpcodes
import ee.schimke.composeai.rcplayer.protocol.RcPaddingModifier
import ee.schimke.composeai.rcplayer.protocol.RcRootLayout
import ee.schimke.composeai.rcplayer.protocol.RcVersion
import ee.schimke.composeai.rcplayer.protocol.RcWidthModifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class RcLayoutTreeTest {
  private val header = RcHeader(RcVersion(1, 0, 0), modern = false)

  @Test
  fun extractsModifiersContentAndCanvasPaintWithoutMutatingTheLinkedTree() {
    val operations =
      listOf(
        RcRootLayout(1),
        RcBoxLayout(2, 20, 1, 4),
        RcWidthModifier(RcDimensionType.EXACT, RcFloatWord.literal(100f)),
        RcPaddingModifier(
          RcFloatWord.literal(1f),
          RcFloatWord.literal(2f),
          RcFloatWord.literal(3f),
          RcFloatWord.literal(4f),
        ),
        RcPaddingModifier(
          RcFloatWord.literal(5f),
          RcFloatWord.literal(6f),
          RcFloatWord.literal(7f),
          RcFloatWord.literal(8f),
        ),
        RcLayoutContent(3),
        RcCanvasLayout(4, 40),
        RcNoArg(RcOpcodes.CANVAS_OPERATIONS),
        RcNoArg(RcOpcodes.MATRIX_SAVE),
        RcNoArg(RcOpcodes.CONTAINER_END),
        RcNoArg(RcOpcodes.CONTAINER_END),
        RcNoArg(RcOpcodes.CONTAINER_END),
        RcNoArg(RcOpcodes.CONTAINER_END),
        RcNoArg(RcOpcodes.CONTAINER_END),
      )
    val linked = RcDocumentLinker.link(RcDocument(header, operations))

    val root = requireNotNull(RcLayoutTree.build(linked))
    val box = assertIs<RcLayoutNode.Box>(root.children.single())
    val canvas = assertIs<RcLayoutNode.Canvas>(box.content.children.single())

    assertEquals(100f, box.modifiers.width?.value?.value)
    assertEquals(listOf(1f, 5f), box.modifiers.padding.map { it.left.value })
    assertEquals(
      RcOpcodes.MATRIX_SAVE,
      assertIs<RcLinkedNode.Operation>(canvas.paintBlocks.single().single()).operation.opcode,
    )
    assertEquals(operations, linked.source.operations)
  }

  @Test
  fun rejectsMissingContentDuplicateModifiersAndDuplicateIds() {
    assertFailsWith<RcLayoutException> {
      treeOf(RcRootLayout(1), RcBoxLayout(2, 20, 1, 4), ends = 2)
    }
    assertFailsWith<RcLayoutException> {
      treeOf(
        RcRootLayout(1),
        RcCanvasLayout(2, 20),
        RcWidthModifier(RcDimensionType.EXACT, RcFloatWord.literal(10f)),
        RcWidthModifier(RcDimensionType.EXACT, RcFloatWord.literal(20f)),
        ends = 2,
      )
    }
    assertFailsWith<RcLayoutException> {
      treeOf(RcRootLayout(1), RcLayoutContent(2), RcCanvasLayout(2, 20), ends = 3)
    }
  }

  private fun treeOf(
    vararg operations: ee.schimke.composeai.rcplayer.protocol.RcOperation,
    ends: Int,
  ) =
    RcLayoutTree.build(
      RcDocumentLinker.link(
        RcDocument(header, operations.toList() + List(ends) { RcNoArg(RcOpcodes.CONTAINER_END) })
      )
    )
}
