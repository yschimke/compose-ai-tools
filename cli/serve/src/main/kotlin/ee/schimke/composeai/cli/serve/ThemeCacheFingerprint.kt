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
 *   same classpath, and they do not agree pixel-for-pixel. What is deliberately **not** keyed on,
 *   and why:
 * - **The tool version.** It used to be, and that made a release throw away every warmed render on
 *   the box. Measured on preview.coo.ee, whose theme optimization needs the better part of a day to
 *   fill 18,604 entries: four versions shipped inside four hours, each one starting the pass again
 *   from zero, so the cache could never be adopted once. A cache that is invalidated faster than it
 *   can be filled is not a cache.
 *
 *   The version was never proof of anything — it stood *proxy* for the renderer and the container
 *   image (the JVM, Skia, the installed fonts), none of which are visible from here, and the class
 *   doc above already conceded that proxying is an assumption rather than a proof: a base-image
 *   bump shipping without a release slipped through it either way. So it was a key that failed open
 *   on the case it was supposed to cover, while failing closed on every case it was not.
 *
 *   What actually catches a renderer that moved is [CatalogThemeCache.verifySample], which
 *   re-renders a sample of the adopted entries against the running renderer and discards the whole
 *   generation on any mismatch — the same net that was always covering the unenumerated inputs.
 *   Crossing a version boundary is now exactly that case: every entry reads as adopted (it came
 *   from another process), so it is withheld from the read path until the sample settles. A version
 *   that renders differently costs a re-warm; it does not serve a wrong pixel.
 *
 *   The version is still recorded in the generation manifest, so which build last wrote a
 *   generation stays answerable, and `--theme-cache-evict` discards the store outright for the case
 *   where an operator *knows* the pixels moved and does not want to wait for a sample to notice.
 * - **The render config.** The server-side defaults that never appear in a cache key — density,
 *   default device, font scale, image encoding. The easiest inputs to forget precisely *because*
 *   they are absent from the key, so they are named explicitly by the caller.
 *
 * Nothing here is asserted by hand: change the renderer and the version moves, change the catalog
 * and the classpath moves. There is no cache-version constant to remember to bump, because a
 * constant someone must remember is a constant that will eventually be wrong.
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
  ): String? {
    if (classpath.isEmpty() || classpath.size > MAX_CLASSPATH_ENTRIES) return null
    val digest = MessageDigest.getInstance("SHA-256")
    digest.line("schema", SCHEMA)
    digest.line("variant", variant)
    // NOT the tool version — see the class doc. A release must not orphan the warmed renders; the
    // load-time sample verification is what covers a renderer that actually moved.
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
   */
  private const val SCHEMA = "theme-cache/1"
}
