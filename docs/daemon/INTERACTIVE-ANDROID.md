# Interactive mode on Android (Robolectric) — v3 architecture

Companion to [INTERACTIVE.md § 9](INTERACTIVE.md#9-v2-click-dispatch-into-composition-renderhost-surface).
This doc is the Android/Robolectric architecture behind the
sandbox-pinning approach.

## 1. The Robolectric constraint

The Android render path (`daemon/android/.../RenderEngine.kt`)
constructs `createAndroidComposeRule<ComponentActivity>()` *per
render*, and the rule's `apply(statement, description).evaluate()`
creates the `ActivityScenario` at the start of `evaluate()` and
**closes it when `evaluate()` returns**. The Compose test rule also
forbids a second `setContent` on the same `ComponentActivity`, so
holding the rule across renders without changing scope is the only
way to keep `remember { mutableStateOf(...) }` alive across inputs.

Three sub-constraints follow:

1. **Held-statement-blocks-its-thread.** If the rule's wrapped
   `Statement.evaluate()` blocks (waiting for further `dispatch` /
   `render` commands), the calling thread blocks too. The
   `RobolectricHost` model lives inside one such held-statement —
   `SandboxRunner.holdSandboxOpen` is a long-running `@Test` method
   that drains the request queue. Holding *another* statement (the
   rule's wrapper) inside that `@Test` would block the queue-drain
   loop entirely.
2. **Bridge classloader-identity dance.** Anything that crosses the
   sandbox boundary has to be either a `java.*` type or live in
   `bridge.DaemonHostBridge`'s do-not-acquire package. Compose
   pointer types and Roborazzi capture types fail that test — they
   have to be constructed *inside* the sandbox.
3. **MotionEvent dispatch on the main looper.** `View.dispatchTouchEvent`
   on the main looper is what Compose's pointer-input pipeline picks
   up. Robolectric's main looper exists per sandbox; commands from
   the host have to be marshalled onto it via `Handler` /
   `Looper.getMainLooper()`.

## 2. Sandbox pinning

Architecture:

- **Slot 0** stays the always-on normal-render slot. Its
  `holdSandboxOpen` keeps draining the per-slot request queue.
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
  back to v1.

`ServerCapabilities.interactive = (sandboxCount >= 2)` for the
Android backend. The capability is daemon-level, not per-call.

### 2.1 One held session per host

Each held interactive session pins one slot. v3 ships single-target on
Android: one held session at a time. A second
`acquireInteractiveSession` call with the first session still open
throws `UnsupportedOperationException` for the second; the panel falls
back to v1 for that stream. The wire's multi-stream support stays
intact — the daemon-side limit is a host capability, not a protocol
restriction.

## 3. Inbound interactive command queue

New types live in the `bridge` package so they're loaded once by the
system classloader and visible identically from both sides.

```kotlin
// bridge/DaemonHostBridge.kt — additive

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
        val replyError: AtomicReference<Throwable?>,
    ) : InteractiveCommand

    /** Synthesise + dispatch a MotionEvent on the rule's main thread. */
    data class Dispatch(
        override val streamId: String,
        val kind: String, // "click" | "pointerDown" | "pointerUp"
        val pixelX: Int,
        val pixelY: Int,
        val replyLatch: CountDownLatch,
        val replyError: AtomicReference<Throwable?>,
    ) : InteractiveCommand

    /** Capture pixels via captureRoboImage; emit a RenderResult on the
     *  shared results queue keyed by [requestId]. */
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

`Render`'s result rides the existing `DaemonHostBridge.results` map,
keyed by `requestId`. `Start` / `Dispatch` / `Close` get reply latches
because the host needs to know when the sandbox-side work has
completed.

**Drain on host swap.** When the host swaps user classloaders
(`fileChanged{kind: "source"}`), the host posts an
`InteractiveCommand.Close` to its active session's slot to drain the
queued state cleanly before allocating a new session.

## 4. Held-rule loop

`SandboxRunner.holdSandboxOpen` grows an outer state machine: free
(serving normal renders) ↔ pinned (in a held interactive session).

```kotlin
@Test
fun holdSandboxOpen() {
    val slotIndex = DaemonHostBridge.registerSandbox(this.javaClass.classLoader!!)
    val slot = DaemonHostBridge.slot(slotIndex)

    while (!DaemonHostBridge.shutdown.get()) {
        val interactive = slot.interactiveCommands.poll()
        if (interactive is InteractiveCommand.Start) {
            runHeldInteractiveSession(slot, interactive)
            continue
        }
        if (interactive != null) {
            // Dispatch / Render / Close arriving without a held session is a
            // wire-level race — log + drop.
            System.err.println("compose-ai-daemon: orphan interactive command: $interactive")
            continue
        }
        val request = slot.requests.poll(100, TimeUnit.MILLISECONDS) ?: continue
        // ... existing dispatch logic.
    }
}

private fun runHeldInteractiveSession(slot: SandboxSlot, start: InteractiveCommand.Start) {
    @Suppress("DEPRECATION")
    val rule = createAndroidComposeRule<ComponentActivity>()
    val description = Description.createTestDescription(
        RobolectricHost.SandboxRunner::class.java,
        "interactive_${start.streamId}",
    )
    val ruleStatement = object : Statement() {
        override fun evaluate() {
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
                    is InteractiveCommand.Render -> captureAndPostResult(rule, cmd, start)
                    is InteractiveCommand.Close -> {
                        cmd.replyLatch.countDown()
                        return
                    }
                    is InteractiveCommand.Start -> {
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
  drain loop.
- `LocalInspectionMode = false` by default for the duration of the
  session. `interactive/start.inspectionMode=true` opts back in.
- Compose's `mainClock` is paused; advance manually after each
  dispatch + before each render. `CAPTURE_ADVANCE_MS` matches the
  existing `RenderEngine`; `POINTER_HOLD_MS` is 100ms.
- **Nested `Start` while a session is held is an error.** Reply with
  `IllegalStateException` so the host throws `Unsupported`.

## 5. Interactive input dispatch

Compose's pointer-input pipeline reads `MotionEvent`s posted to a
`View`'s `dispatchTouchEvent` on the main looper. Approach: explicit
`View.dispatchTouchEvent` on the main looper.

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

## 6. Lifecycle on classpath dirty

When the user's source recompiles (`fileChanged{kind: "source"}`)
mid-session, the held composition's `Class.forName` references stale
bytecode. The session is torn down and re-allocated on classpath dirty.
The host listens for `swapUserClassLoaders()` and posts an
`InteractiveCommand.Close` to its active session's slot. The panel's
`channelClosed` cleanup catches the symmetric extension-side state
cleanup.

## 7. RobolectricHost.acquireInteractiveSession

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
    replyError.get()?.let {
        throw UnsupportedOperationException("interactive/start failed: ${it.message}")
    }
    activeInteractiveStreamId.set(streamId)
    return AndroidInteractiveSession(previewId, streamId, slot)
}

override val supportsInteractive: Boolean
    get() = sandboxCount >= 2 && previewSpecResolver != null
```

`INTERACTIVE_SLOT_INDEX = 1` for v3. Slot 0 stays the always-on
normal-render slot.

## 10.3 RenderSpec qualifier set / PR C

PR C threads dimensions through `previewSpecResolver` from
`PreviewInfoDto.params`. The resolver returns the same qualifier set
the one-shot `RenderEngine` uses, so held sessions render at the
correct width/height/density.

## 11.4 Dispatch-on-shutdown / client disconnect

When the daemon shuts down while a session is held, the host's
`shutdown()` posts `Close` to every active session before the slot's
poison pill so the held statement returns cleanly. Symmetric path for
client disconnect: `channelClosed` cleanup posts `Close` for every
session belonging to the disconnected client.
