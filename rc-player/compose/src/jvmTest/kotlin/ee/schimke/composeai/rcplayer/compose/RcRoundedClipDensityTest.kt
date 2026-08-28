package ee.schimke.composeai.rcplayer.compose

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import ee.schimke.composeai.rcplayer.protocol.RcBackgroundModifier
import ee.schimke.composeai.rcplayer.protocol.RcCanvasLayout
import ee.schimke.composeai.rcplayer.protocol.RcDimensionType
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcDraw4
import ee.schimke.composeai.rcplayer.protocol.RcFloatWord
import ee.schimke.composeai.rcplayer.protocol.RcHeader
import ee.schimke.composeai.rcplayer.protocol.RcHeaderProperty
import ee.schimke.composeai.rcplayer.protocol.RcHeaderValue
import ee.schimke.composeai.rcplayer.protocol.RcHeightModifier
import ee.schimke.composeai.rcplayer.protocol.RcLayoutContent
import ee.schimke.composeai.rcplayer.protocol.RcNoArg
import ee.schimke.composeai.rcplayer.protocol.RcOpcodes
import ee.schimke.composeai.rcplayer.protocol.RcPaintData
import ee.schimke.composeai.rcplayer.protocol.RcRootLayout
import ee.schimke.composeai.rcplayer.protocol.RcRoundedClipRectModifier
import ee.schimke.composeai.rcplayer.protocol.RcVersion
import ee.schimke.composeai.rcplayer.protocol.RcWidthModifier
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import org.jetbrains.skia.Bitmap

/**
 * A rounded clip radius is in **pixels** whatever the document's density behavior says, so the
 * player renders it at the value on the wire and never multiplies it by the display density
 * (#4712; #4710 established the same for the embedded player, off a different document model).
 *
 * Both tests here exist because the bug was invisible at density 1.0 — the density every other unit
 * test in this module renders at, which is why nothing caught it for as long as it was there.
 */
class RcRoundedClipDensityTest {

  /**
   * The measurement, off a real document rather than by inspection.
   *
   * `AppCardRemote-640x480` declares `DENSITY_BEHAVIOR_DP` at a generation density of 2.0, and its
   * four clip corners are literal `52f` — a 26dp card corner with the density already folded in.
   * `remote-creation-compose` writes the radius through `RemoteDp.toPx()` at capture and
   * remote-core's `RoundedClipRectModifierOperation` never rescales it, so the wire value already
   * scales with density and scaling it again is pure doubling.
   *
   * If a future capture changes what a DP document carries here, this fails first and the
   * pass-through in `RcComposePlayer` has to be re-established rather than assumed.
   */
  @OptIn(ExperimentalEncodingApi::class)
  @Test
  fun theCornerOnTheWireAlreadyCarriesTheGenerationDensity() {
    val bytes =
      checkNotNull(javaClass.getResourceAsStream("/rc-fixtures/$APP_CARD_FIXTURE")) {
          "missing fixture /rc-fixtures/$APP_CARD_FIXTURE"
        }
        .use { Base64.decode(it.readBytes().decodeToString().trim()) }
    val document = RcDocumentCodec.decode(bytes)

    assertEquals(RcHeader.DENSITY_BEHAVIOR_DP, document.header.densityBehavior)
    assertEquals(2f, document.header.density)

    val corners =
      document.operations.filterIsInstance<RcRoundedClipRectModifier>().map {
        listOf(it.topStart, it.topEnd, it.bottomStart, it.bottomEnd).map(RcFloatWord::value)
      }
    assertEquals(listOf(listOf(52f, 52f, 52f, 52f)), corners)
  }

  /**
   * The regression, in the shape that renders it.
   *
   * A 20px corner on an 80×80 box is genuinely smaller than half the box, so `RoundRect` does not
   * normalize it back — which is exactly why cards showed the doubling and every stadium-shaped
   * button beside them did not. Pixel (12, 3) sits inside a 20px corner arc and outside a 40px one,
   * so it reads green at both densities only if the radius is passed through: with the old `*
   * density` it went red at density 2.0, the doubled clip having eaten the corner off the content.
   */
  @Test
  fun aDpDocumentClipsToTheSameRadiusAtEveryDensity() {
    assertEquals(GREEN, cornerPixel(Density(1f)))
    assertEquals(GREEN, cornerPixel(Density(2f)))
  }

  private fun cornerPixel(density: Density): Int {
    val scene =
      ImageComposeScene(width = SIZE, height = SIZE, density = density) {
        RcComposePlayer(clippedCanvas())
      }
    try {
      val bitmap = Bitmap().apply { allocN32Pixels(SIZE, SIZE) }
      check(scene.render().readPixels(bitmap))
      return bitmap.getColor(12, 3)
    } finally {
      scene.close()
    }
  }

  /** A red 80×80 box, clipped to a 20px corner, with a green rect painted inside the clip. */
  private fun clippedCanvas(): RcDocument =
    RcDocument(
      RcHeader(
        RcVersion(1, 0, 0),
        properties =
          listOf(
            RcHeaderProperty(RcHeader.DOC_WIDTH, RcHeaderValue.IntValue(SIZE)),
            RcHeaderProperty(RcHeader.DOC_HEIGHT, RcHeaderValue.IntValue(SIZE)),
            RcHeaderProperty(
              RcHeader.DOC_DENSITY_AT_GENERATION,
              RcHeaderValue.FloatValue(RcFloatWord.literal(2f)),
            ),
            RcHeaderProperty(
              RcHeader.DOC_DENSITY_BEHAVIOR,
              RcHeaderValue.IntValue(RcHeader.DENSITY_BEHAVIOR_DP),
            ),
          ),
      ),
      listOf(
        RcRootLayout(1),
        RcLayoutContent(2),
        RcCanvasLayout(3, 30),
        RcWidthModifier(RcDimensionType.EXACT, RcFloatWord.literal(BOX)),
        RcHeightModifier(RcDimensionType.EXACT, RcFloatWord.literal(BOX)),
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
          RcFloatWord.literal(CORNER),
          RcFloatWord.literal(CORNER),
          RcFloatWord.literal(CORNER),
          RcFloatWord.literal(CORNER),
        ),
        RcNoArg(RcOpcodes.CANVAS_OPERATIONS),
        RcPaintData(listOf(4, GREEN)),
        RcDraw4(
          RcOpcodes.DRAW_RECT,
          RcFloatWord.literal(0f),
          RcFloatWord.literal(0f),
          RcFloatWord.literal(BOX),
          RcFloatWord.literal(BOX),
        ),
        RcNoArg(RcOpcodes.CONTAINER_END),
        RcNoArg(RcOpcodes.CONTAINER_END),
        RcNoArg(RcOpcodes.CONTAINER_END),
        RcNoArg(RcOpcodes.CONTAINER_END),
      ),
    )

  private companion object {
    const val APP_CARD_FIXTURE = "AppCardRemote-640x480.rc.b64"
    const val SIZE = 100
    const val BOX = 80f
    const val CORNER = 20f
    val GREEN = 0xff00ff00.toInt()
  }
}
