package ee.schimke.composeai.rcplayer.compose

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import ee.schimke.composeai.rcplayer.protocol.RcBoxLayout
import ee.schimke.composeai.rcplayer.protocol.RcCoreText
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcFloatWord
import ee.schimke.composeai.rcplayer.protocol.RcHeader
import ee.schimke.composeai.rcplayer.protocol.RcLayoutContent
import ee.schimke.composeai.rcplayer.protocol.RcNoArg
import ee.schimke.composeai.rcplayer.protocol.RcOpcodes
import ee.schimke.composeai.rcplayer.protocol.RcRootLayout
import ee.schimke.composeai.rcplayer.protocol.RcTextData
import ee.schimke.composeai.rcplayer.protocol.RcTextStyleProperty
import ee.schimke.composeai.rcplayer.protocol.RcVersion
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.junit.Assume.assumeTrue

/**
 * Font-variation axes, end to end: a document naming a host family *and* a `wdth` value must render
 * in that instance of the face, not in the family's default one.
 *
 * Width is the axis the test is built on for the same reason the catalog's specimen is: `wght` can
 * be faked (a text stack that drops the axis still synthesises a bold and lands near the right
 * thickness), while nothing in a text API asks for a narrower face. So a `wdth` 25 run that draws
 * the same ink as a `wdth` 151 run means the axes never reached the font engine — there is no other
 * explanation, and no tolerance to argue about.
 *
 * The face is the repo's vendored Roboto Flex (the wasm catalog's own default), used here as raw
 * bytes exactly as the browser lane's host manifest supplies it.
 */
class RcFontAxisRenderTest {

  @Test
  fun `a wdth axis changes the drawn face rather than being dropped`() {
    val face = robotoFlex()
    assumeTrue("vendored Roboto Flex not found at ${VARIABLE_FACE_PATH}", face != null)
    val fonts = mapOf("roboto flex" to RcFontFaces(RcFontFace("RobotoFlex.ttf", face!!)))

    val narrow = inkWidth(document(wdth = 25f), fonts)
    val wide = inkWidth(document(wdth = 151f), fonts)

    assertTrue(narrow > 0, "the wdth 25 line drew nothing at all")
    assertTrue(wide > 0, "the wdth 151 line drew nothing at all")
    assertTrue(
      wide > narrow,
      "wdth 151 must set wider than wdth 25 — same ink means the axes were dropped " +
        "(narrow=$narrow, wide=$wide)",
    )
  }

  /**
   * One text run naming `google:Roboto Flex` at [wdth], shaped the way a captured `RemoteText`
   * carries it: family as a text id (property 8), axis tags as text ids (property 20), axis values
   * as floats (property 21).
   */
  private fun document(wdth: Float): RcDocument =
    RcDocument(
      RcHeader(RcVersion(1, 0, 0), legacyWidth = WIDTH, legacyHeight = HEIGHT, modern = false),
      listOf(
        RcRootLayout(-2),
        RcBoxLayout(-3, -1, 2, 2),
        RcLayoutContent(-4),
        RcTextData(42, "HHHHHHHH"),
        RcTextData(43, "google:Roboto Flex"),
        RcTextData(44, "wdth"),
        RcCoreText(
          textId = 42,
          properties =
            listOf(
              RcTextStyleProperty.IntValue(3, 0xff000000.toInt()),
              RcTextStyleProperty.FloatValue(5, RcFloatWord.literal(40f)),
              RcTextStyleProperty.IntValue(8, 43),
              RcTextStyleProperty.IntArrayValue(20, listOf(44)),
              RcTextStyleProperty.FloatArrayValue(21, listOf(RcFloatWord.literal(wdth))),
            ),
        ),
        RcLayoutContent(-5),
      ) + List(5) { RcNoArg(RcOpcodes.CONTAINER_END) },
    )

  /** Columns containing any ink — the run's set width, which is what an axis instance changes. */
  private fun inkWidth(document: RcDocument, fonts: Map<String, RcFontFaces>): Int {
    val scene =
      ImageComposeScene(width = WIDTH, height = HEIGHT, density = Density(1f)) {
        RcComposePlayer(document, typefaces = RcBundledTypefaceLoader(fonts))
      }
    try {
      val bitmap = Bitmap().apply { allocN32Pixels(WIDTH, HEIGHT) }
      check(scene.render(0L).readPixels(bitmap))
      return (0 until WIDTH).count { x -> (0 until HEIGHT).any { y -> bitmap.getColor(x, y) != 0 } }
    } finally {
      scene.close()
    }
  }

  private fun robotoFlex(): ByteArray? =
    File(VARIABLE_FACE_PATH).takeIf { it.isFile }?.readBytes()?.takeIf { it.isNotEmpty() }

  private companion object {
    const val WIDTH = 600
    const val HEIGHT = 80

    /**
     * The wasm catalog's vendored variable face, read from the repo rather than copied: the browser
     * lane serves this exact file, so the test exercises the same bytes the lane does. Relative to
     * this module's directory, which is a Gradle `Test` task's working directory.
     */
    const val VARIABLE_FACE_PATH =
      "../../samples/cmp-wasm-catalog/src/wasmJsMain/resources/fonts/RobotoFlex.ttf"
  }
}
