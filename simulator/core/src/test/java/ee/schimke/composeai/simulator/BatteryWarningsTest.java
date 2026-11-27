package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertEquals;

import ee.schimke.composeai.simulator.BatteryWarnings.Broadcast;
import org.junit.Test;

public class BatteryWarningsTest {

  @Test
  public void drainingAcrossTheWarningLevelSendsLowOnce() {
    BatteryWarnings w = new BatteryWarnings();
    assertEquals(Broadcast.LOW, w.update(50, false, 12, false));
    assertEquals(Broadcast.NONE, w.update(12, false, 10, false));
  }

  @Test
  public void recoveringPastTheCloseLevelSendsOkay() {
    BatteryWarnings w = new BatteryWarnings();
    w.update(50, false, 12, false);
    assertEquals(Broadcast.NONE, w.update(12, false, 18, false));
    assertEquals(Broadcast.OKAY, w.update(18, false, 20, false));
    assertEquals(Broadcast.NONE, w.update(20, false, 80, false));
  }

  @Test
  public void pluggingInWhileLowDoesNotSendOkay() {
    BatteryWarnings w = new BatteryWarnings();
    w.update(50, false, 12, false);
    assertEquals(Broadcast.NONE, w.update(12, false, 12, true));
    assertEquals(Broadcast.OKAY, w.update(12, true, 60, true));
  }

  @Test
  public void unpluggingAtALowLevelSendsLow() {
    BatteryWarnings w = new BatteryWarnings();
    assertEquals(Broadcast.NONE, w.update(10, true, 10, true));
    assertEquals(Broadcast.LOW, w.update(10, true, 10, false));
  }

  @Test
  public void okayIsNeverSentWithoutALow() {
    assertEquals(Broadcast.NONE, new BatteryWarnings().update(10, true, 90, true));
  }
}
