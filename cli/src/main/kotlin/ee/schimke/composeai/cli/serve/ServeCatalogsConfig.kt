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
  )

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
  }

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

    /** Why [entry] is unusable, or null when it's well-formed. */
    fun validateEntry(entry: Entry): String? =
      when {
        !SYSTEM_RE.matches(entry.system) -> "invalid catalog system id '${entry.system}'"
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
}
