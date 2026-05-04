@file:OptIn(androidx.compose.runtime.ExperimentalComposeRuntimeApi::class)

package ee.schimke.composeai.daemon

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.tooling.CompositionObserver
import androidx.compose.runtime.tooling.CompositionObserverHandle
import androidx.compose.runtime.tooling.CompositionRegistrationObserver
import androidx.compose.runtime.tooling.ObservableComposition
import androidx.compose.runtime.tooling.observe
import androidx.compose.ui.ImageComposeScene
import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.daemon.protocol.DataProductAttachment
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.data.render.extensions.DataExtensionCapability
import ee.schimke.composeai.data.render.extensions.DataExtensionConstraints
import ee.schimke.composeai.data.render.extensions.DataExtensionHookKind
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionLifecycle
import ee.schimke.composeai.data.render.extensions.DataExtensionPhase
import ee.schimke.composeai.data.render.extensions.compose.CompositionObserverHook
import ee.schimke.composeai.data.render.extensions.compose.ExtensionComposeContext
import ee.schimke.composeai.data.render.extensions.compose.ExtensionCompositionSink
import ee.schimke.composeai.data.render.extensions.compose.ExtensionContextKey
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * D5 — `compose/recomposition` producer for the desktop interactive surface. See
 * [docs/daemon/DATA-PRODUCTS.md](../../../../../../../docs/daemon/DATA-PRODUCTS.md) §
 * "Recomposition + interactive mode" for the wire contract this implements.
 *
 * **What it does.**
 * - Advertises a single kind, `compose/recomposition`, schemaVersion=1, transport=INLINE,
 *   attachable=true, fetchable=true, requiresRerender=true.
 * - On `data/subscribe` with `params: { frameStreamId, mode: "delta" }` against a live
 *   [DesktopInteractiveSession]: looks up the live scene via [liveScenes], reaches the held scene's
 *   [androidx.compose.runtime.Recomposer] reflectively, and installs a [CompositionObserver] that
 *   increments per-[RecomposeScope] counters on each `onScopeExit`.
 * - On each `interactive/input` cycle the daemon's render watcher emits `renderFinished` and calls
 *   [attachmentsFor]; we snapshot the current counter map, attach it as a [RecompositionPayload],
 *   then reset counters to zero so the next input's payload is the delta *since the previous
 *   input*.
 * - On `data/unsubscribe` (or `setVisible` dropping the preview): tear down the observer and drop
 *   counter state.
 *
 * **Producer-side observation, not a registry-level flush hook.** Per the D5 brief: the dispatcher
 * (`JsonRpcServer`) stays unchanged. The producer collects counts as Compose runs its own
 * recomposition cycles, and `attachmentsFor` is the seam the dispatcher already calls after every
 * render — so we get an automatic flush-on-input from the existing wire path.
 *
 * **Stable id caveat (v2 problem).** [RecompositionNode.nodeId] is
 * `System.identityHashCode(scope).toString(16)`, scoped within the per-(previewId, frameStreamId)
 * subscription. That's stable for the *duration of the session* — enough for the heat-map UI's
 * "what recomposed when I clicked here" question — but NOT stable across sessions. Stable ids
 * require slot-table line:column extraction; deferred to v2.
 *
 * FOLLOWUP (D5 v2): swap the identityHashCode-based id for a slot-table-derived
 * `(file:line:column)` key so heat-map state survives daemon restarts and sandbox recycles.
 *
 * **Safety net.** [androidx.compose.runtime.tooling.CompositionObserver] is
 * `@ExperimentalComposeRuntimeApi`. Compose may rename or restructure these surfaces between
 * runtime releases. Every observer-install path is wrapped in `try/catch (LinkageError |
 * NoSuchMethodError)` so a future API rename degrades the producer to "advertise but useless"
 * (subscriptions succeed, `nodes: []` ships) rather than crashing the daemon JVM.
 */
open class RecompositionDataProductRegistry : DataProductRegistry {

  /**
   * Per-previewId tracking of the currently-held [ImageComposeScene], populated by [DesktopHost]'s
   * interactive-session lifecycle hook (see [DesktopHost.InteractiveSessionListener] — this class
   * implements that interface via [onSessionLifecycle]). The producer uses this map both as its "is
   * there a live session?" lookup at subscribe time and as the source of the scene whose recomposer
   * it instruments.
   */
  private val liveScenes: ConcurrentHashMap<String, ImageComposeScene> = ConcurrentHashMap()

  /** Per-(previewId, frameStreamId) state for one live `data/subscribe`. */
  private class SubscriptionState(
    val frameStreamId: String,
    val mode: String,
    /** Monotonic counter incremented per `interactive/input` flushed snapshot. */
    @Volatile var inputSeq: Long = 0L,
    @Volatile var observerHandle: CompositionObserverHandle? = null,
    /**
     * `true` when the observer install path threw a `LinkageError` / `NoSuchMethodError`. The
     * subscription stays in [subscriptions] so `attachmentsFor` keeps shipping payloads — they just
     * carry `nodes: []` until the panel re-subscribes against a working build.
     */
    @Volatile var instrumentationUnavailable: Boolean = false,
    /**
     * Map of identity-hashcode-of-RecomposeScope → its current delta-window count. Cleared on each
     * [snapshotAndReset]. Concurrent because the observer fires from Compose's recomposer thread
     * while [attachmentsFor] runs from JsonRpcServer's render watcher.
     */
    val counters: ConcurrentHashMap<Int, Int> = ConcurrentHashMap(),
  )

  /** Keyed by previewId. One subscription per (previewId, kind=compose/recomposition). */
  private val subscriptions = ConcurrentHashMap<String, SubscriptionState>()

  override val capabilities: List<DataProductCapability> =
    listOf(
      DataProductCapability(
        kind = KIND,
        schemaVersion = SCHEMA_VERSION,
        transport = DataProductTransport.INLINE,
        attachable = true,
        fetchable = true,
        // Snapshot mode triggers a re-render-on-fetch (mode=recomposition); see DATA-PRODUCTS.md
        // § "Recomposition + interactive mode" and the Outcome.RequiresRerender path on fetch.
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
        // Delta mode is only meaningful when there's a live session backing the previewId. The
        // brief says: "in mode = delta against a non-live session, return Outcome.NotAvailable."
        if (liveScenes[previewId] == null) return DataProductRegistry.Outcome.NotAvailable
        // For a live session, the canonical delta lives on the renderFinished attachment path —
        // the panel doesn't normally `data/fetch` deltas (they ride attached). When it does, we
        // surface the current counters without resetting; the attachment path is what mutates.
        val state = subscriptions[previewId]
        val payload =
          if (state == null) {
            RecompositionPayload(
              mode = MODE_DELTA,
              sinceFrameStreamId = parsed?.frameStreamId,
              inputSeq = null,
              nodes = emptyList(),
            )
          } else {
            RecompositionPayload(
              mode = MODE_DELTA,
              sinceFrameStreamId = state.frameStreamId,
              inputSeq = state.inputSeq,
              nodes = state.counters.snapshot(),
            )
          }
        DataProductRegistry.Outcome.Ok(
          DataFetchResult(
            kind = KIND,
            schemaVersion = SCHEMA_VERSION,
            payload = json.encodeToJsonElement(RecompositionPayload.serializer(), payload),
          )
        )
      }
      MODE_SNAPSHOT -> {
        // Snapshot mode answers "what recomposed during initial composition" — it's a one-shot
        // measure, so we ask the dispatcher to drive a fresh render in the recomposition mode
        // and return Ok on the next pass. v1 of the producer doesn't keep a renderer-side cache
        // of "the last snapshot for previewId X" — the renderer doesn't yet write a recomp
        // sidecar, and writing one is a follow-up to this PR. Until then, snapshot fetches
        // always require a re-render.
        DataProductRegistry.Outcome.RequiresRerender(MODE_RECOMPOSITION_RENDER)
      }
      else ->
        DataProductRegistry.Outcome.FetchFailed(
          message = "compose/recomposition: unknown mode '$mode' (expected 'delta' or 'snapshot')"
        )
    }
  }

  override fun attachmentsFor(previewId: String, kinds: Set<String>): List<DataProductAttachment> {
    if (KIND !in kinds) return emptyList()
    val state = subscriptions[previewId] ?: return emptyList()
    // Bump the input-seq counter once per attachment build — that's exactly one per
    // post-input renderFinished in the interactive path (the dispatcher calls attachmentsFor
    // once per render). Snapshot the counter map *before* incrementing so the payload reads
    // monotonically from the client's perspective: the n-th attachment carries inputSeq=n.
    val nodes = state.counters.snapshotAndReset()
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
    val effectiveMode =
      if (rawMode == MODE_DELTA && liveScenes[previewId] == null) {
        // No live session — the panel asked for delta but the desktop daemon hasn't been
        // wired with an interactive session for this preview. Per the brief: "log a warning
        // and treat as snapshot (deferring to the existing fetch-driven re-render path)."
        // The subscription still records — that way a subsequent `interactive/start` followed
        // by another subscribe attaches an observer.
        System.err.println(
          "compose-ai-daemon: RecompositionDataProductRegistry: subscribe mode='delta' for " +
            "previewId='$previewId' but no live interactive session; treating as 'snapshot'"
        )
        MODE_SNAPSHOT
      } else rawMode
    // Tear down any prior subscription state for this previewId — re-subscribe semantics per
    // DataProductRegistry.onSubscribe contract: "Producers that need 'reset on re-subscribe'
    // semantics use that as the signal."
    subscriptions.remove(previewId)?.disposeObserver()
    val state = SubscriptionState(frameStreamId = frameStreamId, mode = effectiveMode)
    subscriptions[previewId] = state
    if (effectiveMode == MODE_DELTA) {
      val scene = liveScenes[previewId]
      if (scene != null) {
        installObserverSafely(state, scene)
      }
    }
  }

  override fun onUnsubscribe(previewId: String, kind: String) {
    if (kind != KIND) return
    subscriptions.remove(previewId)?.disposeObserver()
  }

  /**
   * [DesktopHost.InteractiveSessionListener] entry point — wires this producer into the held- scene
   * lifecycle so the observer install path can fire on session-up *or* on a
   * subscribe-while-snapshot promotion. Idempotent.
   *
   * - `scene != null` → session acquired. Track in [liveScenes]. If a delta-mode subscription
   *   already exists for [previewId], install the observer now. If a snapshot-mode subscription
   *   exists, replace it with a fresh delta entry and install — the panel asked for delta but the
   *   daemon couldn't honour it at subscribe time; promote on session-up.
   * - `scene == null` → session released. Drop from [liveScenes] and dispose the observer handle
   *   (subscription state itself stays so `attachmentsFor` keeps shipping empty payloads until the
   *   client unsubscribes).
   */
  fun onSessionLifecycle(previewId: String, scene: ImageComposeScene?) {
    if (scene == null) {
      liveScenes.remove(previewId)
      subscriptions[previewId]?.disposeObserver()
      return
    }
    liveScenes[previewId] = scene
    val state = subscriptions[previewId] ?: return
    if (state.observerHandle != null || state.instrumentationUnavailable) return
    if (state.mode != MODE_DELTA) {
      // Promote a snapshot subscription to delta now that we have a live scene. Replace the
      // state with a fresh delta-mode entry so the existing inputSeq doesn't carry forward
      // from snapshot territory (where inputSeq is meaningless).
      val replaced = SubscriptionState(frameStreamId = state.frameStreamId, mode = MODE_DELTA)
      subscriptions[previewId] = replaced
      installObserverSafely(replaced, scene)
    } else {
      installObserverSafely(state, scene)
    }
  }

  private fun installObserverSafely(state: SubscriptionState, scene: ImageComposeScene) {
    val handle =
      try {
        installObserver(
          scene = scene,
          onScopeRecomposed = { scope ->
            val key = System.identityHashCode(scope)
            state.counters.merge(key, 1, Int::plus)
          },
          onScopeDisposed = { scope -> state.counters.remove(System.identityHashCode(scope)) },
        )
      } catch (t: Throwable) {
        // The brief mandates LinkageError / NoSuchMethodError specifically; we widen to any
        // Throwable here because the reflection path has more failure modes than just the
        // experimental-API-rename one (private-field access denied under a future SecurityManager,
        // null intermediate values, etc.). All of them should degrade to "advertise but useless"
        // rather than killing the daemon.
        System.err.println(
          "compose-ai-daemon: RecompositionDataProductRegistry: observer install failed for " +
            "previewId='?' frameStreamId='${state.frameStreamId}' " +
            "(${t.javaClass.simpleName}: ${t.message}); marking instrumentation unavailable"
        )
        state.instrumentationUnavailable = true
        null
      }
    state.observerHandle = handle
  }

  /**
   * The actual observer install. Walks `ImageComposeScene.scene` (BaseComposeScene) → `recomposer`
   * (ComposeSceneRecomposer) → `recomposer` (androidx.compose.runtime.Recomposer) via reflection on
   * the private fields, then registers a [CompositionRegistrationObserver] which calls
   * [ObservableComposition.setObserver] for each existing + future composition.
   *
   * The walk is reflective because Compose UI doesn't expose the held [Recomposer] publicly — see
   * the discussion in [RecompositionDataProductRegistry] KDoc. The
   * `addCompositionRegistrationObserver$runtime` path the public `Recomposer.observe` extension
   * delegates to also reports every *currently registered* composition synchronously, so even a
   * subscribe-after-render install picks up the live composition tree on the same call.
   */
  @OptIn(ExperimentalComposeRuntimeApi::class)
  protected open fun installObserver(
    scene: ImageComposeScene,
    onScopeRecomposed: (RecomposeScope) -> Unit,
    onScopeDisposed: (RecomposeScope) -> Unit,
  ): CompositionObserverHandle {
    val recomposer = reflectRecomposer(scene)
    val perCompositionHandles = mutableListOf<CompositionObserverHandle>()
    val perCompositionObserver =
      object : CompositionObserver {
        override fun onBeginComposition(composition: ObservableComposition) {
          // No-op — we count post-recomposition via onScopeExit. onBeginComposition fires once
          // per composition cycle (not per scope) so it doesn't carry the per-scope info.
        }

        override fun onScopeEnter(scope: RecomposeScope) {
          // No-op — we attribute counts at exit so the count reflects "this scope's body
          // actually ran to completion" rather than "we entered it." Matters for nested
          // scopes that throw mid-body; entry would over-count, exit only fires on success.
        }

        override fun onReadInScope(scope: RecomposeScope, value: Any) {
          // Snapshot-state read tracker — useful for "why did this recompose" tooling but not
          // needed for the count. Skipping keeps observer overhead minimal.
        }

        override fun onScopeExit(scope: RecomposeScope) {
          onScopeRecomposed(scope)
        }

        override fun onEndComposition(composition: ObservableComposition) {
          // No-op for v1. A future "settle frame" hook could batch counts here, but the
          // attachmentsFor seam already runs once per renderFinished so there's nothing to add.
        }

        override fun onScopeInvalidated(scope: RecomposeScope, value: Any?) {
          // Compose calls this when a Snapshot write invalidates [scope]. We could surface "n
          // invalidations since last flush" alongside counts, but the brief asks only for the
          // recomposition count; defer.
        }

        override fun onScopeDisposed(scope: RecomposeScope) {
          // The scope is going away — drop its counter so it doesn't leak across long-running
          // sessions. The next id allocation for a scope at the same address will land in a
          // fresh slot.
          onScopeDisposed(scope)
        }
      }
    val registrationObserver =
      object : CompositionRegistrationObserver {
        override fun onCompositionRegistered(composition: ObservableComposition) {
          val handle = composition.setObserver(perCompositionObserver)
          synchronized(perCompositionHandles) { perCompositionHandles.add(handle) }
        }

        override fun onCompositionUnregistered(composition: ObservableComposition) {
          // The composition's per-comp observer handle is still in [perCompositionHandles];
          // disposing it now is a no-op because the composition is already gone, and we'll
          // dispose the whole list at unsubscribe time. Skipping the bookkeeping cleanup here
          // keeps the locking simple.
        }
      }
    val recomposerHandle = recomposer.observe(registrationObserver)
    return object : CompositionObserverHandle {
      override fun dispose() {
        // Compound dispose — first unregister the per-recomposer registration observer (so no
        // new compositions get hooked), then dispose every per-composition handle. Both calls
        // are best-effort; the daemon is going down anyway when this fires from a teardown path.
        try {
          recomposerHandle.dispose()
        } catch (_: Throwable) {}
        val handlesCopy = synchronized(perCompositionHandles) { perCompositionHandles.toList() }
        for (h in handlesCopy) {
          try {
            h.dispose()
          } catch (_: Throwable) {}
        }
      }
    }
  }

  private fun reflectRecomposer(scene: ImageComposeScene): androidx.compose.runtime.Recomposer {
    return DesktopSceneRecomposer.current(scene)
  }

  private fun parseParams(params: JsonElement?): SubscribeParamsView? {
    if (params == null) return null
    val obj = params as? JsonObject ?: return null
    val frameStreamId = (obj["frameStreamId"] as? JsonPrimitive)?.contentOrNull
    val mode = (obj["mode"] as? JsonPrimitive)?.contentOrNull
    return SubscribeParamsView(frameStreamId = frameStreamId, mode = mode)
  }

  private fun ConcurrentHashMap<Int, Int>.snapshot(): List<RecompositionNode> =
    map { (k, v) -> RecompositionNode(nodeId = Integer.toHexString(k), count = v) }
      .sortedBy { it.nodeId }

  private fun ConcurrentHashMap<Int, Int>.snapshotAndReset(): List<RecompositionNode> {
    val out = mutableListOf<RecompositionNode>()
    val keys = keys.toList()
    for (k in keys) {
      // Atomic per-key read+remove; Compose's observer thread may be incrementing concurrently,
      // so a `get()`-then-`remove()` sequence could lose a count between the two. `remove()`
      // returns the prior value atomically.
      val v = remove(k) ?: continue
      out.add(RecompositionNode(nodeId = Integer.toHexString(k), count = v))
    }
    return out.sortedBy { it.nodeId }
  }

  private fun SubscriptionState.disposeObserver() {
    val handle = observerHandle ?: return
    observerHandle = null
    try {
      handle.dispose()
    } catch (t: Throwable) {
      System.err.println(
        "compose-ai-daemon: RecompositionDataProductRegistry: observer dispose failed " +
          "(${t.javaClass.simpleName}: ${t.message}); ignoring"
      )
    }
  }

  /** Internal view of decoded [DataSubscribeParams.params]. */
  private data class SubscribeParamsView(val frameStreamId: String?, val mode: String?)

  companion object {
    /** The single kind this producer surfaces. */
    const val KIND: String = "compose/recomposition"

    /** Wire-format schema version pinned alongside [RecompositionPayload]. */
    const val SCHEMA_VERSION: Int = 1

    /** Subscribe-time mode value: per-input deltas, requires a live interactive session. */
    const val MODE_DELTA: String = "delta"

    /** Subscribe-time mode value: one-shot snapshot of initial-composition counts. */
    const val MODE_SNAPSHOT: String = "snapshot"

    /**
     * Render mode tag the dispatcher forwards to the renderer-agnostic seam when this producer
     * returns [DataProductRegistry.Outcome.RequiresRerender]. Renderer-side wiring of this tag is a
     * follow-up — the desktop renderer doesn't yet write a recomposition sidecar — so snapshot
     * fetches today complete the budget timer without producing a payload.
     */
    const val MODE_RECOMPOSITION_RENDER: String = "recomposition"

    /**
     * `encodeDefaults = true` so empty `nodes: []` lists ride the wire instead of being dropped —
     * clients that read `nodes.length` on every payload can rely on the field's presence regardless
     * of whether instrumentation is unavailable or just no-op-this-frame.
     */
    private val json = Json {
      encodeDefaults = true
      prettyPrint = false
    }
  }
}

fun interface RecompositionObservationHandle {
  fun dispose()
}

fun interface RecompositionObservationSession {
  fun observe(onPayload: (RecompositionPayload) -> Unit): RecompositionObservationHandle
}

object RecompositionExtensionContextKeys {
  val ObservationSession: ExtensionContextKey<RecompositionObservationSession> =
    ExtensionContextKey(
      "compose.recomposition.observationSession",
      RecompositionObservationSession::class.java,
    )
}

/**
 * Clean Compose-facing connector for recomposition observation.
 *
 * Hosts provide a typed [RecompositionObservationSession] through [ExtensionComposeContext]. This
 * keeps scene/recomposer access, including any reflection needed by a specific host, behind a
 * product-owned facade while the extension itself stays shaped like a normal Compose lifecycle
 * hook.
 */
class RecompositionObserverExtension : CompositionObserverHook {
  override val id: DataExtensionId = DataExtensionId(RecompositionDataProductRegistry.KIND)
  override val hooks: Set<DataExtensionHookKind> = setOf(DataExtensionHookKind.CompositionObserver)
  override val constraints: DataExtensionConstraints =
    DataExtensionConstraints(
      phase = DataExtensionPhase.Instrumentation,
      provides = setOf(DataExtensionCapability(RecompositionDataProductRegistry.KIND)),
      lifecycle = DataExtensionLifecycle.Subscribed,
    )

  @Composable
  override fun Observe(context: ExtensionComposeContext, sink: ExtensionCompositionSink) {
    val session = context.get(RecompositionExtensionContextKeys.ObservationSession) ?: return
    DisposableEffect(session, sink) {
      val handle = session.observe { payload ->
        sink.put(extensionId = id, key = PAYLOAD_KEY, value = payload)
      }
      onDispose { handle.dispose() }
    }
  }

  companion object {
    const val PAYLOAD_KEY: String = "payload"
  }
}

/**
 * Domain API for reading the recomposer that drives a desktop [ImageComposeScene].
 *
 * The regular recomposition extension should be authored against Compose runtime observer APIs.
 * This object hides Compose Desktop implementation details, making it easy to replace with a
 * composable extractor or public scene API when available.
 */
internal object DesktopSceneRecomposer {
  fun current(scene: ImageComposeScene): androidx.compose.runtime.Recomposer =
    currentFromSceneHandle(scene) as androidx.compose.runtime.Recomposer

  internal fun currentFromSceneHandle(scene: Any): Any {
    val composeScene = fieldValue(scene, "scene") ?: error("ImageComposeScene.scene was null")
    val sceneRecomposer =
      fieldValue(composeScene, "recomposer")
        ?: error("ComposeScene.recomposer was null on ${composeScene.javaClass.name}")
    val recomposer =
      fieldValue(sceneRecomposer, "recomposer")
        ?: error("ComposeSceneRecomposer.recomposer was null")
    return recomposer
  }

  private fun fieldValue(receiver: Any, name: String): Any? {
    val field = findDeclaredField(receiver.javaClass, name)
    field.isAccessible = true
    return field.get(receiver)
  }

  private fun findDeclaredField(start: Class<*>, name: String): java.lang.reflect.Field {
    var cls: Class<*>? = start
    while (cls != null) {
      try {
        return cls.getDeclaredField(name)
      } catch (_: NoSuchFieldException) {
        cls = cls.superclass
      }
    }
    throw NoSuchFieldException("$name not found on $start or any superclass")
  }
}

/**
 * Wire shape for `compose/recomposition` payloads (schemaVersion=1). Mirrors the JSON the VS Code
 * panel's heat-map overlay decodes. See
 * [docs/daemon/DATA-PRODUCTS.md](../../../../../../../docs/daemon/DATA-PRODUCTS.md) §
 * "Recomposition + interactive mode".
 *
 * [sinceFrameStreamId] / [inputSeq] are populated only in delta mode — the snapshot mode is a
 * one-shot answer to "what recomposed during the initial composition" with no temporal baseline to
 * track.
 */
@Serializable
data class RecompositionPayload(
  val mode: String,
  val sinceFrameStreamId: String? = null,
  val inputSeq: Long? = null,
  val nodes: List<RecompositionNode> = emptyList(),
)

@Serializable
data class RecompositionNode(
  /**
   * Identity-hashcode-of-RecomposeScope encoded as base-16 — stable for the duration of one
   * interactive session. NOT stable across sessions. See [RecompositionDataProductRegistry] KDoc
   * for the v2 followup (slot-table-derived `(file:line:column)` keys).
   */
  val nodeId: String,
  val count: Int,
)
