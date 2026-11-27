package com.google.android.wearable.compat;

import android.app.Activity;
import android.os.Bundle;
import ee.schimke.composeai.simulator.AmbientClients;
import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * The Wear OS shared library's ambient controller ({@code <uses-library
 * android:name="com.google.android.wearable">}), as the simulator's watch provides it.
 * androidx.wear builds every ambient API on it: {@code AmbientLifecycleObserver}, the legacy {@code
 * AmbientModeSupport} and {@code AmbientMode}, and through those Horologist's {@code AmbientAware}.
 * Without the class, {@code SharedLibraryVersion} reports the library missing and those APIs throw
 * or never call back.
 *
 * <p>An activity that calls {@link #setAmbientEnabled} is registered with {@link AmbientClients},
 * and the simulator's screen ({@code ScreenPower}) calls it back as the system does: {@link
 * AmbientCallback#onEnterAmbient} with the burn-in and low-bit extras when the screen dozes while
 * it is on top and resumed, {@link AmbientCallback#onUpdateAmbient} once a minute, and {@link
 * AmbientCallback#onExitAmbient} on wake. Apps compile against the real library and never package
 * it; a stub an APK does carry is dropped when it is converted, as the device's library would come
 * first.
 */
public class WearableActivityController {

  public static final String EXTRA_BURN_IN_PROTECTION =
      "com.google.android.wearable.compat.extra.BURN_IN_PROTECTION";
  public static final String EXTRA_LOWBIT_AMBIENT =
      "com.google.android.wearable.compat.extra.LOWBIT_AMBIENT";

  /** What the system calls on the activity's side. */
  public abstract static class AmbientCallback {
    public void onEnterAmbient(Bundle ambientDetails) {}

    public void onUpdateAmbient() {}

    public void onExitAmbient() {}

    public void onInvalidateAmbientOffload() {}
  }

  private final Activity activity;
  private final AmbientCallback callback;
  private final Client client = new Client();
  private boolean enabled;
  private boolean resumed;
  private boolean ambient;
  private boolean autoResume = true;
  private boolean offload;

  public WearableActivityController(String tag, Activity activity, AmbientCallback callback) {
    this.activity = activity;
    this.callback = callback;
  }

  public void onCreate() {}

  public void onResume() {
    resumed = true;
  }

  public void onPause() {
    resumed = false;
  }

  public void onStop() {}

  public void onDestroy() {
    AmbientClients.unregister(client);
  }

  /** Opts the activity into ambient mode: it stays on screen, resumed, when the screen dozes. */
  public void setAmbientEnabled() {
    enabled = true;
    AmbientClients.register(client);
  }

  public void setAutoResumeEnabled(boolean enabled) {
    autoResume = enabled;
  }

  public void setAmbientOffloadEnabled(boolean enabled) {
    offload = enabled;
  }

  public boolean isAmbient() {
    return ambient;
  }

  public void dump(String prefix, FileDescriptor fd, PrintWriter writer, String[] args) {
    writer.println(
        prefix
            + "WearableActivityController (simulator): enabled="
            + enabled
            + " ambient="
            + ambient
            + " autoResume="
            + autoResume
            + " offload="
            + offload);
  }

  private final class Client implements AmbientClients.Client {
    @Override
    public Activity activity() {
      return activity;
    }

    @Override
    public boolean resumed() {
      return enabled && resumed;
    }

    @Override
    public String api() {
      return "WearableActivityController";
    }

    @Override
    public boolean ambient() {
      return ambient;
    }

    @Override
    public void enter(boolean burnInProtection, boolean lowBitAmbient) {
      ambient = true;
      Bundle details = new Bundle();
      details.putBoolean(EXTRA_BURN_IN_PROTECTION, burnInProtection);
      details.putBoolean(EXTRA_LOWBIT_AMBIENT, lowBitAmbient);
      callback.onEnterAmbient(details);
    }

    @Override
    public void update() {
      callback.onUpdateAmbient();
    }

    @Override
    public void exit() {
      ambient = false;
      callback.onExitAmbient();
    }
  }
}
