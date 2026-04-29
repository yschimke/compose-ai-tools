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
 * `:daemon:core`. It's renderer-agnostic: a `URLClassLoader` lifecycle holder doesn't
 * touch Compose, Robolectric, or any backend specifics. Both [RenderHost] implementations
 * (`DesktopHost` and `RobolectricHost`) construct one against the user-class-dirs sysprop wired by
 * the gradle plugin's launch descriptor.
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
     * disk. Returns an empty list if the sysprop is unset.
     */
    fun urlsFromSysprop(): List<URL> {
      val raw = System.getProperty(USER_CLASS_DIRS_PROP) ?: return emptyList()
      if (raw.isBlank()) return emptyList()
      return raw
        .split(java.io.File.pathSeparator)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { java.io.File(it) }
        .filter { it.exists() }
        .map { it.toURI().toURL() }
    }
  }
}
