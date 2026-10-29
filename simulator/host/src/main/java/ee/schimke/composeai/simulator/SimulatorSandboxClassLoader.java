package ee.schimke.composeai.simulator;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import javax.annotation.Priority;
import javax.inject.Named;
import org.robolectric.internal.bytecode.ClassInstrumentor;
import org.robolectric.internal.bytecode.InstrumentationConfiguration;
import org.robolectric.pluginapi.Sdk;
import org.robolectric.simulator.SimulatorClassLoader;

/**
 * Robolectric's simulator class loader, which serves the APK's classes ahead of the runtime
 * classpath, except for the libraries Robolectric's own sandboxed code links against.
 *
 * <p>An app that bundles an older copy of one of those would otherwise hand it to Robolectric:
 * Now in Android's Guava has no {@code ImmutableList.toImmutableList()}, and the simulator's
 * {@code ScreenUpdateHandler} died on it before the first frame. Serving Robolectric's copy to the
 * app as well is what a Robolectric unit test of that app does, with one Guava resolved for both.
 *
 * <p>It also serves the SDK's {@code android.jar} stub of a platform class the simulated SDK
 * lacks. Apps reference newer platform classes behind {@code SDK_INT} checks (OkHttp's {@code
 * Android17SocketAdapter} catches API 37's {@code InvalidEchDataException}); ART leaves such a
 * reference unresolved until it runs, where the JVM's verifier loads it up front and fails the
 * whole class. The stub satisfies the verifier, and throws "Stub!" if the guarded code ever ran.
 *
 * <p>Ranked above the simulator's own loader by {@link Priority}.
 */
@Priority(100)
public class SimulatorSandboxClassLoader extends SimulatorClassLoader {

  /**
   * Class-resource prefixes served from the runtime classpath when it has the class. Only then:
   * D8 puts its global backport synthetics in whatever package it likes, so the APK can carry a
   * {@code com/google/common/…$$ExternalSyntheticBackport…} that kotlinx.coroutines needs and
   * no Guava jar has.
   */
  static final String[] RUNTIME_FIRST = {"com/google/common/", "com/google/thirdparty/"};

  public SimulatorSandboxClassLoader(
      InstrumentationConfiguration config,
      @Named("runtimeSdk") Sdk runtimeSdk,
      ClassInstrumentor classInstrumentor,
      JarCollection jarCollection) {
    super(config, runtimeSdk, classInstrumentor, jarCollection);
  }

  @Override
  protected InputStream getClassBytesFromAlternateClassLoader(String classResName) {
    if (runtimeUrl(classResName) != null) return null;
    InputStream app = super.getClassBytesFromAlternateClassLoader(classResName);
    if (app != null) return app;
    URL stub = platformStubUrl(classResName);
    try {
      return stub == null ? null : stub.openStream();
    } catch (IOException e) {
      return null;
    }
  }

  /** The sandbox also reads class bytes through here, where the simulator prefers the APK too. */
  @Override
  protected URL getResourceUrl(String name) {
    URL runtime = runtimeUrl(name);
    if (runtime != null) return runtime;
    URL url = super.getResourceUrl(name);
    return url != null ? url : platformStubUrl(name);
  }

  /** The SDK's stub of platform class [name], from {@code android.jar} on the classpath. */
  private static URL platformStubUrl(String name) {
    if (!name.startsWith("android/") || !name.endsWith(".class")) return null;
    return SimulatorSandboxClassLoader.class.getClassLoader().getResource(name);
  }

  /** The runtime classpath's copy of [name] when runtime-first and present there, else null. */
  private static URL runtimeUrl(String name) {
    if (!runtimeFirst(name)) return null;
    return SimulatorSandboxClassLoader.class.getClassLoader().getResource(name);
  }

  static boolean runtimeFirst(String classResName) {
    for (String prefix : RUNTIME_FIRST) {
      if (classResName.startsWith(prefix)) return true;
    }
    return false;
  }
}
