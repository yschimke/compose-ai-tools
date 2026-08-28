package ee.schimke.composeai.cli.serve

import java.io.File
import java.security.MessageDigest

/**
 * Content identity of one *renderable catalog generation* — everything that decides what a theme
 * render's pixels look like, reduced to a string.
 *
 * ### Why a cache key is not enough
 *
 * [ServeOverrides.cacheKey] identifies **what was asked for** — a preview id plus its overrides.
 * That is sufficient while the cache lives inside one process holding exactly one generation of one
 * catalog, and insufficient the moment an entry outlives the thing that rendered it. A persisted
 * entry does exactly that, so it needs a second half: **what produced it**.
 *
 * ### The rule this is built on
 *
 * You cannot enumerate every input with confidence, so the design is not "list the inputs and
 * hope". It is: key on the coarsest cheap thing that *provably* covers content and renderer, and
 * let anything unenumerated be caught by [CatalogThemeCache]'s load-time sample verification rather
 * than served as a wrong pixel.
 *
 * What goes in, and why each one:
 * - **The classpath's bytes.** Not its paths — a load stages the same bundle into a fresh directory
 *   every time, so paths churn while content does not. Hashing the actual jars covers the catalog's
 *   code, its theme definitions, its resources *and* its dependencies in one move, without needing
 *   to know which of them a given preview touches. This is the expensive part and it is the part
 *   worth paying for: it is the only input that is derived from the thing itself rather than
 *   asserted about it.
 * - **The daemon variant.** Desktop and Android/Robolectric are different renderers reading the
 *   same classpath, and they do not agree pixel-for-pixel.
 * - **The render environment** — the JVM, the rasteriser shared objects and the installed system
 *   fonts, none of which the classpath can reach. See [ThemeCacheFingerprint.rendererIdentity] for
 *   what is read and why it is stat rather than bytes. This is the input the tool version used to
 *   stand proxy for, keyed on directly.
 * - **The render config.** The server-side defaults that never appear in a cache key — density,
 *   default device, font scale, image encoding. The easiest inputs to forget precisely *because*
 *   they are absent from the key, so they are named explicitly by the caller.
 *
 * What is deliberately **not** keyed on, and why:
 * - **The tool version.** It used to be, and that made a release throw away every warmed render on
 *   the box. Measured on preview.coo.ee, whose theme optimization needs the better part of a day to
 *   fill 18,604 entries: four versions shipped inside four hours, each one starting the pass again
 *   from zero, so the cache could never be adopted once. A cache that is invalidated faster than it
 *   can be filled is not a cache.
 *
 *   The version was never proof of anything — it stood *proxy* for the renderer and the container
 *   image (the JVM, Skia, the installed fonts), none of which were visible from here, and the class
 *   doc above already conceded that proxying is an assumption rather than a proof: a base-image
 *   bump shipping without a release slipped through it either way. So it was a key that failed open
 *   on the case it was supposed to cover, while failing closed on every case it was not.
 *
 *   [rendererIdentity] is what replaced it, and the substitution is the point: it moves when the
 *   *image* moves and holds still when only the build number does, which is the exact opposite of
 *   the version on both halves. Four builds shipped out of one base image share a generation; one
 *   build shipped on a bumped base image does not.
 *
 *   Behind that sits [CatalogThemeCache.verifySample], which re-renders a sample of the adopted
 *   entries against the running renderer and discards the whole generation on any mismatch. It is
 *   the net for inputs nobody enumerated, and it is a *sample* — the first five sorted adopted keys
 *   — so it is a backstop and not a substitute for keying on an input that can be named. Anything
 *   the key covers is caught structurally, before a wrong pixel is ever a candidate.
 *
 *   The version is still recorded in the generation manifest — as the build that last **opened**
 *   the generation, see [GenerationInputs.toolVersion] — and `--theme-cache-evict` discards the
 *   store outright for the case where an operator *knows* the pixels moved and does not want to
 *   wait for a sample to notice.
 *
 * Nothing here is asserted by hand: change the image and the renderer identity moves, change the
 * catalog and the classpath moves. There is no cache-version constant to remember to bump, because
 * a constant someone must remember is a constant that will eventually be wrong.
 */
object ThemeCacheFingerprint {

  /**
   * How many classpath entries will be hashed before this gives up and returns null.
   *
   * A bound rather than a best effort: a descriptor with an implausible classpath is a descriptor
   * this does not understand, and the safe answer to "I do not understand this" is to decline to
   * persist rather than to persist under an identity that might not be one.
   */
  const val MAX_CLASSPATH_ENTRIES: Int = 8192

  /** Read size for hashing a classpath entry. */
  private const val BUFFER_BYTES = 1 shl 16

  /**
   * Fingerprint the generation a daemon launched with [classpath] and [variant] will render, or
   * null when it cannot be established.
   *
   * **Null is a first-class answer, and it means "do not persist".** A missing or unreadable
   * classpath entry, or an implausible number of them, leaves the generation's identity unknown —
   * and an unknown identity must never be invented, because every wrong pixel this cache could
   * possibly serve begins with two different generations agreeing on a name.
   */
  fun of(
    classpath: List<File>,
    variant: String,
    renderConfig: String,
    /**
     * Digest of the catalog-id to daemon-preview routing this generation renders through.
     *
     * Persisted keys name the **published catalog** preview id, but a render resolves that id
     * through the alias map before it reaches a daemon. That map comes from the catalog manifest,
     * not the bundle — so a delivery-branch update can repoint an id at a different daemon preview
     * while shipping a byte-identical executable bundle. Same classpath, same key, different
     * pixels, and a five-entry verification sample would very likely miss the one row that moved.
     */
    routing: String = "",
    /**
     * Digest of the render *environment* this generation's pixels come out of — see
     * [rendererIdentity].
     *
     * The one input the classpath cannot reach. Everything else here is derived from files the
     * catalog carries; this is derived from the container image underneath them, and a base image
     * that swaps the JVM, freetype or the installed fonts moves the pixels without moving a single
     * byte of the classpath.
     */
    renderer: String = currentRendererIdentity,
  ): String? {
    if (classpath.isEmpty() || classpath.size > MAX_CLASSPATH_ENTRIES) return null
    val digest = MessageDigest.getInstance("SHA-256")
    digest.line("schema", SCHEMA)
    digest.line("variant", variant)
    // NOT the tool version — see the class doc. A release must not orphan the warmed renders; the
    // load-time sample verification is what covers a renderer that actually moved. The *renderer*
    // is keyed on directly instead, which is the thing the version only ever stood proxy for.
    digest.line("renderer", renderer)
    digest.line("renderConfig", renderConfig)
    digest.line("routing", routing)
    // Hashed in the order the descriptor lists them, NOT sorted. Classpath order is semantically
    // significant: when two entries carry the same class or resource the JVM resolves the earlier
    // one, so a reordering with identical bytes can genuinely change the pixels. Sorting made both
    // orders one generation, which would let a render be reused from the wrong resolution order.
    // The cost of being order-sensitive is a re-warm if the order ever churns for no reason; the
    // cost of being order-blind is wrong pixels, and only one of those is a correctness bug.
    for (entry in classpath) {
      val hash = hashFile(entry) ?: return null
      digest.line("entry", "${entry.name}:$hash")
    }
    return digest.digest().hex()
  }

  /**
   * Everything a daemon launched with this descriptor will actually load, in load order — the
   * parent [classpath] **and** the user classpath carried in [systemProperties].
   *
   * The user half is not a detail, it is the catalog itself. `ServeBundleDaemon.splitBundleRuntime`
   * puts the bundle's own `classes/` directory, its rehydrated external resources and its child
   * dependency jars into `composeai.daemon.userClassDirs`, leaving `classpath` holding only parent
   * overlays and daemon sidecars. Fingerprinting the parent alone therefore gives two catalog
   * revisions with unchanged framework dependencies the *same* name, and the new revision adopts
   * the old one's pixels — the exact collision this whole mechanism exists to prevent.
   *
   * Only the contents of these paths are ever hashed, never the paths themselves, so the fresh
   * staging directory each load creates does not invent a new generation.
   */
  fun renderedClasspath(
    classpath: List<String>,
    systemProperties: Map<String, String>,
    extraPayloads: List<String> = emptyList(),
  ): List<File> =
    classpath.map(::File) +
      (systemProperties[USER_CLASS_DIRS_PROPERTY]
        ?.split(File.pathSeparator)
        ?.filter { it.isNotBlank() }
        ?.map(::File)
        .orEmpty()) +
      // Captured payloads the daemon renders FROM rather than executes: the extracted IR/Remote
      // Compose documents and the bundle manifest. `BundleIrReplayStore` reads these bytes to
      // produce the scene, so a bundle that regenerates a capture without touching a single class
      // renders differently — and would otherwise carry the previous generation's name.
      (PAYLOAD_PROPERTIES.mapNotNull { key -> systemProperties[key]?.takeIf { it.isNotBlank() } } +
          CONTENT_PATH_PROPERTIES.mapNotNull { key ->
            systemProperties[key]?.takeIf { it.isNotBlank() }
          } +
          extraPayloads.filter { it.isNotBlank() })
        .distinct()
        .map(::File)

  /**
   * The render-affecting launch settings that are *values* rather than file contents, as a stable
   * string for [of]'s `renderConfig`.
   *
   * Excluding the descriptor's whole system-property map was wrong, and wrong in the same way as
   * excluding the user classpath was: most of it is absolute paths that a fresh staging directory
   * churns every load, but not all of it. `composeai.fonts.offline`, `composeai.svg.embedFonts` and
   * the Android launch's `robolectric.*` settings all change what the renderer produces while the
   * classpath stays byte-identical — offline font resolution, for instance, substitutes fallback
   * glyphs for downloaded faces.
   *
   * So the filter is on the *shape of the value*, not on the map: a value that names a filesystem
   * path is dropped (its churn is meaningless, and where its contents matter they are hashed by
   * [renderedClasspath] instead), and everything else is kept. A setting that legitimately varies
   * per load and is not a path will cost an unnecessary re-warm, which is the right direction to
   * err.
   */
  fun renderConfig(systemProperties: Map<String, String>, jvmArgs: List<String>): String {
    val covered = PAYLOAD_PROPERTIES.toSet() + CONTENT_PATH_PROPERTIES + USER_CLASS_DIRS_PROPERTY
    val settings =
      systemProperties
        .filterKeys { it !in covered }
        .filterValues { !looksLikePath(it) }
        .entries
        .sortedBy { it.key }
        .joinToString(" ") { (key, value) -> "$key=$value" }
    val args = jvmArgs.filterNot(::looksLikePath).sorted().joinToString(" ")
    return listOf(settings, args).filter { it.isNotBlank() }.joinToString(" ")
  }

  /**
   * Whether [value] names a filesystem location, and is therefore churn rather than configuration.
   *
   * Deliberately crude: an absolute path, or a JVM argument carrying one. Both a false positive
   * (dropping a real setting) and a false negative (keeping a path) fail toward a re-warm rather
   * than a wrong pixel, because anything genuinely load-bearing about a path's *contents* is hashed
   * by [renderedClasspath].
   */
  private fun looksLikePath(value: String): Boolean =
    value.startsWith(File.separator) ||
      value.contains("=${File.separator}") ||
      Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(value)

  /**
   * Digest of the render environment — the container image's half of what produces a pixel.
   *
   * ### The hole this fills
   *
   * Dropping the tool version from [of] was right for the reason the class doc gives, but it left
   * the thing the version stood *proxy* for uncovered: the JVM, the native rasteriser and the
   * installed fonts all live in the image rather than in the catalog, so a base-image bump changes
   * the pixels while every hashed input holds still. The only remaining net was
   * [CatalogThemeCache.verifySample], and that net has a known-size hole — it re-renders the first
   * five sorted adopted keys, so a change touching any of the other 18,599 entries passes the
   * sample and the whole generation is then trusted. Keying on the environment directly closes the
   * case rather than sampling for it: a moved renderer gets a different generation directory and
   * nothing is adopted at all.
   *
   * Unlike the version, this does **not** move on a release. Four builds shipped in four hours out
   * of the same base image produce four identical identities, so the pathology that got the version
   * removed does not come back.
   *
   * ### What it reads, and why stat rather than bytes
   *
   * - **The JVM**, from its own system properties. `eclipse-temurin:21.0.12_8` and `21.0.13_11` are
   *   different text here, which is the whole requirement.
   * - **The architecture**, because the native halves of both renderers are per-arch binaries.
   * - **The system font inventory** — every file under the standard font roots, by relative path
   *   and size. Not by content: these are hundreds of megabytes on a fontconfig-equipped image and
   *   this runs on the catalog-load path. Not by mtime either, because a package layer rebuilt from
   *   identical sources restamps mtimes and that would reintroduce churn-per-build. A base image
   *   that swaps or upgrades a font package moves a name or a size; one that does not, does not.
   *   (The *downloadable* font cache is a different thing and is already hashed by content — see
   *   [CONTENT_PATH_PROPERTIES].)
   * - **The rasteriser libraries** — freetype, fontconfig and harfbuzz, by name and size. A
   *   freetype bump changes glyph rasterisation on the Android/Robolectric path with nothing else
   *   moving at all, and it is the same cheap stat walk.
   *
   * Anything unreadable is simply recorded as absent rather than failing the fingerprint: unlike a
   * classpath entry, a missing font root is the ordinary state of most machines, and refusing to
   * persist on a developer laptop with no `/usr/share/fonts` would be a bug rather than caution.
   */
  fun rendererIdentity(
    systemProperties: Map<String, String> = jvmProperties(),
    fontRoots: List<File> = DEFAULT_FONT_ROOTS,
    libraryRoots: List<File> = DEFAULT_LIBRARY_ROOTS,
  ): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.line("schema", SCHEMA)
    for (key in RENDERER_PROPERTIES) digest.line(key, systemProperties[key].orEmpty())
    for (root in fontRoots) {
      digest.line("fonts", inventory(root, FONT_ROOT_DEPTH) { true })
    }
    for (root in libraryRoots) {
      digest.line("libs", inventory(root, LIBRARY_ROOT_DEPTH) { it in RASTERISER_LIBRARIES })
    }
    return digest.digest().hex()
  }

  /**
   * `<relative path>:<size>` for every accepted file under [root], sorted, or `""` when [root] is
   * not there.
   *
   * Two bounds, and both of them are about not hanging a catalog load rather than about tidiness:
   * - **[maxDepth]**, because a font root is a shallow tree and a library root is one directory,
   *   while `/usr/lib` beneath it is neither. Recursing it would stat tens of thousands of files to
   *   find three.
   * - **[MAX_CLASSPATH_ENTRIES] against files VISITED, not files kept.** Counting only the kept
   *   ones would let a filtered walk — the library one, which keeps almost nothing — run without
   *   limit down a symlink loop, and `walkTopDown` follows directory symlinks. The overflow answer
   *   is the root's name alone: stable across boots, and honestly saying "this root is bigger than
   *   I will read" rather than a truncated inventory that would look like an identity while
   *   truncating somewhere different each time.
   */
  private fun inventory(root: File, maxDepth: Int, accept: (String) -> Boolean): String =
    runCatching {
      if (!root.isDirectory) return@runCatching ""
      val entries = mutableListOf<String>()
      var visited = 0
      for (file in root.walkTopDown().maxDepth(maxDepth)) {
        if (file.isDirectory) continue
        if (++visited > MAX_CLASSPATH_ENTRIES) return@runCatching "${root.name}:overflow"
        if (!accept(file.name)) continue
        entries += "${file.relativeTo(root).invariantPath()}:${file.length()}"
      }
      entries.sorted().joinToString("\n")
    }
    // An unreadable root is "nothing here", not a reason to decline to persist — see
    // [rendererIdentity].
    .getOrDefault("")

  /** Deep enough for `/usr/share/fonts/truetype/<family>/<face>.ttf` and its usual variations. */
  private const val FONT_ROOT_DEPTH = 6

  /** A rasteriser shared object sits directly in its library directory; below that is not ours. */
  private const val LIBRARY_ROOT_DEPTH = 1

  /**
   * [rendererIdentity] for the process this is running in, computed once.
   *
   * Cached because it walks the font roots and a multi-module catalog fingerprints once per bundle,
   * so an uncached default would stat the same few thousand files several times per load for an
   * answer that cannot change without the process being replaced.
   */
  val currentRendererIdentity: String by lazy { rendererIdentity() }

  /** The JVM's own description of itself, for [rendererIdentity]. */
  private fun jvmProperties(): Map<String, String> = RENDERER_PROPERTIES.associateWith {
    System.getProperty(it).orEmpty()
  }

  /**
   * System properties naming the renderer's environment rather than the catalog's configuration.
   *
   * Deliberately not `java.home` or any other path: the value would churn across hosts that render
   * identically. What is wanted is the JVM's *identity*, and these three carry it.
   */
  val RENDERER_PROPERTIES: List<String> =
    listOf("java.vm.vendor", "java.vm.version", "java.runtime.version", "os.arch")

  /** Where a Linux/macOS/Windows image keeps the fonts fontconfig will hand the rasteriser. */
  val DEFAULT_FONT_ROOTS: List<File> =
    listOf(
        "/usr/share/fonts",
        "/usr/local/share/fonts",
        "/System/Library/Fonts",
        "/Library/Fonts",
        System.getProperty("user.home")?.let { "$it/.fonts" },
        System.getProperty("user.home")?.let { "$it/.local/share/fonts" },
        System.getenv("WINDIR")?.let { "$it\\Fonts" },
      )
      .filterNotNull()
      .map(::File)

  /** Where the rasteriser shared objects named in [RASTERISER_LIBRARIES] live. */
  val DEFAULT_LIBRARY_ROOTS: List<File> =
    listOf("/usr/lib/${System.getProperty("os.arch") ?: ""}-linux-gnu", "/usr/lib", "/usr/lib64")
      .map(::File)

  /**
   * Shared objects whose version decides how a glyph is rasterised.
   *
   * Matched on the **soname**, not on a prefix. A prefix match would also take
   * `libfreetype.so.6.18.3`, and Debian ships that as a real file beside the `libfreetype.so.6`
   * symlink pointing at it — so a package upgrade that renames the versioned file would move the
   * fingerprint twice over, while the soname link the loader actually resolves already carries the
   * change through its own size.
   */
  val RASTERISER_LIBRARIES: Set<String> =
    setOf(
      "libfreetype.so",
      "libfreetype.so.6",
      "libfontconfig.so",
      "libfontconfig.so.1",
      "libharfbuzz.so",
      "libharfbuzz.so.0",
    )

  /** Stable digest of a catalog-id to daemon-id map, for [of]'s `routing`. */
  fun routingDigest(alias: Map<String, String>): String {
    if (alias.isEmpty()) return ""
    val digest = MessageDigest.getInstance("SHA-256")
    digest.line("schema", SCHEMA)
    // Sorted by catalog id: the map's iteration order is not part of what it means.
    alias.entries.sortedBy { it.key }.forEach { (id, daemonId) -> digest.line(id, daemonId) }
    return digest.digest().hex()
  }

  /** Where the daemon launch carries the catalog's own classes — see [renderedClasspath]. */
  const val USER_CLASS_DIRS_PROPERTY: String = "composeai.daemon.userClassDirs"

  /**
   * Launch properties naming rendered-from *content* rather than executed code.
   *
   * Kept as a list because the failure they share is silent: each one is a path in a system
   * property rather than a classpath entry, so anything that only reads `classpath` misses it and
   * two genuinely different generations agree on a name.
   */
  val PAYLOAD_PROPERTIES: List<String> =
    listOf(
      "composeai.daemon.irDir",
      "composeai.daemon.bundleManifestPath",
      // The preview manifest carries each preview's discovery-time render spec — dimensions,
      // density, device, declared themes. A repacked catalog can change any of those while keeping
      // an identical classpath and identical alias routing, and the cache key is theme-only, so the
      // new host would adopt the old pixels. The five-entry sample only catches it if the changed
      // preview happens to be one of the five.
      "composeai.daemon.previewsJsonPath",
    )

  /** Path-valued launch settings whose directory contents directly affect rendered pixels. */
  val CONTENT_PATH_PROPERTIES: Set<String> = setOf("composeai.fonts.cacheDir")

  /**
   * Fold several module fingerprints into one.
   *
   * A multi-module catalog renders from several bundles at once, and its generation is all of them
   * together — any one changing changes what a visitor sees. Sorted so the module order a caller
   * happens to assemble does not invent a new generation.
   */
  fun combine(parts: List<String>): String? {
    if (parts.isEmpty() || parts.any { it.isBlank() }) return null
    if (parts.size == 1) return parts.single()
    val digest = MessageDigest.getInstance("SHA-256")
    digest.line("schema", SCHEMA)
    parts.sorted().forEach { digest.line("part", it) }
    return digest.digest().hex()
  }

  /** Content hash of one classpath entry, or null when it is missing or unreadable. */
  private fun hashFile(file: File): String? {
    if (!file.isFile) {
      // A DIRECTORY on the classpath — exploded classes — is hashed by walking it, because that is
      // exactly what a from-source catalog puts there and skipping it would fingerprint a
      // generation by its dependencies alone.
      if (file.isDirectory) return hashDirectory(file)
      return null
    }
    return runCatching {
      val digest = MessageDigest.getInstance("SHA-256")
      file.inputStream().use { stream ->
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
          val read = stream.read(buffer)
          if (read <= 0) break
          digest.update(buffer, 0, read)
        }
      }
      digest.digest().hex()
    }
      .getOrNull()
  }

  private fun hashDirectory(dir: File): String? = runCatching {
    val digest = MessageDigest.getInstance("SHA-256")
    val files =
      dir.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(dir).invariantPath() }
    var count = 0
    for (file in files) {
      if (++count > MAX_CLASSPATH_ENTRIES) return null
      val hash = hashFile(file) ?: return null
      digest.line("file", "${file.relativeTo(dir).invariantPath()}:$hash")
    }
    // An empty directory is legitimate but carries no identity of its own; folding the count in
    // keeps two differently-empty classpaths from colliding.
    digest.line("files", count.toString())
    digest.digest().hex()
  }
    .getOrNull()

  /**
   * Feed one labelled field, length-prefixed.
   *
   * Length prefixes rather than a separator character: any separator can appear inside a file name,
   * and `a|b` + `c` must not hash the same as `a` + `b|c`.
   */
  private fun MessageDigest.line(label: String, value: String) {
    val bytes = value.toByteArray()
    update(label.toByteArray())
    update(bytes.size.toString().toByteArray())
    update(0)
    update(bytes)
    update(0)
  }

  private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

  private fun File.invariantPath(): String = path.replace(File.separatorChar, '/')

  /**
   * Bumped only when the fingerprint's own *composition* changes in a way that should invalidate
   * everything already on disk. Not a cache version anyone has to remember for ordinary changes —
   * the inputs cover those on their own.
   *
   * `/2` folded the render environment in ([rendererIdentity]). Every generation written under `/1`
   * was named without it, so none of them can be adopted: on a box mid-warm that is one re-warm,
   * paid once, for a key that stops depending on a five-entry sample to notice the image moved.
   */
  private const val SCHEMA = "theme-cache/2"
}
