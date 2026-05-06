package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.InteractiveInputParams
import ee.schimke.composeai.daemon.protocol.RecordingFormat
import ee.schimke.composeai.daemon.protocol.RecordingInputParams
import ee.schimke.composeai.daemon.protocol.RecordingScriptEvent
import ee.schimke.composeai.daemon.protocol.RecordingScriptEvidence
import ee.schimke.composeai.data.render.extensions.RecordingScriptDataExtensions
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import javax.imageio.ImageIO

/**
 * Android (Robolectric) [RecordingSession]. Wraps an [AndroidInteractiveSession] (the v3 held-rule
 * loop documented in
 * [INTERACTIVE-ANDROID.md](../../../../../../docs/daemon/INTERACTIVE-ANDROID.md)) and replays a
 * scripted timeline frame-by-frame on top of it.
 *
 * **Why wrap interactive.** The held-rule loop already handles all the hard cross-classloader
 * marshalling — `MotionEvent` synthesis, `mainClock` advance, `captureRoboImage` — and runs
 * inside Robolectric's instrumented sandbox. Recording layers on top: at [stop] we walk the
 * timeline frame-by-frame, calling `interactive.dispatch` for events whose `tMs` has elapsed and
 * `interactive.render` to capture each frame. Each render's PNG is copied (and scaled if
 * `scale != 1.0`) into a per-frame path the encoder reads.
 *
 * **Slot pinning trade-off.** Android v3 supports only one held session at a time per host, so a
 * recording in progress excludes a concurrent interactive session against any preview in this
 * host. Documented constraint; we'll lift it when v4 ships multi-target Android.
 *
 * **Live mode.** Real-time focus-view recording uses a background tick thread that drains
 * `recording/input` events and calls [InteractiveSession.render] once per frame. This intentionally
 * reuses the held interactive bridge instead of introducing a separate sandbox runner; it favours a
 * narrow, functional implementation over ideal Android throughput.
 *
 * **Virtual-time fidelity.** Each captured frame passes an explicit virtual-clock delta into the
 * held-rule loop, so animation time advances according to [fps] instead of the interactive
 * backend's default render-settle window.
 */
class AndroidRecordingSession(
  override val previewId: String,
  override val recordingId: String,
  override val fps: Int,
  override val scale: Float,
  override val live: Boolean = false,
  private val interactive: InteractiveSession,
  private val framesDir: File,
  private val encodedDir: File,
) : RecordingSession {

  private val timeline = mutableListOf<RecordingScriptEvent>()

  private val liveInputs = ConcurrentLinkedQueue<RecordingInputParams>()

  @Volatile private var stopped: Boolean = false

  @Volatile private var closed: Boolean = false

  @Volatile private var liveStopRequested: Boolean = false

  @Volatile private var liveFrameCount: Int = 0

  @Volatile private var liveDurationMs: Long = 0L

  @Volatile private var liveFrameWidthPx: Int = 0

  @Volatile private var liveFrameHeightPx: Int = 0

  @Volatile private var liveFailure: Throwable? = null

  private var result: RecordingResult? = null

  private val liveTickThread: Thread? =
    if (live) {
      framesDir.mkdirs()
      Thread({ runLiveTickLoop() }, "compose-ai-daemon-android-recording-live-$recordingId")
        .apply {
          isDaemon = true
          uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, t ->
            if (liveFailure == null) liveFailure = t
            System.err.println(
              "compose-ai-daemon: AndroidRecordingSession($recordingId) tick-thread uncaught " +
                "${t.javaClass.simpleName}: ${t.message}"
            )
          }
          start()
        }
    } else null

  override fun postScript(events: List<RecordingScriptEvent>) {
    check(!live) {
      "AndroidRecordingSession.postScript called on a live recording (recordingId='$recordingId'); " +
        "use postInput for live mode"
    }
    check(!stopped) {
      "AndroidRecordingSession.postScript called after stop() for recordingId='$recordingId'"
    }
    if (events.isEmpty()) return
    synchronized(timeline) {
      for (e in events) {
        require(e.tMs >= 0) { "RecordingScriptEvent.tMs must be ≥ 0; got ${e.tMs}" }
        timeline.add(e)
      }
      timeline.sortBy { it.tMs }
    }
  }

  override fun postInput(input: RecordingInputParams) {
    check(live) {
      "AndroidRecordingSession.postInput called on a scripted recording " +
        "(recordingId='$recordingId'); use postScript for scripted mode"
    }
    if (stopped) return
    liveInputs.add(input)
  }

  override fun stop(): RecordingResult {
    val cached = result
    if (cached != null) return cached
    if (stopped) error("AndroidRecordingSession.stop() called twice without a cached result")
    stopped = true
    return try {
      val r = if (live) stopLive() else stopScripted()
      result = r
      r
    } finally {
      // Release the held interactive session — frees the pinned sandbox slot for the next caller.
      // close() is idempotent; the explicit close() call here mirrors DesktopRecordingSession's
      // engine.tearDown and runs even when the playback loop threw.
      try {
        interactive.close()
      } catch (t: Throwable) {
        System.err.println(
          "compose-ai-daemon: AndroidRecordingSession.stop($recordingId): interactive.close threw " +
            "${t.javaClass.simpleName}: ${t.message}; continuing"
        )
      }
    }
  }

  private fun stopScripted(): RecordingResult {
    framesDir.mkdirs()
    val sortedEvents = synchronized(timeline) { timeline.toList() }
    val durationMs: Long = sortedEvents.maxOfOrNull { it.tMs } ?: 0L
    val totalFrames = ((durationMs * fps + 999L) / 1000L).toInt() + 1

    // Probe the natural frame size from the first interactive render so the per-frame copies +
    // optional scaling know the output dimensions without hard-coding the spec. Robolectric
    // captures whatever the held composition rendered to, so this is the authoritative source.
    val startNs = System.nanoTime()
    var nextEventIdx = 0
    var lastFrameTimeMs = 0L
    var frameWidthPx = 0
    var frameHeightPx = 0
    val evidence = mutableListOf<RecordingScriptEvidence>()
    for (frameIndex in 0 until totalFrames) {
      val tMs: Long = frameIndex.toLong() * 1000L / fps.toLong()

      // Drain script events whose virtual tMs has elapsed by this frame's tMs and dispatch each
      // through the held-rule loop. Translation back through the InteractiveInputParams shape
      // because that's what AndroidInteractiveSession.dispatch consumes; the recordingId field
      // doesn't matter on the host side (the underlying session has its own streamId).
      while (nextEventIdx < sortedEvents.size && sortedEvents[nextEventIdx].tMs <= tMs) {
        val e = sortedEvents[nextEventIdx]
        val ctx = SimpleRecordingDispatchContext(tNanos = tMs * 1_000_000L, tMs = tMs)
        evidence.add(scriptHandlers.dispatch(e, ctx))
        nextEventIdx++
      }

      val advanceTimeMs = if (frameIndex == 0) 0L else tMs - lastFrameTimeMs
      lastFrameTimeMs = tMs
      val rendered =
        interactive.render(
          requestId = RenderHost.nextRequestId(),
          advanceTimeMs = advanceTimeMs,
        )
      val srcPath =
        rendered.pngPath
          ?: error(
            "AndroidRecordingSession: interactive.render returned no pngPath at frame $frameIndex"
          )
      val dst = File(framesDir, "frame-${"%05d".format(frameIndex)}.png")
      val dims = copyAndMaybeScale(File(srcPath), dst, scale)
      if (frameWidthPx == 0) {
        frameWidthPx = dims.first
        frameHeightPx = dims.second
      }
    }
    val tookMs = (System.nanoTime() - startNs) / 1_000_000L
    System.err.println(
      "compose-ai-daemon: AndroidRecordingSession.stop($recordingId): rendered $totalFrames " +
        "frame(s) covering ${durationMs}ms scripted time in ${tookMs}ms wall " +
        "(scale=$scale, fps=$fps, ${frameWidthPx}x${frameHeightPx}px)"
    )
    return RecordingResult(
      frameCount = totalFrames,
      durationMs = durationMs,
      framesDir = framesDir.absolutePath,
      frameWidthPx = frameWidthPx,
      frameHeightPx = frameHeightPx,
      scriptEvents = evidence,
    )
  }

  private fun stopLive(): RecordingResult {
    liveStopRequested = true
    liveTickThread?.let { thread ->
      thread.join(10_000)
      if (thread.isAlive) {
        System.err.println(
          "compose-ai-daemon: AndroidRecordingSession.stop($recordingId, live): " +
            "tick thread did not exit within 10s; continuing anyway"
        )
      }
    }
    liveFailure?.let { failure ->
      throw IllegalStateException(
        "live Android recording '$recordingId' failed mid-flight: " +
          "${failure.javaClass.simpleName}: ${failure.message}",
        failure,
      )
    }
    return RecordingResult(
      frameCount = liveFrameCount,
      durationMs = liveDurationMs,
      framesDir = framesDir.absolutePath,
      frameWidthPx = liveFrameWidthPx,
      frameHeightPx = liveFrameHeightPx,
    )
  }

  /**
   * Per-session script-event handler registry. Built once so each handler closes over [interactive];
   * [stopScripted]'s loop just calls `scriptHandlers.dispatch(...)` and never branches on event
   * kind directly.
   *
   * Built-in input kinds (`click`, `pointerDown`, `pointerMove`, `pointerUp`, `rotaryScroll`) all
   * route through the held-rule loop's [InteractiveSession.dispatch] — same path the live tick loop
   * uses. `keyDown` / `keyUp` register as unsupported (Robolectric key dispatch is a follow-up).
   * The probe extension handler appears once, here, instead of as a dispatch-loop special case.
   */
  private val scriptHandlers: RecordingScriptHandlerRegistry = buildScriptHandlers()

  private fun buildScriptHandlers(): RecordingScriptHandlerRegistry =
    RecordingScriptHandlerRegistry(
      buildMap {
        put(
          InputTouchRecordingScriptEvents.CLICK_EVENT,
          interactiveDispatchHandler(InteractiveInputKind.CLICK),
        )
        put(
          InputTouchRecordingScriptEvents.POINTER_DOWN_EVENT,
          interactiveDispatchHandler(InteractiveInputKind.POINTER_DOWN),
        )
        put(
          InputTouchRecordingScriptEvents.POINTER_MOVE_EVENT,
          interactiveDispatchHandler(InteractiveInputKind.POINTER_MOVE),
        )
        put(
          InputTouchRecordingScriptEvents.POINTER_UP_EVENT,
          interactiveDispatchHandler(InteractiveInputKind.POINTER_UP),
        )
        put(
          InputRsbRecordingScriptEvents.ROTARY_SCROLL_EVENT,
          interactiveDispatchHandler(InteractiveInputKind.ROTARY_SCROLL),
        )
        put(InputKeyboardRecordingScriptEvents.KEY_DOWN_EVENT, androidUnsupported("keyDown"))
        put(InputKeyboardRecordingScriptEvents.KEY_UP_EVENT, androidUnsupported("keyUp"))
        put(RecordingScriptDataExtensions.PROBE_EVENT, RecordingScriptEventHandler { e, _ ->
          appliedEvidence(e, "probe marker reached")
        })
        // Accessibility-driven dispatch — every `a11y.action.<name>` id with a clean Compose
        // SemanticsActions equivalent registers here. Each handler shares the same shape:
        // resolve a node by `nodeContentDescription`, route through
        // `interactive.dispatchSemanticsAction(kind, description)`, surface applied / unsupported
        // evidence with a specific reason. The action arm in
        // [RobolectricHost.performSemanticsActionByContentDescription] does the actual lookup +
        // invoke. Action ids without a clean equivalent (`accessibilityFocus`, `clearFocus`,
        // `select`, granularity navigation) appear alongside the wired ones in
        // `AccessibilityRecordingScriptEvents.descriptor` with `supported = false` and are
        // rejected at MCP.
        for (action in A11Y_SEMANTIC_ACTIONS) {
          put("a11y.action.$action", a11ySemanticsActionHandler(action))
        }
        // UIAutomator-shaped dispatch — every `uia.<actionKind>` id reads the event's `selector`
        // JsonObject (multi-axis BySelector predicate), encodes it as a JSON string, and routes
        // through `interactive.dispatchUiAutomator(actionKind, selectorJson, useUnmergedTree,
        // inputText)`. The matching arm in `RobolectricHost.performUiAutomatorAction` decodes the
        // selector and dispatches the named UiObject method. See [UiAutomatorRecordingScriptEvents]
        // for the descriptor + supported-id list.
        for (id in UiAutomatorRecordingScriptEvents.WIRED_EVENTS) {
          val actionKind = id.removePrefix("uia.")
          put(id, uiAutomatorActionHandler(actionKind))
        }
        // Lifecycle dispatch — one event id per state transition (`lifecycle.pause` /
        // `lifecycle.resume` / `lifecycle.stop`). Each routes through
        // `interactive.dispatchLifecycle(...)` → `ActivityScenario.moveToState(...)`. See
        // [LifecycleRecordingScriptEvents] for the descriptor + state mapping.
        put(LifecycleRecordingScriptEvents.LIFECYCLE_PAUSE_EVENT, lifecycleEventHandler("pause"))
        put(LifecycleRecordingScriptEvents.LIFECYCLE_RESUME_EVENT, lifecycleEventHandler("resume"))
        put(LifecycleRecordingScriptEvents.LIFECYCLE_STOP_EVENT, lifecycleEventHandler("stop"))
        // Preview reload — `preview.reload` forces a fresh composition via the held rule's
        // `key(...)` reload counter. See [PreviewReloadRecordingScriptEvents].
        put(PreviewReloadRecordingScriptEvents.PREVIEW_RELOAD_EVENT, previewReloadHandler())
        // State events — `state.recreate` (single-event round-trip), `state.save`
        // (named-checkpoint capture), `state.restore` (named-checkpoint apply). All ride on the
        // host's SaveableStateRegistry bridge. See [StateRecordingScriptEvents].
        put(StateRecordingScriptEvents.STATE_RECREATE_EVENT, stateRecreateHandler())
        put(StateRecordingScriptEvents.STATE_SAVE_EVENT, stateSaveHandler())
        put(StateRecordingScriptEvents.STATE_RESTORE_EVENT, stateRestoreHandler())
      }
    )

  /**
   * Forward an input event through the held-rule loop. Mirrors the live tick path's
   * [dispatchLiveInput]; the wire-string kind translates to the typed [InteractiveInputKind]
   * once at registration time so the handler body doesn't repeat the lookup.
   */
  private fun interactiveDispatchHandler(kind: InteractiveInputKind): RecordingScriptEventHandler =
    RecordingScriptEventHandler { event, _ ->
      interactive.dispatch(
        InteractiveInputParams(
          frameStreamId = "android-recording-internal",
          kind = kind,
          pixelX = event.pixelX,
          pixelY = event.pixelY,
          scrollDeltaY = event.scrollDeltaY,
          keyCode = event.keyCode,
        )
      )
      appliedEvidence(event)
    }

  private fun androidUnsupported(label: String): RecordingScriptEventHandler =
    RecordingScriptEventHandler { event, _ ->
      unsupportedEvidence(event, "$label dispatch is not implemented for Android recording")
    }

  /**
   * Shared factory for every `a11y.action.<actionKind>` script event. Resolves a node by its
   * visible content description and forwards to
   * `interactive.dispatchSemanticsAction(actionKind, description)` — the sandbox-side `when` in
   * [RobolectricHost.performSemanticsActionByContentDescription] picks the matching
   * `SemanticsActions` constant.
   *
   * Reports `unsupported` (with a specific reason) when the agent didn't supply
   * `nodeContentDescription`, when no node matched, or when the matched node didn't expose the
   * requested semantic action. Throws (propagating into stop()) only when the action body
   * itself fails — same shape as a regular pointer dispatch under a Compose runtime error.
   */
  private fun a11ySemanticsActionHandler(actionKind: String): RecordingScriptEventHandler =
    RecordingScriptEventHandler { event, _ ->
      val description = event.nodeContentDescription
      if (description.isNullOrBlank()) {
        return@RecordingScriptEventHandler unsupportedEvidence(
          event,
          "${event.kind} requires a non-blank 'nodeContentDescription' to resolve the target node",
        )
      }
      val matched = interactive.dispatchSemanticsAction(actionKind, description)
      if (matched) {
        appliedEvidence(
          event,
          "a11y action '$actionKind' fired against contentDescription='$description'",
        )
      } else {
        unsupportedEvidence(
          event,
          "no node with contentDescription='$description' exposes the '$actionKind' semantic",
        )
      }
    }

  /**
   * Shared factory for every `uia.<actionKind>` script event. Reads the event's `selector`
   * `JsonObject` (multi-axis BySelector-style predicate; see `:data-uiautomator-core`'s
   * `SelectorJson`), serialises it to a JSON string, and forwards to
   * `interactive.dispatchUiAutomator(actionKind, selectorJson, useUnmergedTree, inputText)`. The
   * arm in [RobolectricHost.performUiAutomatorAction] decodes the JSON, walks
   * `UiAutomator.findObject(rule, selector, useUnmergedTree)`, and invokes the matching
   * `UiObject` method.
   *
   * Reports `unsupported` (with a specific reason) when the agent didn't supply `selector`, when
   * `inputText` is required (`uia.inputText`) but absent, when no node matched, or when the
   * matched node didn't expose the requested action. Throws (propagating into stop()) only when
   * the action body itself fails — same shape as the `a11y.action.*` path.
   */
  private fun uiAutomatorActionHandler(actionKind: String): RecordingScriptEventHandler =
    RecordingScriptEventHandler { event, _ ->
      val selector = event.selector
      if (selector == null) {
        return@RecordingScriptEventHandler unsupportedEvidence(
          event,
          "${event.kind} requires a non-null 'selector' object to resolve the target node",
        )
      }
      if (actionKind == "inputText" && event.inputText == null) {
        return@RecordingScriptEventHandler unsupportedEvidence(
          event,
          "${event.kind} requires a non-null 'inputText' string",
        )
      }
      val selectorJson = selector.toString()
      val useUnmergedTree = event.useUnmergedTree ?: false
      val matched =
        interactive.dispatchUiAutomator(
          actionKind = actionKind,
          selectorJson = selectorJson,
          useUnmergedTree = useUnmergedTree,
          inputText = event.inputText,
        )
      if (matched) {
        appliedEvidence(event, "uia '$actionKind' fired against selector=$selectorJson")
      } else {
        unsupportedEvidence(
          event,
          "no node matched selector=$selectorJson for uia '$actionKind' (or matched node didn't expose the action)",
        )
      }
    }

  /**
   * Handler for `preview.reload` — forces a fresh composition by routing through
   * `interactive.dispatchPreviewReload()`. The Robolectric sandbox increments a `key(...)` reload
   * counter, which Compose detects as a key change and rebuilds the slot table from scratch.
   *
   * Reports `unsupported` when the host can't dispatch reload (interactive returned `false`,
   * e.g. on a backend without a held rule). No payload validation needed — the kind alone is
   * sufficient.
   */
  private fun previewReloadHandler(): RecordingScriptEventHandler =
    RecordingScriptEventHandler { event, _ ->
      val applied = interactive.dispatchPreviewReload()
      if (applied) {
        appliedEvidence(event, "composition rebuilt from a fresh `key(...)` boundary")
      } else {
        unsupportedEvidence(
          event,
          "host did not apply preview reload; held composition may be missing a reload counter",
        )
      }
    }

  /**
   * Handler for `state.recreate` — forces a Compose-level save+restore round-trip via
   * `interactive.dispatchStateRecreate()`. The Robolectric sandbox snapshots the current
   * `SaveableStateRegistry`, increments a recreate counter, and rebuilds the slot table with the
   * snapshot restored. `rememberSaveable` survives; `remember` resets.
   *
   * Reports `unsupported` when the host can't dispatch recreate (interactive returned `false`,
   * e.g. on a backend without the SaveableStateRegistry bridge wired).
   */
  private fun stateRecreateHandler(): RecordingScriptEventHandler =
    RecordingScriptEventHandler { event, _ ->
      val applied = interactive.dispatchStateRecreate()
      if (applied) {
        appliedEvidence(
          event,
          "rememberSaveable state snapshotted and restored across a key(...) recreate",
        )
      } else {
        unsupportedEvidence(
          event,
          "host did not apply state recreate; held composition may be missing the " +
            "SaveableStateRegistry bridge",
        )
      }
    }

  /**
   * Handler for `state.save` — captures the current `SaveableStateRegistry` into a named bundle
   * keyed by `event.checkpointId`. Doesn't rebuild the composition; pair with a later
   * `state.restore` carrying the same id to apply the saved bundle.
   *
   * Reports `unsupported` when `checkpointId` is missing (the wire shape requires a non-blank
   * id) or when the host can't dispatch save (interactive returned `false`).
   */
  private fun stateSaveHandler(): RecordingScriptEventHandler =
    RecordingScriptEventHandler { event, _ ->
      val checkpointId = event.checkpointId
      if (checkpointId.isNullOrBlank()) {
        return@RecordingScriptEventHandler unsupportedEvidence(
          event,
          "${event.kind} requires a non-blank 'checkpointId' to name the saved bundle",
        )
      }
      val applied = interactive.dispatchStateSave(checkpointId)
      if (applied) {
        appliedEvidence(
          event,
          "rememberSaveable state captured under checkpointId='$checkpointId'",
        )
      } else {
        unsupportedEvidence(
          event,
          "host did not apply state save for checkpointId='$checkpointId'; held composition " +
            "may be missing the SaveableStateRegistry bridge",
        )
      }
    }

  /**
   * Handler for `state.restore` — looks up the bundle stashed by an earlier `state.save` with
   * matching `checkpointId` and rebuilds the held composition with that bundle restored.
   *
   * Reports `unsupported` when `checkpointId` is missing, when no checkpoint with that id has
   * been saved (the host returned `false` because the lookup missed), or when the host can't
   * dispatch restore at all. Specific reason in each case so the agent can tell apart "I didn't
   * provide an id" from "I provided an id no one saved" from "this host doesn't do restore".
   */
  private fun stateRestoreHandler(): RecordingScriptEventHandler =
    RecordingScriptEventHandler { event, _ ->
      val checkpointId = event.checkpointId
      if (checkpointId.isNullOrBlank()) {
        return@RecordingScriptEventHandler unsupportedEvidence(
          event,
          "${event.kind} requires a non-blank 'checkpointId' to identify the saved bundle",
        )
      }
      val applied = interactive.dispatchStateRestore(checkpointId)
      if (applied) {
        appliedEvidence(
          event,
          "rememberSaveable state restored from checkpointId='$checkpointId'",
        )
      } else {
        unsupportedEvidence(
          event,
          "no checkpoint with checkpointId='$checkpointId' has been saved (or the host's " +
            "SaveableStateRegistry bridge is unwired); pair with an earlier `state.save` " +
            "carrying the same id",
        )
      }
    }

  /**
   * Handler factory for the three `lifecycle.*` event ids — each registry entry pre-binds the
   * lifecycle target ("pause" / "resume" / "stop") at registration time so the dispatch body
   * doesn't re-parse a payload field. Forwards to `interactive.dispatchLifecycle(...)`; the
   * Robolectric sandbox maps the string to a `Lifecycle.State` and calls
   * `ActivityScenario.moveToState(...)`.
   *
   * Reports `unsupported` only when the host couldn't apply the transition (interactive
   * returned `false` — typically because it has no ActivityScenario). Validation of the
   * transition name happened up front: the registry only carries the three wired ids, so
   * unknown lifecycle.* kinds are rejected at MCP via the descriptor's closed set.
   */
  private fun lifecycleEventHandler(target: String): RecordingScriptEventHandler =
    RecordingScriptEventHandler { event, _ ->
      val applied = interactive.dispatchLifecycle(target)
      if (applied) {
        appliedEvidence(event, "lifecycle transition '$target' fired on the held activity")
      } else {
        unsupportedEvidence(
          event,
          "host did not apply lifecycle transition '$target'; held composition may be missing " +
            "an ActivityScenario",
        )
      }
    }

  private fun runLiveTickLoop() {
    val startNs = System.nanoTime()
    val frameIntervalNs = 1_000_000_000L / fps.toLong()
    var lastFrameTimeMs = 0L
    try {
      do {
        val frameIndex = liveFrameCount
        val tNanos = System.nanoTime() - startNs
        val tMs = tNanos / 1_000_000L
        // Same registry the scripted path uses — typed RecordingInputParams translates to a
        // synthetic RecordingScriptEvent keyed by `kind.wireName()`. Live mode discards the
        // per-event evidence (only the scripted [stop] result carries `scriptEvents`); the
        // dispatch's effect on the held interactive composition is what matters here.
        val ctx = SimpleRecordingDispatchContext(tNanos = tNanos, tMs = tMs)
        while (true) {
          val next = liveInputs.poll() ?: break
          scriptHandlers.dispatch(next.toScriptEvent(tMs), ctx)
        }

        val advanceTimeMs = if (frameIndex == 0) 0L else (tMs - lastFrameTimeMs).coerceAtLeast(0L)
        lastFrameTimeMs = tMs
        val rendered =
          interactive.render(
            requestId = RenderHost.nextRequestId(),
            advanceTimeMs = advanceTimeMs,
          )
        val srcPath =
          rendered.pngPath
            ?: error(
              "AndroidRecordingSession: interactive.render returned no pngPath at live frame " +
                frameIndex
            )
        val dst = File(framesDir, "frame-${"%05d".format(frameIndex)}.png")
        val dims = copyAndMaybeScale(File(srcPath), dst, scale)
        if (liveFrameWidthPx == 0) {
          liveFrameWidthPx = dims.first
          liveFrameHeightPx = dims.second
        }
        liveFrameCount = frameIndex + 1
        liveDurationMs = tMs

        val nextTickNs = startNs + liveFrameCount.toLong() * frameIntervalNs
        val sleepNs = (nextTickNs - System.nanoTime()).coerceAtLeast(0L)
        if (sleepNs > 0L) {
          try {
            Thread.sleep(sleepNs / 1_000_000L, (sleepNs % 1_000_000L).toInt())
          } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return
          }
        }
      } while (!liveStopRequested)
    } catch (t: Throwable) {
      liveFailure = t
      System.err.println(
        "compose-ai-daemon: AndroidRecordingSession.runLiveTickLoop($recordingId) failed at " +
          "frame $liveFrameCount: ${t.javaClass.simpleName}: ${t.message}"
      )
    }
  }

  /**
   * Translate a typed live-mode [RecordingInputParams] into a synthetic [RecordingScriptEvent]
   * keyed by the same wire-name string the scripted path emits — so [scriptHandlers] dispatches
   * both modes through one registry. `tMs` is the **frame's** virtual time (live mode stamps the
   * input at the current frame boundary, same convention scripted playback uses).
   *
   * `RecordingInputParams` doesn't carry `scrollDeltaY` today (the wire shape is set by
   * `JsonRpcServer.handleRecordingInput`); future rotary-aware live drivers can extend this
   * synthesisation when the new payload field is wired through.
   */
  private fun RecordingInputParams.toScriptEvent(tMs: Long): RecordingScriptEvent =
    RecordingScriptEvent(
      tMs = tMs,
      kind = kind.wireName(),
      pixelX = pixelX,
      pixelY = pixelY,
      keyCode = keyCode,
    )

  override fun encode(format: RecordingFormat): EncodedRecording {
    val r =
      result
        ?: error(
          "AndroidRecordingSession.encode($format): stop() must be called before encode() " +
            "(recordingId='$recordingId')"
        )
    encodedDir.mkdirs()
    return when (format) {
      RecordingFormat.APNG -> {
        val target = File(encodedDir, "$recordingId.apng")
        val frames =
          (0 until r.frameCount).map { i ->
            File(framesDir, "frame-${"%05d".format(i)}.png").also {
              check(it.isFile) {
                "AndroidRecordingSession.encode: missing frame PNG ${it.absolutePath}"
              }
            }
          }
        ApngEncoder.encodeFromPngFrames(
          frames = frames,
          delayNumerator = 1,
          delayDenominator = fps.toShort(),
          loopCount = 0,
          out = target,
        )
        EncodedRecording(
          videoPath = target.absolutePath,
          mimeType = "image/apng",
          sizeBytes = target.length(),
        )
      }
      RecordingFormat.MP4 ->
        encodeViaFfmpeg(FfmpegEncoder.RecordingFormatChoice.MP4, "mp4", "video/mp4")
      RecordingFormat.WEBM ->
        encodeViaFfmpeg(FfmpegEncoder.RecordingFormatChoice.WEBM, "webm", "video/webm")
    }
  }

  private fun encodeViaFfmpeg(
    choice: FfmpegEncoder.RecordingFormatChoice,
    extension: String,
    mimeType: String,
  ): EncodedRecording {
    val target = File(encodedDir, "$recordingId.$extension")
    FfmpegEncoder.encodeFromPngFrames(
      framesDir = framesDir,
      fps = fps,
      format = choice,
      out = target,
    )
    return EncodedRecording(
      videoPath = target.absolutePath,
      mimeType = mimeType,
      sizeBytes = target.length(),
    )
  }

  override fun close() {
    if (closed) return
    closed = true
    if (!stopped) {
      if (live) {
        liveStopRequested = true
        liveTickThread?.join(10_000)
      }
      // Auto-stop / daemon-shutdown path: stop() never ran, so we still own the held interactive
      // session. Release it here. A subsequent encode() will fail with "stop() must be called
      // first" — same contract as desktop.
      try {
        interactive.close()
      } catch (t: Throwable) {
        System.err.println(
          "compose-ai-daemon: AndroidRecordingSession.close($recordingId): interactive.close threw " +
            "${t.javaClass.simpleName}: ${t.message}; continuing"
        )
      }
    }
  }

  /**
   * Copy [src] PNG to [dst]; if [scaleMul] != 1.0, decode + redraw at the scaled dimensions before
   * encoding. Returns the resulting `(widthPx, heightPx)` so [stop] can record them in the
   * [RecordingResult]. Pure javax.imageio + AWT — no Skiko dependency, so this works inside the
   * Robolectric sandbox classloader without crossing back to the host.
   *
   * `BILINEAR` interpolation matches what desktop's Skiko `SamplingMode.LINEAR` does for the same
   * up/down-scale. We deliberately don't expose interpolation as a knob — agents that want
   * pixel-perfect upscale can pass `scale = 1.0` and resample client-side.
   */
  private fun copyAndMaybeScale(src: File, dst: File, scaleMul: Float): Pair<Int, Int> {
    val img =
      ImageIO.read(src)
        ?: error("AndroidRecordingSession: failed to decode PNG ${src.absolutePath}")
    val outW = (img.width * scaleMul).toInt().coerceAtLeast(1)
    val outH = (img.height * scaleMul).toInt().coerceAtLeast(1)
    val out =
      if (scaleMul == 1.0f && outW == img.width && outH == img.height) {
        img
      } else {
        BufferedImage(outW, outH, BufferedImage.TYPE_INT_ARGB).also { scaled ->
          val g = scaled.createGraphics()
          try {
            g.setRenderingHint(
              RenderingHints.KEY_INTERPOLATION,
              RenderingHints.VALUE_INTERPOLATION_BILINEAR,
            )
            g.drawImage(img, 0, 0, outW, outH, null)
          } finally {
            g.dispose()
          }
        }
      }
    ImageIO.write(out, "png", dst)
    return outW to outH
  }

  companion object {
    /**
     * `actionKind` strings advertised as `supported = true` under
     * `AccessibilityRecordingScriptEvents.descriptor` — each registers in [scriptHandlers] as
     * `a11y.action.<kind>` and fans out to a `when` arm in
     * [RobolectricHost.performSemanticsActionByContentDescription]. Adding a new entry requires
     * three coordinated edits: (a) the descriptor in [AccessibilityRecordingScriptEvents], (b)
     * this list, (c) the matching arm in `performSemanticsActionByContentDescription`. Test
     * coverage is `AndroidRecordingSessionTest.a11ySemanticsActionsRouteThroughDispatch` which
     * loops every entry here so a missing arm is caught at unit-test time.
     */
    internal val A11Y_SEMANTIC_ACTIONS: List<String> =
      listOf(
        "click",
        "longClick",
        "focus",
        "expand",
        "collapse",
        "dismiss",
        "scrollForward",
        "scrollBackward",
        "scrollUp",
        "scrollDown",
        "scrollLeft",
        "scrollRight",
      )
  }
}
