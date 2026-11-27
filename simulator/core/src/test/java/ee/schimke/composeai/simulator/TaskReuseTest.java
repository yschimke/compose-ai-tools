package ee.schimke.composeai.simulator;

import static ee.schimke.composeai.simulator.TaskReuse.FLAG_ACTIVITY_CLEAR_TASK;
import static ee.schimke.composeai.simulator.TaskReuse.FLAG_ACTIVITY_CLEAR_TOP;
import static ee.schimke.composeai.simulator.TaskReuse.FLAG_ACTIVITY_NEW_TASK;
import static ee.schimke.composeai.simulator.TaskReuse.FLAG_ACTIVITY_SINGLE_TOP;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TaskReuseTest {

  @Test
  public void aLauncherIntentForTheRunningRootBringsTheTaskForward() {
    // Horologist's startRemoteOwnApp: getLaunchIntentForPackage + NEW_TASK, the app already up.
    assertTrue(TaskReuse.bringsTaskForward(FLAG_ACTIVITY_NEW_TASK, true, false, true, true));
    assertTrue(TaskReuse.bringsTaskForward(FLAG_ACTIVITY_NEW_TASK, true, true, false, false));
  }

  @Test
  public void withoutNewTaskAnotherInstanceStarts() {
    assertFalse(TaskReuse.bringsTaskForward(0, true, true, true, true));
  }

  @Test
  public void anotherActivityOrIntentStarts() {
    assertFalse(TaskReuse.bringsTaskForward(FLAG_ACTIVITY_NEW_TASK, false, false, true, true));
    // The root with other data (a deep link) starts a new instance on top, as on a device.
    assertFalse(TaskReuse.bringsTaskForward(FLAG_ACTIVITY_NEW_TASK, true, false, false, true));
  }

  @Test
  public void clearingAndSingleTopFlagsKeepTheirOwnHandling() {
    for (int flag :
        new int[] {FLAG_ACTIVITY_CLEAR_TASK, FLAG_ACTIVITY_CLEAR_TOP, FLAG_ACTIVITY_SINGLE_TOP}) {
      assertFalse(
          TaskReuse.bringsTaskForward(FLAG_ACTIVITY_NEW_TASK | flag, true, true, true, true));
    }
  }

  @Test
  public void theLauncherOverADeepLinkedRootStartsAnotherInstance() {
    // ActivityStarter.complyActivityFlags: the task's root activity "with a different intent"
    // starts a new instance on top of the task it brings forward (the known launcher-after-
    // deep-link duplicate that apps guard with isTaskRoot()).
    assertFalse(TaskReuse.bringsTaskForward(FLAG_ACTIVITY_NEW_TASK, true, false, true, false));
  }
}
