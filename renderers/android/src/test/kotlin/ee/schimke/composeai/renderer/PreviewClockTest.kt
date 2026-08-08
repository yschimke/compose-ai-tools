package ee.schimke.composeai.renderer

import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [PreviewClock]'s resolution of `composeai.render.fixedTime` (issue #3239). Pure JVM — the
 * shadow half is exercised by [WearTimeTextClockTest], which is where the pin's *effect* on a render
 * is worth asserting.
 */
class PreviewClockTest {

  private val utc = ZoneOffset.UTC

  @After
  fun restoreGlobals() {
    System.clearProperty(PreviewClock.PROPERTY)
    PreviewClock.clearCache()
  }

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

  /**
   * `off` has to hand back the *host* clock, not a different fixed instant. This is the property
   * [ShadowWearTimeSource] relies on, and the reason the fix shadows the read instead of moving
   * Robolectric's `SystemClock` — the instrumentation is unconditional, so only a value-returning
   * seam can restore host-clock semantics.
   */
  @Test
  fun `off makes currentTimeMillis follow the host clock`() {
    System.setProperty(PreviewClock.PROPERTY, "off")
    PreviewClock.clearCache()

    val before = System.currentTimeMillis()
    val reported = PreviewClock.currentTimeMillis()

    assertNull(PreviewClock.pinnedTimeMillis())
    assertTrue("expected the host clock (~$before), got $reported", reported >= before)
    assertTrue(reported - before < 60_000)
  }

  @Test
  fun `currentTimeMillis reports the configured instant`() {
    System.setProperty(PreviewClock.PROPERTY, "1700000000000")
    PreviewClock.clearCache()

    assertEquals(1_700_000_000_000L, PreviewClock.currentTimeMillis())
  }

  /**
   * The memo is keyed by the raw property value, not merely populated once — a daemon JVM outlives
   * many renders and a launcher may change the property between them.
   */
  @Test
  fun `changing the property re-resolves rather than serving a stale instant`() {
    System.setProperty(PreviewClock.PROPERTY, "1700000000000")
    assertEquals(1_700_000_000_000L, PreviewClock.currentTimeMillis())

    System.setProperty(PreviewClock.PROPERTY, "1800000000000")

    assertEquals(1_800_000_000_000L, PreviewClock.currentTimeMillis())
  }

  /**
   * Robolectric's paused clock refuses to move backwards, which is why the fix does not move it: a
   * pre-1970 instant is a perfectly ordinary value here and resolves as configured rather than
   * being silently dropped.
   */
  @Test
  fun `an instant before the epoch resolves rather than being silently ignored`() {
    val millis = PreviewClock.resolve("1969-07-20T20:17:40", utc)!!

    assertTrue("expected a negative epoch, got $millis", millis < 0)
    assertEquals(
      LocalDateTime.of(1969, 7, 20, 20, 17, 40).toInstant(ZoneOffset.UTC).toEpochMilli(),
      millis,
    )
  }
}
