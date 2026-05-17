package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.bridge.InteractiveCommand
import ee.schimke.composeai.daemon.bridge.SandboxRecompositionBridge
import ee.schimke.composeai.daemon.bridge.SandboxSlot
import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.daemon.protocol.DataProductAttachment
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.data.recomposition.RecompositionNode
import ee.schimke.composeai.data.recomposition.RecompositionPayload
import ee.schimke.composeai.data.recomposition.RecompositionProduct
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Android producer for `compose/recomposition` (issue #1204). Mirrors
 * [ee.schimke.composeai.daemon.RecompositionDataProductRegistry]'s wire contract, but routes the
 * observer install through the Robolectric sandbox via
 * [InteractiveCommand.StartObserveRecomposition] / [InteractiveCommand.StopObserveRecomposition]
 * instead of installing reflectively in-process. Counters travel back through
 * [SandboxRecompositionBridge.drainCounters].
 *
 * **Lifecycle.** The producer plugs into [RobolectricHost.InteractiveSessionListener] to learn
 * which (previewId, streamId, slot) tuple is currently held. The dispatcher's
 * `data/subscribe(mode=delta)` lands in [onSubscribe]; if the matching session is already live,
 * we enqueue StartObserve right away, otherwise we defer until the listener fires
 * [RobolectricHost.InteractiveSessionLifecycle.Acquired]. On `data/unsubscribe` / session release
 * we enqueue StopObserve (or — if the session is already gone — just drop the bridge counters).
 *
 * **Safety net.** Same shape as the desktop producer: a `LinkageError` / `NoSuchMethodError` on
 * the in-sandbox observer install path latches a per-JVM [globallyUnavailable] flag and
 * subsequent subscribes skip the install entirely. The kind stays advertised in
 * `initialize.capabilities` (we can't unsay that), but `attachmentsFor` returns `emptyList()` so
 * the panel sees no compose/recomposition entries on `renderFinished` instead of misleading
 * empty payloads.
 *
 * **`fetch(mode=snapshot)`.** Snapshot mode requires a re-render; for v1 the renderer-side
 * sidecar isn't wired (same gap the desktop producer documents), so a snapshot fetch with no
 * cached payload returns [DataProductRegistry.Outcome.RequiresRerender] and the dispatcher
 * issues a recomposition-mode render. The held interactive path is the supported route for now.
 */
open class AndroidRecompositionDataProductRegistry : DataProductRegistry {

  /** Latched true after the first observer-install failure. Same shape as the desktop producer. */
  @Volatile private var globallyUnavailable: Boolean = false

  internal fun markGloballyUnavailableForTesting() {
    globallyUnavailable = true
  }

  /** Per-previewId subscription state. One subscription per (previewId, compose/recomposition). */
  private class SubscriptionState(
    @Volatile var frameStreamId: String,
    @Volatile var mode: String,
    @Volatile var inputSeq: Long = 0L,
    /** Sandbox stream id of the currently-held session for [previewId], or `null` when no session. */
    @Volatile var sandboxStreamId: String? = null,
    @Volatile var slot: SandboxSlot? = null,
    @Volatile var observerInstalled: Boolean = false,
    @Volatile var instrumentationUnavailable: Boolean = false,
  )

  private val subscriptions = ConcurrentHashMap<String, SubscriptionState>()

  /** Live sessions keyed by previewId — populated by [onSessionLifecycle]. */
  private val liveSessions: ConcurrentHashMap<String, LiveSession> = ConcurrentHashMap()

  private data class LiveSession(val streamId: String, val slot: SandboxSlot)

  override val capabilities: List<DataProductCapability> =
    listOf(
      DataProductCapability(
        kind = KIND,
        schemaVersion = SCHEMA_VERSION,
        transport = DataProductTransport.INLINE,
        attachable = true,
        fetchable = true,
        requiresRerender = true,
      )
    )

  override fun fetch(
    previewId: String,
    kind: String,
    params: JsonElement?,
    inline: Boolean,
  ): DataProductRegistry.Outcome {
    if (kind != KIND) return DataProductRegistry.Outcome.Unknown
    val parsed = parseParams(params)
    val mode = parsed?.mode ?: MODE_SNAPSHOT
    return when (mode) {
      MODE_DELTA -> {
        // Delta mode only meaningful against a live held session — mirrors desktop.
        if (liveSessions[previewId] == null) return DataProductRegistry.Outcome.NotAvailable
        val state = subscriptions[previewId]
        if (globallyUnavailable || state?.instrumentationUnavailable == true) {
          return DataProductRegistry.Outcome.FetchFailed(
            message = "compose/recomposition: instrumentation unavailable for this Compose runtime"
          )
        }
        val nodes =
          state?.let { snapshotCounters(previewId, it.sandboxStreamId ?: return@let null) }
            ?: emptyList()
        val payload =
          RecompositionPayload(
            mode = MODE_DELTA,
            sinceFrameStreamId = state?.frameStreamId,
            inputSeq = state?.inputSeq,
            nodes = nodes,
          )
        DataProductRegistry.Outcome.Ok(
          DataFetchResult(
            kind = KIND,
            schemaVersion = SCHEMA_VERSION,
            payload = json.encodeToJsonElement(RecompositionPayload.serializer(), payload),
          )
        )
      }
      MODE_SNAPSHOT -> DataProductRegistry.Outcome.RequiresRerender(MODE_RECOMPOSITION_RENDER)
      else ->
        DataProductRegistry.Outcome.FetchFailed(
          message = "compose/recomposition: unknown mode '$mode' (expected 'delta' or 'snapshot')"
        )
    }
  }

  override fun attachmentsFor(previewId: String, kinds: Set<String>): List<DataProductAttachment> {
    if (KIND !in kinds) return emptyList()
    val state = subscriptions[previewId] ?: return emptyList()
    if (globallyUnavailable || state.instrumentationUnavailable) return emptyList()
    val sandboxStreamId = state.sandboxStreamId ?: return emptyList()
    val nodes = snapshotCounters(previewId, sandboxStreamId)
    state.inputSeq += 1L
    val payload =
      RecompositionPayload(
        mode = state.mode,
        sinceFrameStreamId = state.frameStreamId,
        inputSeq = state.inputSeq,
        nodes = nodes,
      )
    return listOf(
      DataProductAttachment(
        kind = KIND,
        schemaVersion = SCHEMA_VERSION,
        payload = json.encodeToJsonElement(RecompositionPayload.serializer(), payload),
      )
    )
  }

  override fun onSubscribe(previewId: String, kind: String, params: JsonElement?) {
    if (kind != KIND) return
    val parsed = parseParams(params)
    val frameStreamId = parsed?.frameStreamId ?: ""
    val rawMode = parsed?.mode ?: MODE_SNAPSHOT
    // Tear down any prior subscription's observer — re-subscribe semantics per
    // DataProductRegistry.onSubscribe.
    subscriptions.remove(previewId)?.let { prior -> tearDownObserver(previewId, prior) }
    val live = liveSessions[previewId]
    val effectiveMode =
      if (rawMode == MODE_DELTA && live == null) {
        System.err.println(
          "compose-ai-daemon: AndroidRecompositionDataProductRegistry: subscribe mode='delta' for " +
            "previewId='$previewId' but no live interactive session; treating as 'snapshot'"
        )
        MODE_SNAPSHOT
      } else rawMode
    val state =
      SubscriptionState(
        frameStreamId = frameStreamId,
        mode = effectiveMode,
        sandboxStreamId = live?.streamId,
        slot = live?.slot,
      )
    subscriptions[previewId] = state
    if (effectiveMode == MODE_DELTA && live != null && !globallyUnavailable) {
      installObserver(previewId, state, live)
    } else if (globallyUnavailable) {
      state.instrumentationUnavailable = true
    }
  }

  override fun onUnsubscribe(previewId: String, kind: String) {
    if (kind != KIND) return
    subscriptions.remove(previewId)?.let { prior -> tearDownObserver(previewId, prior) }
  }

  /**
   * Wired from [RobolectricHost.InteractiveSessionListener]. Two arms:
   *
   * - **Acquired** — record the live (streamId, slot). If a snapshot-mode subscription exists for
   *   [previewId], promote it to delta and install the observer (mirrors desktop's
   *   `onSessionLifecycle` shape). Idempotent on re-acquire (e.g. after a slot recycle).
   * - **Released** — drop the live tuple. The subscription itself stays so a later acquire can
   *   re-install, but [attachmentsFor] returns `emptyList()` until then.
   */
  fun onSessionLifecycle(event: RobolectricHost.InteractiveSessionLifecycle) {
    when (event) {
      is RobolectricHost.InteractiveSessionLifecycle.Acquired -> {
        liveSessions[event.previewId] = LiveSession(streamId = event.streamId, slot = event.slot)
        val state = subscriptions[event.previewId] ?: return
        if (state.observerInstalled || state.instrumentationUnavailable) return
        if (globallyUnavailable) {
          state.instrumentationUnavailable = true
          return
        }
        if (state.mode != MODE_DELTA) {
          state.mode = MODE_DELTA
          state.inputSeq = 0L
        }
        state.sandboxStreamId = event.streamId
        state.slot = event.slot
        installObserver(
          event.previewId,
          state,
          LiveSession(streamId = event.streamId, slot = event.slot),
        )
      }
      is RobolectricHost.InteractiveSessionLifecycle.Released -> {
        // Scope cleanup to the released stream — a watchdog-driven close for an old session can
        // race with a new acquire for the same previewId, so we must verify the streamId
        // before nulling out subscription state. `AndroidInteractiveSession.close()` clears the
        // active-stream ref before firing the close hook, which is what makes the race
        // observable: by the time we see Released(A) the host may have already entered
        // Acquired(B) for the same preview. Without this guard we'd wipe out B's
        // sandboxStreamId and silently drop subsequent attachments.
        liveSessions.compute(event.previewId) { _, current ->
          if (current?.streamId == event.streamId) null else current
        }
        val state = subscriptions[event.previewId] ?: return
        if (state.sandboxStreamId == event.streamId) {
          // The held loop has already disposed every recomposition observer on Close — we just
          // forget the sandbox stream id so attachmentsFor stops shipping payloads.
          state.observerInstalled = false
          state.sandboxStreamId = null
          state.slot = null
        }
      }
    }
  }

  private fun installObserver(previewId: String, state: SubscriptionState, live: LiveSession) {
    val replyLatch = CountDownLatch(1)
    val replyError = AtomicReference<Throwable?>(null)
    val replyUnavailable = AtomicBoolean(false)
    live.slot.interactiveCommands.put(
      InteractiveCommand.StartObserveRecomposition(
        streamId = live.streamId,
        previewId = previewId,
        replyLatch = replyLatch,
        replyError = replyError,
        replyUnavailable = replyUnavailable,
      )
    )
    val arrived = replyLatch.await(OBSERVE_INSTALL_TIMEOUT_SEC, TimeUnit.SECONDS)
    if (!arrived) {
      System.err.println(
        "compose-ai-daemon: AndroidRecompositionDataProductRegistry: StartObserve timed out for " +
          "previewId='$previewId' streamId='${live.streamId}'; held loop may be stuck. " +
          "Marking instrumentation unavailable for this subscription."
      )
      state.instrumentationUnavailable = true
      return
    }
    val err = replyError.get()
    if (err != null) {
      System.err.println(
        "compose-ai-daemon: AndroidRecompositionDataProductRegistry: StartObserve threw on " +
          "previewId='$previewId' streamId='${live.streamId}': " +
          "${err.javaClass.simpleName}: ${err.message}"
      )
      state.instrumentationUnavailable = true
      return
    }
    if (replyUnavailable.get()) {
      // The sandbox-side install hit a Compose-runtime API failure — latch globally so the next
      // subscribe doesn't pay the round-trip again.
      state.instrumentationUnavailable = true
      globallyUnavailable = true
      return
    }
    state.observerInstalled = true
  }

  private fun tearDownObserver(previewId: String, state: SubscriptionState) {
    val slot = state.slot
    val streamId = state.sandboxStreamId
    state.observerInstalled = false
    if (slot != null && streamId != null) {
      val replyLatch = CountDownLatch(1)
      val replyError = AtomicReference<Throwable?>(null)
      slot.interactiveCommands.put(
        InteractiveCommand.StopObserveRecomposition(
          streamId = streamId,
          previewId = previewId,
          replyLatch = replyLatch,
          replyError = replyError,
        )
      )
      if (!replyLatch.await(OBSERVE_INSTALL_TIMEOUT_SEC, TimeUnit.SECONDS)) {
        System.err.println(
          "compose-ai-daemon: AndroidRecompositionDataProductRegistry: StopObserve timed out for " +
            "previewId='$previewId' streamId='$streamId'; dropping bridge counters anyway"
        )
      }
      val err = replyError.get()
      if (err != null) {
        System.err.println(
          "compose-ai-daemon: AndroidRecompositionDataProductRegistry: StopObserve threw " +
            "(${err.javaClass.simpleName}: ${err.message}); continuing"
        )
      }
    }
    // Defensive: even if the sandbox-side close ran (or the session is already gone), drop the
    // bridge entry so the next subscribe starts on a clean slot.
    if (streamId != null) SandboxRecompositionBridge.close(previewId, streamId)
  }

  private fun snapshotCounters(previewId: String, streamId: String): List<RecompositionNode> {
    val drained = SandboxRecompositionBridge.drainCounters(previewId, streamId)
    @Suppress("UNCHECKED_CAST")
    val ids = drained[0] as Array<String>
    val counts = drained[1] as LongArray
    if (ids.isEmpty()) return emptyList()
    return List(ids.size) { i -> RecompositionNode(nodeId = ids[i], count = counts[i].toInt()) }
  }

  private fun parseParams(params: JsonElement?): SubscribeParamsView? {
    if (params == null) return null
    val obj = params as? JsonObject ?: return null
    val frameStreamId = (obj["frameStreamId"] as? JsonPrimitive)?.contentOrNull
    val mode = (obj["mode"] as? JsonPrimitive)?.contentOrNull
    return SubscribeParamsView(frameStreamId = frameStreamId, mode = mode)
  }

  private data class SubscribeParamsView(val frameStreamId: String?, val mode: String?)

  companion object {
    const val KIND: String = RecompositionProduct.KIND
    const val SCHEMA_VERSION: Int = RecompositionProduct.SCHEMA_VERSION
    const val MODE_DELTA: String = RecompositionProduct.MODE_DELTA
    const val MODE_SNAPSHOT: String = RecompositionProduct.MODE_SNAPSHOT
    const val MODE_RECOMPOSITION_RENDER: String = "recomposition"

    /**
     * Bound on the bridge round-trip for Start/StopObserve. The sandbox-side handler does one
     * `findViewTreeCompositionContext` + a few CompositionObserver allocations — milliseconds
     * post-warmup. 10s is conservative slack for a cold first-call.
     */
    private const val OBSERVE_INSTALL_TIMEOUT_SEC: Long = 10L

    private val json = Json {
      encodeDefaults = true
      prettyPrint = false
    }
  }
}
