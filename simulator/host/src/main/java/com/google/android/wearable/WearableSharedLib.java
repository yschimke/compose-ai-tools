package com.google.android.wearable;

/**
 * The Wear OS shared library's version, which androidx.wear's {@code SharedLibraryVersion} reads on
 * API 25+ before it gates newer ambient features. See {@code
 * com.google.android.wearable.compat.WearableActivityController} for why the simulator provides the
 * library.
 */
public final class WearableSharedLib {

  private WearableSharedLib() {}

  /** Not a compile-time constant, so the app reads this value at run time. */
  public static int version() {
    return Integer.getInteger("composeai.simulator.wearableSharedLib", 4);
  }
}
