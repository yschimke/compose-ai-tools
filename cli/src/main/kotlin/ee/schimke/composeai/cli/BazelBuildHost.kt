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
   * Runs the Gradle task names the server sends. Only `:<module>:composePreviewDaemonStart` has a
   * Bazel meaning; anything else fails with a message naming the task, not a silent success.
   */
  fun runTasks(tasks: List<String>): Boolean = tasks.all { task ->
    val module =
      task.removePrefix(":").removeSuffix(DAEMON_START).takeIf { task.endsWith(DAEMON_START) }
    if (module == null) {
      System.err.println(
        "compose-preview build-host: $task is a Gradle task; this is a Bazel workspace, where " +
          "only :<module>$DAEMON_START is supported"
      )
      return@all false
    }
    daemonStart(module)
  }

  private fun daemonStart(module: String): Boolean {
    val base = outputBases[module]
    if (base != null && base.resolve(DESCRIPTOR).isFile) return true
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
    const val DESCRIPTOR = "build/compose-previews/daemon-launch.json"
    const val DEFAULT_TIMEOUT_SECONDS = "1800"

    /**
     * Where target selection starts looking for the nearest `BUILD` file: the `--project` the user
     * passed (a subpackage, say) or the working directory, never the workspace root it resolved to
     * — the same rule `list` and `render` follow.
     */
    fun invocationDirectory(args: List<String>): File =
      args.flagValue("--project")?.let(::File) ?: File(".")

    /** `//app/ui:lib` → `app.ui:lib`, `//:app` → `app`: Gradle-shaped, no `/`. */
    fun modulePath(label: String): String =
      label.substringAfter("//").replace('/', '.').removePrefix(":").ifEmpty { "root" }

    /** The normal surface writes into `<base>/build/compose-previews`. */
    fun outputBase(prepared: PreparedBazelPreviews): File =
      prepared.manifestFile.absoluteFile.parentFile.parentFile.parentFile
  }
}
