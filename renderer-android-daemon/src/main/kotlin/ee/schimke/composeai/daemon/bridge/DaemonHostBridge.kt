package ee.schimke.composeai.daemon.bridge

import java.net.URLClassLoader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.LinkedBlockingQueue
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

  /** Reset to a clean state — call before each [RobolectricHost.start]. */
  @JvmStatic
  fun reset() {
    requests.clear()
    results.clear()
    shutdown.set(false)
    childLoaderRef.set(null)
    // Render IDs (RenderHost.nextRequestId) deliberately stay monotonic
    // across host restarts within a single JVM — keeps log correlation
    // unambiguous. They live in the core module's RenderHost companion now.
  }
}
