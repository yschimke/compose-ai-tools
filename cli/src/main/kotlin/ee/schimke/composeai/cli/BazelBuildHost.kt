package ee.schimke.composeai.cli

import ee.schimke.composeai.buildhost.WireModule
import ee.schimke.composeai.buildhost.WireModuleManifest
import ee.schimke.composeai.previewdata.PreviewManifest as ResultManifest
import java.io.File

/**
 * The build host's answers for a Bazel-family workspace, so `browse` and `serve --discover` work
 * there with no server or protocol change. The preview server asks Gradle-shaped questions; each is
 * answered from the Bazel driver ([BazelCommand]) instead:
 * - discovery builds the target through the aspect, discovers and renders its previews, and reports
 *   one module whose `projectDir` is the target's output base — the server reads
 *   `<projectDir>/build/compose-previews/daemon-launch.json`, exactly where the normal surface
 *   writes it;
 * - `:<module>:composePreviewDaemonStart` makes sure that descriptor exists;
 * - there is no Gradle root, so the server skips its Gradle-only Wasm and git discovery rather than
 *   walking a tree that holds `bazel-*` output symlinks.
 *
 * A label is not a safe session id (`/` must survive URL segments), so modules are named with
 * [modulePath] and mapped back here.
 */
internal class BazelBuildHost(
  private val root: File,
  private val args: List<String>,
  /** Runs a `compose-preview bazel` operation in-process; replaced in tests. */
  private val bazel: (List<String>) -> List<PreparedBazelPreviews> = { bazelArgs ->
    BazelCommand(bazelArgs, normalSurface = true, invocationDirectory = invocationDirectory(args))
      .execute()
  },
) {
  private val labelsByModule = linkedMapOf<String, String>()
  private val outputBases = linkedMapOf<String, File>()

  fun modules(): List<WireModule> = outputBases.map { (module, base) ->
    WireModule(module, WireModule.wirePath(base))
  }

  /**
   * Builds, discovers and renders the selected targets, one module each; a failed build reports
   * `buildOk = false`. A pattern (`//app/...`) or a package with several targets yields several.
   */
  fun discoverAndBuild(): Pair<Boolean, List<WireModuleManifest>> {
    val prepared =
      runCatching { bazel(operation("render", requestedTargets())) }
        .onFailure { System.err.println("compose-preview bazel: ${it.message}") }
        .getOrNull() ?: return false to emptyList()
    return true to
      prepared.map { each ->
        val module = record(each)
        WireModuleManifest(
          WireModule(module, WireModule.wirePath(outputBase(each))),
          // The result form the server consumes, read back as `render` itself reports it.
          bazelJson.decodeFromString<ResultManifest>(each.manifestFile.readText()),
        )
      }
  }

  /** Remembers [prepared]'s label and output base under its module path, which it returns. */
  private fun record(prepared: PreparedBazelPreviews): String {
    val label = prepared.manifest.module
    val module = modulePath(label)
    labelsByModule[module] = label
    outputBases[module] = outputBase(prepared)
    return module
  }

  /**
   * Runs the Gradle task names the server sends. Two have a Bazel meaning:
   * - `:<module>:composePreviewDaemonStart` makes sure the module's descriptor exists;
   * - `:<module>:composePreviewCompile` rebuilds the module after an edit: `prepare` of its
   *   selection, always, into the same output base, so the jar paths do not move and the daemon's
   *   classloader swap ([ee.schimke.composeai.cli.serve.ServeHost.sourcesChanged]) loads the new
   *   classes. A failed build answers false, so the server keeps the last good classes.
   *
   * Anything else fails with a message naming the task, not a silent success.
   */
  fun runTasks(tasks: List<String>): Boolean = tasks.all { task ->
    when {
      task.endsWith(DAEMON_START) -> daemonStart(task.removePrefix(":").removeSuffix(DAEMON_START))
      task.endsWith(COMPILE) -> rebuild(task.removePrefix(":").removeSuffix(COMPILE))
      else -> {
        System.err.println(
          "compose-preview build-host: $task is a Gradle task; this is a Bazel workspace, where " +
            "only :<module>$DAEMON_START and :<module>$COMPILE are supported"
        )
        false
      }
    }
  }

  private fun daemonStart(module: String): Boolean {
    val base = outputBases[module]
    if (base != null && base.resolve(DESCRIPTOR).isFile) return true
    return rebuild(module)
  }

  private fun rebuild(module: String): Boolean {
    val label = labelsByModule[module]
    val prepared =
      runCatching { bazel(operation("prepare", label?.let(::listOf) ?: requestedTargets())) }
        .onFailure { System.err.println("compose-preview bazel: ${it.message}") }
        .getOrNull() ?: return false
    prepared.forEach(::record)
    return outputBases[module]?.resolve(DESCRIPTOR)?.isFile == true
  }

  /**
   * Every `--target` (labels or patterns), or a label passed as `--module`; empty lets the driver
   * select from bazel.json or the current package.
   */
  private fun requestedTargets(): List<String> =
    args.flagValuesAll("--target").ifEmpty {
      listOfNotNull(
        args.flagValue("--module")?.takeIf { it.startsWith("//") || it.startsWith("@") }
      )
    }

  private fun operation(name: String, targets: List<String>): List<String> = buildList {
    add(name)
    add("--project")
    add(root.absolutePath)
    targets.forEach {
      add("--target")
      add(it)
    }
    // A first build compiles the toolchain; the server waits on this call, so allow for it.
    add("--timeout")
    add(args.flagValue("--timeout") ?: DEFAULT_TIMEOUT_SECONDS)
    if ("--verbose" in args || "-v" in args) add("--verbose")
  }

  internal companion object {
    const val DAEMON_START = ":composePreviewDaemonStart"
    const val COMPILE = ":composePreviewCompile"
    const val DESCRIPTOR = "build/compose-previews/daemon-launch.json"
    const val DEFAULT_TIMEOUT_SECONDS = "1800"

    /**
     * Where target selection starts looking for the nearest `BUILD` file: the `--project` the user
     * passed (a subpackage, say) or the working directory, never the workspace root it resolved to
     * — the same rule `list` and `render` follow.
     */
    fun invocationDirectory(args: List<String>): File =
      args.flagValue("--project")?.let(::File) ?: File(".")

    /**
     * `//app/ui:lib` → `app.ui:lib`, `//:app` → `app`: Gradle-shaped, no `/`. An external
     * repository stays part of the name, reversibly escaped, so `@vendor//app:lib`
     * (`vendor~app:lib`) never collides with `//app:lib`, nor `@@rules_ui+` (`rules_ui-2b`) with
     * another canonical repository. A selection of several targets keeps its patterns readable:
     * `//app/... -//app/legacy/...` → `app.all+-app.legacy.all`, `//...` → `all`.
     */
    fun modulePath(label: String): String =
      label.trim().split(Regex("\\s+")).joinToString("+") { token ->
        val excluded = token.startsWith("-")
        val label = token.removePrefix("-")
        // Reversible, so distinct canonical repositories never share a name: `-` is the escape,
        // and every character outside `[A-Za-z0-9._]` (`-` included) is written as `-<hex>`.
        val repository =
          label
            .substringBefore("//", "")
            .trimStart('@')
            .map { c ->
              if (c.isLetterOrDigit() && c.code < 128 || c == '.' || c == '_') "$c"
              else "-" + c.code.toString(16)
            }
            .joinToString("")
        val path =
          label
            .substringAfter("//")
            .replace("/...", ".all")
            .replace("...", "all")
            .replace('/', '.')
            .removePrefix(":")
            .ifEmpty { "root" }
        val named = if (repository.isEmpty()) path else "$repository~$path"
        if (excluded) "-$named" else named
      }

    /** The normal surface writes into `<base>/build/compose-previews`. */
    fun outputBase(prepared: PreparedBazelPreviews): File =
      prepared.manifestFile.absoluteFile.parentFile.parentFile.parentFile
  }
}
