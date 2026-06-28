package ee.schimke.composeai.overrides

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import ee.schimke.composeai.daemon.protocol.PreviewOverrideValue
import ee.schimke.composeai.data.overrides.PreviewOverrideDeclaration
import java.util.concurrent.CopyOnWriteArrayList

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
 */
object PreviewOverrideController {

  private val seededValuesState: MutableState<Map<String, PreviewOverrideValue>> =
    mutableStateOf(emptyMap())

  // Insertion-ordered, deduped by seedKey. A LinkedHashMap snapshot keeps declaration order stable
  // for
  // the viewer while letting a re-declared key (recomposition) replace its prior entry in place.
  private val declarationsState: MutableState<Map<String, PreviewOverrideDeclaration>> =
    mutableStateOf(emptyMap())

  private val listeners: MutableList<() -> Unit> = CopyOnWriteArrayList()

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
  }

  /** The knobs declared so far this render, in declaration order. */
  fun declarations(): List<PreviewOverrideDeclaration> = declarationsState.value.values.toList()

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
    seededValuesState.value = emptyMap()
    declarationsState.value = emptyMap()
  }

  /**
   * Clear only the recorded declarations, keeping any seeded values, so a fresh composition
   * re-declares its current set without losing the daemon's seed. Used at the start of each render
   * pass.
   */
  fun clearDeclarations() {
    if (declarationsState.value.isEmpty()) return
    declarationsState.value = emptyMap()
  }
}
