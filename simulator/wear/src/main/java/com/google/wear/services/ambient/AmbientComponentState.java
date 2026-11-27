package com.google.wear.services.ambient;

/** Whether an ambient component is in a state the system may take into ambient. */
public final class AmbientComponentState {

  private AmbientComponentState() {}

  public static ActivityStateRegistry makeActivityStateRegistry() {
    return new ActivityStateRegistry();
  }

  /** The activity's resumed state, which the component reports from its lifecycle. */
  public static final class ActivityStateRegistry {
    boolean resumed;

    ActivityStateRegistry() {}

    public void onResume() {
      resumed = true;
    }

    public void onPause() {
      resumed = false;
    }
  }
}
