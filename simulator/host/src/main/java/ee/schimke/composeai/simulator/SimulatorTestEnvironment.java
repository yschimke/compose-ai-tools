package ee.schimke.composeai.simulator;

import ee.schimke.composeai.simulator.media.HostCodecs;
import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
   * "Unsupported bluetooth state: 0"). A device's radio is on, with nothing in range; off when
   * launched in airplane mode ({@link NetworkModel#bluetoothAtStartup}).
   */
  static void bluetoothOn(Application app) {
    BluetoothManager manager = app.getSystemService(BluetoothManager.class);
    BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
    if (adapter == null) return;
    ShadowBluetoothAdapter shadow = Shadow.extract(adapter);
    int state = NetworkModel.bluetoothAtStartup(adapter.getState(), NetworkSim.get().model.airplane);
    shadow.setState(state);
    android.provider.Settings.Global.putInt(
        app.getContentResolver(),
        android.provider.Settings.Global.BLUETOOTH_ON,
        state == BluetoothAdapter.STATE_ON ? 1 : 0);
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
  /**
   * {@code Build.SUPPORTED_ABIS} otherwise stays Robolectric's {@code armeabi-v7a} only, which
   * misleads libraries that pick a {@code .so} from that list (#6342). The host ABI comes first.
   */
  static void hostAbis() {
    ShadowBuild.setSupportedAbis(new String[] {hostAbi(), "armeabi-v7a"});
  }

  static String hostAbi() {
    String arch = System.getProperty("os.arch", "");
    if ("aarch64".equals(arch) || "arm64".equals(arch)) return "arm64-v8a";
    if (arch.contains("64")) return "x86_64";
    if (arch.contains("86")) return "x86";
    return "arm64-v8a";
  }

  /**
   * {@code ApplicationInfo.nativeLibraryDir} is null for an APK loaded this way. SoLoader and
   * ReLinker look their libraries up by that path; give them a real directory.
   */
  static void nativeLibraryDir(Application app) {
    if (app.getApplicationInfo().nativeLibraryDir != null
        && !app.getApplicationInfo().nativeLibraryDir.isEmpty()) {
      return;
    }
    File parent =
        app.getApplicationInfo().dataDir != null
            ? new File(app.getApplicationInfo().dataDir)
            : app.getFilesDir().getParentFile();
    if (parent == null) return;
    File lib = new File(parent, "lib");
    lib.mkdirs();
    app.getApplicationInfo().nativeLibraryDir = lib.getAbsolutePath();
  }

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

    // The simulator's main thread loops the main looper itself (PAUSED mode, driven in real time),
    // and Robolectric lets no other thread idle it. An instrumentation test (`--instrument`) calls
    // these from its own thread, as on a device: each is handed to the main thread and waited for,
    // which a device's instrumentation does too.

    private volatile Context testContext;

    // The simulator's own activities (the home screen, the stand-in for an app with nothing
    // started) are the system's: a device's launcher runs in another process, where androidx.test's
    // activity monitor never sees it. Their lifecycle goes through the framework's Instrumentation
    // without the monitor's signals, so Back from the app's last activity leaves no activity
    // resumed (Espresso's NoActivityResumedException), and a test's ActivityScenario never finds
    // the home screen.

    private static boolean systemActivity(android.app.Activity activity) {
      if (!InstrumentationRun.enabled()) return false;
      return activity.getClass().getName().startsWith(SimulatorTestEnvironment.class.getPackageName() + ".");
    }

    /**
     * What the framework's {@code Instrumentation.callActivityOn*} does, without the monitor's
     * signal: a {@code findSpecial} call to it would land in Robolectric's monitoring override
     * all the same (a super call resolves from the direct superclass), so the activity's own
     * lifecycle methods are called, as {@code Instrumentation}'s are.
     */
    private static void unmonitored(String stage, android.app.Activity activity, android.os.Bundle state) {
      switch (stage) {
        case "create" ->
            org.robolectric.util.ReflectionHelpers.callInstanceMethod(
                android.app.Activity.class,
                activity,
                "performCreate",
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(android.os.Bundle.class, state));
        case "start" -> org.robolectric.util.ReflectionHelpers.callInstanceMethod(android.app.Activity.class, activity, "onStart");
        case "restart" ->
            org.robolectric.util.ReflectionHelpers.callInstanceMethod(android.app.Activity.class, activity, "onRestart");
        case "resume" -> {
          org.robolectric.util.ReflectionHelpers.setField(android.app.Activity.class, activity, "mResumed", true);
          org.robolectric.util.ReflectionHelpers.callInstanceMethod(android.app.Activity.class, activity, "onResume");
        }
        case "pause" ->
            org.robolectric.util.ReflectionHelpers.callInstanceMethod(android.app.Activity.class, activity, "performPause");
        case "stop" -> org.robolectric.util.ReflectionHelpers.callInstanceMethod(android.app.Activity.class, activity, "onStop");
        case "destroy" ->
            org.robolectric.util.ReflectionHelpers.callInstanceMethod(
                android.app.Activity.class, activity, "performDestroy");
        default -> throw new IllegalArgumentException(stage);
      }
    }

    @Override
    public void callActivityOnCreate(android.app.Activity activity, android.os.Bundle state) {
      if (systemActivity(activity)) {
        unmonitored("create", activity, state);
      } else {
        super.callActivityOnCreate(activity, state);
      }
    }

    @Override
    public void callActivityOnStart(android.app.Activity activity) {
      if (systemActivity(activity)) {
        unmonitored("start", activity, null);
      } else {
        super.callActivityOnStart(activity);
      }
    }

    @Override
    public void callActivityOnRestart(android.app.Activity activity) {
      if (systemActivity(activity)) {
        unmonitored("restart", activity, null);
      } else {
        super.callActivityOnRestart(activity);
      }
    }

    @Override
    public void callActivityOnResume(android.app.Activity activity) {
      if (systemActivity(activity)) {
        unmonitored("resume", activity, null);
      } else {
        super.callActivityOnResume(activity);
      }
    }

    @Override
    public void callActivityOnPause(android.app.Activity activity) {
      if (systemActivity(activity)) {
        unmonitored("pause", activity, null);
      } else {
        super.callActivityOnPause(activity);
      }
    }

    @Override
    public void callActivityOnStop(android.app.Activity activity) {
      if (systemActivity(activity)) {
        unmonitored("stop", activity, null);
      } else {
        super.callActivityOnStop(activity);
      }
    }

    @Override
    public void callActivityOnDestroy(android.app.Activity activity) {
      if (systemActivity(activity)) {
        unmonitored("destroy", activity, null);
      } else {
        super.callActivityOnDestroy(activity);
      }
    }

    /**
     * The test APK's context under {@code --instrument} ({@link TestApkContext}), else the app's,
     * as Robolectric answers.
     */
    @Override
    public Context getContext() {
      String apk = System.getProperty(InstrumentationRun.APK_PROPERTY);
      if (apk == null || apk.isEmpty()) return super.getContext();
      Context context = testContext;
      if (context == null) {
        synchronized (this) {
          if (testContext == null) {
            Context created = TestApkContext.create(super.getTargetContext(), apk);
            testContext = created != null ? created : super.getContext();
          }
          context = testContext;
        }
      }
      return context;
    }

    @Override
    public void waitForIdleSync() {
      if (onMainThread()) super.waitForIdleSync();
      else onMainAndWait(() -> ProvidersFirstInstrumentation.super.waitForIdleSync());
    }

    @Override
    public void runOnMainSync(Runnable runnable) {
      if (onMainThread()) super.runOnMainSync(runnable);
      else onMainAndWait(() -> ProvidersFirstInstrumentation.super.runOnMainSync(runnable));
    }

    @Override
    public Activity startActivitySync(Intent intent) {
      if (onMainThread()) return super.startActivitySync(intent);
      return startAndAwaitResumed(intent, null);
    }

    @Override
    public Activity startActivitySync(Intent intent, Bundle options) {
      if (onMainThread()) return super.startActivitySync(intent);
      return startAndAwaitResumed(intent, options);
    }

    /**
     * A device's {@code startActivitySync}: the start goes through the system (here {@link
     * Navigator}, which drains {@code startActivity} on the main thread), and the caller waits for
     * the activity to resume and the main thread to go idle. androidx.test's {@code
     * ActivityScenario} starts every activity this way.
     */
    private Activity startAndAwaitResumed(Intent intent, Bundle options) {
      Intent start = new Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      ComponentName wanted = start.getComponent();
      Application app = (Application) getTargetContext().getApplicationContext();
      CountDownLatch resumed = new CountDownLatch(1);
      Activity[] found = new Activity[1];
      Application.ActivityLifecycleCallbacks callbacks =
          new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(Activity activity) {
              if (found[0] != null) return;
              if (wanted != null
                  && !wanted.getClassName().equals(activity.getComponentName().getClassName())) {
                return;
              }
              found[0] = activity;
              resumed.countDown();
            }

            @Override
            public void onActivityCreated(Activity activity, Bundle state) {}

            @Override
            public void onActivityStarted(Activity activity) {}

            @Override
            public void onActivityPaused(Activity activity) {}

            @Override
            public void onActivityStopped(Activity activity) {}

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle state) {}

            @Override
            public void onActivityDestroyed(Activity activity) {}
          };
      onMainAndWait(
          () -> {
            app.registerActivityLifecycleCallbacks(callbacks);
            app.startActivity(start, options);
          });
      try {
        if (!resumed.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
          throw new IllegalStateException(
              "activity " + wanted + " did not resume within " + START_TIMEOUT_SECONDS + " s");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted starting " + wanted, e);
      } finally {
        onMainAndWait(() -> app.unregisterActivityLifecycleCallbacks(callbacks));
      }
      waitForIdleSync();
      return found[0];
    }

    private static final long START_TIMEOUT_SECONDS = 45;

    private static boolean onMainThread() {
      return Looper.myLooper() == Looper.getMainLooper();
    }

    /** Runs [task] on the main thread and waits, rethrowing what it threw here. */
    private static void onMainAndWait(Runnable task) {
      CountDownLatch done = new CountDownLatch(1);
      Throwable[] thrown = new Throwable[1];
      new Handler(Looper.getMainLooper())
          .post(
              () -> {
                try {
                  task.run();
                } catch (Throwable t) {
                  thrown[0] = t;
                } finally {
                  done.countDown();
                }
              });
      try {
        done.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted waiting for the main thread", e);
      }
      if (thrown[0] instanceof RuntimeException e) throw e;
      if (thrown[0] instanceof Error e) throw e;
      if (thrown[0] != null) throw new RuntimeException(thrown[0]);
    }
    @Override
    public void callApplicationOnCreate(Application app) {
      // A manifest parsed from an APK leaves the process name unset, where a device defaults it
      // to the package. WorkManager compares the two to decide it runs in the app's main process,
      // and otherwise schedules nothing in-process (Robolectric's JobScheduler never runs a job).
      if (app.getApplicationInfo().processName == null) {
        app.getApplicationInfo().processName = app.getPackageName();
      }
      hostAbis();
      nativeLibraryDir(app);
      Logcat.install();
      // The device's /sdcard, shared with its other apps (--app), before anything opens a file.
      DevicePeers.shareStorage();
      // A restarted or updated simulator's data, before anything opens a file.
      int restored = AppFiles.restore(app);
      if (restored > 0) System.setProperty(Startup.RESTORED_PROPERTY, Integer.toString(restored));
      NetworkSim.connect(app);
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
      PlayServicesPackages.sync(app);
      // AndroidKeyStore, which encrypted preferences open at startup, and voice input's activity.
      AndroidKeyStoreProvider.install();
      SystemPrompts.install(app);
      Alarms.grantExact(app);
      // The other apps of the device (--install): their manifests, here; their code, elsewhere.
      DevicePeers.install(app);
      // Watch Face Format resource APKs hosted by DeclarativeWatchFaceRuntime (--dwf-runtime).
      WffWatchFaces.install(app);
      // ...and their content providers, each call forwarded to the app that owns it.
      DeviceProviders.install(app);
      // The documents and photo pickers, camera, share sheet and browser an intent can reach.
      SystemApps.install(app);
      DocumentsStore.install(app);
      MediaStoreProvider.install(app);
      CalendarProvider.install(app);
      seedSystemPackages(app);
      WearPlatformServices.install(app);
      Navigator.registerBindableServices(app);
      // The host's audio codecs (ffmpeg), in MediaCodecList before the app looks.
      HostCodecs.install();
      // DataLayer names Play services types: an unpaired app may not have them, so it is never
      // even loaded unless the launch is paired.
      if (DataLayerLink.enabled()) {
        DataLayer.start(app);
      } else if ((app.getResources().getConfiguration().uiMode & Configuration.UI_MODE_TYPE_MASK)
          == Configuration.UI_MODE_TYPE_WATCH) {
        // A watch alone still has the Wear OS app's services; paired, DataLayer registers them.
        try {
          WearCompanion.install(app, null);
        } catch (LinkageError e) {
          // The app has no wear-phone-interactions.
        }
      }
      Startup.mark(Startup.PROVIDERS);
      Providers.installBeforeOnCreate(app);
      // Wire Firebase emulators and FCM IPC right after FirebaseInitProvider and before
      // Application.onCreate() so useEmulator() runs before app code starts Firestore/Auth.
      ee.schimke.composeai.simulator.firebase.FirebaseServices.installEarly(app);
      super.callApplicationOnCreate(app);
      Startup.mark(Startup.APPLICATION);
    }

    private static void seedSystemPackages(Application app) {
      org.robolectric.shadows.ShadowPackageManager pm = Shadow.extract(app.getPackageManager());
      String[] systemPackages = {"android", "com.android.shell", "com.android.settings"};
      for (String pkg : systemPackages) {
        try {
          app.getPackageManager().getPackageInfo(pkg, 0);
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
          android.content.pm.PackageInfo pi = new android.content.pm.PackageInfo();
          pi.packageName = pkg;
          android.content.pm.ApplicationInfo ai = new android.content.pm.ApplicationInfo();
          ai.packageName = pkg;
          ai.flags = android.content.pm.ApplicationInfo.FLAG_SYSTEM;
          pi.applicationInfo = ai;
          pm.installPackage(pi);
        }
      }
    }
  }
}
