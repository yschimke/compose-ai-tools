package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.io.SystemFileSystem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * The **served catalog set as data** — the operator's `catalogs.json`, not code.
 *
 * Which catalogs a preview server publishes, where each one's `design-artifacts/<system>` branch
 * lives, whether it's on the front door, and which front-page section it belongs to used to be
 * spread across three places that all had to agree: a comma-separated `--catalogs` flag baked into
 * the container entrypoint, and a pair of hardcoded id/repo sets in [ServeWeb.homeSections] that
 * decided a catalog's publisher section by *name*. Adding a catalog meant editing the image and
 * shipping a CLI release; a catalog the code had never heard of could only ever land in "Other".
 *
 * This file is the single declarative source instead. It lives **outside** the image (a mounted
 * volume / config dir), so publishing a catalog is a config edit — or a call to the admin API
 * ([ServeCatalogAdmin]), which rewrites this same file so a runtime registration survives a
 * restart.
 *
 * ```json
 * {
 *   "groups": [
 *     { "id": "design-systems", "heading": "Design Systems", "noun": "design system(s)" }
 *   ],
 *   "catalogs": [
 *     { "system": "compose-m3", "repo": "yschimke/compose-ai-tools", "group": "design-systems" },
 *     { "system": "cadence", "repo": "yschimke/cadence", "listed": false }
 *   ]
 * }
 * ```
 *
 * A [Group] is *claimed*, never assumed: a catalog only renders under its declared heading when the
 * bytes actually came from a repo the entry names ([Entry.repo] plus any [Entry.attributionRepos]).
 * That keeps the property the old hardcoded sets existed for — a third party serving a catalog
 * under the id `compose-m3` can't present it as an official design system — while making the
 * mapping data rather than a code branch.
 */
@Serializable
data class ServeCatalogsConfig(
  /** Front-page sections a catalog entry may claim by [Group.id]. */
  val groups: List<Group> = emptyList(),
  /** The catalogs to serve, in front-page order. */
  val catalogs: List<Entry> = emptyList(),
  /**
   * **Top-level sites** ([ServeSites]): hostnames that serve one of the [catalogs] as if it were
   * the only thing on the box. Config rather than code for the same reason the catalog set is — a
   * new vhost is a DNS record plus a line here, not an image rebuild.
   */
  val sites: List<Site> = emptyList(),
) {
  /** One front-page section: its stable [id], the [heading] shown, and its count [noun]. */
  @Serializable
  data class Group(val id: String, val heading: String, val noun: String = DEFAULT_NOUN)

  /** One published catalog. */
  @Serializable
  data class Entry(
    /** Catalog id — the `/<system>/` path segment and the `design-artifacts/<system>` branch. */
    val system: String,
    /** `<owner>/<repo>` the delivery branch lives in; null ⇒ the server's `--catalog-repo`. */
    val repo: String? = null,
    /** On the front-page index. False ⇒ served at `/<system>/` but off the front door. */
    val listed: Boolean = true,
    /** [Group.id] this catalog is published under; null ⇒ grouped by its source repo's owner. */
    val group: String? = null,
    /**
     * Extra repos allowed to satisfy the [group] claim, for a catalog **fetched** from somewhere
     * other than where it's authored — Android's samples are served from preview branches in a
     * fork, but the section is Android's. Never widen this to a repo you don't trust to publish
     * under the heading.
     */
    val attributionRepos: List<String> = emptyList(),
    /**
     * **Startup load order**, highest first; ties keep the order they appear in here. Default 0, so
     * a config that says nothing loads exactly as it always did — front-page order.
     *
     * The initial fetch is one sequential pass over the configured set, and a big catalog takes
     * minutes to fetch, verify and register. Which catalog that pass reaches first is therefore
     * what a rollout's first few minutes serve — and the order was purely positional, which for a
     * catalog published through the admin API means *last*, since a runtime registration is
     * appended ([CatalogLoadTracker.add]). So the catalogs a box most wants back after a restart
     * were reliably the ones it got back last (issue #4231). This decouples the two orders: the
     * list stays the front page's, this decides the queue.
     *
     * It does **not** change what is served, or where a card renders — only what gets fetched
     * first. Nothing here is a guarantee of availability either: loading stays best-effort per
     * catalog, and a prioritised catalog that fails to fetch just fails earlier.
     */
    val loadPriority: Int = 0,
  )

  /**
   * One top-level site: a [host] that serves [system] at its root. The system must be one of this
   * config's [catalogs] — a site is a second door onto a catalog already being served, never a way
   * to publish one.
   */
  @Serializable data class Site(val host: String, val system: String)

  /**
   * The declared group for [Entry.group], or null when the entry claims none / names an unknown.
   */
  fun groupFor(entry: Entry): Group? = entry.group?.let { id -> groups.firstOrNull { it.id == id } }

  /**
   * Human-readable problems with this config — unknown group ids, malformed system ids / repo
   * slugs, duplicate systems. Empty ⇒ usable. Reported rather than thrown so a server starts with
   * the entries that *are* valid instead of refusing to boot on one typo.
   */
  fun problems(): List<String> = buildList {
    groups
      .groupBy { it.id }
      .filterValues { it.size > 1 }
      .keys
      .forEach { add("duplicate group id '$it'") }
    catalogs
      .groupBy { it.system }
      .filterValues { it.size > 1 }
      .keys
      .forEach { add("duplicate catalog system '$it'") }
    for (entry in catalogs) {
      validateEntry(entry)?.let { add(it) }
      if (entry.group != null && groups.none { it.id == entry.group }) {
        add("catalog '${entry.system}' names unknown group '${entry.group}'")
      }
    }
    val served = catalogs.map { it.system }.toSet()
    val seenHosts = mutableSetOf<String>()
    for (site in sites) {
      val host = ServeSites.normalizeHost(site.host)
      when {
        host == null -> add("site host '${site.host}' is not a hostname")
        !seenHosts.add(host) -> add("duplicate site host '$host'")
      }
      if (site.system !in served) {
        add("site '${site.host}' names '${site.system}', which no catalog entry serves")
      }
    }
  }

  /** The configured [sites] as a lookup, dropping entries [problems] already reported. */
  fun siteMap(onProblem: (String) -> Unit = {}): ServeSites =
    ServeSites.of(
      sites.map { it.host to it.system },
      knownSystems = catalogs.map { it.system }.toSet(),
      onProblem = onProblem,
    )

  companion object {
    /** The count noun a section uses when its group declares none. */
    const val DEFAULT_NOUN: String = "catalog(s)"

    val EMPTY: ServeCatalogsConfig = ServeCatalogsConfig()

    private val JSON = Json {
      ignoreUnknownKeys = true
      prettyPrint = true
      prettyPrintIndent = "  "
      encodeDefaults = true
    }

    /**
     * A catalog id is a URL path segment and a branch-name suffix, so it stays in the conservative
     * slug alphabet — no `/`, no `..`, no whitespace.
     */
    private val SYSTEM_RE = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    private val REPO_RE = Regex("[A-Za-z0-9._-]{1,64}/[A-Za-z0-9._-]{1,64}")

    fun parse(text: String): ServeCatalogsConfig = JSON.decodeFromString(serializer(), text)

    fun encode(config: ServeCatalogsConfig): String =
      JSON.encodeToString(serializer(), config) + "\n"

    /**
     * A group id is referenced by catalog entries and never appears in a URL, so it only needs to
     * be a stable slug. The heading and noun ARE rendered, so they're length-capped — they reach
     * the page HTML-escaped ([ServeWeb.section]), but an operator pasting a novel into a heading
     * should get told, not silently produce an unreadable front page.
     */
    private val GROUP_ID_RE = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

    /** Why [group] is unusable as a front-page section, or null when it's well-formed. */
    fun validateGroup(group: Group): String? =
      when {
        !GROUP_ID_RE.matches(group.id) -> "invalid group id '${group.id}'"
        group.heading.isBlank() -> "group '${group.id}' needs a heading"
        group.heading.length > 120 -> "group '${group.id}' heading is too long (max 120)"
        group.noun.isBlank() -> "group '${group.id}' needs a noun"
        group.noun.length > 60 -> "group '${group.id}' noun is too long (max 60)"
        else -> null
      }

    /** Why [entry] is unusable, or null when it's well-formed. */
    fun validateEntry(entry: Entry): String? =
      when {
        !SYSTEM_RE.matches(entry.system) -> "invalid catalog system id '${entry.system}'"
        // A slug-shaped id can still be one of the server's own top-level routes, which the
        // registry refuses to name a session ([ServeSessionRegistry.register]) — so the entry
        // would parse, validate, be scheduled for loading, and then fail at registration with a
        // runtime error instead of the ordinary malformed-entry warning. Say so here, where the
        // three callers (startup filtering, `problems()`, the admin add) all read it.
        entry.system in ServeSites.RESERVED_SYSTEMS ->
          "catalog system id '${entry.system}' is one of the server's own routes"
        entry.repo != null && !REPO_RE.matches(entry.repo) ->
          "catalog '${entry.system}' has an invalid repo '${entry.repo}'"
        entry.attributionRepos.any { !REPO_RE.matches(it) } ->
          "catalog '${entry.system}' has an invalid attribution repo"
        else -> null
      }
  }
}

/**
 * The `catalogs.json` file itself — read at startup, rewritten by the admin API so a runtime
 * registration outlives the container. Deliberately a plain JSON document on a mounted path rather
 * than a database: the whole point is that it's editable, diffable, and backup-able by the operator
 * without the image knowing anything about it.
 */
class ServeCatalogsConfigFile(
  private val path: Path,
  private val fileSystem: FileSystem = SystemFileSystem,
) {
  val displayPath: String
    get() = path.toString()

  /** True when the file exists (an absent file is not an error — it reads as empty). */
  fun exists(): Boolean = fileSystem.exists(path)

  /** Parse the file; an absent file is [ServeCatalogsConfig.EMPTY]. Throws on malformed JSON. */
  fun load(): ServeCatalogsConfig {
    if (!fileSystem.exists(path)) return ServeCatalogsConfig.EMPTY
    return ServeCatalogsConfig.parse(fileSystem.read(path) { readUtf8() })
  }

  /**
   * Write [config] back. Staged through a sibling temp file + [FileSystem.atomicMove] so a crash
   * mid-write can't leave a truncated config that would drop every catalog on the next boot.
   */
  fun save(config: ServeCatalogsConfig) {
    val parent = path.parent
    parent?.let { fileSystem.createDirectories(it) }
    val tmp = if (parent != null) parent / "${path.name}.tmp" else "${path.name}.tmp".toPath()
    fileSystem.write(tmp) { writeUtf8(ServeCatalogsConfig.encode(config)) }
    fileSystem.atomicMove(tmp, path)
  }

  /**
   * [load] -> [mutate] -> [save] as one critical section, returning what was written.
   *
   * The whole read-modify-write has to be serialised, not just the write: two admin requests on
   * different threads would otherwise load the same document, apply one edit each, and atomically
   * move — last one wins, both report success, and the loser's change silently vanishes on the next
   * restart. Atomicity of the individual save doesn't help, because the lost update happens between
   * the load and the save.
   *
   * The lock lives here, on the file, rather than inside one administrator, because more than one
   * edits this document now — [ServeCatalogAdmin] for catalogs and groups, [ServeSiteAdmin] for
   * sites. A lock per administrator would serialise each against itself and neither against the
   * other, which is the same lost update with a longer stack trace. Callers share one instance per
   * path.
   */
  fun update(mutate: (ServeCatalogsConfig) -> ServeCatalogsConfig): ServeCatalogsConfig =
    synchronized(this) { mutate(load()).also { save(it) } }
}
