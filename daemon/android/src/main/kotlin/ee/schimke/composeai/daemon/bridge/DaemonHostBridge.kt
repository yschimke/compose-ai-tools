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
   * loader. See `docs/daemon/CLASSLOADER-FORENSICS.md`.
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
    // INTERACTIVE-ANDROID.md § 3 — drain any queued interactive commands so a previous host
    // lifecycle's leftover Start/Dispatch/Close don't leak into the next start. Slot 0's queue is
    // an instance field on [SandboxSlot] (not aliased to a top-level @JvmField like [requests]),
    // so the explicit clear is necessary even on the single-slot path.
    slot0Eager.interactiveCommands.clear()
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
  /**
   * Inbound interactive-mode command queue (INTERACTIVE-ANDROID.md § 3 — v3 Android interactive).
   * The held-rule loop in `SandboxRunner.holdSandboxOpen` (lands in PR B) drains this queue when
   * the slot is in interactive-mode; until PR B lands, the queue exists as part of the bridge
   * surface but no sandbox-side code reads from it (callers can enqueue, nothing happens).
   *
   * Separate from [requests] because the held-rule loop's lifecycle is different: it's allocated
   * on `Start`, drained until `Close`, then the slot returns to draining [requests]. A single
   * mixed queue would force the sandbox-side loop to discriminate per-poll.
   */
  @JvmField val interactiveCommands: LinkedBlockingQueue<InteractiveCommand> = LinkedBlockingQueue(),
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

/**
 * Cross-classloader command for the v3 Android-interactive held-rule loop (INTERACTIVE-ANDROID.md
 * § 3). The sandbox-side `SandboxRunner` reads from [SandboxSlot.interactiveCommands] when its
 * slot is pinned to an interactive session; commands are routed to the held rule's main thread.
 *
 * **Bridge-package-only.** This sealed interface and every member must use only `java.*` types or
 * other types in this `bridge` package — see the file KDoc on [DaemonHostBridge] for the
 * Robolectric do-not-acquire boundary the package relies on. Specifically: no Compose pointer
 * types, no Roborazzi capture types, no `ee.schimke.composeai.daemon.*` imports outside this
 * package. Pixel coordinates ride as primitives; the sandbox synthesises the `MotionEvent` itself.
 *
 * **Wire-only in PR A.** The host enqueues nothing yet; no sandbox-side loop drains the queue.
 * PR B (INTERACTIVE-ANDROID.md § 4 + § 7) wires both ends and ships
 * `RobolectricHost.acquireInteractiveSession`. Lands here first so PR B doesn't have to widen
 * the bridge surface mid-rollout.
 */
sealed interface InteractiveCommand {
  /**
   * Stream identifier echoed back to the host so a single slot can reject nested-start races
   * (INTERACTIVE-ANDROID.md § 4: "A nested Start while a session is held is an error"). Opaque to
   * the bridge — the host generates it, the sandbox carries it back unchanged.
   */
  val streamId: String

  /**
   * Allocate the rule + ActivityScenario, run `setContent`, signal ready via [replyLatch]. The
   * preview reference travels as separate FQN + function-name strings (the sandbox resolves them
   * via `Class.forName` against the slot's child classloader), and dimension/qualifier fields
   * mirror the v1 `RenderSpec` subset the held composition needs.
   *
   * **Qualifier fields are encoded as primitives + Strings** rather than the daemon-side
   * [`RenderSpec.SpecUiMode`] / [`RenderSpec.SpecOrientation`] enums because those enums live in
   * the instrumented `ee.schimke.composeai.daemon.*` package — passing them across the bridge
   * would re-load the enum class inside the sandbox, breaking `==` equality. Strings cross the
   * boundary as `java.lang.String` (do-not-acquire by default). PR C wired the extra qualifiers
   * (`localeTag`, `fontScale`, `uiMode`, `orientation`) so the held composition reflects the
   * same Configuration overrides a one-shot render would; PR B's Start carried only the v1
   * size/density/round subset.
   *
   * @param replyError the sandbox sets this before counting down [replyLatch] when `setContent`
   *   throws; the host then surfaces a clean failure on the v2 `interactive/start` reply rather
   *   than a wire-level timeout. Wrapping in [AtomicReference] keeps the field assignable from
   *   the sandbox classloader without requiring Throwable types to cross the boundary as
   *   anything other than the standard `java.lang.Throwable`.
   */
  data class Start(
    override val streamId: String,
    val previewClassName: String,
    val previewFunctionName: String,
    val widthPx: Int,
    val heightPx: Int,
    val density: Float,
    val backgroundColor: Long,
    val showBackground: Boolean,
    val device: String?,
    val outputBaseName: String,
    val replyLatch: CountDownLatch,
    val replyError: AtomicReference<Throwable?>,
    /** BCP-47 locale tag (e.g. `"en-US"`); `null` = no `b+lang+region` qualifier. */
    val localeTag: String? = null,
    /** Font scale multiplier; `null` = leave Robolectric's `RuntimeEnvironment.setFontScale` at 1.0. */
    val fontScale: Float? = null,
    /** `"light"` / `"dark"` / `null`; resolved sandbox-side to the `notnight` / `night` qualifier. */
    val uiMode: String? = null,
    /** `"portrait"` / `"landscape"` / `null`; overrides the size-derived guess. */
    val orientation: String? = null,
    /** Held-session `LocalInspectionMode`; `null` preserves the runtime-like default (`false`). */
    val inspectionMode: Boolean? = null,
  ) : InteractiveCommand

  /**
   * Synthesise + dispatch a [android.view.MotionEvent] on the rule's main thread. [kind] mirrors
   * the v2 wire `interactive/input` `kind` field — pointer events plus `"rotaryScroll"` for Wear
   * previews; key events fall through to a no-op until v4. Pixel coordinates are in the held
   * composition's own pixel space — the host has already scaled from any `pixelDensity` ratio.
   */
  data class Dispatch(
    override val streamId: String,
    val kind: String,
    val pixelX: Int,
    val pixelY: Int,
    val scrollDeltaY: Float? = null,
    val replyLatch: CountDownLatch,
    val replyError: AtomicReference<Throwable?>,
  ) : InteractiveCommand

  /**
   * Capture current pixels via the same `captureRoboImage` path the one-shot `RenderEngine` uses,
   * and emit a `RenderResult` on [DaemonHostBridge.results] keyed by [requestId]. The host's
   * `AndroidInteractiveSession.render()` polls that map exactly like the v1
   * `RobolectricHost.submit` does today — no new result channel needed because render ids are
   * already globally monotonic.
   */
  data class Render(override val streamId: String, val requestId: Long) : InteractiveCommand

  /**
   * Tear down the rule + ActivityScenario; the held statement returns and the slot reverts to
   * draining normal renders from [SandboxSlot.requests]. [replyLatch] counts down before the
   * statement exits so the host knows the ActivityScenario has actually closed before the next
   * `interactive/start` would race against the same slot.
   */
  data class Close(override val streamId: String, val replyLatch: CountDownLatch) :
    InteractiveCommand
}
