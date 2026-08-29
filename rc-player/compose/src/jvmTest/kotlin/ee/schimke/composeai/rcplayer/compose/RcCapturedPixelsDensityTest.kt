package ee.schimke.composeai.rcplayer.compose

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import ee.schimke.composeai.rcplayer.protocol.RcBackgroundModifier
import ee.schimke.composeai.rcplayer.protocol.RcBoxLayout
import ee.schimke.composeai.rcplayer.protocol.RcColumnLayout
import ee.schimke.composeai.rcplayer.protocol.RcDimensionType
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcFloatWord
import ee.schimke.composeai.rcplayer.protocol.RcHeader
import ee.schimke.composeai.rcplayer.protocol.RcHeaderProperty
import ee.schimke.composeai.rcplayer.protocol.RcHeaderValue
import ee.schimke.composeai.rcplayer.protocol.RcHeightModifier
import ee.schimke.composeai.rcplayer.protocol.RcLayoutContent
import ee.schimke.composeai.rcplayer.protocol.RcNoArg
import ee.schimke.composeai.rcplayer.protocol.RcOpcodes
import ee.schimke.composeai.rcplayer.protocol.RcOperation
import ee.schimke.composeai.rcplayer.protocol.RcPaddingModifier
import ee.schimke.composeai.rcplayer.protocol.RcRootLayout
import ee.schimke.composeai.rcplayer.protocol.RcVersion
import ee.schimke.composeai.rcplayer.protocol.RcWidthModifier
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import org.jetbrains.skia.Bitmap

/**
 * Padding is in **pixels** whatever the document's density behavior says, so the player insets by
 * the value on the wire and never multiplies it by the display density (#4749). The CMP twin
 * of #4727, and the same shape as the rounded-clip doubling `RcRoundedClipDensityTest` pins.
 *
 * As there, both tests exist because the bug was invisible at density 1.0 — the density every other
 * unit test in this module renders at, which is why nothing caught it for as long as it was there.
 */
class RcCapturedPixelsDensityTest {

  /**
   * The measurement, off a real document rather than by inspection.
   *
   * `AppCardRemote-640x480` declares `DENSITY_BEHAVIOR_DP` at a generation density of 2.0, and its
   * padding edges are literal `24f` — a 12dp card inset with the density already folded in, beside
   * the `52f` clip corners of the same 26dp card. `remote-creation-compose` writes both through
   * `RemoteDp.toPx()` at capture, so both already scale with density and scaling either again is
   * pure doubling.
   *
   * #4727 read the same edge off the live op at both densities, on `RemoteCompactButton`'s 8dp
   * inset: `8` at density 1.0 and `16` at density 2.0.
   *
   * If a future capture changes what a DP document carries here, this fails first and the
   * pass-through in `RcComposePlayer` has to be re-established rather than assumed.
   */
  @OptIn(ExperimentalEncodingApi::class)
  @Test
  fun theEdgesOnTheWireAlreadyCarryTheGenerationDensity() {
    val bytes =
      checkNotNull(javaClass.getResourceAsStream("/rc-fixtures/$APP_CARD_FIXTURE")) {
          "missing fixture /rc-fixtures/$APP_CARD_FIXTURE"
        }
        .use { Base64.decode(it.readBytes().decodeToString().trim()) }
    val document = RcDocumentCodec.decode(bytes)

    assertEquals(RcHeader.DENSITY_BEHAVIOR_DP, document.header.densityBehavior)
    assertEquals(2f, document.header.density)

    val edges =
      document.operations.filterIsInstance<RcPaddingModifier>().map {
        listOf(it.left, it.top, it.right, it.bottom).map(RcFloatWord::value)
      }
    assertEquals(listOf(listOf(24f, 24f, 24f, 24f)), edges)
  }

  /**
   * The regression, in the shape that renders it.
   *
   * Both boxes FILL, so the only thing setting the white box's edge is the 20px inset — no dp-typed
   * size modifier is in play to move it, which is what makes the correct answer the same physical
   * pixel at every density. With the old `* density` the inset became 40px at density 2.0 and the
   * white box's edge walked inward, taking (21, 50) with it.
   */
  @Test
  fun aDpDocumentInsetsByTheSamePaddingAtEveryDensity() {
    for (density in listOf(1f, 2f)) {
      assertEquals(0, colorAt(19, 50, Density(density)), "outside the inset at density $density")
      assertEquals(WHITE, colorAt(21, 50, Density(density)), "inside the inset at density $density")
      assertEquals(WHITE, colorAt(50, 21, Density(density)), "top edge at density $density")
      assertEquals(0, colorAt(50, 19, Density(density)), "above the top edge at density $density")
    }
  }

  /**
   * The `spacedBy` gap is the same field written the same way, and moves for the same reason.
   *
   * `WatchScreenRemote`'s `RemoteArrangement.spacedBy(8.rdp)` is on the wire as `16` at a
   * generation density of 2.0, and `ButtonGroupRemote`'s 4dp gap as `8` — the doubling #4731
   * recorded as "RemoteButtonGroup's 4dp gap rendering at 8dp".
   *
   * Both children are weighted and the column FILLs, so the gap is the only thing setting their
   * edges — no dp-typed size modifier is in play to move them. Measured, the 100px column lays out
   * as white [0, 40), the 20px gap [40, 60), white [60, 100) at both densities. Doubling the gap to
   * 40 shrinks each child to 30 and empties the rows at y = 39 and y = 60.
   */
  @Test
  fun aDpDocumentGapsByTheSameSpacedByAtEveryDensity() {
    for (density in listOf(1f, 2f)) {
      assertEquals(
        WHITE,
        colorAt(50, 39, Density(density), spacedColumn()),
        "the first weight ends at the gap at density $density",
      )
      assertEquals(
        0,
        colorAt(50, 50, Density(density), spacedColumn()),
        "the gap is empty at density $density",
      )
      assertEquals(
        WHITE,
        colorAt(50, 60, Density(density), spacedColumn()),
        "the second weight begins after a 20px gap at density $density",
      )
    }
  }

  private fun colorAt(
    x: Int,
    y: Int,
    density: Density,
    document: RcDocument = paddedBoxes(),
  ): Int {
    val scene =
      ImageComposeScene(width = SIZE, height = SIZE, density = density) {
        RcComposePlayer(document)
      }
    try {
      val bitmap = Bitmap().apply { allocN32Pixels(SIZE, SIZE) }
      check(scene.render().readPixels(bitmap))
      return bitmap.getColor(x, y)
    } finally {
      scene.close()
    }
  }

  /** A filling box inset by 20px on every side, wrapped around a filling white box. */
  private fun paddedBoxes(): RcDocument =
    RcDocument(
      dpHeader(),
      listOf(
        RcRootLayout(1),
        RcLayoutContent(2),
        RcBoxLayout(3, 30, 1, 4),
        fillWidth(),
        fillHeight(),
        RcPaddingModifier(
          RcFloatWord.literal(INSET),
          RcFloatWord.literal(INSET),
          RcFloatWord.literal(INSET),
          RcFloatWord.literal(INSET),
        ),
        RcLayoutContent(4),
        RcBoxLayout(5, 50, 1, 4),
        fillWidth(),
        fillHeight(),
        whiteBackground(),
        RcLayoutContent(6),
      ) + List(6) { RcNoArg(RcOpcodes.CONTAINER_END) },
    )

  /** A DP-behavior header at a generation density of 2.0 — what every capture used to declare. */
  private fun dpHeader() =
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
    )

  private fun whiteBackground() =
    RcBackgroundModifier(
      flags = 0,
      colorId = 0,
      reserved1 = 0,
      reserved2 = 0,
      red = RcFloatWord.literal(1f),
      green = RcFloatWord.literal(1f),
      blue = RcFloatWord.literal(1f),
      alpha = RcFloatWord.literal(1f),
      shapeType = RcBackgroundModifier.SHAPE_RECTANGLE,
    )

  /** A filling column with a 20px gap, wrapped around two weighted white boxes. */
  private fun spacedColumn(): RcDocument {
    val child = { componentId: Int ->
      listOf<RcOperation>(
        RcBoxLayout(componentId, componentId * 10, 1, 4),
        fillWidth(),
        RcHeightModifier(RcDimensionType.WEIGHT, RcFloatWord.literal(1f)),
        whiteBackground(),
        RcLayoutContent(componentId * 10 + 1),
        RcNoArg(RcOpcodes.CONTAINER_END),
        RcNoArg(RcOpcodes.CONTAINER_END),
      )
    }
    return RcDocument(
      dpHeader(),
      listOf(
        RcRootLayout(1),
        RcLayoutContent(2),
        RcColumnLayout(3, 30, 1, 4, RcFloatWord.literal(INSET)),
        fillWidth(),
        fillHeight(),
        RcLayoutContent(4),
      ) + child(5) + child(6) + List(4) { RcNoArg(RcOpcodes.CONTAINER_END) },
    )
  }

  private fun fillWidth() = RcWidthModifier(RcDimensionType.FILL, RcFloatWord.literal(1f))

  private fun fillHeight() = RcHeightModifier(RcDimensionType.FILL, RcFloatWord.literal(1f))

  private companion object {
    const val APP_CARD_FIXTURE = "AppCardRemote-640x480.rc.b64"
    const val SIZE = 100
    const val INSET = 20f
    val WHITE = 0xffffffff.toInt()
  }
}
