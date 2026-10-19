package ee.schimke.composeai.cli

import java.io.File
import java.util.concurrent.Executors
import java.util.zip.ZipFile

/** A runtime classpath with the jars the render JVM can never load a class from taken out. */
internal data class PrunedClasspath(
  val jars: List<File>,
  /** Jars holding no class or resource: KMP stub jars, AAR wrappers of resource-only libraries. */
  val empty: List<File>,
  /** Per-library R jars whose every class the final-ID R jar also defines, and so shadows. */
  val supersededRJars: List<File>,
)

private val R_CLASS = Regex("""(^|/)R(\$[^/]*)?\.class$""")

/** Metadata that matters to a compiler or a Maven client, never to a class loader. */
private fun isInertEntry(name: String): Boolean =
  name.endsWith("/") ||
    name == "META-INF/MANIFEST.MF" ||
    name == "META-INF/INDEX.LIST" ||
    (name.startsWith("META-INF/") && name.endsWith(".kotlin_module")) ||
    name.startsWith("META-INF/maven/")

/** Entry names of [jar], or null when it cannot be read as one (it is then kept as is). */
private fun entryNames(jar: File): List<String>? = runCatching {
  ZipFile(jar).use { zip -> zip.entries().asSequence().map { it.name }.toList() }
}
  .getOrNull()

private enum class JarKind {
  KEEP,
  EMPTY,
  SUPERSEDED,
}

/**
 * What [jar] contributes, stopping at its first entry that settles it: any class or resource
 * [finalRClasses] does not define means [JarKind.KEEP], which for a library jar is its first class.
 * Listing every entry of each of thousands of jars is what made pruning slow (#6111). A jar that
 * cannot be read is kept.
 */
private fun classify(jar: File, finalRClasses: Set<String>): JarKind = runCatching {
  ZipFile(jar).use { zip ->
    var sawContent = false
    for (entry in zip.entries()) {
      val name = entry.name
      if (isInertEntry(name)) continue
      if (name !in finalRClasses) return@use JarKind.KEEP
      sawContent = true
    }
    if (sawContent) JarKind.SUPERSEDED else JarKind.EMPTY
  }
}
  .getOrDefault(JarKind.KEEP)

/** Jars read at once: enough to overlap a remote or lazily-fetched filesystem's latency. */
private const val SCAN_THREADS = 32

/**
 * Drops the [runtime] jars that contribute nothing: empty ones, and, when [rJar] is set, each
 * library's own R jar whose every class [rJar] defines too. [rJar] comes first on the classpath, so
 * those classes never load from the library's jar (whose non-final IDs name nothing in the linked
 * APK anyway). A Bazel Android target carries one of each per AAR: on compose-ai-contrib's
 * bazel-apk, 115 of 175 runtime jars.
 *
 * Jars are read [SCAN_THREADS] at a time: opening one on a FUSE or remote-backed `bazel-out` is a
 * round trip, and 4,000 of them in sequence took over ten minutes (#6111). The result keeps
 * [runtime]'s order.
 *
 * [keep] (the project's own jars and [rJar]) is never dropped, nor is any jar that cannot be read.
 */
internal fun pruneRuntimeClasspath(
  runtime: List<File>,
  keep: Set<File>,
  rJar: File?,
): PrunedClasspath {
  val finalRClasses =
    rJar?.let(::entryNames)?.filterTo(HashSet()) { R_CLASS.containsMatchIn(it) }.orEmpty()
  val toScan = runtime.filter { it !in keep && it != rJar }.distinct()
  val kinds = HashMap<File, JarKind>()
  if (toScan.isNotEmpty()) {
    val pool =
      Executors.newFixedThreadPool(minOf(SCAN_THREADS, toScan.size)) { task ->
        Thread(task, "classpath-scan").apply { isDaemon = true }
      }
    try {
      toScan
        .map { jar -> jar to pool.submit<JarKind> { classify(jar, finalRClasses) } }
        .forEach { (jar, kind) -> kinds[jar] = kind.get() }
    } finally {
      pool.shutdownNow()
    }
  }
  val jars = mutableListOf<File>()
  val empty = mutableListOf<File>()
  val superseded = mutableListOf<File>()
  for (jar in runtime) {
    when (kinds[jar] ?: JarKind.KEEP) {
      JarKind.KEEP -> jars += jar
      JarKind.EMPTY -> empty += jar
      JarKind.SUPERSEDED -> superseded += jar
    }
  }
  return PrunedClasspath(jars, empty, superseded)
}
