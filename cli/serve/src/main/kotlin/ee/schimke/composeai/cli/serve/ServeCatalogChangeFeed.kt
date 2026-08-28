package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.web.WebEscaping
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
public constructor(
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

  /**
   * Whether this feed has a document to serve for [system] — i.e. the session is a published
   * catalog with a delivery branch, not a locally-served module. What decides whether a page may
   * offer its Changelog entry at all.
   */
  fun serves(system: String): Boolean = entries().any { it.system == system }

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
  public fun tick() {
    val configs = entries().associateBy { it.system }
    val at = now()
    for ((key, state) in states) {
      if (state.activeUntil <= at) continue
      val config = configs[key.system] ?: continue
      enqueue(key, config, state)
    }
  }

  public fun isActive(system: String, baseUrl: String, linkQuery: String = ""): Boolean =
    states[Key(system, baseUrl.trimEnd('/'), linkQuery.removePrefix("?"))]?.activeUntil?.let {
      it > now()
    } == true

  public fun stateCount(): Int = states.size

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
    public const val MAX_FEED_ADDRESSES = 64

    public fun safeName(value: String): String =
      value.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').ifBlank { "catalog" }

    private fun digest(value: String): String =
      MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {
        "%02x".format(it)
      }
  }
}

/** A fully materialised, newest-first slice of one delivery branch. */
public data class CatalogFeedHistory(
  val title: String?,
  val revisions: List<CatalogFeedRevision>,
  val batches: List<CatalogFeedBatch>,
  val repo: String? = null,
)

public data class CatalogFeedRevision(
  val commit: String,
  val date: String,
  val subject: String,
  val sourceSha: String?,
)

public enum class CatalogPreviewChangeKind {
  ADDED,
  DELETED,
  CHANGED,
  VISUAL_AND_METADATA,
  METADATA,
}

public data class CatalogPreviewChange(
  val kind: CatalogPreviewChangeKind,
  val id: String,
  val label: String,
  val beforeBlob: String? = null,
  val afterBlob: String? = null,
  val order: Int = Int.MAX_VALUE,
)

public data class CatalogReferenceChange(
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

public data class CatalogFeedBatch(
  val before: CatalogFeedRevision,
  val after: CatalogFeedRevision,
  val previews: List<CatalogPreviewChange>,
  val references: List<CatalogReferenceChange>,
)

/** Source seam: real serving uses Git; tests can provide an already-built history. */
public fun interface CatalogFeedSource {
  /** Return null when [knownHead] is still current, before materialising historical snapshots. */
  fun read(config: CatalogLoadTracker.Config, knownHead: String?): CatalogFeedHistory?
}

/** Shallow bare-Git implementation of [CatalogFeedSource]. */
public class GitCatalogFeedSource(
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

    public fun parseRevision(line: String): CatalogFeedRevision? {
      val fields = line.split('\u001f', limit = 3)
      if (fields.size != 3 || !SHA.matches(fields[0])) return null
      return CatalogFeedRevision(
        commit = fields[0],
        date = fields[1],
        subject = fields[2],
        sourceSha = SOURCE_SHA.find(fields[2])?.groupValues?.get(1),
      )
    }

    public fun parseTree(text: String): Map<String, String> = buildMap {
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

public data class CatalogFeedGitResult(
  val exitCode: Int,
  val stdout: String,
  val stderr: String,
) {
  val ok: Boolean
    get() = exitCode == 0
}

public fun runCatalogFeedGit(dir: File, args: List<String>): CatalogFeedGitResult {
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

public data class CatalogSnapshot(
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
            matchVersion = match.number("scoreVersion")?.toInt(),
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

public data class SnapshotPreview(
  val id: String,
  val label: String,
  val path: String,
  val blob: String?,
  val metadata: String,
  val order: Int,
)

public data class SnapshotReference(
  val id: String,
  val label: String,
  val previewId: String,
  val specFingerprint: String,
  val match: Double?,
  /**
   * Which pixel path [match] was minted by — see `ServeDesignReferenceStore.SCORE_VERSION`.
   *
   * Carried because the feed's job is to say what *changed between two revisions*, and two numbers
   * from two kernels are not a change in the design. The scorer moved once, deliberately; a feed
   * that compared across that move would report every reference in the catalog as having shifted,
   * in the one batch where none of them had.
   */
  val matchVersion: Int?,
  val order: Int,
)

public object CatalogFeedDiff {
  /**
   * Whether the two revisions' scores are two readings of one instrument.
   *
   * Only when **both** sides actually published a score. An absent score is not a rival kernel: the
   * publish-time scorer is optional (no Playwright, no Chromium, an undecodable pair ⇒ no `match`
   * at all), so a score appearing or going away is an ordinary, observable catalog change and was
   * reported as one long before versions existed. Reading a null version as a mismatched kernel
   * would silence exactly that.
   */
  private fun crossKernel(old: SnapshotReference?, new: SnapshotReference?): Boolean =
    old?.match != null && new?.match != null && old.matchVersion != new.matchVersion

  /**
   * Whether the published score actually moved between two revisions of the same reference.
   *
   * Two numbers minted by different kernels are not a move. The scorer's pixel path changed once,
   * deliberately, and every published number changed with it; a feed that compared across that
   * boundary would report every reference in the catalog as having shifted, in the one batch where
   * none of them had. What it still reports across it is everything it can actually see — a moved
   * raster, a renamed label, a reference that appeared or went away, and a score that arrived or
   * stopped being published.
   */
  private fun matchMoved(old: SnapshotReference, new: SnapshotReference): Boolean =
    !crossKernel(old, new) && old.match != new.match

  /** The two scores to print, or nothing when they are not each other's units. */
  private fun reportedMatch(
    old: SnapshotReference?,
    new: SnapshotReference?,
  ): Pair<Double?, Double?> = if (crossKernel(old, new)) null to null else old?.match to new?.match

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
              !matchMoved(old, new)
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
            beforeMatch = reportedMatch(old, new).first,
            afterMatch = reportedMatch(old, new).second,
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
public object CatalogFeedXml {
  /** How many variants a collapsed group names before the rest become a count. */
  public const val MAX_GROUP_LINKS = 24

  /** Preview ids join their component slug and axes with this; group names are cut on it. */
  private const val SEPARATOR = "__"

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

  /**
   * One `<li>` per **component and change kind**, not per preview.
   *
   * A publication that moves a component moves every variant of it — `Media/PlayerScreen` is 4
   * states × 5 screen sizes, so a single source fix produced twenty list entries and forty images
   * in one item, and readers rendered that as a wall. So a group with more than one member shows
   * one representative's images and names the rest as links: the item still accounts for every
   * change, and the reader can open any of them pinned to this publication.
   *
   * Images lead with **after**. The interesting half of a change is what it looks like now; a feed
   * reader that only shows the first image was showing the superseded render.
   */
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
      for ((key, group) in batch.previews.groupBy { it.label to it.kind }) {
        val (label, kind) = key
        val lead = group.first()
        val kindLabel = kindLabel(kind)
        val oldUrl = renderUrl(baseUrl, linkQuery, lead.id, batch.before.commit)
        val newUrl = renderUrl(baseUrl, linkQuery, lead.id, batch.after.commit)
        append("<li><strong>${html(label)}</strong>: ")
        if (group.size == 1) append(kindLabel) else append("${group.size} previews $kindLabel")
        val imaged =
          when (kind) {
            CatalogPreviewChangeKind.ADDED -> {
              append("<br><img alt=\"After\" src=\"${html(newUrl)}\">")
              true
            }
            CatalogPreviewChangeKind.DELETED -> {
              append("<br><img alt=\"Before\" src=\"${html(oldUrl)}\">")
              true
            }
            CatalogPreviewChangeKind.CHANGED,
            CatalogPreviewChangeKind.VISUAL_AND_METADATA -> {
              append(
                "<br><img alt=\"After\" src=\"${html(newUrl)}\"> <img alt=\"Before\" src=\"${html(oldUrl)}\">"
              )
              true
            }
            CatalogPreviewChangeKind.METADATA -> false
          }
        if (group.size > 1) {
          // A deleted preview is absent from the after revision by definition, and a pinned viewer
          // takes that revision as authoritative — it answers "not published in this revision"
          // rather than falling back to the tip. So a deleted group's links are pinned to the
          // revision that still has the pixels.
          val linkCommit =
            if (kind == CatalogPreviewChangeKind.DELETED) batch.before.commit
            else batch.after.commit
          val names = variantNames(group.map { it.id })
          val links = group.mapIndexed { index, change ->
            link(previewUrl(baseUrl, linkQuery, change.id, linkCommit), names[index])
          }
          append("<br><small>")
          if (imaged) {
            append("Shown: ${links.first()}. Also $kindLabel: ")
            appendList(links.drop(1))
          } else {
            append("Previews: ")
            appendList(links)
          }
          append("</small>")
        }
        append("</li>")
      }
      append("</ul>")
    }
    if (batch.references.isNotEmpty()) {
      append("<h3>Design references</h3><ul>")
      // Keyed by the mapped preview's component as well as the label: a reference label is
      // presentation text a producer may repeat across components ("Figma", "Default"), and
      // collapsing two components under one entry would show one of them and silently speak for
      // the other. The preview id's component slug is the identity that cannot collide.
      for ((key, group) in
        batch.references.groupBy { Triple(componentOf(it.previewId), it.label, it.specChanged) }) {
        val (_, label, specChanged) = key
        val lead = group.first()
        append("<li><strong>${html(label)}</strong>: ")
        if (group.size > 1) append("${group.size} references, ")
        if (specChanged) append("spec changed") else append("diff score changed")
        append(matchText(lead))
        if (specChanged) {
          val encodedId = WebEscaping.urlEncodeSegment(lead.id)
          if (lead.afterPresent) {
            val newUrl =
              feedUrl(baseUrl, "/reference/$encodedId.png", withAt(batch.after.commit, linkQuery))
            append("<br><img alt=\"After design reference\" src=\"${html(newUrl)}\">")
          }
          if (lead.beforePresent) {
            val oldUrl =
              feedUrl(baseUrl, "/reference/$encodedId.png", withAt(batch.before.commit, linkQuery))
            val separator = if (lead.afterPresent) " " else "<br>"
            append("$separator<img alt=\"Before design reference\" src=\"${html(oldUrl)}\">")
          }
        }
        if (group.size > 1) {
          val names = variantNames(group.map { it.previewId })
          val links = group.mapIndexed { index, change ->
            // A reference the publication REMOVED cannot be reached through the comparison page at
            // all: that route resolves the reference from the catalog on disk, so a removed one has
            // no page to pin. Its preview at the before revision is the closest thing that answers.
            val href =
              if (change.afterPresent)
                compareUrl(baseUrl, linkQuery, change.previewId, change.id, batch.after.commit)
              else previewUrl(baseUrl, linkQuery, change.previewId, batch.before.commit)
            link(href, names[index]) + matchSuffix(change)
          }
          append("<br><small>")
          if (specChanged && (lead.beforePresent || lead.afterPresent)) {
            append("Shown: ${links.first()}. Also changed: ")
            appendList(links.drop(1))
          } else {
            append("References: ")
            appendList(links)
          }
          append("</small>")
        }
        append("</li>")
      }
      append("</ul>")
    }
  }

  /** `match a → b (+n pp)`, or empty when neither side published a score. */
  private fun matchText(change: CatalogReferenceChange): String = buildString {
    if (change.beforeMatch == null && change.afterMatch == null) return@buildString
    append("; match ${score(change.beforeMatch)} → ${score(change.afterMatch)}")
    if (change.beforeMatch != null && change.afterMatch != null) {
      val delta = change.afterMatch - change.beforeMatch
      append(" (${if (delta >= 0) "+" else ""}${"%.2f".format(java.util.Locale.ROOT, delta)} pp)")
    }
  }

  /** The compact `(a → b)` form that trails a linked variant in a collapsed group. */
  private fun matchSuffix(change: CatalogReferenceChange): String =
    if (change.beforeMatch == null && change.afterMatch == null) ""
    else " (${score(change.beforeMatch)} → ${score(change.afterMatch)})"

  private fun StringBuilder.appendList(links: List<String>) {
    append(links.take(MAX_GROUP_LINKS).joinToString(", "))
    val hidden = links.size - MAX_GROUP_LINKS
    if (hidden > 0) append(", and $hidden more")
  }

  private fun kindLabel(kind: CatalogPreviewChangeKind): String =
    when (kind) {
      CatalogPreviewChangeKind.ADDED -> "added"
      CatalogPreviewChangeKind.DELETED -> "deleted"
      CatalogPreviewChangeKind.CHANGED -> "visually changed"
      CatalogPreviewChangeKind.VISUAL_AND_METADATA -> "visually and metadata changed"
      CatalogPreviewChangeKind.METADATA -> "metadata changed"
    }

  /**
   * What to call each member of a collapsed group.
   *
   * Every id in a group starts with the same component slug, so repeating it once per link buys
   * nothing: `media-playerscreen__ideal__default__192dp, media-playerscreen__ideal__ambient__192dp,
   * …` reads as `default__192dp, ambient__192dp, …` once the shared head is dropped. The cut is
   * taken at a `__` boundary so a name never starts mid-word, and a group that shares no such
   * boundary — or where dropping it would leave a name empty — keeps its full ids.
   */
  private fun variantNames(ids: List<String>): List<String> {
    if (ids.size < 2) return ids
    val shortest = ids.minOf { it.length }
    var common = 0
    while (common < shortest && ids.all { it[common] == ids[0][common] }) common++
    val cut = ids[0].take(common).lastIndexOf(SEPARATOR)
    if (cut < 0) return ids
    val head = cut + SEPARATOR.length
    return if (ids.any { it.length <= head }) ids else ids.map { it.substring(head) }
  }

  /** A preview id's component slug — the head an id shares with its every variant. */
  private fun componentOf(previewId: String): String = previewId.substringBefore(SEPARATOR)

  private fun link(url: String, text: String): String = "<a href=\"${html(url)}\">${html(text)}</a>"

  private fun previewUrl(baseUrl: String, linkQuery: String, id: String, commit: String): String =
    feedUrl(baseUrl, "/p/${WebEscaping.urlEncodeSegment(id)}", withAt(commit, linkQuery))

  private fun renderUrl(baseUrl: String, linkQuery: String, id: String, commit: String): String =
    feedUrl(baseUrl, "/render/${WebEscaping.urlEncodeSegment(id)}.png", withAt(commit, linkQuery))

  private fun compareUrl(
    baseUrl: String,
    linkQuery: String,
    previewId: String,
    referenceId: String,
    commit: String,
  ): String =
    feedUrl(
      baseUrl,
      "/compare/${WebEscaping.urlEncodeSegment(previewId)}",
      "reference=${WebEscaping.urlEncodeSegment(referenceId)}&" + withAt(commit, linkQuery),
    )

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
