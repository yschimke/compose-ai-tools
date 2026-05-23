package ee.schimke.composeai.cli

import java.io.File

/**
 * Library entry point for the `composePreviewRenderAll` pipeline. Wraps a [GradleConnection] with a
 * higher-level `discoverModules()` + `render()` API so external consumers (contrib scripting,
 * third-party tooling) don't have to learn the Tooling-API shape to drive a preview render.
 *
 * The CLI builds its own command surface on top of this — see `Command.renderModules` /
 * `buildResults` — so behaviour stays consistent across the CLI and contrib consumers.
 *
 * Lifecycle: `open()` (constructor) opens a Tooling-API connection rooted at [projectRoot] and
 * holds it for the lifetime of the driver. Always close — the connection holds a daemon handle.
 *
 * ```kotlin
 * GradlePreviewDriver(projectRoot).use { driver ->
 *   val modules = driver.discoverModules()
 *   val outcome = driver.render(RenderRequest(modules = modules))
 *   outcome.previews.forEach { println("${it.id}: ${it.pngPath}") }
 * }
 * ```
 *
 * Scope: the driver runs the gradle task, reads each module's `previews.json`, expands
 * `@PreviewParameter` fan-outs against the on-disk PNG files, hashes them with [previewSha256], and
 * returns base [PreviewResult]s with `changed = null`. CLI-only concerns (`.cli-state.json` change
 * detection, image-size override for hosting agents, extension-renderer annotation, `--force`
 * stderr notices, autoinject init-script synthesis) stay in `:cli` as layers on top of the driver's
 * output.
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
   * Find every subproject that applies the `ee.schimke.composeai.preview` plugin. Detection is by
   * task-name presence (`composePreviewDiscover`) so it works against any consumer plugin version
   * that ships the discovery task.
   */
  fun discoverModules(): List<PreviewModule> = connection.findPreviewModules()

  /**
   * Resolve a single subproject by its Gradle path (with or without the leading colon). Returns
   * `null` when no project with that path applies the plugin.
   */
  fun discoverModule(gradlePath: String): PreviewModule? = connection.findPreviewModule(gradlePath)

  /**
   * Drive a render against [request].modules. Returns a [RenderOutcome] carrying the build's
   * pass/fail, every read manifest, the base [PreviewResult] list, and any test failures captured
   * live by the Tooling API.
   *
   * An empty `modules` list short-circuits the gradle invocation — returns `buildOk = true` and
   * empty lists. Same shape as the existing CLI behaviour.
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
    val manifests = PreviewResultBuilder.readAllManifests(modules)
    val previews = PreviewResultBuilder.build(manifests)
    return RenderOutcome(
      buildOk = buildOk,
      modules = modules,
      manifests = manifests,
      previews = previews,
      testFailures = connection.lastTestFailures(),
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
  /** Gradle build timeout. Default 300s matches the CLI. */
  val timeoutSeconds: Long = 300,
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
   * Data extensions to enable for this run. Forwarded as a single
   * `-PcomposePreview.activeExtensions=<comma-list>` argument. The gradle plugin currently ignores
   * this property — daemon-driven flows are where opt-in extensions actually run — but the property
   * is the stable contract carrier.
   */
  val extensions: Set<String> = emptySet(),
  /**
   * Task path to invoke per module. Default `:<path>:composePreviewRenderAll` matches the standard
   * CLI behaviour. Override for narrower drives (`composePreviewDiscover` only, resource-only
   * renders, etc.).
   */
  val taskFor: (PreviewModule) -> String = { ":${it.gradlePath}:composePreviewRenderAll" },
  /**
   * Pass `--rerun-tasks` to Gradle so every input task re-executes regardless of UP-TO-DATE. The
   * CLI's `--force=<reason>` flag flips this; contrib consumers can flip it for the same "I think
   * the build is stale" escape hatch.
   */
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
)
