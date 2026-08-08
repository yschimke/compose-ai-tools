package ee.schimke.composeai.renderer

import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [PreviewClock]'s resolution of `composeai.render.fixedTime` (issue #3239). Pure JVM — the
 * `SystemClock` half is exercised by [WearTimeTextClockTest], which is where the pin's *effect* on a
 * render is worth asserting.
 */
class PreviewClockTest {

  private val utc = ZoneOffset.UTC

  @Test
  fun `unset pins ten past ten on the fixed date`() {
    val millis = PreviewClock.resolve(null, utc)!!

    assertEquals(
      LocalDateTime.of(PreviewClock.FIXED_DATE, PreviewClock.DEFAULT_TIME)
        .toInstant(ZoneOffset.UTC)
        .toEpochMilli(),
      millis,
    )
  }

  @Test
  fun `blank is treated as unset rather than as a parse failure`() {
    assertEquals(PreviewClock.resolve(null, utc)!!, PreviewClock.resolve("   ", utc)!!)
  }

  @Test
  fun `off switches the pin off entirely`() {
    for (value in listOf("off", "OFF", "false", "none", "disabled", " off ")) {
      assertNull("'$value' should disable the pin", PreviewClock.resolve(value, utc))
    }
  }

  @Test
  fun `a time of day lands on the fixed date`() {
    val millis = PreviewClock.resolve("09:41", utc)!!

    assertEquals(
      LocalDateTime.of(PreviewClock.FIXED_DATE, LocalTime.of(9, 41))
        .toInstant(ZoneOffset.UTC)
        .toEpochMilli(),
      millis,
    )
  }

  @Test
  fun `an iso local date-time pins the date too`() {
    val millis = PreviewClock.resolve("2019-03-14T15:09:26", utc)!!

    assertEquals(
      LocalDateTime.of(2019, 3, 14, 15, 9, 26).toInstant(ZoneOffset.UTC).toEpochMilli(),
      millis,
    )
  }

  @Test
  fun `epoch millis pass through verbatim`() {
    assertEquals(1_700_000_000_000L, PreviewClock.resolve("1700000000000", utc)!!)
  }

  /**
   * The whole point of resolving against the default zone: the rendered *string* is what has to be
   * reproducible, and `TimeText` formats through `Calendar.getInstance()`. Two zones therefore have
   * to produce two different instants — pinning one instant globally would paint a different time
   * in CI than on a laptop.
   */
  @Test
  fun `the same time of day resolves per zone so the rendered string matches`() {
    val inTokyo = PreviewClock.resolve("10:10", ZoneId.of("Asia/Tokyo"))!!
    val inNewYork = PreviewClock.resolve("10:10", ZoneId.of("America/New_York"))!!

    assertEquals(14 * 60 * 60 * 1000L, inNewYork - inTokyo)
  }

  @Test
  fun `a value that is not a time fails loudly instead of falling back to the wall clock`() {
    val failure = assertThrows(IllegalArgumentException::class.java) {
      PreviewClock.resolve("half past ten", utc)
    }

    assertTrue(failure.message!!.contains(PreviewClock.PROPERTY))
    assertTrue(failure.message!!.contains("half past ten"))
  }
}
