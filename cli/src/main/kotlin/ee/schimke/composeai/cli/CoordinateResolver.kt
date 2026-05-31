package ee.schimke.composeai.cli

import java.io.File
import java.security.MessageDigest

/**
 * Resolves a bundle's detached `maven` coordinates (#1632, Tier 3) into local jar files so a player
 * can put them on the classpath. This is the consumer side of schema v4's content-addressing: a
 * coordinate names *what* dependency the bundle needs, and the resolver finds the bytes from
 * whatever the machine already has.
 *
 * # Sources
 *
 * v1 resolves from **local repositories only** — no network. Two layouts are searched, in order:
 * 1. A Maven local repo (`~/.m2/repository`, standard `group/as/path/artifact/version/...` layout).
 * 2. The Gradle module cache (`~/.gradle/caches/modules-2/files-2.1`, which fans the file out under
 *    a per-sha1 subdirectory — we glob for the jar by name rather than guess the hash dir).
 *
 * Both roots are overridable via [repositoryRoots] (tests pass a temp dir; a future network fetcher
 * is just another root that populates a cache then resolves from it). A colleague who has built any
 * project using the same deps will already have them in one of these caches, so the common "someone
 * sent me a bundle" path works offline.
 *
 * # Verification — warn, never fail
 *
 * When a coordinate carries a v4 `sha256`, the resolved jar's bytes are hashed and compared. A
 * **mismatch is a loud warning, not an error**: a different artifact for the same coordinate is
 * usually almost compatible (point-release skew, a repackaged-but-equivalent jar), and a
 * slightly-off preview beats no preview. The resolver still returns the jar. A missing hash
 * (older/non-Gradle bundles) resolves silently as unverifiable. This mirrors the contract pinned on
 * [ee.schimke.composeai.cli.BundleReader.ClasspathEntry.Maven] / `PreviewBundleFormat`.
 *
 * The resolver itself never throws on a missing artifact either — it returns null and warns, so the
 * caller decides whether to proceed with a partial classpath (it should: the bundled renderer's
 * Compose stack covers the common surface).
 */
internal class CoordinateResolver(
  private val repositoryRoots: List<File> = defaultRepositoryRoots(),
  private val warn: (String) -> Unit = { System.err.println("compose-preview: $it") },
) {

  /** Outcome of resolving one coordinate. [file] is null when nothing was found locally. */
  data class Resolution(
    val coordinate: BundleReader.ClasspathEntry.Maven,
    val file: File?,
    val verified: Boolean,
    val mismatch: Boolean,
  )

  /**
   * Resolve [coords] to local jars, warning (never throwing) on misses and hash mismatches. Returns
   * one [Resolution] per input coordinate in order; callers typically take `mapNotNull { it.file }`
   * for the classpath and surface the misses to the user.
   */
  fun resolveAll(coords: List<BundleReader.ClasspathEntry.Maven>): List<Resolution> = coords.map {
    resolve(it)
  }

  fun resolve(coord: BundleReader.ClasspathEntry.Maven): Resolution {
    val candidates = locate(coord)
    if (candidates.isEmpty()) {
      warn(
        "could not resolve ${coord.group}:${coord.artifact}:${coord.version} from any local " +
          "repository (looked in ${repositoryRoots.joinToString { it.path }}); the preview may " +
          "fail to render if it needs this dependency. Re-pack with --embed-deps for an offline bundle."
      )
      return Resolution(coord, file = null, verified = false, mismatch = false)
    }
    val expected = coord.sha256
    if (expected == null) {
      // No hash to disambiguate with — first candidate, unverifiable.
      return Resolution(coord, file = candidates.first(), verified = false, mismatch = false)
    }
    // The hash exists precisely to pick the *right* copy when a GAV resolves to several local files
    // (a stale ~/.m2 copy + a Gradle cache entry, multiple Gradle hash dirs for a republished
    // module, …). Prefer a candidate whose bytes match before settling for a mismatch.
    candidates
      .firstOrNull { sha256Hex(it).equals(expected, ignoreCase = true) }
      ?.let {
        return Resolution(coord, file = it, verified = true, mismatch = false)
      }
    // Nothing matched. Warn, do NOT fail — the bytes are probably almost compatible (see the
    // verification contract). Fall back to the first candidate so the preview still renders.
    val fallback = candidates.first()
    warn(
      "hash mismatch for ${coord.group}:${coord.artifact}:${coord.version} — bundle expected " +
        "$expected but none of ${candidates.size} local copy(ies) matched (using ${fallback.name}, " +
        "${sha256Hex(fallback)}). Rendering with the local copy anyway; the preview may differ " +
        "slightly from the original."
    )
    return Resolution(coord, file = fallback, verified = false, mismatch = true)
  }

  /**
   * All candidate jars for [coord] across [repositoryRoots], in search order (Maven layout before
   * Gradle layout, roots in declared order). May hold more than one when the same GAV is present in
   * several caches or Gradle hash dirs — [resolve] uses the hash to pick among them.
   */
  private fun locate(coord: BundleReader.ClasspathEntry.Maven): List<File> {
    val jarName = "${coord.artifact}-${coord.version}.jar"
    val found = mutableListOf<File>()
    for (root in repositoryRoots) {
      if (!root.isDirectory) continue
      // Maven layout: <root>/<group as path>/<artifact>/<version>/<artifact>-<version>.jar
      val mavenPath =
        File(root, coord.group.replace('.', '/') + "/${coord.artifact}/${coord.version}/$jarName")
      if (mavenPath.isFile) found += mavenPath
      // Gradle modules-2 layout fans the jar under per-hash dirs; collect every match under
      // <root>/<group>/<artifact>/<version>/<hash>/<jarName>. One directory level only, so a huge
      // cache root doesn't turn this into a full filesystem scan.
      val gradleVersionDir = File(root, "${coord.group}/${coord.artifact}/${coord.version}")
      if (gradleVersionDir.isDirectory) {
        gradleVersionDir
          .listFiles()
          ?.asSequence()
          ?.filter { it.isDirectory }
          ?.mapNotNull { hashDir -> File(hashDir, jarName).takeIf { it.isFile } }
          ?.let { found += it }
      }
    }
    return found
  }

  private fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buf = ByteArray(64 * 1024)
      while (true) {
        val n = input.read(buf)
        if (n < 0) break
        digest.update(buf, 0, n)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  companion object {
    /**
     * Default local repositories searched when a caller doesn't pass its own. Honours the standard
     * `maven.repo.local` override and `GRADLE_USER_HOME`; falls back to the conventional `~/.m2`
     * and `~/.gradle` locations. Non-existent roots are simply skipped at lookup time.
     */
    fun defaultRepositoryRoots(): List<File> {
      val home = System.getProperty("user.home")?.let(::File)
      val roots = mutableListOf<File>()
      System.getProperty("maven.repo.local")?.let { roots += File(it) }
      if (home != null) roots += File(home, ".m2/repository")
      val gradleHome =
        System.getenv("GRADLE_USER_HOME")?.let(::File) ?: home?.let { File(it, ".gradle") }
      if (gradleHome != null) roots += File(gradleHome, "caches/modules-2/files-2.1")
      return roots
    }
  }
}
