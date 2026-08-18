package ee.schimke.composeai.rcplayer.compose

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import ee.schimke.composeai.rcplayer.protocol.RcConditionalOperations
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDraw4
import ee.schimke.composeai.rcplayer.protocol.RcFloatWord
import ee.schimke.composeai.rcplayer.protocol.RcHeader
import ee.schimke.composeai.rcplayer.protocol.RcLoopOperation
import ee.schimke.composeai.rcplayer.protocol.RcNoArg
import ee.schimke.composeai.rcplayer.protocol.RcOpcodes
import ee.schimke.composeai.rcplayer.protocol.RcPaintData
import ee.schimke.composeai.rcplayer.protocol.RcTransform2
import ee.schimke.composeai.rcplayer.protocol.RcVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import org.jetbrains.skia.Bitmap

class RcControlFlowRenderTest {
  @Test
  fun loopAndConditionalContainersExecuteTheirPaintChildren() {
    val red = 0xffff0000.toInt()
    val blue = 0xff0000ff.toInt()
    val green = 0xff00ff00.toInt()
    val end = RcNoArg(RcOpcodes.CONTAINER_END)
    val document =
      RcDocument(
        RcHeader(RcVersion(1, 0, 0), legacyWidth = 40, legacyHeight = 10, modern = false),
        listOf(
          RcPaintData(listOf(4, red)),
          RcLoopOperation(
            20,
            RcFloatWord.literal(0f),
            RcFloatWord.literal(10f),
            RcFloatWord.literal(30f),
          ),
          RcNoArg(RcOpcodes.MATRIX_SAVE),
          RcTransform2(
            RcOpcodes.MATRIX_TRANSLATE,
            RcFloatWord(0x7fc00000 or 20),
            RcFloatWord.literal(0f),
          ),
          rect(0f, 0f, 5f, 10f),
          RcNoArg(RcOpcodes.MATRIX_RESTORE),
          end,
          RcConditionalOperations(
            RcConditionalOperations.EQUAL,
            RcFloatWord.literal(1f),
            RcFloatWord.literal(1f),
          ),
          RcPaintData(listOf(4, blue)),
          rect(30f, 0f, 35f, 10f),
          end,
          RcConditionalOperations(
            RcConditionalOperations.EQUAL,
            RcFloatWord.literal(1f),
            RcFloatWord.literal(2f),
          ),
          RcPaintData(listOf(4, green)),
          rect(35f, 0f, 40f, 10f),
          end,
        ),
      )
    val scene =
      ImageComposeScene(width = 40, height = 10, density = Density(1f)) {
        RcComposePlayer(document)
      }
    try {
      val image = scene.render()
      val bitmap = Bitmap().apply { allocN32Pixels(40, 10) }
      check(image.readPixels(bitmap))

      assertEquals(red, bitmap.getColor(2, 5))
      assertEquals(red, bitmap.getColor(12, 5))
      assertEquals(red, bitmap.getColor(22, 5))
      assertEquals(0, bitmap.getColor(7, 5))
      assertEquals(blue, bitmap.getColor(32, 5))
      assertEquals(0, bitmap.getColor(37, 5))
    } finally {
      scene.close()
    }
  }

  private fun rect(left: Float, top: Float, right: Float, bottom: Float) =
    RcDraw4(
      RcOpcodes.DRAW_RECT,
      RcFloatWord.literal(left),
      RcFloatWord.literal(top),
      RcFloatWord.literal(right),
      RcFloatWord.literal(bottom),
    )
}
