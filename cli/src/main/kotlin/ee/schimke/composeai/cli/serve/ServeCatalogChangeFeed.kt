package ee.schimke.composeai.cli.serve

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Demand-activated catalog change feeds.
 *
 * A request renews a per-feed interest lease and returns the last completed RSS document. While the
 * lease is live, a daemon worker periodically fetches the catalog's delivery branch and rebuilds
 * the document when its head moves. An expired lease stops all fetching; it does not delete the
 * cached Git objects or XML, so a later reader resumes cheaply.
 *
 * The worker keeps a shallow **bare Git repository** rather than fetching every published PNG.
 * `catalog.json` supplies preview identity/order, `references/index.json` supplies Figma metadata
 * and published match scores, and Git's tree supplies exact image blob ids. That is enough to
 * distinguish add/delete/metadata/pixel changes without GitHub API quota or hundreds of raw-image
 * requests.
 */
class ServeCatalogChangeFeed
internal constructor(
  private val entries: () -> List<CatalogLoadTracker.Config>,
  private val cacheRoot: File,
  private val idleTimeoutMillis: Long,
  private val pollIntervalMillis: Long,
  private val now: () -> Long = System::currentTimeMillis,
  private val source: CatalogFeedSource = GitCatalogFeedSource(cacheRoot),
  private val onLog: (String) -> Unit = { System.err.println(it) },
  startScheduler: Boolean = true,
) : AutoCloseable {

  data class Result(val xml: String, val building: Boolean)

  private data class Key(val system: String, val baseUrl: String, val linkQuery: String)

  private class State {
    @Volatile var activeUntil: Long = 0
    @Volatile var xml: String? = null
    @Volatile var head: String? = null
    val building = AtomicBoolean(false)
  }

  private val states = ConcurrentHashMap<Key, State>()
  /** Canonical-path and top-level-site feeds share one bare repo; never fetch it concurrently. */
  private val sourceLocks = ConcurrentHashMap<String, Any>()
  private val exec: ScheduledExecutorService =
    Executors.newScheduledThreadPool(2) { task ->
      Thread(task, "serve-catalog-feed").apply { isDaemon = true }
    }

  init {
    require(idleTimeoutMillis > 0) { "feed idle timeout must be positive" }
    require(pollIntervalMillis > 0) { "feed poll interval must be positive" }
    cacheRoot.mkdirs()
    if (startScheduler) {
      exec.scheduleWithFixedDelay(
        {
          runCatching(::tick).onFailure { onLog("serve: catalog feed tick failed: ${it.message}") }
        },
        pollIntervalMillis,
        pollIntervalMillis,
        TimeUnit.MILLISECONDS,
      )
    }
  }

  /** Renew interest in [system]'s feed and return the newest completed document immediately. */
  fun request(system: String, baseUrl: String, linkQuery: String = ""): Result? {
    val config = entries().firstOrNull { it.system == system } ?: return null
    val cleanBase = baseUrl.trimEnd('/')
    val cleanQuery = linkQuery.removePrefix("?")
    val key = Key(system, cleanBase, cleanQuery)
    val at = now()
    val state =
      synchronized(states) {
        if (!states.containsKey(key) && !makeRoomFor(key, at)) {
          null
        } else {
          val selected =
            states.computeIfAbsent(key) {
              State().apply {
                loadCached(key)?.let { (cachedHead, cachedXml) ->
                  head = cachedHead
                  xml = cachedXml
                }
              }
            }
          val wasActive = selected.activeUntil > at
          selected.activeUntil = at + idleTimeoutMillis
          // Selection, renewal and cold activation are one atomic state-map operation. Otherwise a
          // full map can evict an expired entry after this request selects it but before its lease
          // is renewed, leaving a worker attached to a State that tick() can no longer see.
          if (!wasActive) enqueue(key, config, selected)
          selected
        }
      }
        ?: run {
          onLog("serve: catalog feed state limit reached; ignoring new address for $system")
          return Result(
            CatalogFeedXml.empty(config.system, cleanBase, cleanQuery),
            building = false,
          )
        }
    return Result(
      xml = state.xml ?: CatalogFeedXml.empty(config.system, cleanBase, cleanQuery),
      building = state.building.get(),
    )
  }

  /**
   * Bound request-derived origins and their durable XML documents. Expired entries normally stay
   * warm, but become least-recently-used eviction candidates once the small address cap is full. If
   * every entry is active, replace the oldest idle worker rather than returning a permanent empty
   * feed: a burst of forged Host values must not lock the real feed origin out for a week.
   */
  private fun makeRoomFor(key: Key, at: Long): Boolean {
    if (states.size < MAX_FEED_ADDRESSES) return true
    val expired =
      states.entries
        .filter { it.value.activeUntil <= at && !it.value.building.get() }
        .sortedBy { it.value.activeUntil }
    for ((oldKey, oldState) in expired) {
      if (states.remove(oldKey, oldState)) cacheDir(oldKey).deleteRecursively()
      if (states.size < MAX_FEED_ADDRESSES) return true
    }
    val oldestActive =
      states.entries.filter { !it.value.building.get() }.minByOrNull { it.value.activeUntil }
    if (oldestActive != null && states.remove(oldestActive.key, oldestActive.value)) {
      cacheDir(oldestActive.key).deleteRecursively()
    }
    return states.size < MAX_FEED_ADDRESSES || states.containsKey(key)
  }

  /** One activity pass. Package-visible so lease expiry is deterministic in tests. */
  internal fun tick() {
    val configs = entries().associateBy { it.system }
    val at = now()
    for ((key, state) in states) {
      if (state.activeUntil <= at) continue
      val config = configs[key.system] ?: continue
      enqueue(key, config, state)
    }
  }

  internal fun isActive(system: String, baseUrl: String, linkQuery: String = ""): Boolean =
    states[Key(system, baseUrl.trimEnd('/'), linkQuery.removePrefix("?"))]?.activeUntil?.let {
      it > now()
    } == true

  internal fun stateCount(): Int = states.size

  private fun enqueue(key: Key, config: CatalogLoadTracker.Config, state: State) {
    if (!state.building.compareAndSet(false, true)) return
    exec.execute {
      try {
        val history =
          synchronized(sourceLocks.computeIfAbsent(config.system) { Any() }) {
            source.read(config, state.head)
          } ?: return@execute
        if (history.revisions.isEmpty()) return@execute
        val newHead = history.revisions.first().commit
        if (newHead == state.head && state.xml != null) return@execute
        val xml = CatalogFeedXml.render(config.system, key.baseUrl, history, key.linkQuery)
        persist(key, newHead, xml)
        state.xml = xml
        state.head = newHead
        onLog("serve: catalog feed ${key.system} generated at ${newHead.take(8)}")
      } catch (t: Exception) {
        onLog("serve: catalog feed ${key.system} refresh failed: ${t.message}")
      } finally {
        state.building.set(false)
      }
    }
  }

  private fun cacheDir(key: Key): File =
    File(
      File(cacheRoot, safeName(key.system)),
      "feeds/${digest(key.baseUrl + "\n" + key.linkQuery).take(16)}",
    )

  private fun loadCached(key: Key): Pair<String, String>? {
    val dir = cacheDir(key)
    val version = File(dir, "version").takeIf(File::isFile)?.readText()?.trim()
    val head = File(dir, "head").takeIf(File::isFile)?.readText()?.trim().orEmpty()
    val xml = File(dir, "feed.xml").takeIf(File::isFile)?.readText().orEmpty()
    return if (version == CACHE_VERSION && head.matches(COMMIT) && xml.isNotBlank()) {
      head to xml
    } else {
      null
    }
  }

  private fun persist(key: Key, head: String, xml: String) {
    val dir = cacheDir(key)
    if (!dir.mkdirs() && !dir.isDirectory) return
    val version = File(dir, "version")
    // Invalidate the old pair first. If the process stops between either content write, the next
    // process rebuilds instead of pairing a new document with an old head (or vice versa).
    if (version.exists() && !version.delete()) return
    atomicWrite(File(dir, "feed.xml"), xml)
    atomicWrite(File(dir, "head"), "$head\n")
    atomicWrite(version, "$CACHE_VERSION\n")
  }

  private fun atomicWrite(target: File, text: String) {
    val tmp = File(target.parentFile, ".${target.name}.${Thread.currentThread().id}.tmp")
    tmp.writeText(text)
    runCatching {
      Files.move(
        tmp.toPath(),
        target.toPath(),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING,
      )
    }
      .getOrElse { Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
  }

  override fun close() {
    exec.shutdownNow()
  }

  companion object {
    private val COMMIT = Regex("[0-9a-f]{40}")
    private const val CACHE_VERSION = "2"
    internal const val MAX_FEED_ADDRESSES = 64

    internal fun safeName(value: String): String =
      value.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').ifBlank { "catalog" }

    private fun digest(value: String): String =
      MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {
        "%02x".format(it)
      }
  }
}

/** A fully materialised, newest-first slice of one delivery branch. */
internal data class CatalogFeedHistory(
  val title: String?,
  val revisions: List<CatalogFeedRevision>,
  val batches: List<CatalogFeedBatch>,
  val repo: String? = null,
)

internal data class CatalogFeedRevision(
  val commit: String,
  val date: String,
  val subject: String,
  val sourceSha: String?,
)

internal enum class CatalogPreviewChangeKind {
  ADDED,
  DELETED,
  CHANGED,
  VISUAL_AND_METADATA,
  METADATA,
}

internal data class CatalogPreviewChange(
  val kind: CatalogPreviewChangeKind,
  val id: String,
  val label: String,
  val beforeBlob: String? = null,
  val afterBlob: String? = null,
  val order: Int = Int.MAX_VALUE,
)

internal data class CatalogReferenceChange(
  val id: String,
  val label: String,
  val previewId: String,
  val specChanged: Boolean,
  val beforeMatch: Double?,
  val afterMatch: Double?,
  val order: Int = Int.MAX_VALUE,
  val beforePresent: Boolean = true,
  val afterPresent: Boolean = true,
)

internal data class CatalogFeedBatch(
  val before: CatalogFeedRevision,
  val after: CatalogFeedRevision,
  val previews: List<CatalogPreviewChange>,
  val references: List<CatalogReferenceChange>,
)

/** Source seam: real serving uses Git; tests can provide an already-built history. */
internal fun interface CatalogFeedSource {
  /** Return null when [knownHead] is still current, before materialising historical snapshots. */
  fun read(config: CatalogLoadTracker.Config, knownHead: String?): CatalogFeedHistory?
}

/** Shallow bare-Git implementation of [CatalogFeedSource]. */
internal class GitCatalogFeedSource(
  private val root: File,
  private val git: (File, List<String>) -> CatalogFeedGitResult = ::runCatalogFeedGit,
) : CatalogFeedSource {

  override fun read(config: CatalogLoadTracker.Config, knownHead: String?): CatalogFeedHistory? {
    require(REPO.matches(config.repo)) { "invalid catalog repo" }
    require(BRANCH.matches(config.branch) && ".." !in config.branch.split('/')) {
      "invalid catalog branch"
    }
    val dir = File(root, ServeCatalogChangeFeed.safeName(config.system))
    if (!File(dir, "HEAD").isFile) {
      dir.mkdirs()
      check(git(dir, listOf("init", "--bare", ".")).ok) { "could not initialise feed cache" }
    }
    val remote = "https://github.com/${config.repo}.git"
    val ref = "refs/heads/catalog-feed"
    val setRemote = git(dir, listOf("remote", "set-url", "origin", remote))
    if (!setRemote.ok) {
      check(git(dir, listOf("remote", "add", "origin", remote)).ok) {
        "could not configure catalog feed remote"
      }
    }
    check(git(dir, listOf("config", "remote.origin.promisor", "true")).ok) {
      "could not configure partial catalog fetch"
    }
    check(git(dir, listOf("config", "remote.origin.partialclonefilter", "blob:none")).ok) {
      "could not configure partial catalog fetch"
    }
    val fetch =
      git(
        dir,
        listOf(
          "fetch",
          "--quiet",
          "--force",
          "--depth=$HISTORY_DEPTH",
          "--filter=blob:none",
          "origin",
          "+refs/heads/${config.branch}:$ref",
        ),
      )
    check(fetch.ok) { fetch.stderr.ifBlank { "could not fetch catalog history" } }
    val fetchedHead = git(dir, listOf("rev-parse", "--verify", ref))
    check(fetchedHead.ok) { "could not resolve fetched catalog head" }
    if (knownHead != null && fetchedHead.stdout.trim() == knownHead) return null
    val log =
      git(
        dir,
        listOf(
          "log",
          "--first-parent",
          "--max-count=$HISTORY_DEPTH",
          "--format=%H%x1f%aI%x1f%s",
          ref,
        ),
      )
    check(log.ok) { "could not read catalog history" }
    val revisions = log.stdout.lineSequence().mapNotNull(::parseRevision).toList()
    if (revisions.isEmpty()) return CatalogFeedHistory(null, emptyList(), emptyList(), config.repo)

    val snapshots = revisions.associate { it.commit to snapshot(dir, it.commit) }
    val batches =
      revisions.zipWithNext().map { (after, before) ->
        CatalogFeedDiff.between(
          before,
          snapshots.getValue(before.commit),
          after,
          snapshots.getValue(after.commit),
        )
      }
    return CatalogFeedHistory(
      title = snapshots.getValue(revisions.first().commit).title,
      revisions = revisions,
      batches = batches,
      repo = config.repo,
    )
  }

  private fun snapshot(dir: File, commit: String): CatalogSnapshot {
    val catalog = optionalBlob(dir, commit, "catalog.json")
    val references = optionalBlob(dir, commit, "references/index.json")
    val tree = git(dir, listOf("ls-tree", "-r", commit, "--", "images", "references"))
    check(tree.ok) { tree.stderr.ifBlank { "could not read catalog tree at $commit" } }
    val blobs = parseTree(tree.stdout)
    return CatalogSnapshot.parse(catalog, references, blobs)
  }

  private fun optionalBlob(dir: File, commit: String, path: String): String? {
    val result = git(dir, listOf("show", "$commit:$path"))
    if (result.ok) return result.stdout.takeIf { it.isNotBlank() }
    // An older catalog may legitimately predate either manifest. Every other failure includes
    // partial-clone network errors: treating those as an absent file would persist false deletions
    // and then suppress the retry via knownHead.
    val missing =
      result.stderr.contains("does not exist in") ||
        result.stderr.contains("exists on disk, but not in") ||
        result.stderr.contains("Path '$path' does not exist")
    check(missing) { result.stderr.ifBlank { "could not read $path at $commit" } }
    return null
  }

  companion object {
    const val HISTORY_DEPTH = 21
    private val REPO = Regex("[A-Za-z0-9][A-Za-z0-9._-]*/[A-Za-z0-9][A-Za-z0-9._-]*")
    private val BRANCH = Regex("[A-Za-z0-9][A-Za-z0-9._/-]{0,240}")
    private val SHA = Regex("[0-9a-f]{40}")
    private val SOURCE_SHA = Regex("(?:from |catalog \\([^)]*?,\\s*)([0-9a-f]{7,40})(?:\\)|$)")

    internal fun parseRevision(line: String): CatalogFeedRevision? {
      val fields = line.split('\u001f', limit = 3)
      if (fields.size != 3 || !SHA.matches(fields[0])) return null
      return CatalogFeedRevision(
        commit = fields[0],
        date = fields[1],
        subject = fields[2],
        sourceSha = SOURCE_SHA.find(fields[2])?.groupValues?.get(1),
      )
    }

    internal fun parseTree(text: String): Map<String, String> = buildMap {
      for (line in text.lineSequence()) {
        val tab = line.indexOf('\t')
        if (tab < 0) continue
        val header = line.substring(0, tab).split(' ')
        val blob = header.getOrNull(2) ?: continue
        if (SHA.matches(blob)) put(line.substring(tab + 1), blob)
      }
    }
  }
}

internal data class CatalogFeedGitResult(
  val exitCode: Int,
  val stdout: String,
  val stderr: String,
) {
  val ok: Boolean
    get() = exitCode == 0
}

internal fun runCatalogFeedGit(dir: File, args: List<String>): CatalogFeedGitResult {
  val process = ProcessBuilder(listOf("git") + args).directory(dir).start()
  val stdout = StringBuilder()
  val stderr = StringBuilder()
  val outThread = Thread {
    process.inputStream.bufferedReader().use { stdout.append(it.readText()) }
  }
    .apply { isDaemon = true }
  val errThread = Thread {
    process.errorStream.bufferedReader().use { stderr.append(it.readText()) }
  }
    .apply { isDaemon = true }
  outThread.start()
  errThread.start()
  val completed =
    try {
      process.waitFor(60, TimeUnit.SECONDS)
    } catch (_: InterruptedException) {
      terminateCatalogFeedGit(process, outThread, errThread)
      Thread.currentThread().interrupt()
      return CatalogFeedGitResult(130, stdout.toString(), "git interrupted")
    }
  if (!completed) {
    terminateCatalogFeedGit(process, outThread, errThread)
    return CatalogFeedGitResult(124, stdout.toString(), "git timed out")
  }
  outThread.join()
  errThread.join()
  return CatalogFeedGitResult(process.exitValue(), stdout.toString(), stderr.toString())
}

private fun terminateCatalogFeedGit(process: Process, outThread: Thread, errThread: Thread) {
  process.destroyForcibly()
  runCatching { process.inputStream.close() }
  runCatching { process.errorStream.close() }
  runCatching { process.outputStream.close() }
  runCatching { outThread.join(1_000) }
  runCatching { errThread.join(1_000) }
}

internal data class CatalogSnapshot(
  val title: String?,
  val previews: LinkedHashMap<String, SnapshotPreview>,
  val references: LinkedHashMap<String, SnapshotReference>,
) {
  companion object {
    private val JSON = Json { ignoreUnknownKeys = true }

    fun parse(
      catalogJson: String?,
      referencesJson: String?,
      blobs: Map<String, String>,
    ): CatalogSnapshot {
      val catalog = catalogJson?.let {
        runCatching { JSON.parseToJsonElement(it).jsonObject }.getOrNull()
      }
      val title = catalog.string("title")
      val previews = linkedMapOf<String, SnapshotPreview>()
      val components = catalog?.get("components") as? JsonArray ?: JsonArray(emptyList())
      for (componentElement in components) {
        val component = componentElement as? JsonObject ?: continue
        val componentId = component.string("componentId")
        val images = component["images"] as? JsonArray ?: continue
        for (imageElement in images) {
          val image = imageElement as? JsonObject ?: continue
          val path = image.string("path") ?: continue
          if (!path.startsWith("images/") || !path.endsWith(".png") || ".." in path.split('/'))
            continue
          val id = ServeCatalogStore.previewIdFor(path)
          previews[id] =
            SnapshotPreview(
              id = id,
              label = componentId ?: id,
              path = path,
              blob = blobs[path],
              metadata =
                listOf(
                    component.string("section"),
                    component.string("group"),
                    image.string("state"),
                    image.string("theme"),
                    image["props"]?.toString(),
                  )
                  .joinToString("\u001f") { it.orEmpty() },
              order = previews.size,
            )
        }
      }

      val references = linkedMapOf<String, SnapshotReference>()
      val referenceRoot = referencesJson?.let {
        runCatching { JSON.parseToJsonElement(it).jsonObject }.getOrNull()
      }
      val rows = referenceRoot?.get("references") as? JsonArray ?: JsonArray(emptyList())
      for (element in rows) {
        val obj = element as? JsonObject ?: continue
        val id = obj.string("id") ?: continue
        val previewId = obj.string("previewId") ?: continue
        val raster = obj["raster"] as? JsonObject
        val source = obj["source"] as? JsonObject
        val match = obj["match"] as? JsonObject
        val rasterPath = raster.string("path")
        references.putIfAbsent(
          id,
          SnapshotReference(
            id = id,
            label = obj.string("label") ?: id,
            previewId = previewId,
            specFingerprint =
              listOf(
                  source?.toString().orEmpty(),
                  rasterPath.orEmpty(),
                  raster.string("sha256") ?: rasterPath?.let(blobs::get).orEmpty(),
                )
                .joinToString("\u001f"),
            match = match.number("percent"),
            order = references.size,
          ),
        )
      }
      return CatalogSnapshot(title, previews, references)
    }

    private fun JsonObject?.string(name: String): String? =
      (this?.get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject?.number(name: String): Double? =
      (this?.get(name) as? JsonPrimitive)?.doubleOrNull
  }
}

internal data class SnapshotPreview(
  val id: String,
  val label: String,
  val path: String,
  val blob: String?,
  val metadata: String,
  val order: Int,
)

internal data class SnapshotReference(
  val id: String,
  val label: String,
  val previewId: String,
  val specFingerprint: String,
  val match: Double?,
  val order: Int,
)

internal object CatalogFeedDiff {
  fun between(
    beforeRevision: CatalogFeedRevision,
    before: CatalogSnapshot,
    afterRevision: CatalogFeedRevision,
    after: CatalogSnapshot,
  ): CatalogFeedBatch {
    val ids = before.previews.keys + after.previews.keys
    val previews =
      ids
        .mapNotNull { id ->
          val old = before.previews[id]
          val new = after.previews[id]
          when {
            old == null && new != null ->
              CatalogPreviewChange(
                CatalogPreviewChangeKind.ADDED,
                id,
                new.label,
                afterBlob = new.blob,
                order = new.order,
              )
            old != null && new == null ->
              CatalogPreviewChange(
                CatalogPreviewChangeKind.DELETED,
                id,
                old.label,
                beforeBlob = old.blob,
                // Keep the live catalog's authored order primary; removals no longer have a live
                // slot, so
                // append them in their former authored order instead of interleaving them
                // unpredictably.
                order = after.previews.size + old.order,
              )
            old != null &&
              new != null &&
              old.blob != new.blob &&
              (old.metadata != new.metadata || old.label != new.label) ->
              CatalogPreviewChange(
                CatalogPreviewChangeKind.VISUAL_AND_METADATA,
                id,
                new.label,
                old.blob,
                new.blob,
                new.order,
              )
            old != null && new != null && old.blob != new.blob ->
              CatalogPreviewChange(
                CatalogPreviewChangeKind.CHANGED,
                id,
                new.label,
                old.blob,
                new.blob,
                new.order,
              )
            old != null &&
              new != null &&
              (old.metadata != new.metadata || old.label != new.label) ->
              CatalogPreviewChange(
                CatalogPreviewChangeKind.METADATA,
                id,
                new.label,
                old.blob,
                new.blob,
                new.order,
              )
            else -> null
          }
        }
        .sortedWith(compareBy<CatalogPreviewChange> { it.order }.thenBy { it.id })

    val referenceIds = before.references.keys + after.references.keys
    val references =
      referenceIds
        .mapNotNull { id ->
          val old = before.references[id]
          val new = after.references[id]
          if (
            old != null &&
              new != null &&
              old.label == new.label &&
              old.previewId == new.previewId &&
              old.specFingerprint == new.specFingerprint &&
              old.match == new.match
          )
            return@mapNotNull null
          CatalogReferenceChange(
            id = id,
            label = new?.label ?: old!!.label,
            previewId = new?.previewId ?: old!!.previewId,
            specChanged =
              old?.specFingerprint != new?.specFingerprint ||
                old?.label != new?.label ||
                old?.previewId != new?.previewId ||
                old == null ||
                new == null,
            beforeMatch = old?.match,
            afterMatch = new?.match,
            order = new?.order ?: old!!.order,
            beforePresent = old != null,
            afterPresent = new != null,
          )
        }
        .sortedWith(compareBy<CatalogReferenceChange> { it.order }.thenBy { it.id })

    return CatalogFeedBatch(beforeRevision, afterRevision, previews, references)
  }
}

/** Pure RSS 2.0 projection of [CatalogFeedHistory]. */
internal object CatalogFeedXml {
  fun empty(system: String, baseUrl: String, linkQuery: String = ""): String =
    document(
      title = "$system catalog changes",
      baseUrl = baseUrl,
      linkQuery = linkQuery,
      batches = emptyList(),
      generated = Instant.now(),
      repo = null,
    )

  fun render(
    system: String,
    baseUrl: String,
    history: CatalogFeedHistory,
    linkQuery: String = "",
  ): String =
    document(
      title = "${history.title?.takeIf { it.isNotBlank() } ?: system} catalog changes",
      baseUrl = baseUrl,
      linkQuery = linkQuery,
      batches = history.batches,
      generated = history.revisions.firstOrNull()?.date?.let(::instantOrNull) ?: Instant.now(),
      repo = history.repo,
    )

  private fun document(
    title: String,
    baseUrl: String,
    linkQuery: String,
    batches: List<CatalogFeedBatch>,
    generated: Instant,
    repo: String?,
  ): String = buildString {
    append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    append("<rss version=\"2.0\" xmlns:atom=\"http://www.w3.org/2005/Atom\"><channel>\n")
    append("<title>${xml(title)}</title>\n")
    append("<link>${xml(feedUrl(baseUrl, query = linkQuery))}</link>\n")
    append("<description>${xml("Published preview and design-spec changes")}</description>\n")
    append("<atom:link href=\"")
      .append(xml(feedUrl(baseUrl, "/feed.xml", linkQuery)))
      .append("\" rel=\"self\" type=\"application/rss+xml\"/>\n")
    append("<lastBuildDate>${rfc822(generated)}</lastBuildDate>\n")
    for (batch in batches) append(item(baseUrl, linkQuery, repo, batch))
    append("</channel></rss>\n")
  }

  private fun item(
    baseUrl: String,
    linkQuery: String,
    repo: String?,
    batch: CatalogFeedBatch,
  ): String {
    val p = batch.previews.groupingBy { it.kind }.eachCount()
    val summary = buildList {
      p[CatalogPreviewChangeKind.ADDED]?.let { add("$it added") }
      p[CatalogPreviewChangeKind.DELETED]?.let { add("$it deleted") }
      p[CatalogPreviewChangeKind.CHANGED]?.let { add("$it visually changed") }
      p[CatalogPreviewChangeKind.VISUAL_AND_METADATA]?.let {
        add("$it visually and metadata changed")
      }
      p[CatalogPreviewChangeKind.METADATA]?.let { add("$it metadata changed") }
      if (batch.references.isNotEmpty()) add("${batch.references.size} design reference changes")
      if (isEmpty()) add("catalog metadata changed")
    }
      .joinToString(", ")
    val commitUrl = ServeCatalogRevision.treeUrl(repo, batch.after.commit)
    val fallbackLink = feedUrl(baseUrl, query = withAt(batch.after.commit, linkQuery))
    val link = commitUrl ?: fallbackLink
    val html = description(baseUrl, linkQuery, batch)
    return buildString {
      append("<item>\n")
      append("<title>${xml(summary)}</title>\n")
      append("<link>${xml(link)}</link>\n")
      append("<guid isPermaLink=\"false\">${batch.after.commit}</guid>\n")
      instantOrNull(batch.after.date)?.let { append("<pubDate>${rfc822(it)}</pubDate>\n") }
      append("<description>${xml(html)}</description>\n")
      append("</item>\n")
    }
  }

  private fun description(
    baseUrl: String,
    linkQuery: String,
    batch: CatalogFeedBatch,
  ): String = buildString {
    append("<p>Catalog publication <code>${batch.after.commit.take(8)}</code>")
    batch.after.sourceSha?.let { append(" from source <code>${html(it)}</code>") }
    append(".</p>")
    if (batch.previews.isNotEmpty()) {
      append("<h3>Previews</h3><ul>")
      for (change in batch.previews) {
        val oldUrl =
          feedUrl(
            baseUrl,
            "/render/${WebEscaping.urlEncodeSegment(change.id)}.png",
            withAt(batch.before.commit, linkQuery),
          )
        val newUrl =
          feedUrl(
            baseUrl,
            "/render/${WebEscaping.urlEncodeSegment(change.id)}.png",
            withAt(batch.after.commit, linkQuery),
          )
        val kindLabel =
          when (change.kind) {
            CatalogPreviewChangeKind.ADDED -> "added"
            CatalogPreviewChangeKind.DELETED -> "deleted"
            CatalogPreviewChangeKind.CHANGED -> "visually changed"
            CatalogPreviewChangeKind.VISUAL_AND_METADATA -> "visually and metadata changed"
            CatalogPreviewChangeKind.METADATA -> "metadata changed"
          }
        append("<li><strong>${html(change.label)}</strong>: $kindLabel")
        when (change.kind) {
          CatalogPreviewChangeKind.ADDED ->
            append("<br><img alt=\"After\" src=\"${html(newUrl)}\">")
          CatalogPreviewChangeKind.DELETED ->
            append("<br><img alt=\"Before\" src=\"${html(oldUrl)}\">")
          CatalogPreviewChangeKind.CHANGED ->
            append(
              "<br><img alt=\"Before\" src=\"${html(oldUrl)}\"> <img alt=\"After\" src=\"${html(newUrl)}\">"
            )
          CatalogPreviewChangeKind.VISUAL_AND_METADATA ->
            append(
              "<br><img alt=\"Before\" src=\"${html(oldUrl)}\"> <img alt=\"After\" src=\"${html(newUrl)}\">"
            )
          CatalogPreviewChangeKind.METADATA -> Unit
        }
        append("</li>")
      }
      append("</ul>")
    }
    if (batch.references.isNotEmpty()) {
      append("<h3>Design references</h3><ul>")
      for (change in batch.references) {
        append("<li><strong>${html(change.label)}</strong>")
        if (change.specChanged) append(": spec changed") else append(": diff score changed")
        if (change.beforeMatch != null || change.afterMatch != null) {
          append("; match ${score(change.beforeMatch)} → ${score(change.afterMatch)}")
          if (change.beforeMatch != null && change.afterMatch != null) {
            val delta = change.afterMatch - change.beforeMatch
            append(
              " (${if (delta >= 0) "+" else ""}${"%.2f".format(java.util.Locale.ROOT, delta)} pp)"
            )
          }
        }
        if (change.specChanged) {
          val encodedId = WebEscaping.urlEncodeSegment(change.id)
          if (change.beforePresent) {
            val oldUrl =
              feedUrl(
                baseUrl,
                "/reference/$encodedId.png",
                withAt(batch.before.commit, linkQuery),
              )
            append("<br><img alt=\"Before design reference\" src=\"${html(oldUrl)}\">")
          }
          if (change.afterPresent) {
            val newUrl =
              feedUrl(
                baseUrl,
                "/reference/$encodedId.png",
                withAt(batch.after.commit, linkQuery),
              )
            append(" <img alt=\"After design reference\" src=\"${html(newUrl)}\">")
          }
        }
        append("</li>")
      }
      append("</ul>")
    }
  }

  private fun score(value: Double?): String =
    value?.let { "%.2f%%".format(java.util.Locale.ROOT, it) } ?: "n/a"

  private fun withAt(commit: String, linkQuery: String): String =
    "at=$commit" + linkQuery.takeIf { it.isNotBlank() }?.let { "&$it" }.orEmpty()

  private fun feedUrl(baseUrl: String, path: String = "", query: String = ""): String =
    baseUrl + path + query.takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()

  private fun instantOrNull(value: String): Instant? = runCatching {
    Instant.parse(value)
  }
    .getOrNull()

  private fun rfc822(value: Instant): String =
    DateTimeFormatter.RFC_1123_DATE_TIME.format(value.atZone(ZoneOffset.UTC))

  private fun xml(value: String): String =
    value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

  private fun html(value: String): String = xml(value).replace("'", "&#39;")
}
