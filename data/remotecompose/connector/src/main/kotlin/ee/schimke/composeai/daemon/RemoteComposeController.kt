package ee.schimke.composeai.daemon

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import ee.schimke.composeai.daemon.protocol.RemoteComposeOverride
import ee.schimke.composeai.daemon.protocol.RemoteComposeProfile
import ee.schimke.composeai.daemon.protocol.RemoteHostAction
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import ee.schimke.composeai.data.remotecompose.RemoteComposeDeclarationsPayload
import ee.schimke.composeai.data.remotecompose.RemoteComposeKnobDeclaration
import ee.schimke.composeai.data.remotecompose.RemoteComposePayload
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.Json

/**
 * Process-static state holder for the Remote Compose connector.
 *
 * Four responsibilities:
 *
 * 1. **Named-value map** — the effective name -> [RemoteNamedValue] map for the current render.
 *    Snapshot-state so user code reading a value through `LocalRemoteComposeHost.current
 *    .namedFloat(...)` recomposes when the daemon pushes a fresh
 *    `renderNow.overrides.remoteCompose.namedValues` (or when user code writes back via
 *    [setNamedValue]).
 * 2. **Host-action buffer** — ring-buffered list of [RemoteHostAction]s the remote runtime fired.
 *    Bounded by [RemoteComposePayload.HOST_ACTION_BUFFER_SIZE] so a runaway emitter doesn't grow
 *    unboundedly. Each emission also fires registered [listeners] so a live
 *    `data/subscribe(kind=compose/remotecompose)` session pushes the event to the panel without
 *    waiting for the next render.
 * 3. **Active profile** — the [RemoteComposeProfile] the override last requested; null when no
 *    override is active. User code reads this via `LocalRemoteComposeHost.current.profile` and
 *    passes it to `RemotePreview(profile = …)`.
 * 4. **Accepted-action filter** — when the override carries
 *    [RemoteComposeOverride .acceptedHostActions], the controller only records actions whose
 *    `payload` is in the set. Null accepts everything. Lets a panel client constrain capture to
 *    events it actually wants surfaced without depending on remote-code-side filtering.
 *
 * Reads happen only from inside [RemoteComposeOverrideExtension.AroundComposable] (and from
 * `RemoteComposeDataProductRegistry.onRender`), which observe the snapshot-state via Compose's
 * normal subscription pipeline. Writers can be on any thread — the daemon's render thread for
 * `renderNow.overrides.remoteCompose` seeding, the composition thread for in-frame [setNamedValue]
 * / [recordHostAction] calls. Snapshot-state and `CopyOnWriteArrayList` carry the cross-thread
 * propagation.
 */
object RemoteComposeController {

  private val namedValuesState: MutableState<Map<String, RemoteNamedValue>> =
    mutableStateOf(emptyMap())

  private val hostActionsState: MutableState<List<RemoteHostAction>> = mutableStateOf(emptyList())

  private val profileState: MutableState<RemoteComposeProfile?> = mutableStateOf(null)

  // Editable named-value knobs the current render declared, keyed by name for dedup with first-seen
  // order preserved (a re-declaration during recomposition replaces the entry in place). Mirrors
  // `PreviewOverrideController.declarationsState` — the auto-capture surface the viewer renders
  // controls from.
  private val declarationsState: MutableState<Map<String, RemoteComposeKnobDeclaration>> =
    mutableStateOf(emptyMap())

  /**
   * Optional allow-list for [recordHostAction]. `null` accepts every action; non-null filters by
   * `payload` membership. Snapshot of the override's `acceptedHostActions`.
   */
  @Volatile private var acceptedActionPayloads: Set<String>? = null

  /** Hooks notified whenever the named-value map or host-action buffer changes. */
  private val listeners: MutableList<() -> Unit> = CopyOnWriteArrayList()

  /** Bridge scope key for a render that carries no previewId; mirrors the bridge's own sentinel. */
  private const val NO_PREVIEW_SCOPE: String = ""

  /**
   * previewId of the render currently composing, stamped by the around-composable via [beginRender].
   * Scopes the [bridgeForwarder] forwards so a pooled-sandbox run (`sandboxCount > 1`) doesn't leak
   * one preview's declarations into another's host-side snapshot.
   */
  @Volatile private var activePreviewId: String? = null

  private val json = Json { encodeDefaults = true }

  val namedValues: State<Map<String, RemoteNamedValue>>
    get() = namedValuesState

  val hostActions: State<List<RemoteHostAction>>
    get() = hostActionsState

  val profile: State<RemoteComposeProfile?>
    get() = profileState

  /**
   * Record an editable knob the preview just declared (via a `LocalRemoteComposeHost` named-value
   * read, or an explicit `declareKnob`). Keyed by [RemoteComposeKnobDeclaration.name]; a repeat
   * declaration of the same name (recomposition) replaces the prior entry while keeping its position
   * so the viewer's control list stays stable. Idempotent — re-recording an identical declaration
   * doesn't notify listeners. Mirrors `PreviewOverrideController.record`.
   */
  fun recordDeclaration(declaration: RemoteComposeKnobDeclaration) {
    val current = declarationsState.value
    if (current[declaration.name] != declaration) {
      // LinkedHashMap preserves first-seen order even when replacing an existing name's value.
      val next = LinkedHashMap(current)
      next[declaration.name] = declaration
      declarationsState.value = next
      listeners.toList().forEach { it() }
    }
    // When a document capture is being collected ([collectingDeclarations]), also stash the
    // declaration so the caller can re-record it on later renders (see `RemoteOverridablePreview`).
    declarationCollector?.let { sink -> if (declaration !in sink) sink.add(declaration) }
    // Cross-classloader forward for the Android sandbox. Serialise only when the bridge is present
    // (a plain app / desktop daemon skips this entirely). Always forwarded — even when the in-CL
    // value is unchanged — so a host reset the sandbox didn't observe is repopulated.
    bridgeForwarder?.record(
      bridgeScope(),
      declaration.name,
      json.encodeToString(RemoteComposeKnobDeclaration.serializer(), declaration),
    )
  }

  /** Active collection sink for [collectingDeclarations]; null when no capture is being collected. */
  @Volatile private var declarationCollector: MutableList<RemoteComposeKnobDeclaration>? = null

  /**
   * Run [block] with declaration collection active and return its result paired with every
   * declaration [recordDeclaration] saw during it (deduped, in first-seen order) — *in addition* to
   * the normal recording.
   *
   * `RemoteOverridablePreview` wraps its memoized `captureSingleRemoteDocument` in this so it can
   * snapshot the knobs the sticker declares while the document is captured, then re-record them on
   * every subsequent render. That matters on the daemon path: the memoized capture only records once
   * (during the outer *composition* phase), but `RemoteComposeOverrideExtension`'s render-start
   * [clearDeclarations] runs from a `DisposableEffect` (the outer *apply* phase, after the capture),
   * so without re-recording a `renderNow` / `data/fetch` render would report no knobs. Not
   * re-entrant (one capture at a time per classloader); a nested call restores the outer sink.
   */
  fun <T> collectingDeclarations(block: () -> T): Pair<T, List<RemoteComposeKnobDeclaration>> {
    val sink = mutableListOf<RemoteComposeKnobDeclaration>()
    val previous = declarationCollector
    declarationCollector = sink
    return try {
      block() to sink.toList()
    } finally {
      declarationCollector = previous
    }
  }

  /**
   * Stamp the previewId whose composition is about to run, so subsequent [recordDeclaration] /
   * [clearDeclarations] forwards land in this preview's bridge scope (not a concurrently-rendering
   * preview's, under a pooled sandbox). Called by the around-composable before preview content
   * composes; `null` (a render with no previewId) maps to the bridge's no-preview scope. Mirrors
   * `PreviewOverrideController.beginRender`.
   */
  fun beginRender(previewId: String?) {
    activePreviewId = previewId
  }

  /** Bridge scope key for the active render — the no-preview sentinel when unset. */
  private fun bridgeScope(): String = activePreviewId ?: NO_PREVIEW_SCOPE

  /** The knobs declared so far this render, in declaration order. */
  fun declarations(): List<RemoteComposeKnobDeclaration> = declarationsState.value.values.toList()

  /**
   * The declared knobs serialised as a [RemoteComposeDeclarationsPayload] JSON string, or `null`
   * when nothing was declared. Called **reflectively** by the standalone render step
   * (`RobolectricRenderTest.writeRemoteComposeSidecar`) to emit the
   * `renders/<stem>.remotecompose.json` bundle sidecar — reflection keeps the renderer free of a
   * hard dependency on this alpha-gated connector, exactly like the bridge readers. Returning a
   * ready JSON string (not the typed list) means the renderer never needs the
   * `RemoteComposeKnobDeclaration` type on its classpath.
   */
  fun declarationsJson(): String? {
    val decls = declarations()
    if (decls.isEmpty()) return null
    return json.encodeToString(
      RemoteComposeDeclarationsPayload.serializer(),
      RemoteComposeDeclarationsPayload(decls),
    )
  }

  /**
   * Drop the recorded declarations at the start of a render pass, keeping named values / profile /
   * host actions, so a held session re-rendering with a shrunk knob set doesn't carry stale
   * declarations from an earlier pass. Called from [RemoteComposeOverrideExtension] before the pass
   * re-records via the `named*` reads' `SideEffect`s. Mirrors
   * `PreviewOverrideController.clearDeclarations`.
   */
  fun clearDeclarations() {
    // Always reset the bridge scope (even when the in-classloader set is already empty) so a
    // shrinking list's stale knobs drop from a reused sandbox's bridge entries — mirrors
    // `PreviewOverrideController.clearDeclarations`.
    bridgeForwarder?.reset(bridgeScope())
    if (declarationsState.value.isEmpty()) return
    declarationsState.value = emptyMap()
  }

  /**
   * Read [name]'s current value, or `null` if no override / write has bound it. Caller decides the
   * default (the `LocalRemoteComposeHost.namedFloat(name, default)` helpers default to the user-
   * supplied fallback when this returns null).
   */
  fun valueOf(name: String): RemoteNamedValue? = namedValuesState.value[name]

  /**
   * Apply a fresh override. Replaces the entire named-value map, the active profile, and the
   * accepted-action filter — a follow-up `renderNow.overrides.remoteCompose` with `namedValues =
   * mapOf("score" to FloatValue(0f))` drops anything previously seeded that isn't in the new map.
   * Matches `KeyboardController.seed` / `PermissionsController.set` semantics. `null` clears
   * everything (empty map / no profile / accept-all filter).
   *
   * Does NOT clear the host-action buffer — captured events persist across overrides so a panel
   * pushing a new seed mid-session keeps the audit trail of what fired before. Use
   * [resetForNewSession] to drop both state and buffer.
   */
  fun set(override: RemoteComposeOverride?) {
    namedValuesState.value = override?.namedValues ?: emptyMap()
    profileState.value = override?.profile
    acceptedActionPayloads = override?.acceptedHostActions?.toSet()
    listeners.toList().forEach { it() }
  }

  /**
   * Push a named-value write back from user code (typically from inside a `RemotePreview` block
   * after the remote runtime computed a new value). Merges into the existing map rather than
   * replacing — daemon-seeded entries the user code didn't touch stay intact. Idempotent: writing
   * the same value twice doesn't notify listeners.
   */
  fun setNamedValue(name: String, value: RemoteNamedValue) {
    val current = namedValuesState.value
    if (current[name] == value) return
    namedValuesState.value = current + (name to value)
    listeners.toList().forEach { it() }
  }

  /**
   * Replace just the active profile without touching named values or the accept-list. Mirrors
   * [setNamedValue]'s "merge, don't replace" semantics for the profile facet so a live edit
   * dispatched by `interactive/setRemoteCompose` lands cleanly on top of an existing override bag.
   * Idempotent — writing the same profile twice doesn't notify listeners.
   */
  fun setProfile(profile: RemoteComposeProfile?) {
    if (profileState.value == profile) return
    profileState.value = profile
    listeners.toList().forEach { it() }
  }

  /**
   * Record a `HostAction` emission. Filtered against the override's
   * [RemoteComposeOverride .acceptedHostActions] set when present (null accepts every action). The
   * ring buffer is capped at [RemoteComposePayload.HOST_ACTION_BUFFER_SIZE] entries — once full,
   * the oldest entry drops on each new append so the most recent activity always wins. Listeners
   * fire after every accepted entry so a `data/subscribe(kind=compose/remotecompose)` session sees
   * the event without waiting for the next render.
   */
  fun recordHostAction(action: RemoteHostAction) {
    val accepted = acceptedActionPayloads
    if (accepted != null && action.payload !in accepted) return
    // Stamp receiver-side wall-clock at ingest so consumers can order/time events;
    // most callers default to 0, which would otherwise emit zero timestamps downstream.
    val stamped =
      if (action.firedAtMillis == 0L) action.copy(firedAtMillis = System.currentTimeMillis())
      else action
    val current = hostActionsState.value
    val next =
      if (current.size < RemoteComposePayload.HOST_ACTION_BUFFER_SIZE) current + stamped
      else current.drop(current.size - RemoteComposePayload.HOST_ACTION_BUFFER_SIZE + 1) + stamped
    hostActionsState.value = next
    listeners.toList().forEach { it() }
  }

  /** Register a callback fired on every state change. Returns an unregister handle. */
  fun addChangeListener(listener: () -> Unit): () -> Unit {
    listeners.add(listener)
    return { listeners.remove(listener) }
  }

  /**
   * Cleanup hook for per-session reset (interactive close, recording stop, sandbox recycle). Drops
   * the named-value map, the host-action buffer, and the active profile so the next preview starts
   * fresh. Mirrors `KeyboardController.resetForNewSession` /
   * `PermissionsController.resetForNewSession`.
   */
  fun resetForNewSession() {
    val scope = bridgeScope()
    namedValuesState.value = emptyMap()
    hostActionsState.value = emptyList()
    profileState.value = null
    declarationsState.value = emptyMap()
    activePreviewId = null
    acceptedActionPayloads = null
    bridgeForwarder?.reset(scope)
  }

  /**
   * Resolved once per JVM, cached even on failure. `null` means the bridge class isn't on the
   * classpath (plain apps, the desktop daemon, connector unit tests) — every forward no-ops. Mirrors
   * `PreviewOverrideController`'s `BridgeForwarder`.
   */
  private val bridgeForwarder: BridgeForwarder? by lazy { BridgeForwarder.tryLoad() }

  /**
   * Reflective handle to `ee.schimke.composeai.daemon.bridge.SandboxRemoteComposeBridge`, which lives
   * in `:daemon:android` (a downstream module this connector does NOT depend on). Reached via
   * `Class.forName` — same shape as `PreviewOverrideController`'s `BridgeForwarder`. In the
   * production Android daemon the controller is sandbox-loaded and the bridge package is
   * do-not-acquire on the sandbox classloader, so both sides observe the same single bridge instance.
   */
  private class BridgeForwarder(
    private val recordMethod: java.lang.reflect.Method,
    private val resetMethod: java.lang.reflect.Method,
  ) {
    fun record(previewId: String, name: String, json: String) {
      try {
        recordMethod.invoke(null, previewId, name, json)
      } catch (_: ReflectiveOperationException) {
        // Drop — the in-classloader controller state still serves the same-CL fast path.
      }
    }

    fun reset(previewId: String) {
      try {
        resetMethod.invoke(null, previewId)
      } catch (_: ReflectiveOperationException) {
        // Same defensive drop.
      }
    }

    companion object {
      private const val BRIDGE_FQN: String =
        "ee.schimke.composeai.daemon.bridge.SandboxRemoteComposeBridge"

      fun tryLoad(): BridgeForwarder? =
        try {
          val cls = Class.forName(BRIDGE_FQN, true, RemoteComposeController::class.java.classLoader)
          BridgeForwarder(
            recordMethod =
              cls.getMethod("record", String::class.java, String::class.java, String::class.java),
            resetMethod = cls.getMethod("reset", String::class.java),
          )
        } catch (_: ClassNotFoundException) {
          null
        } catch (_: NoSuchMethodException) {
          null
        }
    }
  }
}
