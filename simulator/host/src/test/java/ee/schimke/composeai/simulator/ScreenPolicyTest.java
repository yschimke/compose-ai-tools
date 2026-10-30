package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import ee.schimke.composeai.simulator.ScreenPolicy.State;
import ee.schimke.composeai.simulator.ScreenPolicy.Transition;
import org.junit.Test;

public class ScreenPolicyTest {

  @Test
  public void anAmbientAppStaysOnScreenWhenAWatchSleeps() {
    ScreenPolicy p = new ScreenPolicy(true, 0);
    Transition t = p.to(p.sleepTarget(), true, true, 0);
    assertEquals(State.AMBIENT, t.to);
    assertTrue(t.screenOff);
    assertTrue(t.enterAmbient);
    assertFalse(t.stopApp);
    assertTrue(p.appAmbient());
  }

  @Test
  public void anAppWithoutAmbientLeavesTheScreenAndComesBackOnWake() {
    ScreenPolicy p = new ScreenPolicy(true, 0);
    Transition sleep = p.to(State.AMBIENT, true, false, 0);
    assertTrue(sleep.stopApp);
    assertFalse(sleep.enterAmbient);
    Transition wake = p.to(State.ON, true, false, 10);
    assertTrue(wake.screenOn);
    assertTrue(wake.restartApp);
    assertFalse(wake.exitAmbient);
    assertFalse(p.appStopped());
  }

  @Test
  public void wakingFromAmbientExitsIt() {
    ScreenPolicy p = new ScreenPolicy(true, 0);
    p.to(State.AMBIENT, true, true, 0);
    Transition wake = p.to(State.ON, true, true, 5);
    assertTrue(wake.exitAmbient);
    assertTrue(wake.screenOn);
    assertFalse(wake.restartApp);
    assertFalse(wake.screenOff);
  }

  @Test
  public void turningOffFromAmbientExitsThenStops() {
    ScreenPolicy p = new ScreenPolicy(true, 0);
    p.to(State.AMBIENT, true, true, 0);
    Transition off = p.to(State.OFF, true, true, 5);
    assertTrue(off.exitAmbient);
    assertTrue(off.stopApp);
    // Already not interactive: no second SCREEN_OFF.
    assertFalse(off.screenOff);
  }

  @Test
  public void withoutAlwaysOnAWatchSleepsToOff() {
    ScreenPolicy p = new ScreenPolicy(true, 0);
    p.alwaysOn(false);
    assertEquals(State.OFF, p.sleepTarget());
    Transition t = p.to(p.sleepTarget(), true, true, 0);
    assertTrue(t.stopApp);
    assertFalse(t.enterAmbient);
  }

  @Test
  public void aPhoneHasNoAmbient() {
    ScreenPolicy p = new ScreenPolicy(false, 0);
    assertEquals(State.OFF, p.sleepTarget());
    p.alwaysOn(true);
    assertEquals(State.OFF, p.sleepTarget());
    try {
      p.to(State.AMBIENT, true, true, 0);
      throw new AssertionError("expected a refusal");
    } catch (IllegalArgumentException expected) {
      // ambient is Wear's
    }
  }

  @Test
  public void theSystemUiOnTopIsNotStopped() {
    ScreenPolicy p = new ScreenPolicy(true, 0);
    Transition t = p.to(State.AMBIENT, false, false, 0);
    assertFalse(t.stopApp);
    assertFalse(p.to(State.ON, false, false, 1).restartApp);
  }

  @Test
  public void theTimeoutCountsFromTheLastInput() {
    ScreenPolicy p = new ScreenPolicy(true, 0);
    assertFalse(p.timedOut(1_000_000));
    p.timeoutMs(15_000, 0);
    assertFalse(p.timedOut(14_999));
    p.input(10_000);
    assertFalse(p.timedOut(20_000));
    assertTrue(p.timedOut(25_000));
    p.to(State.AMBIENT, true, true, 25_000);
    assertFalse(p.timedOut(90_000));
  }

  @Test
  public void ambientUpdatesOncePerMinute() {
    ScreenPolicy p = new ScreenPolicy(true, 0);
    p.to(State.AMBIENT, true, true, 1_000);
    assertFalse(p.updateDue(60_999));
    assertTrue(p.updateDue(61_000));
    assertFalse(p.updateDue(61_001));
    assertTrue(p.updateDue(121_000));
  }

  @Test
  public void noUpdatesWithoutAnAmbientApp() {
    ScreenPolicy p = new ScreenPolicy(true, 0);
    p.to(State.AMBIENT, true, false, 0);
    assertFalse(p.updateDue(120_000));
  }

  @Test
  public void stateNamesParse() {
    assertEquals(State.ON, State.of("on"));
    assertEquals(State.AMBIENT, State.of("Ambient"));
    assertEquals(State.AMBIENT, State.of("doze"));
    assertEquals(State.OFF, State.of("off"));
    assertEquals("ambient", State.AMBIENT.id());
  }

  @Test
  public void aTiltInAmbientReportsTheGestureBeforeExitingAmbient() {
    ScreenPolicy p = new ScreenPolicy(true, 0);
    p.to(State.AMBIENT, true, true, 0);
    Transition t = p.tilt(true, true, 5);
    // ScreenPower delivers the sensor event first, while the app is still in ambient.
    assertTrue(t.wristTilt);
    assertTrue(t.exitAmbient);
    assertTrue(t.screenOn);
    assertEquals(State.ON, p.state());
  }

  @Test
  public void aTiltOnAnAwakeWatchStillReportsTheGesture() {
    ScreenPolicy p = new ScreenPolicy(true, 0);
    Transition t = p.tilt(true, true, 0);
    assertTrue(t.wristTilt);
    assertFalse(t.changed());
  }

  @Test
  public void aPhoneHasNoWristTiltSensor() {
    ScreenPolicy p = new ScreenPolicy(false, 0);
    p.to(State.OFF, true, false, 0);
    Transition t = p.tilt(true, false, 1);
    assertFalse(t.wristTilt);
    assertTrue(t.restartApp);
  }
}
