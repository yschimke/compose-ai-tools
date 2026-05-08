package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.displayfilter.DisplayFilter
import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayFilterConfigTest {

  @Test
  fun parsesCommaSeparatedIdsInSourceOrder() {
    assertEquals(
      listOf(DisplayFilter.Grayscale, DisplayFilter.Invert, DisplayFilter.DeuteranopiaSimulation),
      DisplayFilterConfig.parseFilters("grayscale,invert,deuteranopia"),
    )
  }

  @Test
  fun trimsWhitespaceAndTolerantOfEmptyTokens() {
    assertEquals(
      listOf(DisplayFilter.Grayscale, DisplayFilter.Invert),
      DisplayFilterConfig.parseFilters("  grayscale , , invert "),
    )
  }

  @Test
  fun emptyOrNullProducesEmptyList() {
    assertEquals(emptyList<DisplayFilter>(), DisplayFilterConfig.parseFilters(null))
    assertEquals(emptyList<DisplayFilter>(), DisplayFilterConfig.parseFilters(""))
    assertEquals(emptyList<DisplayFilter>(), DisplayFilterConfig.parseFilters("   "))
  }

  @Test
  fun unknownFilterIdsAreDroppedSilently() {
    assertEquals(
      listOf(DisplayFilter.Grayscale),
      DisplayFilterConfig.parseFilters("grayscale,bogusfilter,monochrome"),
    )
  }

  @Test
  fun duplicatesAreCollapsed() {
    assertEquals(
      listOf(DisplayFilter.Grayscale, DisplayFilter.Invert),
      DisplayFilterConfig.parseFilters("grayscale,invert,grayscale"),
    )
  }
}
