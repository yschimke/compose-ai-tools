package ee.schimke.composeai.daemon

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import ee.schimke.composeai.daemon.protocol.PermissionGrantStateOverride
import ee.schimke.composeai.daemon.protocol.PermissionsOverride
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-static state holder for the Android runtime-permissions connector.
 *
 * Three responsibilities:
 *
 * 1. **Grant map** — the effective permission -> grant-state mapping the around-composable applies
 *    for the current render. Held in snapshot state so future readers inside a `@Composable`
 *    recompose on flips; nothing in-tree reads it from composition today (the override flows
 *    through Robolectric's `ShadowApplication` grants) — kept observable so a future panel-driven
 *    "live grants" mid-composition hook can plug in without changing the controller's shape.
 * 2. **Query tracker** — the set of permissions the screen has asked about so far in the render /
 *    interactive session, in insertion order. Written by the Robolectric shadow on
 *    `ContextWrapper.checkPermission(...)`, which covers every standard Android check API
 *    (`ContextCompat.checkSelfPermission`, `Activity.checkSelfPermission`,
 *    `Context.checkPermission`, accompanist's `rememberPermissionState`).
 * 3. **Robolectric grant sync** — when the override is applied the controller calls into
 *    `ShadowApplication.grantPermissions(...)` / `denyPermissions(...)` via reflection so consumer
 *    code reading the permission through the platform path also sees the requested state without
 *    the connector forking a custom shadow on `Application.checkPermission`.
 *
 * **Threading.** [set], [recordQuery], [resetForNewSession] are guarded by a single mutex.
 * Snapshot-state writes happen on whatever thread fires the override (the daemon's render thread
 * for `renderNow.overrides.permissions`); reads are by definition on the composition thread and
 * Compose's snapshot machinery handles the cross-thread propagation.
 */
object PermissionsController {

  /**
   * Bridge scope key for a render with no previewId. Must equal
   * `SandboxPermissionsBridge.NO_PREVIEW_SCOPE`; duplicated as a literal here because the
   * controller reaches the bridge reflectively (no compile-time dependency on `:daemon:android`).
   */
  private const val NO_PREVIEW_SCOPE: String = ""

  private val lock = Any()

  /** Effective grant map. Persisted across renders so a session that flips a grant mid-flight is observable. */
  private val grantsState: MutableState<Map<String, PermissionGrantStateOverride>> =
    mutableStateOf(emptyMap())

  /** Insertion-ordered tracking of which permissions the screen has queried. */
  private val queriedSet: MutableState<List<String>> = mutableStateOf(emptyList())

  /** Hooks notified on grant-map changes — populated by the around-composable's `DisposableEffect`. */
  private val listeners: MutableList<() -> Unit> = CopyOnWriteArrayList()

  /** Cache of unique queried permissions for O(1) duplicate suppression in [recordQuery]. */
  private val queriedSeen: MutableSet<String> = ConcurrentHashMap.newKeySet()

  /**
   * previewId of the render whose composition is currently driving this controller, or `null` for
   * a render with no previewId. Set by [PermissionsOverrideExtension]'s around-composable from
   * `ExtensionComposeContext.previewId` before the preview content composes, so the shadow-driven
   * [recordQuery] can stamp the cross-classloader bridge with the right scope (issue #1593).
   *
   * `@Volatile`, not mutex-guarded: composition for a given sandbox is single-threaded, so the only
   * cross-thread reader is a defensive one; a plain volatile is enough to publish the write made on
   * the composition thread. The controller is loaded fresh per sandbox, so this never holds two
   * concurrent previews' ids — those live in separate classloaders, each with their own static.
   */
  @Volatile private var activePreviewId: String? = null

  val grants: State<Map<String, PermissionGrantStateOverride>>
    get() = grantsState

  val queried: State<List<String>>
    get() = queriedSet

  /**
   * Apply a fresh override. Replaces the entire grant map — a follow-up `renderNow.overrides
   * .permissions` with `grants = mapOf("CAMERA" to GRANTED)` revokes anything previously granted
   * that isn't in the new map. Matches `KeyboardController.seed` semantics. `null` clears the
   * override (empty map).
   *
   * Also pushes the grant state into Robolectric's `ShadowApplication` so the platform permission
   * path (`ContextCompat.checkSelfPermission`, `Activity.checkSelfPermission`,
   * `PackageManager.checkPermission`) returns the requested value without a fresh shadow on
   * `Application.checkPermission`. Reflection-driven to avoid a hard compile-time dep on
   * Robolectric in the connector's public surface — the daemon's runtime classpath always carries
   * Robolectric.
   */
  fun set(override: PermissionsOverride?) {
    val newGrants = override?.grants ?: emptyMap()
    synchronized(lock) {
      grantsState.value = newGrants
      syncRobolectricGrants(newGrants)
    }
    listeners.toList().forEach { it() }
  }

  /** Read the current grant for [permission]. `null` means "no override applied" — caller defaults. */
  fun grantFor(permission: String): PermissionGrantStateOverride? = grantsState.value[permission]

  /**
   * Record a query against [permission]. Idempotent — duplicate queries don't re-append. The
   * insertion-ordered list is what the data-product payload surfaces, so the panel can display
   * queries in roughly the sequence the composition issued them.
   *
   * Also forwards to the cross-classloader [SandboxPermissionsBridge][bridgeForwarder] so the
   * daemon-side `PermissionsDataProductRegistry`, which lives in the host classloader, can read
   * out the sandbox-side queries. Without the forward, the host registry's
   * `PermissionsController.queried.value` read returns the host-CL controller's (empty) state and
   * `data/fetch?kind=compose/permissions` reports no queries even though
   * `ShadowContextWrapperPermissionTracker` caught them. The bridge is loaded reflectively so the
   * connector keeps its no-`:daemon:android` dependency shape — the same pattern
   * [syncRobolectricGrants] uses for Robolectric.
   */
  fun recordQuery(permission: String) {
    if (queriedSeen.add(permission)) {
      synchronized(lock) { queriedSet.value = queriedSet.value + permission }
    }
    bridgeForwarder?.recordQuery(bridgeScope(), permission)
  }

  /**
   * Sandbox-side: stamp the previewId whose composition is about to read permissions, so the
   * subsequent shadow-driven [recordQuery] forwards land in that preview's bridge scope. Called by
   * [PermissionsOverrideExtension]'s around-composable before the preview content composes. `null`
   * (a render with no previewId) maps to the bridge's no-preview scope.
   */
  fun beginRender(previewId: String?) {
    activePreviewId = previewId
  }

  /** Bridge scope key for the active render — the bridge's no-preview sentinel when unset. */
  private fun bridgeScope(): String = activePreviewId ?: NO_PREVIEW_SCOPE

  /** Register a callback fired on every [set] transition. Returns an unregister handle. */
  fun addChangeListener(listener: () -> Unit): () -> Unit {
    listeners.add(listener)
    return { listeners.remove(listener) }
  }

  /**
   * Cleanup hook for per-session reset (interactive close, recording stop, sandbox recycle). Drops
   * the grant map and the queried list so the next preview starts fresh. Mirrors
   * `KeyboardController.resetForNewSession` / `AmbientStateController.resetForNewSession`.
   */
  fun resetForNewSession() {
    val scope = bridgeScope()
    synchronized(lock) {
      grantsState.value = emptyMap()
      queriedSet.value = emptyList()
      queriedSeen.clear()
      activePreviewId = null
      syncRobolectricGrants(emptyMap())
    }
    // Scope the bridge reset to the closing preview so a concurrent preview's queries survive —
    // a JVM-wide clear here would reintroduce the cross-preview leak the per-preview keying fixes.
    bridgeForwarder?.reset(scope)
  }

  /**
   * Push the grant map into Robolectric's `ShadowApplication`. We reach into the
   * `org.robolectric.Shadows.shadowOf(application)` API reflectively so a non-Robolectric
   * classpath (impossible today, but defensive) doesn't link-error on the controller class itself.
   * The shadow's `grantPermissions(vararg String)` / `denyPermissions(vararg String)` are the
   * supported public API for seeding the platform permission path; everything `ContextCompat
   * .checkSelfPermission` reaches eventually consults the same data structure.
   *
   * Permissions present in the override are granted or denied per their wire state. Permissions
   * NOT present in the override are explicitly denied so a re-render with a shrunk grant map
   * doesn't leak the previous render's grants.
   */
  private fun syncRobolectricGrants(grants: Map<String, PermissionGrantStateOverride>) {
    try {
      val rEnvCls =
        Class.forName("org.robolectric.RuntimeEnvironment", true, javaClass.classLoader)
      val app = rEnvCls.getMethod("getApplication").invoke(null) ?: return
      val shadowsCls = Class.forName("org.robolectric.Shadows", true, javaClass.classLoader)
      val shadowOf =
        shadowsCls.getMethod("shadowOf", Class.forName("android.app.Application"))
      val shadowApp = shadowOf.invoke(null, app) ?: return
      val granted =
        grants.filterValues { it == PermissionGrantStateOverride.GRANTED }.keys.toTypedArray()
      val denied =
        grants.filterValues { it == PermissionGrantStateOverride.DENIED }.keys.toTypedArray()
      val shadowAppCls = shadowApp.javaClass
      // Clear the previously-granted set first by denying everything in the override; then grant
      // the explicit grants. Permissions outside the override stay at their post-deny default
      // (denied), which is what we want for "absent from map = revoke".
      shadowAppCls.getMethod("denyPermissions", Array<String>::class.java)
        .invoke(shadowApp, denied + granted)
      if (granted.isNotEmpty()) {
        shadowAppCls.getMethod("grantPermissions", Array<String>::class.java)
          .invoke(shadowApp, granted)
      }
    } catch (_: ClassNotFoundException) {
      // No Robolectric on this classpath — the controller still tracks queries via the shadow
      // and serves the captured set through the data product. Production Android paths reach
      // this controller only inside a Robolectric sandbox today, so the catch is defensive.
    } catch (_: NoSuchMethodException) {
      // Robolectric API surface drifted — fall back to controller-only state without crashing the
      // render. A subsequent Robolectric bump that renames the methods will need a code update.
    } catch (_: ReflectiveOperationException) {
      // Underlying invocation failure (e.g. application not yet initialised). Drop silently — the
      // controller's snapshot state still reflects the requested grants for the data product.
    }
  }

  /**
   * Resolved once per JVM, cached even on failure. `null` means the bridge class isn't on the
   * classpath (connector-module unit tests; consumers that depend on the connector without the
   * `:daemon:android` artefact) — every forward call no-ops in that case.
   */
  private val bridgeForwarder: BridgeForwarder? by lazy { BridgeForwarder.tryLoad() }

  /**
   * Reflective handle to [bridge.SandboxPermissionsBridge][ee.schimke.composeai.daemon.bridge.SandboxPermissionsBridge].
   * The bridge lives in `:daemon:android` (a downstream module the connector does NOT depend on),
   * so the connector reaches it through `Class.forName` — same shape as [syncRobolectricGrants]'s
   * reach into Robolectric. Connector-only consumers (no daemon-android on the classpath) see
   * `tryLoad() == null` and the connector still serves the in-CL controller state.
   *
   * `Class.forName(..., javaClass.classLoader)`: in the production daemon, the controller is
   * sandbox-loaded so `javaClass.classLoader` is the Robolectric sandbox CL; the bridge package
   * (`ee.schimke.composeai.daemon.bridge`) is registered as do-not-acquire on that CL, so the
   * sandbox delegates loading to the daemon CL parent — both sides observe the same single bridge
   * instance and writes here are readable from the host registry.
   */
  private class BridgeForwarder(
    private val recordQueryMethod: java.lang.reflect.Method,
    private val resetMethod: java.lang.reflect.Method,
  ) {
    fun recordQuery(previewId: String, permission: String) {
      try {
        recordQueryMethod.invoke(null, previewId, permission)
      } catch (_: ReflectiveOperationException) {
        // Bridge invocation failed (unexpected — the class loaded but the call failed). Drop —
        // controller's in-CL state still serves the same-CL fast path.
      }
    }

    fun reset(previewId: String) {
      try {
        resetMethod.invoke(null, previewId)
      } catch (_: ReflectiveOperationException) {
        // Same defensive drop as recordQuery; the controller's in-CL state already cleared above.
      }
    }

    companion object {
      private const val BRIDGE_FQN: String = "ee.schimke.composeai.daemon.bridge.SandboxPermissionsBridge"

      fun tryLoad(): BridgeForwarder? =
        try {
          val cls = Class.forName(BRIDGE_FQN, true, PermissionsController::class.java.classLoader)
          BridgeForwarder(
            recordQueryMethod =
              cls.getMethod("recordQuery", String::class.java, String::class.java),
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
