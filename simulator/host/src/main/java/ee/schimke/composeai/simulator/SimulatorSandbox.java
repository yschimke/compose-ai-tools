package ee.schimke.composeai.simulator;

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
final class SimulatorSandbox {

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
                SimulatorShadowConnectivityManager.class,
                SimulatorShadowWifiManager.class,
                SimulatorShadowTelephonyManager.class,
                SimulatorShadowBluetoothGatt.class,
                SimulatorShadowResultReceiver.class,
                SimulatorShadowAppWidgetManager.class,
                SimulatorShadowAppWidgetHostView.class,
                SimulatorShadowRemoteViewsAdapter.class,
                SimulatorShadowCompanionDeviceManager.class,
                // Play services' availability checks, as a device with Play services answers.
                ShadowGooglePlayServicesUtilLight.class,
                ShadowGoogleApiAvailability.class,
                // androidx.appsearch's storages, in memory (AppSearchSessions).
                ShadowLocalStorage.class,
                ShadowPlatformStorage.class);
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
            String.class, SimulatorSandbox.class.getPackageName() + ".SimulatorShadowUiAutomation"),
        ClassParameter.from(boolean.class, false));
    ReflectionHelpers.callInstanceMethod(
        shadows,
        "addShadowClass",
        ClassParameter.from(String.class, "android.app.Instrumentation"),
        ClassParameter.from(
            String.class,
            SimulatorSandbox.class.getPackageName() + ".SimulatorShadowInstrumentation"),
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
        .doNotAcquirePackage("org.bouncycastle.")
        .doNotAcquirePackage("org.conscrypt.")
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
        .doNotInstrumentPackage("android.R");
    builder.doNotInstrumentClass("android.app.RecoverableSecurityException");
    builder.addInstrumentedClass(ShadowGooglePlayServicesUtilLight.UTIL_LIGHT);
    builder.addInstrumentedClass(ShadowGoogleApiAvailability.AVAILABILITY);
    builder.addInstrumentedClass(ShadowLocalStorage.LOCAL_STORAGE);
    builder.addInstrumentedClass(ShadowPlatformStorage.PLATFORM_STORAGE);
    if (DataLayerLink.enabled()) builder.addInstrumentedClass(ShadowWearable.WEARABLE);
    builder
        .addInstrumentedPackage("dalvik.")
        .addInstrumentedPackage("libcore.")
        .addInstrumentedPackage("android.")
        .addInstrumentedPackage("com.android.internal.")
        .addInstrumentedPackage("org.apache.http.")
        .addInstrumentedPackage("org.ccil.cowan.tagsoup.")
        .addInstrumentedPackage("org.kxml2.");
    return builder.build();
  }

  private SimulatorSandbox() {}
}
