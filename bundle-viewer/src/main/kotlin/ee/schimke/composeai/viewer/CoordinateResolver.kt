package ee.schimke.composeai.viewer

import java.io.File
import java.security.MessageDigest

/**
 * Resolves a bundle's detached `maven` coordinates ([ClasspathEntry.Maven]) to local jar files so
 * the viewer can add them to a bundle's child classloader. Consumer side of schema v4's
 * content-addressing: a coordinate names *what* dependency a preview needs, and this finds the
 * bytes from whatever the machine already has.
 *
 * Mirrors `:cli`'s `CoordinateResolver` (duplicated rather than depended on, same convention as the
 * viewer's copy of `extractZipBytes`, so the viewer's module graph stays minimal):
 * - Local repos only — `~/.m2/repository` (Maven layout) and `~/.gradle/.../modules-2` (Gradle
 *   cache), overridable via `maven.repo.local` / `GRADLE_USER_HOME`. No network.
 * - When several local copies exist for one GAV, the v4 `sha256` picks the matching one.
 * - **Warn, never fail**: a miss or hash mismatch logs and still returns the best local jar (or
 *   none), so a preview renders with an almost-compatible dep rather than not at all.
 */
internal object CoordinateResolver {

  /** Resolve [coords] to local jars (name-sorted, misses dropped), warning on misses/mismatches. */
  fun resolve(
    coords: List<ClasspathEntry.Maven>,
    warn: (String) -> Unit = { System.err.println("compose-preview-viewer: $it") },
    repositoryRoots: List<File> = defaultRepositoryRoots(),
  ): List<File> = coords.mapNotNull { resolveOne(it, warn, repositoryRoots) }

  private fun resolveOne(
    coord: ClasspathEntry.Maven,
    warn: (String) -> Unit,
    roots: List<File>,
  ): File? {
    val candidates = locate(coord, roots)
    if (candidates.isEmpty()) {
      warn(
        "could not resolve ${coord.group}:${coord.artifact}:${coord.version} from a local " +
          "repository; the preview may fail if it needs this dependency."
      )
      return null
    }
    val expected = coord.sha256 ?: return candidates.first()
    candidates
      .firstOrNull { sha256Hex(it).equals(expected, ignoreCase = true) }
      ?.let {
        return it
      }
    val fallback = candidates.first()
    warn(
      "hash mismatch for ${coord.group}:${coord.artifact}:${coord.version} — using ${fallback.name} " +
        "anyway; the preview may differ slightly from the original."
    )
    return fallback
  }

  private fun locate(coord: ClasspathEntry.Maven, roots: List<File>): List<File> {
    val jarName = "${coord.artifact}-${coord.version}.jar"
    val found = mutableListOf<File>()
    for (root in roots) {
      if (!root.isDirectory) continue
      val mavenPath =
        File(root, coord.group.replace('.', '/') + "/${coord.artifact}/${coord.version}/$jarName")
      if (mavenPath.isFile) found += mavenPath
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

  private fun defaultRepositoryRoots(): List<File> {
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
