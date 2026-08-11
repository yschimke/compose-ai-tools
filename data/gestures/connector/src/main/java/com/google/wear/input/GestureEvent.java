package com.google.wear.input;

/**
 * Preview-classpath placeholder for the device-only Wear SDK type referenced by Wear Compose.
 *
 * <p>The public Wear Compose artifact links its internal gesture bridge against this class, but the
 * Google Wear input SDK is only present on supported watches. Robolectric reflects the bridge's
 * method signatures while installing {@code ShadowSdkGestureInputManager}, so it needs the type to
 * exist even though the shadow never constructs or reads an event.
 */
public final class GestureEvent {
  private GestureEvent() {}
}
