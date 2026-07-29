package ee.schimke.composeai.data.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins [PreviewBackground]'s precedence and, in particular, the night case — the whole point of the
 * class is that `showBackground = true` under `UI_MODE_NIGHT_YES` is NOT white.
 */
class PreviewBackgroundTest {

  private val nightYes = 0x20
  private val nightNo = 0x10

  @Test
  fun `showBackground under night uiMode is the dark backing, not white`() {
    assertEquals(
      PreviewBackground.NIGHT_ARGB,
      PreviewBackground.resolveArgbForUiMode(
        showBackground = true,
        backgroundColor = 0L,
        uiMode = nightYes,
      ),
    )
  }

  @Test
  fun `showBackground under day or unspecified uiMode stays white`() {
    assertEquals(
      PreviewBackground.DAY_ARGB,
      PreviewBackground.resolveArgbForUiMode(
        showBackground = true,
        backgroundColor = 0L,
        uiMode = nightNo,
      ),
    )
    assertEquals(
      PreviewBackground.DAY_ARGB,
      PreviewBackground.resolveArgbForUiMode(
        showBackground = true,
        backgroundColor = 0L,
        uiMode = 0,
      ),
    )
  }

  @Test
  fun `an explicit backgroundColor wins over the uiMode default`() {
    val magenta = 0xFFFF00FFL
    assertEquals(
      magenta.toInt(),
      PreviewBackground.resolveArgbForUiMode(
        showBackground = true,
        backgroundColor = magenta,
        uiMode = nightYes,
      ),
    )
    assertEquals(
      magenta.toInt(),
      PreviewBackground.resolveArgbForUiMode(
        showBackground = false,
        backgroundColor = magenta,
        uiMode = nightNo,
      ),
    )
  }

  @Test
  fun `clearBackground beats everything`() {
    assertEquals(
      PreviewBackground.TRANSPARENT_ARGB,
      PreviewBackground.resolveArgbForUiMode(
        showBackground = true,
        backgroundColor = 0xFFFF00FFL,
        uiMode = nightYes,
        clearBackground = true,
      ),
    )
  }

  @Test
  fun `no background and no colour is transparent in both modes`() {
    assertEquals(
      PreviewBackground.TRANSPARENT_ARGB,
      PreviewBackground.resolveArgbForUiMode(
        showBackground = false,
        backgroundColor = 0L,
        uiMode = nightYes,
      ),
    )
    assertEquals(
      PreviewBackground.TRANSPARENT_ARGB,
      PreviewBackground.resolveArgbForUiMode(
        showBackground = false,
        backgroundColor = 0L,
        uiMode = nightNo,
      ),
    )
  }

  @Test
  fun `isNight reads only the night bits`() {
    assertTrue(PreviewBackground.isNight(nightYes))
    // UI_MODE_TYPE_TELEVISION (0x04) or'd with the night bit is still night.
    assertTrue(PreviewBackground.isNight(nightYes or 0x04))
    assertFalse(PreviewBackground.isNight(nightNo))
    assertFalse(PreviewBackground.isNight(0))
    // Night-undefined with a type set is not night.
    assertFalse(PreviewBackground.isNight(0x04))
  }
}
