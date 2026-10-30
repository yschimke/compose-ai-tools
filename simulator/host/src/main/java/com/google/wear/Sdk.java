package com.google.wear;

/**
 * The Wear OS system library ({@code wear-sdk}, {@code <uses-library android:name="wear-sdk">}) as
 * the simulator's watch reports it: a Wear OS whose only system service is {@code
 * RemoteInteractionsManager}. A watch on API
 * 34+ has the library, and androidx.wear reads {@code Sdk.VERSION.WEAR_SDK_INT} there ({@code
 * WearApiVersion}) before using {@code RemoteInteractionsManager}; without the class it fails with
 * {@code NoClassDefFoundError}. Version 0 sends it down its pre-Wear-SDK paths (a broadcast to the
 * Wear OS app), which the simulator implements. Apps compile against the real library and never
 * package it, so this copy on the simulator's class path is the only one.
 */
public final class Sdk {

  /** The Wear SDK's version constants. */
  public static final class VERSION {
    /** Not a compile-time constant, so the app reads this value at run time. */
    public static final int WEAR_SDK_INT = Integer.getInteger("composeai.simulator.wearSdk", 0);

    private VERSION() {}
  }

  /**
   * The Wear SDK service of [type]: {@code RemoteInteractionsManager}, the one the simulator has
   * ({@code RemoteActivityHelper} needs it on API 34+); null for any other.
   */
  public static <T> T getWearManager(android.content.Context context, Class<T> type) {
    if (type
        == com.google.wear.services.remoteinteractions.RemoteInteractionsManager.class) {
      return type.cast(
          new com.google.wear.services.remoteinteractions.RemoteInteractionsManager(context));
    }
    return null;
  }

  private Sdk() {}
}
