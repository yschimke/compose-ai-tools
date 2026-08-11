package com.google.wear.input;

/**
 * Preview-classpath placeholder for Wear's device-only gesture manager.
 *
 * <p>All calls to Wear Compose's SDK bridge are replaced by {@code
 * ee.schimke.composeai.daemon.ShadowSdkGestureInputManager}; this class exists only so Robolectric
 * can resolve the bridge's field and private-method signatures while instrumenting it off-watch.
 */
public final class GestureInputManager {
  private GestureInputManager() {}
}
