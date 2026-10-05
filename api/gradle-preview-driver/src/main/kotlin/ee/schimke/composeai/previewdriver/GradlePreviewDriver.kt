package ee.schimke.composeai.previewdriver

import ee.schimke.composeai.previewdata.PreviewManifest
import ee.schimke.composeai.previewdata.PreviewModule
import ee.schimke.composeai.previewdata.PreviewResult
import ee.schimke.composeai.previewdata.PreviewResultBuilder
import ee.schimke.composeai.previewdata.previewSha256
import java.io.File

/**
 * Library entry point for the `composePreviewRenderAll` pipeline: `discoverModules()` + `render()`
 * over a [GradleConnection], so external consumers needn't learn the Tooling API. The CLI layers
 * its own concerns (change detection, `--force`, init-script injection) on top.
 *
 * Holds a Tooling API connection rooted at [projectRoot] until closed.
 *
 * ```kotlin
 * GradlePreviewDriver(projectRoot).use { driver ->
 *   val modules = driver.discoverModules()
 *   val outcome = driver.render(RenderRequest(modules = modules))
 *   outcome.previews.forEach { println("${it.id}: ${it.pngPath}") }
 * }
 * ```
 *
 * [render] runs the task, reads each module's `previews.json`, expands `@PreviewParameter` fan-outs
 * and hashes PNGs with [previewSha256], returning results with `changed = null`.
 */
class GradlePreviewDriver(projectRoot: File, private val options: DriverOptions = DriverOptions()) :
  AutoCloseable {

  private val connection: GradleConnection =
    GradleConnection(
      projectDir = projectRoot,
      verbose = options.verbose,
      progress = options.progress,
      extraArguments = options.extraArguments,
    )

  /**
   * Last `BuildEnvironment` / `GradleProject` model query failure, or `null` if the most recent
   * model access succeeded. Forwarded from the wrapped [GradleConnection] so callers can
   * differentiate "no preview modules found" from "couldn't talk to gradle at all."
   */
  val lastModelAccessFailure: GradleAccessFailure?
    get() = connection.lastModelAccessFailure

  /**
   * Per-project configuration failures from the most recent [discoverModules] call — modules that
   * were skipped because building their `ComposePreviewModel` threw. Lets consumers explain an
   * empty discovery instead of reporting a bare "no modules" (issue #3).
   */
  val lastDiscoveryFailures: List<ProjectDiscoveryFailure>
    get() = connection.lastDiscoveryFailures

  /**
   * Every subproject that applies the plugin, found through its `ComposePreviewModel` (see
   * [DiscoverPreviewModulesAction]) so unrelated tasks aren't realized (issue #1620). Needs plugin
   * 0.11.13+, which auto-inject always supplies.
   */
  fun discoverModules(): List<PreviewModule> = connection.findPreviewModules(options.timeoutSeconds)

  /**
   * Resolve a single subproject by its Gradle path (with or without the leading colon). Returns
   * `null` when no project with that path applies the plugin.
   */
  fun discoverModule(gradlePath: String): PreviewModule? =
    connection.findPreviewModule(gradlePath, options.timeoutSeconds)

  /**
   * Render [request].modules. An empty module list skips Gradle and returns `buildOk = true` with
   * empty results.
   */
  fun render(request: RenderRequest): RenderOutcome {
    val modules = request.modules
    val args = buildList {
      if (request.rerunTasks) add("--rerun-tasks")
      addAll(request.additionalArgs)
      val extensions = request.extensions.filter { it.isNotEmpty() }.distinct()
      if (extensions.isNotEmpty()) {
        add("-PcomposePreview.activeExtensions=${extensions.joinToString(",")}")
      }
    }
    val buildOk =
      if (modules.isEmpty()) {
        true
      } else {
        val tasks = modules.map(request.taskFor).toTypedArray()
        connection.runTasks(*tasks, timeoutSeconds = options.timeoutSeconds, arguments = args)
      }
    val taskOutcomes = if (modules.isEmpty()) emptyMap() else connection.lastTaskOutcomes()
    val readableModules = modules.filter { module ->
      taskOutcomes[request.taskFor(module)]?.canReadOutputs == true
    }
    val manifests = PreviewResultBuilder.readAllManifests(readableModules)
    val previews = PreviewResultBuilder.build(manifests)
    return RenderOutcome(
      buildOk = buildOk,
      modules = readableModules,
      manifests = manifests,
      previews = previews,
      testFailures = connection.lastTestFailures(),
      taskOutcomes = taskOutcomes,
    )
  }

  override fun close() {
    connection.close()
  }
}

/**
 * Driver-wide configuration. Bound at construction time — these knobs map straight onto the
 * `GradleConnection`'s constructor parameters and the per-build timeout.
 */
data class DriverOptions(
  /** Stream Gradle stdout/stderr to the driver's stderr instead of swallowing it. */
  val verbose: Boolean = false,
  /** Emit per-task heartbeat lines on stderr every 15s, plus OSC progress for TTYs. */
  val progress: Boolean = false,
  /** Gradle build timeout; defaults to the connection's own so the two never drift. */
  val timeoutSeconds: Long = GradleConnection.DEFAULT_TIMEOUT_SECONDS,
  /**
   * Extra Tooling-API arguments prepended to every build / model query — primarily for
   * `--init-script <path>` injection. The CLI uses this to auto-apply its plugin to projects that
   * haven't manually wired it; contrib consumers typically leave this empty.
   */
  val extraArguments: List<String> = emptyList(),
)

/**
 * Per-render request. Bound at call time so callers can swap module sets, extensions, and task
 * paths across multiple renders on the same driver.
 */
data class RenderRequest(
  /** Modules to render. Typically the result of [GradlePreviewDriver.discoverModules]. */
  val modules: List<PreviewModule>,
  /**
   * Data extensions to enable, forwarded as `-PcomposePreview.activeExtensions=<comma-list>`. The
   * plugin currently ignores it (opt-in extensions run in the daemon), but it is the stable
   * carrier.
   */
  val extensions: Set<String> = emptySet(),
  /**
   * Task path to invoke per module. Default `:<path>:composePreviewRenderAll` matches the standard
   * CLI behaviour. Override for narrower drives (`composePreviewDiscover` only, resource-only
   * renders, etc.).
   */
  val taskFor: (PreviewModule) -> String = { ":${it.gradlePath}:composePreviewRenderAll" },
  /** Pass `--rerun-tasks` (the CLI's `--force=<reason>`). */
  val rerunTasks: Boolean = false,
  /** Additional Tooling-API arguments appended to the per-call build. */
  val additionalArgs: List<String> = emptyList(),
)

/**
 * Outcome of one [GradlePreviewDriver.render] call. [buildOk] is `false` when Gradle reported a
 * build failure; [previews] is non-empty only if at least one module's manifest landed on disk (a
 * render that failed early before writing previews.json produces empty results plus `buildOk =
 * false`).
 */
data class RenderOutcome(
  val buildOk: Boolean,
  val modules: List<PreviewModule>,
  val manifests: List<Pair<PreviewModule, PreviewManifest>>,
  val previews: List<PreviewResult>,
  val testFailures: List<CapturedTestFailure>,
  val taskOutcomes: Map<String, GradleTaskOutcome> = emptyMap(),
)
