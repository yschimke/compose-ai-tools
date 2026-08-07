package ee.schimke.composeai.cli.serve

import java.util.concurrent.ConcurrentHashMap

/**
 * A served preview's Kotlin, staged as the playground editor's opening buffer — the "open this
 * preview in the playground" handoff (`/playground?from=<system>/<previewId>`).
 *
 * The catalog pages can already say *where* a preview is declared (the viewer's `source` link to
 * GitHub). This carries the next step: read that file and hand it to the editor with the catalog it
 * came from already selected, so a visitor lands on something they can press Run on instead of on
 * the generic sample.
 *
 * **Ready to compile is a starting point, not a promise.** A preview file is ordinary module code:
 * it may reference siblings the catalog's bundle never exported, or internals the resolved
 * classpath doesn't carry. Those come back as ordinary compile diagnostics against the right
 * classpath, which is a far better place to start editing from than an empty buffer — so the seed
 * is offered verbatim rather than rewritten into something guaranteed to build, which would no
 * longer be the preview's source.
 */
data class PlaygroundSeed(
  /** The catalog to preselect — the system the preview belongs to. */
  val catalog: String,
  /** The preview this came from, for the note the editor shows. */
  val previewId: String,
  /** Editor tab name, from the source path's basename (`FilledButton.kt`). */
  val fileName: String,
  /** The file's text, verbatim. */
  val text: String,
  /** Where it was read from, so the note can link back to the human-readable blob. */
  val blobUrl: String?,
)

/**
 * Resolves `(system, previewId)` to a [PlaygroundSeed] by reading the preview's source file off
 * GitHub.
 *
 * Two properties make this safe to expose on a public host:
 *
 * **The URL is never client-derived.** A request names a system and a preview id; both are resolved
 * through this server's own session registry, and the repo/ref/module/path that build the fetch URL
 * all come from the catalog's trusted metadata. A visitor cannot point the host at a URL of their
 * choosing — the worst they can do is name a preview that doesn't exist, which resolves to null.
 *
 * **Results are cached, and the cache cannot go stale behind a catalog refresh.** A page load must
 * not cost a GitHub round-trip every time, but a catalog that is refreshed, retired, or republished
 * under the same system id would otherwise keep serving the source it had at first read — the
 * viewer showing the new catalog while the handoff opens the old file, indefinitely. Two things
 * prevent that. The entry is keyed by the **resolved location**, not just `(system, previewId)`, so
 * a catalog whose repo/ref/module/path moved misses the cache by construction; and every entry
 * carries a [ttlSeconds] deadline, because a `ref` that names a *branch* is stable while the file
 * under it is not. Both are needed: the first catches a republished catalog immediately, the second
 * catches new content on an unchanged branch.
 *
 * The cache is also bounded: past [maxEntries] it stops accepting new entries rather than evicting
 * — the entries are tiny and a served catalog has a fixed preview count, so a full cache means the
 * interesting ones are already in it. Expired entries are swept when the cache is full, so a
 * long-running host reclaims them rather than wedging at the cap.
 */
class PlaygroundSeedResolver(
  /** Where a preview's source lives, or null when this server can't say. */
  private val locate: (system: String, previewId: String) -> Location?,
  /** Fetches a URL, returning its bytes or null. Injected so tests never touch the network. */
  private val fetch: (String) -> ByteArray?,
  /** Source files are code; anything larger than this is not a preview file worth seeding from. */
  private val maxBytes: Int = DEFAULT_MAX_BYTES,
  private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
  /** How long a cached seed may be served before it is re-read; see the class KDoc. */
  private val ttlSeconds: Long = DEFAULT_TTL_SECONDS,
  private val clock: () -> Long = System::currentTimeMillis,
  private val onLog: (String) -> Unit = {},
) {

  /** A preview's source location, entirely from catalog metadata. */
  data class Location(
    val repo: String,
    val ref: String,
    val module: String?,
    /** Module-relative path, as discovery recorded it. */
    val sourceFile: String,
  )

  /**
   * The cache key: the request's identity plus the *resolved* location it maps to.
   *
   * A data class rather than a joined string on purpose. Every field here is a repository path
   * component, and paths may legitimately contain the separator you'd pick — `"a" + " " + "b c.kt"`
   * and `"a b" + " " + "c.kt"` join to the same string, so a catalog whose location moved between
   * those two would hit the cache it was supposed to miss. Structural equality has no such seam and
   * needs no escaping rules to get right.
   */
  private data class CacheKey(val system: String, val previewId: String, val where: Location)

  private class Entry(val seed: PlaygroundSeed, val readAtMillis: Long)

  private val cache = ConcurrentHashMap<CacheKey, Entry>()

  fun seed(system: String, previewId: String): PlaygroundSeed? {
    // Resolve FIRST, then consult the cache. The location is an in-memory registry read, and keying
    // on it is what makes a refreshed or republished catalog miss by construction instead of
    // serving whatever the previous one pointed at.
    val where =
      locate(system, previewId)
        ?: run {
          onLog("no source path recorded for $system/$previewId; playground seed unavailable")
          return null
        }
    val key = CacheKey(system, previewId, where)
    val now = clock()
    cache[key]
      ?.takeIf { now - it.readAtMillis < ttlSeconds * 1000 }
      ?.let {
        return it.seed
      }
    val rawUrl =
      ServeUrls.githubRawUrl(where.repo, where.ref, where.module, where.sourceFile)
        ?: run {
          onLog("could not build a source URL for $system/$previewId")
          return null
        }
    val bytes =
      try {
        fetch(rawUrl)
      } catch (e: Exception) {
        onLog("fetching $rawUrl failed (${e.message})")
        null
      }
    if (bytes == null) {
      onLog("could not read $rawUrl; playground seed unavailable for $system/$previewId")
      return null
    }
    if (bytes.size > maxBytes) {
      onLog("$rawUrl is ${bytes.size} bytes, over the ${maxBytes}-byte seed cap")
      return null
    }
    val text = bytes.decodeToString()
    // A file that decodes to replacement characters isn't Kotlin the editor can usefully open —
    // better no seed (and the sample) than a buffer full of U+FFFD.
    if (text.contains('�')) {
      onLog("$rawUrl is not valid UTF-8; playground seed unavailable")
      return null
    }
    val seed =
      PlaygroundSeed(
        catalog = system,
        previewId = previewId,
        fileName = fileNameFor(where.sourceFile),
        text = text,
        blobUrl = ServeUrls.githubBlobUrl(where.repo, where.ref, where.module, where.sourceFile),
      )
    // Bounded, and deliberately not an LRU: entries are a few KB, a catalog has a fixed number of
    // previews, and a full cache means the ones people actually open are already served from it. A
    // full cache first drops what has expired, so a long-running host reclaims the space a moved
    // catalog left behind rather than wedging at the cap forever.
    if (cache.size >= maxEntries) {
      cache.entries.removeIf { now - it.value.readAtMillis >= ttlSeconds * 1000 }
    }
    if (cache.size < maxEntries) cache[key] = Entry(seed, now)
    return seed
  }

  companion object {
    /** A preview source file. Well above any real one, well below "somebody linked a blob". */
    const val DEFAULT_MAX_BYTES = 256 * 1024

    /** Cached seeds. A large catalog set is a few hundred previews; this holds the popular ones. */
    const val DEFAULT_MAX_ENTRIES = 256

    /**
     * How long a cached seed is served before it is re-read. Sized against the catalog refresh
     * interval (`--catalog-refresh-interval`, default 600 s): a `ref` that names a branch keeps the
     * cache key stable while the file under it moves, so this is the bound on how long the handoff
     * can lag the catalog the viewer is showing.
     */
    const val DEFAULT_TTL_SECONDS = 600L

    /**
     * The editor tab name for a source path: its basename, `.kt`-suffixed, sanitised the same way
     * [PlaygroundCompileService.safeKtName] sanitises a client-supplied name — the seed is staged
     * into the same request shape a hand-typed file goes through, so it may as well be named by the
     * same rules.
     */
    internal fun fileNameFor(sourceFile: String): String =
      PlaygroundCompileService.safeKtName(sourceFile.replace('\\', '/').substringAfterLast('/'))

    private val httpClient: okhttp3.OkHttpClient by lazy {
      okhttp3.OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        // Shorter than the catalog store's 30 s: this one is on a page-load path, and a slow
        // GitHub is better answered by opening the sample than by holding the request open.
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    }

    /**
     * The production fetcher: one capped GET. Kept here rather than in `ServeCommand` so the seed's
     * network envelope (timeouts, size cap, fail-soft on anything non-2xx) lives with the thing it
     * bounds.
     */
    fun httpFetch(url: String, maxBytes: Int = DEFAULT_MAX_BYTES): ByteArray? =
      try {
        httpClient.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { response ->
          if (!response.isSuccessful) null
          else response.body?.byteStream()?.readNBytes(maxBytes + 1)
        }
      } catch (_: Exception) {
        null
      }
  }
}
