package ee.schimke.composeai.simulator;

/**
 * Where {@link SystemBack} sends Back, and the order an opted-in window hears it in; free of
 * Android types so it is tested on the JVM.
 */
enum BackRoute {
  /** The simulator's own home activity, which handles it itself. */
  SYSTEM,
  /**
   * The activity's own default callback, which would ask the system server to finish it: its
   * {@code onBackPressed} instead, which finishes it as that server would.
   */
  ACTIVITY,
  /** The focused window's top {@code OnBackInvokedCallback}. */
  CALLBACK,
  /** {@code KEYCODE_BACK} into the focused window's view tree. */
  KEY;

  /**
   * [systemWindow]: the focused window is a home activity's own, which takes Back whatever the app
   * opted in to (it runs in the app's process, so it shares the app's opt-in). [hasCallback]: the
   * focused window opted in and has a top {@code OnBackInvokedCallback}; [activityDefault]: that
   * callback is the activity's own default, as it is while nothing in the app (androidx's {@code
   * OnBackPressedDispatcher} included) wants Back.
   */
  static BackRoute of(boolean systemWindow, boolean hasCallback, boolean activityDefault) {
    if (systemWindow) return SYSTEM;
    if (!hasCallback) return KEY;
    return activityDefault ? ACTIVITY : CALLBACK;
  }

  /** One {@code OnBackInvokedCallback}, as {@link #deliver} drives it. */
  interface Steps {
    /** An {@code OnBackAnimationCallback}, on API 34+. */
    boolean animated();

    void started();

    void invoked();
  }

  /**
   * As {@code ViewRootImpl} hands a Back key to an opted-in window on API [sdk]: from 34 an
   * animated callback hears the press start on the key down, then every callback is invoked on the
   * release.
   */
  static void deliver(Steps callback, int sdk) {
    if (sdk >= 34 && callback.animated()) callback.started();
    callback.invoked();
  }
}
