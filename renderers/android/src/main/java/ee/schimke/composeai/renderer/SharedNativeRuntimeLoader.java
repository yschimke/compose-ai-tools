package ee.schimke.composeai.renderer;

import static android.os.Build.VERSION_CODES.O;
import static android.os.Build.VERSION_CODES.P;
import static android.os.Build.VERSION_CODES.VANILLA_ICE_CREAM;

import android.os.Build;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Priority;
import org.robolectric.nativeruntime.DefaultNativeRuntimeLoader;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.OsUtil;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.TempDirectory;

/**
 * Robolectric native-runtime loader that extracts one immutable, versioned copy for all JVMs.
 *
 * <p>Robolectric's default loader creates a fresh {@code robolectric-nativeruntime*} directory for
 * every process. Gradle can stop test workers before Robolectric's deletion hook completes, so a
 * fork-heavy render session leaks roughly 200 MB per JVM. This loader is selected through
 * Robolectric's {@code NativeRuntimeLoader} service and uses a file lock plus a completion marker
 * to initialize one SDK/platform/version-specific cache directory. Every process then loads the
 * same read-only data files from that directory. Each Robolectric sandbox loads a short-lived copy
 * of only the native library because both the JVM and the platform loader associate a loaded image
 * with one classloader; the roughly 185 MB of fonts, ICU, and hyphenation data remains shared.
 *
 * <p>The extraction calls are deliberately reflective because Robolectric keeps them private. The
 * project pins Robolectric and the method signatures are covered by native-render tests; a future
 * Robolectric change fails loudly during cache initialization rather than silently falling back to
 * per-process extraction.
 */
@Priority(0)
public final class SharedNativeRuntimeLoader extends DefaultNativeRuntimeLoader {
  public static final String CACHE_DIR_PROPERTY =
      "composeai.robolectric.nativeRuntimeCacheDir";

  private static final String COMPLETE_MARKER = ".complete";
  private static final String HYPHEN_DATA_DIR = "hyphen-data";
  private static final String SANDBOX_LIBRARY_DIR = "sandbox-libraries";
  private static final String PROCESS_DIR_PREFIX = "jvm-";

  /**
   * Identifies the directory this loader stages sandbox library copies in.
   *
   * <p>Deliberately a static field — see {@link #sandboxLibraryDirectory} for why nothing a child
   * process can inherit will do.
   */
  private static final String OWNER_TOKEN = UUID.randomUUID().toString().replace("-", "");

  /**
   * Registers a path for best-effort deletion at JVM exit.
   *
   * <p>An indirection so {@code SharedNativeRuntimeLoaderTest} can pin <em>which</em> paths a
   * render JVM claims. Registering anything shared between render JVMs — the {@code
   * sandbox-libraries/} key directory in particular — is the regression this seam exists to catch;
   * see {@link #sandboxLibraryDirectory}.
   *
   * <p>Visible for testing.
   */
  static java.util.function.Consumer<Path> deleteAtExit = path -> path.toFile().deleteOnExit();

  /** Serializes cache initialization for every sandbox that shares this class — see below. */
  private static final Object EXTRACTION_MONITOR = new Object();

  private static final int LOCK_RETRY_LIMIT = 600;
  private static final long LOCK_RETRY_MILLIS = 100L;

  @Override
  public synchronized void ensureLoaded() {
    if (loaded.get()) {
      return;
    }

    try {
      Path cacheDirectory = ensureSharedExtraction();
      configureDataProperties(cacheDirectory);

      Map<String, String> originalProperties = new HashMap<>();
      if (isAndroidVOrGreater()) {
        originalProperties = saveRegistrationProperties();
        System.setProperty("use_base_native_hostruntime", "true");
        System.setProperty("core_native_classes", String.join(",", getCoreClassNatives()));
        System.setProperty("graphics_native_classes", String.join(",", getGraphicsNatives()));
        System.setProperty("method_binding_format", METHOD_BINDING_FORMAT);
      }
      Path sandboxLibrary = createSandboxLibraryCopy(cacheDirectory);
      try {
        System.load(sandboxLibrary.toAbsolutePath().toString());
      } finally {
        restoreRegistrationProperties(originalProperties);
        deleteSandboxLibraryCopy(sandboxLibrary);
      }

      String hyphenDataDir = cacheDirectory.resolve(HYPHEN_DATA_DIR).toAbsolutePath().toString();
      if (isAndroidVOrGreater()) {
        invokeDeferredStaticInitializers();
        setNativeSystemProperty("ro.hyphen.data.dir", hyphenDataDir);
        ReflectionHelpers.callStaticMethod(
            Shadow.class.getClassLoader(),
            "android.graphics.Typeface",
            "loadPreinstalledSystemFontMap");
      } else {
        System.setProperty("hyphen.data.dir", hyphenDataDir);
      }
      if (Build.VERSION.SDK_INT >= P) {
        ReflectionHelpers.callStaticMethod(
            Shadow.class.getClassLoader(), "android.text.Hyphenator", "init");
      }
      loaded.set(true);
    } catch (IOException | ReflectiveOperationException e) {
      loaded.set(false);
      // Name the cache root and the cause inline: Gradle's test report shows the failure line
      // without the cause chain, which left issue #3754 diagnosable only by guesswork.
      throw new AssertionError(
          "Unable to load shared Robolectric native runtime library from " + cacheRoot() + ": " + e,
          e);
    }
  }

  private Path ensureSharedExtraction() throws IOException, ReflectiveOperationException {
    Path root = cacheRoot();
    Files.createDirectories(root);
    String key = cacheKey();
    Path destination = root.resolve(key);
    Path lockPath = root.resolve(key + ".lock");

    // Warm-cache fast path, deliberately outside the lock. The cache directory only ever appears
    // via the atomic move below, so a complete marker means a fully published directory — there is
    // nothing to serialize against. This is the overwhelmingly common case (every sandbox after the
    // first, in every process after the first), and taking a file lock for it is what made the
    // intra-JVM overlap below reachable at all.
    if (isComplete(destination)) {
      return destination;
    }

    return withExclusiveCacheLock(lockPath, () -> extractUnderLock(destination, key, root));
  }

  /** Work that runs while this JVM holds the cache lock exclusively. */
  @FunctionalInterface
  interface CacheWork<T> {
    T run() throws IOException, ReflectiveOperationException;
  }

  /**
   * Run {@code body} while holding {@code lockPath} against both other processes and other
   * sandboxes in this one.
   *
   * <p>A {@link FileLock} belongs to the JVM, not to the thread or the classloader: a second
   * sandbox locking the same region in the same process gets {@link OverlappingFileLockException}
   * rather than blocking, and that is an unchecked exception the caller's {@code ensureLoaded}
   * catch does not cover. Robolectric builds a classloader per sandbox, so {@code synchronized} on
   * a field of this class only covers sandboxes that happen to share our copy of it — the retry is
   * what makes the cross-classloader case correct, and the monitor is what stops the common
   * same-classloader case from spinning to get there.
   *
   * <p>Visible for testing.
   */
  static <T> T withExclusiveCacheLock(Path lockPath, CacheWork<T> body)
      throws IOException, ReflectiveOperationException {
    synchronized (EXTRACTION_MONITOR) {
      for (int attempt = 0; ; attempt++) {
        try (FileChannel channel =
                FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock ignored = channel.lock()) {
          return body.run();
        } catch (OverlappingFileLockException contended) {
          if (attempt >= LOCK_RETRY_LIMIT) {
            throw new IOException(
                "Another sandbox in this JVM held "
                    + lockPath
                    + " for over "
                    + (LOCK_RETRY_LIMIT * LOCK_RETRY_MILLIS)
                    + "ms",
                contended);
          }
          try {
            Thread.sleep(LOCK_RETRY_MILLIS);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted waiting for " + lockPath, interrupted);
          }
        }
      }
    }
  }

  private Path extractUnderLock(Path destination, String key, Path root)
      throws IOException, ReflectiveOperationException {
    if (isComplete(destination)) {
      return destination;
    }

    deleteRecursively(destination);
    Path staging = root.resolve(key + ".staging");
    deleteRecursively(staging);
    Files.createDirectories(staging);
    try {
      SharedTempDirectory extraction = new SharedTempDirectory(staging);
      if (Build.VERSION.SDK_INT >= O) {
        invokeExtraction("maybeCopyFonts", extraction);
        invokeExtraction("maybeCopyHyphenData", extraction);
      }
      invokeExtraction("maybeCopyIcuData", extraction);
      maybeCopyExtraResources(extraction);
      copyNativeLibrary(staging);
      Files.writeString(staging.resolve(COMPLETE_MARKER), key, StandardCharsets.UTF_8);
      try {
        Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
      } catch (java.nio.file.AtomicMoveNotSupportedException ignoredMove) {
        Files.move(staging, destination);
      }
    } catch (IOException | ReflectiveOperationException | RuntimeException e) {
      deleteRecursively(staging);
      throw e;
    }
    return destination;
  }

  private static boolean isComplete(Path directory) {
    return Files.isRegularFile(directory.resolve(COMPLETE_MARKER))
        && Files.isRegularFile(directory.resolve(libraryName()));
  }

  private void invokeExtraction(String methodName, TempDirectory directory)
      throws ReflectiveOperationException, IOException {
    Method method =
        DefaultNativeRuntimeLoader.class.getDeclaredMethod(methodName, TempDirectory.class);
    method.setAccessible(true);
    try {
      method.invoke(this, directory);
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause();
      if (cause instanceof IOException) {
        throw (IOException) cause;
      }
      if (cause instanceof RuntimeException) {
        throw (RuntimeException) cause;
      }
      throw e;
    }
  }

  private static void copyNativeLibrary(Path directory) throws IOException {
    URL resource =
        SharedNativeRuntimeLoader.class.getClassLoader().getResource(nativeLibraryResourcePath());
    if (resource == null) {
      throw new IOException("Missing Robolectric native library " + nativeLibraryResourcePath());
    }
    try (java.io.InputStream input = resource.openStream()) {
      Files.copy(input, directory.resolve(libraryName()), StandardCopyOption.REPLACE_EXISTING);
    }
  }

  /**
   * Gives each Robolectric sandbox a distinct native-library image while sharing the large data
   * payload.
   *
   * <p>{@link System#load(String)} associates an absolute pathname with the initiating
   * classloader. Robolectric creates multiple sandbox classloaders in one test worker, so loading
   * the cache pathname directly fails after the first sandbox with "already loaded in another
   * classloader". A hard link bypasses that pathname check but is still one image to the platform
   * dynamic loader, preventing JNI registration for later sandboxes. Copying only the native
   * library gives every sandbox a distinct image; fonts, ICU, and hyphenation data continue to use
   * the immutable shared cache directly.
   */
  static Path createSandboxLibraryCopy(Path cacheDirectory) throws IOException {
    Path source = cacheDirectory.resolve(libraryName());
    Path copies = sandboxLibraryDirectory(cacheDirectory);

    Path copy;
    try {
      copy = Files.createTempFile(copies, "sandbox-", "-" + libraryName());
    } catch (NoSuchFileException reaped) {
      // Only an outside cleaner (tmpreaper, a wiped cache root) can get here now that the
      // directory is process-private. Recreating it is safe: nothing else in the tree points at it.
      Files.createDirectories(copies);
      copy = Files.createTempFile(copies, "sandbox-", "-" + libraryName());
    }
    Files.copy(source, copy, StandardCopyOption.REPLACE_EXISTING);
    deleteAtExit.accept(copy);
    return copy;
  }

  /**
   * Returns this JVM's private directory for sandbox library copies, creating it on first use.
   *
   * <p>The directory is private to this JVM because it is the one part of the cache written outside
   * the extraction lock, and every render JVM registers what it creates for deletion at exit. A
   * directory shared across JVMs therefore races: each JVM unlinks its own copy as soon as {@code
   * System.load} returns, so the shared directory is usually <em>empty</em> when a worker exits and
   * its {@link java.io.File#deleteOnExit()} hook succeeds in removing it — out from under a
   * concurrent worker that has already created it and is about to add its copy. That worker's
   * {@code createTempFile} then fails with {@link NoSuchFileException} and the render dies with
   * "Unable to load shared Robolectric native runtime library" (issue #3754). Gradle retires and
   * forks test workers throughout a render session, so the window opens constantly; the failure is
   * unreachable with a single worker, which is why it only ever surfaced as a concurrency flake.
   *
   * <p>Per-JVM directories close that window without reintroducing the disk leak this class exists
   * to fix (issue #3281): no JVM ever deletes a path another JVM depends on, the copies inside stay
   * the roughly 10 MB library rather than the full runtime payload, and a normal exit now removes
   * the directory as well as its contents. A worker killed hard enough to skip shutdown hooks
   * leaves its directory behind, exactly as it already left copies behind in the shared one.
   *
   * <p>{@link #OWNER_TOKEN} must stay a static field rather than something reachable from outside
   * the process. A system property looks like the tidier home for it — it would give every
   * Robolectric sandbox classloader in a JVM one shared directory instead of one apiece — but the
   * daemon's {@code SandboxProcessPool} forwards every {@code composeai.*} property to the worker
   * JVMs it spawns, and {@code RobolectricHost.start()} boots its in-process slot 0 (populating the
   * property) before spawning them. Every worker would inherit the parent's token and stage its
   * copies in the parent's directory, putting several processes back in one directory and
   * reintroducing exactly the race above. A static cannot cross a process boundary, so a child
   * always generates its own. The cost is a directory per sandbox classloader rather than per JVM,
   * which is a handful of empty directories that exit deletion then removes.
   */
  private static Path sandboxLibraryDirectory(Path cacheDirectory) throws IOException {
    Path owned =
        cacheRoot()
            .resolve(SANDBOX_LIBRARY_DIR)
            .resolve(cacheDirectory.getFileName())
            .resolve(PROCESS_DIR_PREFIX + OWNER_TOKEN);
    if (Files.isDirectory(owned)) {
      return owned;
    }
    Files.createDirectories(owned);
    deleteAtExit.accept(owned);
    return owned;
  }

  private static void deleteSandboxLibraryCopy(Path copy) {
    try {
      Files.deleteIfExists(copy);
    } catch (IOException stillLoaded) {
      // Windows does not allow an in-use DLL to be unlinked. deleteOnExit handles the normal JVM
      // shutdown path. Even there, each copy is only the native library rather than the full
      // runtime data set.
    }
  }

  private static void configureDataProperties(Path directory) throws IOException {
    Path fonts = directory.resolve("fonts");
    if (Files.isDirectory(fonts)) {
      System.setProperty(
          "robolectric.nativeruntime.fontdir", fonts.toAbsolutePath() + java.io.File.separator);
    }

    Path icu = directory.resolve("icu");
    if (Files.isDirectory(icu)) {
      try (java.util.stream.Stream<Path> files = Files.list(icu)) {
        Path data =
            files.filter(Files::isRegularFile)
                .findFirst()
                .orElseThrow(() -> new IOException("Shared native runtime cache has no ICU data"));
        System.setProperty("icu.data.path", data.toAbsolutePath().toString());
        System.setProperty("icu.locale.default", Locale.getDefault().toLanguageTag());
      }
    }
  }

  static Path cacheRoot() {
    String configured = System.getProperty(CACHE_DIR_PROPERTY);
    if (configured != null && !configured.isBlank()) {
      return Path.of(configured);
    }
    String xdg = System.getenv("XDG_CACHE_HOME");
    Path base =
        xdg != null && !xdg.isBlank()
            ? Path.of(xdg)
            : Path.of(System.getProperty("user.home"), ".cache");
    return base.resolve("composeai").resolve("robolectric-native");
  }

  static String cacheKey() {
    // CodeSource is null for Robolectric-instrumented classes. Resource URLs retain the owning JAR
    // coordinates, so hashing the native binary plus android-all locations gives a stable local
    // identity that changes whenever either half of the extracted payload changes.
    String identity =
        resourceIdentity(nativeLibraryResourcePath()) + "\n" + resourceIdentity("build.prop");
    return "runtime-"
        + sha256Prefix(identity)
        + "-sdk"
        + Build.VERSION.SDK_INT
        + "-"
        + osName()
        + "-"
        + architecture();
  }

  private static String resourceIdentity(String path) {
    URL resource = SharedNativeRuntimeLoader.class.getClassLoader().getResource(path);
    return resource == null ? "missing:" + path : resource.toExternalForm();
  }

  private static String sha256Prefix(String value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder result = new StringBuilder(16);
      for (int i = 0; i < 8; i++) {
        result.append(String.format(Locale.US, "%02x", digest[i] & 0xff));
      }
      return result.toString();
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError("JVM has no SHA-256 implementation", impossible);
    }
  }

  private static String nativeLibraryResourcePath() {
    return "native/" + osName() + "/" + architecture() + "/" + libraryName();
  }

  private static String osName() {
    if (OsUtil.isLinux()) return "linux";
    if (OsUtil.isMac()) return "mac";
    if (OsUtil.isWindows()) return "windows";
    return "unknown";
  }

  private static String architecture() {
    String architecture = System.getProperty("os.arch").toLowerCase(Locale.US);
    return architecture.equals("amd64") ? "x86_64" : architecture;
  }

  private static boolean isAndroidVOrGreater() {
    return Build.VERSION.SDK_INT >= VANILLA_ICE_CREAM;
  }

  private static Map<String, String> saveRegistrationProperties() {
    Map<String, String> values = new HashMap<>();
    for (String key :
        new String[] {
          "use_base_native_hostruntime",
          "core_native_classes",
          "graphics_native_classes",
          "method_binding_format"
        }) {
      values.put(key, System.getProperty(key));
    }
    return values;
  }

  private static void restoreRegistrationProperties(Map<String, String> values) {
    for (Map.Entry<String, String> entry : values.entrySet()) {
      if (entry.getValue() == null) {
        System.clearProperty(entry.getKey());
      } else {
        System.setProperty(entry.getKey(), entry.getValue());
      }
    }
  }

  private static void deleteRecursively(Path path) throws IOException {
    if (!Files.exists(path)) return;
    Files.walkFileTree(
        path,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Files.deleteIfExists(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path directory, IOException error)
              throws IOException {
            if (error != null) throw error;
            Files.deleteIfExists(directory);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  /** TempDirectory adapter used only while the cache is initialized under the inter-process lock. */
  private static final class SharedTempDirectory extends TempDirectory {
    private final Path basePath;

    SharedTempDirectory(Path basePath) {
      super("nativeruntime-bootstrap");
      this.basePath = basePath;
    }

    @Override
    public Path getBasePath() {
      return basePath;
    }

    @Override
    public Path create(String name) {
      try {
        return Files.createDirectory(basePath.resolve(name));
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public Path createIfNotExists(String name) {
      try {
        return Files.createDirectories(basePath.resolve(name));
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public Path createFile(String name, String contents) {
      try {
        return Files.writeString(basePath.resolve(name), contents, StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public void destroy() {
      // The shared cache is immutable after its completion marker is published.
    }
  }
}
