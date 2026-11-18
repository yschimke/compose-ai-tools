package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.ZonedDateTime;
import org.junit.After;
import org.junit.Test;

/** {@link SimulatorClock}: a face's {@code ZonedDateTime.now()} follows the simulated clock. */
public class SimulatorClockTest {
  @After
  public void clear() {
    SimulatorClock.setOverride(null);
  }

  @Test
  public void aPinnedTimeIsWhatNowReads() {
    // 2026-03-05T10:08:30Z: the date the runtime's screenshot path replaced with the 28th.
    SimulatorClock.setOverride(1772705310000L);
    ZonedDateTime now = SimulatorClock.zonedNow();
    assertEquals(1772705310000L, now.toInstant().toEpochMilli());
    assertEquals(5, now.getDayOfMonth());
    assertEquals(3, now.getMonthValue());
  }

  @Test
  public void withoutAnOverrideItIsTheRealClockWhenNoneIsSimulated() {
    assertNull(SimulatorClock.override());
    long before = System.currentTimeMillis();
    long read = SimulatorClock.zonedNow().toInstant().toEpochMilli();
    assertTrue(read >= before - 1 && read <= System.currentTimeMillis() + 1);
  }

  @Test
  public void anExplicitPinAlwaysRefreshesTheFace() {
    long minute = 1772705340000L; // 10:09:00Z
    assertTrue(SimulatorClock.needsRefresh(null, minute, true));
    // 10:08:59.700 -> 10:09:00.000: under a second, across a minute boundary.
    assertTrue(SimulatorClock.needsRefresh(minute - 300, minute, true));
    assertEquals(false, SimulatorClock.needsRefresh(minute, minute, true));
  }

  @Test
  public void aRunningSimulatedClockRefreshesOncePerSecond() {
    long t = 1772705340000L;
    assertEquals(false, SimulatorClock.needsRefresh(t, t + 400, false));
    assertTrue(SimulatorClock.needsRefresh(t, t + 1000, false));
  }

  @Test
  public void aWallpaperEngineThatAttachesAfterTheFirstRequestIsAwaited() throws Exception {
    // The runtime hands back its interactive instance before the engine reports itself, so the
    // first frame is asked for with no engine: waiting for it (not giving up for good) is what
    // keeps a pushed face on the live frame path rather than the screenshot call.
    java.util.concurrent.atomic.AtomicReference<String> engine =
        new java.util.concurrent.atomic.AtomicReference<>();
    Thread attach =
        new Thread(
            () -> {
              try {
                Thread.sleep(150);
              } catch (InterruptedException e) {
                return;
              }
              engine.set("engine");
            });
    attach.start();
    assertEquals("engine", LiveFrameSource.await(engine::get, 5_000));
    attach.join();
    assertNull(LiveFrameSource.await(() -> null, 100));
  }
}
