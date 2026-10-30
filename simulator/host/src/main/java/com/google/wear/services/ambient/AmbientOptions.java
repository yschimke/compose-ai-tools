package com.google.wear.services.ambient;

import android.app.Activity;

/** What an {@link AmbientManager} controller is for: one activity and its listeners. */
public final class AmbientOptions {

  final Activity activity;
  final AmbientComponentState.ActivityStateRegistry registry;
  final AmbientManager.AmbientComponentListener listener;
  final AmbientManager.AmbientTransitionListener transitions;

  private AmbientOptions(
      Activity activity,
      AmbientComponentState.ActivityStateRegistry registry,
      AmbientManager.AmbientComponentListener listener,
      AmbientManager.AmbientTransitionListener transitions) {
    this.activity = activity;
    this.registry = registry;
    this.listener = listener;
    this.transitions = transitions;
  }

  public static AmbientOptions makeActivityOptions(
      Activity activity,
      AmbientComponentState.ActivityStateRegistry registry,
      AmbientManager.AmbientComponentListener listener,
      AmbientManager.AmbientTransitionListener transitions) {
    return new AmbientOptions(activity, registry, listener, transitions);
  }
}
