package ee.schimke.composeai.daemon

import java.lang.ref.WeakReference
import java.net.URL
import java.net.URLClassLoader

/**
 * Owns the disposable child [URLClassLoader] for the user module's compiled classes — the
 * implementation seam for the [B2.0 disposable-user-classloader design](
 * ../../../../../../docs/daemon/CLASSLOADER.md).
 *
 * The daemon's parent classloader is long-lived (Robolectric `InstrumentingClassLoader` on Android,
 * the JVM app classloader on desktop) and pays the multi-second bootstrap cost once at daemon
 * spawn. The user's `build/intermediates/built_in_kotlinc/<variant>/classes/` directory is
 * **excluded** from the parent's classpath; the [currentChildLoader] reads those URLs instead with
 * the parent loader as its delegate. On `fileChanged({ kind: "source" })` the [JsonRpcServer]
 * invokes [swap], which drops the strong reference to the current child loader and lazily allocates
 * a fresh one on next read — the next render then sees the recompiled bytecode rather than the
 * cached `Class<?>`.
 *
 * Per [Decision 2](../../../../../../docs/daemon/CLASSLOADER.md#decisions-made) this lives in
 * `:daemon:core`. It's renderer-agnostic: a `URLClassLoader` lifecycle holder doesn't touch
 * Compose, Robolectric, or any backend specifics. Both [RenderHost] implementations (`DesktopHost`
 * and `RobolectricHost`) construct one against the user-class-dirs sysprop wired by the gradle
 * plugin's launch descriptor.
 *
 * **Thread-safety.** The holder is read by the render thread and mutated by the JSON-RPC read
 * thread (when `handleFileChanged` arrives). All access goes through `synchronized(this)` — the
 * critical section is tiny (single field write) and there's no hot path that takes the lock per
 * render.
 *
 * **No-mid-render-cancellation invariant.** [swap] is a queue-time event, not a preemption: the
 * `JsonRpcServer.handleFileChanged` path drops the current loader strong reference but the
 * in-flight render keeps using the loader it already resolved its `Class<?>` against (the JVM holds
 * a strong reference for the duration of the reflection call).
 *
 * **Soak-leak detection.** Each allocated child loader is also tracked via a [WeakReference] in
 * [trackedLoaders]; [liveLoaderCount] forces 2 GCs and returns the surviving count, used by the
 * unit/integration soak loop to assert that recycled loaders collect within 2 GCs (CLASSLOADER.md §
 * Risks 1).
 *
 * @param urls user-class directories the child loader exposes. Mutating the list after construction
 *   has no effect; [swap] re-reads the same URLs every time.
 * @param parentSupplier function returning the parent classloader the child delegates to.
 *   **Evaluated lazily at allocation time**, not at construction. This is load-bearing for the
 *   Android backend: the holder is constructed on the host thread (where
 *   `Thread.currentThread().contextClassLoader` is the JVM app loader), but the URLClassLoader must
 *   inherit the **sandbox classloader** as its parent — otherwise framework classes (Compose
 *   runtime, Robolectric internals) load via the app loader instead of the instrumented sandbox
 *   loader, and `getDeclaredComposableMethod` fails on classloader-identity skew
 *   (forensics-confirmed, see
 *   [`docs/daemon/classloader-forensics-diff.md`](../../../../../../docs/daemon/classloader-forensics-diff.md)).
 *   Android's `DaemonMain` passes a supplier that reads `DaemonHostBridge.sandboxClassLoaderRef`,
 *   set inside the sandbox by `SandboxHoldingRunner.holdSandboxOpen`. Desktop's default supplier
 *   resolves to the JVM app loader, which is the right parent there.
 * @param onSwap optional callback invoked synchronously after [swap] (and the initial allocation)
 *   with the new loader. The Android backend uses it to mirror the loader into
 *   `DaemonHostBridge.currentChildLoader` so the sandbox-side render thread sees the swap.
 */
class UserClassLoaderHolder(
  private val urls: List<URL>,
  private val parentSupplier: () -> ClassLoader = {
    Thread.currentThread().contextClassLoader ?: ClassLoader.getSystemClassLoader()
  },
  private val onSwap: ((URLClassLoader) -> Unit)? = null,
) {

  private val lock = Any()
  private var current: URLClassLoader? = null
  private val trackedLoaders: MutableList<WeakReference<URLClassLoader>> = mutableListOf()

  /**
   * Returns the current child [URLClassLoader], allocating it lazily on first read. Subsequent
   * reads return the same instance until [swap] is invoked.
   */
  fun currentChildLoader(): URLClassLoader =
    synchronized(lock) {
      val existing = current
      if (existing != null) return@synchronized existing
      allocateLocked()
    }

  /**
   * Drops the strong reference to the current child loader. The next [currentChildLoader] read
   * lazily allocates a fresh one with the same URLs. Old loader becomes GC-able once any
   * sandbox/Compose state holding references to user-class-loaded objects is cleared (per-render
   * scoping verified in CLASSLOADER.md § Risks 1).
   */
  fun swap() {
    synchronized(lock) {
      current = null
      // Self-diagnostic log — surfaces as `[daemon stderr] [classloader] swap …` in the VS Code
      // extension's Compose Preview output channel. Pairs with the `allocate` line emitted on the
      // next currentChildLoader() read; if a save loop produces "swap" but never the matching
      // "allocate" the host's render thread isn't picking up the swap, and if "allocate" fires but
      // the .class fingerprint doesn't move across saves the disk hasn't actually been recompiled.
      System.err.println(
        "compose-ai-daemon: [classloader] swap requested urlCount=${urls.size} liveLoaders=${trackedLoaders.size}"
      )
      // Force the next currentChildLoader read to do the allocation; we deliberately don't
      // pre-allocate here because the next render is what cares, and pre-allocating would
      // double the loader objects in flight when fileChanged arrives faster than renders.
    }
  }

  /**
   * Returns the URL list this holder uses. Exposed so backends can construct identically-shaped
   * sibling loaders (e.g. the Robolectric bridge mirror).
   */
  fun urls(): List<URL> = urls.toList()

  /**
   * Forces 2 GCs and returns the count of allocated child loaders that haven't yet been collected.
   * The current loader (if any) is always counted; legacy swapped-out loaders should bring the
   * count back to 1 (or 0 if no allocation has happened).
   *
   * Used by the soak `WeakReference` probe in the unit test.
   */
  fun liveLoaderCount(): Int {
    repeat(2) {
      System.gc()
      // Hint the runtime that finalizers should run; not guaranteed but doesn't hurt.
      try {
        Thread.sleep(20)
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
      }
    }
    synchronized(lock) {
      // Prune cleared references in-place so the count is accurate and the list doesn't grow
      // unboundedly across long-running soak loops.
      trackedLoaders.removeAll { it.get() == null }
      return trackedLoaders.size
    }
  }

  private fun allocateLocked(): URLClassLoader {
    val resolvedParent = parentSupplier()
    val fresh = ChildFirstURLClassLoader(urls.toTypedArray(), resolvedParent)
    current = fresh
    trackedLoaders.add(WeakReference(fresh))
    // Self-diagnostic log — pairs with `swap requested` above. Surfaces the URL list so we can see
    // exactly which directories the next render's findClass walks. The `urlsSummary` helper
    // reports the directory mtime for each entry; an mtime that doesn't advance across saves means
    // `compileKotlin` didn't actually rewrite anything (Gradle up-to-date, no-op edit, etc.).
    System.err.println(
      "compose-ai-daemon: [classloader] allocate child loader parent=${resolvedParent.javaClass.name} " +
        "loaderId=${System.identityHashCode(fresh).toString(16)} urls=${urlsSummary(urls)}"
    )
    onSwap?.invoke(fresh)
    return fresh
  }

  /**
   * Child-first [URLClassLoader] — overrides the JVM's default parent-first delegation so user
   * classes are resolved against the child's URLs even when the same FQN is also reachable via the
   * parent classpath. Required because the gradle plugin doesn't partition user-class-dirs out of
   * the parent's `-cp` in B2.0's v1 (the daemon's launch descriptor still puts everything on a
   * single classpath); without child-first the parent would happily resolve `Foo.kt`'s old bytes
   * before the child got a chance.
   *
   * **Framework classes still resolve against the parent.** When the child's own URLs don't carry a
   * class (e.g. `androidx.compose.foundation.*`, `kotlin.*`, anything not under the user's
   * `build/intermediates/...`), the child falls through to the parent. Per CLASSLOADER.md the
   * Compose runtime, AndroidX, and the daemon's helpers stay on the parent.
   *
   * **Loaded-class cache discipline.** The JVM's `findLoadedClass` is checked first so a user class
   * loaded via the child stays the same `Class<?>` instance for repeated lookups within the
   * loader's lifetime — only [swap] rotates it.
   */
  private class ChildFirstURLClassLoader(urls: Array<URL>, parent: ClassLoader) :
    URLClassLoader(urls, parent) {

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
      synchronized(getClassLoadingLock(name)) {
        // Already cached on this loader? Return it.
        val cached = findLoadedClass(name)
        if (cached != null) {
          if (resolve) resolveClass(cached)
          return cached
        }
        // System / JDK classes always go through the parent — punching holes here would break the
        // JVM's bootstrap invariants. `java.*`, `javax.*`, `sun.*`, `jdk.*`, etc.
        if (
          name.startsWith("java.") ||
            name.startsWith("javax.") ||
            name.startsWith("sun.") ||
            name.startsWith("jdk.") ||
            name.startsWith("kotlin.") ||
            name.startsWith("kotlinx.") ||
            name.startsWith("androidx.") ||
            name.startsWith("android.") ||
            name.startsWith("org.robolectric.") ||
            name.startsWith("com.github.takahirom.roborazzi.") ||
            name.startsWith("org.jetbrains.skia.") ||
            name.startsWith("ee.schimke.composeai.daemon.")
        ) {
          return super.loadClass(name, resolve)
        }
        // Try our own URLs first (child-first); fall back to parent if not present locally.
        return try {
          val found = findClass(name)
          if (resolve) resolveClass(found)
          found
        } catch (_: ClassNotFoundException) {
          super.loadClass(name, resolve)
        }
      }
    }
  }

  companion object {
    /**
     * Sysprop name. Colon-delimited (`File.pathSeparator`) absolute paths to user-class directories
     * — the gradle plugin's daemon launch descriptor sets it; both backends' [DaemonMain] reads it
     * and constructs a [UserClassLoaderHolder] with the resolved URLs.
     */
    const val USER_CLASS_DIRS_PROP: String = "composeai.daemon.userClassDirs"

    /**
     * Resolves [USER_CLASS_DIRS_PROP] into a list of [URL]s, dropping entries that don't exist on
     * disk and **ordering directories before jars**. Returns an empty list if the sysprop is unset.
     *
     * **Why directories first.** AGP's variant classpath surfaces both the kotlinc output directory
     * (`build/intermediates/built_in_kotlinc/<variant>/compileDebugKotlin/classes/`) and the
     * runtime-bundled jar (`build/intermediates/runtime_app_classes_jar/<variant>/.../classes.jar`)
     * for the same set of user classes. The kotlinc directory is rewritten by `compileDebugKotlin`
     * — an upstream of every save's `discoverPreviews` — so it carries the fresh bytecode. The
     * runtime jar is rewritten by `bundleDebugClassesToRuntimeJar`, which is **not** on the
     * `discoverPreviews` task graph; it only runs as part of `composePreviewDaemonStart` (once at
     * bootstrap) and the unit-test packaging path. After the first save the jar is therefore stale
     * relative to the `.class` directory.
     *
     * `URLClassLoader.findClass` walks URLs in declaration order and returns the first match. AGP's
     * natural ordering puts the runtime jar before the kotlinc directory; without this sort the
     * daemon resolves every user class out of the stale jar and the freshly-recompiled directory is
     * never read — the "first edit updates, subsequent edits stick" symptom that the cancellation
     * hole / classloader-swap diagnostics couldn't explain on their own. Moving directories to the
     * front lets the kotlinc output win for any class it carries; bundled jars stay on the path as
     * a fallback for kapt/ksp-generated classes and the AGP `R.jar`.
     *
     * Sort is stable (`sortedBy` uses TimSort), so directories among themselves and jars among
     * themselves keep their relative order — important because AGP's main-vs-test classpath
     * ordering carries semantics we don't want to scramble.
     */
    fun urlsFromSysprop(): List<URL> {
      val raw = System.getProperty(USER_CLASS_DIRS_PROP) ?: return emptyList()
      if (raw.isBlank()) return emptyList()
      val files =
        raw
          .split(java.io.File.pathSeparator)
          .map { it.trim() }
          .filter { it.isNotEmpty() }
          .map { java.io.File(it) }
          .filter { it.exists() }
      return files.sortedBy { if (it.isDirectory) 0 else 1 }.map { it.toURI().toURL() }
    }

    /**
     * Compact one-line dump of [urls] suitable for stderr — each entry is `<path>(mtime=…)` so the
     * directory's most-recent write is visible at a glance. Only `file:` URLs are statted; other
     * schemes are reported by URL string only.
     */
    internal fun urlsSummary(urls: List<URL>): String =
      urls.joinToString(prefix = "[", postfix = "]") { url ->
        if (url.protocol == "file") {
          val file = java.io.File(url.toURI())
          val mtime =
            if (file.exists()) java.time.Instant.ofEpochMilli(file.lastModified()).toString()
            else "missing"
          "${file.absolutePath}(mtime=$mtime)"
        } else {
          url.toString()
        }
      }

    /**
     * Resolves [className] to its on-disk `.class` file via [loader]'s resource lookup and returns
     * a compact `path=… mtime=… size=… sha=…` string. Returns `null` if the class isn't on a
     * `file:` URL (jar entry, network, missing) — we only fingerprint disk-backed user classes
     * since those are the ones that should change across save → recompile cycles.
     *
     * The SHA is the first 6 bytes of SHA-256 hex (12 chars) — enough to disambiguate consecutive
     * recompiles in a save loop without bloating each log line.
     */
    fun classFileFingerprint(loader: ClassLoader, className: String): String? {
      val resourceName = className.replace('.', '/') + ".class"
      if (loader is URLClassLoader) {
        fingerprintFromUrls(loader.urLs, resourceName)?.let {
          return it
        }
      }
      val url = loader.getResource(resourceName) ?: return null
      if (url.protocol != "file") return null
      val file =
        try {
          java.io.File(url.toURI())
        } catch (_: Throwable) {
          return null
        }
      if (!file.exists()) return null
      val mtime = java.time.Instant.ofEpochMilli(file.lastModified()).toString()
      return "path=${file.absolutePath} mtime=$mtime size=${file.length()} sha=${sha256Short(file)}"
    }

    private fun fingerprintFromUrls(urls: Array<URL>, resourceName: String): String? {
      for (url in urls) {
        if (url.protocol != "file") continue
        val root =
          try {
            java.io.File(url.toURI())
          } catch (_: Throwable) {
            continue
          }
        if (root.isDirectory) {
          val file = root.resolve(resourceName)
          if (file.exists()) {
            val mtime = java.time.Instant.ofEpochMilli(file.lastModified()).toString()
            return "path=${file.absolutePath} mtime=$mtime size=${file.length()} sha=${sha256Short(file)}"
          }
        } else if (root.isFile) {
          val hasEntry =
            try {
              java.util.jar.JarFile(root).use { jar -> jar.getEntry(resourceName) != null }
            } catch (_: Throwable) {
              false
            }
          if (hasEntry) {
            val mtime = java.time.Instant.ofEpochMilli(root.lastModified()).toString()
            return "jar=${root.absolutePath}!/$resourceName mtime=$mtime size=${root.length()} sha=${sha256Short(root)}"
          }
        }
      }
      return null
    }

    private fun sha256Short(file: java.io.File): String {
      val md = java.security.MessageDigest.getInstance("SHA-256")
      try {
        file.inputStream().use { input ->
          val buffer = ByteArray(8192)
          while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            md.update(buffer, 0, read)
          }
        }
      } catch (_: Throwable) {
        return "unreadable"
      }
      return md.digest().take(6).joinToString("") { "%02x".format(it) }
    }
  }
}
