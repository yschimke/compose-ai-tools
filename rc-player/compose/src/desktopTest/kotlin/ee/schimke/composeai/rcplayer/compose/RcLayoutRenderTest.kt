package ee.schimke.composeai.rcplayer.compose

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import ee.schimke.composeai.rcplayer.protocol.RcBackgroundModifier
import ee.schimke.composeai.rcplayer.protocol.RcBoxLayout
import ee.schimke.composeai.rcplayer.protocol.RcCanvasContent
import ee.schimke.composeai.rcplayer.protocol.RcCanvasLayout
import ee.schimke.composeai.rcplayer.protocol.RcDimensionType
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDraw4
import ee.schimke.composeai.rcplayer.protocol.RcFitBoxLayout
import ee.schimke.composeai.rcplayer.protocol.RcFloatWord
import ee.schimke.composeai.rcplayer.protocol.RcHeader
import ee.schimke.composeai.rcplayer.protocol.RcHeightModifier
import ee.schimke.composeai.rcplayer.protocol.RcLayoutContent
import ee.schimke.composeai.rcplayer.protocol.RcNoArg
import ee.schimke.composeai.rcplayer.protocol.RcOpcodes
import ee.schimke.composeai.rcplayer.protocol.RcOperation
import ee.schimke.composeai.rcplayer.protocol.RcPaintData
import ee.schimke.composeai.rcplayer.protocol.RcRootLayout
import ee.schimke.composeai.rcplayer.protocol.RcRoundedClipRectModifier
import ee.schimke.composeai.rcplayer.protocol.RcVersion
import ee.schimke.composeai.rcplayer.protocol.RcWidthModifier
import kotlin.test.Test
import kotlin.test.assertEquals
import org.jetbrains.skia.Bitmap

class RcLayoutRenderTest {
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

  private fun rect(size: Float) =
    RcDraw4(
      RcOpcodes.DRAW_RECT,
      RcFloatWord.literal(0f),
      RcFloatWord.literal(0f),
      RcFloatWord.literal(size),
      RcFloatWord.literal(size),
    )

  private fun width(value: Float) =
    RcWidthModifier(RcDimensionType.EXACT, RcFloatWord.literal(value))

  private fun height(value: Float) =
    RcHeightModifier(RcDimensionType.EXACT, RcFloatWord.literal(value))
}
