package ee.schimke.composeai.rcplayer.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The pairing rule for a `CoreText` style's font-variation axes: property 20 carries the axis tags
 * (as text ids the caller has already resolved) and property 21 the values, positionally matched. A
 * document can leave either side short or unresolvable, so the rule is that an axis counts only
 * when both halves are present — anything else is dropped rather than paired with a neighbour's
 * value, which would silently apply the wrong instance.
 */
class RcFontVariationSettingsTest {
  @Test
  fun tagsAndValuesPairPositionally() {
    val settings =
      fontVariationSettings(axisTags = listOf("wght", "wdth"), axisValues = listOf(700f, 25f))

    assertEquals(listOf("wght" to 700f, "wdth" to 25f), settings.pairs())
  }

  @Test
  fun aStyleWithNoAxesResolvesToNoSettings() {
    assertNull(fontVariationSettings(axisTags = emptyList(), axisValues = emptyList()))
    // Values with no tags name nothing: there is no axis to apply them to.
    assertNull(fontVariationSettings(axisTags = emptyList(), axisValues = listOf(700f)))
  }

  @Test
  fun anUnresolvableTagDropsItsAxisAndKeepsTheRest() {
    // A tag id the document's text table has no entry for arrives as null.
    val settings =
      fontVariationSettings(axisTags = listOf(null, "wdth"), axisValues = listOf(700f, 151f))

    assertEquals(listOf("wdth" to 151f), settings.pairs())
  }

  @Test
  fun a_tag_without_a_value_is_dropped_rather_than_taking_the_next_one() {
    val settings =
      fontVariationSettings(axisTags = listOf("wght", "wdth"), axisValues = listOf(700f))

    assertEquals(listOf("wght" to 700f), settings.pairs())
  }

  private fun androidx.compose.ui.text.font.FontVariation.Settings?.pairs():
    List<Pair<String, Float>> =
    this?.settings.orEmpty().map { it.axisName to it.toVariationValue(null) }
}
