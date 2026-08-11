package ee.schimke.composeai.rcplayer.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RcTextMeasurementTest {
  @Test
  fun selectsEveryAndroidXTextAttributeMode() {
    val expected = listOf(30f, 12f, -2f, 28f, -4f, 8f, 7f)

    expected.forEachIndexed { type, value ->
      assertEquals(value, selectTextMeasurement(type, -2f, -4f, 28f, 8f, 7))
    }
  }

  @Test
  fun textMeasureDoesNotImplementTextLength() {
    assertNull(selectTextMeasurement(6, 0f, 0f, 0f, 0f, 7, supportsLength = false))
  }

  @Test
  fun unknownMeasurementModeLeavesDestinationUntouched() {
    assertNull(selectTextMeasurement(7, 0f, 0f, 0f, 0f, 0, supportsLength = true))
  }
}
