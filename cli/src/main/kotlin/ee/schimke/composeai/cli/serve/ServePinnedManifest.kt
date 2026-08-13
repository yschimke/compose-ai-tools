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

  /** One commit's published layout. */
  data class Paths(
    /** Preview id → its baked render's path on the branch. */
    val renders: Map<String, String>,
    /** Design-reference id → its canonical raster's path on the branch. */
    val references: Map<String, String>,
  ) {
    val isEmpty: Boolean
      get() = renders.isEmpty() && references.isEmpty()

    companion object {
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
    byCommit[pin]?.let {
      return it
    }
    val paths =
      Paths(
        renders = read(pin, ServeCatalogRevision.CATALOG_FILE, ::parseCatalog),
        references = read(pin, ServeCatalogRevision.REFERENCES_FILE, ::parseReferences),
      )
    synchronized(byCommit) {
      if (byCommit.size >= maxCommits) byCommit.keys.firstOrNull()?.let(byCommit::remove)
      byCommit[pin] = paths
    }
    return paths
  }

  private fun read(
    commit: String,
    file: String,
    parse: (String) -> Map<String, String>,
  ): Map<String, String> =
    runCatching { fetch(commit, file)?.toString(Charsets.UTF_8)?.let(parse) }.getOrNull().orEmpty()

  companion object {

    /**
     * How many commits' layouts one catalog host remembers. Small on purpose: this is a
     * de-duplicator across the assets of a page (and its reload), not an archive of the branch.
     */
    private const val MAX_COMMITS = 4

    private val JSON = Json { ignoreUnknownKeys = true }

    /**
     * `catalog.json` → preview id → image path, keyed exactly as the loader keys the live catalog
     * ([ServeCatalogStore.previewIdFor]), so a pinned id and a served id are the same string by
     * construction rather than by coincidence.
     *
     * Tolerant by design: this reads a file published by an older CLI than the one reading it, so a
     * malformed component or image is skipped rather than failing the whole map.
     */
    fun parseCatalog(json: String): Map<String, String> {
      val components =
        runCatching { JSON.parseToJsonElement(json).jsonObject["components"]?.jsonArray }
          .getOrNull() ?: return emptyMap()
      val paths = LinkedHashMap<String, String>()
      for (component in components) {
        val images =
          runCatching { component.jsonObject["images"]?.jsonArray }.getOrNull() ?: continue
        for (image in images) {
          val path =
            runCatching { image.jsonObject["path"]?.jsonPrimitive?.content }
              .getOrNull()
              ?.takeIf { it.isNotBlank() } ?: continue
          paths.putIfAbsent(ServeCatalogStore.previewIdFor(path), path)
        }
      }
      return paths
    }

    /** `references/index.json` → reference id → the canonical raster's path on the branch. */
    fun parseReferences(json: String): Map<String, String> {
      val references =
        runCatching { JSON.parseToJsonElement(json).jsonObject["references"]?.jsonArray }
          .getOrNull() ?: return emptyMap()
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
        paths.putIfAbsent(id, path)
      }
      return paths
    }
  }
}
