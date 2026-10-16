package ee.schimke.composeai.cli

import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * The bundled BXL script's entry point, relative to the project root. Buck2 has no aspects; a BXL
 * script reads the same JVM providers a build produced and prints the build-artifacts documents the
 * Bazel aspect writes, so everything after collection is shared with the Bazel driver.
 */
internal const val DEFAULT_BUCK_BXL = "//.compose-preview/buck/compose_preview.bxl:artifacts"

/** Rules whose `JavaLibraryInfo` describes a JVM/Desktop target, as a regex over rule names. */
internal const val DEFAULT_BUCK_TARGET_KINDS = "^(kotlin_library|java_library)$"

/** Names Buck2 reads a package from by default (`[buildfile] name`). */
private val BUCK_PACKAGE_FILES = listOf("BUCK", "BUCK.v2", "TARGETS", "TARGETS.v2")

/**
 * The project root: the directory holding `.buckroot`, else the outermost `.buckconfig` below the
 * repository boundary. A cell inside the project (a vendored prelude, say) carries its own
 * `.buckconfig`, so the nearest one is not necessarily the root.
 */
internal fun detectBuckRoot(start: File): File? {
  var directory: File? = start.canonicalFile
  var outermost: File? = null
  while (directory != null) {
    val dir = directory
    if (dir.resolve(".buckroot").isFile) return dir
    if (dir.resolve(".buckconfig").isFile) outermost = dir
    if (dir.resolve(".git").exists() || dir.resolve(".hg").exists()) break
    directory = dir.parentFile
  }
  return outermost
}

internal fun isBuckProjectMarker(dir: File): Boolean =
  dir.resolve(".buckroot").isFile || dir.resolve(".buckconfig").isFile

/** Copies the bundled BXL script; never overwrites a locally adapted copy. */
internal fun installBuckBxl(root: File) {
  val text =
    requireNotNull(BazelCommand::class.java.getResourceAsStream("buck/compose_preview.bxl")) {
        "Missing bundled BXL script"
      }
      .bufferedReader()
      .use { it.readText() }
  val file = root.resolve(".compose-preview/buck/compose_preview.bxl")
  require(!file.exists() || (file.isFile && file.readText() == text)) {
    "Existing BXL script differs: $file. Keep it and configure bxl, or move it before reinitializing."
  }
  file.parentFile.mkdirs()
  file.writeText(text)
}

internal class BuckBuild(
  private val execute: (List<String>, File, Long) -> String = BazelProcess::run
) {
  /**
   * The nearest package at or above [directory], as `//pkg:`; outside any package, everything below
   * [directory] (`//dir/...`). A configured `target`, then `targets`, wins.
   */
  fun selectTargets(root: File, directory: File, config: BazelBuildConfig): BazelTargetSelection {
    config.target?.let {
      return BazelTargetSelection(it, listOf(it))
    }
    if (config.targets.isNotEmpty())
      return BazelTargetSelection(config.targets.joinToString(" "), config.targets)
    val canonicalRoot = root.canonicalFile
    var current: File? = directory.canonicalFile
    while (current != null && current.startsWith(canonicalRoot)) {
      val dir = current
      if (BUCK_PACKAGE_FILES.any { dir.resolve(it).isFile }) {
        val pattern = "//${dir.relativeTo(canonicalRoot).invariantSeparatorsPath}:"
        return BazelTargetSelection(pattern, listOf(pattern))
      }
      current = dir.parentFile
    }
    val relative =
      directory.canonicalFile
        .takeIf { it.startsWith(canonicalRoot) }
        ?.relativeTo(canonicalRoot)
        ?.invariantSeparatorsPath
        .orEmpty()
    val pattern = if (relative.isEmpty()) "//..." else "//$relative/..."
    return BazelTargetSelection(pattern, listOf(pattern))
  }

  /** `buck2 [startup] bxl [buildArgs] <bxl> -- --kinds <regex> --target <t>...` */
  fun argv(targets: List<String>, config: BazelBuildConfig): List<String> = buildList {
    addAll(config.buildCommand)
    addAll(config.startupArgs)
    add("bxl")
    addAll(config.buildArgs)
    add(config.bxl)
    add("--")
    add("--kinds")
    add(config.targetKinds ?: DEFAULT_BUCK_TARGET_KINDS)
    targets.forEach {
      add("--target")
      add(it)
    }
  }

  /**
   * Builds [targets] through the BXL script and resolves each JVM library it reports against
   * [root], Buck2's execution root. With [skipWithoutMetadata] (a pattern's candidates) an empty
   * result is allowed; a target named explicitly must be a JVM library.
   */
  fun collectAll(
    root: File,
    targets: List<String>,
    config: BazelBuildConfig,
    timeout: Long,
    skipWithoutMetadata: Boolean,
  ): List<ResolvedBazelArtifacts> {
    require(targets.none { it.startsWith("-") }) {
      "Buck2 target patterns cannot exclude (${targets.filter { it.startsWith("-") }.joinToString()}); " +
        "select the packages to include instead"
    }
    val documents = parseBuckArtifacts(execute(argv(targets, config), root, timeout))
    check(documents.isNotEmpty() || skipWithoutMetadata) {
      "No JVM library among ${targets.joinToString(" ")}: the BXL script reads rules matching " +
        "${config.targetKinds ?: DEFAULT_BUCK_TARGET_KINDS}; set targetKinds in " +
        ".compose-preview/buck.json for another rule"
    }
    return documents.map { text ->
      bazelJson
        .decodeFromString<BazelArtifacts>(text)
        .resolve(root, root, config.androidCompileSdk, bazelRuntimeJarOwners(text))
    }
  }
}

/** `//pkg:` (a package's targets) and `//dir/...`; a full label names one target. */
internal fun isBuckTargetPattern(target: String): Boolean =
  target.endsWith("...") || target.endsWith(":")

/** Each document of the BXL script's `{"targets": [...]}`, re-encoded for the typed decode. */
internal fun parseBuckArtifacts(stdout: String): List<String> {
  // `print_json` writes one object; anything a wrapper prints before it is skipped.
  val start = stdout.indexOf('{')
  check(start >= 0) { "The BXL script printed no artifacts document" }
  val document = bazelJson.parseToJsonElement(stdout.substring(start)) as? JsonObject
  val targets = document?.get("targets") as? JsonArray ?: error("BXL output has no targets array")
  return targets.map { bazelJson.encodeToString(JsonObject.serializer(), it as JsonObject) }
}
