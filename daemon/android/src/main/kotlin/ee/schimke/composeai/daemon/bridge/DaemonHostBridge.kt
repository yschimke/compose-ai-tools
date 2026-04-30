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
 *
 * **Multi-sandbox foundation (SANDBOX-POOL.md).** The bridge is now
 * **slot-keyed**: per-sandbox state (request queue, sandbox classloader
 * ref, child loader ref, ready latch) is wrapped in [SandboxSlot] and
 * exposed via [slots] / [slot]. Slot 0 is created eagerly and aliases the
 * legacy top-level fields so existing single-sandbox callers stay
 * source- and binary-compatible. Multi-sandbox callers (in-flight)
 * configure the slot count via [configureSlotCount] before the first
 * sandbox boots and use [registerSandbox] from inside each sandbox to
 * claim an exclusive slot.
 */
object DaemonHostBridge {

  // ---------------------------------------------------------------------------
  // Legacy slot-0 state. Kept as top-level @JvmField for binary compatibility
  // with every pre-pool caller (RobolectricHost.kt, DaemonMain.kt,
  // ClassloaderForensicsDaemonTest, etc.). Slot 0's [SandboxSlot] is a view
  // over these same instances — see [slot0Eager] below.
  // ---------------------------------------------------------------------------

  /** Inbound request queue. Render bodies and the shutdown poison pill flow through here. */
  @JvmField val requests: LinkedBlockingQueue<Any> = LinkedBlockingQueue()

  /**
   * Per-request result queue, keyed by request id. Sized 1 in practice
   * (one render per id) but typed as a queue for safe blocking semantics.
   *
   * **Shared across slots.** Render ids are monotonic and globally unique
   * (see [ee.schimke.composeai.daemon.RenderHost.nextRequestId]); a single
   * map keyed by id is unambiguous regardless of which sandbox slot
   * produced the result.
   */
  @JvmField
  val results: ConcurrentMap<Long, LinkedBlockingQueue<Any>> = ConcurrentHashMap()

  /**
   * Shutdown signal. The sandbox-side polling loop checks this on every
   * iteration so a missed Shutdown message (e.g. due to a future
   * classloader rule change reintroducing instrumentation) still
   * terminates the loop in bounded time.
   *
   * **Shared across slots.** A single flag tears down every sandbox in the
   * pool — pool-wide shutdown is the only mode in v1.
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
   *
   * **Slot-0 only.** Multi-slot callers should use [SandboxSlot.childLoaderRef] via [slot] /
   * [slots]. See SANDBOX-POOL.md for the layered plan.
   */
  @JvmField
  val childLoaderRef: AtomicReference<URLClassLoader?> = AtomicReference(null)

  /** Sets the current child classloader (host-thread side). Slot-0 alias of [SandboxSlot]. */
  @JvmStatic
  fun setCurrentChildLoader(loader: URLClassLoader?) {
    childLoaderRef.set(loader)
  }

  /** Reads the current child classloader (sandbox-thread side). Slot-0 alias of [SandboxSlot]. */
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
   *
   * **Slot-0 only.** Multi-slot callers should use [SandboxSlot.sandboxClassLoaderRef].
   */
  @JvmField val sandboxClassLoaderRef: AtomicReference<ClassLoader?> = AtomicReference(null)

  /** Counts down once [setSandboxClassLoader] runs; host-side `awaitSandboxReady` blocks on it. */
  @Volatile @JvmField var sandboxReadyLatch: CountDownLatch = CountDownLatch(1)

  /**
   * Sets [sandboxClassLoaderRef] and counts down [sandboxReadyLatch]. Called from
   * [SandboxHoldingRunner.holdSandboxOpen] as the very first prologue line, **before** entering the
   * polling loop. Must be called from inside the sandbox so `this.javaClass.classLoader` resolves
   * to the sandbox's `SandboxClassLoader`.
   *
   * **Slot-0 only.** Multi-slot callers should use [registerSandbox], which atomically claims the
   * next free slot and returns its index.
   */
  @JvmStatic
  fun setSandboxClassLoader(loader: ClassLoader) {
    sandboxClassLoaderRef.set(loader)
    sandboxReadyLatch.countDown()
  }

  /** Reads the current sandbox classloader. Null until [setSandboxClassLoader] runs. Slot-0 only. */
  @JvmStatic
  fun currentSandboxClassLoader(): ClassLoader? = sandboxClassLoaderRef.get()

  /**
   * Blocks until the sandbox has registered itself via [setSandboxClassLoader], or until [timeoutMs]
   * elapses. Returns true if the sandbox is ready, false on timeout. Host-side code that needs to
   * allocate a child URLClassLoader with the sandbox loader as parent calls this before evaluating
   * the holder's `parentSupplier`.
   *
   * **Slot-0 only.** Multi-slot callers should iterate [slots] and await each slot individually
   * via [SandboxSlot.awaitSandboxReady].
   */
  @JvmStatic
  fun awaitSandboxReady(timeoutMs: Long): Boolean =
    sandboxReadyLatch.await(timeoutMs, TimeUnit.MILLISECONDS)

  // ---------------------------------------------------------------------------
  // Multi-slot foundation (SANDBOX-POOL.md). Purely additive — slot 0 aliases
  // the legacy top-level fields. Default slot count is 1, preserving the
  // pre-pool single-sandbox behaviour bit-for-bit.
  // ---------------------------------------------------------------------------

  /**
   * Slot 0's [SandboxSlot]. Backed by the same instances as the legacy top-level fields
   * ([requests], [shutdown], [childLoaderRef], [sandboxClassLoaderRef], [sandboxReadyLatch]) so
   * single-sandbox callers and the slot-aware path see identical state.
   */
  private val slot0Eager: SandboxSlot =
    SandboxSlot(
      index = 0,
      requests = requests,
      childLoaderRef = childLoaderRef,
      sandboxClassLoaderRef = sandboxClassLoaderRef,
      sandboxReadyLatchRef = AtomicReference(sandboxReadyLatch),
    )

  /**
   * The active slot list. Always non-empty; slot 0 is always present and is the legacy slot.
   * Updated atomically by [configureSlotCount] before the first sandbox boots; updates after that
   * are forbidden (see [configureSlotCount]'s preconditions).
   */
  private val slotsRef: AtomicReference<List<SandboxSlot>> = AtomicReference(listOf(slot0Eager))

  /**
   * Reconfigures the slot list to [count] slots. Slot 0 is always reused (legacy aliases stay
   * valid); slots 1..count-1 are fresh standalone [SandboxSlot] instances.
   *
   * **Must be called before any sandbox boots** — typically once, from the daemon's `main` or from
   * `RobolectricHost.start` before the worker threads launch. Re-configuration after a sandbox has
   * registered into a slot would orphan that registration; v1 forbids it (the precondition catches
   * an already-claimed slot 0 and refuses, surfacing the bug at config time rather than letting the
   * worker run against a stale slot view).
   *
   * Idempotent at [count] = current size — useful for the supervisor reconnect flow where the
   * sandbox-count sysprop is parsed each launch but the value typically doesn't change.
   */
  @JvmStatic
  fun configureSlotCount(count: Int) {
    require(count >= 1) { "DaemonHostBridge.configureSlotCount: count must be >= 1, got $count" }
    val current = slotsRef.get()
    if (current.size == count) return
    check(slot0Eager.sandboxClassLoaderRef.get() == null) {
      "DaemonHostBridge.configureSlotCount: slot 0 already claimed by a sandbox " +
        "(${slot0Eager.sandboxClassLoaderRef.get()}). Reconfiguration after boot is not supported."
    }
    val newSlots = buildList {
      add(slot0Eager)
      for (i in 1 until count) add(SandboxSlot.standalone(i))
    }
    slotsRef.set(newSlots)
  }

  /** Snapshot of the active slot list. Always non-empty. */
  @JvmStatic
  fun slots(): List<SandboxSlot> = slotsRef.get()

  /** Returns slot at [index]. Throws if out of range. */
  @JvmStatic
  fun slot(index: Int): SandboxSlot {
    val list = slotsRef.get()
    require(index in list.indices) {
      "DaemonHostBridge.slot($index): out of range (slot count = ${list.size})"
    }
    return list[index]
  }

  /**
   * Sandbox-side: claim the first free slot (one whose `sandboxClassLoaderRef` is still null) and
   * register [loader] into it. Returns the slot index. Counts down the slot's ready latch so the
   * host's `awaitSandboxReady(slot)` returns.
   *
   * Throws when every slot is already claimed — that means the host configured fewer slots than
   * the sandbox-bootstrap pool is actually trying to register, which is a wiring bug rather than a
   * runtime condition.
   *
   * Each Robolectric sandbox has its own classloader; the CAS on `compareAndSet(null, loader)`
   * makes the slot allocation race-free even when multiple sandboxes finish booting concurrently.
   */
  @JvmStatic
  fun registerSandbox(loader: ClassLoader): Int {
    val list = slotsRef.get()
    for ((i, s) in list.withIndex()) {
      if (s.sandboxClassLoaderRef.compareAndSet(null, loader)) {
        s.sandboxReadyLatchRef.get().countDown()
        return i
      }
    }
    error(
      "DaemonHostBridge.registerSandbox: no free sandbox slot for ${loader.javaClass.name} " +
        "— ${list.size} slots, all claimed. Did configureSlotCount get called with too low a value?"
    )
  }

  /** Reset to a clean state — call before each [RobolectricHost.start]. */
  @JvmStatic
  fun reset() {
    requests.clear()
    results.clear()
    shutdown.set(false)
    childLoaderRef.set(null)
    sandboxClassLoaderRef.set(null)
    sandboxReadyLatch = CountDownLatch(1)
    // Reset slot 0's latch ref to the new latch so the slot view stays consistent with the legacy
    // top-level field. Slot 0's other refs alias the top-level AtomicReferences directly so they
    // were already cleared by the lines above.
    slot0Eager.sandboxReadyLatchRef.set(sandboxReadyLatch)
    // Drop any extended slots back to the canonical single-slot list. Multi-slot configuration is
    // re-applied by the next [configureSlotCount] call (typically from `RobolectricHost.start`).
    // Render IDs (RenderHost.nextRequestId) deliberately stay monotonic across host restarts
    // within a single JVM — keeps log correlation unambiguous.
    slotsRef.set(listOf(slot0Eager))
  }
}

/**
 * Per-sandbox handoff state. Each Robolectric sandbox in the pool gets one. Slot 0 aliases the
 * legacy [DaemonHostBridge] top-level fields; slots 1..N-1 are standalone instances allocated by
 * [DaemonHostBridge.configureSlotCount].
 *
 * Field semantics mirror the slot-0 KDocs on [DaemonHostBridge]; see those for rationale. The
 * shared (per-JVM) state — [DaemonHostBridge.results] and [DaemonHostBridge.shutdown] — stays on
 * the bridge object itself.
 */
class SandboxSlot
internal constructor(
  /** Index in the slot list. Slot 0 is the legacy slot. */
  @JvmField val index: Int,
  /** Inbound request queue for this sandbox. */
  @JvmField val requests: LinkedBlockingQueue<Any>,
  /** Disposable user-class child classloader for this sandbox's render path. */
  @JvmField val childLoaderRef: AtomicReference<URLClassLoader?>,
  /**
   * Sandbox classloader, set inside the sandbox by [DaemonHostBridge.registerSandbox] (or, for
   * slot 0, by the legacy [DaemonHostBridge.setSandboxClassLoader]).
   */
  @JvmField val sandboxClassLoaderRef: AtomicReference<ClassLoader?>,
  /**
   * Counts down once the slot is claimed. Wrapped in an [AtomicReference] so
   * [DaemonHostBridge.reset] can swap a fresh latch in without invalidating the [SandboxSlot]
   * reference held by callers.
   */
  @JvmField val sandboxReadyLatchRef: AtomicReference<CountDownLatch>,
) {
  /** Convenience: blocks until this slot's sandbox is ready. */
  fun awaitSandboxReady(timeoutMs: Long): Boolean =
    sandboxReadyLatchRef.get().await(timeoutMs, TimeUnit.MILLISECONDS)

  companion object {
    /** Standalone slot for index [i] > 0. Used by [DaemonHostBridge.configureSlotCount]. */
    @JvmStatic
    internal fun standalone(i: Int): SandboxSlot =
      SandboxSlot(
        index = i,
        requests = LinkedBlockingQueue(),
        childLoaderRef = AtomicReference(null),
        sandboxClassLoaderRef = AtomicReference(null),
        sandboxReadyLatchRef = AtomicReference(CountDownLatch(1)),
      )
  }
}
