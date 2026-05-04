package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.InteractiveInputParams

/**
 * Held-scene interactive session for one `frameStreamId` — see
 * [INTERACTIVE.md § 9](../../../../../../docs/daemon/INTERACTIVE.md#9-v2--click-dispatch-into-composition).
 *
 * Backends that support interactive mode (today: `:daemon:desktop`'s `DesktopInteractiveSession`,
 * landing in PR 2) implement this interface to keep an `ImageComposeScene` (or per-host equivalent)
 * warm across `interactive/input` notifications. State derived from `remember { mutableStateOf(...)
 * }` survives between [dispatch] calls, so a click that flips a `mutableStateOf` re-paints the
 * composition the next time [render] runs.
 *
 * Lifecycle owned by [JsonRpcServer]:
 * - **Allocate** at `interactive/start` via [RenderHost.acquireInteractiveSession]. Each session
 *   owns a fresh scene; concurrent streams targeting the same preview can either share one session
 *   (host's call — matches the design doc's "one session per previewId" preference) or hold their
 *   own, transparent to this interface.
 * - **Drive** at `interactive/input` — [dispatch] feeds the pointer/key event into the held scene,
 *   then [render] encodes the next frame.
 * - **Release** at `interactive/stop` (or daemon shutdown) — [close] frees the scene and any native
 *   resources.
 *
 * **Threading.** Implementations may assume calls are serialised per-instance — `JsonRpcServer`
 * dispatches inputs from its render-watcher thread, one at a time per session. Concurrent calls to
 * different session instances are independent and may happen on different threads.
 *
 * **No-mid-render-cancellation invariant** ([DESIGN.md §
 * 9](../../../../../../docs/daemon/DESIGN.md)). [close] must drain any in-flight render before
 * tearing down the scene, the same way `RenderHost.shutdown` drains its queue. We never interrupt a
 * render mid-flight.
 */
interface InteractiveSession : AutoCloseable {

  /** The preview id this session is rendering. Frozen at allocation time. */
  val previewId: String

  /**
   * Feed one wire-level [InteractiveInputParams] (click, pointer down/up, key down/up) into the
   * held composition. Implementations translate the protocol-level kind into the host's pointer-
   * input dispatch (e.g. `ImageComposeScene.sendPointerEvent` on desktop), splitting `CLICK` into
   * Press+Release at the same position. Image-natural pixel coords on the wire are scaled by scene
   * density before dispatch.
   *
   * Does NOT render — call [render] afterwards to encode the next frame. The split lets the
   * coalescing path in [JsonRpcServer.handleInteractiveInput] queue several inputs and dispatch
   * them in a batch followed by a single [render].
   */
  fun dispatch(input: InteractiveInputParams)

  /**
   * Accessibility-driven dispatch: resolve a node by its visible content description and invoke the
   * named
   * [`SemanticsActions`](https://developer.android.com/reference/kotlin/androidx/compose/ui/semantics/SemanticsActions)
   * action against it — same path a screen reader would walk. Used by `record_preview`'s
   * `a11y.action.*` script events.
   *
   * Returns `true` when a node matched [nodeContentDescription] and the action fired; `false` when
   * no node matched or the matched node didn't expose [actionKind] (caller surfaces unsupported
   * evidence). Throws when the action ran but failed mid-flight (Compose runtime error,
   * cross-classloader marshalling failure) — same semantics as [dispatch].
   *
   * Default returns `false` so hosts without semantics-driven dispatch (DesktopHost today) cleanly
   * surface "no a11y dispatch available" via [false] without blowing up the session.
   *
   * @param actionKind short name of the semantics action — `"click"`, `"longClick"`, `"focus"`,
   *   `"scrollForward"`, etc. The implementation maps each name to the matching
   *   [`androidx.compose.ui.semantics.SemanticsActions`] constant.
   * @param nodeContentDescription content-description string the agent already saw in the latest
   *   `a11y/hierarchy` payload (or that they know from the source). Matched against the node's
   *   `SemanticsProperties.ContentDescription` — exact match, useUnmergedTree = true so merged
   *   children remain reachable.
   */
  fun dispatchSemanticsAction(actionKind: String, nodeContentDescription: String): Boolean = false

  /**
   * Lifecycle dispatch: move the held activity (or per-host equivalent) to the named lifecycle
   * state, exercising `onPause` / `onResume` / `onStop` etc. on the way. Used by `record_preview`'s
   * `lifecycle.event` script events to verify that a preview survives a pause-resume cycle or a
   * stop-restart.
   *
   * Returns `true` when the lifecycle transition fired; `false` when the host doesn't support
   * lifecycle dispatch (e.g. desktop's `ImageComposeScene` has no Android lifecycle) or the named
   * event isn't one the host recognises (caller surfaces unsupported evidence with a specific
   * reason). Throws when the transition itself failed — same propagation shape as [dispatch].
   *
   * Default returns `false` so hosts without an Android lifecycle owner cleanly surface "no
   * lifecycle dispatch available" without blowing up the session.
   *
   * @param lifecycleEvent transition name on the wire — `"pause"`, `"resume"`, `"stop"`. The
   *   implementation maps each to the matching `Lifecycle.State` and calls
   *   `ActivityScenario.moveToState(...)`. Unknown names yield `false`. `"destroy"` is
   *   intentionally not part of v1 — moving to `DESTROYED` mid-recording would tear down the
   *   scenario and break subsequent renders; document it as a follow-up if a use case lands.
   */
  fun dispatchLifecycle(lifecycleEvent: String): Boolean = false

  /**
   * Force a fresh composition: tear down the current composition slot and rebuild from scratch
   * against the same composable function. Used by `record_preview`'s `preview.reload` script event
   * to verify a screen recovers cleanly from a recompose-from-zero (`remember`, `rememberSaveable`,
   * and `LaunchedEffect`-keyed work all reset).
   *
   * Returns `true` when the composition was rebuilt; `false` when the host doesn't support forced
   * reloads (DesktopHost today). Throws when the rebuild itself failed.
   *
   * Note: this is a Compose-level reset, not an Android lifecycle round-trip. State preserved by
   * `rememberSaveable` (bundle-backed) is also lost because the `key(...)` boundary that drives the
   * rebuild invalidates the saveable-state call sites. For "state survives a config-change" audits
   * use [dispatchLifecycle] (`pause` / `resume`) instead.
   */
  fun dispatchPreviewReload(): Boolean = false

  /**
   * Force a Compose-level save+restore round-trip: snapshot `rememberSaveable` state from the
   * current composition, tear it down under a `key(...)` boundary, and rebuild with the snapshot
   * restored. Same audit signal as an Android `ActivityScenario.recreate()` — verifies state
   * survives a teardown — but lives entirely at the Compose level so it doesn't depend on the
   * activity's `onSaveInstanceState`/onCreate path.
   *
   * Returns `true` when the recreate fired; `false` when the host doesn't have the
   * `SaveableStateRegistry` bridge wired (DesktopHost today). Throws when the rebuild itself
   * failed.
   *
   * Note: `remember` state is lost across the boundary (same as a real recreate);
   * `rememberSaveable` survives via the snapshot/restore. Use [dispatchPreviewReload] when you want
   * a true cold composition (both `remember` and `rememberSaveable` reset). Use [dispatchLifecycle]
   * (`pause` / `resume`) when you want a real Android lifecycle round-trip with the activity
   * intact.
   */
  fun dispatchStateRecreate(): Boolean = false

  /**
   * Capture the current `SaveableStateRegistry` snapshot into a named bundle keyed by
   * [checkpointId]. Doesn't rebuild the composition — pair with a later [dispatchStateRestore]
   * carrying the same id to apply the saved bundle.
   *
   * Returns `true` when the snapshot was stored; `false` when the host doesn't have the bridge
   * wired (DesktopHost today). Multiple saves to the same id overwrite the previous bundle.
   */
  fun dispatchStateSave(checkpointId: String): Boolean = false

  /**
   * Look up the bundle stashed by an earlier [dispatchStateSave] with matching [checkpointId]
   * and rebuild the held composition with it restored. Returns `true` when the restore fired,
   * `false` when no checkpoint with that id has been saved (caller surfaces unsupported
   * evidence). Throws when the rebuild itself failed.
   */
  fun dispatchStateRestore(checkpointId: String): Boolean = false

  /**
   * Render the current composition to a PNG and return the result. The implementation runs the
   * scene through enough frames to settle (typically two `scene.render()` calls — same heuristic as
   * the one-shot path) and encodes to disk at a stable path the daemon can publish via
   * `renderFinished.pngPath`. The session retains the scene; this method is callable repeatedly.
   *
   * @param requestId opaque id forwarded to [RenderResult.id] so the caller's render-watcher can
   *   demux concurrent renders. Generated by `RenderHost.nextRequestId()` at the call site.
   * @param advanceTimeMs optional backend-specific virtual-clock advance before capture. Normal
   *   interactive callers leave this null so the backend uses its default settle window; recording
   *   callers can pass frame deltas to keep captured animation time paced to fps.
   */
  fun render(requestId: Long, advanceTimeMs: Long? = null): RenderResult

  /**
   * Drains any in-flight render, frees the held scene + its native resources, and removes any
   * filesystem state owned by the session (e.g. the per-stream PNG output file). Idempotent —
   * subsequent calls are no-ops. Safe to call from the daemon's shutdown drain even when an input
   * is queued.
   */
  override fun close()
}
