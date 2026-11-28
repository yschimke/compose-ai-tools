package ee.schimke.composeai.simulator;

import ee.schimke.composeai.simulator.shadows.ShadowDisplayControlLock;
import ee.schimke.composeai.simulator.shadows.ShadowDisplayOffloadManager;
import ee.schimke.composeai.simulator.shadows.gms.ShadowGoogleApiAvailability;
import ee.schimke.composeai.simulator.shadows.gms.ShadowGooglePlayServicesUtilLight;
import ee.schimke.composeai.simulator.shadows.ShadowLocalStorage;
import ee.schimke.composeai.simulator.shadows.ShadowPlatformStorage;
import ee.schimke.composeai.simulator.shadows.ShadowWearHapticFeedbackConstants;
import ee.schimke.composeai.simulator.shadows.gms.ShadowWearable;
import ee.schimke.composeai.simulator.shadows.SimulatorShadowActivityClient;
import ee.schimke.composeai.simulator.shadows.SimulatorShadowAlarmManager;
import ee.schimke.composeai.simulator.shadows.SimulatorShadowAppFunctionManager;
import ee.schimke.composeai.simulator.shadows.SimulatorShadowAppWidgetHostView;
import ee.schimke.composeai.simulator.shadows.SimulatorShadowAppWidgetHost;
import ee.schimke.composeai.simulator.shadows.SimulatorShadowAppWidgetManager;
import ee.schimke.composeai.simulator.shadows.SimulatorShadowBluetoothGatt;
import ee.schimke.composeai.simulator.shadows.SimulatorShadowCompanionDeviceManager;
import ee.schimke.composeai.simulator.shadows.SimulatorShadowConnectivityManager;
import ee.schimke.composeai.simulator.shadows.SimulatorShadowContentResolver;
import ee.schimke.composeai.simulator.shadows.SimulatorShadowContextImpl;
import ee.schimke.composeai.simulator.shadows.SimulatorShadowCredentialManager;
import ee.schimke.composeai.simulator.shadows.SimulatorShadowJobScheduler;
import ee.schimke.composeai.simulator.shadows.SimulatorShadowPackageManager;
import ee.schimke.composeai.simulator.shadows.SimulatorShadowRemoteViewsAdapter;
import ee.schimke.composeai.simulator.shadows.SimulatorShadowResources;
import ee.schimke.composeai.simulator.shadows.SimulatorShadowResultReceiver;
import ee.schimke.composeai.simulator.shadows.SimulatorShadowSettings;
import ee.schimke.composeai.simulator.shadows.SimulatorShadowSurfaceView;
import ee.schimke.composeai.simulator.shadows.SimulatorShadowTelephonyManager;
import ee.schimke.composeai.simulator.shadows.SimulatorShadowTextToSpeech;
import ee.schimke.composeai.simulator.shadows.SimulatorShadowWifiManager;
import com.google.common.collect.ImmutableList;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.robolectric.android.AndroidSdkShadowMatcher;
import org.robolectric.annotation.ResourcesMode;
import org.robolectric.annotation.SQLiteMode;
import org.robolectric.interceptors.AndroidInterceptors;
import org.robolectric.internal.AndroidSandbox;
import org.robolectric.internal.SandboxManager;
import org.robolectric.internal.bytecode.ClassHandler;
import org.robolectric.internal.bytecode.ClassHandlerBuilder;
import org.robolectric.internal.bytecode.InstrumentationConfiguration;
import org.robolectric.internal.bytecode.Interceptors;
import org.robolectric.internal.bytecode.MethodRef;
import org.robolectric.internal.bytecode.ShadowMap;
import org.robolectric.internal.bytecode.ShadowProviders;
import org.robolectric.pluginapi.Sdk;
import org.robolectric.pluginapi.SdkProvider;
import org.robolectric.simulator.SimulatorClassLoader.JarCollection;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;
import org.robolectric.util.inject.Injector;

/**
 * The simulator's sandbox: Robolectric 4.17's {@code org.robolectric.simulator.SandboxBuilder}
 * (Apache-2.0), with one binding added — {@link SimulatorTestEnvironment} as the test
 * environment, so the manifest's content providers are created before {@code
 * Application.onCreate()} as on a device. Upstream's builder offers no way to bind it; keep the
 * rest in step with the Robolectric pin.
 */
public final class SimulatorSandbox {

  static AndroidSandbox build(List<Path> extraJars, int sdkVersion) {
    JarCollection jarCollection = new JarCollection(extraJars);
    URLClassLoader classLoader =
        new URLClassLoader(jarCollection.getUrls(), SimulatorSandbox.class.getClassLoader());
    Injector injector =
        new Injector.Builder(classLoader)
            .bind(Properties.class, System.getProperties())
            .bind(JarCollection.class, jarCollection)
            .bind(
                AndroidSandbox.TestEnvironmentSpec.class,
                new AndroidSandbox.TestEnvironmentSpec(SimulatorTestEnvironment.class))
            .build();

    Sdk chosenSdk = null;
    for (Sdk sdk : injector.getInstance(SdkProvider.class).getSdks()) {
      if (sdk.getApiLevel() == sdkVersion) chosenSdk = sdk;
    }
    if (chosenSdk == null) {
      throw new IllegalArgumentException("Robolectric has no Android SDK " + sdkVersion);
    }

    Interceptors interceptors = new Interceptors(AndroidInterceptors.all());
    SandboxManager.SandboxBuilder sandboxBuilder =
        injector.getInstance(SandboxManager.SandboxBuilder.class);
    AndroidSandbox androidSandbox =
        sandboxBuilder.build(
            instrumentationConfiguration(interceptors),
            chosenSdk,
            chosenSdk,
            ResourcesMode.Mode.BINARY,
            SQLiteMode.Mode.NATIVE);

    ShadowProviders shadowProviders = injector.getInstance(ShadowProviders.class);
    ClassHandlerBuilder classHandlerBuilder = injector.getInstance(ClassHandlerBuilder.class);
    ShadowMap.Builder shadows =
        shadowProviders
            .getBaseShadowMap()
            .newBuilder()
            .addShadowClasses(
                SimulatorShadowContextImpl.class,
                SimulatorShadowPackageManager.class,
                SimulatorShadowConnectivityManager.class,
                SimulatorShadowWifiManager.class,
                SimulatorShadowTelephonyManager.class,
                SimulatorShadowBluetoothGatt.class,
                SimulatorShadowResultReceiver.class,
                SimulatorShadowActivityClient.class,
                SimulatorShadowAppWidgetManager.class,
                SimulatorShadowAppWidgetHost.class,
                SimulatorShadowAppWidgetHostView.class,
                // SurfaceView with a surface its app can draw into, shown on screen.
                SimulatorShadowSurfaceView.class,
                SimulatorShadowRemoteViewsAdapter.class,
                SimulatorShadowCompanionDeviceManager.class,
                SimulatorShadowTextToSpeech.class,
                SimulatorShadowCredentialManager.class,
                SimulatorShadowAlarmManager.class,
                SimulatorShadowJobScheduler.class,
                // Content changes tell the device's other apps (DeviceProviders).
                SimulatorShadowContentResolver.class,
                // The device's settings, shared by its apps (DeviceSettings).
                SimulatorShadowSettings.SimulatorShadowSystem.class,
                SimulatorShadowSettings.SimulatorShadowSecure.class,
                SimulatorShadowSettings.SimulatorShadowGlobal.class,
                // Play services' availability checks, as a device with Play services answers.
                ShadowGooglePlayServicesUtilLight.class,
                ShadowGoogleApiAvailability.class,
                // Play services Auth & Identity (Identity, GoogleSignIn, SmsRetriever, FIDO2).
                ee.schimke.composeai.simulator.shadows.gms.ShadowIdentity.class,
                ee.schimke.composeai.simulator.shadows.gms.ShadowGoogleSignIn.class,
                ee.schimke.composeai.simulator.shadows.gms.ShadowSmsRetrieverClient.class,
                ee.schimke.composeai.simulator.shadows.gms.ShadowFido2ApiClient.class,
                // androidx.appsearch's storages, in memory (AppSearchSessions).
                ShadowLocalStorage.class,
                // Cross-app App Functions calls, over the device bus of --install.
                SimulatorShadowAppFunctionManager.class,
                ShadowPlatformStorage.class,
                // Optional Wear SDK APIs are signature-only Android library classes bundled by
                // Wear apps, not android.jar. The simulator supplies the safe haptic mapping.
                ShadowWearHapticFeedbackConstants.class,
                // Wear OS display offload stubs bundled in DeclarativeWatchFaceRuntime.
                ShadowDisplayOffloadManager.class,
                ShadowDisplayControlLock.class,
                // Dynamic Material 3 system colors derived from the active watch face palette.
                SimulatorShadowResources.class);
    // A paired launch answers the app's Play services Wearable calls itself (DataLayer).
    if (DataLayerLink.enabled()) {
      shadows.addShadowClasses(ShadowWearable.class);
    }
    // By name: loading these classes here, outside the sandbox, would load Robolectric's shadows
    // and the framework classes they name (android.app.UiAutomation) into this class loader, and
    // RemoteControl.connect(UiAutomation, ...) would then refuse the sandbox's SimulatorControl.
    ReflectionHelpers.callInstanceMethod(
        shadows,
        "addShadowClass",
        ClassParameter.from(String.class, "android.app.UiAutomation"),
        ClassParameter.from(
            String.class, SimulatorSandbox.class.getPackageName() + ".shadows.SimulatorShadowUiAutomation"),
        ClassParameter.from(boolean.class, false));
    ReflectionHelpers.callInstanceMethod(
        shadows,
        "addShadowClass",
        ClassParameter.from(String.class, "android.app.Instrumentation"),
        ClassParameter.from(
            String.class,
            SimulatorSandbox.class.getPackageName() + ".shadows.SimulatorShadowInstrumentation"),
        ClassParameter.from(boolean.class, false));
    // Wear SDK API version and global status bar settings (instrumented under com.google.wear.).
    ReflectionHelpers.callInstanceMethod(
        shadows,
        "addShadowClass",
        ClassParameter.from(String.class, "com.google.wear.Sdk"),
        ClassParameter.from(
            String.class, SimulatorSandbox.class.getPackageName() + ".shadows.ShadowWearSdk"),
        ClassParameter.from(boolean.class, false));
    ReflectionHelpers.callInstanceMethod(
        shadows,
        "addShadowClass",
        ClassParameter.from(String.class, "com.google.wear.Sdk$VERSION_CODES"),
        ClassParameter.from(
            String.class,
            SimulatorSandbox.class.getPackageName() + ".shadows.ShadowWearSdk$VersionCodes"),
        ClassParameter.from(boolean.class, false));
    ReflectionHelpers.callInstanceMethod(
        shadows,
        "addShadowClass",
        ClassParameter.from(String.class, "com.google.wear.settings.WearSettings"),
        ClassParameter.from(
            String.class,
            SimulatorSandbox.class.getPackageName() + ".shadows.ShadowWearSettings"),
        ClassParameter.from(boolean.class, false));
    // PixelCopy (Compose's captureToImage) from a test's own thread, copied on the main thread.
    ReflectionHelpers.callInstanceMethod(
        shadows,
        "addShadowClass",
        ClassParameter.from(String.class, "android.view.PixelCopy"),
        ClassParameter.from(
            String.class,
            SimulatorSandbox.class.getPackageName() + ".shadows.SimulatorShadowPixelCopy"),
        ClassParameter.from(boolean.class, false));
    // Frame-commit callbacks run once the frame is drawn (the native renderer never calls back).
    ReflectionHelpers.callInstanceMethod(
        shadows,
        "addShadowClass",
        ClassParameter.from(String.class, "android.view.ViewRootImpl"),
        ClassParameter.from(
            String.class,
            SimulatorSandbox.class.getPackageName() + ".shadows.SimulatorShadowViewRootImpl"),
        ClassParameter.from(boolean.class, false));
    // SystemClock.sleep off the main thread sleeps, as on a device (UiAutomator's long press).
    ReflectionHelpers.callInstanceMethod(
        shadows,
        "addShadowClass",
        ClassParameter.from(String.class, "android.os.SystemClock"),
        ClassParameter.from(
            String.class,
            SimulatorSandbox.class.getPackageName() + ".shadows.SimulatorShadowSystemClock"),
        ClassParameter.from(boolean.class, false));
    // Injected input (Espresso's touches and keys) reaches the simulator's windows.
    ReflectionHelpers.callInstanceMethod(
        shadows,
        "addShadowClass",
        ClassParameter.from(String.class, "android.hardware.input.InputManager"),
        ClassParameter.from(
            String.class, SimulatorSandbox.class.getPackageName() + ".shadows.SimulatorShadowInputManager"),
        ClassParameter.from(boolean.class, false));
    // MediaCodecs backed by the host's ffmpeg (HostCodecs), beside Robolectric's.
    ReflectionHelpers.callInstanceMethod(
        shadows,
        "addShadowClass",
        ClassParameter.from(String.class, "android.media.MediaCodec"),
        ClassParameter.from(
            String.class, SimulatorSandbox.class.getPackageName() + ".shadows.SimulatorShadowMediaCodec"),
        ClassParameter.from(boolean.class, false));
    // MediaExtractor over real files (MP3, WAV, MP4), not only tracks a test registered.
    ReflectionHelpers.callInstanceMethod(
        shadows,
        "addShadowClass",
        ClassParameter.from(String.class, "android.media.MediaExtractor"),
        ClassParameter.from(
            String.class,
            SimulatorSandbox.class.getPackageName() + ".shadows.SimulatorShadowMediaExtractor"),
        ClassParameter.from(boolean.class, false));
    // Activity.recreate() through Navigator, as its `recreate` command does.
    ReflectionHelpers.callInstanceMethod(
        shadows,
        "addShadowClass",
        ClassParameter.from(String.class, "android.app.Activity"),
        ClassParameter.from(
            String.class, SimulatorSandbox.class.getPackageName() + ".shadows.SimulatorShadowActivity"),
        ClassParameter.from(boolean.class, false));
    ShadowMap shadowMap = shadows.build();
    androidSandbox.replaceShadowMap(shadowMap);
    ClassHandler classHandler =
        classHandlerBuilder.build(
            shadowMap,
            new AndroidSdkShadowMatcher(chosenSdk.getApiLevel()),
            interceptors,
            ImmutableList.of());
    androidSandbox.configure(classHandler, interceptors);
    return androidSandbox;
  }

  private static InstrumentationConfiguration instrumentationConfiguration(
      Interceptors interceptors) {
    InstrumentationConfiguration.Builder builder = new InstrumentationConfiguration.Builder();
    for (MethodRef methodRef : interceptors.getAllMethodRefs()) {
      builder.addInterceptedMethod(methodRef);
    }
    builder
        .doNotAcquireClass("org.robolectric.shadow.api.ShadowPicker")
        .doNotAcquireClass("org.robolectric.simulator.SimulatorPanelRegistry");
    builder
        // macOS's look and feel lives in java.desktop. Swing loads it through the context class
        // loader, the sandbox's on the AWT thread; a sandbox copy cannot reach sun.awt, and the
        // window's panel fails to build (IllegalAccessError in AquaUtils).
        .doNotAcquirePackage("apple.")
        .doNotAcquirePackage("com.apple.")
        // One NetworkGate for the launcher, which installs it, and the sandbox, which drives it.
        .doNotAcquirePackage("ee.schimke.composeai.simulator.net.")
        // One HotSwap: the agent and the sandbox class loader set it up outside, Session drives it.
        .doNotAcquirePackage("ee.schimke.composeai.simulator.hotswap.")
        .doNotAcquirePackage("org.bouncycastle.")
        .doNotAcquirePackage("org.conscrypt.")
        // Mockito's InlineByteBuddyMockMaker (and MockK's JVM agent) inject their dispatcher
        // classes directly into the bootstrap class loader; acquiring them in the sandbox fails
        // with ClassNotFoundException. Keep BootJarLoader in the sandbox.
        .doNotAcquirePackage("org.mockito.internal.creation.bytebuddy.inject.")
        .doNotAcquireClass("io.mockk.proxy.jvm.dispatcher.JvmMockKDispatcher")
        .doNotAcquirePackage("org.objectweb.asm")
        .doNotAcquirePackage("org.robolectric.manifest.")
        .doNotAcquirePackage("org.robolectric.res.")
        .doNotAcquirePackage("org.w3c.")
        .doNotAcquirePackage("org.xml.");
    builder
        .addClassNameTranslation(
            "java.net.ExtendedResponseCache", "org.robolectric.fakes.RoboExtendedResponseCache")
        .addClassNameTranslation(
            "java.net.ResponseSource", "org.robolectric.fakes.RoboResponseSource")
        .addClassNameTranslation("java.nio.charset.Charsets", StandardCharsets.class.getName())
        .addClassNameTranslation("java.lang.UnsafeByteSequence", Object.class.getName())
        .addClassNameTranslation("java.util.jar.StrictJarFile", Object.class.getName())
        .addClassNameTranslation("sun.misc.Cleaner", "java.lang.ref.Cleaner$Cleanable");
    builder
        .doNotInstrumentPackage("android.support.constraint.")
        .doNotInstrumentPackage("android.support.v7.view.")
        .doNotInstrumentPackage("android.arch")
        .doNotInstrumentPackage("android.support.test")
        .doNotInstrumentPackage("android.support.wearable.")
        .doNotInstrumentPackage("com.google.wear.watchface.")
        .doNotInstrumentPackage("android.R");
    builder.doNotInstrumentClass("android.app.RecoverableSecurityException");
    builder.addInstrumentedClass(ShadowGooglePlayServicesUtilLight.UTIL_LIGHT);
    builder.addInstrumentedClass(ShadowGoogleApiAvailability.AVAILABILITY);
    builder.addInstrumentedClass(ee.schimke.composeai.simulator.shadows.gms.ShadowIdentity.IDENTITY);
    builder.addInstrumentedClass(
        ee.schimke.composeai.simulator.shadows.gms.ShadowGoogleSignIn.GOOGLE_SIGN_IN);
    builder.addInstrumentedClass(
        ee.schimke.composeai.simulator.shadows.gms.ShadowSmsRetrieverClient.SMS_RETRIEVER_CLIENT);
    builder.addInstrumentedClass(
        ee.schimke.composeai.simulator.shadows.gms.ShadowFido2ApiClient.FIDO2_API_CLIENT);
    builder.addInstrumentedClass(ShadowLocalStorage.LOCAL_STORAGE);
    builder.addInstrumentedClass(ShadowPlatformStorage.PLATFORM_STORAGE);
    builder.addInstrumentedClass(ShadowDisplayOffloadManager.TARGET);
    builder.addInstrumentedClass(ShadowDisplayControlLock.TARGET);
    // `android.jar` deliberately has no Wear SDK surface. Wear Compose calls this optional API
    // during scroll setup, so instrument the app-bundled signature classes and route the small
    // simulator-safe subset through explicit shadows. This is inert for phone apps.
    builder.addInstrumentedPackage("com.google.wear.");
    if (DataLayerLink.enabled()) builder.addInstrumentedClass(ShadowWearable.WEARABLE);
    builder
        .addInstrumentedPackage("dalvik.")
        .addInstrumentedPackage("libcore.")
        .addInstrumentedPackage("android.")
        .addInstrumentedPackage("com.android.internal.")
        .addInstrumentedPackage("org.apache.http.")
        .addInstrumentedPackage("org.ccil.cowan.tagsoup.")
        .addInstrumentedPackage("org.kxml2.");
    return new AppLibraryConfiguration(builder);
  }

  /**
   * Robolectric's configuration, but acquiring {@link #APP_PACKAGES}: app libraries that live under a
   * prefix Robolectric never acquires because the JDK owns most of it. JNA is `com.sun.jna`; left
   * to the parent class loader, which has none of the APK's classes, the app's first use of it
   * fails with NoClassDefFoundError. The builder's collections are passed as they are: `build()`
   * only deduplicates them.
   */
  static final class AppLibraryConfiguration extends InstrumentationConfiguration {
    static final List<String> APP_PACKAGES = List.of("com.sun.jna.");

    AppLibraryConfiguration(InstrumentationConfiguration.Builder builder) {
      super(
          builder.classNameTranslations,
          builder.interceptedMethods,
          builder.instrumentedPackages,
          builder.instrumentedClasses,
          builder.classesToNotAcquire,
          builder.packagesToNotAcquire,
          builder.classesToNotInstrument,
          builder.packagesToNotInstrument,
          builder.classesToNotInstrumentRegex);
    }

    @Override
    public boolean shouldAcquire(String name) {
      for (String prefix : APP_PACKAGES) {
        if (name.startsWith(prefix)) return true;
      }
      return super.shouldAcquire(name);
    }
  }

  private SimulatorSandbox() {}
}
