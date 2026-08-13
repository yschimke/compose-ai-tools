package ee.schimke.composeai.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit coverage for [PreviewRowAddress] — the string-level half of row addressing (issue #3749).
 * The rest (matching a token to a provider value) lives in the renderers, where the values exist.
 */
class PreviewRowAddressTest {

  /** Manifest stand-in: `Screen` and `Screen_Light` are parameterized, `Plain` is not. */
  private val parameterized: (String) -> Boolean = setOf("Screen", "Screen_Light")::contains

  @Test
  fun `splits an index-addressed row off a parameterized base`() {
    assertEquals(
      PreviewRowAddress.Split("Screen", "PARAM_4"),
      PreviewRowAddress.split("Screen_PARAM_4", parameterized),
    )
  }

  @Test
  fun `splits a label-addressed row off a parameterized base`() {
    assertEquals(
      PreviewRowAddress.Split("Screen", "Dark"),
      PreviewRowAddress.split("Screen_Dark", parameterized),
    )
  }

  /**
   * The load-bearing case from the issue: `MyScreenPreview_Light_PARAM_4`. A multi-preview
   * annotation already contributed `_Light`, so both `Screen` and `Screen_Light` are real entries
   * and the LONGEST parameterized prefix has to win — reading `Light_PARAM_4` as a row token of the
   * bare `Screen` would render the wrong variant under the right id.
   */
  @Test
  fun `longest parameterized base wins`() {
    assertEquals(
      PreviewRowAddress.Split("Screen_Light", "PARAM_4"),
      PreviewRowAddress.split("Screen_Light_PARAM_4", parameterized),
    )
  }

  /** A label can itself carry underscores; only the split whose base is parameterized is taken. */
  @Test
  fun `skips prefixes that are not parameterized entries`() {
    assertEquals(
      PreviewRowAddress.Split("Screen", "Long_Title"),
      PreviewRowAddress.split("Screen_Long_Title", parameterized),
    )
  }

  @Test
  fun `no split when nothing in the id names a parameterized preview`() {
    assertNull(PreviewRowAddress.split("Plain_Dark", parameterized))
    assertNull(PreviewRowAddress.split("Unrelated", parameterized))
  }

  /** A trailing `_` leaves an empty row token, which addresses nothing. */
  @Test
  fun `no split on an empty row token`() {
    assertNull(PreviewRowAddress.split("Screen_", parameterized))
  }

  /** A leading `_` would leave an empty base; `cut > 0` is what rules it out. */
  @Test
  fun `no split on an empty base`() {
    assertNull(PreviewRowAddress.split("_Screen", { it.isEmpty() }))
  }

  @Test
  fun `index tokens parse, labels do not`() {
    assertEquals(0, PreviewRowAddress.indexOf("PARAM_0"))
    assertEquals(12, PreviewRowAddress.indexOf("PARAM_12"))
    assertNull(PreviewRowAddress.indexOf("Dark"))
    assertNull(PreviewRowAddress.indexOf("PARAM_"))
    assertNull(PreviewRowAddress.indexOf("PARAM_x"))
    assertNull(PreviewRowAddress.indexOf("PARAM_-1"))
  }

  @Test
  fun `rowId is the fan-out filename stem`() {
    assertEquals("Screen_Dark", PreviewRowAddress.rowId("Screen", "Dark"))
  }
}
