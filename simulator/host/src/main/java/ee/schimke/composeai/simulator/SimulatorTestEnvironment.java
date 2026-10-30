package ee.schimke.composeai.simulator;

import android.app.Application;
import android.app.Instrumentation;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import javax.inject.Inject;
import javax.inject.Named;
import org.robolectric.android.internal.AndroidTestEnvironment;
import org.robolectric.android.internal.RoboMonitoringInstrumentation;
import org.robolectric.internal.ShadowProvider;
import org.robolectric.pluginapi.Sdk;
import org.robolectric.pluginapi.TestEnvironmentLifecyclePlugin;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowBluetoothAdapter;

/**
 * Robolectric's test environment with the one step it leaves out for an APK: on a device the
 * process creates the manifest's content providers after {@code Application.attachBaseContext()}
 * and before {@code Application.onCreate()}. {@code androidx.startup}'s {@code
 * InitializationProvider} runs initializers there — WorkManager's among them — and an {@code
 * onCreate()} that uses them fails without it (Now in Android does). Loaded inside the sandbox via
 * {@link SimulatorSandbox}.
 */
public class SimulatorTestEnvironment extends AndroidTestEnvironment {

  @Inject
  public SimulatorTestEnvironment(
      @Named("runtimeSdk") Sdk runtimeSdk,
      @Named("compileSdk") Sdk compileSdk,
      ShadowProvider[] shadowProviders,
      TestEnvironmentLifecyclePlugin[] lifecyclePlugins) {
    super(runtimeSdk, compileSdk, shadowProviders, lifecyclePlugins);
  }

  @Override
  protected Instrumentation pickInstrumentation() {
    return new ProvidersFirstInstrumentation();
  }

  /**
   * Robolectric's Bluetooth adapter starts in no state at all (0), which BLE libraries reject (Kable:
   * "Unsupported bluetooth state: 0"). A device's radio is on, with nothing in range.
   */
  static void bluetoothOn(Application app) {
    BluetoothManager manager = app.getSystemService(BluetoothManager.class);
    BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
    if (adapter == null) return;
    ShadowBluetoothAdapter shadow = Shadow.extract(adapter);
    if (adapter.getState() == 0) shadow.setState(BluetoothAdapter.STATE_ON);
  }

  /** Installs the providers immediately before the application's {@code onCreate()}. */
  public static class ProvidersFirstInstrumentation extends RoboMonitoringInstrumentation {
    @Override
    public void callApplicationOnCreate(Application app) {
      // A manifest parsed from an APK leaves the process name unset, where a device defaults it
      // to the package. WorkManager compares the two to decide it runs in the app's main process,
      // and otherwise schedules nothing in-process (Robolectric's JobScheduler never runs a job).
      if (app.getApplicationInfo().processName == null) {
        app.getApplicationInfo().processName = app.getPackageName();
      }
      Connectivity.connect(app);
      Permissions.grantInstallTime(app);
      bluetoothOn(app);
      Navigator.registerBindableServices(app);
      // DataLayer names Play services types: an unpaired app may not have them, so it is never
      // even loaded unless the launch is paired.
      if (DataLayerLink.enabled()) DataLayer.start(app);
      Providers.installBeforeOnCreate(app);
      super.callApplicationOnCreate(app);
    }
  }
}
