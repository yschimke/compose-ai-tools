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
 * - **The tool version.** Covers the renderer, and stands proxy for the container image — hence the
 *   JVM, Skia and the installed fonts, none of which are visible from here. **That proxying is an
 *   assumption, not a proof:** a base-image bump that ships without a release would slip through
 *   it. It is recorded in the generation manifest so a mismatch is at least explainable after the
 *   fact, and the sample verification is what actually catches it.
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
    toolVersion: String,
    renderConfig: String,
  ): String? {
    if (classpath.isEmpty() || classpath.size > MAX_CLASSPATH_ENTRIES) return null
    val digest = MessageDigest.getInstance("SHA-256")
    digest.line("schema", SCHEMA)
    digest.line("variant", variant)
    digest.line("version", toolVersion)
    digest.line("renderConfig", renderConfig)
    // Sorted by NAME, not by the order the descriptor happens to list them: a classpath reordered
    // between loads is the same classpath, and a fingerprint that disagreed would throw away a
    // whole generation's warming for nothing.
    val entries = classpath.sortedBy { it.name }
    for (entry in entries) {
      val hash = hashFile(entry) ?: return null
      digest.line("entry", "${entry.name}:$hash")
    }
    return digest.digest().hex()
  }

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
