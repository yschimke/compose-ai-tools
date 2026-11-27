package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TimeTickTest {

  @Test
  public void theFirstCycleOnlyNotesTheMinute() {
    assertFalse(new TimeTick().due(11 * 3_600_000L + 2 * 60_000L + 5_000L));
  }

  @Test
  public void oneTickPerMinuteEntered() {
    TimeTick tick = new TimeTick();
    long start = 11 * 3_600_000L + 2 * 60_000L + 59_000L;
    tick.due(start);
    assertFalse(tick.due(start + 500));
    assertTrue(tick.due(start + 1_000));
    assertFalse(tick.due(start + 30_000));
    assertTrue(tick.due(start + 61_000));
  }

  @Test
  public void aClockSetBackTicksToo() {
    TimeTick tick = new TimeTick();
    tick.due(10 * 60_000L);
    assertTrue(tick.due(5 * 60_000L));
  }
}
