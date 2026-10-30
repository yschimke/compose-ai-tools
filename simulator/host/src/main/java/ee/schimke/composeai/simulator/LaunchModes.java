package ee.schimke.composeai.simulator;

import java.util.List;

/**
 * What a {@code startActivity} does to the back stack of the display it lands on, from the target's
 * manifest {@code android:launchMode} and the intent's flags, as {@code ActivityStarter} decides on
 * a device. {@link Navigator} carries the plan out.
 *
 * <p>The simulator keeps one task per display, the app's, so the task-level outcomes collapse onto
 * it: a {@code singleTask} or {@code singleInstance} activity that already runs is brought forward
 * with everything above it finished, and one that does not starts on top. A {@code singleInstance}
 * activity does not get a task of its own, so what it starts goes on top of it rather than into the
 * app's other task.
 *
 * <p>Pure Java over the flags and the stack's class names, so it is tested without the framework.
 */
final class LaunchModes {
  // ActivityInfo.LAUNCH_*
  static final int STANDARD = 0;
  static final int SINGLE_TOP = 1;
  static final int SINGLE_TASK = 2;
  static final int SINGLE_INSTANCE = 3;
  static final int SINGLE_INSTANCE_PER_TASK = 4;

  static final int FLAG_ACTIVITY_SINGLE_TOP = 0x20000000;
  static final int FLAG_ACTIVITY_CLEAR_TOP = 0x04000000;
  static final int FLAG_ACTIVITY_CLEAR_TASK = 0x00008000;
  static final int FLAG_ACTIVITY_REORDER_TO_FRONT = 0x00020000;
  static final int FLAG_ACTIVITY_NO_HISTORY = 0x40000000;

  private LaunchModes() {}

  enum Action {
    /** A new instance goes on top. */
    START,
    /** The activities above {@link Plan#target} finish, then it gets {@code onNewIntent}. */
    DELIVER,
    /** The activities above {@link Plan#target} finish, it finishes too, and a new one starts. */
    REPLACE,
    /** {@link Plan#target} moves to the front, finishing nothing, and gets {@code onNewIntent}. */
    REORDER,
  }

  static final class Plan {
    /** Every activity of the app's on the display finishes first ({@code CLEAR_TASK}). */
    final boolean clearTask;

    final Action action;

    /** The index, top first, of the existing instance the plan acts on; -1 for {@code START}. */
    final int target;

    Plan(boolean clearTask, Action action, int target) {
      this.clearTask = clearTask;
      this.action = action;
      this.target = target;
    }

    @Override
    public String toString() {
      return (clearTask ? "clearTask " : "") + action + (target >= 0 ? " " + target : "");
    }
  }

  /**
   * @param launchMode the target's manifest {@code launchMode} ({@code ActivityInfo.LAUNCH_*})
   * @param flags the start's intent flags
   * @param stack the class names of the activities on the display, top first
   * @param activity the class name of the activity being started
   */
  static Plan plan(int launchMode, int flags, List<String> stack, String activity) {
    if ((flags & FLAG_ACTIVITY_CLEAR_TASK) != 0) return new Plan(true, Action.START, -1);
    int existing = stack.indexOf(activity);
    boolean singleTask =
        launchMode == SINGLE_TASK
            || launchMode == SINGLE_INSTANCE
            || launchMode == SINGLE_INSTANCE_PER_TASK;
    boolean clearTop = (flags & FLAG_ACTIVITY_CLEAR_TOP) != 0;
    if (existing >= 0 && (clearTop || singleTask)) {
      // ActivityStarter: a standard activity under CLEAR_TOP without SINGLE_TOP is finished and
      // started again; any other launch mode keeps the instance and hands it the intent.
      boolean replace = launchMode == STANDARD && (flags & FLAG_ACTIVITY_SINGLE_TOP) == 0;
      return new Plan(false, replace ? Action.REPLACE : Action.DELIVER, existing);
    }
    if (existing > 0 && (flags & FLAG_ACTIVITY_REORDER_TO_FRONT) != 0) {
      return new Plan(false, Action.REORDER, existing);
    }
    if (existing == 0 && (deliversToTop(launchMode, flags) || reordersTop(flags))) {
      return new Plan(false, Action.DELIVER, 0);
    }
    return new Plan(false, Action.START, -1);
  }

  /** Whether an activity on top receives a start of itself as {@code onNewIntent}. */
  static boolean deliversToTop(int launchMode, int flags) {
    return launchMode != STANDARD || (flags & FLAG_ACTIVITY_SINGLE_TOP) != 0;
  }

  private static boolean reordersTop(int flags) {
    return (flags & FLAG_ACTIVITY_REORDER_TO_FRONT) != 0;
  }

  /**
   * Whether an activity finishes as soon as another covers it or the app goes home: the manifest's
   * {@code android:noHistory}, or {@code FLAG_ACTIVITY_NO_HISTORY} on the intent that started it.
   */
  static boolean noHistory(boolean manifestNoHistory, int flags) {
    return manifestNoHistory || (flags & FLAG_ACTIVITY_NO_HISTORY) != 0;
  }
}
