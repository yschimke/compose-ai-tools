package ee.schimke.composeai.overrides

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import ee.schimke.composeai.data.overrides.PreviewOverrideDeclaration
import ee.schimke.composeai.data.overrides.PreviewOverrideType
import ee.schimke.composeai.data.overrides.PreviewOverrideValue
import ee.schimke.composeai.data.overrides.dedupeResourceOverrideDeclarations
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.Json

/**
 * Process-static state holder for the plain-Compose named-override surface — the counterpart to
 * `RemoteComposeController`, minus the Remote-Compose-specific facets (host actions, platform
 * profile).
 *
 * Two responsibilities:
 *
 * 1. **Seeded values** — the daemon-supplied `name -> [PreviewOverrideValue]` map for the current
 *    render (`renderNow.overrides.namedOverrides`, keyed by [PreviewOverrideDeclaration.seedKey]).
 *    Snapshot-state so a `previewOverride*` lookup recomposes when the daemon pushes a fresh seed.
 * 2. **Declarations** — the ordered set of knobs the preview declared *this render* via its
 *    `previewOverride*` calls. Accumulated as the composition runs (deduped by `seedKey`,
 *    declaration order preserved) so a producer — the daemon's `compose/overrides` data product, or
 *    a standalone render's bundle-sidecar drain — can read back "what is editable on this preview".
 *
 * The controller is **always** the fallback host (see [LocalPreviewOverrideHost]) so a plain Gradle
 * render with no daemon still records declarations (with no seeds, every lookup returns its author
 * default). When the connector's around-composable is active it seeds values through [set] before
 * the preview composes.
 *
 * Writers can be on any thread — the daemon's render thread for seeding, the composition thread for
 * [record]. Snapshot-state + a copy-on-write listener list carry cross-thread propagation.
 *
 * **Android sandbox bridge.** On the Android daemon a preview composes inside a Robolectric sandbox
 * classloader, so the controller's static state is a *different instance* from the one the
 * host-side `PreviewOverridesDataProductRegistry` reads. To make
 * `data/fetch?kind=compose/overrides` work there, every [record] / [clearDeclarations] /
 * [resetForNewSession] also forwards to the do-not-acquire `SandboxPreviewOverridesBridge`
 * singleton — reached **reflectively** so this consumer-facing runtime keeps its
 * no-`:daemon:android` dependency shape. When the bridge class isn't on the classpath (a plain app,
 * the desktop daemon, connector unit tests) the forward is a cheap no-op and the in-classloader
 * state is the only source of truth. Mirrors `PermissionsController`'s `SandboxPermissionsBridge`
 * forwarding.
 */
object PreviewOverrideController {

  /** Bridge scope key for a render that carries no previewId; mirrors the bridge's own sentinel. */
  private const val NO_PREVIEW_SCOPE: String = ""

  private val seededValuesState: MutableState<Map<String, PreviewOverrideValue>> =
    mutableStateOf(emptyMap())

  // Insertion-ordered, deduped by seedKey. A LinkedHashMap snapshot keeps declaration order stable
  // for the viewer while letting a re-declared key (recomposition) replace its prior entry in
  // place.
  private val declarationsState: MutableState<Map<String, PreviewOverrideDeclaration>> =
    mutableStateOf(emptyMap())

  // Auto-synthesised knobs for strings loaded from resources (see [recordResource]). Held in a
  // SEPARATE bucket from the explicit `previewOverride*` declarations above because the two have
  // different lifecycles: the resource interceptor records eagerly while resources load (on desktop
  // that is an async coroutine dispatcher, out of step with composition), whereas the explicit
  // knobs
  // record from a post-composition `SideEffect` after [clearDeclarations] has wiped the prior
  // render. Folding resource knobs into `declarationsState` would let `clearDeclarations` erase a
  // resource record that already landed this render, and the warm CMP string cache would keep the
  // interceptor from re-recording it — the knob would vanish. Keeping them apart, reset once per
  // render via [resetResourceDeclarations] (not on the explicit-knob clear), makes the two
  // independent.
  private val resourceDeclarationsState: MutableState<Map<String, PreviewOverrideDeclaration>> =
    mutableStateOf(emptyMap())

  private val listeners: MutableList<() -> Unit> = CopyOnWriteArrayList()

  /**
   * previewId of the render currently composing, stamped by the around-composable via
   * [beginRender].
   */
  @Volatile private var activePreviewId: String? = null

  private val json = Json { encodeDefaults = true }

  val seededValues: State<Map<String, PreviewOverrideValue>>
    get() = seededValuesState

  /** Current seeded value for [seedKey], or null when no override bound it. */
  fun valueOf(seedKey: String): PreviewOverrideValue? = seededValuesState.value[seedKey]

  /**
   * Seed the replacement values for this render. Replaces the whole map — a follow-up render
   * carrying a subset drops anything not present (the daemon's [mergePreviewOverrides] does per-key
   * merging before this point, so the map handed here is already the effective set). `null` / empty
   * clears all seeds.
   */
  fun set(values: Map<String, PreviewOverrideValue>?) {
    val next = values ?: emptyMap()
    if (seededValuesState.value == next) return
    seededValuesState.value = next
    listeners.toList().forEach { it() }
  }

  /**
   * Stamp the previewId whose composition is about to run, so subsequent [record] /
   * [clearDeclarations] forwards land in this preview's bridge scope (not a concurrently-rendering
   * preview's, under a pooled sandbox). Called by the around-composable before preview content
   * composes; `null` (a render with no previewId) maps to the bridge's no-preview scope.
   */
  fun beginRender(previewId: String?) {
    activePreviewId = previewId
  }

  /** Bridge scope key for the active render — the no-preview sentinel when unset. */
  private fun bridgeScope(): String = activePreviewId ?: NO_PREVIEW_SCOPE

  /**
   * Record a knob the preview just declared. Keyed by [PreviewOverrideDeclaration.seedKey]; a
   * repeat declaration of the same key (recomposition) replaces the prior entry while keeping its
   * position, so the viewer's control list is stable across recompositions.
   */
  fun record(declaration: PreviewOverrideDeclaration) {
    val current = declarationsState.value
    val prior = current[declaration.seedKey]
    if (prior == declaration) return
    // LinkedHashMap to preserve first-seen order even when replacing an existing key's value.
    val next = LinkedHashMap(current)
    next[declaration.seedKey] = declaration
    declarationsState.value = next
    listeners.toList().forEach { it() }
    // Cross-classloader forward for the Android sandbox. Serialise only when the bridge is present
    // (a plain app / desktop daemon skips this entirely).
    bridgeForwarder?.record(
      bridgeScope(),
      declaration.seedKey,
      json.encodeToString(PreviewOverrideDeclaration.serializer(), declaration),
    )
  }

  /**
   * Record an auto-synthesised knob for a string just loaded from resources, keyed by
   * [PreviewOverrideDeclaration.seedKey] (a `res:`-prefixed key the backend mints
   * deterministically). Kept in the resource bucket so the explicit-knob [clearDeclarations] can't
   * erase it mid-render; [resetResourceDeclarations] clears this bucket once per render instead. A
   * repeat record of the same key (a later render pass, or the same string read twice) replaces the
   * prior entry in place, preserving first-seen order.
   */
  fun recordResource(declaration: PreviewOverrideDeclaration) {
    val current = resourceDeclarationsState.value
    if (current[declaration.seedKey] == declaration) return
    val next = LinkedHashMap(current)
    next[declaration.seedKey] = declaration
    resourceDeclarationsState.value = next
    listeners.toList().forEach { it() }
  }

  /**
   * Resolve the effective text for a resource-loaded string and record it as an editable knob in
   * one call — the entry point the platform resource interceptors use. Returns the daemon-seeded
   * replacement bound to [key] (via the same `namedOverrides` map that seeds explicit knobs) when
   * one is present and string-typed, otherwise [default]. Either way the knob is recorded (with its
   * resolved `current`) so a viewer can offer an editable control. [key] must carry the `res:`
   * prefix; [label] is what the viewer shows (typically the author default text).
   */
  fun resolveResourceString(key: String, default: String, label: String = default): String {
    val effective = (valueOf(key) as? PreviewOverrideValue.StringValue)?.value ?: default
    recordResource(
      PreviewOverrideDeclaration(
        key = key,
        type = PreviewOverrideType.STRING,
        label = label,
        default = PreviewOverrideValue.StringValue(default),
        current = PreviewOverrideValue.StringValue(effective),
      )
    )
    return effective
  }

  /**
   * Drop the resource-synthesised knobs so the next render re-discovers its own set. Called once
   * per render by the resource interceptor's around-composable (keyed on the extension instance so
   * it runs at render start, not on every recomposition) — deliberately NOT wired into
   * [clearDeclarations], which only owns the explicit `previewOverride*` bucket.
   */
  fun resetResourceDeclarations() {
    if (resourceDeclarationsState.value.isEmpty()) return
    resourceDeclarationsState.value = emptyMap()
  }

  /**
   * The knobs declared so far this render, in declaration order: the explicit `previewOverride*`
   * knobs first, then the auto-synthesised resource-string knobs. A resource knob that merely
   * duplicates an explicit knob's author default is dropped (see
   * [dedupeResourceOverrideDeclarations]) so a preview that wraps a `stringResource(...)` in
   * `previewOverrideString(...)` shows one control, not two.
   */
  fun declarations(): List<PreviewOverrideDeclaration> {
    val explicit = declarationsState.value.values
    val resource = resourceDeclarationsState.value.values
    if (resource.isEmpty()) return explicit.toList()
    return dedupeResourceOverrideDeclarations(explicit + resource)
  }

  /** Register a callback fired on every state change. Returns an unregister handle. */
  fun addChangeListener(listener: () -> Unit): () -> Unit {
    listeners.add(listener)
    return { listeners.remove(listener) }
  }

  /**
   * Drop seeds and recorded declarations so the next preview starts fresh. Called on a new render /
   * interactive-session boundary. Mirrors `RemoteComposeController.resetForNewSession`.
   */
  fun resetForNewSession() {
    val scope = bridgeScope()
    seededValuesState.value = emptyMap()
    declarationsState.value = emptyMap()
    resourceDeclarationsState.value = emptyMap()
    activePreviewId = null
    bridgeForwarder?.reset(scope)
  }

  /**
   * Clear only the recorded declarations, keeping any seeded values, so a fresh composition
   * re-declares its current set without losing the daemon's seed. Used at the start of each render
   * pass. Always resets the bridge scope (even when the in-classloader set is already empty) so a
   * shrinking list's stale indexed knobs drop from a reused sandbox's bridge entries.
   */
  fun clearDeclarations() {
    bridgeForwarder?.reset(bridgeScope())
    if (declarationsState.value.isEmpty()) return
    declarationsState.value = emptyMap()
  }

  /**
   * Resolved once per JVM, cached even on failure. `null` means the bridge class isn't on the
   * classpath (plain apps, the desktop daemon, connector unit tests) — every forward no-ops.
   */
  private val bridgeForwarder: BridgeForwarder? by lazy { BridgeForwarder.tryLoad() }

  /**
   * Reflective handle to `ee.schimke.composeai.daemon.bridge.SandboxPreviewOverridesBridge`, which
   * lives in `:daemon:android` (a downstream module this runtime does NOT depend on). Reached via
   * `Class.forName` — same shape as `PermissionsController`'s `BridgeForwarder`. In the production
   * Android daemon the controller is sandbox-loaded, and the bridge package is do-not-acquire on
   * the sandbox classloader, so both sides observe the same single bridge instance.
   */
  private class BridgeForwarder(
    private val recordMethod: java.lang.reflect.Method,
    private val resetMethod: java.lang.reflect.Method,
  ) {
    fun record(previewId: String, seedKey: String, json: String) {
      try {
        recordMethod.invoke(null, previewId, seedKey, json)
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
        "ee.schimke.composeai.daemon.bridge.SandboxPreviewOverridesBridge"

      fun tryLoad(): BridgeForwarder? =
        try {
          val cls =
            Class.forName(BRIDGE_FQN, true, PreviewOverrideController::class.java.classLoader)
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
