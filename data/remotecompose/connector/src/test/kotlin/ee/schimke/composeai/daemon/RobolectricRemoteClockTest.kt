package ee.schimke.composeai.daemon

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class RobolectricRemoteClockTest {
  @Test
  fun `elapsed time starts at zero and follows shadow uptime`() {
    var uptimeMillis = 4_200L
    val clock =
      RobolectricRemoteClock(
        startUptimeMillis = uptimeMillis,
        uptimeMillis = { uptimeMillis },
        clockZoneId = ZoneId.of("UTC"),
      )

    assertEquals(0L, clock.millis())
    assertEquals(0L, clock.nanoTime())

    uptimeMillis += 100L

    assertEquals(100L, clock.millis())
    assertEquals(100_000_000L, clock.nanoTime())
    assertEquals("UTC", clock.zoneId)
    assertNotNull(clock.snapshot(null))
  }

  @Test
  fun `elapsed time never moves backwards`() {
    var uptimeMillis = 99L
    val clock =
      RobolectricRemoteClock(
        startUptimeMillis = 100L,
        uptimeMillis = { uptimeMillis },
      )

    assertEquals(0L, clock.millis())
    assertEquals(0L, clock.nanoTime())
  }
}
