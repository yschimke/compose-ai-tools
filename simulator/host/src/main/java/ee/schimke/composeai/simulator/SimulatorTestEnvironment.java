package ee.schimke.composeai.simulator;

import android.app.Application;
import android.app.Instrumentation;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import javax.inject.Inject;
import javax.inject.Named;
import org.robolectric.android.internal.AndroidTestEnvironment;
import org.robolectric.android.internal.RoboMonitoringInstrumentation;
import org.robolectric.internal.ShadowProvider;
import org.robolectric.pluginapi.Sdk;
import org.robolectric.pluginapi.TestEnvironmentLifecyclePlugin;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowBluetoothAdapter;
import org.robolectric.shadows.ShadowBuild;
import org.robolectric.shadows.ShadowPackageManager;

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

  /**
   * A watch reports {@code android.hardware.type.watch}, which Robolectric leaves out whatever the
   * qualifiers say. Wear libraries branch on it: {@code RemoteActivityHelper} picks the watch's
   * path (a broadcast to the Wear OS app, no companion lookup), and apps check it to tell a watch
   * from a phone.
   */
  static void watchFeature(Application app) {
    int type = app.getResources().getConfiguration().uiMode & Configuration.UI_MODE_TYPE_MASK;
    if (type != Configuration.UI_MODE_TYPE_WATCH) return;
    ShadowPackageManager shadow = Shadow.extract(app.getPackageManager());
    shadow.setSystemFeature(PackageManager.FEATURE_WATCH, true);
  }

  static final String FINGERPRINT_PROPERTY = "composeai.simulator.fingerprint";

  /**
   * {@code Build.FINGERPRINT} from {@code launch --fingerprint}. Robolectric's is {@code
   * robolectric}, and apps compare against it to skip real work in tests and previews: MeshCore's
   * watch then uses canned preview data instead of its phone.
   */
  static void fingerprint() {
    String value = System.getProperty(FINGERPRINT_PROPERTY);
    if (value != null && !value.isEmpty()) ShadowBuild.setFingerprint(value);
  }

  /**
   * In a paired launch, {@code Build.DEVICE} and {@code Build.MODEL} name this node ({@code
   * phone-sim}, "Simulated Phone") rather than Robolectric's {@code robolectric}, so an app that
   * labels what it sends with the device it runs on tells the two apart, as Horologist's Data
   * Layer sample does with {@code Build.DEVICE}. {@code Build.FINGERPRINT} stays {@code
   * --fingerprint}'s.
   */
  static void pairedDevice() {
    if (!DataLayerLink.enabled()) return;
    String node = System.getProperty(DataLayerLink.NODE_PROPERTY);
    String name = System.getProperty(DataLayerLink.NAME_PROPERTY);
    if (node != null && !node.isEmpty()) ShadowBuild.setDevice(node);
    if (name != null && !name.isEmpty()) ShadowBuild.setModel(name);
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
      Logcat.install();
      Connectivity.connect(app);
      Permissions.grantInstallTime(app);
      bluetoothOn(app);
      watchFeature(app);
      // As on a device: unbindService does not call onServiceDisconnected. Robolectric's default
      // does, and a connection that unbinds in onServiceDisconnected (androidx.wear's
      // BridgingManager) then loops forever.
      ShadowApplication shadowApp = Shadow.extract(app);
      shadowApp.setUnbindServiceCallsOnServiceDisconnected(false);
      fingerprint();
      pairedDevice();
      Navigator.registerBindableServices(app);
      // DataLayer names Play services types: an unpaired app may not have them, so it is never
      // even loaded unless the launch is paired.
      if (DataLayerLink.enabled()) DataLayer.start(app);
      Providers.installBeforeOnCreate(app);
      super.callApplicationOnCreate(app);
    }
  }
}
