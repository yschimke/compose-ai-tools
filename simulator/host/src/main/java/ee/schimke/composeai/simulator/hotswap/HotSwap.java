package ee.schimke.composeai.simulator.hotswap;

import java.io.File;
import java.io.IOException;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.robolectric.internal.bytecode.ClassDetails;
import org.robolectric.internal.bytecode.InstrumentationConfiguration;

/**
 * A reload's changed classes, redefined in the running simulator instead of restarting it.
 *
 * <p>One copy for the whole JVM: {@code SimulatorSandbox} keeps this package out of the sandbox, as
 * it does {@code net.}. The JVM's {@code -javaagent} (this jar) hands over the {@link
 * Instrumentation} at start, the sandbox class loader registers itself when it is built, and the
 * sandbox's {@code Session} drives a swap from the main thread.
 *
 * <p>A swap is all or nothing. Everything that could make it fail is checked before any class
 * changes, and {@link Instrumentation#redefineClasses} itself is atomic: when the JVM refuses one
 * class (a method or field added or removed, a signature or modifier changed) it changes none. The
 * caller restarts the app instead, as a reload did before.
 */
public final class HotSwap {

  private static volatile Instrumentation instrumentation;
  private static volatile ClassLoader sandbox;
  private static volatile InstrumentationConfiguration configuration;
  /** The last swapped reload's classes, read ahead of the launch's for classes not loaded yet. */
  private static volatile File overlay;

  private HotSwap() {}

  /** The {@code -javaagent} entry point: keeps the JVM's instrumentation for {@link #redefine}. */
  public static void premain(String arguments, Instrumentation inst) {
    instrumentation = inst;
  }

  /** The sandbox class loader, and the instrumentation choices it applies, as it is built. */
  public static void register(ClassLoader loader, InstrumentationConfiguration config) {
    sandbox = loader;
    configuration = config;
  }

  /**
   * The class file the last swap delivered for {@code resourceName} ({@code com/example/Foo.class}),
   * or null. A class the app had not loaded when it was swapped loads from here later, not from the
   * launch's older copy.
   */
  public static File overlayFile(String resourceName) {
    File dir = overlay;
    if (dir == null || !resourceName.endsWith(".class")) return null;
    File file = new File(dir, resourceName);
    return file.isFile() ? file : null;
  }

  /** Why a swap was not applied; nothing changed. */
  public static final class Refused extends Exception {
    public Refused(String message) {
      super(message);
    }
  }

  /**
   * Redefines each of {@code classes} (internal names, {@code com/example/Foo}) that the sandbox has
   * loaded with its class file under {@code dir}, and serves {@code dir} first from then on.
   *
   * @return how many loaded classes were redefined
   * @throws Refused when the swap cannot be applied whole; no class has changed
   */
  public static int redefine(File dir, List<String> classes) throws Refused {
    Instrumentation inst = instrumentation;
    if (inst == null) throw new Refused("the simulator was started without its agent");
    if (!inst.isRedefineClassesSupported()) throw new Refused("this JVM cannot redefine classes");
    ClassLoader loader = sandbox;
    InstrumentationConfiguration config = configuration;
    if (loader == null || config == null) throw new Refused("no sandbox class loader is registered");

    Map<String, Class<?>> loaded = new HashMap<>();
    for (Class<?> type : inst.getAllLoadedClasses()) {
      if (type.getClassLoader() == loader) loaded.put(type.getName(), type);
    }
    List<ClassDefinition> definitions = new ArrayList<>();
    for (String name : classes) {
      File file = new File(dir, name + ".class");
      byte[] bytes;
      try {
        bytes = Files.readAllBytes(file.toPath());
      } catch (IOException e) {
        throw new Refused("cannot read " + file + ": " + e.getMessage());
      }
      // The sandbox defines an instrumented class from rewritten bytes; plain compiler output
      // would not match what it loaded.
      if (config.shouldInstrument(new ClassDetails(bytes))) {
        throw new Refused(name + " is instrumented by Robolectric");
      }
      Class<?> type = loaded.get(name.replace('/', '.'));
      if (type == null) continue; // Not loaded yet: it loads from dir.
      if (!inst.isModifiableClass(type)) throw new Refused(name + " cannot be redefined");
      definitions.add(new ClassDefinition(type, bytes));
    }
    try {
      inst.redefineClasses(definitions.toArray(new ClassDefinition[0]));
    } catch (ClassNotFoundException
        | UnmodifiableClassException
        | UnsupportedOperationException
        | LinkageError e) {
      throw new Refused("the JVM refused the new classes: " + e);
    }
    overlay = dir;
    return definitions.size();
  }
}
