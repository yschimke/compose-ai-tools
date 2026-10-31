package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class StillnessTest {

  @Test
  public void anEntryAnimationKeepsTheWaitGoingUntilTheScreenRests() {
    Stillness still = new Stillness(1_000, 400, 3_000);
    // A fade-in: every frame differs from the last.
    for (long t = 1_100; t <= 1_600; t += 100) assertFalse(still.frame(t, true, true));
    // Then it rests: unchanged for 400 ms is still.
    assertFalse(still.frame(1_700, true, false));
    assertFalse(still.frame(1_800, true, false));
    assertFalse(still.frame(1_900, true, false));
    assertTrue(still.frame(2_000, true, false));
    assertTrue(still.still());
    assertEquals(1_000, still.waitedMs(5_000));
  }

  @Test
  public void theFirstFrameCountsAsAChange() {
    Stillness still = new Stillness(0, 300, 3_000);
    assertFalse(still.frame(100, true, false));
    assertFalse(still.frame(200, true, false));
    assertFalse(still.frame(300, true, false));
    assertTrue(still.frame(400, true, false));
    assertEquals(400, still.waitedMs(400));
  }

  @Test
  public void anEmptyScreenIsNeverStill() {
    Stillness still = new Stillness(0, 200, 1_000);
    for (long t = 100; t < 1_000; t += 100) assertFalse(still.frame(t, false, false));
    assertTrue("the timeout ends it", still.frame(1_000, false, false));
    assertFalse(still.still());
  }

  @Test
  public void anEndlessAnimationEndsAtTheTimeout() {
    Stillness still = new Stillness(0, 400, 3_000);
    long t = 100;
    for (; t < 3_000; t += 100) assertFalse(still.frame(t, true, true));
    assertTrue(still.frame(t, true, true));
    assertFalse(still.still());
    assertEquals(3_000, still.waitedMs(9_999));
    // Once over it stays over.
    assertTrue(still.frame(t + 100, true, false));
    assertFalse(still.still());
  }

  @Test
  public void noQuietTimeIsStillOnTheFirstFrame() {
    Stillness still = new Stillness(0, 0, 3_000);
    assertTrue(still.frame(100, true, true));
    assertTrue(still.still());
  }

  @Test
  public void samplesOncePerQuietPeriodSoTwoEqualFramesProveStillness() {
    // Each sample is a full screenshot costing real time, while the virtual clock between samples
    // is free: polling every 100 ms took nine screenshots and four real seconds per restart.
    Stillness still = new Stillness(0, 400, 3_000);
    assertEquals(400, still.pollMs());
    assertFalse(still.frame(400, true, true));
    assertTrue(still.frame(800, true, false));
    assertTrue(still.still());
    assertEquals(800, still.waitedMs(800));
  }

  @Test
  public void aTinyQuietPeriodStillSamplesAtAFloor() {
    assertEquals(Stillness.MIN_POLL_MS, new Stillness(0, 0, 3_000).pollMs());
  }
}
