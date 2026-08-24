package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.cli.serve.UsageRules.Companion.appliesToModule
import ee.schimke.composeai.cli.serve.UsageRules.Companion.declaresCatalogScaffolds
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
 * is never rewritten into something guaranteed to build, which would no longer be the preview's
 * source.
 *
 * **What it does narrow is scope.** A section file is one *group*, not one component: opening
 * `Button/Filled` used to hand over all 242 lines of `Buttons.kt` — five components, and forty-odd
 * `@OverrideVariant` lines belonging to the variant matrix rather than to anything a visitor wants
 * to read. When discovery recorded an anchor line inside the preview (`PreviewInfo.bodyLine`), the
 * seed is the file's header plus **that one declaration**, still verbatim, so the buffer opens on
 * the composable that was clicked. Without an anchor it stays the whole file, as it always was.
 */
data class PlaygroundSeed(
  /** The catalog to preselect — the system the preview belongs to. */
  val catalog: String,
  /** Owning Gradle module, used to select the matching compile bundle in a multi-module catalog. */
  val sourceModule: String? = null,
  /** The preview this came from, for the note the editor shows. */
  val previewId: String,
  /** Editor tab name, from the source path's basename (`FilledButton.kt`). */
  val fileName: String,
  /** The seeded Kotlin: the whole file, or its header plus one declaration when [sliced]. */
  val text: String,
  /** Where it was read from, so the note can link back to the human-readable blob. */
  val blobUrl: String?,
  /**
   * True when [text] is one declaration rather than the whole file, so the editor's note can say
   * which it is. A visitor told "this is the whole file" while looking at one function would go
   * hunting for the rest.
   */
  val sliced: Boolean = false,
  /**
   * True when [text] has been rewritten into plain Compose by [PlaygroundSourceCleaner] — the
   * catalog's annotations, sticker frame, click tally and knobs resolved away — rather than carried
   * verbatim. This changes what the editor may claim: a verbatim seed is "the preview's source, and
   * some of it will not resolve"; a cleaned one is "usage code, ready to Run".
   */
  val cleaned: Boolean = false,
  /**
   * Declared scaffolding that survived cleaning ([PlaygroundSourceCleaner.Result.residue]). Empty
   * is the good case. Non-empty means the seed is *partly* cleaned — better than verbatim, but
   * carrying names that will not resolve — and the note says so instead of over-promising.
   */
  val residue: List<String> = emptyList(),
  /**
   * True when the catalog actually declared what its own helpers mean (a `compose-usage.json` with
   * scaffold rules), as opposed to getting [UsageRules.GENERIC].
   *
   * The distinction has to reach the editor's note, because the two produce very different buffers
   * from the same code path. With rules, `Sticker`/`counted`/the knobs are resolved away and "press
   * Run" is true. Without them only the shared annotations come off — the catalog's own helpers
   * stay exactly where they were, and they will not resolve against the published bundle. They are
   * not [residue] either, since residue reports *declared* scaffolding that survived a rule and
   * under generic rules nothing was declared. So without this flag the note claimed the frame and
   * knobs were gone while they were still on screen.
   */
  val scaffoldsDeclared: Boolean = false,
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
    /**
     * A 1-based line inside the preview function's body, as discovery recorded it — the anchor
     * [sliceDeclaration] walks outwards from to find the whole declaration. Null on a manifest
     * predating the field, or a classfile with no line numbers; the seed is then the whole file.
     *
     * Part of the cache key by construction (it is a [Location] field), which matters: a catalog
     * republished from a file whose declarations moved must not keep slicing at the old offset.
     */
    val bodyLine: Int? = null,
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

  /**
   * One monitor per in-flight key, so a cold key is fetched **once** however many callers ask for
   * it at the same moment.
   *
   * This mattered little while the only caller was the playground page, which one visitor opens
   * deliberately. The viewer's Source panel changed that: it is one click on a page anyone browsing
   * a catalog is already on, so a popular preview after a restart or a TTL expiry can have a dozen
   * viewers arrive together. Without coalescing each one performs its own 10 s-connect / 10 s-read
   * GitHub GET for the same file — a burst of duplicate work holding IO threads, for a result they
   * will all share a moment later.
   */
  private val inFlight = ConcurrentHashMap<CacheKey, Flight>()

  /**
   * One resolution attempt, carrying its **outcome** and not merely acting as a monitor.
   *
   * Signalling completion through the cache alone was not enough, because two ordinary outcomes
   * never reach it: a fetch that fails (a 404, a timeout, an oversized file) is deliberately not
   * cached, and a successful one is dropped when the cache is at [maxEntries]. In both cases every
   * waiter woke to another miss and repeated the same GitHub round trip — sequentially, each behind
   * the previous one's 10 s connect and 10 s read, which is the exact pile-up the coalescing was
   * added to prevent, in the two situations where it hurts most.
   */
  private class Flight {
    var done = false
    var seed: PlaygroundSeed? = null
  }

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
    cachedSeed(key)?.let {
      return it
    }
    // Single-flight: the first caller for a key fetches, the rest wait on its monitor and then find
    // the answer in the cache. Re-checked inside the lock because that is the whole point — every
    // waiter arrives after the fetch it was waiting for has already stored its result.
    val flight = inFlight.computeIfAbsent(key) { Flight() }
    try {
      synchronized(flight) {
        // The leader's answer, whatever it was — including "no seed", which is a result and not a
        // reason to try again.
        if (flight.done) return flight.seed
        val seed = cachedSeed(key) ?: fetchSeed(system, previewId, where, key)
        flight.seed = seed
        flight.done = true
        return seed
      }
    } finally {
      // Removed by whoever leaves first; the waiters still behind it hold the same object and read
      // its recorded outcome. A caller arriving after the removal starts a fresh flight, which is
      // correct — that is a new request, not one this attempt was ever going to answer.
      inFlight.remove(key, flight)
    }
  }

  private fun cachedSeed(key: CacheKey): PlaygroundSeed? {
    val now = clock()
    return cache[key]?.takeIf { now - it.readAtMillis < ttlSeconds * 1000 }?.seed
  }

  private fun fetchSeed(
    system: String,
    previewId: String,
    where: Location,
    key: CacheKey,
  ): PlaygroundSeed? {
    val now = clock()
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
    // Cleaning first, slicing as the fallback. The cleaner does its own slicing (it has to — it
    // closes over the same-file helpers the cleaned body still calls, which a single-declaration
    // slice would have cut away), so this is one choice between two whole strategies rather than
    // two passes. Null means it found nothing it could safely do, and the verbatim slice stands.
    //
    // Gated on the anchor, which is also why the rules file is not fetched for a catalog whose
    // manifest predates `bodyLine`: without an anchor the cleaner cannot say which declaration was
    // clicked, so there is nothing to clean and no reason to ask GitHub for rules describing it.
    val cleaned =
      try {
        if (where.bodyLine == null) null
        else {
          val rules = rulesFor(where)
          PlaygroundSourceCleaner.clean(
            source = text,
            bodyLine = where.bodyLine,
            rules = rules,
            strings = stringsFor(where, rules),
            helperSources = helperSourcesFor(where, rules),
          )
        }
      } catch (e: Exception) {
        // A seed is a convenience. A cleaner bug must degrade to the verbatim slice that worked
        // before it existed, never take the playground handoff down with it.
        onLog("cleaning $system/$previewId failed (${e.message}); seeding the verbatim slice")
        null
      }
    val sliced = sliceDeclaration(text, where.bodyLine)
    val seed =
      PlaygroundSeed(
        catalog = system,
        sourceModule = where.module,
        previewId = previewId,
        fileName = fileNameFor(where.sourceFile),
        text = cleaned?.text ?: sliced ?: text,
        blobUrl = ServeUrls.githubBlobUrl(where.repo, where.ref, where.module, where.sourceFile),
        sliced = cleaned != null || sliced != null,
        cleaned = cleaned != null,
        residue = cleaned?.residue.orEmpty(),
        scaffoldsDeclared = cleaned != null && rulesFor(where).declaresCatalogScaffolds(),
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

  /**
   * The catalog's own [UsageRules], read from `compose-usage.json` at the repo root, at the same
   * `ref` the catalog was published from — so the rules and the source they describe can never be
   * from different revisions.
   *
   * Cached per `(repo, ref)` rather than per preview: one catalog has one rules file, and every
   * preview in it wants the same one. A catalog that ships no rules file caches the *absence* too
   * (as [UsageRules.GENERIC]), so browsing a catalog without one does not re-ask GitHub for a file
   * that isn't there on every card.
   */
  private val rulesCache = ConcurrentHashMap<Pair<String, String>, Pair<UsageRules, Long>>()

  private val stringsCache =
    ConcurrentHashMap<Pair<String, String>, Pair<Map<String, String>, Long>>()

  private val helperCache = ConcurrentHashMap<Pair<String, String>, Pair<List<String>, Long>>()

  private fun rulesFor(where: Location): UsageRules {
    val key = where.repo to where.ref
    val now = clock()
    rulesCache[key]
      ?.takeIf { now - it.second < ttlSeconds * 1000 }
      ?.let {
        return it.first.takeIf { rules -> rules.appliesToModule(where.module) }
          ?: UsageRules.GENERIC
      }
    val url = ServeUrls.githubRawUrl(where.repo, where.ref, null, USAGE_RULES_FILE)
    val rules =
      url
        ?.let { u ->
          try {
            fetch(u)
          } catch (_: Exception) {
            null
          }
        }
        ?.takeIf { it.size <= maxBytes }
        ?.decodeToString()
        ?.let { UsageRules.parse(it, onLog) } ?: UsageRules.GENERIC
    // Bounded and swept, like the seed cache beside it. A TTL alone only stops an expired value
    // being *returned* — it never removes the key, so a long-running host seeing catalogs
    // republished under changing refs would keep an entry per historical ref forever, each holding
    // a
    // parsed rules object and (below) up to the fetch cap of string data.
    evictExpired(rulesCache, now)
    if (rulesCache.size < maxEntries) rulesCache[key] = rules to now
    // Cached by `(repo, ref)` because that is what was FETCHED; scoped by module on the way out,
    // because a repo can publish several catalogs from one rules file. See [UsageRules.modules].
    return rules.takeIf { it.appliesToModule(where.module) } ?: UsageRules.GENERIC
  }

  /**
   * The catalog's English string resources, so `stringResource(Res.string.label_filled)` can be
   * inlined as the label the sticker actually renders.
   *
   * Parsed with a deliberately narrow regex rather than an XML parser: this reads one known
   * generated file shape, and a `<string name="x">y</string>` it does not recognise simply is not
   * inlined, which leaves the lookup in place — the safe direction.
   */
  private fun stringsFor(where: Location, rules: UsageRules): Map<String, String> {
    val path = rules.stringsPath?.takeIf { it.isNotBlank() } ?: return emptyMap()
    val key = where.repo to "${where.ref}:${where.module}:$path"
    val now = clock()
    stringsCache[key]
      ?.takeIf { now - it.second < ttlSeconds * 1000 }
      ?.let {
        return it.first
      }
    // A leading `/` means the repo root rather than the catalog's own module — the resources a
    // shared component module owns are not under the module the previews live in, and every
    // existing (module-relative) rules file is unaffected because none of them starts with one.
    val url =
      if (path.startsWith("/")) ServeUrls.githubRawUrl(where.repo, where.ref, null, path)
      else ServeUrls.githubRawUrl(where.repo, where.ref, where.module, path)
    val text =
      url
        ?.let { u ->
          try {
            fetch(u)
          } catch (_: Exception) {
            null
          }
        }
        ?.takeIf { it.size <= maxBytes }
        ?.decodeToString()
    val strings =
      if (text == null) emptyMap()
      else
        STRING_RESOURCE.findAll(text).associate {
          it.groupValues[1] to unescapeAndroidString(it.groupValues[2])
        }
    evictExpired(stringsCache, now)
    if (stringsCache.size < maxEntries) stringsCache[key] = strings to now
    return strings
  }

  /**
   * The catalog's declared scaffold sources ([UsageRules.scaffoldSources]), read repo-root-relative
   * at the same `ref` as the preview's own file, so a sticker and the shared component it delegates
   * to are always from one revision.
   *
   * Cached per `(repo, ref)` beside the rules that named them: one catalog has one set, every
   * preview in it wants the same set, and a browse of a catalog must not re-read a shared module on
   * every card. Capped at [MAX_SCAFFOLD_SOURCES] files — the whole point is a handful of
   * scaffolding files, and a rules file naming hundreds would turn one page load into a crawl of
   * the repo.
   */
  private fun helperSourcesFor(where: Location, rules: UsageRules): List<String> {
    val paths = rules.scaffoldSources.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    if (paths.isEmpty()) return emptyList()
    if (paths.size > MAX_SCAFFOLD_SOURCES) {
      onLog(
        "compose-usage.json names ${paths.size} scaffold sources; reading the first $MAX_SCAFFOLD_SOURCES"
      )
    }
    val wanted = paths.take(MAX_SCAFFOLD_SOURCES)
    val key = where.repo to "${where.ref}:${wanted.joinToString("|")}"
    val now = clock()
    helperCache[key]
      ?.takeIf { now - it.second < ttlSeconds * 1000 }
      ?.let {
        return it.first
      }
    val texts = wanted.mapNotNull { path ->
      val url = ServeUrls.githubRawUrl(where.repo, where.ref, null, path) ?: return@mapNotNull null
      val bytes =
        try {
          fetch(url)
        } catch (e: Exception) {
          onLog("fetching scaffold source $url failed (${e.message})")
          null
        }
      if (bytes == null) {
        onLog("could not read scaffold source $url")
        return@mapNotNull null
      }
      if (bytes.size > maxBytes) {
        onLog("$url is ${bytes.size} bytes, over the ${maxBytes}-byte cap")
        return@mapNotNull null
      }
      bytes.decodeToString().takeIf { !it.contains('\uFFFD') }
    }
    evictExpired(helperCache, now)
    if (helperCache.size < maxEntries) helperCache[key] = texts to now
    return texts
  }

  /** Drops every entry past its TTL. Called before an insert, so the caps stay reachable. */
  private fun <K, V> evictExpired(cache: ConcurrentHashMap<K, Pair<V, Long>>, now: Long) {
    cache.entries.removeIf { now - it.value.second >= ttlSeconds * 1000 }
  }

  companion object {
    /**
     * Where a catalog declares what its own scaffolding is. Repo root, beside `catalog.spec.json`.
     */
    const val USAGE_RULES_FILE = "compose-usage.json"

    private val STRING_RESOURCE =
      Regex("""<string\s+name="([A-Za-z0-9_]+)"\s*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)

    /**
     * The Android/CMP resource escapes a label can carry. Not a general XML unescape — an entity
     * this does not know is left as written, which shows up in the snippet as itself rather than as
     * a wrong character.
     */
    internal fun unescapeAndroidString(raw: String): String =
      raw
        .replace("\\'", "'")
        .replace("\\\"", "\"")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
        .trim()

    /**
     * How many [UsageRules.scaffoldSources] a catalog may have read. A catalog's scaffolding is a
     * handful of files by construction (m3-catalog: 17 helpers across three); this is the bound
     * that keeps a mistaken rules file from turning one Source panel into a repo crawl.
     */
    const val MAX_SCAFFOLD_SOURCES = 12

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

    /**
     * Narrows [text] to the file's **header plus the one declaration** [bodyLine] falls inside, or
     * null when it can't be done and the caller should seed the whole file.
     *
     * ### Why the header is kept whole
     *
     * "Header" is everything above the first top-level declaration: the `package` line, any
     * `@file:` annotations, and the imports. It is carried verbatim rather than pruned to the
     * imports this one declaration happens to use, because deciding that needs a Kotlin parser and
     * getting it wrong turns a working buffer into a wall of unresolved references. An unused
     * import costs a visitor nothing; a missing one costs them the compile. `@file:OptIn(...)` is
     * in there too, which a body using an experimental API genuinely needs.
     *
     * ### How the declaration's bounds are found
     *
     * From **one** anchor, expanded to the enclosing *top-level declaration*.
     *
     * A span would be the obvious input, and the classfile appears to offer one — but its upper
     * bound is fiction on Kotlin whenever the method inlines anything (see `PreviewInfo.bodyLine`),
     * and a wrong end here silently cuts into the next declaration. One line known to be *inside*
     * the body is enough, because the boundaries are findable from the source.
     *
     * A boundary is [startsTopLevelDeclaration]: a non-blank line at **column 0** whose predecessor
     * is **blank**. The declaration containing the anchor runs from the nearest such line at or
     * above it, to the last non-blank line before the next one.
     *
     * Both halves of that test earn their place, and the first version of this had only one of them
     * — "walk outwards over non-blank lines" — which was wrong in a way worth recording. A blank
     * line *inside* a body is ordinary formatted Kotlin, not an oddity (`OverridablePreviews`
     * separates its `previewOverride*` declarations from the `Surface` they feed), and treating it
     * as the end truncated the declaration mid-body, closing braces and all. Requiring column 0
     * *and* a preceding blank is what tells a separator from a breath inside a body: an internal
     * blank line is followed by indented code, and a top-level closing brace sits at column 0 but
     * is not preceded by a blank.
     *
     * A brace-counting scan would be more general still, but it has to model strings, char
     * literals, comments and nested lambdas to not go wrong, and going wrong means silently
     * truncating somebody's code mid-expression. This rule fails in the safer direction: on source
     * that puts no blank line between two declarations it over-selects, taking both — a bigger
     * buffer rather than a broken one.
     *
     * ktfmt (Google style, which every catalog in this repo is formatted with) guarantees the
     * separating blank line, and never puts one inside an annotation stack or between KDoc and what
     * it documents — so the annotations and the KDoc come along for free rather than needing a
     * doc-comment-matching special case.
     *
     * Returns null — meaning "seed the whole file" — when there is no anchor, when the anchor does
     * not fall inside the text (the file moved under a branch `ref` since discovery ran), or when
     * the slice would be the whole file anyway.
     */
    internal fun sliceDeclaration(text: String, bodyLine: Int?): String? {
      val lines = text.lines()
      val bounds = declarationLines(lines, bodyLine) ?: return null
      val start = bounds.first
      val end = bounds.last

      val headerEnd = headerEndExclusive(lines)
      if (headerEnd == 0 && start == 0 && end == lines.lastIndex) return null

      val header = lines.subList(0, headerEnd).joinToString("\n").trimEnd()
      val declaration = lines.subList(start, end + 1).joinToString("\n")
      val slice = if (header.isEmpty()) declaration else "$header\n\n$declaration"
      return slice.takeIf { it.trimEnd() != text.trimEnd() }
    }

    /**
     * The **line range** of the top-level declaration containing [bodyLine], 0-based and inclusive,
     * or null when the anchor cannot be trusted.
     *
     * The bounds rule is described at length on [sliceDeclaration], which is one of this function's
     * two callers; the other is [PreviewUsageIndex], which needs the same declaration boundaries to
     * say which calls in a file belong to which preview. Extracted rather than duplicated because a
     * second copy of "where does this declaration end" is exactly the kind of near-miss that shows
     * up as one preview quietly inheriting its neighbour's calls.
     *
     * Null means the anchor is unusable: absent, outside the text, pointing at a blank line (all
     * three say the file moved under the `ref` since discovery ran), or sitting at or above the
     * file header, where the outward scan has escaped past the imports. It does **not** mean "the
     * whole file" — that is [sliceDeclaration]'s own extra guard, which belongs to seeding an
     * editor buffer rather than to locating a declaration.
     */
    internal fun declarationLines(lines: List<String>, bodyLine: Int?): IntRange? {
      if (bodyLine == null) return null
      if (bodyLine < 1 || bodyLine > lines.size) return null
      if (lines[bodyLine - 1].isBlank()) return null

      // Up to the declaration this anchor sits in…
      var start = bodyLine - 1 // to 0-based
      while (start > 0 && !startsTopLevelDeclaration(lines, start)) start--
      // …and down to the last non-blank line before the next declaration begins.
      var next = start + 1
      while (next <= lines.lastIndex && !startsTopLevelDeclaration(lines, next)) next++
      var end = next - 1
      while (end > start && lines[end].isBlank()) end--

      // The declaration starting at or inside the header means the scan escaped upwards past the
      // imports — unusual formatting, and re-emitting the header would then duplicate lines.
      if (start < headerEndExclusive(lines)) return null
      return start..end
    }

    /**
     * Whether `lines[i]` begins a top-level declaration: non-blank, at **column 0**, and preceded
     * by a **blank** line (or the start of the file).
     *
     * Both conditions matter. Column 0 alone would match a top-level closing brace, ending the
     * declaration one line early. A preceding blank alone would match the first indented statement
     * after a blank line inside a body — the case that broke the first version of the slice.
     * Together they match what a reader would call the start of a declaration: its KDoc, its first
     * annotation, or its `fun`/`val`/`class` line.
     */
    private fun startsTopLevelDeclaration(lines: List<String>, i: Int): Boolean {
      val line = lines[i]
      if (line.isBlank()) return false
      if (line.first().isWhitespace()) return false
      return i == 0 || lines[i - 1].isBlank()
    }

    /**
     * Index of the first line that is part of a top-level declaration — everything before it is the
     * file header (`package`, `@file:` annotations, imports, and the blank lines and comments among
     * them).
     *
     * Anchored on the **last import**, then the `package` line, rather than on "the first line that
     * looks like a declaration": a file can open with a licence comment or a block comment that
     * mentions `fun`, and a header that swallowed the first declaration would be far worse than one
     * that stopped a few lines early. A file with neither — a script-like snippet — has no header,
     * which the caller handles.
     */
    private fun headerEndExclusive(lines: List<String>): Int {
      val lastImport = lines.indexOfLast { it.trimStart().startsWith("import ") }
      if (lastImport >= 0) return lastImport + 1
      val packageLine = lines.indexOfLast { it.trimStart().startsWith("package ") }
      return if (packageLine >= 0) packageLine + 1 else 0
    }

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
          if (!response.isSuccessful) null else response.body.byteStream().readNBytes(maxBytes + 1)
        }
      } catch (_: Exception) {
        null
      }
  }
}
