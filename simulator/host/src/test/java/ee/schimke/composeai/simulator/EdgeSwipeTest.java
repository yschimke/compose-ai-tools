package ee.schimke.composeai.simulator;

import static ee.schimke.composeai.simulator.EdgeSwipe.CANCELLED;
import static ee.schimke.composeai.simulator.EdgeSwipe.DOWN;
import static ee.schimke.composeai.simulator.EdgeSwipe.MOVE;
import static ee.schimke.composeai.simulator.EdgeSwipe.UP;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import ee.schimke.composeai.simulator.EdgeSwipe.Step;
import org.junit.Test;

public class EdgeSwipeTest {

  // A 540px-wide display, 36px edges, 12px slop, 96px to trigger.
  private final EdgeSwipe swipe = new EdgeSwipe(540, 36, 12, 96);

  @Test
  public void aSwipeInFromTheLeftEdgeIsBack() {
    assertEquals(Step.APP, swipe.on(DOWN, 4, 400));
    assertEquals(Step.APP, swipe.on(MOVE, 10, 401));
    assertEquals(Step.START, swipe.on(MOVE, 30, 402));
    assertTrue(swipe.active());
    assertEquals(EdgeSwipe.EDGE_LEFT, swipe.edge());
    assertEquals(Step.PROGRESS, swipe.on(MOVE, 140, 405));
    assertEquals(136 / 536f, swipe.progress(), 1e-4);
    assertEquals(Step.COMMIT, swipe.on(UP, 200, 405));
    assertFalse(swipe.active());
  }

  @Test
  public void theRightEdgeToo() {
    swipe.on(DOWN, 536, 300);
    assertEquals(Step.START, swipe.on(MOVE, 510, 300));
    assertEquals(EdgeSwipe.EDGE_RIGHT, swipe.edge());
    assertEquals(Step.COMMIT, swipe.on(UP, 400, 300));
  }

  @Test
  public void itRemembersWhereTheTouchWentDownForTheAppsCancel() {
    swipe.on(DOWN, 6, 410);
    swipe.on(MOVE, 30, 425);
    swipe.on(MOVE, 200, 440);
    assertEquals(6, swipe.startX(), 0);
    assertEquals(410, swipe.startY(), 0);
  }

  @Test
  public void liftingShortOfTheTriggerCancels() {
    swipe.on(DOWN, 2, 300);
    assertEquals(Step.START, swipe.on(MOVE, 40, 300));
    assertEquals(Step.CANCEL, swipe.on(UP, 60, 300));
  }

  @Test
  public void comingBackToTheEdgeBeforeLiftingCancels() {
    swipe.on(DOWN, 2, 300);
    swipe.on(MOVE, 200, 300);
    swipe.on(MOVE, 20, 300);
    assertEquals(0, swipe.progress(), 0.05);
    assertEquals(Step.CANCEL, swipe.on(UP, 20, 300));
  }

  @Test
  public void aCancelledPointerIsNoBack() {
    swipe.on(DOWN, 2, 300);
    swipe.on(MOVE, 200, 300);
    assertEquals(Step.CANCEL, swipe.on(CANCELLED, 200, 300));
  }

  @Test
  public void aDragAlongTheEdgeStaysTheApps() {
    swipe.on(DOWN, 10, 300);
    assertEquals(Step.APP, swipe.on(MOVE, 14, 340));
    assertEquals(Step.APP, swipe.on(MOVE, 200, 350));
    assertFalse(swipe.active());
    assertEquals(Step.APP, swipe.on(UP, 300, 350));
  }

  @Test
  public void aTouchAwayFromTheEdgesIsTheApps() {
    assertEquals(Step.APP, swipe.on(DOWN, 270, 300));
    assertEquals(Step.APP, swipe.on(MOVE, 500, 300));
    assertEquals(Step.APP, swipe.on(UP, 500, 300));
  }

  @Test
  public void aTapAtTheEdgeIsTheApps() {
    assertEquals(Step.APP, swipe.on(DOWN, 5, 300));
    assertEquals(Step.APP, swipe.on(UP, 6, 300));
  }

  @Test
  public void progressStopsAtTheFarSide() {
    swipe.on(DOWN, 0, 300);
    swipe.on(MOVE, 30, 300);
    swipe.on(MOVE, 900, 300);
    assertEquals(1f, swipe.progress(), 0);
  }
}
