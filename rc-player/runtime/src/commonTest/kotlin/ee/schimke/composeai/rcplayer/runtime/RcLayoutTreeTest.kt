package ee.schimke.composeai.rcplayer.runtime

import ee.schimke.composeai.rcplayer.protocol.RcAccessibilitySemantics
import ee.schimke.composeai.rcplayer.protocol.RcAlignByModifier
import ee.schimke.composeai.rcplayer.protocol.RcBoxLayout
import ee.schimke.composeai.rcplayer.protocol.RcCanvasLayout
import ee.schimke.composeai.rcplayer.protocol.RcClickModifier
import ee.schimke.composeai.rcplayer.protocol.RcCollapsiblePriorityModifier
import ee.schimke.composeai.rcplayer.protocol.RcCollapsibleRowLayout
import ee.schimke.composeai.rcplayer.protocol.RcComponentValue
import ee.schimke.composeai.rcplayer.protocol.RcCoreText
import ee.schimke.composeai.rcplayer.protocol.RcDimensionType
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDynamicFloatList
import ee.schimke.composeai.rcplayer.protocol.RcFloatWord
import ee.schimke.composeai.rcplayer.protocol.RcFlowLayout
import ee.schimke.composeai.rcplayer.protocol.RcHeader
import ee.schimke.composeai.rcplayer.protocol.RcHostAction
import ee.schimke.composeai.rcplayer.protocol.RcLayoutCompute
import ee.schimke.composeai.rcplayer.protocol.RcLayoutContent
import ee.schimke.composeai.rcplayer.protocol.RcMarqueeModifier
import ee.schimke.composeai.rcplayer.protocol.RcMultiClickModifier
import ee.schimke.composeai.rcplayer.protocol.RcMultiClickType
import ee.schimke.composeai.rcplayer.protocol.RcNoArg
import ee.schimke.composeai.rcplayer.protocol.RcOpcodes
import ee.schimke.composeai.rcplayer.protocol.RcPaddingModifier
import ee.schimke.composeai.rcplayer.protocol.RcRippleModifier
import ee.schimke.composeai.rcplayer.protocol.RcRootLayout
import ee.schimke.composeai.rcplayer.protocol.RcScrollModifier
import ee.schimke.composeai.rcplayer.protocol.RcTextStyle
import ee.schimke.composeai.rcplayer.protocol.RcTextStyleProperty
import ee.schimke.composeai.rcplayer.protocol.RcTouchCancelModifier
import ee.schimke.composeai.rcplayer.protocol.RcTouchDownModifier
import ee.schimke.composeai.rcplayer.protocol.RcTouchExpression
import ee.schimke.composeai.rcplayer.protocol.RcTouchUpModifier
import ee.schimke.composeai.rcplayer.protocol.RcUpdateDynamicFloatList
import ee.schimke.composeai.rcplayer.protocol.RcVersion
import ee.schimke.composeai.rcplayer.protocol.RcWidthModifier
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class RcLayoutTreeTest {
  private val header = RcHeader(RcVersion(1, 0, 0), modern = false)

  @Test
  fun validatesNestedComponentValueTargetsAndConflictingOutputs() {
    // CoreDocument discovers ComponentValue recursively, including inside CanvasOperations.
    requireNotNull(
      treeOf(
        RcRootLayout(1),
        RcLayoutContent(2),
        RcCanvasLayout(3, 30),
        RcNoArg(RcOpcodes.CANVAS_OPERATIONS),
        RcComponentValue(RcComponentValue.WIDTH, componentId = 3, valueId = 90),
        ends = 4,
      )
    )
    assertFailsWith<RcLayoutException> {
      treeOf(
        RcRootLayout(1),
        RcComponentValue(RcComponentValue.WIDTH, componentId = 404, valueId = 90),
        ends = 1,
      )
    }
    assertFailsWith<RcLayoutException> {
      treeOf(
        RcRootLayout(1),
        RcLayoutContent(2),
        RcComponentValue(RcComponentValue.WIDTH, componentId = 1, valueId = 90),
        RcComponentValue(RcComponentValue.HEIGHT, componentId = 2, valueId = 90),
        ends = 2,
      )
    }
  }

  @Test
  fun retainsRippleInPaintDecoratorWireOrder() {
    val root =
      requireNotNull(
        treeOf(
          RcRootLayout(1),
          RcLayoutContent(2),
          RcCanvasLayout(3, 30),
          RcRippleModifier,
          ends = 3,
        )
      )
    val content = assertIs<RcLayoutNode.Content>(root.children.single())
    val canvas = assertIs<RcLayoutNode.Canvas>(content.children.single())

    assertEquals(listOf(RcRippleModifier), canvas.modifiers.paintDecorators)
  }

  @Test
  fun preservesClickActionsAsAnImmutableModifierBlock() {
    val root =
      requireNotNull(
        treeOf(
          RcRootLayout(1),
          RcLayoutContent(2),
          RcCanvasLayout(3, 30),
          RcClickModifier,
          RcHostAction(77),
          RcNoArg(RcOpcodes.CONTAINER_END),
          ends = 3,
        )
      )
    val content = assertIs<RcLayoutNode.Content>(root.children.single())
    val canvas = assertIs<RcLayoutNode.Canvas>(content.children.single())

    assertEquals(
      RcHostAction(77),
      assertIs<RcLinkedNode.Operation>(canvas.modifiers.clicks.single().children.single()).operation,
    )
  }

  @Test
  fun preservesScrollTouchExpressionAsAnImmutableModifierBlock() {
    val touch =
      RcTouchExpression(
        id = 41,
        defaultValue = RcFloatWord.literal(0f),
        min = RcFloatWord.literal(0f),
        max = RcFloatWord.literal(100f),
        velocityId = RcFloatWord.literal(Float.NaN),
        touchEffects = 0,
        expression = emptyList(),
        stopMode = RcTouchExpression.STOP_INSTANTLY,
        stopSpec = emptyList(),
        easingSpec = emptyList(),
      )
    val scroll =
      RcScrollModifier(
        RcScrollModifier.VERTICAL,
        RcFloatWord.literal(0f),
        RcFloatWord.literal(100f),
        RcFloatWord.literal(0f),
      )
    val root =
      requireNotNull(
        treeOf(
          RcRootLayout(1),
          RcLayoutContent(2),
          RcCanvasLayout(3, 30),
          scroll,
          touch,
          RcNoArg(RcOpcodes.CONTAINER_END),
          ends = 3,
        )
      )
    val content = assertIs<RcLayoutNode.Content>(root.children.single())
    val canvas = assertIs<RcLayoutNode.Canvas>(content.children.single())

    assertEquals(scroll, canvas.modifiers.scroll?.operation)
    assertEquals(
      touch,
      assertIs<RcLinkedNode.Operation>(canvas.modifiers.scroll?.children?.single()).operation,
    )
  }

  @Test
  fun extractsMarqueeAsAnImmutableModifier() {
    val marquee =
      RcMarqueeModifier(
        iterations = 3,
        animationMode = 1,
        repeatDelayMillis = RcFloatWord.literal(250f),
        initialDelayMillis = RcFloatWord.literal(500f),
        spacing = RcFloatWord.literal(12f),
        velocity = RcFloatWord.literal(40f),
      )
    val root =
      requireNotNull(
        treeOf(RcRootLayout(1), RcLayoutContent(2), RcCanvasLayout(3, 30), marquee, ends = 3)
      )
    val content = assertIs<RcLayoutNode.Content>(root.children.single())
    val canvas = assertIs<RcLayoutNode.Canvas>(content.children.single())

    assertEquals(marquee, canvas.modifiers.marquee)
  }

  @Test
  fun preservesTouchLifecycleActionsInWireOrder() {
    val end = RcNoArg(RcOpcodes.CONTAINER_END)
    val root =
      requireNotNull(
        treeOf(
          RcRootLayout(1),
          RcLayoutContent(2),
          RcCanvasLayout(3, 30),
          RcTouchDownModifier,
          RcHostAction(71),
          end,
          RcTouchUpModifier,
          RcHostAction(72),
          end,
          RcTouchCancelModifier,
          RcHostAction(73),
          end,
          ends = 3,
        )
      )
    val content = assertIs<RcLayoutNode.Content>(root.children.single())
    val canvas = assertIs<RcLayoutNode.Canvas>(content.children.single())

    assertEquals(
      listOf(RcTouchActionType.DOWN, RcTouchActionType.UP, RcTouchActionType.CANCEL),
      canvas.modifiers.touchActions.map { it.type },
    )
    assertEquals(
      listOf(71, 72, 73),
      canvas.modifiers.touchActions.map {
        assertIs<RcHostAction>(assertIs<RcLinkedNode.Operation>(it.children.single()).operation)
          .actionId
      },
    )
  }

  @Test
  fun preservesMultiClickActionsInWireOrder() {
    val end = RcNoArg(RcOpcodes.CONTAINER_END)
    val root =
      requireNotNull(
        treeOf(
          RcRootLayout(1),
          RcLayoutContent(2),
          RcCanvasLayout(3, 30),
          RcMultiClickModifier(RcMultiClickType.SINGLE),
          RcHostAction(71),
          end,
          RcMultiClickModifier(RcMultiClickType.LONG),
          RcHostAction(72),
          end,
          RcMultiClickModifier(RcMultiClickType.DOUBLE),
          RcHostAction(73),
          end,
          ends = 3,
        )
      )
    val content = assertIs<RcLayoutNode.Content>(root.children.single())
    val canvas = assertIs<RcLayoutNode.Canvas>(content.children.single())

    assertEquals(
      listOf(RcClickActionType.SINGLE, RcClickActionType.LONG, RcClickActionType.DOUBLE),
      canvas.modifiers.clicks.map { it.type },
    )
    assertEquals(
      listOf(71, 72, 73),
      canvas.modifiers.clicks.map {
        assertIs<RcHostAction>(assertIs<RcLinkedNode.Operation>(it.children.single()).operation)
          .actionId
      },
    )
  }

  @Test
  fun extractsAccessibilityModifiersInWireOrder() {
    val first =
      RcAccessibilitySemantics(10, RcAccessibilitySemantics.ROLE_BUTTON, 11, 12, 0, true, false)
    val second =
      RcAccessibilitySemantics(20, RcAccessibilitySemantics.ROLE_IMAGE, 21, 22, 2, false, false)
    val root =
      requireNotNull(
        treeOf(RcRootLayout(1), RcLayoutContent(2), RcCanvasLayout(3, 30), first, second, ends = 3)
      )
    val content = assertIs<RcLayoutNode.Content>(root.children.single())
    val canvas = assertIs<RcLayoutNode.Canvas>(content.children.single())

    assertEquals(listOf(first, second), canvas.modifiers.accessibility)
  }

  @Test
  fun preservesAndEvaluatesLayoutComputeAsAnImmutableModifierBlock() {
    val operations =
      listOf(
        RcDynamicFloatList(42, RcFloatWord.literal(6f)),
        RcRootLayout(1),
        RcLayoutContent(2),
        RcCanvasLayout(3, 30),
        RcLayoutCompute(RcLayoutCompute.MEASURE, boundsId = 42, animateChanges = false),
        RcUpdateDynamicFloatList(42, RcFloatWord.literal(2f), RcFloatWord.literal(37f)),
        RcUpdateDynamicFloatList(42, RcFloatWord.literal(3f), RcFloatWord.literal(19f)),
        RcNoArg(RcOpcodes.CONTAINER_END),
        RcNoArg(RcOpcodes.CONTAINER_END),
        RcNoArg(RcOpcodes.CONTAINER_END),
        RcNoArg(RcOpcodes.CONTAINER_END),
      )
    val document = RcDocument(header, operations)
    val root = requireNotNull(RcLayoutTree.build(RcDocumentLinker.link(document)))
    val content = assertIs<RcLayoutNode.Content>(root.children.single())
    val canvas = assertIs<RcLayoutNode.Canvas>(content.children.single())
    val block = canvas.modifiers.layoutComputes.single()

    assertEquals(RcLayoutCompute.MEASURE, block.operation.type)
    assertEquals(2, block.children.size)
    assertContentEquals(
      floatArrayOf(4f, 5f, 37f, 19f, 100f, 80f),
      RcPlayerState(document)
        .evaluateLayoutCompute(block, floatArrayOf(4f, 5f, 20f, 10f, 100f, 80f)),
    )
  }

  @Test
  fun extractsAlignByAsTypedChildLayoutMetadata() {
    val root =
      requireNotNull(
        treeOf(
          RcRootLayout(1),
          RcLayoutContent(2),
          RcCanvasLayout(3, 30),
          RcAlignByModifier(RcFloatWord(0x7fc00001), flags = 5),
          ends = 3,
        )
      )

    val content = assertIs<RcLayoutNode.Content>(root.children.single())
    val canvas = assertIs<RcLayoutNode.Canvas>(content.children.single())
    assertEquals(RcAlignByModifier.FIRST_BASELINE_ID, canvas.modifiers.alignBy?.line?.referencedId)
    assertEquals(5, canvas.modifiers.alignBy?.flags)
  }

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
  fun rejectsMissingContentAndDuplicateIdsButKeepsFirstRequiredDimension() {
    assertFailsWith<RcLayoutException> {
      treeOf(RcRootLayout(1), RcBoxLayout(2, 20, 1, 4), ends = 2)
    }
    val repeatedDimensions =
      requireNotNull(
        treeOf(
          RcRootLayout(1),
          RcCanvasLayout(2, 20),
          RcWidthModifier(RcDimensionType.EXACT, RcFloatWord.literal(10f)),
          RcWidthModifier(RcDimensionType.FILL, RcFloatWord.literal(1f)),
          ends = 2,
        )
      )
    assertEquals(
      10f,
      assertIs<RcLayoutNode.Canvas>(repeatedDimensions.children.single())
        .modifiers
        .width
        ?.value
        ?.value,
    )
    assertFailsWith<RcLayoutException> {
      treeOf(RcRootLayout(1), RcLayoutContent(2), RcCanvasLayout(2, 20), ends = 3)
    }
  }

  @Test
  fun retainsLeafLayoutContentIdsForComponentValues() {
    val coreText =
      RcCoreText(
        42,
        listOf(
          RcTextStyleProperty.IntValue(1, 3),
          RcTextStyleProperty.IntValue(4, 0xff000000.toInt()),
        ),
      )
    val root =
      requireNotNull(
        treeOf(
          RcRootLayout(1),
          RcLayoutContent(2),
          coreText,
          RcLayoutContent(4),
          RcComponentValue(RcComponentValue.WIDTH, componentId = 4, valueId = 90),
          ends = 4,
        )
      )

    val content = assertIs<RcLayoutNode.Content>(root.children.single())
    assertEquals(4, assertIs<RcLayoutNode.CoreText>(content.children.single()).contentComponentId)
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

  @Test
  fun acceptsAlpha16CoreTextWithoutOptionalComponentOrAnimationIds() {
    val core =
      RcCoreText(
        textId = 7,
        properties = listOf(RcTextStyleProperty.FloatValue(5, RcFloatWord.literal(18f))),
      )

    val root = requireNotNull(treeOf(RcRootLayout(1), RcLayoutContent(2), core, ends = 3))
    val content = assertIs<RcLayoutNode.Content>(root.children.single())
    val text = assertIs<RcLayoutNode.CoreText>(content.children.single())

    assertEquals(-1, text.operation.componentId)
    assertEquals(-1, text.operation.animationId)
    assertEquals(core.properties, text.resolvedStyle)
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
