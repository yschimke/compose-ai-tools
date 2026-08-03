package ee.schimke.composeai.rcplayer.runtime

import ee.schimke.composeai.rcplayer.protocol.RcBoxLayout
import ee.schimke.composeai.rcplayer.protocol.RcCanvasLayout
import ee.schimke.composeai.rcplayer.protocol.RcCollapsiblePriorityModifier
import ee.schimke.composeai.rcplayer.protocol.RcCollapsibleRowLayout
import ee.schimke.composeai.rcplayer.protocol.RcCoreText
import ee.schimke.composeai.rcplayer.protocol.RcDimensionType
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcFloatWord
import ee.schimke.composeai.rcplayer.protocol.RcFlowLayout
import ee.schimke.composeai.rcplayer.protocol.RcHeader
import ee.schimke.composeai.rcplayer.protocol.RcLayoutContent
import ee.schimke.composeai.rcplayer.protocol.RcNoArg
import ee.schimke.composeai.rcplayer.protocol.RcOpcodes
import ee.schimke.composeai.rcplayer.protocol.RcPaddingModifier
import ee.schimke.composeai.rcplayer.protocol.RcRootLayout
import ee.schimke.composeai.rcplayer.protocol.RcTextStyle
import ee.schimke.composeai.rcplayer.protocol.RcTextStyleProperty
import ee.schimke.composeai.rcplayer.protocol.RcVersion
import ee.schimke.composeai.rcplayer.protocol.RcWidthModifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class RcLayoutTreeTest {
  private val header = RcHeader(RcVersion(1, 0, 0), modern = false)

  @Test
  fun extractsCollapsiblePriorityFromAChildWithoutMutatingTheTree() {
    val root =
      requireNotNull(
        treeOf(
          RcRootLayout(1),
          RcLayoutContent(2),
          RcCollapsibleRowLayout(3, 30, 1, 4, RcFloatWord.literal(0f)),
          RcLayoutContent(4),
          RcCanvasLayout(5, 50),
          RcCollapsiblePriorityModifier(
            RcCollapsiblePriorityModifier.HORIZONTAL,
            RcFloatWord.literal(12f),
          ),
          ends = 5,
        )
      )

    val outerContent = assertIs<RcLayoutNode.Content>(root.children.single())
    val row = assertIs<RcLayoutNode.CollapsibleRow>(outerContent.children.single())
    val child = assertIs<RcLayoutNode.Canvas>(row.content.children.single())
    assertEquals(12f, child.modifiers.collapsiblePriority?.priority?.value)
  }

  @Test
  fun linksFlowAsAnImmutableLayoutContainer() {
    val root =
      requireNotNull(
        treeOf(
          RcRootLayout(1),
          RcLayoutContent(2),
          RcFlowLayout(3, 30, 6, 2, RcFloatWord.literal(8f), 4, 3),
          RcLayoutContent(4),
          RcCanvasLayout(5, 50),
          ends = 5,
        )
      )

    val outerContent = assertIs<RcLayoutNode.Content>(root.children.single())
    val flow = assertIs<RcLayoutNode.Flow>(outerContent.children.single())
    assertEquals(4, flow.operation.maxItemsInEachRow)
    assertEquals(3, flow.operation.maxLines)
    assertIs<RcLayoutNode.Canvas>(flow.content.children.single())
  }

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
      assertIs<RcLinkedNode.Operation>(requireNotNull(canvas.canvasOperations).single())
        .operation
        .opcode,
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

  @Test
  fun resolvesImmutableTextStyleInheritanceBeforeCoreTextRendering() {
    val parent =
      RcTextStyle(
        listOf(
          RcTextStyleProperty.IntValue(1, 100),
          RcTextStyleProperty.IntValue(3, 0xffff0000.toInt()),
          RcTextStyleProperty.FloatValue(5, RcFloatWord.literal(18f)),
        )
      )
    val child =
      RcTextStyle(
        listOf(
          RcTextStyleProperty.IntValue(1, 101),
          RcTextStyleProperty.IntValue(24, 100),
          RcTextStyleProperty.FloatValue(7, RcFloatWord.literal(700f)),
        )
      )
    val core =
      RcCoreText(
        textId = 7,
        properties =
          listOf(
            RcTextStyleProperty.IntValue(1, 3),
            RcTextStyleProperty.IntValue(2, 30),
            RcTextStyleProperty.IntValue(24, 101),
            RcTextStyleProperty.IntValue(3, 0xff0000ff.toInt()),
          ),
      )
    val root =
      requireNotNull(treeOf(parent, child, RcRootLayout(1), RcLayoutContent(2), core, ends = 3))
    val content = assertIs<RcLayoutNode.Content>(root.children.single())
    val text = assertIs<RcLayoutNode.CoreText>(content.children.single())

    assertEquals(
      0xff0000ff.toInt(),
      text.resolvedStyle
        .filterIsInstance<RcTextStyleProperty.IntValue>()
        .single { it.id == 3 }
        .value,
    )
    assertEquals(
      18f,
      text.resolvedStyle
        .filterIsInstance<RcTextStyleProperty.FloatValue>()
        .single { it.id == 5 }
        .value
        .value,
    )
    assertEquals(
      700f,
      text.resolvedStyle
        .filterIsInstance<RcTextStyleProperty.FloatValue>()
        .single { it.id == 7 }
        .value
        .value,
    )
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
