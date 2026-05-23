package ee.schimke.composeai.cli

import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import kotlin.system.exitProcess
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * On-disk shape mirrors gradle-plugin/PreviewData.kt (parsed with ignoreUnknownKeys).
 *
 * The wire-format DTOs (`PreviewParams`, `ScrollCapture`, `Capture`, `PreviewInfo`,
 * `PreviewDataProduct`, `PreviewManifest`, `CaptureResult`, `PreviewResult`) carved out to
 * `:preview-data-api` so external consumers (contrib scripting, third-party tooling) can compile
 * against the published wire shapes without dragging in `:cli`'s Gradle Tooling API + scripting
 * closure. Package preserved (`ee.schimke.composeai.cli`) so existing importers in this module
 * don't change — same pattern `:data-a11y-core` used for the D2.2 extraction.
 */

// AccessibilityFinding / AccessibilityEntry / AccessibilityReport moved to A11yReportRenderer.kt
// as part of the per-extension strategy refactor — they're a11y-specific wire-format DTOs that
// have no business in the shared Command base layer.
// (Those types then carved out to `:preview-data-api` alongside `PreviewResult` for the
// clean-API step A — they're the wire-format mirrors that the deprecated
// `PreviewResult.a11yFindings` field references.)

/**
 * Versioned envelope for `compose-preview show|list|a11y --json`. Pinning the schema lets agents
 * detect format breaks without dispatching on field shapes — bump [SHOW_LIST_SCHEMA] when the
 * per-row shape changes.
 *
 * Top-level [previews] is the same `PreviewResult` list the unwrapped form used to emit. The
 * [counts] block is filled in by `show`/`a11y` (where `changed` is meaningful) and lets agents skip
 * downloading every PNG when they only care about the diff against the previous run.
 */
@Serializable
data class PreviewListResponse(
  val schema: String = SHOW_LIST_SCHEMA,
  val previews: List<PreviewResult>,
  val counts: PreviewCounts? = null,
)

@Serializable
data class PreviewCounts(val total: Int, val changed: Int, val unchanged: Int, val missing: Int)

/**
 * Compact response shape emitted under `--brief`. Drops everything an agent already had from a
 * prior `show --json` (functionName, className, params, sourceFile) and shortens field names so the
 * per-row JSON shrinks to ~5x smaller. Keys are intentionally terse: `png` = absolute PNG path,
 * `sha` = first 12 hex chars of sha256, `time` = advanceTimeMillis, `scroll` = scroll mode string.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class BriefPreviewListResponse(
  // Always-encode so brief mode (encodeDefaults=false) still emits the
  // version pin agents grep for.
  @EncodeDefault val schema: String = SHOW_LIST_BRIEF_SCHEMA,
  val previews: List<BriefPreviewResult>,
  val counts: PreviewCounts? = null,
)

@Serializable
data class BriefPreviewResult(
  val id: String,
  /** Omitted in single-module output. */
  val module: String? = null,
  val captures: List<BriefCapture>,
  /** Number of ATF findings; null when a11y is off for the module. */
  val a11y: Int? = null,
)

@Serializable
data class BriefCapture(
  /** Absolute path; null when render didn't produce a PNG. */
  val png: String? = null,
  /** sha256 prefix (12 hex chars); null when no PNG. */
  val sha: String? = null,
  /** null when first run / unknown. */
  val changed: Boolean? = null,
  /** advanceTimeMillis; omitted for static captures. */
  val time: Long? = null,
  /** Scroll mode (`END`/`LONG`); omitted when no scroll drive. */
  val scroll: String? = null,
)

// Bumped to `/v2` when `PreviewResult.a11yFindings` + `a11yAnnotatedPath` were removed —
// consumers that read those top-level fields break here. Findings + annotated-path migrate to
// `dataExtensions["a11y"]` against `AccessibilityEntry`. Brief format stays at `/v1`: the
// `a11y: Int?` count field is unchanged (the count source migrated from `a11yFindings` to
// decoded `dataExtensions["a11y"]`, but the wire shape is identical).
internal const val SHOW_LIST_SCHEMA = "compose-preview-show/v2"
internal const val SHOW_LIST_BRIEF_SCHEMA = "compose-preview-show-brief/v1"

@Serializable private data class CliState(val shas: Map<String, String> = emptyMap())

private val json = Json {
  ignoreUnknownKeys = true
  prettyPrint = true
  encodeDefaults = true
}

/**
 * JSON config for `--brief`: no pretty-print (one-line-per-row encoding is the common agent
 * consumption pattern) and `encodeDefaults = false` so all the null/false/0 fields drop out instead
 * of bloating the payload.
 */
private val briefJson = Json {
  ignoreUnknownKeys = true
  prettyPrint = false
  encodeDefaults = false
}

abstract class Command(protected val args: List<String>) {
  protected val explicitModule: String? = args.flagValue("--module")
  protected val filter: String? = args.flagValue("--filter")
  protected val exactId: String? = args.flagValue("--id")
  protected val verbose: Boolean = "--verbose" in args || "-v" in args
  protected val progress: Boolean = verbose || "--progress" in args
  protected val timeoutSeconds: Long = args.flagValue("--timeout")?.toLongOrNull() ?: 300
  /** When true, drop previews with no `changed=true` capture from JSON output. */
  protected val changedOnly: Boolean = "--changed-only" in args
  /**
   * Compact JSON: drop `functionName`/`className`/`sourceFile`/`module`/`params` from each row,
   * keep `id` + `captures`. Designed for agent re-render loops where the full metadata was already
   * cached on first call.
   */
  protected val brief: Boolean = "--brief" in args

  /**
   * Sanctioned escape hatch when an agent thinks `:composePreviewRenderAll` is serving a stale
   * render. Set via `--force=<reason>`; threaded into Gradle as `--rerun-tasks` so every input task
   * re-executes regardless of UP-TO-DATE. **Never** runs `:clean` and **never** touches
   * `build/classes/` — agents that delete class files directly are exactly the failure mode we're
   * giving an alternative to. Each use is logged to stderr with a pointer to issue #924, where
   * agents are asked to report the freshness gap that made them reach for it.
   */
  protected val forceReason: String? = args.flagValue("--force")?.takeIf { it.isNotBlank() }

  /**
   * Data extensions the user explicitly requested for this run via `--with-extension`. Repeatable
   * (`--with-extension a11y --with-extension theme`), comma-batched (`--with-extension
   * a11y,theme`), or equals-form (`--with-extension=a11y`).
   *
   * Forwarded to Gradle as a single `-PcomposePreview.activeExtensions=<comma-list>` argument. The
   * gradle plugin itself currently ignores this property — a11y and other opt-in data products are
   * daemon-only — but the property is the contract carrier the CLI keeps writing so future
   * daemon-orchestrating logic in the CLI (spinning up a temporary daemon for `compose-preview
   * a11y`, see TODO on [A11yCommand]) can read it back as the per-invocation subscription list.
   */
  protected val requestedExtensions: List<String> =
    (args.flagValuesAll("--with-extension") + args.flagValuesAll("--with"))
      .flatMap { raw -> raw.split(',', ';') }
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .distinct()

  /**
   * Subclass hook — extensions a particular command always wants on regardless of whether the user
   * passed `--with-extension`. Default empty; `A11yCommand` returns `["a11y"]` so its behaviour is
   * "render with the built-in a11y data extension and read the canned report."
   */
  protected open fun implicitExtensions(): List<String> = emptyList()

  /**
   * Gradle property arguments for every extension this run wants enabled — the union of
   * [implicitExtensions] (subclass-pinned) and [requestedExtensions] (user-requested), deduplicated
   * and joined into a single `-PcomposePreview.activeExtensions=<list>` argument. Returns an empty
   * list (no Gradle args) when neither implicit nor requested extensions are present. The gradle
   * plugin currently does not act on this property — daemon-driven flows are where opt-in
   * extensions actually run — but the CLI keeps emitting it as the stable record of what the
   * invocation requested.
   */
  protected fun extensionGradleArgs(): List<String> {
    val all = (implicitExtensions() + requestedExtensions).distinct().filter { it.isNotEmpty() }
    if (all.isEmpty()) return emptyList()
    return listOf("-PcomposePreview.activeExtensions=${all.joinToString(",")}")
  }

  private val forceNoticePrinted = AtomicBoolean(false)

  /**
   * Build the gradle-side argument list for a render-pipeline task, prepending `--rerun-tasks` when
   * [forceReason] is set so the build re-executes even if Gradle's UP-TO-DATE check would skip it.
   * Emits a one-line stderr notice the first time it's called per process so the agent (and the
   * human reading their transcript) can see the reason and the tracking-issue link.
   *
   * Always appends [extensionGradleArgs] so any `--with-extension` flags the user passed (and the
   * implicit `a11y` request from `A11yCommand`) flow through as a single
   * `-PcomposePreview.activeExtensions=<comma-list>` argument on the spawned Gradle build.
   */
  protected fun gradleArgsWithForce(extra: List<String> = emptyList()): List<String> {
    val extensionArgs = extensionGradleArgs()
    val withExtras = extra + extensionArgs
    val reason = forceReason ?: return withExtras
    if (forceNoticePrinted.compareAndSet(false, true)) {
      System.err.println(
        "compose-preview --force: reason='$reason' — passing --rerun-tasks. " +
          "Please report on https://github.com/yschimke/compose-ai-tools/issues/924 " +
          "(do not delete build/classes/ — that's what this flag exists to replace)."
      )
    }
    return listOf("--rerun-tasks") + withExtras
  }

  abstract fun run()

  protected fun withGradle(silenceStdout: Boolean = false, block: (GradleConnection) -> Unit) {
    val root =
      findProjectRoot()
        ?: run {
          System.err.println("Cannot find Gradle project root (no gradlew found)")
          exitProcess(1)
        }
    val injectArgs = autoInjectInitScriptArgs(args, projectRoot = root)
    warnIfPluginNotPreApplied(args, projectRoot = root, autoInjectActive = injectArgs.isNotEmpty())
    val connection =
      withGradleStdout(silenceStdout) {
        GradleConnection(root, verbose, progress, extraArguments = injectArgs)
      }
    connection.use(block)
  }

  protected fun <T> withGradleStdout(silence: Boolean, block: () -> T): T {
    if (!silence) return block()
    val originalOut = System.out
    return try {
      System.setOut(System.err)
      block()
    } finally {
      System.setOut(originalOut)
    }
  }

  protected fun resolveModules(gradle: GradleConnection): List<PreviewModule> {
    if (explicitModule != null) {
      // Resolve via the Tooling API so --module works with nested
      // Gradle paths (e.g. `--module auth:composables`) and reflects
      // any custom `project.projectDir` override.
      val one = gradle.findPreviewModule(explicitModule)
      if (one == null) {
        gradle.lastModelAccessFailure?.let {
          System.err.println(
            "Could not query Gradle project model while resolving module '$explicitModule'."
          )
          System.err.println("Gradle ${it.operation} failed: ${it.message}")
          it.detail?.let { detail -> System.err.println("Caused by: $detail") }
          System.err.println(
            "Check Gradle wrapper/cache access, then rerun with --verbose for full output."
          )
          exitProcess(1)
        }
        System.err.println(
          "Module '$explicitModule' not found or does not apply the compose-ai-tools plugin."
        )
        exitProcess(1)
      }
      return listOf(one)
    }

    val modules = gradle.findPreviewModules()
    if (modules.isEmpty()) {
      gradle.lastModelAccessFailure?.let {
        System.err.println("Could not query Gradle project model.")
        System.err.println("Gradle ${it.operation} failed: ${it.message}")
        it.detail?.let { detail -> System.err.println("Caused by: $detail") }
        System.err.println(
          "Check Gradle wrapper/cache access, then rerun with --verbose for full output."
        )
        exitProcess(1)
      }
      System.err.println("No modules with compose-ai-tools plugin found.")
      System.err.println("Apply the plugin: id(\"ee.schimke.composeai.preview\")")
      exitProcess(1)
    }
    if (verbose || modules.size > 1) {
      System.err.println("Found preview modules: ${modules.joinToString(", ") { it.gradlePath }}")
    }
    return modules
  }

  protected fun runGradle(
    gradle: GradleConnection,
    vararg tasks: String,
    arguments: List<String> = emptyList(),
  ): Boolean {
    return gradle.runTasks(*tasks, timeoutSeconds = timeoutSeconds, arguments = arguments)
  }

  /**
   * Outcome of [renderAllModules] — the full result of "discover preview modules, run their
   * `:composePreviewRenderAll` tasks, read each module's manifest, and build the merged
   * [PreviewResult] list." Each subcommand decides what to do with this (filter, format, exit code)
   * but the gradle drive is shared.
   *
   * [buildOk] reflects the gradle build result. Some callers (`show`) exit non-zero immediately on
   * `false`; others (`a11y`) still want to surface the partial findings written before gradle gave
   * up, so the bool is data on the outcome rather than an early-return.
   */
  protected data class RenderModulesOutcome(
    val buildOk: Boolean,
    val modules: List<PreviewModule>,
    val manifests: List<Pair<PreviewModule, PreviewManifest>>,
    val results: List<PreviewResult>,
  )

  /**
   * Lower-level outcome of [renderModules] — only the gradle build result and the (optionally
   * filtered) module list, leaving manifest reads + result building to the caller. Used by commands
   * whose manifests don't fit the `PreviewManifest` / `PreviewResult` shape (today:
   * `show-resources`, which has its own resource-manifest type).
   */
  protected data class RawRenderOutcome(val buildOk: Boolean, val modules: List<PreviewModule>)

  /**
   * Shared gradle-drive pipeline — open the connector, resolve preview modules, optionally filter
   * them, run a per-module task, report failures. Returns the build result + the modules the caller
   * should read manifests from.
   *
   * [moduleFilter] runs after `resolveModules`; pass it when only a subset of plugin-applied
   * modules participates in this pipeline (e.g. `show-resources` filters to Android-only modules
   * because `:composePreviewRenderAndroidResources` doesn't exist on CMP modules).
   *
   * [taskFor] builds the gradle task path for one module. Standard preview commands use
   * `:${path}:composePreviewRenderAll`; resource commands use
   * `:${path}:composePreviewRenderAndroidResources`.
   *
   * Skips the actual `runGradle` call (and reports `buildOk = true`) when [moduleFilter] yields an
   * empty list — there's nothing to render and `gradle.runTasks([])` has no defined meaning.
   */
  protected fun renderModules(
    silenceStdout: Boolean,
    moduleFilter: (PreviewModule) -> Boolean = { true },
    taskFor: (PreviewModule) -> String = { ":${it.gradlePath}:composePreviewRenderAll" },
    gradleArguments: List<String> = emptyList(),
  ): RawRenderOutcome {
    var outcome: RawRenderOutcome? = null
    withGradle(silenceStdout = silenceStdout) { gradle ->
      val modules = withGradleStdout(silenceStdout) { resolveModules(gradle).filter(moduleFilter) }
      val buildOk =
        if (modules.isEmpty()) true
        else {
          val tasks = modules.map(taskFor).toTypedArray()
          val ok =
            withGradleStdout(silenceStdout) {
              runGradle(gradle, *tasks, arguments = gradleArguments)
            }
          if (!ok) reportRenderFailures(gradle)
          ok
        }
      outcome = RawRenderOutcome(buildOk = buildOk, modules = modules)
    }
    return outcome ?: error("renderModules: gradle block did not produce an outcome")
  }

  /**
   * Standard "discover modules → run `:composePreviewRenderAll` → load manifests → build results"
   * pipeline used by `show` and `a11y`. Wraps [renderModules] with the preview-manifest read
   * + [PreviewResult] build steps. Subcommands contribute per-feature gradle properties via
   *   [gradleArguments].
   *
   * [silenceStdout] mirrors each command's `--json` flag: when on, the shared helpers redirect
   * stdout to stderr so the gradle progress output doesn't poison the JSON envelope.
   */
  protected fun renderAllModules(
    silenceStdout: Boolean,
    gradleArguments: List<String> = emptyList(),
  ): RenderModulesOutcome {
    val raw = renderModules(silenceStdout = silenceStdout, gradleArguments = gradleArguments)
    val manifests = readAllManifests(raw.modules)
    val results = if (manifests.isEmpty()) emptyList() else buildResults(manifests)
    return RenderModulesOutcome(
      buildOk = raw.buildOk,
      modules = raw.modules,
      manifests = manifests,
      results = results,
    )
  }

  /**
   * Prints failing tests captured live during the build by [GradleConnection]'s Tooling API
   * listener. Called on Gradle build failure so users see the actual test exception in the CLI log
   * instead of just Gradle's "There were failing tests. See the report at file:///…/index.html"
   * pointer (which is unreachable from CI runner logs).
   */
  protected fun reportRenderFailures(gradle: GradleConnection) {
    printCapturedTestFailures(gradle.lastTestFailures())
  }

  protected fun readManifest(module: PreviewModule): PreviewManifest? =
    PreviewResultBuilder.readManifest(module)

  protected fun readAllManifests(
    modules: List<PreviewModule>
  ): List<Pair<PreviewModule, PreviewManifest>> = PreviewResultBuilder.readAllManifests(modules)

  /**
   * Per-CLI-invocation renderer set, built once and cached. Iterated by [buildResults] (via
   * [annotateExtensions]) so every result picks up data from every loaded extension without
   * Command-level branching. Subcommands that want a single specific renderer ([ReportCommand])
   * pull the same instance from this map by id so cached decoded state is shared.
   *
   * Stateful per invocation — see [ExtensionReportRenderer] kdoc.
   */
  protected val extensionRenderers: Map<String, ExtensionReportRenderer> =
    builtInExtensionReporters().mapValues { (_, factory) -> factory() }

  /**
   * Loads every built-in extension's sidecar JSON against the merged manifest set. Each renderer
   * caches its decoded state internally; subsequent [annotateExtensions] calls are pure dictionary
   * lookups.
   */
  private fun loadExtensionReports(manifests: List<Pair<PreviewModule, PreviewManifest>>) {
    for (renderer in extensionRenderers.values) {
      renderer.load(manifests, verbose)
    }
  }

  /**
   * Runs every loaded renderer's [ExtensionReportRenderer.annotate] over [result] in registration
   * order. Each annotator returns an immutable copy with its extension's fields set — the next
   * annotator sees that copy, so multiple extensions can layer cleanly. Renderers whose extensions
   * aren't enabled for [module] no-op.
   */
  private fun annotateExtensions(result: PreviewResult, module: PreviewModule): PreviewResult {
    var enriched = result
    for (renderer in extensionRenderers.values) {
      enriched = renderer.annotate(enriched, module)
    }
    return enriched
  }

  /**
   * Per-capture result builder. Delegates the manifest → base-result transform (PNG glob, sha256,
   * `@PreviewParameter` fan-out, data-product artefact captures) to [PreviewResultBuilder] from
   * `:gradle-preview-driver`; layers the CLI-only concerns on top:
   *
   * 1. [ImageSizeOverride] — resize PNGs in place when running inside a hosting agent that caps
   *    image dimensions. Recomputes sha256 for files that actually got resized.
   * 2. State-file diff — read the per-module `.cli-state.json`, fill `changed` per capture based on
   *    the prior run's sha, write the new shas back. State key is `<id>` for the first capture
   *    (preserves legacy state files from before per-capture tracking) and `<id>#<n>` for
   *    subsequent captures of an animation / scroll / param fan-out.
   * 3. Extension annotation — every registered [ExtensionReportRenderer] (today: a11y) loads its
   *    sidecar JSON and layers per-preview data onto the result (populating both the new
   *    `dataExtensions[ext]` carrier and the v1 deprecated `a11yFindings` field).
   *
   * The top-level `pngPath` / `sha256` / `changed` on [PreviewResult] mirror the first capture
   * verbatim so existing agents keep working.
   */
  protected fun buildResults(
    manifests: List<Pair<PreviewModule, PreviewManifest>>
  ): List<PreviewResult> {
    val base = PreviewResultBuilder.build(manifests)
    val imageSizeOverride = ImageSizeOverride.detect()
    // Load every registered extension's sidecar JSON up-front; the per-row [annotateExtensions]
    // step below does pure lookups against the cached decoded state.
    loadExtensionReports(manifests)

    // Group base results by module so per-module state-file I/O is one pass.
    val moduleByPath = manifests.associate { (m, _) -> m.gradlePath to m }
    val resultsByModule = base.groupBy { it.module }
    val out = mutableListOf<PreviewResult>()
    for ((modulePath, moduleResults) in resultsByModule) {
      val module = moduleByPath[modulePath] ?: continue
      val prior = readState(module).shas
      val updated = mutableMapOf<String, String>()
      for (result in moduleResults) {
        val overlayed = applyImageOverrideAndStateDiff(result, prior, updated, imageSizeOverride)
        out += annotateExtensions(overlayed, module)
      }
      writeState(module, CliState(updated))
    }
    return out
  }

  /**
   * Apply [ImageSizeOverride] to each capture's PNG (resize in place if oversized; recompute sha256
   * for resized files), then compute the `changed` flag per capture from the prior state sha.
   * Mutates [updated] with the new shas to write back to the state file.
   */
  private fun applyImageOverrideAndStateDiff(
    base: PreviewResult,
    prior: Map<String, String>,
    updated: MutableMap<String, String>,
    imageSizeOverride: ImageSizeOverride,
  ): PreviewResult {
    // `applyImageSizeOverride` rewrites the file in place when it resizes, so the driver-computed
    // sha is no longer trustworthy whenever an override is active. Recompute the sha across the
    // module in that case; otherwise trust the driver's sha to avoid a redundant hash pass.
    val overrideActive = imageSizeOverride.maxEdgePx != null
    val captures =
      base.captures.mapIndexed { index, capture ->
        val pngFile = capture.pngPath?.let(::File)
        val normalizedFile = pngFile?.let { applyImageSizeOverride(it, imageSizeOverride) }
        val sha =
          when {
            normalizedFile == null -> null
            overrideActive -> previewSha256(normalizedFile)
            else -> capture.sha256
          }
        val stateKey = if (index == 0) base.id else "${base.id}#$index"
        if (sha != null) updated[stateKey] = sha
        val priorSha = prior[stateKey]
        val changed =
          when {
            sha == null -> null
            priorSha == null -> true
            else -> priorSha != sha
          }
        capture.copy(pngPath = normalizedFile?.absolutePath, sha256 = sha, changed = changed)
      }
    val first = captures.firstOrNull()
    return base.copy(
      captures = captures,
      pngPath = first?.pngPath,
      sha256 = first?.sha256,
      changed = first?.changed,
    )
  }

  /** True if the preview has at least one capture with `changed = true`. */
  protected fun PreviewResult.anyChanged(): Boolean =
    captures.any { it.changed == true } || changed == true

  /** Filters by `--id` / `--filter` and (optionally) `--changed-only`. */
  protected fun applyFilters(all: List<PreviewResult>): List<PreviewResult> = all.filter {
    matchesRequest(it) && (!changedOnly || it.anyChanged())
  }

  /**
   * @param results rows to emit (after `--id`/`--filter`/`--changed-only`)
   * @param countsScope rows the [PreviewCounts] should be computed from — typically the unfiltered
   *   set so the agent sees totals even when `--changed-only` narrows the visible rows. Pass `null`
   *   to omit counts.
   */
  protected fun encodeResponse(
    results: List<PreviewResult>,
    countsScope: List<PreviewResult>?,
  ): String {
    val counts = countsScope?.let { countsOf(it) }
    if (brief) {
      val multiModule = results.map { it.module }.distinct().size > 1
      val brief = results.map { r ->
        // Decode the a11y count from the generic `dataExtensions["a11y"]` carrier — the
        // previous `r.a11yFindings?.size` read disappeared with the v1→v2 bump. `null` when
        // ATF didn't run for the module (no `dataExtensions["a11y"]` entry), matching the v1
        // null vs. `0` semantics that agents already grep for.
        val a11yCount = decodeA11yFindingsCount(r)
        BriefPreviewResult(
          id = r.id,
          module = r.module.takeIf { multiModule },
          captures =
            r.captures.map { c ->
              BriefCapture(
                png = c.pngPath,
                sha = c.sha256?.take(12),
                changed = c.changed,
                time = c.advanceTimeMillis,
                scroll = c.scroll?.mode,
              )
            },
          a11y = a11yCount,
        )
      }
      return briefJson.encodeToString(
        BriefPreviewListResponse.serializer(),
        BriefPreviewListResponse(previews = brief, counts = counts),
      )
    }
    return json.encodeToString(
      PreviewListResponse.serializer(),
      PreviewListResponse(previews = results, counts = counts),
    )
  }

  private fun countsOf(results: List<PreviewResult>) =
    PreviewCounts(
      total = results.size,
      changed = results.count { it.anyChanged() },
      unchanged = results.count { !it.anyChanged() && it.captures.any { c -> c.pngPath != null } },
      missing = results.count { it.captures.all { c -> c.pngPath == null } },
    )

  protected fun matchesRequest(result: PreviewResult): Boolean {
    if (exactId != null && result.id != exactId) return false
    if (filter != null && !result.id.contains(filter, ignoreCase = true)) return false
    return true
  }

  private fun stateFile(module: PreviewModule): File =
    module.projectDir.resolve("build/compose-previews/.cli-state.json")

  private fun readState(module: PreviewModule): CliState {
    val f = stateFile(module)
    if (!f.exists()) return CliState()
    return try {
      json.decodeFromString(CliState.serializer(), f.readText())
    } catch (e: Exception) {
      if (verbose)
        System.err.println("Warning: corrupt state file ${f.path}, resetting: ${e.message}")
      CliState()
    }
  }

  private fun writeState(module: PreviewModule, state: CliState) {
    val f = stateFile(module)
    f.parentFile?.mkdirs()
    f.writeText(json.encodeToString(CliState.serializer(), state))
  }

  private fun findProjectRoot(): File? {
    var dir: File? = File(".").absoluteFile
    while (dir != null) {
      if (File(dir, "gradlew").exists()) return dir
      dir = dir.parentFile
    }
    return null
  }
}

class ShowCommand(args: List<String>) : Command(args) {
  private val jsonOutput = "--json" in args

  override fun run() {
    val outcome =
      renderAllModules(silenceStdout = jsonOutput, gradleArguments = gradleArgsWithForce())
    if (!outcome.buildOk) {
      System.err.println("Render failed")
      System.out.flush()
      exitProcess(2)
    }

    if (outcome.manifests.isEmpty() || outcome.manifests.all { it.second.previews.isEmpty() }) {
      if (jsonOutput) println(encodeResponse(emptyList(), countsScope = emptyList()))
      else println("No previews found.")
      // Mirror ShowResourcesCommand: a workspace with the plugin applied
      // but no @Preview functions is a legitimate state (mid-adoption,
      // first-ever render in CI), not a CLI error. Returning non-zero
      // here trips `bash -e` in preview-comment.yml on the first run.
      // Flush before exit because System.exit doesn't flush stdout, and
      // the redirected file would otherwise lose this println (issue
      // #292).
      System.out.flush()
      exitProcess(0)
    }

    val all = outcome.results
    val modules = outcome.modules
    val filtered = applyFilters(all)

    if (filtered.isEmpty()) {
      // Counts reflect the full discovered set so an agent using
      // `--changed-only` can still see "60 unchanged, 0 changed"
      // and skip a follow-up query.
      if (jsonOutput) println(encodeResponse(emptyList(), countsScope = all))
      else println("No previews matched.")
      System.out.flush()
      exitProcess(3)
    }

    if (jsonOutput) {
      println(encodeResponse(filtered, countsScope = all))
    } else {
      var lastModule: String? = null
      for (r in filtered) {
        if (modules.size > 1 && r.module != lastModule) {
          println("[${r.module}]")
          lastModule = r.module
        }
        val statusTag =
          when {
            r.pngPath == null -> " [no PNG]"
            r.anyChanged() -> " [changed]"
            else -> ""
          }
        val shaTag = r.sha256?.let { "  sha=${it.take(12)}" } ?: ""
        println("${r.functionName} (${r.id})$statusTag$shaTag")
        if (r.captures.size <= 1) {
          if (r.pngPath != null) println("  ${r.pngPath}")
        } else {
          for (c in r.captures) {
            val tag =
              when {
                c.pngPath == null -> " [no PNG]"
                c.changed == true -> " [changed]"
                else -> ""
              }
            val coord =
              listOfNotNull(
                  c.advanceTimeMillis?.let { "${it}ms" },
                  c.scroll?.let { "scroll ${it.mode.lowercase()}" },
                )
                .joinToString(" · ")
                .ifEmpty { "default" }
            println("  [$coord]$tag ${c.pngPath ?: ""}")
          }
        }
      }
    }

    // "Missing" = at least one capture failed to produce a PNG.
    val missing = filtered.filter { r -> r.captures.any { it.pngPath == null } }
    if (missing.isNotEmpty()) {
      System.err.println(
        "Render task completed but produced no PNG for ${missing.size} of ${filtered.size} preview(s)."
      )
      System.err.println(
        "Check the Gradle output above — a common cause is the `composePreviewRender` task " +
          "reporting NO-SOURCE, which means the renderer test class wasn't found on " +
          "testClassesDirs."
      )
      System.out.flush()
      exitProcess(2)
    }
    System.out.flush()
  }
}

class ListCommand(args: List<String>) : Command(args) {
  private val jsonOutput = "--json" in args

  override fun run() {
    withGradle(silenceStdout = jsonOutput) { gradle ->
      lateinit var modules: List<PreviewModule>
      val buildOk =
        withGradleStdout(jsonOutput) {
          modules = resolveModules(gradle)
          val tasks = modules.map { ":${it.gradlePath}:composePreviewDiscover" }.toTypedArray()
          runGradle(gradle, *tasks)
        }

      if (!buildOk) exitProcess(1)

      val manifests = readAllManifests(modules)
      // List runs discovery only — PNGs may not exist, so sha/changed are null.
      // `--changed-only` is meaningless without rendering; ignore it here.
      val all = buildResults(manifests)
      val filtered = all.filter { matchesRequest(it) }

      if (filtered.isEmpty()) {
        if (jsonOutput) println(encodeResponse(emptyList(), countsScope = null))
        else println("No previews found.")
        exitProcess(3)
      }

      if (jsonOutput) {
        println(encodeResponse(filtered, countsScope = null))
      } else {
        for (r in filtered) {
          println("${r.id}  (${r.sourceFile ?: "unknown"})")
        }
      }
    }
  }
}

class RenderCommand(args: List<String>) : Command(args) {
  private val output: String? = args.flagValue("--output")

  override fun run() {
    withGradle { gradle ->
      val modules = resolveModules(gradle)
      val tasks = modules.map { ":${it.gradlePath}:composePreviewRenderAll" }.toTypedArray()

      if (!runGradle(gradle, *tasks, arguments = gradleArgsWithForce())) {
        reportRenderFailures(gradle)
        exitProcess(2)
      }

      val manifests = readAllManifests(modules)
      val all = buildResults(manifests)
      // `render` ignores `--changed-only` so the agent can ask "render
      // the world, but report only what changed" via a follow-up
      // `show --changed-only`.
      val filtered = all.filter { matchesRequest(it) }

      if (filtered.isEmpty()) {
        System.err.println("No previews matched.")
        exitProcess(3)
      }

      val missing = filtered.filter { r -> r.captures.any { it.pngPath == null } }

      if (output != null) {
        if (filtered.size != 1) {
          System.err.println(
            "--output requires a single match (got ${filtered.size}). " +
              "Narrow with --id <exact> or --filter <substring>."
          )
          exitProcess(1)
        }
        val one = filtered.single()
        if (one.pngPath == null) {
          System.err.println("Render produced no PNG for: ${one.id}")
          exitProcess(2)
        }
        File(one.pngPath).copyTo(File(output), overwrite = true)
        println("Rendered ${one.id} to $output")
      } else {
        val rendered = filtered.size - missing.size
        println("Rendered $rendered preview(s)")
        val changedCount = filtered.count { it.anyChanged() }
        if (changedCount > 0) println("  $changedCount changed since last run")
        if (missing.isNotEmpty()) {
          System.err.println(
            "Render task completed but produced no PNG for ${missing.size} preview(s):"
          )
          for (r in missing) System.err.println("  ${r.id}")
          exitProcess(2)
        }
      }
    }
  }
}

/**
 * Generic "render previews with extension X enabled, print extension X's canned report" command —
 * the shared shape behind `compose-preview a11y` and future per-extension commands. Looks up the
 * named [ExtensionReportRenderer] from [extensionRenderers], opts the Gradle build into the
 * extension via [implicitExtensions], runs `:composePreviewRenderAll`, then delegates the print +
 * exit policy to the renderer.
 *
 * Subclasses exist purely to bind a name to a renderer id — the entire orchestration body lives
 * here so adding a new canned-report command is a 3-line class plus a renderer registration.
 */
open class ReportCommand(args: List<String>, private val extensionId: String) : Command(args) {
  protected val jsonOutput: Boolean = "--json" in args
  // "errors" | "warnings" | "none". When not set, exit code mirrors Gradle.
  protected val failOn: String? = args.flagValue("--fail-on")

  override fun implicitExtensions(): List<String> = listOf(extensionId)

  /**
   * Subclass hook called between the gradle build and the result-building / reporting step.
   * Subclasses use this to spin up additional production paths (the daemon-driven a11y fetch in
   * [A11yCommand]) that write sidecar JSON the extension renderer's `load` pass then picks up when
   * [buildResults] runs. Default no-op.
   *
   * At the point this is called, the standard `composePreviewRenderAll` gradle task has already run
   * and each module's `previews.json` is on disk. Implementations iterate the manifests for preview
   * ids, drive any out-of-band production, and write the resulting sidecars to the conventional
   * `build/compose-previews/<extension>.json` locations the renderers read.
   */
  protected open fun produceAdditionalDataProducts(
    modules: List<PreviewModule>,
    manifests: List<Pair<PreviewModule, PreviewManifest>>,
  ) {}

  override fun run() {
    val renderer =
      extensionRenderers[extensionId]
        ?: run {
          System.err.println(
            "Unknown extension id '$extensionId'. Available: " +
              "${extensionRenderers.keys.sorted().joinToString(", ")}. " +
              "Run `compose-preview extensions list` for descriptions."
          )
          exitProcess(1)
        }

    // Hand-roll the renderModules→manifests→buildResults pipeline so the subclass hook can slot
    // between gradle finish and renderer load. `renderAllModules` is the same shape but
    // single-shot — no hook seam — so we expand it here.
    val raw = renderModules(silenceStdout = jsonOutput, gradleArguments = gradleArgsWithForce())
    val manifests = readAllManifests(raw.modules)
    produceAdditionalDataProducts(raw.modules, manifests)
    val results = if (manifests.isEmpty()) emptyList() else buildResults(manifests)
    val outcome =
      RenderModulesOutcome(
        buildOk = raw.buildOk,
        modules = raw.modules,
        manifests = manifests,
        results = results,
      )

    if (outcome.manifests.isEmpty()) {
      if (jsonOutput) println(encodeResponse(emptyList(), countsScope = null))
      else println("No previews discovered.")
      exitProcess(if (outcome.buildOk) 0 else 2)
    }

    val filtered =
      outcome.results.filter {
        matchesRequest(it) && renderer.hasData(it) && (!changedOnly || it.anyChanged())
      }

    if (jsonOutput) {
      println(encodeResponse(filtered, countsScope = null))
    } else {
      if (filtered.isEmpty()) renderer.printEmpty() else renderer.printAll(filtered)
    }

    // Threshold first: a renderer-set `--fail-on` always wins over a successful build. Renderer
    // returns null when no threshold tripped and the underlying Gradle result should decide.
    val rendererExit = renderer.thresholdExitCode(filtered, failOn)
    when (rendererExit) {
      EXIT_UNKNOWN_FAIL_ON -> {
        System.err.println("Unknown --fail-on value: $failOn (expected errors|warnings|none)")
        exitProcess(EXIT_UNKNOWN_FAIL_ON)
      }
      null -> exitProcess(if (outcome.buildOk) 0 else 2)
      else -> exitProcess(rendererExit)
    }
  }
}

/**
 * `compose-preview a11y` — `ReportCommand` bound to the built-in `a11y` extension id.
 *
 * Production of a11y data products moved entirely to the preview daemon, so this command opens a
 * short-lived [ee.schimke.composeai.render.session.RenderSession] per module after the standard
 * `composePreviewRenderAll` build completes, walks every preview through `data/fetch` for
 * `a11y/atf`, aggregates the findings into the canonical
 * `build/compose-previews/accessibility.json` shape that [A11yReportRenderer] then loads through
 * its disk-fallback path, and closes the session. The daemon is short-lived — spawned, drained,
 * shut down — so there's no persistent server for the agent / CI script to manage.
 *
 * The session is opened via the public `:render-session-api` / `:render-session-subprocess`
 * library; everything the CLI does here is reachable from any third-party tooling that compiles
 * against the same coordinates.
 */
class A11yCommand(args: List<String>) : ReportCommand(args, "a11y") {
  override fun produceAdditionalDataProducts(
    modules: List<PreviewModule>,
    manifests: List<Pair<PreviewModule, PreviewManifest>>,
  ) {
    if (modules.isEmpty()) return
    // The daemon launch descriptor (`daemon-launch.json`) is written by
    // `composePreviewDaemonStart`, which the standalone `composePreviewRenderAll` task does not
    // depend
    // on. Run it in a second gradle invocation so the descriptor is fresh against the
    // consumer's current classpath. Gradle's daemon reuses the warm JVM started by the first
    // invocation, so the cold-start cost is paid once per CLI run, not once per gradle task.
    val daemonStartOk = runDaemonStartTasks(modules)
    if (!daemonStartOk) {
      System.err.println(
        "compose-preview a11y: composePreviewDaemonStart failed; skipping daemon-driven a11y " +
          "fetch. Findings will be empty."
      )
      return
    }
    val fetcher = DaemonA11yFetcher(onLog = { System.err.println("[daemon a11y] $it") })
    for ((module, manifest) in manifests) {
      val previewIds = manifest.previews.map { it.id }
      if (previewIds.isEmpty()) continue
      val outcome =
        fetcher.fetch(
          projectDir = module.projectDir,
          modulePath = module.gradlePath,
          moduleName = manifest.module,
          previewIds = previewIds,
        )
      when (outcome) {
        is DaemonA11yFetcher.Outcome.Ok ->
          if (verbose) {
            System.err.println(
              "compose-preview a11y: ${module.gradlePath} wrote ${outcome.reportFile.path} " +
                "(${outcome.entryCount} entr${if (outcome.entryCount == 1) "y" else "ies"})"
            )
          }
        is DaemonA11yFetcher.Outcome.DescriptorMissing ->
          System.err.println(
            "compose-preview a11y: ${module.gradlePath} missing daemon-launch.json at " +
              "${outcome.expected.path}"
          )
        is DaemonA11yFetcher.Outcome.OpenFailed ->
          System.err.println(
            "compose-preview a11y: ${module.gradlePath} failed to open render session " +
              "(${outcome.reason})"
          )
      }
    }
  }

  /**
   * Drive `:<modulePath>:composePreviewDaemonStart` for every module so each one has a fresh
   * `daemon-launch.json` on disk before the per-module session opens. Returns false when the gradle
   * task itself failed; the caller falls through to "no findings" rather than blocking the user.
   */
  private fun runDaemonStartTasks(modules: List<PreviewModule>): Boolean {
    var ok = true
    withGradle(silenceStdout = jsonOutput) { gradle ->
      val tasks = modules.map { ":${it.gradlePath}:composePreviewDaemonStart" }.toTypedArray()
      ok =
        withGradleStdout(jsonOutput) {
          runGradle(gradle, *tasks, arguments = gradleArgsWithForce())
        }
    }
    return ok
  }
}

// `sha256` / `previewSha256` / `gifBookendFrameSha256` carved out to `:gradle-preview-driver`
// alongside `PreviewResultBuilder` — the same hash function the driver returns to external
// consumers, so CLI state files stay compatible with contrib-side tooling. Re-imported from
// the same package so existing callers (`PreviewSha256Test`, in-CLI usage) don't need to change.

private data class ImageSizeOverride(val maxEdgePx: Int?) {
  companion object {
    fun detect(env: Map<String, String> = System.getenv()): ImageSizeOverride {
      if (
        !env["CLAUDE_CODE_SESSION_ID"].isNullOrBlank() || !env["CLAUDE_ENV_FILE"].isNullOrBlank()
      ) {
        return ImageSizeOverride(maxEdgePx = 2000)
      }
      if (
        env["__CFBundleIdentifier"] == "com.google.antigravity" ||
          !env["ANTIGRAVITY_CLI_ALIAS"].isNullOrBlank()
      ) {
        return ImageSizeOverride(maxEdgePx = 3072)
      }
      if (!env["CODEX_SANDBOX"].isNullOrBlank() || !env["CODEX_SESSION_ID"].isNullOrBlank()) {
        return ImageSizeOverride(maxEdgePx = 3072)
      }
      return ImageSizeOverride(maxEdgePx = null)
    }
  }
}

private fun applyImageSizeOverride(file: File, override: ImageSizeOverride): File {
  val maxEdgePx = override.maxEdgePx ?: return file
  val source = runCatching { ImageIO.read(file) }.getOrNull() ?: return file
  if (source.width <= maxEdgePx && source.height <= maxEdgePx) return file
  val scale = minOf(maxEdgePx.toDouble() / source.width, maxEdgePx.toDouble() / source.height)
  val targetWidth = maxOf(1, kotlin.math.floor(source.width * scale).toInt())
  val targetHeight = maxOf(1, kotlin.math.floor(source.height * scale).toInt())
  val target = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB)
  val g = target.createGraphics()
  try {
    g.setRenderingHint(
      java.awt.RenderingHints.KEY_INTERPOLATION,
      java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR,
    )
    g.setRenderingHint(
      java.awt.RenderingHints.KEY_RENDERING,
      java.awt.RenderingHints.VALUE_RENDER_QUALITY,
    )
    g.setRenderingHint(
      java.awt.RenderingHints.KEY_ANTIALIASING,
      java.awt.RenderingHints.VALUE_ANTIALIAS_ON,
    )
    g.drawImage(source, 0, 0, targetWidth, targetHeight, null)
  } finally {
    g.dispose()
  }
  ImageIO.write(target, "png", file)
  return file
}

// `previewSha256`, `gifBookendFrameSha256`, `framesToBytes`, `sha256` carved out to
// `:gradle-preview-driver/PreviewSha256.kt`. Same package, same callers, just lives in the
// driver module now so contrib consumers get the same change-detection hash.
