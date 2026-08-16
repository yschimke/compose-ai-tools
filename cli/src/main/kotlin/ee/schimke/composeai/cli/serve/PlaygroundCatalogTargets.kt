package ee.schimke.composeai.cli.serve

import java.util.concurrent.ConcurrentHashMap

/**
 * One catalog the playground can compile against, as the runtime selector offers it.
 *
 * [backend] is the catalog bundle's own `manifest.backend`, and it is what makes the selector more
 * than a convenience: picking a catalog *is* picking the renderer and the dependency set. A
 * `desktop` catalog compiles and renders on the Skiko daemon; an `android` one on Robolectric,
 * which also unlocks the Remote Compose capture. So [modes] is derived, never asked for — see
 * [PlaygroundCatalogTargets.modesForBackend].
 */
data class PlaygroundCatalogTarget(
  /** The `--catalogs` system id, e.g. `compose-m3`. */
  val system: String,
  /** `desktop` or `android`, straight from the bundle manifest. */
  val backend: String,
  /** The modes this catalog can serve **on this host** — already intersected with what is wired. */
  val modes: List<PlaygroundMode>,
  /** True once this catalog's compile classpath has been resolved and memoized. */
  val resolved: Boolean,
  /** Request id; equals [system] for a legacy/primary bundle and is module-qualified otherwise. */
  val id: String = system,
  /** Owning Gradle module for a repository-wide catalog target. */
  val module: String = "",
)

/** One independently compilable bundle currently published by a served catalog. */
data class PlaygroundCatalogAvailable(
  val id: String,
  val system: String,
  val module: String,
  val backend: String,
)

/**
 * The playground's **runtime catalog selector** (issue #3215 follow-up): the set of served catalogs
 * a snippet may be compiled against, chosen per request instead of pinned at startup.
 *
 * `--playground-bundle <system>` pins exactly one catalog per mode for the life of the process, so
 * trying a snippet against a different design system means an operator edit and a restart. A serve
 * host already fetches, trust-verifies and unpacks *every* catalog in `--catalogs`, and each of
 * those bundles carries the two things a compile needs — a `manifest.classpath` to resolve and a
 * `manifest.backend` to pick the renderer. This type exposes that set as a per-request choice.
 *
 * **Nothing is pinned by default.** `--playground` alone enables the lane with an empty pin set and
 * a selector over the served catalogs; the `--playground-bundle` flags still work and become the
 * *default* entry the selector preselects, so an existing deployment is unchanged.
 *
 * Two properties are load-bearing:
 *
 * **Resolution is lazy and memoized per catalog.** A catalog's classpath costs a bundle unpack plus
 * a full Maven coordinate resolve; doing that for 20 catalogs at startup would be minutes of work
 * for a lane most visitors never touch. Each catalog resolves on the first compile that names it,
 * through the same [PlaygroundClasspathSupplier] the pinned flags use.
 *
 * **The number of *resolved* catalogs is capped** ([limit]). Each one holds an unpacked classes dir
 * and a resolved classpath for the life of the process — deliberately, because those jars are open
 * in live snippet JVMs and cannot be evicted underneath them (the same reason
 * [PlaygroundClasspathSupplier] does not follow catalog auto-refresh). So the budget is spent, not
 * recycled: past the cap a request naming an unresolved catalog is refused with a message saying
 * so, rather than silently growing the host's disk and heap one curious visitor at a time.
 */
class PlaygroundCatalogTargets(
  /**
   * The catalogs currently offerable, as `system to backend`. Read fresh on every call: catalogs
   * are fetched in the background *after* the playground lane is wired, so a list captured once
   * would be empty forever on a freshly started host.
   */
  private val available: () -> List<PlaygroundCatalogAvailable>,
  /**
   * Which modes this host can actually serve for a bundle backend — the backend's natural modes
   * intersected with the render backends that came up (no Robolectric sidecar ⇒ no Android modes,
   * no `/d/` store ⇒ no Remote Compose). Empty ⇒ the catalog is not offered at all.
   */
  private val modesForBackend: (String) -> List<PlaygroundMode>,
  /** Builds the lazy classpath supplier for one system. Called at most once per system. */
  private val newSupplier: (String) -> PlaygroundClasspathSupplier,
  /** How many catalogs may hold a resolved classpath at once. */
  private val limit: Int = DEFAULT_LIMIT,
  private val onLog: (String) -> Unit = {},
) {

  private val suppliers = ConcurrentHashMap<String, PlaygroundClasspathSupplier>()

  /** Everything the selector should offer, sorted by system id so the list is stable per render. */
  fun targets(): List<PlaygroundCatalogTarget> =
    available()
      .mapNotNull { available ->
        val modes = modesForBackend(available.backend)
        if (modes.isEmpty()) null
        else
          PlaygroundCatalogTarget(
            system = available.system,
            backend = available.backend,
            modes = modes,
            resolved = suppliers[available.id]?.isResolved == true,
            id = available.id,
            module = available.module,
          )
      }
      .sortedWith(
        compareBy<PlaygroundCatalogTarget>({ it.system }, { it.id != it.system }, { it.module })
      )

  /** How many catalogs currently hold a resolved classpath — for `/status.json`. */
  fun resolvedCount(): Int = suppliers.values.count { it.isResolved }

  /**
   * The compile classpath for [system] in [mode], or null when this host will not serve that pair:
   * an unknown/unloaded catalog, a mode the catalog's backend does not support, a bundle that will
   * not resolve, or the [limit] already spent on other catalogs. Every one of those is reported to
   * the caller as "not available" rather than distinguished on the wire, and logged here with the
   * actual reason.
   */
  fun classpath(id: String, mode: PlaygroundMode): PlaygroundCompileService.Classpath? {
    val target = targets().firstOrNull { it.id == id }
    if (target == null) {
      onLog("catalog target '$id' is not selectable on this host")
      return null
    }
    if (mode !in target.modes) {
      onLog(
        "catalog target '$id' is a ${target.backend} bundle and cannot serve mode ${mode.name} here " +
          "(offers ${target.modes.joinToString { it.name }})"
      )
      return null
    }
    // Fast path: an already-resolved catalog is a map read plus a memo read, so concurrent compiles
    // against the common catalog never touch the lock below.
    suppliers[id]
      ?.takeIf { it.isResolved }
      ?.let {
        return it.classpath()
      }
    // First resolve of a catalog is serialized: it unpacks a bundle and resolves a full Maven
    // classpath, and serializing is what makes the cap exact rather than a racy over-shoot.
    synchronized(this) {
      suppliers[id]
        ?.takeIf { it.isResolved }
        ?.let {
          return it.classpath()
        }
      // Past the check above, this system's supplier is absent or UNRESOLVED either way — so it
      // holds none of the budget, and a full budget refuses it either way. Deliberately not keyed
      // on
      // "have we seen this system before": a supplier whose first resolve failed (a transient Maven
      // miss, a bundle that hadn't landed) is still in the map, and letting its retry through
      // because it exists would resolve an N+1st catalog straight past the cap.
      if (resolvedCount() >= limit) {
        onLog(
          "playground catalog budget spent: $limit catalog(s) already hold a resolved classpath " +
            "and they cannot be evicted while snippet JVMs hold their jars open, so '$id' is " +
            "refused. Raise --playground-catalog-limit, or restart to pick a different set."
        )
        return null
      }
      val supplier = suppliers[id] ?: newSupplier(id).also { suppliers[id] = it }
      return supplier.classpath()
    }
  }

  companion object {
    /**
     * Catalogs that may hold a resolved classpath at once. Six covers "try my snippet against a few
     * design systems" — the reason the selector exists — while bounding the unpacked-bundle
     * footprint a public host can be walked into by a visitor clicking through every entry.
     */
    const val DEFAULT_LIMIT = 6

    /**
     * A bundle backend's natural playground modes, before intersecting with what this host wired.
     * Mirrors `ServeBundleDaemon.materialize`: `desktop` renders on Skiko, `android` on Robolectric
     * — and only the Robolectric path can capture a Remote Compose document.
     */
    fun naturalModes(backend: String): List<PlaygroundMode> =
      when (backend) {
        "desktop" -> listOf(PlaygroundMode.CMP)
        "android" -> listOf(PlaygroundMode.ANDROID, PlaygroundMode.REMOTE_COMPOSE)
        else -> emptyList()
      }
  }
}
