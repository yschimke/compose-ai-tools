package ee.schimke.composeai.daemon

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import ee.schimke.composeai.daemon.protocol.RemoteComposeOverride
import ee.schimke.composeai.daemon.protocol.RemoteComposeProfile
import ee.schimke.composeai.daemon.protocol.RemoteHostAction
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import ee.schimke.composeai.data.remotecompose.RemoteComposePayload
import java.util.concurrent.CopyOnWriteArrayList

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
 * 4. **Accepted-action filter** — when the override carries [RemoteComposeOverride
 *    .acceptedHostActions], the controller only records actions whose `payload` is in the set.
 *    Null accepts everything. Lets a panel client constrain capture to events it actually wants
 *    surfaced without depending on remote-code-side filtering.
 *
 * Reads happen only from inside [RemoteComposeOverrideExtension.AroundComposable] (and from
 * `RemoteComposeDataProductRegistry.onRender`), which observe the snapshot-state via Compose's
 * normal subscription pipeline. Writers can be on any thread — the daemon's render thread for
 * `renderNow.overrides.remoteCompose` seeding, the composition thread for in-frame
 * [setNamedValue] / [recordHostAction] calls. Snapshot-state and `CopyOnWriteArrayList` carry
 * the cross-thread propagation.
 */
object RemoteComposeController {

  private val namedValuesState: MutableState<Map<String, RemoteNamedValue>> =
    mutableStateOf(emptyMap())

  private val hostActionsState: MutableState<List<RemoteHostAction>> = mutableStateOf(emptyList())

  private val profileState: MutableState<RemoteComposeProfile?> = mutableStateOf(null)

  /**
   * Optional allow-list for [recordHostAction]. `null` accepts every action; non-null filters by
   * `payload` membership. Snapshot of the override's `acceptedHostActions`.
   */
  @Volatile private var acceptedActionPayloads: Set<String>? = null

  /** Hooks notified whenever the named-value map or host-action buffer changes. */
  private val listeners: MutableList<() -> Unit> = CopyOnWriteArrayList()

  val namedValues: State<Map<String, RemoteNamedValue>>
    get() = namedValuesState

  val hostActions: State<List<RemoteHostAction>>
    get() = hostActionsState

  val profile: State<RemoteComposeProfile?>
    get() = profileState

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
   * dispatched by `interactive/setRemoteCompose` lands cleanly on top of an existing override
   * bag. Idempotent — writing the same profile twice doesn't notify listeners.
   */
  fun setProfile(profile: RemoteComposeProfile?) {
    if (profileState.value == profile) return
    profileState.value = profile
    listeners.toList().forEach { it() }
  }

  /**
   * Record a `HostAction` emission. Filtered against the override's [RemoteComposeOverride
   * .acceptedHostActions] set when present (null accepts every action). The ring buffer is capped
   * at [RemoteComposePayload.HOST_ACTION_BUFFER_SIZE] entries — once full, the oldest entry drops
   * on each new append so the most recent activity always wins. Listeners fire after every
   * accepted entry so a `data/subscribe(kind=compose/remotecompose)` session sees the event
   * without waiting for the next render.
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
   * the named-value map, the host-action buffer, and the active profile so the next preview
   * starts fresh. Mirrors `KeyboardController.resetForNewSession` /
   * `PermissionsController.resetForNewSession`.
   */
  fun resetForNewSession() {
    namedValuesState.value = emptyMap()
    hostActionsState.value = emptyList()
    profileState.value = null
    acceptedActionPayloads = null
  }
}
