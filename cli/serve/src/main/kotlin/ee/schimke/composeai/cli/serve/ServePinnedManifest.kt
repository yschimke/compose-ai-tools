package ee.schimke.composeai.cli.serve

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The **id → branch-path** maps a pinned (`?at=<sha>`) request resolves against, read from the
 * catalog's own manifests *at that commit*.
 *
 * Why not reuse the loaded catalog's map: it describes the branch **tip**, and a permalink is a
 * question about a commit. The two lanes go wrong differently, and both do:
 * - a **render** id is derived from its path ([ServeCatalogStore.previewIdFor]), so a component
 *   renamed or reorganised in the catalog spec produces a *new* id and retires the old one. Every
 *   link made before that rename names an id the live catalog no longer holds, and the tip's map
 *   cannot resolve it under any path — the asset is right there at that commit, and the link 404s;
 * - a **reference** declares its id and its raster path independently, so the id survives a move.
 *   The tip's map then resolves confidently to a path that commit never had.
 *
 * So a pin reads that commit's `catalog.json` and `references/index.json` and maps ids the same way
 * the loader does. Both are small JSON files, both are immutable at a given sha, and a commit's
 * maps are memoised — a pinned page costs at most two extra fetches once, however many assets it
 * links.
 *
 * Fail-soft throughout: an unreadable or unparseable manifest yields empty maps, and the caller
 * falls back to the tip's mapping, which is exactly the behaviour that existed before this class.
 * The parses are pure and live in the companion, so the id derivation is testable without a
 * network.
 */
class ServePinnedManifest(
  /** Reads one published manifest at one commit. Supplied by [ServeCatalogStore]. */
  private val fetch: (commit: String, file: String) -> ByteArray?,
  private val maxCommits: Int = MAX_COMMITS,
) {

  /**
   * One commit's published layout.
   *
   * Each lane records **whether its manifest was read**, separately from what it contained, because
   * the two answer different questions and only one of them licenses a fallback. "The manifest says
   * this revision had no such id" is an answer — the asset was not published then, and the request
   * is a 404. "I could not read the manifest" is an absence of one, and there the tip's map is
   * better than nothing. Collapsing them would let a pin serve a file that merely happens to exist
   * at that commit under today's path, i.e. claim an asset was published in a revision whose own
   * manifest omits it.
   */
  data class Paths(
    /** Preview id → its baked render's path on the branch. */
    val renders: Map<String, String>,
    /** Design-reference id → its canonical raster's path on the branch. */
    val references: Map<String, String>,
    /**
     * Preview id → the component it belonged to, when that revision's catalog named one.
     *
     * The bare minimum needed to *page* a preview this catalog no longer has: a permalink to one
     * that has since been renamed away resolves its pixels from [renders], but the viewer also has
     * to put a name on the page, and the session's own preview list — built from the tip — has
     * nothing to say about an id it no longer contains.
     */
    val labels: Map<String, String> = emptyMap(),
    /** Preview id → the caption that revision published for it. See [CatalogEntries.captions]. */
    val captions: Map<String, String> = emptyMap(),
    /** Preview id → the baked light/dark theme recorded by that revision's image entry. */
    val themes: Map<String, String> = emptyMap(),
    /** Whether `catalog.json` was fetched and parsed at this commit. */
    val catalogRead: Boolean = false,
    /** Whether `references/index.json` was fetched and parsed at this commit. */
    val referencesRead: Boolean = false,
  ) {
    val isEmpty: Boolean
      get() = renders.isEmpty() && references.isEmpty()

    companion object {
      /** Nothing was read — every lookup falls back to the tip's map. */
      val NONE = Paths(emptyMap(), emptyMap())
    }
  }

  private val byCommit = java.util.concurrent.ConcurrentHashMap<String, Paths>()

  /**
   * [commit]'s published layout, fetched once and then remembered.
   *
   * A miss is cached too, as [Paths.NONE]: a commit whose manifests cannot be read will not become
   * readable, and remembering that is what stops a page of broken pinned images from re-fetching
   * the branch once per image. At capacity an arbitrary entry is dropped — pinned traffic is a long
   * tail of one-off links, so there is no recency order worth maintaining.
   */
  fun forCommit(commit: String): Paths {
    val pin = ServeCatalogRevision.normalize(commit) ?: return Paths.NONE
    // `computeIfAbsent` rather than get-then-put: the two panels of a comparison page (and every
    // image of a pinned grid) race into this method at once, and a check-then-fetch lets each of
    // them fetch both manifests before any of them stores a result — the memoisation this class
    // promises, spent. ConcurrentHashMap serialises the mapping function per key, so the first
    // caller fetches and the rest wait for its answer. The eviction stays outside the mapping
    // function, which must not touch the map it is being computed into.
    val paths =
      byCommit.computeIfAbsent(pin) {
        // One read of catalog.json, two maps out of it — the paths a pinned asset resolves
        // through and the labels a pinned page needs to name a preview the tip no longer lists.
        val catalog = read(pin, ServeCatalogRevision.CATALOG_FILE, ::parseCatalog)
        val references = read(pin, ServeCatalogRevision.REFERENCES_FILE, ::parseReferences)
        Paths(
          renders = catalog?.paths.orEmpty(),
          references = references.orEmpty(),
          labels = catalog?.labels.orEmpty(),
          captions = catalog?.captions.orEmpty(),
          themes = catalog?.themes.orEmpty(),
          catalogRead = catalog != null,
          referencesRead = references != null,
        )
      }
    if (byCommit.size > maxCommits) {
      synchronized(byCommit) { byCommit.keys.firstOrNull { it != pin }?.let(byCommit::remove) }
    }
    return paths
  }

  /** Null when the manifest could not be read at all — distinct from "read, and it lists none". */
  private fun <T> read(commit: String, file: String, parse: (String) -> T?): T? = runCatching {
    fetch(commit, file)?.toString(Charsets.UTF_8)?.let(parse)
  }
    .getOrNull()

  companion object {

    /**
     * How many commits' layouts one catalog host remembers. Small on purpose: this is a
     * de-duplicator across the assets of a page (and its reload), not an archive of the branch.
     */
    private const val MAX_COMMITS = 4

    private val JSON = Json { ignoreUnknownKeys = true }

    /**
     * The same eligibility rule [ServeCatalogStore] plans an image by: inside `images/`, a PNG, no
     * traversal. An entry that fails it is one the live catalog never served, so a pinned request
     * must not resolve to it either.
     *
     * Deliberately *not* mirrored: the loader's `maxImages` ceiling. That is a property of the
     * server reading the catalog, not of the revision — a box with a different cap would otherwise
     * disagree with itself about what a commit published.
     */
    private fun isServable(path: String): Boolean =
      path.startsWith("${ServeCatalogStore.IMAGES_DIR}/") &&
        path.endsWith(".png") &&
        ".." !in path.split("/")

    /**
     * `catalog.json` → preview id → image path, keyed exactly as the loader keys the live catalog
     * ([ServeCatalogStore.previewIdFor]), so a pinned id and a served id are the same string by
     * construction rather than by coincidence.
     *
     * Tolerant by design: this reads a file published by an older CLI than the one reading it, so a
     * malformed component or image is skipped rather than failing the whole map.
     */
    /** What one revision's `catalog.json` says, read in a single pass. */
    data class CatalogEntries(
      /** Preview id → image path. */
      val paths: Map<String, String>,
      /** Preview id → the component that declared it, where the catalog names one. */
      val labels: Map<String, String>,
      /** Preview id → the caption that component authored, where the catalog carries one. */
      val captions: Map<String, String>,
      /** Preview id → the explicit baked theme on the winning image entry. */
      val themes: Map<String, String>,
    )

    fun parseCatalog(json: String): CatalogEntries? {
      val components =
        runCatching { JSON.parseToJsonElement(json).jsonObject["components"]?.jsonArray }
          .getOrNull() ?: return null
      val paths = LinkedHashMap<String, String>()
      val labels = LinkedHashMap<String, String>()
      val captions = LinkedHashMap<String, String>()
      val themes = LinkedHashMap<String, String>()
      for (component in components) {
        val obj = runCatching { component.jsonObject }.getOrNull() ?: continue
        val componentId = runCatching {
          obj["componentId"]?.jsonPrimitive?.content
        }
          .getOrNull()
          ?.takeIf { it.isNotBlank() }
        val caption = runCatching {
          obj["caption"]?.jsonPrimitive?.content
        }
          .getOrNull()
          ?.takeIf { it.isNotBlank() }
        val images = runCatching { obj["images"]?.jsonArray }.getOrNull() ?: continue
        for (image in images) {
          val path =
            runCatching { image.jsonObject["path"]?.jsonPrimitive?.content }
              .getOrNull()
              ?.takeIf(::isServable) ?: continue
          // LAST declaration wins, because that is what the live loader does
          // (`bakedPathById[id] = path`). Two paths can flatten to one route id, and a pin that
          // resolved such a collision the other way would serve different pixels than the same
          // catalog served while it was current — the one thing a revision must never do.
          //
          // Which is also why the eligibility filter above has to run FIRST. The loader plans only
          // the images it would serve, so an entry it rejects never reaches its map; accepting one
          // here and then applying last-wins would let a rejected entry overwrite the served one
          // under a shared id — a pin answering with bytes that revision never exposed, arrived at
          // by faithfully copying half of the loader's rule.
          val id = ServeCatalogStore.previewIdFor(path)
          paths[id] = path
          // The label follows the winning path, including when the winner has no component name —
          // otherwise a collision resolved in favour of an unnamed entry leaves the *loser's*
          // component behind, and the page attributes one component's render to another. Whichever
          // declaration owns the pixels owns the name, even when that name is nothing.
          if (componentId != null) labels[id] = componentId else labels.remove(id)
          // The caption follows the winning path for the same reason the label does: it describes
          // the component that owns these pixels, so a collision resolved towards an uncaptioned
          // entry must not leave the loser's sentence behind explaining someone else's render.
          if (caption != null) captions[id] = caption else captions.remove(id)
          val theme = runCatching {
            image.jsonObject["theme"]?.jsonPrimitive?.content
          }
            .getOrNull()
            ?.takeIf { it == "light" || it == "dark" }
          if (theme != null) themes[id] = theme else themes.remove(id)
        }
      }
      return CatalogEntries(paths, labels, captions, themes)
    }

    /** `references/index.json` → reference id → the canonical raster's path on the branch. */
    fun parseReferences(json: String): Map<String, String>? {
      val references =
        runCatching { JSON.parseToJsonElement(json).jsonObject["references"]?.jsonArray }
          .getOrNull() ?: return null
      val paths = LinkedHashMap<String, String>()
      for (reference in references) {
        val obj = runCatching { reference.jsonObject }.getOrNull() ?: continue
        val id =
          runCatching { obj["id"]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: continue
        val path =
          runCatching { obj["raster"]?.jsonObject?.get("path")?.jsonPrimitive?.content }
            .getOrNull()
            ?.takeIf { it.isNotBlank() } ?: continue
        // FIRST declaration wins here, and the asymmetry with the renders above is deliberate: the
        // reference importer discards a duplicate id (`seen.add(reference.id)`), so first-wins is
        // what the served catalog does. Each lane mirrors its own loader rather than both being
        // made consistent with each other.
        paths.putIfAbsent(id, path)
      }
      return paths
    }
  }
}
