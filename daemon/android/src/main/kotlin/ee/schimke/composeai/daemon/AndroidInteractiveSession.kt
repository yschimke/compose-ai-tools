package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.bridge.DaemonHostBridge
import ee.schimke.composeai.daemon.bridge.InteractiveCommand
import ee.schimke.composeai.daemon.bridge.SandboxSlot
import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.InteractiveInputParams
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Android (Robolectric) [InteractiveSession]. Mirrors `:daemon:desktop`'s
 * [DesktopInteractiveSession] at the protocol surface; the difference is all in cross-classloader
 * marshalling — Compose pointer / Roborazzi capture types can't cross the sandbox boundary, so
 * `dispatch` / `render` ride [InteractiveCommand] envelopes through the
 * [bridge.DaemonHostBridge] and the actual `MotionEvent.dispatchTouchEvent` +
 * `captureRoboImage` happens inside the sandbox-side [RobolectricHost.SandboxRunner.runHeldInteractiveSession]
 * loop.
 *
 * **Sandbox pinning** (INTERACTIVE-ANDROID.md § 2). Each session pins exactly one sandbox slot for
 * its lifetime. v3 ships with `INTERACTIVE_SLOT_INDEX = 1` so slot 0 stays the always-on
 * normal-render slot — that's enforced host-side in [RobolectricHost.acquireInteractiveSession]
 * (the constraint that `sandboxCount >= 2`). The session itself is slot-agnostic; it only carries
 * a reference to the slot it was constructed against.
 *
 * **Threading.** Per the [InteractiveSession] contract, callers are serialised — `JsonRpcServer`
 * dispatches one input per session at a time. Each public method enqueues an
 * [InteractiveCommand] and blocks the caller's thread on the per-command reply latch (or, for
 * [render], on the shared [DaemonHostBridge.results] queue). The sandbox-side held-rule statement
 * is single-threaded by construction (one `evaluate()` call), so command ordering on the wire
 * matches dispatch order in the composition.
 *
 * **Lifecycle** (INTERACTIVE-ANDROID.md § 7).
 * - **Allocate** at `interactive/start`. The host enqueues [InteractiveCommand.Start] onto slot 1's
 *   [SandboxSlot.interactiveCommands]; the sandbox's idle loop picks it up and enters
 *   `runHeldInteractiveSession`, which constructs the rule + ActivityScenario + paused-clock
 *   `setContent`, then counts down [InteractiveCommand.Start.replyLatch] before draining further
 *   commands. The host blocks on that latch in [RobolectricHost.acquireInteractiveSession] so
 *   `interactive/start` only returns after `setContent` has landed.
 * - **Drive** at `interactive/input` and `renderNow` while held. [dispatch] enqueues
 *   [InteractiveCommand.Dispatch] (synthesised `MotionEvent`); [render] enqueues
 *   [InteractiveCommand.Render] and polls the global results map.
 * - **Release** at `interactive/stop` or daemon shutdown. [close] enqueues
 *   [InteractiveCommand.Close]; the held-rule statement returns from `evaluate()`, which causes
 *   the rule's outer wrapper to close the `ActivityScenario` — the same disposal point a one-shot
 *   render hits.
 */
class AndroidInteractiveSession
internal constructor(
  override val previewId: String,
  /**
   * Stream identifier echoed back on every [InteractiveCommand]. Generated host-side at
   * [RobolectricHost.acquireInteractiveSession] so the host can detect a nested-start race (#3 of
   * the held-loop's open questions) and route its reply correctly.
   */
  internal val streamId: String,
  /**
   * The sandbox slot this session was acquired against — almost always slot 1 in v3 (slot 0 is the
   * always-on normal-render slot). Carried explicitly rather than re-derived because the host's
   * routing code (`acquireInteractiveSession`) already chose it; passing it in keeps the session
   * itself slot-policy-agnostic so v4 (multi-target Android) can reuse this class unchanged.
   */
  private val slot: SandboxSlot,
  /**
   * Cleared by [close] so [RobolectricHost.acquireInteractiveSession] can reuse the slot for the
   * next interactive/start. Wrapped in [AtomicReference] so the host's CAS-on-acquire race-check
   * sees the same instance the session writes to.
   */
  private val activeStreamRef: AtomicReference<String?>,
  /**
   * Idle lease — auto-close the session if no [dispatch] / [render] arrives for this many
   * milliseconds. Defaults to [DEFAULT_IDLE_LEASE_MS] (1 minute) and is overridable via the
   * `composeai.daemon.interactive.idleLeaseMs` sysprop so operators can tune for slow networks
   * and tests can drive a sub-second lease without sleeping CI for a minute.
   *
   * Without this lease, a panel that crashes or a websocket that drops mid-session would leak a
   * pinned sandbox slot for the rest of the daemon's lifetime — slot 1 stays held with no
   * `interactive/stop` ever arriving. v3 supports one held session at a time per host, so a
   * single zombie burns the whole interactive capacity. Symmetry with the desktop story comes
   * via the panel's auto-stop on editor change/scroll (#427); the lease is the daemon-side
   * belt-and-braces.
   *
   * Setting this to a non-positive value disables the watchdog entirely — useful for tests that
   * verify the lease itself or for scenarios where an external lifecycle (e.g. a smoke test
   * driver) owns the session's close path explicitly.
   */
  private val idleLeaseMs: Long =
    System.getProperty(IDLE_LEASE_PROP)?.toLongOrNull() ?: DEFAULT_IDLE_LEASE_MS,
  /**
   * Invoked from [close] (after the bridge round-trip and `activeStreamRef` clear) so the host
   * can drop its own reference to this session — see
   * [RobolectricHost.activeInteractiveSession]. PR C wired this so the host's lifecycle hooks
   * ([RobolectricHost.swapUserClassLoaders] and [RobolectricHost.shutdown]) can force-close a
   * held session without keeping a strong reference that would survive an explicit
   * `interactive/stop`.
   *
   * Default is a no-op so tests and other callers that construct sessions directly don't have
   * to wire a hook. Called exactly once per session — after the first [close]. Subsequent
   * [close] calls (idempotent) skip the hook.
   */
  private val onCloseHook: () -> Unit = {},
) : InteractiveSession {

  @Volatile private var closed: Boolean = false

  /**
   * Wall-clock millis at the most recent [dispatch] / [render] call (or session construction, the
   * implicit "first use"). The watchdog reads this every [idleLeaseMs]/4 ticks and triggers
   * auto-close when the gap exceeds [idleLeaseMs]. Updated **before** the bridge enqueue so
   * a long-running render doesn't look idle from the watchdog's perspective.
   */
  private val lastUsedAtMs: AtomicLong = AtomicLong(System.currentTimeMillis())

  /**
   * Wall-clock millis at the most recent [render] call, or `-1L` until the first render lands.
   * Drives the wall-clock-accurate auto-advance in [render]: when the caller passes
   * `advanceTimeMs = null`, the held composition's clock advances by the wall-clock delta since
   * this timestamp (clamped — see [render]). Single-writer (`render` is serialised by
   * `JsonRpcServer`) but `AtomicLong` for memory-visibility symmetry with [lastUsedAtMs].
   */
  private val lastRenderAtMs: AtomicLong = AtomicLong(-1L)

  /**
   * Set to a non-null human-readable string when the watchdog auto-closes. Surfaced via
   * [autoClosedReason] so PR C's panel-side handler can distinguish "host force-closed because
   * idle" from "client hit interactive/stop".
   */
  @Volatile private var autoClosedReason: String? = null

  /**
   * Daemon-thread executor that runs the idle-lease check. Allocated lazily so a session
   * constructed with `idleLeaseMs <= 0` doesn't pay the executor allocation. Cancelled by
   * [close] — the executor is single-threaded so cancellation is bounded.
   */
  private val watchdog: ScheduledExecutorService? =
    if (idleLeaseMs > 0L) {
      Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "compose-ai-daemon-interactive-watchdog-$streamId").apply {
          isDaemon = true
        }
      }
    } else null

  init {
    // Schedule the lease check at idleLeaseMs/4 intervals so we close within at most 25% over
    // the configured timeout. Doesn't run on `idleLeaseMs <= 0` (watchdog is null) — that's the
    // explicit opt-out path for tests that own the session lifecycle directly.
    watchdog?.scheduleWithFixedDelay(
      ::checkIdleLease,
      idleLeaseMs,
      (idleLeaseMs / 4).coerceAtLeast(50L),
      TimeUnit.MILLISECONDS,
    )
  }

  /**
   * `true` when the session has been closed (either via explicit [close] or the idle-lease
   * watchdog). Subsequent [dispatch] returns silently; [render] throws. Exposed for PR C's
   * panel-side handler that wants to redraw the v1 fallback hint when the daemon force-closes a
   * stream out from under it.
   */
  val isClosed: Boolean
    get() = closed

  /**
   * Non-null when the session was force-closed by the idle-lease watchdog (rather than by an
   * explicit [close] call). Carries the lease config + idle duration for diagnostics. Stays
   * `null` when the session was closed explicitly — PR C's wire handler checks this to decide
   * whether to surface a status-bar hint or treat the close as routine.
   */
  fun autoClosedReason(): String? = autoClosedReason

  private fun checkIdleLease() {
    if (closed) return
    val now = System.currentTimeMillis()
    val idle = now - lastUsedAtMs.get()
    if (idle >= idleLeaseMs) {
      autoClosedReason =
        "idle for ${idle}ms (lease ${idleLeaseMs}ms); auto-closing held session " +
          "'$streamId' (previewId='$previewId')"
      System.err.println("compose-ai-daemon: AndroidInteractiveSession: $autoClosedReason")
      // close() handles activeStreamRef CAS + bridge Close enqueue + watchdog shutdown.
      // Running it on this scheduled thread blocks the scheduler for at most CLOSE_TIMEOUT_SEC
      // — bounded, no leak.
      try {
        close()
      } catch (t: Throwable) {
        System.err.println(
          "compose-ai-daemon: AndroidInteractiveSession watchdog close threw: " +
            "${t.javaClass.simpleName}: ${t.message}"
        )
      }
    }
  }

  override fun dispatch(input: InteractiveInputParams) {
    if (closed) return
    val px = input.pixelX
    val py = input.pixelY
    val kind =
      when (input.kind) {
        InteractiveInputKind.CLICK -> "click"
        InteractiveInputKind.POINTER_DOWN -> "pointerDown"
        InteractiveInputKind.POINTER_MOVE -> "pointerMove"
        InteractiveInputKind.POINTER_UP -> "pointerUp"
        InteractiveInputKind.ROTARY_SCROLL -> "rotaryScroll"
        // Key events are no-op on Android v3 — same shape as desktop v2's silent drop. Wire shape
        // accepts them so a forward-looking client doesn't get rejected; the dispatch lands in
        // the sandbox loop and is intentionally ignored there.
        InteractiveInputKind.KEY_DOWN,
        InteractiveInputKind.KEY_UP -> return
      }
    if (px == null || py == null) return
    lastUsedAtMs.set(System.currentTimeMillis())
    val replyLatch = CountDownLatch(1)
    val replyError = AtomicReference<Throwable?>(null)
    slot.interactiveCommands.put(
      InteractiveCommand.Dispatch(
        streamId = streamId,
        kind = kind,
        pixelX = px,
        pixelY = py,
        scrollDeltaY = input.scrollDeltaY,
        replyLatch = replyLatch,
        replyError = replyError,
      )
    )
    if (!replyLatch.await(DISPATCH_TIMEOUT_SEC, TimeUnit.SECONDS)) {
      error(
        "AndroidInteractiveSession.dispatch timed out after ${DISPATCH_TIMEOUT_SEC}s for stream " +
          "'$streamId' (kind=$kind, px=$px, py=$py). Held-rule loop may be stuck."
      )
    }
    replyError.get()?.let { throw it }
  }

  /**
   * Override of [InteractiveSession.dispatchSemanticsAction]. Enqueues a
   * [InteractiveCommand.DispatchSemanticsAction] envelope through the bridge; the sandbox-side
   * loop in [RobolectricHost.SandboxRunner.runHeldInteractiveSession] resolves the matching node
   * by `hasContentDescription(...)` and invokes the corresponding `SemanticsActions` action.
   *
   * Returns `true` when the action fired against a matched node; `false` when no node matched
   * (caller surfaces unsupported evidence). Throws when the action body itself failed — same
   * propagation path as [dispatch].
   */
  override fun dispatchSemanticsAction(
    actionKind: String,
    nodeContentDescription: String,
  ): Boolean {
    if (closed) return false
    lastUsedAtMs.set(System.currentTimeMillis())
    val replyLatch = CountDownLatch(1)
    val replyError = AtomicReference<Throwable?>(null)
    val replyMatched = AtomicBoolean(false)
    slot.interactiveCommands.put(
      InteractiveCommand.DispatchSemanticsAction(
        streamId = streamId,
        actionKind = actionKind,
        nodeContentDescription = nodeContentDescription,
        replyLatch = replyLatch,
        replyError = replyError,
        replyMatched = replyMatched,
      )
    )
    if (!replyLatch.await(DISPATCH_TIMEOUT_SEC, TimeUnit.SECONDS)) {
      error(
        "AndroidInteractiveSession.dispatchSemanticsAction timed out after " +
          "${DISPATCH_TIMEOUT_SEC}s for stream '$streamId' (action=$actionKind, " +
          "contentDescription='$nodeContentDescription'). Held-rule loop may be stuck."
      )
    }
    replyError.get()?.let { throw it }
    return replyMatched.get()
  }

  /**
   * Override of [InteractiveSession.dispatchLifecycle]. Enqueues a
   * [InteractiveCommand.DispatchLifecycle] envelope through the bridge; the sandbox-side loop in
   * [RobolectricHost.SandboxRunner.runHeldInteractiveSession] resolves the wire-name string to a
   * `Lifecycle.State` and calls `ActivityScenario.moveToState(...)`.
   *
   * Returns `true` when the lifecycle transition fired; `false` when the named event isn't
   * recognised. Throws when the transition itself failed — same propagation path as [dispatch].
   */
  override fun dispatchLifecycle(lifecycleEvent: String): Boolean {
    if (closed) return false
    lastUsedAtMs.set(System.currentTimeMillis())
    val replyLatch = CountDownLatch(1)
    val replyError = AtomicReference<Throwable?>(null)
    val replyApplied = AtomicBoolean(false)
    slot.interactiveCommands.put(
      InteractiveCommand.DispatchLifecycle(
        streamId = streamId,
        lifecycleEvent = lifecycleEvent,
        replyLatch = replyLatch,
        replyError = replyError,
        replyApplied = replyApplied,
      )
    )
    if (!replyLatch.await(DISPATCH_TIMEOUT_SEC, TimeUnit.SECONDS)) {
      error(
        "AndroidInteractiveSession.dispatchLifecycle timed out after " +
          "${DISPATCH_TIMEOUT_SEC}s for stream '$streamId' (lifecycleEvent=$lifecycleEvent). " +
          "Held-rule loop may be stuck."
      )
    }
    replyError.get()?.let { throw it }
    return replyApplied.get()
  }

  /**
   * Override of [InteractiveSession.dispatchPreviewReload]. Enqueues a
   * [InteractiveCommand.DispatchPreviewReload] envelope through the bridge; the sandbox-side
   * loop in [RobolectricHost.SandboxRunner.runHeldInteractiveSession] increments the held
   * `key(...)` reload counter, which Compose detects as a key change and rebuilds the
   * composition slot table from scratch.
   */
  override fun dispatchPreviewReload(): Boolean {
    if (closed) return false
    lastUsedAtMs.set(System.currentTimeMillis())
    val replyLatch = CountDownLatch(1)
    val replyError = AtomicReference<Throwable?>(null)
    val replyApplied = AtomicBoolean(false)
    slot.interactiveCommands.put(
      InteractiveCommand.DispatchPreviewReload(
        streamId = streamId,
        replyLatch = replyLatch,
        replyError = replyError,
        replyApplied = replyApplied,
      )
    )
    if (!replyLatch.await(DISPATCH_TIMEOUT_SEC, TimeUnit.SECONDS)) {
      error(
        "AndroidInteractiveSession.dispatchPreviewReload timed out after " +
          "${DISPATCH_TIMEOUT_SEC}s for stream '$streamId'. Held-rule loop may be stuck."
      )
    }
    replyError.get()?.let { throw it }
    return replyApplied.get()
  }

  /**
   * Override of [InteractiveSession.dispatchStateRecreate]. Enqueues a
   * [InteractiveCommand.DispatchStateRecreate] envelope; the sandbox-side loop snapshots the
   * `SaveableStateRegistry`, increments a recreate counter, and Compose rebuilds the slot table
   * with the snapshot restored.
   */
  override fun dispatchStateRecreate(): Boolean {
    if (closed) return false
    lastUsedAtMs.set(System.currentTimeMillis())
    val replyLatch = CountDownLatch(1)
    val replyError = AtomicReference<Throwable?>(null)
    val replyApplied = AtomicBoolean(false)
    slot.interactiveCommands.put(
      InteractiveCommand.DispatchStateRecreate(
        streamId = streamId,
        replyLatch = replyLatch,
        replyError = replyError,
        replyApplied = replyApplied,
      )
    )
    if (!replyLatch.await(DISPATCH_TIMEOUT_SEC, TimeUnit.SECONDS)) {
      error(
        "AndroidInteractiveSession.dispatchStateRecreate timed out after " +
          "${DISPATCH_TIMEOUT_SEC}s for stream '$streamId'. Held-rule loop may be stuck."
      )
    }
    replyError.get()?.let { throw it }
    return replyApplied.get()
  }

  /**
   * Override of [InteractiveSession.dispatchStateSave]. Enqueues a
   * [InteractiveCommand.DispatchStateSave] envelope; the sandbox-side loop snapshots the
   * `SaveableStateRegistry` and stores the bundle in a per-stream checkpoint map.
   */
  override fun dispatchStateSave(checkpointId: String): Boolean {
    if (closed) return false
    lastUsedAtMs.set(System.currentTimeMillis())
    val replyLatch = CountDownLatch(1)
    val replyError = AtomicReference<Throwable?>(null)
    val replyApplied = AtomicBoolean(false)
    slot.interactiveCommands.put(
      InteractiveCommand.DispatchStateSave(
        streamId = streamId,
        checkpointId = checkpointId,
        replyLatch = replyLatch,
        replyError = replyError,
        replyApplied = replyApplied,
      )
    )
    if (!replyLatch.await(DISPATCH_TIMEOUT_SEC, TimeUnit.SECONDS)) {
      error(
        "AndroidInteractiveSession.dispatchStateSave timed out after " +
          "${DISPATCH_TIMEOUT_SEC}s for stream '$streamId' (checkpointId='$checkpointId'). " +
          "Held-rule loop may be stuck."
      )
    }
    replyError.get()?.let { throw it }
    return replyApplied.get()
  }

  /**
   * Override of [InteractiveSession.dispatchStateRestore]. Enqueues a
   * [InteractiveCommand.DispatchStateRestore] envelope; the sandbox-side loop looks up the
   * stashed bundle by [checkpointId] and rebuilds the composition with it restored. Returns
   * `false` when no matching checkpoint has been saved.
   */
  override fun dispatchStateRestore(checkpointId: String): Boolean {
    if (closed) return false
    lastUsedAtMs.set(System.currentTimeMillis())
    val replyLatch = CountDownLatch(1)
    val replyError = AtomicReference<Throwable?>(null)
    val replyApplied = AtomicBoolean(false)
    slot.interactiveCommands.put(
      InteractiveCommand.DispatchStateRestore(
        streamId = streamId,
        checkpointId = checkpointId,
        replyLatch = replyLatch,
        replyError = replyError,
        replyApplied = replyApplied,
      )
    )
    if (!replyLatch.await(DISPATCH_TIMEOUT_SEC, TimeUnit.SECONDS)) {
      error(
        "AndroidInteractiveSession.dispatchStateRestore timed out after " +
          "${DISPATCH_TIMEOUT_SEC}s for stream '$streamId' (checkpointId='$checkpointId'). " +
          "Held-rule loop may be stuck."
      )
    }
    replyError.get()?.let { throw it }
    return replyApplied.get()
  }

  override fun render(requestId: Long, advanceTimeMs: Long?): RenderResult {
    check(!closed) { "AndroidInteractiveSession.render() called after close()" }
    val nowMs = System.currentTimeMillis()
    lastUsedAtMs.set(nowMs)
    // Wall-clock-accurate mode for live preview. JsonRpcServer.submitInteractiveRenderAsync calls
    // render(hostId) without an explicit advance, in which case RobolectricHost defaults to a
    // fixed 32ms per render — so a 200ms-per-render Robolectric capture only walks 32ms of
    // animation per iteration and animations appear ~6× slower than wall-clock. Substitute the
    // wall-clock delta since the previous render so the held composition's clock tracks real
    // time; if the daemon falls behind, the next render covers the missed interval in one tick
    // (i.e. animation skips frames forward to the correct wall-clock target). Floored at the
    // existing settle window so first-render and back-to-back renders still get the recompose
    // tick they need; capped at MAX_AUTO_ADVANCE_MS so a paused session doesn't lurch animations
    // forward by minutes when the user returns. Recording callers pass an explicit delta and
    // bypass this substitution entirely.
    val previousRenderAtMs = lastRenderAtMs.getAndSet(nowMs)
    val resolvedAdvance =
      advanceTimeMs
        ?: if (previousRenderAtMs < 0L) {
          AUTO_ADVANCE_FLOOR_MS
        } else {
          (nowMs - previousRenderAtMs).coerceIn(AUTO_ADVANCE_FLOOR_MS, MAX_AUTO_ADVANCE_MS)
        }
    slot.interactiveCommands.put(
      InteractiveCommand.Render(
        streamId = streamId,
        requestId = requestId,
        advanceTimeMs = resolvedAdvance,
      )
    )
    val resultQueue =
      DaemonHostBridge.results.computeIfAbsent(requestId) { LinkedBlockingQueue() }
    val raw =
      resultQueue.poll(RENDER_TIMEOUT_SEC, TimeUnit.SECONDS)
        ?: error(
          "AndroidInteractiveSession.render timed out after ${RENDER_TIMEOUT_SEC}s for stream " +
            "'$streamId', request $requestId"
        )
    DaemonHostBridge.results.remove(requestId)
    if (raw is Throwable) throw raw
    // Same cross-classloader copy `RobolectricHost.submit` uses — the sandbox-side `RenderResult`
    // is loaded by Robolectric's `InstrumentingClassLoader`, the host-side caller expects the
    // host-loader's class. Reflective copy collapses the two.
    return raw as? RenderResult ?: copyResultAcrossClassloaders(raw)
  }

  override fun close() {
    if (closed) return
    closed = true
    // Stop the watchdog **before** waiting on the close latch — we don't want the watchdog
    // double-firing close() on this same session while the bridge round-trip is in flight.
    //
    // `shutdown()` (non-interrupting) rather than `shutdownNow()`: the watchdog tick that fired
    // the auto-close is itself running on this executor's worker thread and is in the middle of
    // calling us. `shutdownNow()` would interrupt that tick mid-flight; the interrupt status
    // would propagate into our subsequent `replyLatch.await(...)` and throw
    // `InterruptedException` before `activeStreamRef` gets cleared. `shutdown()` lets the
    // current task finish naturally (we're past the scheduled work, the rest of close() runs to
    // completion) and just prevents future ticks — exactly what we want.
    watchdog?.shutdown()
    val replyLatch = CountDownLatch(1)
    slot.interactiveCommands.put(
      InteractiveCommand.Close(streamId = streamId, replyLatch = replyLatch)
    )
    // Bounded wait — if the held-rule statement is wedged we don't want `interactive/stop` to
    // hang the JsonRpcServer thread forever. After this returns we always clear the active-stream
    // ref so the host doesn't think a session is still pinned.
    replyLatch.await(CLOSE_TIMEOUT_SEC, TimeUnit.SECONDS)
    // CAS-from-our-streamId so concurrent close() calls don't clobber a fresh session that the
    // host has already started binding to the slot. Idempotent against a missing entry.
    activeStreamRef.compareAndSet(streamId, null)
    // PR C — let the host drop its own reference to this session. Wrapped in try/catch because
    // the hook runs on whatever thread called close() (could be the watchdog) and a
    // mis-configured hook shouldn't take down the watchdog.
    try {
      onCloseHook()
    } catch (t: Throwable) {
      System.err.println(
        "compose-ai-daemon: AndroidInteractiveSession.onCloseHook for stream " +
          "'$streamId' threw: ${t.javaClass.simpleName}: ${t.message}"
      )
    }
  }

  private fun copyResultAcrossClassloaders(raw: Any): RenderResult {
    val cls = raw.javaClass
    val id = cls.getMethod("getId").invoke(raw) as Long
    val hash = cls.getMethod("getClassLoaderHashCode").invoke(raw) as Int
    val name = cls.getMethod("getClassLoaderName").invoke(raw) as String
    val pngPath = cls.getMethod("getPngPath").invoke(raw) as String?
    @Suppress("UNCHECKED_CAST")
    val metrics = cls.getMethod("getMetrics").invoke(raw) as Map<String, Long>?
    return RenderResult(
      id = id,
      classLoaderHashCode = hash,
      classLoaderName = name,
      pngPath = pngPath,
      metrics = metrics?.let { LinkedHashMap(it) },
    )
  }

  companion object {
    /**
     * Upper bound on a single `interactive/input` round-trip — sandbox synthesises the
     * MotionEvent, dispatches on the UI thread, advances `mainClock` by `POINTER_HOLD_MS`, signals
     * back. 30 s is generous; in practice well under 100 ms post-cold-boot.
     */
    private const val DISPATCH_TIMEOUT_SEC: Long = 30L

    /**
     * Upper bound on a single capture — Roborazzi's `captureRoboImage` plus the disk write.
     * Matches the v1 `RobolectricHost.submit` default (60s) so a slow first-render after the
     * Roborazzi static init doesn't trip the timeout.
     */
    private const val RENDER_TIMEOUT_SEC: Long = 60L

    /**
     * Upper bound on session teardown. Closes the ActivityScenario via the rule's outer wrapper —
     * fast (single Activity.onDestroy + Compose disposal pass). 10 s is the slack for a degenerate
     * case where a `LaunchedEffect` cleanup blocks the dispatcher; if it ever times out, the
     * activeStreamRef is still cleared so the next acquire isn't blocked.
     */
    private const val CLOSE_TIMEOUT_SEC: Long = 10L

    /**
     * Sysprop knob for the idle-lease timeout (millis). When unset, sessions auto-close after
     * [DEFAULT_IDLE_LEASE_MS] of inactivity. Operators can tune this for slow networks; tests
     * pass a non-default via the constructor parameter directly.
     */
    const val IDLE_LEASE_PROP: String = "composeai.daemon.interactive.idleLeaseMs"

    /**
     * Default idle-lease timeout — 1 minute. Long enough that a user thinking about their
     * preview between clicks doesn't get yanked out from under, short enough that a panel crash
     * or a websocket drop returns the pinned slot to normal-render duty within a minute. The
     * panel's own auto-stop on editor change/scroll already handles the "user moved on" case;
     * this lease catches the "client disappeared" case PR C will explicitly wire to
     * `JsonRpcServer.onChannelClosed`.
     */
    const val DEFAULT_IDLE_LEASE_MS: Long = 60_000L

    /**
     * Floor for the wall-clock-derived advance applied in [render] when the caller leaves
     * `advanceTimeMs` null. Matches `RobolectricHost.HELD_CAPTURE_ADVANCE_MS` — the same
     * fixed-delta the held loop used unconditionally before the wall-clock substitution landed.
     * Two reasons to floor at this value:
     * - **First render** has no previous timestamp to subtract from, so `previousRenderAtMs <
     *   0L`; the floor preserves the existing settle window the held loop relies on to flush
     *   the initial composition.
     * - **Back-to-back renders** (delta < 32ms — possible when the daemon batches a dispatch +
     *   render arriving within a few ms of each other) still want at least one full recompose
     *   tick before capture, otherwise effects scheduled by the dispatch may not have applied
     *   yet.
     */
    private const val AUTO_ADVANCE_FLOOR_MS: Long = 32L

    /**
     * Cap on the wall-clock-derived advance applied in [render] when the caller leaves
     * `advanceTimeMs` null. A session that's been idle for many seconds (user switched
     * windows, network lag, etc.) shouldn't lurch animations forward by that whole gap on the
     * next render — `rememberInfiniteTransition`-style animations would jump phase, and any
     * `LaunchedEffect(Unit) { delay(...); ... }` that happens to straddle the gap could fire
     * far past its intended trigger point.
     *
     * 1000ms picks the largest jump a user might plausibly tolerate as "the animation just
     * caught up": one second of skipped wall-clock applied in a single tick lands the
     * composition at a sensible mid-animation frame for typical Material / Wear motion
     * (durations ≤ 600ms), without the multi-second phase jumps that frustrate diagnosis.
     * Beyond that the held clock simply lags real time — animations resume from where they
     * left off, accepting that the live preview is showing "the animation 2s ago" rather
     * than skipping ahead unpredictably.
     */
    private const val MAX_AUTO_ADVANCE_MS: Long = 1_000L
  }
}
