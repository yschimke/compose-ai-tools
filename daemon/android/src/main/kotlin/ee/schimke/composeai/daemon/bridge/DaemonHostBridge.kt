package ee.schimke.composeai.daemon.bridge

import java.net.URLClassLoader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Cross-classloader handoff for the Robolectric-sandboxed [DaemonHost].
 *
 * **Why a separate package?** Robolectric's `InstrumentingClassLoader`
 * re-loads classes in the project's namespace by default — confirmed
 * empirically: a static `companion object` on `DaemonHost` resolves to
 * different instances when accessed from the test thread vs. from inside
 * the sandbox (`@Test fun holdSandboxOpen`). That breaks the
 * single-shared-queue assumption the daemon depends on.
 *
 * The fix is a custom Robolectric runner ([SandboxHoldingRunner]) that
 * registers `ee.schimke.composeai.daemon.bridge` as a do-not-acquire
 * package. Classes here are then loaded once by the system classloader
 * and visible identically from both sides of the sandbox boundary.
 *
 * Keep this file **trivial**: only `java.util.concurrent.*` types and
 * primitives. No Compose, no Robolectric, no `ee.schimke.composeai.*`
 * imports — those would drag the bridge back into the instrumented graph.
 */
object DaemonHostBridge {

  /** Inbound request queue. Render bodies and the shutdown poison pill flow through here. */
  @JvmField val requests: LinkedBlockingQueue<Any> = LinkedBlockingQueue()

  /**
   * Per-request result queue, keyed by request id. Sized 1 in practice
   * (one render per id) but typed as a queue for safe blocking semantics.
   */
  @JvmField
  val results: ConcurrentMap<Long, LinkedBlockingQueue<Any>> = ConcurrentHashMap()

  /**
   * Shutdown signal. The sandbox-side polling loop checks this on every
   * iteration so a missed Shutdown message (e.g. due to a future
   * classloader rule change reintroducing instrumentation) still
   * terminates the loop in bounded time.
   */
  @JvmField val shutdown: AtomicBoolean = AtomicBoolean(false)

  /**
   * Disposable user-class child classloader (B2.0 — see
   * docs/daemon/CLASSLOADER.md). Mirrored from `UserClassLoaderHolder` on the host thread so the
   * sandbox-side `RenderEngine.render` reads the current loader without holding a reference to the
   * core-side holder (which itself is double-loaded across the sandbox boundary, like every other
   * `ee.schimke.composeai.daemon.*` class).
   *
   * Set via [setCurrentChildLoader] from the host thread (host-side `RobolectricHost` calls it
   * after every `UserClassLoaderHolder.swap()`); read via [currentChildLoader] from the
   * sandbox-side render thread. `URLClassLoader` is a `java.net.*` type so the do-not-acquire
   * default boundary keeps it from being re-loaded inside the sandbox — the same
   * `URLClassLoader.class` object is visible from both sides.
   */
  @JvmField
  val childLoaderRef: AtomicReference<URLClassLoader?> = AtomicReference(null)

  /** Sets the current child classloader (host-thread side). */
  @JvmStatic
  fun setCurrentChildLoader(loader: URLClassLoader?) {
    childLoaderRef.set(loader)
  }

  /** Reads the current child classloader (sandbox-thread side). */
  @JvmStatic
  fun currentChildLoader(): URLClassLoader? = childLoaderRef.get()

  /**
   * Sandbox classloader, set by [SandboxHoldingRunner.holdSandboxOpen]'s prologue inside the
   * Robolectric sandbox so the host can build user-class child loaders whose **parent is the
   * sandbox loader**, not the host's app loader. Forensics-confirmed root cause for the B2.0
   * Android failure: without this wiring, every framework class (Compose runtime, Robolectric
   * internals, Android APIs) loads via the JVM app loader instead of the instrumented sandbox
   * loader. See [`docs/daemon/classloader-forensics-diff.md`](../../../../../../../docs/daemon/classloader-forensics-diff.md).
   *
   * The [sandboxReadyLatch] counts down once the ref is set, so the host can block until the
   * sandbox is initialised before calling `holder.currentChildLoader()` (which would otherwise
   * race against sandbox boot and throw).
   */
  @JvmField val sandboxClassLoaderRef: AtomicReference<ClassLoader?> = AtomicReference(null)

  /** Counts down once [setSandboxClassLoader] runs; host-side `awaitSandboxReady` blocks on it. */
  @Volatile @JvmField var sandboxReadyLatch: CountDownLatch = CountDownLatch(1)

  /**
   * Sets [sandboxClassLoaderRef] and counts down [sandboxReadyLatch]. Called from
   * [SandboxHoldingRunner.holdSandboxOpen] as the very first prologue line, **before** entering the
   * polling loop. Must be called from inside the sandbox so `this.javaClass.classLoader` resolves
   * to the sandbox's `SandboxClassLoader`.
   */
  @JvmStatic
  fun setSandboxClassLoader(loader: ClassLoader) {
    sandboxClassLoaderRef.set(loader)
    sandboxReadyLatch.countDown()
  }

  /** Reads the current sandbox classloader. Null until [setSandboxClassLoader] runs. */
  @JvmStatic
  fun currentSandboxClassLoader(): ClassLoader? = sandboxClassLoaderRef.get()

  /**
   * Blocks until the sandbox has registered itself via [setSandboxClassLoader], or until [timeoutMs]
   * elapses. Returns true if the sandbox is ready, false on timeout. Host-side code that needs to
   * allocate a child URLClassLoader with the sandbox loader as parent calls this before evaluating
   * the holder's `parentSupplier`.
   */
  @JvmStatic
  fun awaitSandboxReady(timeoutMs: Long): Boolean =
    sandboxReadyLatch.await(timeoutMs, TimeUnit.MILLISECONDS)

  /** Reset to a clean state — call before each [RobolectricHost.start]. */
  @JvmStatic
  fun reset() {
    requests.clear()
    results.clear()
    shutdown.set(false)
    childLoaderRef.set(null)
    sandboxClassLoaderRef.set(null)
    sandboxReadyLatch = CountDownLatch(1)
    // Render IDs (RenderHost.nextRequestId) deliberately stay monotonic
    // across host restarts within a single JVM — keeps log correlation
    // unambiguous. They live in the core module's RenderHost companion now.
  }
}
