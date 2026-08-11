package ee.schimke.composeai.rcplayer.compose

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import ee.schimke.composeai.rcplayer.protocol.RcAlignByModifier
import ee.schimke.composeai.rcplayer.protocol.RcBackgroundModifier
import ee.schimke.composeai.rcplayer.protocol.RcBitmapData
import ee.schimke.composeai.rcplayer.protocol.RcBorderModifier
import ee.schimke.composeai.rcplayer.protocol.RcBoxLayout
import ee.schimke.composeai.rcplayer.protocol.RcCanvasContent
import ee.schimke.composeai.rcplayer.protocol.RcCanvasLayout
import ee.schimke.composeai.rcplayer.protocol.RcCollapsibleColumnLayout
import ee.schimke.composeai.rcplayer.protocol.RcCollapsiblePriorityModifier
import ee.schimke.composeai.rcplayer.protocol.RcCollapsibleRowLayout
import ee.schimke.composeai.rcplayer.protocol.RcColorConstant
import ee.schimke.composeai.rcplayer.protocol.RcColorExpression
import ee.schimke.composeai.rcplayer.protocol.RcComponentValue
import ee.schimke.composeai.rcplayer.protocol.RcCoreText
import ee.schimke.composeai.rcplayer.protocol.RcDimensionConstraintsModifier
import ee.schimke.composeai.rcplayer.protocol.RcDimensionType
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDraw4
import ee.schimke.composeai.rcplayer.protocol.RcDynamicFloatList
import ee.schimke.composeai.rcplayer.protocol.RcFitBoxLayout
import ee.schimke.composeai.rcplayer.protocol.RcFloatWord
import ee.schimke.composeai.rcplayer.protocol.RcFlowLayout
import ee.schimke.composeai.rcplayer.protocol.RcGraphicsLayerAttribute
import ee.schimke.composeai.rcplayer.protocol.RcGraphicsLayerModifier
import ee.schimke.composeai.rcplayer.protocol.RcHeader
import ee.schimke.composeai.rcplayer.protocol.RcHeightModifier
import ee.schimke.composeai.rcplayer.protocol.RcImageLayout
import ee.schimke.composeai.rcplayer.protocol.RcIntegerConstant
import ee.schimke.composeai.rcplayer.protocol.RcLayoutCompute
import ee.schimke.composeai.rcplayer.protocol.RcLayoutContent
import ee.schimke.composeai.rcplayer.protocol.RcMarqueeModifier
import ee.schimke.composeai.rcplayer.protocol.RcNoArg
import ee.schimke.composeai.rcplayer.protocol.RcOffsetModifier
import ee.schimke.composeai.rcplayer.protocol.RcOpcodes
import ee.schimke.composeai.rcplayer.protocol.RcOperation
import ee.schimke.composeai.rcplayer.protocol.RcPaddingModifier
import ee.schimke.composeai.rcplayer.protocol.RcPaintData
import ee.schimke.composeai.rcplayer.protocol.RcRootLayout
import ee.schimke.composeai.rcplayer.protocol.RcRoundedClipRectModifier
import ee.schimke.composeai.rcplayer.protocol.RcRowLayout
import ee.schimke.composeai.rcplayer.protocol.RcScrollModifier
import ee.schimke.composeai.rcplayer.protocol.RcTextData
import ee.schimke.composeai.rcplayer.protocol.RcTextLayout
import ee.schimke.composeai.rcplayer.protocol.RcTextStyle
import ee.schimke.composeai.rcplayer.protocol.RcTextStyleProperty
import ee.schimke.composeai.rcplayer.protocol.RcTouchExpression
import ee.schimke.composeai.rcplayer.protocol.RcUpdateDynamicFloatList
import ee.schimke.composeai.rcplayer.protocol.RcVersion
import ee.schimke.composeai.rcplayer.protocol.RcVisibilityModifier
import ee.schimke.composeai.rcplayer.protocol.RcWidthInModifier
import ee.schimke.composeai.rcplayer.protocol.RcWidthModifier
import ee.schimke.composeai.rcplayer.protocol.RcZIndexModifier
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap

class RcLayoutRenderTest {
  @Test
  fun rootStateOperationsFeedComponentModifiers() {
    val grey = 0xffb0b0b0.toInt()
    val blue = 0xff2196f3.toInt()
    val progressId = 46
    val colorId = 47
    val document =
      RcDocument(
        RcHeader(RcVersion(1, 0, 0), legacyWidth = 40, legacyHeight = 24, modern = false),
        listOf(
          RcRootLayout(1),
          RcColorConstant(44, grey),
          RcColorConstant(45, blue),
          ee.schimke.composeai.rcplayer.protocol.RcFloatExpression(
            progressId,
            listOf(RcFloatWord.literal(0f)),
            animation = listOf(RcFloatWord.literal(0.2f)),
          ),
          RcColorExpression(
            colorId,
            RcColorExpression.ID_ID_INTERPOLATE,
            44,
            45,
            RcFloatWord(0x7fc00000 or progressId).bits,
          ),
          RcBoxLayout(3, 30, 1, 4),
          width(40f),
          height(24f),
          RcBackgroundModifier(
            flags = RcBackgroundModifier.COLOR_REFERENCE_FLAG,
            colorId = colorId,
            reserved1 = 0,
            reserved2 = 0,
            red = RcFloatWord.literal(0f),
            green = RcFloatWord.literal(0f),
            blue = RcFloatWord.literal(0f),
            alpha = RcFloatWord.literal(0f),
            shapeType = RcBackgroundModifier.SHAPE_RECTANGLE,
          ),
          RcLayoutContent(4),
          RcNoArg(RcOpcodes.CONTAINER_END),
          RcNoArg(RcOpcodes.CONTAINER_END),
          RcNoArg(RcOpcodes.CONTAINER_END),
        ),
      )
    val scene =
      ImageComposeScene(width = 40, height = 24, density = Density(1f)) {
        RcComposePlayer(document)
      }
    try {
      val bitmap = Bitmap().apply { allocN32Pixels(40, 24) }
      check(scene.render(0L).readPixels(bitmap))

      assertEquals(grey, bitmap.getColor(20, 12))
    } finally {
      scene.close()
    }
  }

  @Test
  fun weightedRowAddsSpacingAfterDistributingTheFullRemainingWidth() {
    val red = 0xffff0000.toInt()
    val child = { componentId: Int ->
      listOf<RcOperation>(
        RcBoxLayout(componentId, componentId * 10, 1, 4),
        RcWidthModifier(RcDimensionType.WEIGHT, RcFloatWord.literal(1f)),
        height(20f),
        solidBackground(red = 1f, green = 0f, blue = 0f),
        RcLayoutContent(componentId * 10 + 1),
        RcNoArg(RcOpcodes.CONTAINER_END),
        RcNoArg(RcOpcodes.CONTAINER_END),
      )
    }
    val document =
      RcDocument(
        RcHeader(RcVersion(1, 0, 0), legacyWidth = 100, legacyHeight = 20, modern = false),
        listOf(
          RcRootLayout(1),
          RcLayoutContent(2),
          RcRowLayout(3, 30, 1, 4, RcFloatWord.literal(8f)),
          width(100f),
          height(20f),
          RcLayoutContent(4),
        ) + child(5) + child(6) + child(7) + child(8) + List(4) { RcNoArg(RcOpcodes.CONTAINER_END) },
      )
    val scene =
      ImageComposeScene(width = 100, height = 20, density = Density(1f)) {
        RcComposePlayer(document)
      }
    try {
      val bitmap = Bitmap().apply { allocN32Pixels(100, 20) }
      check(scene.render(0L).readPixels(bitmap))

      assertEquals(red, bitmap.getColor(24, 10), "each weight receives 25px")
      assertEquals(0, bitmap.getColor(25, 10), "the 8px visual gap follows the first weight")
      assertEquals(0, bitmap.getColor(95, 10), "the fourth child begins only after the third gap")
      assertEquals(red, bitmap.getColor(99, 10), "the overflowing fourth child remains clipped")
    } finally {
      scene.close()
    }
  }

  @Test
  fun paddingBeforeExactSizePositionsSubsequentPaint() {
    val document =
      RcDocument(
        RcHeader(RcVersion(1, 0, 0), legacyWidth = 40, legacyHeight = 24, modern = false),
        listOf(
          RcRootLayout(1),
          RcLayoutContent(2),
          RcBoxLayout(3, 30, 1, 4),
          width(40f),
          height(24f),
          RcLayoutContent(4),
          RcBoxLayout(5, 50, 1, 4),
          RcPaddingModifier(
            RcFloatWord.literal(18f),
            RcFloatWord.literal(2f),
            RcFloatWord.literal(0f),
            RcFloatWord.literal(0f),
          ),
          width(16f),
          height(16f),
          solidBackground(red = 1f, green = 1f, blue = 1f),
          RcLayoutContent(6),
        ) + List(6) { RcNoArg(RcOpcodes.CONTAINER_END) },
      )
    val scene =
      ImageComposeScene(width = 40, height = 24, density = Density(1f)) {
        RcComposePlayer(document)
      }
    try {
      val bitmap = Bitmap().apply { allocN32Pixels(40, 24) }
      check(scene.render(0L).readPixels(bitmap))

      assertEquals(0, bitmap.getColor(17, 10))
      assertEquals(0xffffffff.toInt(), bitmap.getColor(18, 2))
      assertEquals(0xffffffff.toInt(), bitmap.getColor(33, 17))
      assertEquals(0, bitmap.getColor(34, 10))
    } finally {
      scene.close()
    }
  }

  @Test
  fun marqueeClipsOverflowingLayoutContent() {
    val red = 0xffff0000.toInt()
    val blue = 0xff0000ff.toInt()
    val document =
      RcDocument(
        RcHeader(RcVersion(1, 0, 0), legacyWidth = 80, legacyHeight = 20, modern = false),
        listOf(
          RcRootLayout(1),
          RcLayoutContent(2),
          RcRowLayout(3, 30, 1, 4, RcFloatWord.literal(0f)),
          width(40f),
          height(20f),
          RcMarqueeModifier(
            iterations = 1,
            animationMode = 0,
            repeatDelayMillis = RcFloatWord.literal(0f),
            initialDelayMillis = RcFloatWord.literal(0f),
            spacing = RcFloatWord.literal(0f),
            velocity = RcFloatWord.literal(40f),
          ),
          RcLayoutContent(4),
        ) +
          canvas(5, 40f, red) +
          canvas(6, 40f, blue) +
          List(4) { RcNoArg(RcOpcodes.CONTAINER_END) },
      )
    val scene =
      ImageComposeScene(width = 80, height = 20, density = Density(1f)) {
        RcComposePlayer(document)
      }
    try {
      val bitmap = Bitmap().apply { allocN32Pixels(80, 20) }
      check(scene.render(0L).readPixels(bitmap))

      assertEquals(red, bitmap.getColor(20, 10))
      assertEquals(0, bitmap.getColor(60, 10), "blue overflow must be clipped to the marquee width")
    } finally {
      scene.close()
    }
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun verticalScrollRevealsOverflowingContent() =
    runSkikoComposeUiTest(
      size = androidx.compose.ui.geometry.Size(40f, 40f),
      density = Density(1f),
    ) {
      val reference: (Int) -> RcFloatWord = { RcFloatWord(0x7fc00000 or it) }
      val end = RcNoArg(RcOpcodes.CONTAINER_END)
      val document =
        RcDocument(
          RcHeader(RcVersion(1, 0, 0), legacyWidth = 40, legacyHeight = 40, modern = false),
          listOf(
            RcRootLayout(1),
            RcLayoutContent(2),
            ee.schimke.composeai.rcplayer.protocol.RcColumnLayout(
              3,
              30,
              1,
              4,
              RcFloatWord.literal(0f),
            ),
            width(40f),
            height(40f),
            RcScrollModifier(
              RcScrollModifier.VERTICAL,
              reference(41),
              reference(42),
              reference(43),
            ),
            RcTouchExpression(
              id = 41,
              defaultValue = RcFloatWord.literal(0f),
              min = RcFloatWord.literal(0f),
              max = reference(42),
              velocityId = RcFloatWord.literal(Float.NaN),
              touchEffects = 0,
              expression = listOf(reference(14)),
              stopMode = RcTouchExpression.STOP_INSTANTLY,
              stopSpec = emptyList(),
              easingSpec = emptyList(),
            ),
            end,
            RcLayoutContent(4),
            RcCanvasLayout(5, 50),
            width(40f),
            height(40f),
            solidBackground(1f, 0f, 0f),
            end,
            RcCanvasLayout(6, 60),
            width(40f),
            height(40f),
            solidBackground(0f, 0f, 1f),
            end,
            end,
            end,
            end,
            end,
          ),
        )
      setContent { RcComposePlayer(document) }
      onRoot().performTouchInput { swipeUp(startY = 35f, endY = 5f, durationMillis = 100L) }

      assertEquals(0xff0000ff.toInt(), onRoot().captureToImage().toPixelMap()[20, 20].toArgb())
    }

  @Test
  fun layoutComputePositionMovesRenderedComponent() {
    val blue = 0xff0000ff.toInt()
    val operations =
      listOf<RcOperation>(
        RcDynamicFloatList(42, RcFloatWord.literal(6f)),
        RcRootLayout(1),
        RcLayoutContent(2),
        RcCanvasLayout(3, 30),
        width(20f),
        height(20f),
        RcLayoutCompute(RcLayoutCompute.POSITION, 42, animateChanges = false),
        RcUpdateDynamicFloatList(42, RcFloatWord.literal(0f), RcFloatWord.literal(30f)),
        RcUpdateDynamicFloatList(42, RcFloatWord.literal(1f), RcFloatWord.literal(25f)),
        RcNoArg(RcOpcodes.CONTAINER_END),
        solidBackground(red = 0f, green = 0f, blue = 1f),
      ) + List(3) { RcNoArg(RcOpcodes.CONTAINER_END) }
    val document =
      RcDocument(
        RcHeader(RcVersion(1, 0, 0), legacyWidth = 80, legacyHeight = 80, modern = false),
        operations,
      )
    val scene =
      ImageComposeScene(width = 80, height = 80, density = Density(1f)) {
        RcComposePlayer(document)
      }
    try {
      val image = scene.render()
      val bitmap = Bitmap().apply { allocN32Pixels(80, 80) }
      check(image.readPixels(bitmap))
      val pixels =
        (0 until 80).flatMap { y ->
          (0 until 80).mapNotNull { x -> if (bitmap.getColor(x, y) == blue) x to y else null }
        }

      assertEquals(30, pixels.minOf { it.first })
      assertEquals(25, pixels.minOf { it.second })
      assertEquals(20 * 20, pixels.size)
    } finally {
      scene.close()
    }
  }

  @Test
  fun layoutComputeMeasureChangesRenderedComponentBounds() {
    val red = 0xffff0000.toInt()
    val operations =
      listOf<RcOperation>(
        RcDynamicFloatList(42, RcFloatWord.literal(6f)),
        RcRootLayout(1),
        RcLayoutContent(2),
        RcCanvasLayout(3, 30),
        width(60f),
        height(50f),
        RcLayoutCompute(RcLayoutCompute.MEASURE, 42, animateChanges = false),
        RcUpdateDynamicFloatList(42, RcFloatWord.literal(2f), RcFloatWord.literal(24f)),
        RcUpdateDynamicFloatList(42, RcFloatWord.literal(3f), RcFloatWord.literal(18f)),
        RcNoArg(RcOpcodes.CONTAINER_END),
        solidBackground(red = 1f, green = 0f, blue = 0f),
      ) + List(3) { RcNoArg(RcOpcodes.CONTAINER_END) }
    val document =
      RcDocument(
        RcHeader(RcVersion(1, 0, 0), legacyWidth = 80, legacyHeight = 80, modern = false),
        operations,
      )
    val scene =
      ImageComposeScene(width = 80, height = 80, density = Density(1f)) {
        RcComposePlayer(document)
      }
    try {
      val image = scene.render()
      val bitmap = Bitmap().apply { allocN32Pixels(80, 80) }
      check(image.readPixels(bitmap))
      val redPixels =
        (0 until 80).sumOf { y -> (0 until 80).count { x -> bitmap.getColor(x, y) == red } }

      assertEquals(24 * 18, redPixels)
    } finally {
      scene.close()
    }
  }

  @Test
  fun rowAlignByFirstBaselineMovesTheSmallerTextComponentDown() {
    val green = 0xff00ff00.toInt()
    val red = 0xffff0000.toInt()
    val operations =
      listOf<RcOperation>(
        RcTextData(10, "A"),
        RcRootLayout(1),
        RcLayoutContent(2),
        RcRowLayout(3, 30, 1, 4, RcFloatWord.literal(0f)),
        width(80f),
        height(80f),
        RcLayoutContent(4),
        textLayout(5, 10, 10f),
        width(40f),
        height(20f),
        RcAlignByModifier(RcFloatWord(0x7fc00001), 0),
        solidBackground(red = 0f, green = 1f, blue = 0f),
        RcNoArg(RcOpcodes.CONTAINER_END),
        textLayout(6, 10, 30f),
        width(40f),
        height(50f),
        RcAlignByModifier(RcFloatWord(0x7fc00001), 0),
        solidBackground(red = 1f, green = 0f, blue = 0f),
        RcNoArg(RcOpcodes.CONTAINER_END),
      ) + List(4) { RcNoArg(RcOpcodes.CONTAINER_END) }
    val document =
      RcDocument(
        RcHeader(RcVersion(1, 0, 0), legacyWidth = 80, legacyHeight = 80, modern = false),
        operations,
      )
    val scene =
      ImageComposeScene(width = 80, height = 80, density = Density(1f)) {
        RcComposePlayer(document)
      }
    try {
      val image = scene.render()
      val bitmap = Bitmap().apply { allocN32Pixels(80, 80) }
      check(image.readPixels(bitmap))
      fun topOf(color: Int): Int =
        (0 until 80).first { y -> (0 until 80).any { x -> bitmap.getColor(x, y) == color } }

      assertTrue(topOf(green) > topOf(red))
    } finally {
      scene.close()
    }
  }

  @Test
  fun collapsibleSelectionUsesAndroidXPriorityOrderAndFirstOverflowCutoff() {
    assertContentEquals(
      booleanArrayOf(false, true, true),
      selectCollapsibleChildren(
        mainSizes = listOf(40, 40, 20),
        priorities = listOf(1f, 3f, 2f),
        maximumMain = 70,
      ),
    )
    assertContentEquals(
      booleanArrayOf(true, false),
      selectCollapsibleChildren(
        mainSizes = listOf(60, 20),
        priorities = listOf(Float.MAX_VALUE, 100f),
        maximumMain = 70,
      ),
    )
  }

  @Test
  fun collapsibleRowRetainsRankedChildrenButPlacesThemInWireOrder() {
    assertCollapsiblePixels(horizontal = true)
  }

  @Test
  fun collapsibleColumnRetainsRankedChildrenButPlacesThemInWireOrder() {
    assertCollapsiblePixels(horizontal = false)
  }

  @Test
  fun flowWrapsAndHonorsMaximumItemsAndLines() {
    val green = 0xff00ff00.toInt()
    val red = 0xffff0000.toInt()
    val blue = 0xff0000ff.toInt()
    val document =
      RcDocument(
        RcHeader(RcVersion(1, 0, 0), legacyWidth = 70, legacyHeight = 50, modern = false),
        listOf<RcOperation>(
          RcRootLayout(1),
          RcLayoutContent(2),
          RcFlowLayout(3, 30, 1, 4, RcFloatWord.literal(10f), 2, 1),
          width(70f),
          height(50f),
          RcLayoutContent(4),
        ) +
          canvas(5, 30f, green) +
          canvas(6, 30f, red) +
          canvas(7, 30f, blue) +
          List(4) { RcNoArg(RcOpcodes.CONTAINER_END) },
      )
    val scene =
      ImageComposeScene(width = 70, height = 50, density = Density(1f)) {
        RcComposePlayer(document)
      }
    try {
      val image = scene.render()
      val bitmap = Bitmap().apply { allocN32Pixels(70, 50) }
      check(image.readPixels(bitmap))

      assertEquals(green, bitmap.getColor(5, 5))
      assertEquals(red, bitmap.getColor(45, 5))
      assertEquals(0, bitmap.getColor(5, 35))
    } finally {
      scene.close()
    }
  }

  @Test
  fun legacyFlowSpacingRemainsInPhysicalPixelsAtHighDensity() {
    val green = 0xff00ff00.toInt()
    val red = 0xffff0000.toInt()
    val document =
      RcDocument(
        RcHeader(RcVersion(1, 1, 0), legacyWidth = 100, legacyHeight = 40, modern = false),
        listOf<RcOperation>(
          RcRootLayout(1),
          RcLayoutContent(2),
          RcFlowLayout(3, 30, 1, 4, RcFloatWord.literal(20f), Int.MAX_VALUE, Int.MAX_VALUE),
          width(100f),
          height(40f),
          RcLayoutContent(4),
        ) +
          canvas(5, 40f, green) +
          canvas(6, 40f, red) +
          List(4) { RcNoArg(RcOpcodes.CONTAINER_END) },
      )
    val scene =
      ImageComposeScene(width = 100, height = 40, density = Density(2f)) {
        RcComposePlayer(document)
      }
    try {
      val bitmap = Bitmap().apply { allocN32Pixels(100, 40) }
      check(scene.render().readPixels(bitmap))

      assertEquals(green, bitmap.getColor(20, 20))
      assertEquals(0, bitmap.getColor(50, 20), "20px wire spacing must not become 20dp")
      assertEquals(red, bitmap.getColor(80, 20), "both children must remain on the first row")
    } finally {
      scene.close()
    }
  }

  @Test
  fun coreTextRendersInheritedSparseStyle() {
    val document =
      RcDocument(
        RcHeader(RcVersion(1, 0, 0), legacyWidth = 100, legacyHeight = 40, modern = false),
        listOf(
          RcTextData(10, "III"),
          RcTextStyle(
            listOf(
              RcTextStyleProperty.IntValue(1, 100),
              RcTextStyleProperty.IntValue(3, 0xffff0000.toInt()),
              RcTextStyleProperty.FloatValue(5, RcFloatWord.literal(24f)),
            )
          ),
          RcTextStyle(
            listOf(
              RcTextStyleProperty.IntValue(1, 101),
              RcTextStyleProperty.IntValue(24, 100),
              RcTextStyleProperty.IntValue(9, RcTextLayout.ALIGN_CENTER),
              RcTextStyleProperty.IntValue(6, 1),
            )
          ),
          RcRootLayout(1),
          RcLayoutContent(2),
          RcCoreText(
            textId = 10,
            properties =
              listOf(
                RcTextStyleProperty.IntValue(1, 3),
                RcTextStyleProperty.IntValue(2, 30),
                RcTextStyleProperty.IntValue(24, 101),
              ),
          ),
          width(100f),
          height(40f),
          RcNoArg(RcOpcodes.CONTAINER_END),
          RcNoArg(RcOpcodes.CONTAINER_END),
          RcNoArg(RcOpcodes.CONTAINER_END),
        ),
      )
    val scene =
      ImageComposeScene(width = 100, height = 40, density = Density(1f)) {
        RcComposePlayer(document)
      }
    try {
      val image = scene.render()
      val bitmap = Bitmap().apply { allocN32Pixels(100, 40) }
      check(image.readPixels(bitmap))
      val painted = buildList {
        for (y in 0 until 40) {
          for (x in 0 until 100) {
            val color = bitmap.getColor(x, y)
            if (color ushr 24 != 0) add(x to color)
          }
        }
      }

      assertTrue(painted.size > 30)
      assertTrue(painted.minOf { it.first } > 25)
      assertTrue(painted.maxOf { it.first } < 75)
      assertTrue(
        painted.any { (_, color) ->
          val red = color ushr 16 and 0xff
          val green = color ushr 8 and 0xff
          red > green
        }
      )
    } finally {
      scene.close()
    }
  }

  @Test
  fun textLayoutMeasuresAndCentersAndroidxText() {
    val document =
      RcDocument(
        RcHeader(RcVersion(1, 0, 0), legacyWidth = 100, legacyHeight = 40, modern = false),
        listOf(
          RcTextData(10, "III"),
          RcRootLayout(1),
          RcLayoutContent(2),
          RcTextLayout(
            componentId = 3,
            animationId = 30,
            textId = 10,
            color = 0xffff0000.toInt(),
            fontSize = RcFloatWord.literal(24f),
            fontStyle = 0,
            fontWeight = RcFloatWord.literal(400f),
            fontFamilyId = -1,
            textAlignAndFlags = RcTextLayout.ALIGN_CENTER,
            overflow = RcTextLayout.OVERFLOW_CLIP,
            maxLines = 1,
          ),
          width(100f),
          height(40f),
          RcNoArg(RcOpcodes.CONTAINER_END),
          RcNoArg(RcOpcodes.CONTAINER_END),
          RcNoArg(RcOpcodes.CONTAINER_END),
        ),
      )
    val scene =
      ImageComposeScene(width = 100, height = 40, density = Density(1f)) {
        RcComposePlayer(document)
      }
    try {
      val image = scene.render()
      val bitmap = Bitmap().apply { allocN32Pixels(100, 40) }
      check(image.readPixels(bitmap))
      val paintedX = buildList {
        for (y in 0 until 40) {
          for (x in 0 until 100) {
            if (bitmap.getColor(x, y) ushr 24 != 0) add(x)
          }
        }
      }

      assertTrue(paintedX.size > 20)
      assertTrue(paintedX.min() > 25)
      assertTrue(paintedX.max() < 75)
    } finally {
      scene.close()
    }
  }

  @Test
  fun boxEndBottomPlacesCanvasAtAndroidxCoordinates() {
    val red = 0xffff0000.toInt()
    val document =
      RcDocument(
        RcHeader(RcVersion(1, 0, 0), legacyWidth = 120, legacyHeight = 120, modern = false),
        listOf(
          RcRootLayout(1),
          RcLayoutContent(2),
          RcBoxLayout(3, 30, horizontalPositioning = 3, verticalPositioning = 5),
          width(100f),
          height(100f),
          RcLayoutContent(4),
          RcCanvasLayout(5, 50),
          width(20f),
          height(20f),
          RcNoArg(RcOpcodes.CANVAS_OPERATIONS),
          RcPaintData(listOf(4, red)),
          RcDraw4(
            RcOpcodes.DRAW_RECT,
            RcFloatWord.literal(0f),
            RcFloatWord.literal(0f),
            RcFloatWord.literal(20f),
            RcFloatWord.literal(20f),
          ),
          RcNoArg(RcOpcodes.CONTAINER_END),
          RcNoArg(RcOpcodes.CONTAINER_END),
          RcNoArg(RcOpcodes.CONTAINER_END),
          RcNoArg(RcOpcodes.CONTAINER_END),
          RcNoArg(RcOpcodes.CONTAINER_END),
          RcNoArg(RcOpcodes.CONTAINER_END),
        ),
      )
    val scene =
      ImageComposeScene(width = 120, height = 120, density = Density(1f)) {
        RcComposePlayer(document)
      }
    try {
      val image = scene.render()
      val bitmap = Bitmap().apply { allocN32Pixels(120, 120) }
      check(image.readPixels(bitmap))

      assertEquals(0, bitmap.getColor(10, 10))
      assertEquals(red, bitmap.getColor(90, 90))
    } finally {
      scene.close()
    }
  }

  @Test
  fun fitBoxPaintsOnlyTheFirstChildWhoseIntrinsicSizeFits() {
    val red = 0xffff0000.toInt()
    val green = 0xff00ff00.toInt()
    val operations =
      listOf<RcOperation>(
        RcRootLayout(1),
        RcLayoutContent(2),
        RcFitBoxLayout(3, 30, horizontalPositioning = 2, verticalPositioning = 2),
        width(100f),
        height(100f),
        RcLayoutContent(4),
      ) +
        canvas(componentId = 5, size = 200f, color = red) +
        canvas(componentId = 6, size = 20f, color = green) +
        List(4) { RcNoArg(RcOpcodes.CONTAINER_END) }
    val document =
      RcDocument(
        RcHeader(RcVersion(1, 0, 0), legacyWidth = 120, legacyHeight = 120, modern = false),
        operations,
      )
    val scene =
      ImageComposeScene(width = 120, height = 120, density = Density(1f)) {
        RcComposePlayer(document)
      }
    try {
      val image = scene.render()
      val bitmap = Bitmap().apply { allocN32Pixels(120, 120) }
      check(image.readPixels(bitmap))

      assertEquals(0, bitmap.getColor(5, 5))
      assertEquals(green, bitmap.getColor(50, 50))
    } finally {
      scene.close()
    }
  }

  @Test
  fun drawContentComposesComponentChildrenAtItsExactPaintPosition() {
    val red = 0xffff0000.toInt()
    val green = 0xff00ff00.toInt()
    val blue = 0xff0000ff.toInt()
    val document =
      RcDocument(
        RcHeader(RcVersion(1, 0, 0), legacyWidth = 120, legacyHeight = 120, modern = false),
        listOf(
          RcRootLayout(1),
          RcLayoutContent(2),
          RcBoxLayout(3, 30, horizontalPositioning = 2, verticalPositioning = 2),
          width(100f),
          height(100f),
          RcNoArg(RcOpcodes.CANVAS_OPERATIONS),
          RcPaintData(listOf(4, red)),
          rect(100f),
          RcNoArg(RcOpcodes.DRAW_CONTENT),
          RcPaintData(listOf(4, blue)),
          rect(10f),
          RcNoArg(RcOpcodes.CONTAINER_END),
          RcLayoutContent(4),
          RcCanvasLayout(5, 50),
          width(20f),
          height(20f),
          RcNoArg(RcOpcodes.CANVAS_OPERATIONS),
          RcPaintData(listOf(4, green)),
          rect(20f),
          RcNoArg(RcOpcodes.CONTAINER_END),
          RcNoArg(RcOpcodes.CONTAINER_END),
          RcNoArg(RcOpcodes.CONTAINER_END),
          RcNoArg(RcOpcodes.CONTAINER_END),
          RcNoArg(RcOpcodes.CONTAINER_END),
          RcNoArg(RcOpcodes.CONTAINER_END),
        ),
      )
    val scene =
      ImageComposeScene(width = 120, height = 120, density = Density(1f)) {
        RcComposePlayer(document)
      }
    try {
      val image = scene.render()
      val bitmap = Bitmap().apply { allocN32Pixels(120, 120) }
      check(image.readPixels(bitmap))

      assertEquals(blue, bitmap.getColor(5, 5))
      assertEquals(red, bitmap.getColor(20, 20))
      assertEquals(green, bitmap.getColor(50, 50))
    } finally {
      scene.close()
    }
  }

  @Test
  fun canvasContentReceivesTheCanvasLayoutContentBounds() {
    val green = 0xff00ff00.toInt()
    val document =
      RcDocument(
        RcHeader(RcVersion(1, 0, 0), legacyWidth = 120, legacyHeight = 120, modern = false),
        listOf(
          RcRootLayout(1),
          RcLayoutContent(2),
          RcCanvasLayout(3, 30),
          width(100f),
          height(80f),
          RcLayoutContent(4),
          RcCanvasContent(5),
          RcPaintData(listOf(4, green)),
          RcDraw4(
            RcOpcodes.DRAW_RECT,
            RcFloatWord.literal(0f),
            RcFloatWord.literal(0f),
            RcFloatWord.literal(100f),
            RcFloatWord.literal(80f),
          ),
          RcNoArg(RcOpcodes.CONTAINER_END),
          RcNoArg(RcOpcodes.CONTAINER_END),
          RcNoArg(RcOpcodes.CONTAINER_END),
          RcNoArg(RcOpcodes.CONTAINER_END),
          RcNoArg(RcOpcodes.CONTAINER_END),
        ),
      )
    val scene =
      ImageComposeScene(width = 120, height = 120, density = Density(1f)) {
        RcComposePlayer(document)
      }
    try {
      val image = scene.render()
      val bitmap = Bitmap().apply { allocN32Pixels(120, 120) }
      check(image.readPixels(bitmap))

      assertEquals(green, bitmap.getColor(99, 79))
      assertEquals(0, bitmap.getColor(101, 81))
    } finally {
      scene.close()
    }
  }

  @Test
  fun nestedComponentValuesDriveDirectLayoutContentDrawingAfterGeometrySettles() {
    val green = 0xff00ff00.toInt()
    val reference: (Int) -> RcFloatWord = { RcFloatWord(0x7fc00000 or it) }
    val document =
      RcDocument(
        RcHeader(RcVersion(1, 0, 0), legacyWidth = 80, legacyHeight = 60, modern = false),
        listOf(
          RcRootLayout(-2),
          RcLayoutContent(-3),
          RcCanvasLayout(-4, 40),
          width(40f),
          height(30f),
          RcOffsetModifier(RcFloatWord.literal(7f), RcFloatWord.literal(9f)),
          RcLayoutContent(-6),
          RcComponentValue(RcComponentValue.WIDTH, componentId = -6, valueId = 42),
          RcComponentValue(RcComponentValue.HEIGHT, componentId = -6, valueId = 43),
          RcPaintData(listOf(4, green)),
          RcDraw4(
            RcOpcodes.DRAW_RECT,
            RcFloatWord.literal(0f),
            RcFloatWord.literal(0f),
            reference(42),
            reference(43),
          ),
          RcNoArg(RcOpcodes.CONTAINER_END),
          RcNoArg(RcOpcodes.CONTAINER_END),
          RcNoArg(RcOpcodes.CONTAINER_END),
          RcNoArg(RcOpcodes.CONTAINER_END),
        ),
      )
    val scene =
      ImageComposeScene(width = 80, height = 60, density = Density(1f)) {
        RcComposePlayer(document)
      }
    try {
      // The first layout publishes geometry; subsequent frames observe the stable dynamic floats.
      scene.render()
      val image = scene.render()
      val bitmap = Bitmap().apply { allocN32Pixels(80, 60) }
      check(image.readPixels(bitmap))

      assertEquals(green, bitmap.getColor(7, 9))
      assertEquals(green, bitmap.getColor(46, 38))
      assertEquals(0, bitmap.getColor(47, 39))
    } finally {
      scene.close()
    }
  }

  @Test
  fun backgroundAndRoundedClipDecorateComponentContentInWireOrder() {
    val red = 0xffff0000.toInt()
    val green = 0xff00ff00.toInt()
    val document =
      RcDocument(
        RcHeader(RcVersion(1, 0, 0), legacyWidth = 100, legacyHeight = 100, modern = false),
        listOf(
          RcRootLayout(1),
          RcLayoutContent(2),
          RcCanvasLayout(3, 30),
          width(80f),
          height(80f),
          RcBackgroundModifier(
            flags = 0,
            colorId = 0,
            reserved1 = 0,
            reserved2 = 0,
            red = RcFloatWord.literal(1f),
            green = RcFloatWord.literal(0f),
            blue = RcFloatWord.literal(0f),
            alpha = RcFloatWord.literal(1f),
            shapeType = RcBackgroundModifier.SHAPE_RECTANGLE,
          ),
          RcRoundedClipRectModifier(
            RcFloatWord.literal(20f),
            RcFloatWord.literal(20f),
            RcFloatWord.literal(20f),
            RcFloatWord.literal(20f),
          ),
          RcNoArg(RcOpcodes.CANVAS_OPERATIONS),
          RcPaintData(listOf(4, green)),
          rect(80f),
          RcNoArg(RcOpcodes.CONTAINER_END),
          RcNoArg(RcOpcodes.CONTAINER_END),
          RcNoArg(RcOpcodes.CONTAINER_END),
          RcNoArg(RcOpcodes.CONTAINER_END),
        ),
      )
    val scene =
      ImageComposeScene(width = 100, height = 100, density = Density(1f)) {
        RcComposePlayer(document)
      }
    try {
      val image = scene.render()
      val bitmap = Bitmap().apply { allocN32Pixels(100, 100) }
      check(image.readPixels(bitmap))

      assertEquals(red, bitmap.getColor(0, 0))
      assertEquals(green, bitmap.getColor(40, 40))
    } finally {
      scene.close()
    }
  }

  @Test
  fun modernBorderPaintsInsideMeasuredComponentBounds() {
    val blue = 0xff0000ff.toInt()
    val document =
      RcDocument(
        RcHeader(RcVersion(1, 0, 0), legacyWidth = 100, legacyHeight = 100, modern = false),
        listOf(
          RcRootLayout(1),
          RcLayoutContent(2),
          RcCanvasLayout(3, 30),
          width(80f),
          height(80f),
          RcBorderModifier(
            flags = 0,
            colorId = 0,
            wireVersion = 1,
            reserved = 0,
            borderWidth = RcFloatWord.literal(4f),
            roundedCorner = RcFloatWord.literal(0f),
            red = RcFloatWord.literal(0f),
            green = RcFloatWord.literal(0f),
            blue = RcFloatWord.literal(1f),
            alpha = RcFloatWord.literal(1f),
            shapeType = RcBackgroundModifier.SHAPE_RECTANGLE,
          ),
          RcNoArg(RcOpcodes.CONTAINER_END),
          RcNoArg(RcOpcodes.CONTAINER_END),
          RcNoArg(RcOpcodes.CONTAINER_END),
        ),
      )
    val scene =
      ImageComposeScene(width = 100, height = 100, density = Density(1f)) {
        RcComposePlayer(document)
      }
    try {
      val image = scene.render()
      val bitmap = Bitmap().apply { allocN32Pixels(100, 100) }
      check(image.readPixels(bitmap))

      assertEquals(blue, bitmap.getColor(40, 1))
      assertEquals(0, bitmap.getColor(40, 40))
      assertEquals(0, bitmap.getColor(81, 40))
    } finally {
      scene.close()
    }
  }

  @Test
  fun offsetAndZIndexPlaceAndOrderOverlappingSiblings() {
    val red = 0xffff0000.toInt()
    val green = 0xff00ff00.toInt()
    val document =
      RcDocument(
        RcHeader(RcVersion(1, 0, 0), legacyWidth = 100, legacyHeight = 100, modern = false),
        listOf(
          RcRootLayout(1),
          RcLayoutContent(2),
          RcBoxLayout(3, 30, horizontalPositioning = 1, verticalPositioning = 4),
          width(80f),
          height(80f),
          RcLayoutContent(4),
          RcCanvasLayout(5, 50),
          width(40f),
          height(40f),
          RcOffsetModifier(RcFloatWord.literal(20f), RcFloatWord.literal(20f)),
          RcZIndexModifier(RcFloatWord.literal(2f)),
          RcNoArg(RcOpcodes.CANVAS_OPERATIONS),
          RcPaintData(listOf(4, red)),
          rect(40f),
          RcNoArg(RcOpcodes.CONTAINER_END),
          RcNoArg(RcOpcodes.CONTAINER_END),
          RcCanvasLayout(6, 60),
          width(40f),
          height(40f),
          RcZIndexModifier(RcFloatWord.literal(1f)),
          RcNoArg(RcOpcodes.CANVAS_OPERATIONS),
          RcPaintData(listOf(4, green)),
          rect(40f),
          RcNoArg(RcOpcodes.CONTAINER_END),
          RcNoArg(RcOpcodes.CONTAINER_END),
          RcNoArg(RcOpcodes.CONTAINER_END),
          RcNoArg(RcOpcodes.CONTAINER_END),
          RcNoArg(RcOpcodes.CONTAINER_END),
          RcNoArg(RcOpcodes.CONTAINER_END),
        ),
      )
    val scene =
      ImageComposeScene(width = 100, height = 100, density = Density(1f)) {
        RcComposePlayer(document)
      }
    try {
      val image = scene.render()
      val bitmap = Bitmap().apply { allocN32Pixels(100, 100) }
      check(image.readPixels(bitmap))

      assertEquals(green, bitmap.getColor(5, 5))
      assertEquals(red, bitmap.getColor(25, 25))
      assertEquals(red, bitmap.getColor(55, 55))
    } finally {
      scene.close()
    }
  }

  @Test
  fun dimensionRangesUseRemoteDpAtNonUnitDensity() {
    val red = 0xffff0000.toInt()
    val document =
      RcDocument(
        RcHeader(RcVersion(1, 0, 0), legacyWidth = 100, legacyHeight = 100, modern = false),
        listOf(
          RcRootLayout(1),
          RcLayoutContent(2),
          RcCanvasLayout(3, 30),
          width(80f),
          height(80f),
          RcWidthInModifier(RcFloatWord.literal(-1f), RcFloatWord.literal(40f)),
          RcDimensionConstraintsModifier(
            RcDimensionConstraintsModifier.VERTICAL,
            RcFloatWord.literal(-1f),
            RcFloatWord.literal(30f),
          ),
          RcBackgroundModifier(
            flags = 0,
            colorId = 0,
            reserved1 = 0,
            reserved2 = 0,
            red = RcFloatWord.literal(1f),
            green = RcFloatWord.literal(0f),
            blue = RcFloatWord.literal(0f),
            alpha = RcFloatWord.literal(1f),
            shapeType = RcBackgroundModifier.SHAPE_RECTANGLE,
          ),
          RcNoArg(RcOpcodes.CONTAINER_END),
          RcNoArg(RcOpcodes.CONTAINER_END),
          RcNoArg(RcOpcodes.CONTAINER_END),
        ),
      )
    val scene =
      ImageComposeScene(width = 200, height = 200, density = Density(2f)) {
        RcComposePlayer(document)
      }
    try {
      val image = scene.render()
      val bitmap = Bitmap().apply { allocN32Pixels(200, 200) }
      check(image.readPixels(bitmap))

      assertEquals(red, bitmap.getColor(79, 59))
      assertEquals(0, bitmap.getColor(81, 59))
      assertEquals(0, bitmap.getColor(79, 61))
    } finally {
      scene.close()
    }
  }

  @Test
  fun invisibleReservesLayoutSpaceWhileGoneDoesNot() {
    val green = 0xff00ff00.toInt()
    val red = 0xffff0000.toInt()
    val yellow = 0xffffff00.toInt()
    val blue = 0xff0000ff.toInt()
    val operations =
      listOf<RcOperation>(
        RcIntegerConstant(10, 1),
        RcIntegerConstant(11, 2),
        RcIntegerConstant(12, 0),
        RcRootLayout(1),
        RcLayoutContent(2),
        RcRowLayout(3, 30, 1, 4, RcFloatWord.literal(0f)),
        width(100f),
        height(20f),
        RcLayoutContent(4),
      ) +
        visibilityCanvas(5, green, 10) +
        visibilityCanvas(6, red, 11) +
        visibilityCanvas(7, yellow, 12) +
        visibilityCanvas(8, blue, 10) +
        List(4) { RcNoArg(RcOpcodes.CONTAINER_END) }
    val document =
      RcDocument(
        RcHeader(RcVersion(1, 0, 0), legacyWidth = 100, legacyHeight = 20, modern = false),
        operations,
      )
    val scene =
      ImageComposeScene(width = 100, height = 20, density = Density(1f)) {
        RcComposePlayer(document)
      }
    try {
      val image = scene.render()
      val bitmap = Bitmap().apply { allocN32Pixels(100, 20) }
      check(image.readPixels(bitmap))

      assertEquals(green, bitmap.getColor(5, 5))
      assertEquals(0, bitmap.getColor(25, 5))
      assertEquals(blue, bitmap.getColor(45, 5))
      assertEquals(0, bitmap.getColor(65, 5))
    } finally {
      scene.close()
    }
  }

  @Test
  fun graphicsLayerTranslatesTheWholeMeasuredComponent() {
    val red = 0xffff0000.toInt()
    val document =
      RcDocument(
        RcHeader(RcVersion(1, 0, 0), legacyWidth = 100, legacyHeight = 100, modern = false),
        listOf(
          RcRootLayout(1),
          RcLayoutContent(2),
          RcCanvasLayout(3, 30),
          width(20f),
          height(20f),
          RcGraphicsLayerModifier(
            listOf(
              RcGraphicsLayerAttribute.FloatValue(
                RcGraphicsLayerModifier.TRANSLATION_X,
                RcFloatWord.literal(30f),
              ),
              RcGraphicsLayerAttribute.FloatValue(
                RcGraphicsLayerModifier.TRANSLATION_Y,
                RcFloatWord.literal(10f),
              ),
            )
          ),
          RcBackgroundModifier(
            flags = 0,
            colorId = 0,
            reserved1 = 0,
            reserved2 = 0,
            red = RcFloatWord.literal(1f),
            green = RcFloatWord.literal(0f),
            blue = RcFloatWord.literal(0f),
            alpha = RcFloatWord.literal(1f),
            shapeType = RcBackgroundModifier.SHAPE_RECTANGLE,
          ),
          RcNoArg(RcOpcodes.CONTAINER_END),
          RcNoArg(RcOpcodes.CONTAINER_END),
          RcNoArg(RcOpcodes.CONTAINER_END),
        ),
      )
    val scene =
      ImageComposeScene(width = 100, height = 100, density = Density(1f)) {
        RcComposePlayer(document)
      }
    try {
      val image = scene.render()
      val bitmap = Bitmap().apply { allocN32Pixels(100, 100) }
      check(image.readPixels(bitmap))

      assertEquals(0, bitmap.getColor(5, 5))
      assertEquals(red, bitmap.getColor(35, 15))
      assertEquals(0, bitmap.getColor(55, 15))
    } finally {
      scene.close()
    }
  }

  @Test
  fun imageLayoutScalesInlineBitmapIntoMeasuredBounds() {
    val red = 0xffff0000.toInt()
    val green = 0xff00ff00.toInt()
    val document =
      RcDocument(
        RcHeader(RcVersion(1, 0, 0), legacyWidth = 60, legacyHeight = 30, modern = false),
        listOf(
          RcBitmapData(
            imageId = 10,
            width = 2,
            height = 1,
            type = RcBitmapData.TYPE_RAW8888,
            encoding = RcBitmapData.ENCODING_INLINE,
            data =
              byteArrayOf(0xff.toByte(), 0, 0, 0xff.toByte(), 0, 0xff.toByte(), 0, 0xff.toByte()),
          ),
          RcRootLayout(1),
          RcLayoutContent(2),
          RcImageLayout(3, 30, bitmapId = 10, scaleType = 6, alpha = RcFloatWord.literal(1f)),
          width(40f),
          height(20f),
          RcNoArg(RcOpcodes.CONTAINER_END),
          RcNoArg(RcOpcodes.CONTAINER_END),
          RcNoArg(RcOpcodes.CONTAINER_END),
        ),
      )
    val scene =
      ImageComposeScene(width = 60, height = 30, density = Density(1f)) {
        RcComposePlayer(document)
      }
    try {
      val image = scene.render()
      val bitmap = Bitmap().apply { allocN32Pixels(60, 30) }
      check(image.readPixels(bitmap))

      assertEquals(red, bitmap.getColor(5, 10))
      assertEquals(green, bitmap.getColor(35, 10))
      assertEquals(0, bitmap.getColor(41, 10))
    } finally {
      scene.close()
    }
  }

  private fun canvas(componentId: Int, size: Float, color: Int): List<RcOperation> =
    listOf(
      RcCanvasLayout(componentId, componentId * 10),
      width(size),
      height(size),
      RcNoArg(RcOpcodes.CANVAS_OPERATIONS),
      RcPaintData(listOf(4, color)),
      RcDraw4(
        RcOpcodes.DRAW_RECT,
        RcFloatWord.literal(0f),
        RcFloatWord.literal(0f),
        RcFloatWord.literal(size),
        RcFloatWord.literal(size),
      ),
      RcNoArg(RcOpcodes.CONTAINER_END),
      RcNoArg(RcOpcodes.CONTAINER_END),
    )

  private fun assertCollapsiblePixels(horizontal: Boolean) {
    val red = 0xffff0000.toInt()
    val green = 0xff00ff00.toInt()
    val blue = 0xff0000ff.toInt()
    val orientation =
      if (horizontal) RcCollapsiblePriorityModifier.HORIZONTAL
      else RcCollapsiblePriorityModifier.VERTICAL
    val layout: RcOperation =
      if (horizontal) {
        RcCollapsibleRowLayout(3, 30, 1, 4, RcFloatWord.literal(0f))
      } else {
        RcCollapsibleColumnLayout(3, 30, 1, 4, RcFloatWord.literal(0f))
      }
    val width = if (horizontal) 70 else 40
    val height = if (horizontal) 40 else 70
    val document =
      RcDocument(
        RcHeader(RcVersion(1, 0, 0), legacyWidth = width, legacyHeight = height, modern = false),
        listOf(
          RcRootLayout(1),
          RcLayoutContent(2),
          layout,
          width(width.toFloat()),
          height(height.toFloat()),
          RcLayoutContent(4),
        ) +
          collapsibleCanvas(5, 40f, red, orientation, 1f) +
          collapsibleCanvas(6, 40f, green, orientation, 3f) +
          collapsibleCanvas(7, 20f, blue, orientation, 2f) +
          List(4) { RcNoArg(RcOpcodes.CONTAINER_END) },
      )
    val scene =
      ImageComposeScene(width = width, height = height, density = Density(1f)) {
        RcComposePlayer(document)
      }
    try {
      val image = scene.render()
      val bitmap = Bitmap().apply { allocN32Pixels(width, height) }
      check(image.readPixels(bitmap))

      assertEquals(green, bitmap.getColor(5, 5))
      assertEquals(blue, bitmap.getColor(if (horizontal) 45 else 5, if (horizontal) 5 else 45))
      assertEquals(0, bitmap.getColor(if (horizontal) 65 else 5, if (horizontal) 5 else 65))
    } finally {
      scene.close()
    }
  }

  private fun collapsibleCanvas(
    componentId: Int,
    size: Float,
    color: Int,
    orientation: Int,
    priority: Float,
  ): List<RcOperation> =
    listOf(
      RcCanvasLayout(componentId, componentId * 10),
      width(size),
      height(size),
      RcCollapsiblePriorityModifier(orientation, RcFloatWord.literal(priority)),
      RcNoArg(RcOpcodes.CANVAS_OPERATIONS),
      RcPaintData(listOf(4, color)),
      rect(size),
      RcNoArg(RcOpcodes.CONTAINER_END),
      RcNoArg(RcOpcodes.CONTAINER_END),
    )

  private fun visibilityCanvas(componentId: Int, color: Int, visibilityId: Int): List<RcOperation> =
    listOf(
      RcCanvasLayout(componentId, componentId * 10),
      width(20f),
      height(20f),
      RcVisibilityModifier(visibilityId),
      RcNoArg(RcOpcodes.CANVAS_OPERATIONS),
      RcPaintData(listOf(4, color)),
      rect(20f),
      RcNoArg(RcOpcodes.CONTAINER_END),
      RcNoArg(RcOpcodes.CONTAINER_END),
    )

  private fun rect(size: Float) =
    RcDraw4(
      RcOpcodes.DRAW_RECT,
      RcFloatWord.literal(0f),
      RcFloatWord.literal(0f),
      RcFloatWord.literal(size),
      RcFloatWord.literal(size),
    )

  private fun textLayout(componentId: Int, textId: Int, fontSize: Float) =
    RcTextLayout(
      componentId = componentId,
      animationId = componentId * 10,
      textId = textId,
      color = 0xff000000.toInt(),
      fontSize = RcFloatWord.literal(fontSize),
      fontStyle = 0,
      fontWeight = RcFloatWord.literal(400f),
      fontFamilyId = -1,
      textAlignAndFlags = RcTextLayout.ALIGN_LEFT,
      overflow = RcTextLayout.OVERFLOW_CLIP,
      maxLines = 1,
    )

  private fun solidBackground(red: Float, green: Float, blue: Float) =
    RcBackgroundModifier(
      flags = 0,
      colorId = 0,
      reserved1 = 0,
      reserved2 = 0,
      red = RcFloatWord.literal(red),
      green = RcFloatWord.literal(green),
      blue = RcFloatWord.literal(blue),
      alpha = RcFloatWord.literal(1f),
      shapeType = RcBackgroundModifier.SHAPE_RECTANGLE,
    )

  private fun width(value: Float) =
    RcWidthModifier(RcDimensionType.EXACT, RcFloatWord.literal(value))

  private fun height(value: Float) =
    RcHeightModifier(RcDimensionType.EXACT, RcFloatWord.literal(value))
}
