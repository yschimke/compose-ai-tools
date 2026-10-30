package ee.schimke.composeai.simulator;

/**
 * Whether {@code startActivity} only brings the app's running task forward, as {@code
 * ActivityStarter} decides on a device: a {@code FLAG_ACTIVITY_NEW_TASK} start of the activity at
 * the task's root, with the root's intent, starts nothing new. That is what a launcher's icon does
 * to a running app, and what {@code getLaunchIntentForPackage} intents do when another component
 * starts the app: Horologist's {@code startRemoteOwnApp} on the watch had each request stack
 * another {@code MainActivity} over the last.
 *
 * <p>The same activity with another intent is not reused: {@code
 * ActivityStarter.complyActivityFlags} brings the task forward and starts a new instance on top
 * ("launching the root activity of the task, but with a different intent"), unless {@code
 * SINGLE_TOP} delivers it to the root, which {@link Navigator} handles.
 *
 * <p>The simulator starts the app with a bare component intent where a launcher sends {@code
 * MAIN} / {@code LAUNCHER}, so a launcher intent also matches a root that was started bare. Pure
 * Java over the flags and the comparisons, so it is tested without the framework.
 */
final class TaskReuse {
  static final int FLAG_ACTIVITY_NEW_TASK = 0x10000000;
  static final int FLAG_ACTIVITY_MULTIPLE_TASK = 0x08000000;
  static final int FLAG_ACTIVITY_CLEAR_TOP = 0x04000000;
  static final int FLAG_ACTIVITY_SINGLE_TOP = 0x20000000;
  static final int FLAG_ACTIVITY_NEW_DOCUMENT = 0x00080000;
  static final int FLAG_ACTIVITY_CLEAR_TASK = 0x00008000;

  private TaskReuse() {}

  /**
   * @param flags the start's intent flags
   * @param rootComponent the started activity is the task's root activity
   * @param sameIntent the start's intent equals the root's ({@code Intent.filterEquals}, package
   *     aside)
   * @param launcherIntent the start's intent is {@code MAIN} with {@code CATEGORY_LAUNCHER}
   * @param rootBareOrLauncher the root was started with a bare component intent or a launcher one
   */
  static boolean bringsTaskForward(
      int flags,
      boolean rootComponent,
      boolean sameIntent,
      boolean launcherIntent,
      boolean rootBareOrLauncher) {
    if ((flags & FLAG_ACTIVITY_NEW_TASK) == 0) return false;
    int other =
        FLAG_ACTIVITY_MULTIPLE_TASK
            | FLAG_ACTIVITY_CLEAR_TOP
            | FLAG_ACTIVITY_SINGLE_TOP
            | FLAG_ACTIVITY_NEW_DOCUMENT
            | FLAG_ACTIVITY_CLEAR_TASK;
    if ((flags & other) != 0) return false;
    if (!rootComponent) return false;
    return sameIntent || (launcherIntent && rootBareOrLauncher);
  }
}
