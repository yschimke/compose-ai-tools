package ee.schimke.composeai.rcplayer.runtime

import ee.schimke.composeai.rcplayer.protocol.RcAndroidSystemColors
import ee.schimke.composeai.rcplayer.protocol.RcColorTheme
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcHeader
import ee.schimke.composeai.rcplayer.protocol.RcOperation
import ee.schimke.composeai.rcplayer.protocol.RcTextData
import ee.schimke.composeai.rcplayer.protocol.RcTheme
import ee.schimke.composeai.rcplayer.protocol.RcVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A `ColorTheme` records a resource *index* per mode as well as a literal fallback, and the index
 * is the half a player has to ask its host about. These pin both halves: that the index is read at
 * all, and that the mode chosen to read it for is the one the host asked for.
 *
 * The sentinels are colours nothing else in the document could produce, so "resolved the index" is
 * distinguishable from "fell back" — which matters because falling back is *invisible*: the
 * fallback is exactly what a host with no palette would have drawn, so a document that ignores its
 * indices renders plausibly and silently stops following the system.
 */
class RcColorThemeResolutionTest {

  private val lightSentinel = 0xFF00FF00.toInt()
  private val darkSentinel = 0xFF0000FF.toInt()
  private val lightFallback = 0xFF65558F.toInt()
  private val darkFallback = 0xFFD0BCFF.toInt()

  /** `system_primary_light` / `system_primary_dark`, the pair a Material 3 primary records. */
  private val lightIndex = 153
  private val darkIndex = 150

  private val lookup: (String) -> Int? = { name ->
    when (name) {
      "system_primary_light" -> lightSentinel
      "system_primary_dark" -> darkSentinel
      else -> null
    }
  }

  @Test
  fun theTableNamesTheResourcesADocumentsIndicesRefersTo() {
    // Order is a wire contract: a document records an index, so a shifted table recolours every
    // themed document that has already been captured rather than failing loudly.
    assertEquals(196, RcAndroidSystemColors.NAMES.size)
    assertEquals("system_primary_light", RcAndroidSystemColors.nameAt(153))
    assertEquals("system_primary_dark", RcAndroidSystemColors.nameAt(150))
    assertEquals("system_surface_container_high_light", RcAndroidSystemColors.nameAt(164))
    assertEquals("system_on_surface_dark", RcAndroidSystemColors.nameAt(122))
    // The two entries the creation-side constants get wrong, pinned so a future "simplify this by
    // reflecting over Rc.AndroidColors" reintroduces the bug loudly instead of silently.
    assertEquals("system_accent2_1000", RcAndroidSystemColors.nameAt(30))
    assertEquals("system_accent2_200", RcAndroidSystemColors.nameAt(31))
    assertEquals("system_error_10", RcAndroidSystemColors.nameAt(62))
    assertEquals("system_neutral1_0", RcAndroidSystemColors.nameAt(78))
    // "No resource for this mode" and "written against a newer table" must both be quiet.
    assertNull(RcAndroidSystemColors.nameAt(-1))
    assertNull(RcAndroidSystemColors.nameAt(196))
  }

  @Test
  fun eachModeResolvesItsOwnIndexThroughTheHost() {
    assertEquals(lightSentinel, resolve(RcTheme.LIGHT, lookup))
    assertEquals(darkSentinel, resolve(RcTheme.DARK, lookup))
  }

  @Test
  fun anUnresolvableNameKeepsTheCapturedFallback() {
    // The ordinary case off Android, and below API 34 on it. Not an error — the document must still
    // render, in the colours it captured for exactly this situation.
    assertEquals(lightFallback, resolve(RcTheme.LIGHT, { null }))
    assertEquals(darkFallback, resolve(RcTheme.DARK, { null }))
  }

  @Test
  fun anUnknownColorGroupIsLeftToItsFallbacks() {
    // An index means nothing without knowing whose table it indexes. Resolving another vendor's
    // group against the Android table would invent a colour rather than miss one.
    assertEquals(lightFallback, resolve(RcTheme.LIGHT, lookup, group = "some-other-vendor"))
  }

  @Test
  fun anUnresolvedThemeIsNotSilentlyDark() {
    // `SYSTEM` / `UNSPECIFIED` are questions for the host, and this layer cannot answer one, so the
    // Compose player resolves them before they arrive (`rcResolveSystemTheme`). This test states
    // what the state layer does with one anyway — it takes the non-light branch — so that the
    // reason resolution has to happen upstream stays written down: an unanswered question here is
    // not a neutral default, it is a dark document.
    assertEquals(darkSentinel, resolve(RcTheme.UNSPECIFIED, lookup))
    assertEquals(darkSentinel, resolve(RcTheme.SYSTEM, lookup))
  }

  private fun resolve(
    theme: Int,
    systemColorLookup: (String) -> Int?,
    group: String = RcAndroidSystemColors.GROUP,
  ): Int {
    val groupId = 43
    val colorTheme =
      RcColorTheme(
        outId = 7,
        colorGroupId = groupId,
        lightModeIndex = lightIndex,
        darkModeIndex = darkIndex,
        lightModeFallback = lightFallback,
        darkModeFallback = darkFallback,
      )
    val state = state(RcTextData(groupId, group), colorTheme, lookup = systemColorLookup)
    state.applyColorTheme(colorTheme, theme)
    return state.color(colorTheme.outId)
  }

  private fun state(vararg ops: RcOperation, lookup: (String) -> Int?) =
    RcPlayerState(
      RcDocument(RcHeader(RcVersion(1, 0, 0)), ops.toList()),
      systemColorLookup = lookup,
    )
}
