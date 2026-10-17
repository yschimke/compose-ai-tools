package ee.schimke.composeai.cli

import java.io.File
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

/**
 * Drops the [runtime] jars that contribute nothing: empty ones, and, when [rJar] is set, each
 * library's own R jar whose every class [rJar] defines too. [rJar] comes first on the classpath, so
 * those classes never load from the library's jar (whose non-final IDs name nothing in the linked
 * APK anyway). A Bazel Android target carries one of each per AAR: on compose-ai-contrib's
 * bazel-apk, 115 of 175 runtime jars.
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
  val jars = mutableListOf<File>()
  val empty = mutableListOf<File>()
  val superseded = mutableListOf<File>()
  for (jar in runtime) {
    val names = if (jar in keep || jar == rJar) null else entryNames(jar)
    val content = names?.filterNot(::isInertEntry)
    when {
      content == null -> jars += jar
      content.isEmpty() -> empty += jar
      finalRClasses.isNotEmpty() && content.all { it in finalRClasses } -> superseded += jar
      else -> jars += jar
    }
  }
  return PrunedClasspath(jars, empty, superseded)
}
