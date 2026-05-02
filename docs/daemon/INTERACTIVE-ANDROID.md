# Interactive mode on Android (Robolectric) — v3 design

> **Status:** design only. v2 ships click-into-composition for the
> desktop backend (#406, #408, #409). Android continues to fall back to
> the v1 stateless dispatch path; the panel surfaces the v1-fallback
> hint via #431. This doc captures the architecture for Android v3
> behind a sandbox-pinning approach, with concrete bridge changes and a
> three-PR rollout. Companion to
> [INTERACTIVE.md § 9](INTERACTIVE.md#9-v2--click-dispatch-into-composition)
> (desktop v2) and INTERACTIVE.md § 9.10 (which originally punted
> Android to v3 with a "see this doc" pointer once it lands).

## 1. The constraint that makes Android hard

Desktop v2 holds an `ImageComposeScene` across `interactive/input`
notifications: `setUp` once, dispatch through `sendPointerEvent`,
`renderOnce` per frame, `tearDown` at stop. Compose's desktop runtime
is JVM-only and the scene API is single-instance, single-thread, and
stateless about test runners.

Robolectric isn't. The current Android render path —
[`daemon/android/.../RenderEngine.kt`](../../daemon/android/src/main/kotlin/ee/schimke/composeai/daemon/RenderEngine.kt)
— constructs `createAndroidComposeRule<ComponentActivity>()` *per
render*, and the rule's `apply(statement, description).evaluate()`
creates the `ActivityScenario` at the start of `evaluate()` and
**closes it when `evaluate()` returns**. The Compose test rule also
forbids a second `setContent` on the same `ComponentActivity`, so
holding the rule across renders without changing scope is the only
way to keep `remember { mutableStateOf(...) }` alive across inputs.

Three sub-constraints follow:

1. **Held-statement-blocks-its-thread.** If the rule's wrapped
   `Statement.evaluate()` blocks (waiting for further `dispatch` /
   `render` commands), the calling thread blocks too. The whole
   `RobolectricHost` model lives inside one such held-statement
   already — `SandboxRunner.holdSandboxOpen` is a long-running `@Test`
   method that drains the request queue. Holding *another* statement
   (the rule's wrapper) inside that `@Test` would block the
   queue-drain loop entirely. No more normal renders for the duration
   of the interactive session.
2. **Bridge classloader-identity dance.** Anything that crosses the
   sandbox boundary (host thread ↔ sandbox `@Test` thread) has to be
   either a `java.*` type or live in
   [`bridge.DaemonHostBridge`](../../daemon/android/src/main/kotlin/ee/schimke/composeai/daemon/bridge/DaemonHostBridge.kt)'s
   do-not-acquire package. Compose pointer types and Roborazzi capture
   types fail that test — they have to be constructed *inside* the
   sandbox.
3. **MotionEvent dispatch on the main looper.** `View.dispatchTouchEvent`
   on the main looper is what Compose's pointer-input pipeline picks
   up. Robolectric's main looper exists per sandbox; commands from
   the host have to be marshalled onto it via `Handler` /
   `Looper.getMainLooper()`.

## 2. The unlock — pin one sandbox to interactive

> **Credit.** This architecture came out of the v2-ship discussion;
> the simplification that "only support v2 on Android when there's
> more than one sandbox configured" sidesteps constraint (1) entirely
> by holding the blocking statement on a *different* sandbox slot
> from the one serving normal renders.

Architecture:

- **Slot 0** stays the always-on normal-render slot. Its
  `holdSandboxOpen` keeps draining the per-slot request queue exactly
  like today.
- **Slot 1** (or any slot ≥ 1) is the **interactive slot**. Its
  `holdSandboxOpen` checks for an `InteractiveStartCommand` first; if
  none, it serves normal renders like slot 0. When a start arrives,
  it transitions into the held-rule loop until the matching
  `InteractiveStopCommand` returns control.
- Concurrent normal renders against the interactive slot's preview
  during a held session route to slot 0 (or other free slots) via the
  existing affinity-aware dispatch.
- `RobolectricHost.acquireInteractiveSession` is **supported iff
  `sandboxCount >= 2`**. With `sandboxCount == 1` the existing single
  sandbox can't be sacrificed without taking normal renders down, so
  we throw `UnsupportedOperationException` and `JsonRpcServer` falls
  back to v1; the panel surfaces the v1-fallback hint via #431.

`ServerCapabilities.interactive = (sandboxCount >= 2)` for the
Android backend. The capability is daemon-level, not per-call, so
the panel sees it once at `initialize` time.

### 2.1 Why not multi-target on Android

Each held interactive session pins one slot. Multi-target means
multiple slots, which means more sandboxes. The default `sandboxCount`
for Android is 1; raising it costs cold-boot wall-clock and per-slot
RAM (every Robolectric sandbox carries a copy of `android-all`).

v3 ships single-target on Android: one held session at a time. A
second `acquireInteractiveSession` call with the first session still
open throws `UnsupportedOperationException` for the second; the panel
falls back to v1 for that stream. The wire's multi-stream support
stays intact — the daemon-side limit is a host capability, not a
protocol restriction. v4 (or whenever someone needs it) lifts the cap
by reserving multiple slots.

## 3. Bridge primitives — what crosses the boundary

Mirroring the existing `RenderRequest` ↔ `RenderResult` plumbing on
the bridge, but for interactive commands. New types live in the
`bridge` package so they're loaded once by the system classloader and
visible identically from both sides.

```kotlin
// bridge/DaemonHostBridge.kt — additive

/**
 * Cross-classloader command for the held-rule loop. The sandbox-side
 * SandboxRunner reads from [SandboxSlot.interactiveCommands] when the
 * slot is in interactive-mode and routes each command to the held
 * rule's main thread.
 */
sealed interface InteractiveCommand {
    val streamId: String

    /** Allocate the rule + ActivityScenario, run setContent, signal ready. */
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
        // Filled in by the sandbox before counting down the latch.
        val replyError: AtomicReference<Throwable?>,
    ) : InteractiveCommand

    /** Synthesise + dispatch a MotionEvent on the rule's main thread. */
    data class Dispatch(
        override val streamId: String,
        val kind: String, // "click" | "pointerDown" | "pointerUp"; key events are no-op for v3
        val pixelX: Int,
        val pixelY: Int,
        val replyLatch: CountDownLatch,
        val replyError: AtomicReference<Throwable?>,
    ) : InteractiveCommand

    /**
     * Capture current pixels via the same `captureRoboImage` path the
     * one-shot RenderEngine uses, and emit a RenderResult on the
     * shared results queue keyed by [requestId].
     */
    data class Render(
        override val streamId: String,
        val requestId: Long,
    ) : InteractiveCommand

    /** Tear down the rule + ActivityScenario; the held statement returns. */
    data class Close(
        override val streamId: String,
        val replyLatch: CountDownLatch,
    ) : InteractiveCommand
}
```

Per-slot state added to `SandboxSlot`:

```kotlin
@JvmField val interactiveCommands: LinkedBlockingQueue<InteractiveCommand> =
    LinkedBlockingQueue()
```

`Render`'s result rides the existing
`DaemonHostBridge.results` map, keyed by `requestId`. The host-side
`AndroidInteractiveSession.render()` polls that map exactly like the
v1 `RobolectricHost.submit` does today — no new result channel.

`Start` / `Dispatch` / `Close` get reply latches because the host
needs to know when the sandbox-side work has completed (e.g.
`Start` has to wait for `setContent` to land before subsequent
`Dispatch`es race the composition).

## 4. Sandbox-side held-rule lifecycle

`SandboxRunner.holdSandboxOpen` grows an outer state machine: free
(serving normal renders) ↔ pinned (in a held interactive session).

```kotlin
@Test
fun holdSandboxOpen() {
    val slotIndex = DaemonHostBridge.registerSandbox(this.javaClass.classLoader!!)
    val slot = DaemonHostBridge.slot(slotIndex)

    while (!DaemonHostBridge.shutdown.get()) {
        // Interactive commands take priority — a pending Start should pin the slot
        // before the next normal render lands.
        val interactive = slot.interactiveCommands.poll()
        if (interactive is InteractiveCommand.Start) {
            runHeldInteractiveSession(slot, interactive)
            continue
        }
        if (interactive != null) {
            // Dispatch / Render / Close arriving without a held session is a wire-
            // level race — log + drop. Should never happen if the host's host-side
            // session lifecycle is correct.
            System.err.println("compose-ai-daemon: orphan interactive command: $interactive")
            continue
        }
        // Existing normal-render path.
        val request = slot.requests.poll(100, TimeUnit.MILLISECONDS) ?: continue
        // ... existing dispatch logic.
    }
}

private fun runHeldInteractiveSession(slot: SandboxSlot, start: InteractiveCommand.Start) {
    @Suppress("DEPRECATION")
    val rule = createAndroidComposeRule<ComponentActivity>()
    val description =
        Description.createTestDescription(
            RobolectricHost.SandboxRunner::class.java,
            "interactive_${start.streamId}",
        )
    val ruleStatement = object : Statement() {
        override fun evaluate() {
            // 1. setContent — runs once; held alive for the session's duration.
            val composableMethod = resolveComposableMethod(start)
            rule.mainClock.autoAdvance = false
            rule.setContent {
                CompositionLocalProvider(LocalInspectionMode provides false) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        InvokeComposable(composableMethod)
                    }
                }
            }
            rule.mainClock.advanceTimeBy(CAPTURE_ADVANCE_MS)
            start.replyLatch.countDown()

            // 2. drain interactive commands until Close.
            while (true) {
                val cmd = slot.interactiveCommands.take()
                when (cmd) {
                    is InteractiveCommand.Dispatch -> {
                        try {
                            dispatchMotionEventOnMainThread(rule, cmd)
                            rule.mainClock.advanceTimeBy(POINTER_HOLD_MS)
                        } catch (t: Throwable) {
                            cmd.replyError.set(t)
                        } finally {
                            cmd.replyLatch.countDown()
                        }
                    }
                    is InteractiveCommand.Render -> {
                        captureAndPostResult(rule, cmd, start)
                    }
                    is InteractiveCommand.Close -> {
                        cmd.replyLatch.countDown()
                        return // exits evaluate(); rule's wrapper closes scenario
                    }
                    is InteractiveCommand.Start -> {
                        System.err.println(
                            "compose-ai-daemon: nested interactive/start for stream ${cmd.streamId} " +
                                "while ${start.streamId} is still held; dropped"
                        )
                        cmd.replyError.set(IllegalStateException("nested interactive/start"))
                        cmd.replyLatch.countDown()
                    }
                }
            }
        }
    }
    try {
        rule.apply(ruleStatement, description).evaluate()
    } catch (t: Throwable) {
        // Rule lifecycle failed (e.g. setContent threw). Surface to the start
        // command's reply so the host can fail the session cleanly.
        if (start.replyLatch.count > 0) {
            start.replyError.set(t)
            start.replyLatch.countDown()
        }
    }
}
```

Key invariants:

- The rule's wrapper closes the `ActivityScenario` only after
  `evaluate()` returns — i.e. on `InteractiveCommand.Close`.
- `setContent` is called **exactly once** per session, before the
  drain loop. Compose's "no second setContent on the same Activity"
  constraint is satisfied.
- `LocalInspectionMode = false` for the duration of the session, so
  `Modifier.clickable` / `pointerInput` modifiers actually fire.
- Compose's `mainClock` is paused; we advance it manually after each
  dispatch + before each render so the gesture-detection pipeline
  observes time progression. Same `CAPTURE_ADVANCE_MS` constant the
  existing `RenderEngine` uses (≈ 2 Choreographer frames). After a
  `Dispatch`, advance by `POINTER_HOLD_MS` (suggested 100 ms — matches
  the desktop session's `CLICK_HOLD_MS`).
- A nested `Start` while a session is held is an error; we reply with
  `IllegalStateException` so the host throws `Unsupported`.

## 5. MotionEvent dispatch — the open question

Compose's pointer-input pipeline reads `MotionEvent`s posted to a
`View`'s `dispatchTouchEvent` on the main looper. We need to:

1. Find the rule's root `ComposeView` (the view Compose is attached
   to inside the test rule's host activity).
2. Synthesise a `MotionEvent` with the wire-supplied pixel coords
   scaled to the view's pixel space.
3. Dispatch on the main looper.

Two viable approaches, neither fully verified:

**Option A: explicit `View.dispatchTouchEvent` on the main looper.**

```kotlin
private fun dispatchMotionEventOnMainThread(
    rule: AndroidComposeTestRule<*, *>,
    cmd: InteractiveCommand.Dispatch,
) {
    rule.runOnUiThread {
        val activity = rule.activity
        val rootView = activity.window.decorView
        val now = SystemClock.uptimeMillis()
        when (cmd.kind) {
            "click" -> {
                val down = MotionEvent.obtain(
                    now, now,
                    MotionEvent.ACTION_DOWN,
                    cmd.pixelX.toFloat(), cmd.pixelY.toFloat(), 0
                )
                val up = MotionEvent.obtain(
                    now, now + POINTER_HOLD_MS,
                    MotionEvent.ACTION_UP,
                    cmd.pixelX.toFloat(), cmd.pixelY.toFloat(), 0
                )
                try {
                    rootView.dispatchTouchEvent(down)
                    rootView.dispatchTouchEvent(up)
                } finally {
                    down.recycle()
                    up.recycle()
                }
            }
            // pointerDown / pointerUp similar with single events.
        }
    }
}
```

**Risk:** Robolectric's main-looper handling under paused-clock mode
may delay the dispatch until `mainClock.advanceTimeBy` fires. Need to
verify `runOnUiThread` is synchronous-enough that the events land
before the next clock advance. Compose's `MainTestClock` model
*should* allow this — `runOnUiThread` is a synchronous call from the
test thread to the main thread under Robolectric — but there's a real
chance of interaction with the paused clock that needs empirical
verification.

**Option B: Compose UI test API (`SemanticsNodeInteraction.performClick()` etc.).**

```kotlin
rule.onRoot().performTouchInput {
    down(Offset(cmd.pixelX.toFloat(), cmd.pixelY.toFloat()))
    advanceEventTime(POINTER_HOLD_MS)
    up()
}
```

**Risk:** `performTouchInput` requires the Compose semantic tree to
be attached, which it is inside the rule. But `advanceEventTime`
inside the lambda may not correctly drive the rule's `mainClock`; we
might need to wrap with explicit `rule.mainClock.advanceTimeBy`
calls.

**Recommendation for the spike PR:** start with Option A (explicit
`MotionEvent` + `dispatchTouchEvent`). It's the lowest-magic path
and exactly what a real device's touch driver does. If Robolectric's
paused clock interferes, fall back to Option B.

## 6. Capture path — the other open question

The existing `RenderEngine.render` calls `rule.onRoot().captureRoboImage(...)`.
That requires:

- The rule still being in scope.
- The semantic tree (`onRoot()`) being attached.

Both are true inside our held statement, so the same call should
work. The only difference is we re-capture per `Render` command
instead of once per `evaluate()`.

```kotlin
private fun captureAndPostResult(
    rule: AndroidComposeTestRule<*, *>,
    cmd: InteractiveCommand.Render,
    start: InteractiveCommand.Start,
) {
    val outputFile = File(outputDir, "${start.outputBaseName}.png")
    outputFile.parentFile?.mkdirs()
    rule.mainClock.advanceTimeBy(CAPTURE_ADVANCE_MS)
    rule.onRoot().captureRoboImage(
        file = outputFile,
        roborazziOptions = roborazziOptions(start.device),
    )
    val result = RenderResult(
        id = cmd.requestId,
        classLoaderHashCode = System.identityHashCode(this.javaClass.classLoader),
        classLoaderName = this.javaClass.classLoader?.javaClass?.name ?: "<null>",
        pngPath = outputFile.absolutePath,
        metrics = mapOf("tookMs" to /* compute */ 0L),
    )
    DaemonHostBridge.results
        .computeIfAbsent(cmd.requestId) { LinkedBlockingQueue() }
        .put(result)
}
```

**Risk:** `captureRoboImage` re-uses the same output path on each
call. The host reads bytes off disk after seeing the
`renderFinished` notification, so as long as the writes are atomic
the read won't observe a half-written PNG. Roborazzi does write +
fsync atomically per call; verified empirically on the existing
render path.

**Lifecycle-on-classpath-dirty.** When the user's source recompiles
(`fileChanged{kind: "source"}`) mid-session, the held composition's
`Class.forName` references stale bytecode. The session needs to be
torn down and re-allocated on classpath dirty. Concrete plan: the
host listens for `swapUserClassLoaders()` and posts an
`InteractiveCommand.Close` to its active session's slot. The panel's
`channelClosed` cleanup (#424) catches the symmetric extension-side
state cleanup.

## 7. Host-side `AndroidInteractiveSession`

Mirrors `DesktopInteractiveSession` (#408) at the protocol surface.
Differences are all about cross-classloader marshalling.

```kotlin
class AndroidInteractiveSession(
    override val previewId: String,
    private val streamId: String,
    private val slot: SandboxSlot,
) : InteractiveSession {

    override fun dispatch(input: InteractiveInputParams) {
        val replyLatch = CountDownLatch(1)
        val replyError = AtomicReference<Throwable?>(null)
        val cmd = InteractiveCommand.Dispatch(
            streamId = streamId,
            kind = input.kind.name.lowercase(),
            pixelX = input.pixelX ?: 0,
            pixelY = input.pixelY ?: 0,
            replyLatch = replyLatch,
            replyError = replyError,
        )
        slot.interactiveCommands.put(cmd)
        if (!replyLatch.await(30, TimeUnit.SECONDS)) {
            error("interactive dispatch timed out for stream $streamId")
        }
        replyError.get()?.let { throw it }
    }

    override fun render(requestId: Long): RenderResult {
        slot.interactiveCommands.put(InteractiveCommand.Render(streamId, requestId))
        val q = DaemonHostBridge.results.computeIfAbsent(requestId) { LinkedBlockingQueue() }
        val raw = q.poll(60, TimeUnit.SECONDS)
            ?: error("interactive render timed out for stream $streamId, request $requestId")
        DaemonHostBridge.results.remove(requestId)
        if (raw is Throwable) throw raw
        return raw as RenderResult
    }

    override fun close() {
        val replyLatch = CountDownLatch(1)
        slot.interactiveCommands.put(InteractiveCommand.Close(streamId, replyLatch))
        replyLatch.await(10, TimeUnit.SECONDS)
    }
}
```

`RobolectricHost.acquireInteractiveSession`:

```kotlin
override fun acquireInteractiveSession(
    previewId: String,
    classLoader: ClassLoader,
): InteractiveSession {
    if (sandboxCount < 2) {
        throw UnsupportedOperationException(
            "RobolectricHost: interactive sessions require sandboxCount >= 2 " +
                "(have $sandboxCount); set composeai.daemon.sandboxCount=2 or higher"
        )
    }
    val activeStream = activeInteractiveStreamId.get()
    if (activeStream != null) {
        throw UnsupportedOperationException(
            "RobolectricHost: an interactive session is already active for stream " +
                "'$activeStream'; v3 supports one held session at a time"
        )
    }
    val resolver = previewSpecResolver ?: throw UnsupportedOperationException(...)
    val spec = resolver(previewId) ?: throw UnsupportedOperationException(...)

    val streamId = "android-stream-${nextStreamCounter.getAndIncrement()}"
    val slot = DaemonHostBridge.slot(INTERACTIVE_SLOT_INDEX)
    val replyLatch = CountDownLatch(1)
    val replyError = AtomicReference<Throwable?>(null)
    slot.interactiveCommands.put(
        InteractiveCommand.Start(
            streamId = streamId,
            previewClassName = spec.className,
            previewFunctionName = spec.functionName,
            widthPx = spec.widthPx,
            heightPx = spec.heightPx,
            density = spec.density,
            backgroundColor = spec.backgroundColor,
            showBackground = spec.showBackground,
            device = spec.device,
            outputBaseName = spec.outputBaseName,
            replyLatch = replyLatch,
            replyError = replyError,
        ),
    )
    if (!replyLatch.await(30, TimeUnit.SECONDS)) {
        throw UnsupportedOperationException("interactive/start timed out")
    }
    replyError.get()?.let { throw UnsupportedOperationException("interactive/start failed: ${it.message}") }
    activeInteractiveStreamId.set(streamId)
    return AndroidInteractiveSession(previewId, streamId, slot)
}

override val supportsInteractive: Boolean
    get() = sandboxCount >= 2 && previewSpecResolver != null
```

`INTERACTIVE_SLOT_INDEX = 1` for v3. Slot 0 stays the always-on
normal-render slot.

## 8. DaemonMain wiring

Mirror the desktop wiring (#408 / [DaemonMain](../../daemon/desktop/src/main/kotlin/ee/schimke/composeai/daemon/DaemonMain.kt)):

- Reorder so `previewIndex` loads before `RobolectricHost` is
  constructed.
- Pass `previewSpecResolver = previewIndexBackedSpecResolver(...)` into
  the host.
- Default `sandboxCount` raised to 2 when interactive is wanted —
  configurable via `composeai.daemon.sandboxCount` sysprop. Without
  this, the resolver is wired but `acquireInteractiveSession`
  throws Unsupported (correctly), and the panel falls back to v1.

## 9. Integration test

`AndroidInteractiveSessionTest` mirrors the desktop test:

- `ClickToggleSquare` Android composable — same red-on-first,
  green-on-click shape but Android-flavoured (`Modifier.pointerInput {
  awaitFirstDown() }`).
- Construct a `RobolectricHost(sandboxCount = 2,
  previewSpecResolver = ...)`.
- Acquire the session, capture first frame → assert ≥ 95% red.
- Dispatch a click in the centre.
- Capture second frame → assert ≥ 95% green.

Same pixel-match helper inlined as in the desktop test.

**Sandbox cold-boot cost.** With `sandboxCount = 2` the test pays
~2× the cold-boot wall-clock of single-sandbox tests (each sandbox
downloads + instruments `android-all` independently on a cold cache).
On a warm cache it's ≤ 5 s extra. Acceptable for a single integration
test; we don't multiply this across the harness.

## 10. PR rollout — three pieces

1. **PR A: bridge primitives + capability advertisement.** Adds
   `InteractiveCommand` types in the bridge package, `interactiveCommands`
   queue on `SandboxSlot`, `RobolectricHost.supportsInteractive`
   getter, and unit tests for the bridge wire shape. Capability bit
   stays effectively false because no slot drains the queue yet —
   `acquireInteractiveSession` still throws `Unsupported`. Lands the
   wire surface so PR B doesn't need a wire bump. ~150 lines.
2. **PR B: held-rule loop + session class.** The substantive work.
   `SandboxRunner.holdSandboxOpen` grows the interactive branch,
   `AndroidInteractiveSession` ships, `RobolectricHost.acquireInteractiveSession`
   succeeds when `sandboxCount >= 2`. Integration test included.
   ~600 lines + maybe a Robolectric fight or two.
3. **PR C: lifecycle hardening.** Classpath-dirty handling,
   sandbox-recycle interaction, daemon-shutdown drain semantics,
   dimensions threaded through `previewSpecResolver` from
   `PreviewInfoDto.params` (#439 already widened the index — just need
   to consume). ~200 lines + tests.

Total: ~950 lines + the v3 test infrastructure. PR A is the safe
landing; PR B is where the Robolectric-internals risk actually
materialises.

## 11. Open questions for whoever picks this up

1. **`runOnUiThread` + paused clock.** Does `dispatchTouchEvent`
   inside `rule.runOnUiThread` actually deliver to Compose's
   pointer-input pipeline before the next clock advance? Empirical
   verification needed — write a 30-line probe test before sinking
   PR B time.
2. **Multi-target.** The single-session restriction is a daemon-side
   capacity limit, not a wire limit. If it bites, lifting it means
   reserving slots 1..N as an interactive pool with capacity N-1,
   `sandboxCount` raised to N+1. Each held session takes one slot.
3. **Sandbox-pool interaction.** When sandbox-recycle (B2.5) lands,
   the interactive slot needs to be excluded from recycling while a
   session is held. The recycler reads `activeInteractiveStreamId`
   to know.
4. **Dispatch-on-shutdown.** What happens when the daemon shuts down
   while a session is held? Easiest: the host's `shutdown()` posts
   `Close` to every active session before the slot's poison pill so
   the held statement returns cleanly.
5. **Hardware-renderer state across captures.** Roborazzi's
   `captureRoboImage` opens an `ImageReader` + `HardwareRenderer` per
   call. On a held composition, multiple captures might race or
   accumulate state. Verify on a multi-render integration test
   before declaring victory.
