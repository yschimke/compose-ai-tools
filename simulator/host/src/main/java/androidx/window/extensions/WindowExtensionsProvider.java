package androidx.window.extensions;

import ee.schimke.composeai.simulator.Foldables;

/**
 * The device's Jetpack WindowManager extensions, which a device's system image supplies as a
 * shared library and apps compile against without bundling. Robolectric has none, so an app's
 * {@code WindowInfoTracker} saw no display features at all; this one is backed by {@link
 * Foldables}, which the control channel's {@code posture} and {@code device} commands drive.
 *
 * <p>Same name and shape as the library's, so the app's own copy of {@code androidx.window} finds
 * and validates it by reflection as it would on a device. Only the window-layout component is
 * implemented; activity embedding and window areas report themselves unavailable.
 */
public final class WindowExtensionsProvider {

  private WindowExtensionsProvider() {}

  public static WindowExtensions getWindowExtensions() {
    return Foldables.EXTENSIONS;
  }
}
