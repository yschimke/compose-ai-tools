package ee.schimke.composeai.cli

import ee.schimke.composeai.io.SystemFileSystem
import okio.FileSystem
import okio.Path

/**
 * Which Bazel packages could hold a `@Preview`, judged from their source files on disk (#6162).
 *
 * In a workspace split into many small libraries, a pattern like `//app/...` matches data,
 * view-model and app-wiring libraries alongside the few UI ones, and building each through the
 * aspect costs its whole transitive graph, resource linking included, only to report no previews. A
 * package whose `.kt` / `.java` sources never mention `Preview` cannot declare one: a `@Preview`, a
 * multi-preview annotation and a `@PreviewParameter` all spell it. Such packages are skipped before
 * anything is built, and when an explicit target turns out to hold none, the packages under it that
 * do mention it are suggested.
 *
 * A package is its directory down to, not into, any sub-directory with its own `BUILD` file, as
 * Bazel draws it. Only the main repository is read; a target in an external repository is never
 * filtered.
 */
internal class BazelPreviewSources(
  private val root: Path,
  private val fileSystem: FileSystem = SystemFileSystem,
) {
  private val mentions = HashMap<String, Boolean>()

  /** Whether [label]'s package might declare a preview; true for anything this cannot judge. */
  fun mayHavePreviews(label: String): Boolean {
    val pkg = mainRepoPackage(label) ?: return true
    return mentions.getOrPut(pkg) { packageMentionsPreview(pkg) }
  }

  /**
   * [targets] without those [mayHavePreviews] rules out, unless that would leave none: then all of
   * them, since a filter that empties a selection has more likely missed a spelling than found a
   * workspace with no previews. [kept] are kept whatever their package holds.
   */
  fun filter(targets: List<String>, kept: Set<String>, log: (String) -> Unit): List<String> {
    val (candidates, skipped) = targets.partition { it in kept || mayHavePreviews(it) }
    if (skipped.isEmpty()) return targets
    if (candidates.isEmpty()) {
      log(
        "[bazel] no source under ${skipped.size} matched targets mentions Preview; building them " +
          "all anyway"
      )
      return targets
    }
    val packages = skipped.mapNotNull(::mainRepoPackage).distinct().sorted()
    log(
      "[bazel] skipping ${skipped.size} of ${targets.size} matched targets: no source in " +
        packages.joinToString(", ") { "//$it" } +
        " mentions Preview. Name a target explicitly to build it anyway."
    )
    return candidates
  }

  /**
   * Packages at or below [label]'s package, other than its own, whose sources mention `Preview`:
   * where to look when [label] itself held none. At most [limit], nearest first.
   */
  fun previewPackagesBelow(label: String, limit: Int = 5): List<String> {
    val pkg = mainRepoPackage(label) ?: return emptyList()
    val start = if (pkg.isEmpty()) root else root / pkg
    if (fileSystem.metadataOrNull(start)?.isDirectory != true) return emptyList()
    val found = mutableListOf<String>()
    val queue = ArrayDeque(listOf(start))
    while (queue.isNotEmpty() && found.size < limit) {
      val dir = queue.removeFirst()
      for (child in children(dir)) {
        if (!isDirectory(child) || skipDirectory(child)) continue
        if (isPackage(child)) {
          val childPkg = child.relativeTo(root).segments.joinToString("/")
          if (mentions.getOrPut(childPkg) { packageMentionsPreview(childPkg) }) found += childPkg
        }
        queue += child
      }
    }
    return found.take(limit)
  }

  private fun packageMentionsPreview(pkg: String): Boolean {
    val dir = if (pkg.isEmpty()) root else root / pkg
    if (fileSystem.metadataOrNull(dir)?.isDirectory != true) return true
    val pending = ArrayDeque(listOf(dir))
    while (pending.isNotEmpty()) {
      val current = pending.removeFirst()
      for (child in children(current)) {
        if (isDirectory(child)) {
          if (!skipDirectory(child) && !isPackage(child)) pending += child
        } else if (child.name.endsWith(".kt") || child.name.endsWith(".java")) {
          val text =
            runCatching { fileSystem.read(child) { readUtf8() } }.getOrNull() ?: return true
          if ("Preview" in text) return true
        }
      }
    }
    return false
  }

  private fun children(dir: Path): List<Path> = fileSystem.listOrNull(dir).orEmpty()

  private fun isDirectory(path: Path): Boolean =
    fileSystem.metadataOrNull(path)?.isDirectory == true

  private fun isPackage(dir: Path): Boolean = BUILD_FILES.any { fileSystem.exists(dir / it) }

  /** Output trees, VCS metadata and the like: never a package's sources. */
  private fun skipDirectory(dir: Path): Boolean =
    dir.name.startsWith(".") || dir.name.startsWith("bazel-")

  companion object {
    private val BUILD_FILES = listOf("BUILD.bazel", "BUILD")

    /** `//a/b:c` → `a/b`; null for an external repository's label. */
    fun mainRepoPackage(label: String): String? {
      val local =
        when {
          label.startsWith("@@//") -> label.removePrefix("@@")
          label.startsWith("@//") -> label.removePrefix("@")
          label.startsWith("//") -> label
          else -> return null
        }
      return local.removePrefix("//").substringBefore(':')
    }
  }
}
