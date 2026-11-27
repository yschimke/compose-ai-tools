package ee.schimke.composeai.simulator;

import static ee.schimke.composeai.simulator.LaunchModes.FLAG_ACTIVITY_CLEAR_TASK;
import static ee.schimke.composeai.simulator.LaunchModes.FLAG_ACTIVITY_CLEAR_TOP;
import static ee.schimke.composeai.simulator.LaunchModes.FLAG_ACTIVITY_NO_HISTORY;
import static ee.schimke.composeai.simulator.LaunchModes.FLAG_ACTIVITY_REORDER_TO_FRONT;
import static ee.schimke.composeai.simulator.LaunchModes.FLAG_ACTIVITY_SINGLE_TOP;
import static ee.schimke.composeai.simulator.LaunchModes.SINGLE_INSTANCE;
import static ee.schimke.composeai.simulator.LaunchModes.SINGLE_INSTANCE_PER_TASK;
import static ee.schimke.composeai.simulator.LaunchModes.SINGLE_TASK;
import static ee.schimke.composeai.simulator.LaunchModes.SINGLE_TOP;
import static ee.schimke.composeai.simulator.LaunchModes.STANDARD;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import ee.schimke.composeai.simulator.LaunchModes.Action;
import ee.schimke.composeai.simulator.LaunchModes.Plan;
import java.util.List;
import org.junit.Test;

public class LaunchModesTest {
  private static final int NEW_TASK = 0x10000000;

  /** Main at the root, Detail over it, Settings on top. */
  private static final List<String> STACK = List.of("Settings", "Detail", "Main");

  @Test
  public void aStandardActivityAlwaysStartsAnotherInstance() {
    assertPlan("START", plan(STANDARD, 0, "Settings"));
    assertPlan("START", plan(STANDARD, 0, "Main"));
    assertPlan("START", plan(STANDARD, 0, "Other"));
  }

  @Test
  public void singleTopDeliversToTheTopInstanceOnly() {
    assertPlan("DELIVER 0", plan(SINGLE_TOP, 0, "Settings"));
    // Not on top: another instance, as on a device.
    assertPlan("START", plan(SINGLE_TOP, 0, "Detail"));
  }

  @Test
  public void theSingleTopFlagActsAsTheLaunchMode() {
    assertPlan("DELIVER 0", plan(STANDARD, FLAG_ACTIVITY_SINGLE_TOP, "Settings"));
    assertPlan("START", plan(STANDARD, FLAG_ACTIVITY_SINGLE_TOP, "Main"));
  }

  @Test
  public void singleTaskClearsWhatIsAboveItsInstanceAndDeliversTheIntent() {
    assertPlan("DELIVER 2", plan(SINGLE_TASK, 0, "Main"));
    assertPlan("DELIVER 1", plan(SINGLE_TASK, NEW_TASK, "Detail"));
    assertPlan("DELIVER 0", plan(SINGLE_TASK, 0, "Settings"));
  }

  @Test
  public void singleTaskWithoutAnInstanceStartsOne() {
    assertPlan("START", plan(SINGLE_TASK, 0, "Other"));
  }

  @Test
  public void singleInstanceVariantsBehaveAsSingleTaskInTheOneTask() {
    assertPlan("DELIVER 2", plan(SINGLE_INSTANCE, 0, "Main"));
    assertPlan("DELIVER 2", plan(SINGLE_INSTANCE_PER_TASK, 0, "Main"));
    assertPlan("START", plan(SINGLE_INSTANCE, 0, "Other"));
  }

  @Test
  public void clearTopReplacesAStandardInstance() {
    // ActivityStarter finishes the standard instance and starts a new one in its place.
    assertPlan("REPLACE 2", plan(STANDARD, FLAG_ACTIVITY_CLEAR_TOP, "Main"));
  }

  @Test
  public void clearTopWithSingleTopKeepsTheInstance() {
    assertPlan(
        "DELIVER 2", plan(STANDARD, FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_SINGLE_TOP, "Main"));
  }

  @Test
  public void clearTopKeepsTheInstanceOfANonStandardLaunchMode() {
    assertPlan("DELIVER 2", plan(SINGLE_TOP, FLAG_ACTIVITY_CLEAR_TOP, "Main"));
    assertPlan("DELIVER 1", plan(SINGLE_TASK, FLAG_ACTIVITY_CLEAR_TOP, "Detail"));
  }

  @Test
  public void clearTopWithoutAnInstanceStartsOne() {
    assertPlan("START", plan(STANDARD, FLAG_ACTIVITY_CLEAR_TOP, "Other"));
  }

  @Test
  public void clearTopActsOnTheTopmostInstance() {
    List<String> twice = List.of("Detail", "Main", "Detail", "Main");
    assertEquals(1, LaunchModes.plan(STANDARD, FLAG_ACTIVITY_CLEAR_TOP, twice, "Main").target);
  }

  @Test
  public void clearTaskWinsOverEverythingElse() {
    for (int mode : new int[] {STANDARD, SINGLE_TOP, SINGLE_TASK, SINGLE_INSTANCE}) {
      assertPlan(
          "clearTask START",
          plan(mode, NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK | FLAG_ACTIVITY_CLEAR_TOP, "Main"));
    }
  }

  @Test
  public void reorderToFrontMovesTheInstanceUpWithoutFinishingAnything() {
    assertPlan("REORDER 2", plan(STANDARD, FLAG_ACTIVITY_REORDER_TO_FRONT, "Main"));
    assertPlan("REORDER 1", plan(STANDARD, FLAG_ACTIVITY_REORDER_TO_FRONT, "Detail"));
  }

  @Test
  public void reorderToFrontOfTheTopDeliversTheIntentToIt() {
    assertPlan("DELIVER 0", plan(STANDARD, FLAG_ACTIVITY_REORDER_TO_FRONT, "Settings"));
  }

  @Test
  public void reorderToFrontWithoutAnInstanceStartsOne() {
    assertPlan("START", plan(STANDARD, FLAG_ACTIVITY_REORDER_TO_FRONT, "Other"));
  }

  @Test
  public void clearTopTakesPrecedenceOverReorderToFront() {
    assertPlan(
        "REPLACE 2",
        plan(STANDARD, FLAG_ACTIVITY_REORDER_TO_FRONT | FLAG_ACTIVITY_CLEAR_TOP, "Main"));
  }

  @Test
  public void anEmptyStackAlwaysStarts() {
    for (int mode : new int[] {STANDARD, SINGLE_TOP, SINGLE_TASK, SINGLE_INSTANCE}) {
      assertPlan("START", LaunchModes.plan(mode, FLAG_ACTIVITY_CLEAR_TOP, List.of(), "Main"));
    }
  }

  @Test
  public void noHistoryComesFromTheManifestOrTheIntent() {
    assertTrue(LaunchModes.noHistory(true, 0));
    assertTrue(LaunchModes.noHistory(false, FLAG_ACTIVITY_NO_HISTORY));
    assertFalse(LaunchModes.noHistory(false, FLAG_ACTIVITY_SINGLE_TOP));
  }

  private static Plan plan(int launchMode, int flags, String activity) {
    return LaunchModes.plan(launchMode, flags, STACK, activity);
  }

  private static void assertPlan(String expected, Plan plan) {
    assertEquals(expected, plan.toString());
  }
}
