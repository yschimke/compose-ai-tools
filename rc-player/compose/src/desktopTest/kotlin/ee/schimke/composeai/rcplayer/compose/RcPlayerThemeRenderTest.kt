package ee.schimke.composeai.rcplayer.compose

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import ee.schimke.composeai.rcplayer.protocol.RcAndroidSystemColors
import ee.schimke.composeai.rcplayer.protocol.RcBackgroundModifier
import ee.schimke.composeai.rcplayer.protocol.RcBoxLayout
import ee.schimke.composeai.rcplayer.protocol.RcColorTheme
import ee.schimke.composeai.rcplayer.protocol.RcDimensionType
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcFloatWord
import ee.schimke.composeai.rcplayer.protocol.RcHeader
import ee.schimke.composeai.rcplayer.protocol.RcHeightModifier
import ee.schimke.composeai.rcplayer.protocol.RcLayoutContent
import ee.schimke.composeai.rcplayer.protocol.RcNoArg
import ee.schimke.composeai.rcplayer.protocol.RcOpcodes
import ee.schimke.composeai.rcplayer.protocol.RcRootLayout
import ee.schimke.composeai.rcplayer.protocol.RcTextData
import ee.schimke.composeai.rcplayer.protocol.RcTheme
import ee.schimke.composeai.rcplayer.protocol.RcVersion
import ee.schimke.composeai.rcplayer.protocol.RcWidthModifier
import kotlin.test.Test
import kotlin.test.assertEquals
import org.jetbrains.skia.Bitmap

/**
 * The theme and system-colour parameters are the two the host actually names, and both used to be
 * raw wire values. These render the `ColorTheme` path — the one place both meet — through the typed
 * surface, because the failure mode for either is a *plausible* colour rather than an error.
 */
class RcPlayerThemeRenderTest {

  private val lightFallback = 0xFF102040.toInt()
  private val darkFallback = 0xFF402010.toInt()
  private val hostLight = 0xFF3A7BD5.toInt()

  /** `system_primary_light` / `system_primary_dark`, the pair a Material 3 primary records. */
  private val lightIndex = 153
  private val darkIndex = 150

  @Test
  fun lightAndDarkSelectTheirOwnHalfOfTheRecordedPair() {
    assertEquals(lightFallback, render(RcPlayerTheme.Light))
    assertEquals(darkFallback, render(RcPlayerTheme.Dark))
  }

  /**
   * The three-case collapse. `RcPlayerTheme` has no `Unspecified`, because the wire's `SYSTEM` and
   * `UNSPECIFIED` were always answered the same way — and `RcComposeViewController` defaulted to
   * `UNSPECIFIED` while the Compose entry point defaulted to `SYSTEM`, so the collapse silently
   * changes what one of those two defaults spells.
   *
   * Asserted at the resolver rather than by pixel-comparing two renders, so the test does not
   * depend on what the machine running it has its appearance set to: whatever `isSystemInDarkTheme`
   * says here, all three spellings have to agree with each other.
   */
  @Test
  fun systemAndTheRetiredUnspecifiedConstantResolveIdentically() {
    var fromSystemConstant = Int.MIN_VALUE
    var fromUnspecifiedConstant = Int.MIN_VALUE
    var fromEnum = Int.MIN_VALUE
    val scene =
      ImageComposeScene(width = 1, height = 1, density = Density(1f)) {
        fromSystemConstant = rcResolveSystemTheme(RcTheme.SYSTEM)
        fromUnspecifiedConstant = rcResolveSystemTheme(RcTheme.UNSPECIFIED)
        fromEnum = RcPlayerTheme.System.resolve()
      }
    try {
      scene.render(0L)
    } finally {
      scene.close()
    }
    assertEquals(fromSystemConstant, fromUnspecifiedConstant)
    assertEquals(fromSystemConstant, fromEnum)
    // And it is a *mode*, never a question passed through — that is the whole reason resolution
    // happens at the entry point rather than in the state layer.
    assertEquals(true, fromEnum == RcTheme.LIGHT || fromEnum == RcTheme.DARK)
  }

  /**
   * `systemColors` returns a [Color] now, not a packed ARGB `Int`. A shifted-channel conversion
   * would still produce *a* colour, so the sentinel's three colour channels are distinct and all
   * non-zero: a red/blue swap or a one-byte shift fails here rather than rendering a slightly wrong
   * palette forever. Alpha stays opaque because the scene composites onto a transparent ground, so
   * a translucent sentinel would not read back as itself.
   */
  @Test
  fun aHostColorReachesTheDocumentUnchanged() {
    val rendered =
      render(RcPlayerTheme.Light) { name ->
        if (name == RcAndroidSystemColors.nameAt(lightIndex)) Color(hostLight) else null
      }
    assertEquals(hostLight, rendered)
  }

  /** A host that resolves nothing keeps the document's recorded fallback, as it always did. */
  @Test
  fun anUnresolvedNameFallsBackToTheDocument() {
    assertEquals(lightFallback, render(RcPlayerTheme.Light) { null })
  }

  private fun render(
    theme: RcPlayerTheme,
    systemColors: (String) -> Color? = { null },
  ): Int {
    val groupId = 43
    val colorId = 7
    val document =
      RcDocument(
        RcHeader(RcVersion(1, 0, 0), legacyWidth = 8, legacyHeight = 8, modern = false),
        listOf(
          RcRootLayout(1),
          RcTextData(groupId, RcAndroidSystemColors.GROUP),
          RcColorTheme(
            outId = colorId,
            colorGroupId = groupId,
            lightModeIndex = lightIndex,
            darkModeIndex = darkIndex,
            lightModeFallback = lightFallback,
            darkModeFallback = darkFallback,
          ),
          RcBoxLayout(3, 30, 1, 4),
          RcWidthModifier(RcDimensionType.EXACT, RcFloatWord.literal(8f)),
          RcHeightModifier(RcDimensionType.EXACT, RcFloatWord.literal(8f)),
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
      ImageComposeScene(width = 8, height = 8, density = Density(1f)) {
        RcComposePlayer(document, theme = theme, systemColors = systemColors)
      }
    return try {
      val bitmap = Bitmap().apply { allocN32Pixels(8, 8) }
      check(scene.render(0L).readPixels(bitmap))
      bitmap.getColor(4, 4)
    } finally {
      scene.close()
    }
  }
}
