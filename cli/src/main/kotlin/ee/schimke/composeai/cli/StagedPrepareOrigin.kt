package ee.schimke.composeai.cli

import ee.schimke.composeai.io.SystemFileSystem
import kotlinx.serialization.Serializable
import okio.FileSystem
import okio.Path

/**
 * The `bazel prepare` invocation that staged a `--out` directory, kept in it so the directory can
 * be prepared again from where it came (#6160).
 *
 * A staged directory is registered with tools that know only its path. `compose-preview-mcp`
 * re-runs `compose-preview bazel prepare --project <dir>` when a source changes; without this, that
 * treated the staged directory as the workspace, with no `.compose-preview/bazel.json` there, so
 * the default `bazel` (bazelisk) ran in a directory with no build and hung on its download. Now the
 * CLI finds this file at `--project` (or the directory above it, for one target of a multi-target
 * `--out`) and repeats the recorded invocation instead: the original workspace, targets, config and
 * flags, writing to the same `--out`.
 *
 * Only a build-backed prepare is recorded. One from `--artifacts` builds nothing, so there is no
 * workspace to go back to.
 */
@Serializable
internal data class StagedPrepareOrigin(
  /** The workspace root the prepare ran in. */
  val workspace: String,
  /** The full argument list to repeat, `prepare` and absolute `--project` / `--out` included. */
  val args: List<String>,
) {
  companion object {
    /** Relative to the staged `--out` directory. */
    const val FILE: String = ".compose-preview/prepare-origin.json"

    /** Flags whose value is a path, made absolute when recorded so the replay can run anywhere. */
    private val PATH_FLAGS = setOf("--config", "--out")

    /**
     * Other flags that take the next token as their value, copied with it verbatim. A pass-through
     * such as `--bazel-arg --config=ci` must not have its value read as this CLI's own `--config`.
     */
    private val VALUE_FLAGS =
      setOf(
        "--target",
        "--build-system",
        "--build-command",
        "--bazel-arg",
        "--buck-arg",
        "--aspect",
        "--execution-root",
        "--java-info-load",
        "--sandbox-count",
        "--timeout",
      )

    /**
     * The invocation to record for a `prepare` that ran with [args] in [root], or null when it
     * staged nothing to come back to: no `--out`, or `--artifacts`.
     */
    fun of(
      args: List<String>,
      root: java.io.File,
      resolve: (String) -> java.io.File,
    ): StagedPrepareOrigin? {
      if (args.flagValue("--out") == null || args.flagValue("--artifacts") != null) return null
      val recorded = buildList {
        var index = 0
        while (index < args.size) {
          val arg = args[index]
          val value = args.getOrNull(index + 1)
          val equalsFlag = PATH_FLAGS.firstOrNull { arg.startsWith("$it=") }
          when {
            arg == "--project" -> index += 2
            arg.startsWith("--project=") -> index++
            arg in VALUE_FLAGS && value != null -> {
              add(arg)
              add(value)
              index += 2
            }
            equalsFlag != null -> {
              add(equalsFlag)
              add(resolve(arg.substringAfter("=")).absolutePath)
              index++
            }
            arg in PATH_FLAGS && value != null -> {
              add(arg)
              add(resolve(value).absolutePath)
              index += 2
            }
            else -> {
              add(arg)
              index++
            }
          }
        }
        add("--project")
        add(root.absolutePath)
      }
      return StagedPrepareOrigin(root.absolutePath, recorded)
    }

    /** Writes [origin] into the staged [out] directory. Best-effort: staging never fails on it. */
    fun write(out: Path, origin: StagedPrepareOrigin, fileSystem: FileSystem = SystemFileSystem) {
      runCatching {
        val file = out / FILE
        fileSystem.createDirectories(file.parent!!)
        fileSystem.write(file) { writeUtf8(bazelJson.encodeToString(serializer(), origin)) }
      }
    }

    /** Removes any record from the staged [out] directory. */
    fun clear(out: Path, fileSystem: FileSystem = SystemFileSystem) {
      runCatching { fileSystem.delete(out / FILE, mustExist = false) }
    }

    /**
     * The recorded invocation for a `prepare` pointed at a staged directory ([project] or its
     * parent), with this run's own `--timeout` kept; null when [args] select their own config, a
     * target the recorded invocation did not select, or nothing staged is there.
     *
     * A `--target` the record already covers is still a re-prepare of this directory:
     * `compose-preview-mcp` names the module's label on every re-prepare (#6231). The replay keeps
     * the recorded target set rather than narrowing to it, so the other targets of a multi-target
     * `--out` stay in place.
     */
    fun replayFor(
      args: List<String>,
      project: Path,
      fileSystem: FileSystem = SystemFileSystem,
    ): StagedPrepareOrigin? {
      if (args.flagValue("--config") != null || args.flagValue("--artifacts") != null) return null
      val file =
        listOfNotNull(project, project.parent)
          .map { it / FILE }
          .firstOrNull { fileSystem.exists(it) } ?: return null
      val origin =
        runCatching {
          bazelJson.decodeFromString(serializer(), fileSystem.read(file) { readUtf8() })
        }
          .getOrNull() ?: return null
      // An explicit selection outside the record says what to build here; it is not redirected.
      val requested = targetsOf(args)
      if (requested.isNotEmpty()) {
        val recorded = targetsOf(origin.args)
        if (!requested.all { target -> recorded.any { covers(it, target) } }) return null
      }
      val timeout = args.flagValue("--timeout") ?: return origin
      return origin.copy(args = removeFlag(origin.args, "--timeout") + listOf("--timeout", timeout))
    }

    /** Every label or pattern `--target` selects in [args], space-separated values split. */
    private fun targetsOf(args: List<String>): List<String> =
      args.flagValuesAll("--target").flatMap { it.split(Regex("\\s+")) }.filter { it.isNotBlank() }

    /**
     * Whether the recorded [selection] (a label, or a `//pkg:all`, `//pkg:*`, `//pkg:` or
     * `//dir/...` pattern) includes [target]. An exclusion (`-//...`) includes nothing.
     */
    private fun covers(selection: String, target: String): Boolean {
      if (selection.startsWith("-")) return false
      val label = normalizeBazelLabel(target)
      if (normalizeBazelLabel(selection) == label) return true
      val pkg = packageOf(label.substringBeforeLast(':'))
      if (selection.endsWith("...")) {
        val base =
          if (selection.endsWith("//...")) selection.removeSuffix("...")
          else selection.removeSuffix("/...")
        val prefix = packageOf(base)
        return pkg == prefix || pkg.startsWith(prefix.trimEnd('/') + "/")
      }
      if (':' !in selection) return false
      val name = selection.substringAfterLast(':')
      return (name == "all" || name == "*" || name.isEmpty()) &&
        packageOf(selection.substringBeforeLast(':')) == pkg
    }

    /** The normalised package of a `//pkg` (or `@repo//pkg`) prefix. */
    private fun packageOf(pkg: String): String =
      normalizeBazelLabel("$pkg:x").substringBeforeLast(':')
  }
}
