package ee.schimke.composeai.cli

import ee.schimke.composeai.bundle.BUNDLE_FIGMA_SVG_SUFFIX
import ee.schimke.composeai.bundle.BundleReader
import ee.schimke.composeai.bundle.injectFigmaFontWarningsIntoBundle
import ee.schimke.composeai.bundle.injectFigmaRasterIntoBundle
import ee.schimke.composeai.bundle.injectFigmaSvgIntoBundle
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.previewdata.CaptureResult
import ee.schimke.composeai.previewdata.PreviewInfo
import ee.schimke.composeai.previewdata.PreviewManifest
import ee.schimke.composeai.previewdata.PreviewModule
import ee.schimke.composeai.previewdata.PreviewResult
import ee.schimke.composeai.previewdata.PreviewResultBuilder
import ee.schimke.composeai.previewdata.previewSha256
import ee.schimke.composeai.previewdriver.GradleAccessFailure
import ee.schimke.composeai.previewdriver.GradleConnection
import ee.schimke.composeai.previewdriver.GradleTaskOutcome
import ee.schimke.composeai.previewdriver.ProjectDiscoveryFailure
import ee.schimke.composeai.previewdriver.printCapturedTestFailures
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import kotlin.system.exitProcess
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * On-disk shape mirrors gradle-plugin/PreviewData.kt (parsed with ignoreUnknownKeys). The wire DTOs
 * live in `:preview-data-api` under this same package so external tooling can compile against them.
 */

/**
 * Versioned envelope for `compose-preview show|list|a11y --json`; bump [SHOW_LIST_SCHEMA] when the
 * per-row shape changes. [counts] (from `show`/`a11y`) lets agents skip downloading unchanged PNGs.
 */
@Serializable
data class PreviewListResponse(
  val schema: String = SHOW_LIST_SCHEMA,
  val previews: List<PreviewResult>,
  val counts: PreviewCounts? = null,
)

/**
 * The `counts` block of a `show` / `a11y` JSON envelope. The four buckets partition [total]:
 * `changed + unchanged + missing + skipped == total` (asserted in `PreviewCountsPartitionTest`).
 *
 * @property changed at least one capture's sha256 differs from the previous run.
 * @property unchanged nothing changed, and at least one capture has a PNG.
 * @property missing no PNG at all, and the miss is a render failure — the set `--missing-renders`
 *   gates on (see [previewsMissingPng]).
 * @property skipped no PNG at all, and the miss is expected: every absent capture is `optional`, or
 *   the preview is one of [NON_PNG_PREVIEW_KINDS].
 */
@Serializable
data class PreviewCounts(
  val total: Int,
  val changed: Int,
  val unchanged: Int,
  val missing: Int,
  val skipped: Int = 0,
)

/**
 * Compact `--brief` row: drops metadata the agent already has and shortens keys (`png` = absolute
 * path, `sha` = first 12 hex of sha256, `time` = advanceTimeMillis, `scroll` = scroll mode).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class BriefPreviewListResponse(
  // Always encoded so brief mode (encodeDefaults=false) still emits the version pin.
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

// `/v2`: `PreviewResult.a11yFindings` + `a11yAnnotatedPath` were removed in favour of
// `dataExtensions["a11y"]`. The brief format stays `/v1` since its wire shape is unchanged.
internal const val SHOW_LIST_SCHEMA = "compose-preview-show/v2"
internal const val SHOW_LIST_BRIEF_SCHEMA = "compose-preview-show-brief/v1"

@Serializable private data class CliState(val shas: Map<String, String> = emptyMap())

private val json = Json {
  ignoreUnknownKeys = true
  prettyPrint = true
  encodeDefaults = true
}

/** `--brief` JSON: single-line and `encodeDefaults = false`, so null/false/0 fields drop out. */
private val briefJson = Json {
  ignoreUnknownKeys = true
  prettyPrint = false
  encodeDefaults = false
}

abstract class Command(
  protected val args: List<String>,
  protected val fileSystem: FileSystem = SystemFileSystem,
) {
  // These selectors are `public` because `:cli:serve`'s `ServeOptions` declares them.
  val explicitModule: String? = args.flagValue("--module")
  val filter: String? = args.flagValue("--filter")
  val exactId: String? = args.flagValue("--id")

  /**
   * `--preview <ref>`: the loose "preview reference" selector, spelled as in `record`, `history
   * list` and Gradle's `composePreviewRender --preview`.
   *
   * Selects a preview when the ref equals its id, `<className>.<functionName>` or bare
   * `functionName`, or is a case-insensitive substring of its id ([previewMatchesReference]). May
   * select several and never fails on ambiguity. Combined selectors intersect.
   */
  val previewRef: String? = args.flagValue("--preview")?.takeIf { it.isNotBlank() }

  /**
   * `--id-file <path>`: an exact set of declared preview ids, one per line, e.g. the previews a PR
   * changed. Intersects with the other selectors and narrows the Gradle render
   * ([PreviewRenderScope]); `bundle pack` reads the same format. A missing, unreadable or empty
   * file is an error, since "no selector" would act on every preview.
   */
  val idFileIds: Set<String>? by lazy {
    val path =
      args.flagValuesAll("--id-file").lastOrNull()?.trim()?.takeIf(String::isNotEmpty)
        ?: return@lazy null
    val ids = runCatching {
      readIdFile(File(path))
    }
      .getOrElse {
        System.err.println("compose-preview: --id-file: ${it.message}")
        exitProcess(2)
      }
    ids
  }

  /** Whether any preview selector was passed. */
  internal val hasPreviewRequest: Boolean
    get() = exactId != null || filter != null || previewRef != null || idFileIds != null

  /**
   * Whether this command can expand a `@PreviewParameter` fan-out into row ids after rendering and
   * re-filter, and so wants module selection to keep modules whose rows might match
   * ([previewMatchesRequestIncludingRows]). Extension commands (`a11y` etc.) only know declared
   * ids, so they keep the strict lane and fail fast on a row selector.
   */
  open val rowAwareSelection: Boolean
    get() = false

  protected val verbose: Boolean = "--verbose" in args || "-v" in args
  protected val progress: Boolean = verbose || "--progress" in args
  val timeoutSeconds: Long =
    args.flagValue("--timeout")?.toLongOrNull() ?: GradleConnection.DEFAULT_TIMEOUT_SECONDS
  /** When true, drop previews with no `changed=true` capture from JSON output. */
  protected val changedOnly: Boolean = "--changed-only" in args
  /**
   * Compact JSON: rows keep only `id` + `captures`, for agent loops that already cached the
   * metadata.
   */
  protected val brief: Boolean = "--brief" in args

  /**
   * `--force=<reason>`: re-run render tasks via `--rerun-tasks` when an agent suspects a stale
   * render. Never runs `:clean` or touches `build/classes/` — the alternative this replaces. Each
   * use is logged to stderr with a pointer to issue #924 for reporting the freshness gap.
   */
  protected val forceReason: String? = args.flagValue("--force")?.takeIf { it.isNotBlank() }

  /**
   * `--variant <name>`, forwarded as `-PcomposePreview.variant=<name>` on every Gradle invocation
   * (model queries and tasks) for flavored modules with no plain `debug` variant.
   */
  protected val variantOverride: String? =
    args.flagValue("--variant")?.trim()?.takeIf { it.isNotEmpty() }

  protected fun variantGradleArgs(): List<String> {
    val v = variantOverride ?: return emptyList()
    return listOf("-PcomposePreview.variant=$v")
  }

  /**
   * Extensions requested via `--with-extension` (repeatable, comma-separated, or `=`-form),
   * forwarded as `-PcomposePreview.activeExtensions=<list>`. The plugin ignores it today (opt-in
   * data products are daemon-only); it is the record of what the invocation requested.
   */
  protected val requestedExtensions: List<String> =
    (args.flagValuesAll("--with-extension") + args.flagValuesAll("--with"))
      .flatMap { raw -> raw.split(',', ';') }
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .distinct()

  protected val permutations: List<String> =
    PreviewPermutationsCli.clean(args.flagValuesAll("--permutations"))

  protected fun permutationsGradleArgs(): List<String> =
    if (permutations.isEmpty()) emptyList()
    else listOf("-P${PreviewPermutationsCli.PROPERTY}=${permutations.joinToString(",")}")

  /** Extensions a command always wants (e.g. `A11yCommand` returns `["a11y"]`). */
  protected open fun implicitExtensions(): List<String> = emptyList()

  /**
   * `-PcomposePreview.activeExtensions=<list>` for the union of [implicitExtensions] and
   * [requestedExtensions], or empty when there are none.
   */
  protected fun extensionGradleArgs(): List<String> {
    val all = (implicitExtensions() + requestedExtensions).distinct().filter { it.isNotEmpty() }
    if (all.isEmpty()) return emptyList()
    return listOf("-PcomposePreview.activeExtensions=${all.joinToString(",")}")
  }

  /**
   * `--missing-renders <fail|warn|ignore>`, passed through as `-PcomposePreview.missingRenders`.
   * Unvalidated (unknown values hit the plugin's "fail" default); absent means don't pass it.
   */
  protected val missingRendersPolicy: String? =
    args.flagValue("--missing-renders")?.trim()?.takeIf { it.isNotEmpty() }

  protected fun missingRendersGradleArgs(): List<String> {
    val v = missingRendersPolicy ?: return emptyList()
    return listOf("-PcomposePreview.missingRenders=$v")
  }

  /**
   * Whether the CLI's own missing-PNG post-check exits non-zero, mirroring the plugin's
   * `composePreview.missingRenders` policy; unknown values mean "fail".
   */
  protected fun shouldFailOnMissingRenders(): Boolean =
    when (missingRendersPolicy?.lowercase()) {
      "warn",
      "ignore" -> false
      else -> true
    }

  private val forceNoticePrinted = AtomicBoolean(false)

  /**
   * Gradle args for a render-pipeline task: `--rerun-tasks` when [forceReason] is set (with a
   * one-time stderr notice), plus [extensionGradleArgs].
   */
  protected fun gradleArgsWithForce(extra: List<String> = emptyList()): List<String> {
    val extensionArgs = extensionGradleArgs()
    val missingRendersArgs = missingRendersGradleArgs()
    val permutationsArgs = permutationsGradleArgs()
    // `--write-locks` must be here too: `bundle pack` runs its tasks through this helper.
    val withExtras =
      extra + extensionArgs + missingRendersArgs + permutationsArgs + gradleWriteLocksArgs()
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
          val here = File(".").absoluteFile.normalize().path
          System.err.println(
            "Cannot find Gradle project root: no settings.gradle[.kts] and no gradlew at or " +
              "above $here"
          )
          exitProcess(1)
        }
    noteResolvedProjectRoot(root)
    val injectArgs = autoInjectInitScriptArgs(args, projectRoot = root)
    val connection =
      withGradleStdout(silenceStdout) {
        // `--variant` goes on the connection so the model query sees the same variant as task runs;
        // a flavored module's tasks only register under it.
        GradleConnection(
            root,
            verbose,
            progress,
            extraArguments = injectArgs + variantGradleArgs() + gradleWriteLocksArgs(),
          )
          .apply { failureAdvice = ::buildFailureAdvice }
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
      // Resolved via the Tooling API so nested paths and custom `projectDir`s work.
      val one = gradle.findPreviewModule(explicitModule, timeoutSeconds)
      if (one == null) {
        gradle.lastModelAccessFailure?.let {
          System.err.println(
            "Could not query Gradle project model while resolving module '$explicitModule'."
          )
          System.err.println("Gradle ${it.operation} failed: ${it.message}")
          it.detail?.let { detail -> System.err.println("Caused by: $detail") }
          printModelAccessAdvice(it)
          exitProcess(1)
        }
        System.err.println(
          "Module '$explicitModule' not found or does not apply the compose-ai-tools plugin."
        )
        printDiscoveryFailures(
          gradle.lastDiscoveryFailures,
          pluginVersion = injectedPluginVersion,
          pluginVersionSource = injectedPluginVersionSource,
          timeoutSeconds = timeoutSeconds,
        )
        exitProcess(1)
      }
      return listOf(one)
    }

    val modules = gradle.findPreviewModules(timeoutSeconds)
    if (modules.isEmpty()) {
      gradle.lastModelAccessFailure?.let {
        System.err.println("Could not query Gradle project model.")
        System.err.println("Gradle ${it.operation} failed: ${it.message}")
        it.detail?.let { detail -> System.err.println("Caused by: $detail") }
        printModelAccessAdvice(it)
        exitProcess(1)
      }
      System.err.println("No preview modules discovered in this project.")
      printDiscoveryFailures(
        gradle.lastDiscoveryFailures,
        pluginVersion = injectedPluginVersion,
        pluginVersionSource = injectedPluginVersionSource,
        timeoutSeconds = timeoutSeconds,
      )
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
   * Auto-provision the native `xr-composite` binary into the shared cache, only when a module's
   * `previews.json` contains an `XR_SUBSPACE` preview (so non-XR renders never hit the network).
   * Failures log a note and the render proceeds (compositing is best-effort).
   *
   * Returns `-PcomposePreview.xrCompositeBinary=<path>` when provisioned, else empty.
   */
  /**
   * Run `:<module>:composePreviewDiscover` per module as its own invocation before the render, so
   * `shards = auto` (resolved at configuration time from `previews.json`) sizes correctly on a cold
   * runner. Failure is non-fatal: the render re-runs discover and surfaces the real error.
   */
  protected fun runDiscover(
    gradle: GradleConnection,
    modules: List<PreviewModule>,
    silenceStdout: Boolean,
  ): Boolean =
    withGradleStdout(silenceStdout) {
      val tasks = modules.map { ":${it.gradlePath}:composePreviewDiscover" }.toTypedArray()
      runGradle(gradle, *tasks)
    }

  protected fun provisionXrCompositeArgs(
    gradle: GradleConnection,
    modules: List<PreviewModule>,
    silenceStdout: Boolean,
    discoverFirst: Boolean = true,
  ): List<String> {
    if (modules.isEmpty()) return emptyList()
    // Discover first so previews.json exists; failure just skips provisioning.
    if (discoverFirst && !runDiscover(gradle, modules, silenceStdout)) return emptyList()
    val hasXr =
      readAllManifests(modules).any { (_, manifest) ->
        manifest.previews.any { it.params.kind == XrCompositeProvision.XR_SUBSPACE_KIND }
      }
    if (!hasXr) return emptyList()
    val binary = XrCompositeProvision.ensureCached(XR_COMPOSITE_VERSION) ?: return emptyList()
    return listOf("-PcomposePreview.xrCompositeBinary=${binary.absolutePath}")
  }

  /**
   * Outcome of [renderAllModules]: Gradle build result plus merged [PreviewResult]s. [buildOk] is
   * data rather than an early return because some callers (`a11y`) still report partial results.
   */
  protected data class RenderModulesOutcome(
    val buildOk: Boolean,
    val modules: List<PreviewModule>,
    val manifests: List<Pair<PreviewModule, PreviewManifest>>,
    val results: List<PreviewResult>,
    val discoveredPreviewCount: Int,
    /**
     * Per-task dispositions from the Tooling API, so reporting can tell a fresh failure from a
     * stale `.error.json` left by a skipped task ([renderTaskEvidenceOf]).
     */
    val taskOutcomes: Map<String, GradleTaskOutcome> = emptyMap(),
    /**
     * Preview ids this run rendered, or null for everything. When narrowed, other previews show
     * whatever a previous run left, which must not be reported as render failures.
     */
    val renderedIds: Set<String>? = null,
  )

  /**
   * Lower-level outcome of [renderModules] — build result plus module list — for commands with
   * their own manifest shape (`show-resources`).
   */
  protected data class RawRenderOutcome(
    val buildOk: Boolean,
    val modules: List<PreviewModule>,
    val discoveredPreviewCount: Int,
    val taskOutcomes: Map<String, GradleTaskOutcome> = emptyMap(),
    /**
     * Preview ids this run asked Gradle to render, or null for everything; lets [buildResults] keep
     * the skipped previews' state shas ([PreviewRenderScope]).
     */
    val renderedIds: Set<String>? = null,
  )

  /**
   * Shared Gradle drive: resolve preview modules, optionally filter them, run a per-module task,
   * report failures.
   *
   * [moduleFilter] narrows participating modules (e.g. `show-resources` keeps Android-only ones).
   * [taskFor] builds the per-module task path. An empty module list skips Gradle with `buildOk =
   * true`.
   */
  protected fun renderModules(
    silenceStdout: Boolean,
    moduleFilter: (PreviewModule) -> Boolean = { true },
    taskFor: (PreviewModule) -> String = { ":${it.gradlePath}:composePreviewRenderAll" },
    gradleArguments: List<String> = emptyList(),
    scopeToPreviewRequest: Boolean = false,
  ): RawRenderOutcome {
    var outcome: RawRenderOutcome? = null
    withGradle(silenceStdout = silenceStdout) { gradle ->
      val modules = withGradleStdout(silenceStdout) { resolveModules(gradle).filter(moduleFilter) }
      outcome =
        if (modules.isEmpty()) RawRenderOutcome(true, emptyList(), 0)
        else {
          // Explicit discover so `shards=auto` sees a fresh previews.json (see [runDiscover]);
          // provisionXr then skips its own discover.
          val discoverySucceeded = runDiscover(gradle, modules, silenceStdout)
          val discoveryManifests =
            if (discoverySucceeded) readAllManifests(modules) else emptyList()
          val renderModules =
            if (scopeToPreviewRequest) {
              modulesMatchingPreviewRequest(
                modules,
                discoveryManifests,
                discoverySucceeded = discoverySucceeded,
              )
            } else modules
          // Narrow the render to the requested previews; only for preview manifests, not
          // `show-resources`.
          val scope =
            if (scopeToPreviewRequest)
              previewRenderScope(renderModules, discoveryManifests, discoverySucceeded)
            else PreviewRenderScope.FULL
          val xrArgs =
            provisionXrCompositeArgs(gradle, renderModules, silenceStdout, discoverFirst = false)
          val tasks = renderModules.map(taskFor).toTypedArray()
          val ok =
            if (renderModules.isEmpty()) true
            else {
              withGradleStdout(silenceStdout) {
                runGradle(gradle, *tasks, arguments = gradleArguments + xrArgs + scope.gradleArgs)
              }
            }
          if (!ok) reportRenderFailures(gradle)
          val outcomes = gradle.lastTaskOutcomes()
          RawRenderOutcome(
            buildOk = ok,
            modules = readableRenderModules(renderModules, taskFor, outcomes),
            discoveredPreviewCount =
              if (scopeToPreviewRequest) discoveryManifests.sumOf { it.second.previews.size }
              else 0,
            taskOutcomes = outcomes,
            renderedIds = scope.renderedIds,
          )
        }
    }
    return outcome ?: error("renderModules: gradle block did not produce an outcome")
  }

  /**
   * The `-PcomposePreview.idFilter` / `idFilterFile` narrowing for this invocation's selectors,
   * plus its stderr diagnostics ([PreviewRenderScope]).
   *
   * Returns [PreviewRenderScope.FULL] whenever the request can't be resolved to exact ids: no
   * filter, no modules, or failed discovery (a stale manifest must never narrow the render).
   */
  internal fun previewRenderScope(
    renderModules: List<PreviewModule>,
    discoveryManifests: List<Pair<PreviewModule, PreviewManifest>>,
    discoverySucceeded: Boolean,
  ): PreviewRenderScope.Scope {
    if (!hasPreviewRequest) return PreviewRenderScope.FULL
    if (renderModules.isEmpty()) return PreviewRenderScope.FULL
    val flag =
      when {
        exactId != null -> "--id"
        filter != null -> "--filter"
        previewRef != null -> "--preview"
        else -> "--id-file"
      }
    if (!discoverySucceeded) {
      System.err.println(
        "compose-preview: preview discovery failed, so $flag could not narrow the render; " +
          "rendering all resolved modules so Gradle can retry discovery."
      )
      return PreviewRenderScope.FULL
    }
    val byPath = renderModules.map { it.gradlePath }.toSet()
    val scope =
      PreviewRenderScope.forRequest(
        manifests = discoveryManifests.filter { (module, _) -> module.gradlePath in byPath },
        exactId = exactId,
        filter = filter,
        previewRef = previewRef,
        permutations = permutations,
        rowAware = rowAwareSelection,
        ids = idFileIds,
      )
    scope.note?.let { System.err.println("compose-preview: $flag $it; rendering the full module.") }
    if (verbose && scope.narrowed) {
      System.err.println(
        "compose-preview: $flag narrowed the render to ${scope.renderedIds?.size ?: 0} preview(s) " +
          "via ${scope.gradleArgs.first().substringBefore('=')}"
      )
    }
    return scope
  }

  /**
   * Discover → `:composePreviewRenderAll` → read manifests → build results, used by `show` and
   * `a11y`. [silenceStdout] (the `--json` flag) redirects Gradle output to stderr to keep JSON
   * clean.
   */
  protected fun renderAllModules(
    silenceStdout: Boolean,
    gradleArguments: List<String> = emptyList(),
  ): RenderModulesOutcome {
    val raw =
      renderModules(
        silenceStdout = silenceStdout,
        gradleArguments = gradleArguments,
        scopeToPreviewRequest = true,
      )
    val manifests = readAllManifests(raw.modules)
    val results = if (manifests.isEmpty()) emptyList() else buildResults(manifests, raw.renderedIds)
    return RenderModulesOutcome(
      buildOk = raw.buildOk,
      modules = raw.modules,
      manifests = manifests,
      results = results,
      discoveredPreviewCount =
        raw.discoveredPreviewCount.takeIf { it > 0 } ?: manifests.sumOf { it.second.previews.size },
      taskOutcomes = raw.taskOutcomes,
      renderedIds = raw.renderedIds,
    )
  }

  /**
   * Print failing tests captured by the Tooling API listener, since Gradle's report link is
   * unreachable from CI logs.
   */
  protected fun reportRenderFailures(gradle: GradleConnection) {
    printCapturedTestFailures(gradle.lastTestFailures())
  }

  protected fun readManifest(module: PreviewModule): PreviewManifest? =
    PreviewResultBuilder.readManifest(module)?.let {
      PreviewPermutationsCli.expandManifest(it, permutations)
    }

  protected fun readAllManifests(
    modules: List<PreviewModule>
  ): List<Pair<PreviewModule, PreviewManifest>> =
    PreviewResultBuilder.readAllManifests(modules).map { (module, manifest) ->
      module to PreviewPermutationsCli.expandManifest(manifest, permutations)
    }

  /**
   * Per-invocation extension renderers, applied by [buildResults]; [ReportCommand] reuses instances
   * from this map so decoded state is shared.
   */
  protected val extensionRenderers: Map<String, ExtensionReportRenderer> =
    builtInExtensionReporters().mapValues { (_, factory) -> factory() }

  /** Load every extension's sidecar JSON; later [annotateExtensions] calls are lookups. */
  private fun loadExtensionReports(manifests: List<Pair<PreviewModule, PreviewManifest>>) {
    for (renderer in extensionRenderers.values) {
      renderer.load(manifests, verbose)
    }
  }

  /**
   * Apply every loaded renderer's [ExtensionReportRenderer.annotate] in registration order; each
   * returns a copy, so extensions layer.
   */
  private fun annotateExtensions(result: PreviewResult, module: PreviewModule): PreviewResult {
    var enriched = result
    for (renderer in extensionRenderers.values) {
      enriched = renderer.annotate(enriched, module)
    }
    return enriched
  }

  /**
   * Build per-capture results via [PreviewResultBuilder], then layer CLI-only concerns:
   * 1. [ImageSizeOverride] — resize oversized PNGs in place and recompute their sha256.
   * 2. State diff — fill `changed` from the module's `.cli-state.json` (key `<id>` for the first
   *    capture, `<id>#<n>` for later ones) and write the new shas back.
   * 3. Extension annotation from every registered [ExtensionReportRenderer].
   *
   * Top-level `pngPath` / `sha256` / `changed` mirror the first capture. [renderedIds] names what
   * was re-rendered (null = all); skipped previews keep their previous shas.
   */
  protected fun buildResults(
    manifests: List<Pair<PreviewModule, PreviewManifest>>,
    renderedIds: Set<String>? = null,
  ): List<PreviewResult> {
    val base = PreviewResultBuilder.build(manifests)
    val imageSizeOverride = ImageSizeOverride.detect()
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
        val skipped = renderedIds != null && result.id !in renderedIds
        val overlayed = applyImageOverrideAndStateDiff(result, prior, updated, imageSizeOverride)
        if (skipped) carryForwardSkippedState(result.id, prior, updated)
        out += annotateExtensions(overlayed, module)
      }
      writeState(module, CliState(updated))
    }
    return out
  }

  /**
   * Keep `.cli-state.json` entries for [id] this run didn't rewrite, so a narrowed render doesn't
   * make the next full render report skipped previews as changed.
   *
   * Works from the prior keys, not captures: an unrendered `@PreviewParameter` preview has no
   * captures at all. Scoped to the `<id>` / `<id>#…` family; `putIfAbsent` lets fresh shas win.
   */
  private fun carryForwardSkippedState(
    id: String,
    prior: Map<String, String>,
    updated: MutableMap<String, String>,
  ) {
    for ((key, sha) in prior) {
      if (key == id || key.startsWith("$id#")) updated.putIfAbsent(key, sha)
    }
  }

  /**
   * Resize oversized PNGs ([ImageSizeOverride]), then set `changed` per capture from the prior
   * state; records new shas in [updated].
   */
  private fun applyImageOverrideAndStateDiff(
    base: PreviewResult,
    prior: Map<String, String>,
    updated: MutableMap<String, String>,
    imageSizeOverride: ImageSizeOverride,
  ): PreviewResult {
    // A resize rewrites the file, so recompute shas when the override is active.
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
  protected fun PreviewResult.anyChanged(): Boolean = hasChangedCapture()

  /** Filters by `--id` / `--filter` and (optionally) `--changed-only`. */
  protected fun applyFilters(all: List<PreviewResult>): List<PreviewResult> =
    selectRequested(all).filter { !changedOnly || it.anyChanged() }

  /**
   * This invocation's selectors applied to rendered results, honouring row ids
   * ([selectRequestedResults]). `--changed-only` is applied afterwards.
   */
  protected fun selectRequested(all: List<PreviewResult>): List<PreviewResult> =
    selectRequestedResults(
      all,
      exactId = exactId,
      filter = filter,
      previewRef = previewRef,
      ids = idFileIds,
    )

  /**
   * @param results rows to emit (after `--id`/`--filter`/`--changed-only`)
   * @param countsScope rows to compute [PreviewCounts] from — typically the unfiltered set; `null`
   *   omits counts.
   */
  protected fun encodeResponse(
    results: List<PreviewResult>,
    countsScope: List<PreviewResult>?,
  ): String {
    val counts = countsScope?.let { countsOf(it) }
    if (brief) {
      val multiModule = results.map { it.module }.distinct().size > 1
      val brief = results.map { r ->
        // Null when ATF didn't run for the module, distinct from `0`.
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

  private fun countsOf(results: List<PreviewResult>): PreviewCounts = previewCountsOf(results)

  protected fun matchesRequest(preview: PreviewInfo): Boolean =
    previewIdMatchesRequest(
      preview.id,
      exactId = exactId,
      filter = filter,
      previewRef = previewRef,
      className = preview.className,
      functionName = preview.functionName,
      ids = idFileIds,
    )

  /**
   * Id-only overload; `--preview` forms needing class/function metadata don't apply. Prefer the
   * [PreviewInfo] / [PreviewResult] overloads.
   */
  protected fun matchesRequest(id: String): Boolean =
    previewIdMatchesRequest(
      id,
      exactId = exactId,
      filter = filter,
      previewRef = previewRef,
      ids = idFileIds,
    )

  /**
   * The ids in [manifest] this invocation's selectors ask about (all ids when there is no request),
   * for paths that fan out per preview before results exist.
   *
   * Takes and returns unexpanded discovery ids, because the daemon only knows those, but matches
   * against [PreviewPermutationsCli]-expanded ids, because that's what users see: `--id Foo_dark`
   * selects `Foo`.
   */
  protected fun requestedPreviewIds(manifest: PreviewManifest): List<String> =
    manifest.previews
      .filter { preview ->
        PreviewPermutationsCli.expand(listOf(preview), permutations).any { matchesRequest(it) }
      }
      .map { it.id }

  private fun modulesMatchingPreviewRequest(
    modules: List<PreviewModule>,
    manifests: List<Pair<PreviewModule, PreviewManifest>>,
    discoverySucceeded: Boolean,
  ): List<PreviewModule> =
    modulesMatchingPreviewRequest(
      modules = modules,
      manifests = manifests,
      exactId = exactId,
      filter = filter,
      previewRef = previewRef,
      discoverySucceeded = discoverySucceeded,
      rowAware = rowAwareSelection,
      ids = idFileIds,
    )

  private fun stateFile(module: PreviewModule): File =
    module.projectDir.resolve("build/compose-previews/.cli-state.json")

  private fun readState(module: PreviewModule): CliState {
    val f = stateFile(module)
    if (!f.exists()) return CliState()
    return try {
      val text = fileSystem.read(f.path.toPath()) { readUtf8() }
      json.decodeFromString(CliState.serializer(), text)
    } catch (e: Exception) {
      if (verbose)
        System.err.println("Warning: corrupt state file ${f.path}, resetting: ${e.message}")
      CliState()
    }
  }

  private fun writeState(module: PreviewModule, state: CliState) {
    val f = stateFile(module)
    f.parentFile?.mkdirs()
    fileSystem.write(f.path.toPath()) {
      writeUtf8(json.encodeToString(CliState.serializer(), state))
    }
  }

  protected fun findProjectRoot(): File? = findGradleProjectRoot()

  /** `~/.compose-preview/settings.json` defaults ([CliPreviewSettings]), read once per command. */
  protected val previewSettings: CliPreviewSettings by lazy { CliPreviewSettingsFile.read() }

  /**
   * `show` / `render` draw previews exactly as declared, so display settings can't apply; say so
   * once.
   */
  protected fun noteUnappliedSettings(command: String) {
    unappliedSettingsNote(command, previewSettings)?.let { System.err.println(it) }
  }

  /** The version pin in force for this run, resolved once for [buildFailureAdvice]. */
  private val resolvedPin: ResolvedVersionPin? by lazy {
    resolveVersionPin(findProjectRoot(), args)
  }

  /**
   * The plugin version this run injects (pin, else the CLI's Maven line). Must agree with
   * [autoInjectInitScriptArgs] so [buildFailureAdvice] names the coordinate that actually failed.
   */
  protected val injectedPluginVersion: String
    get() = resolvedPin?.version ?: MAVEN_LINE_VERSION

  /** Where [injectedPluginVersion] came from, or null when it is simply the default line. */
  protected val injectedPluginVersionSource: String?
    get() = resolvedPin?.source?.display

  /**
   * Advice hook for every [GradleConnection]: recognises the plugin marker failing to resolve and
   * explains the publication window rather than blaming the user's build.
   */
  protected fun buildFailureAdvice(failureText: String): String? =
    pluginResolutionGuidance(failureText, injectedPluginVersion, injectedPluginVersionSource)

  /** Print what to do about a failed Gradle model query. */
  private fun printModelAccessAdvice(failure: GradleAccessFailure) {
    val text = listOfNotNull(failure.message, failure.detail).joinToString("\n")
    val advice = buildFailureAdvice(text)
    if (advice != null) {
      System.err.println()
      System.err.println(advice)
    } else {
      System.err.println(
        "Check Gradle wrapper/cache access, then rerun with --verbose for full output."
      )
    }
  }

  /**
   * Name the build the CLI resolved when it isn't the invocation directory (once, on stderr), so
   * diagnostics about an unexpected build aren't mysterious.
   */
  private fun noteResolvedProjectRoot(root: File) {
    val cwd = File(".").absoluteFile.normalize()
    if (root.absoluteFile.normalize() == cwd) return
    if (!projectRootNotePrinted.compareAndSet(false, true)) return
    System.err.println(
      "compose-preview: driving the Gradle build at ${root.path} (from ${cwd.path})."
    )
  }
}

/** One [Command.noteResolvedProjectRoot] line per process, however many connections it opens. */
private val projectRootNotePrinted = AtomicBoolean(false)

/**
 * Preview kinds that legitimately never emit a PNG, so a null `pngPath` isn't a render failure. XR
 * subspace previews render to `scene.json`; the composite PNG needs an optional binary.
 *
 * Keep in sync with `NON_PNG_PREVIEW_KINDS` in `.github/actions/lib/compare-previews.py`.
 */
/**
 * Print per-project discovery failures to stderr after an empty discovery, so the user sees why.
 * Capped for large builds; no-op without failures.
 */
internal fun printDiscoveryFailures(
  failures: List<ProjectDiscoveryFailure>,
  limit: Int = 10,
  err: (String) -> Unit = System.err::println,
  pluginVersion: String? = null,
  pluginVersionSource: String? = null,
  timeoutSeconds: Long? = null,
) {
  if (failures.isEmpty()) return
  // Cancellation leads: cancelled projects were never configured, so nothing about them was
  // observed.
  discoveryTimeoutGuidance(failures, timeoutSeconds)?.let {
    err(it)
    val matching = failures.count { f -> isDiscoveryCancellationFailure(f.message) }
    if (matching * 2 >= failures.size) return
  }
  // An unresolved plugin marker leads, and replaces the rest only when it accounts for at least
  // half the failures.
  val markerGuidance = failures.firstNotNullOfOrNull {
    pluginResolutionGuidance(it.message, pluginVersion, pluginVersionSource)
  }
  if (markerGuidance != null) {
    err(markerGuidance)
    val matching = failures.count { isUnresolvedPluginMarkerFailure(it.message) }
    if (matching * 2 >= failures.size) return
  }
  // A dominant "auto-injected plugin can't see AGP" signature has one actionable cause.
  agpClassloaderGuidance(failures)?.let {
    err(it)
    return
  }
  err(
    "${failures.size} project(s) failed to configure during discovery and were skipped — " +
      "their previews are not listed. This is the usual cause of an empty discovery when the " +
      "render task itself works. Rerun with --verbose for full Gradle output."
  )
  failures.take(limit).forEach { err("  ${it.path}: ${it.message}") }
  if (failures.size > limit) err("  … and ${failures.size - limit} more")
}

/**
 * Recognises a project cancelled mid-configuration (`Build cancelled.` / `BuildCancelledException`
 * after [GradleConnection.runBuildAction]'s timeout) rather than one that failed. A cancellation
 * says nothing about the project: its build script was never evaluated.
 */
internal fun isDiscoveryCancellationFailure(message: String): Boolean =
  message.contains("Build cancelled", ignoreCase = true) ||
    message.contains("BuildCancelledException")

/**
 * When any project was cancelled ([isDiscoveryCancellationFailure]), the timeout explanation and
 * the remedy (re-run; the configuration cache makes the second pass faster). Null otherwise. One
 * cancellation suffices: discovery didn't finish, so its results are incomplete.
 */
internal fun discoveryTimeoutGuidance(
  failures: List<ProjectDiscoveryFailure>,
  timeoutSeconds: Long? = null,
): String? {
  val matching = failures.count { isDiscoveryCancellationFailure(it.message) }
  if (matching == 0) return null
  val budget = timeoutSeconds?.let { " after ${it}s" } ?: ""
  return buildString {
    appendLine(
      "Discovery timed out$budget while Gradle was still configuring the build: " +
        "$matching of ${failures.size} project(s) were cancelled mid-configuration " +
        "(\"Build cancelled.\"), not misconfigured. Their previews are not listed, and whether " +
        "they apply the plugin is unknown — discovery never got far enough to see."
    )
    appendLine()
    append(
      "A build with no configuration cache entry pays full cold configuration on the first pass, " +
        "which on a large multi-module build can take minutes. Re-run the same command — the " +
        "second pass reuses the cached configuration — or raise the budget with " +
        "--timeout <seconds>" +
        (timeoutSeconds?.let { " (e.g. --timeout ${it * 2})" } ?: "") +
        "."
    )
  }
}

/**
 * Recognises the "auto-injected plugin can't see AGP" signature: `NoClassDefFoundError` /
 * `ClassNotFoundException` on `com.android.build.api.variant.*` (either separator).
 *
 * Happens when AGP comes from an included build's convention plugin: auto-inject puts the plugin on
 * each project's own buildscript classloader, a sibling that can't see AGP. See
 * [agpClassloaderGuidance].
 */
internal fun isAgpClassloaderFailure(message: String): Boolean {
  val mentionsAgpVariantApi =
    Regex("""com[./]android[./]build[./]api[./]variant""").containsMatchIn(message)
  val classloaderError =
    message.contains("NoClassDefFoundError") || message.contains("ClassNotFoundException")
  return mentionsAgpVariantApi && classloaderError
}

/**
 * When discovery failures are dominated (at least half) by [isAgpClassloaderFailure], one message
 * explaining how to apply the plugin from the convention plugin instead. Null otherwise, so the
 * per-project list shows. Init scripts can't add to an included build's classpath, so this is
 * guidance only.
 */
internal fun agpClassloaderGuidance(failures: List<ProjectDiscoveryFailure>): String? {
  if (failures.isEmpty()) return null
  val matching = failures.count { isAgpClassloaderFailure(it.message) }
  if (matching == 0 || matching * 2 < failures.size) return null
  return buildString {
    appendLine(
      "$matching of ${failures.size} project(s) failed to configure during discovery with the " +
        "same root cause: the compose-preview plugin can't see the Android Gradle Plugin " +
        "(NoClassDefFoundError on com.android.build.api.variant.* — AGP's variant API)."
    )
    appendLine()
    appendLine(
      "This build supplies AGP through an included build's convention plugin (the build-logic " +
        "pattern), so AGP is loaded by the convention plugin's classloader. Auto-inject puts " +
        "ee.schimke.composeai.preview on each project's own buildscript classpath — a sibling " +
        "classloader that can't reach AGP — so the plugin throws the moment it touches AGP. " +
        "There is no init-script API to add the plugin to the included build's classpath, so " +
        "auto-inject can't fix this layout."
    )
    appendLine()
    appendLine(
      "Apply the plugin from your convention plugin instead: add the plugin marker to your " +
        "build-logic build's dependencies " +
        "(implementation(\"ee.schimke.composeai.preview:" +
        "ee.schimke.composeai.preview.gradle.plugin:<version>\")) so it's on the convention " +
        "plugin's runtime classpath, then pluginManager.apply(\"ee.schimke.composeai.preview\") " +
        "alongside AGP in the convention plugin. The CLI detects that and skips auto-inject " +
        "automatically."
    )
    append(
      "Docs: https://yschimke.github.io/compose-ai-tools/install/" +
        "#builds-that-apply-agp-via-a-convention-plugin"
    )
  }
}

internal val NON_PNG_PREVIEW_KINDS = setOf("XR_SUBSPACE")

/**
 * The ids an `--id-file` lists: one per line, trimmed, blanks dropped, in order. Throws when
 * unreadable or empty — an empty selection must never widen to every preview.
 */
internal fun readIdFile(file: File): Set<String> {
  val lines =
    try {
      file.readLines()
    } catch (e: java.io.IOException) {
      throw IllegalStateException("cannot read '${file.path}': ${e.message}", e)
    }
  val ids = lines.map(String::trim).filter(String::isNotEmpty).toCollection(linkedSetOf())
  check(ids.isNotEmpty()) {
    "'${file.path}' lists no preview ids. Refusing to fall back to every preview; omit the flag " +
      "instead."
  }
  return ids
}

/**
 * Does one preview satisfy this invocation's selection? Passed selectors intersect:
 * - `--id <exact>` — the id, exactly, case-sensitively.
 * - `--filter <substring>` — a case-insensitive substring of the id.
 * - `--preview <ref>` — a loose preview reference; see [previewMatchesReference].
 * - `--id-file <path>` ([ids]) — the id is one of a set (not supported by `serve`).
 *
 * Without [className] / [functionName], `--preview` falls back to its id-only forms.
 *
 * The same rule lives in yschimke/compose-preview-server (`previewIdMatchesStandaloneRequest`);
 * both are pinned by `docs/serve/preview-selector-fixtures.json`. Change the rule and the table
 * together.
 */
internal fun previewIdMatchesRequest(
  id: String,
  exactId: String?,
  filter: String?,
  previewRef: String? = null,
  className: String? = null,
  functionName: String? = null,
  ids: Set<String>? = null,
): Boolean {
  if (ids != null && id !in ids) return false
  if (exactId != null && id != exactId) return false
  if (filter != null && !id.contains(filter, ignoreCase = true)) return false
  if (
    previewRef != null &&
      !previewMatchesReference(previewRef, id, className = className, functionName = functionName)
  ) {
    return false
  }
  return true
}

/**
 * Is [exactId] a preview that actually exists in [manifests]? If so it wins outright over the row
 * lane of [previewMatchesRequestIncludingRows], so `--id Foo_Dark` doesn't also keep a
 * parameterized `Foo` (mirrors the daemon's exact-hit-before-row-split rule).
 *
 * `--id` only: `--filter` / `--preview` are substring rules that legitimately match several
 * previews, and a concrete match must not suppress a row owner there.
 */
internal fun manifestsDeclareExactId(
  manifests: List<Pair<PreviewModule, PreviewManifest>>,
  exactId: String?,
): Boolean =
  declaresExactId(
    exactId,
    manifests.asSequence().flatMap { (_, manifest) ->
      manifest.previews.asSequence().map { it.id }
    },
  )

/** [manifestsDeclareExactId] over rendered results; both must answer identically. */
internal fun resultsDeclareExactId(results: List<PreviewResult>, exactId: String?): Boolean =
  declaresExactId(exactId, results.asSequence().map { it.id })

private fun declaresExactId(exactId: String?, ids: Sequence<String>): Boolean =
  exactId != null && ids.any { it == exactId }

/**
 * `--id` / `--filter` / `--preview` over rendered results, honouring `@PreviewParameter` row ids
 * ([CaptureResult.parameterRowId], derived by `PreviewParameterFanout` exactly as `serve` does).
 *
 * Mirrors `serve`'s rule:
 * - the base id matches → the whole preview, every row;
 * - otherwise a row id matches → the preview, narrowed to the matching rows;
 * - `--id` naming a preview that really exists ([resultsDeclareExactId]) turns the row lane off.
 *
 * Rows are matched by id alone; the class/function forms of `--preview` were tested on the base id.
 */
internal fun selectRequestedResults(
  results: List<PreviewResult>,
  exactId: String?,
  filter: String?,
  previewRef: String? = null,
  ids: Set<String>? = null,
): List<PreviewResult> {
  if (exactId == null && filter == null && previewRef == null && ids == null) return results
  val exactIdExists = resultsDeclareExactId(results, exactId)
  return results.mapNotNull { result ->
    if (
      previewIdMatchesRequest(
        result.id,
        exactId = exactId,
        filter = filter,
        previewRef = previewRef,
        className = result.className,
        functionName = result.functionName,
        ids = ids,
      )
    ) {
      return@mapNotNull result
    }
    // `--id-file` names declared previews only, so it has no row lane.
    if (exactIdExists || ids != null) return@mapNotNull null
    val rows =
      result.captures.filter { capture ->
        capture.parameterRowId?.let {
          previewIdMatchesRequest(it, exactId = exactId, filter = filter, previewRef = previewRef)
        } == true
      }
    if (rows.isEmpty()) null else result.withCaptures(rows)
  }
}

/**
 * [PreviewResult] narrowed to [captures], with the `pngPath` / `sha256` / `changed` mirrors
 * re-pointed at the first surviving capture.
 */
private fun PreviewResult.withCaptures(captures: List<CaptureResult>): PreviewResult {
  val first = captures.firstOrNull()
  return copy(
    captures = captures,
    pngPath = first?.pngPath,
    sha256 = first?.sha256,
    changed = first?.changed,
  )
}

/**
 * Like [previewIdMatchesRequest], but a `@PreviewParameter` preview whose rows might satisfy the
 * request also matches. Discovery can't instantiate providers, so row ids (`Foo_PARAM_1`) don't
 * exist until after the render; module selection and render narrowing run before that.
 *
 * Undecidable resolves to keep: a wrong drop is an error the caller can't work around, a wrong keep
 * only costs build time. Bounded by:
 * 1. [manifestsDeclareExactId] — an `--id` naming a real preview turns the row lane off.
 * 2. Previews with no provider have no rows and match exactly, preserving the typo diagnostic.
 *
 * The render is still narrowed to the parameterized previews ([PreviewRenderScope.forRequest]).
 */
internal fun previewMatchesRequestIncludingRows(
  preview: PreviewInfo,
  exactId: String?,
  filter: String?,
  previewRef: String? = null,
  exactIdExists: Boolean,
  rowAware: Boolean = true,
  ids: Set<String>? = null,
): Boolean {
  if (
    previewIdMatchesRequest(
      preview.id,
      exactId = exactId,
      filter = filter,
      previewRef = previewRef,
      className = preview.className,
      functionName = preview.functionName,
      ids = ids,
    )
  ) {
    return true
  }
  if (!rowAware) return false
  // `--id-file` lists declared ids: a preview it does not name has no row it could name either.
  if (ids != null) return false
  if (exactIdExists) return false
  if (preview.params.previewParameterProviderClassName.isNullOrBlank()) return false
  // `--id` is exact, so it must spell `<base>_<row>`; the other selectors are then tested against
  // that concrete id, keeping unsatisfiable intersections and typos failing fast.
  if (exactId != null) {
    if (!isRowAddressOf(preview.id, exactId)) return false
    return previewIdMatchesRequest(
      exactId,
      exactId = null,
      filter = filter,
      previewRef = previewRef,
      className = preview.className,
      functionName = preview.functionName,
    )
  }
  // `--filter` / `--preview` alone can't be decided before rows exist; keep as a candidate.
  return true
}

/**
 * Whether [selector] spells a row of [baseId] (`<baseId>_<row>`, non-empty row), as the fan-out
 * writes it (docs/RENDER_FILENAMES.md). Case-sensitive; only used for `--id`.
 */
private fun isRowAddressOf(baseId: String, selector: String): Boolean =
  selector.length > baseId.length + 1 &&
    selector.startsWith(baseId) &&
    selector[baseId.length] == '_'

/**
 * The `--preview <ref>` rule. A ref matches when any of these holds:
 * 1. it equals [id] exactly (case-sensitive);
 * 2. it equals `<className>.<functionName>`;
 * 3. it equals the bare [functionName];
 * 4. it is a case-insensitive substring of [id] (the `--filter` rule).
 *
 * OR'ed rather than staged, so the predicate is per-preview and consistent across module selection,
 * Gradle narrowing and row printing; `--preview Foo` also selects `FooBar`. Use `--id` for exactly
 * one.
 */
internal fun previewMatchesReference(
  ref: String,
  id: String,
  className: String? = null,
  functionName: String? = null,
): Boolean =
  id == ref ||
    (className != null && functionName != null && "$className.$functionName" == ref) ||
    functionName == ref ||
    id.contains(ref, ignoreCase = true)

internal fun modulesMatchingPreviewRequest(
  modules: List<PreviewModule>,
  manifests: List<Pair<PreviewModule, PreviewManifest>>,
  exactId: String?,
  filter: String?,
  previewRef: String? = null,
  discoverySucceeded: Boolean = true,
  rowAware: Boolean = true,
  ids: Set<String>? = null,
): List<PreviewModule> {
  // Discovery failed: don't let stale manifests suppress the render's own discovery retry.
  if (!discoverySucceeded) return modules
  if (exactId == null && filter == null && previewRef == null && ids == null) return modules
  // Row-aware: a module whose only match is a row id must survive. Resolved across all manifests
  // first so an `--id` naming a real preview never drags in hypothetical row owners.
  val exactIdExists = manifestsDeclareExactId(manifests, exactId)
  val matchingPaths =
    manifests
      .filter { (_, manifest) ->
        manifest.previews.any {
          previewMatchesRequestIncludingRows(
            it,
            exactId = exactId,
            filter = filter,
            previewRef = previewRef,
            exactIdExists = exactIdExists,
            rowAware = rowAware,
            ids = ids,
          )
        }
      }
      .map { (module, _) -> module.gradlePath }
      .toSet()
  return modules.filter { it.gradlePath in matchingPaths }
}

internal fun readableRenderModules(
  modules: List<PreviewModule>,
  taskFor: (PreviewModule) -> String,
  outcomes: Map<String, GradleTaskOutcome>,
): List<PreviewModule> = modules.filter { module ->
  val outcome = outcomes[taskFor(module)]
  outcome?.canReadOutputs == true
}

/**
 * Previews that rendered but produced no PNG for at least one required capture — the set
 * `--missing-renders` gates on. Excludes [NON_PNG_PREVIEW_KINDS] and `optional` captures.
 */
internal fun previewsMissingPng(results: List<PreviewResult>): List<PreviewResult> =
  results.filter {
    previewMissesRequiredPng(it)
  }

/**
 * Single source of truth for "missing PNG is a render failure", shared by the gate,
 * `counts.missing` and the `[no PNG]` tag so they can't disagree.
 */
internal fun previewMissesRequiredPng(r: PreviewResult): Boolean =
  r.params.kind !in NON_PNG_PREVIEW_KINDS && r.captures.any { it.pngPath == null && !it.optional }

/** True if the preview has at least one capture with `changed = true`. */
internal fun PreviewResult.hasChangedCapture(): Boolean =
  captures.any { it.changed == true } || changed == true

/** Which [PreviewCounts] bucket a preview belongs to. Exhaustive, and mutually exclusive. */
internal enum class PreviewCountBucket {
  CHANGED,
  UNCHANGED,
  MISSING,
  SKIPPED,
}

/**
 * Classify one preview into its [PreviewCounts] bucket; a `when` cascade so the buckets partition.
 */
internal fun previewCountBucket(r: PreviewResult): PreviewCountBucket =
  when {
    r.hasChangedCapture() -> PreviewCountBucket.CHANGED
    r.captures.any { it.pngPath != null } -> PreviewCountBucket.UNCHANGED
    previewMissesRequiredPng(r) -> PreviewCountBucket.MISSING
    else -> PreviewCountBucket.SKIPPED
  }

/** The `counts` block for a set of results; buckets partition `total` by construction. */
internal fun previewCountsOf(results: List<PreviewResult>): PreviewCounts {
  val byBucket = results.groupingBy { previewCountBucket(it) }.eachCount()
  return PreviewCounts(
    total = results.size,
    changed = byBucket[PreviewCountBucket.CHANGED] ?: 0,
    unchanged = byBucket[PreviewCountBucket.UNCHANGED] ?: 0,
    missing = byBucket[PreviewCountBucket.MISSING] ?: 0,
    skipped = byBucket[PreviewCountBucket.SKIPPED] ?: 0,
  )
}

/**
 * The trailing tag on a `show` row: `[changed]`, `[no PNG]`, or a tag marking the absent PNG as
 * expected. Bare `[no PNG]` is reserved for [previewMissesRequiredPng] failures.
 */
internal fun previewStatusTag(r: PreviewResult): String =
  when {
    r.pngPath != null -> if (r.hasChangedCapture()) " [changed]" else ""
    previewMissesRequiredPng(r) -> " [no PNG]"
    r.params.kind in NON_PNG_PREVIEW_KINDS -> NO_PNG_BY_DESIGN_TAG
    else -> NO_PNG_OPTIONAL_TAG
  }

/** [previewStatusTag] for one capture row of a multi-capture preview. */
internal fun captureStatusTag(c: CaptureResult, kind: String): String =
  when {
    c.pngPath != null -> if (c.changed == true) " [changed]" else ""
    c.optional -> NO_PNG_OPTIONAL_TAG
    kind in NON_PNG_PREVIEW_KINDS -> NO_PNG_BY_DESIGN_TAG
    else -> " [no PNG]"
  }

/** Best-effort capture (`Capture.optional`) that produced nothing — expected, not a failure. */
internal const val NO_PNG_OPTIONAL_TAG = " [no PNG, optional]"

/** A [NON_PNG_PREVIEW_KINDS] preview, whose empty `pngPath` is the normal outcome. */
internal const val NO_PNG_BY_DESIGN_TAG = " [no PNG, by design]"

/**
 * Whether `show` can still report after Gradle failed. `composePreviewRenderAll` fails the whole
 * task for one broken preview, but manifests are read regardless, so report whenever there are
 * results. Exit code is unaffected ([showExitCode]).
 */
internal fun canReportAfterBuildFailure(results: List<PreviewResult>): Boolean =
  results.isNotEmpty()

/**
 * `show`'s exit code: a Gradle failure (2) outranks whatever the output would report, including "no
 * previews matched" (3).
 */
internal fun showExitCode(buildOk: Boolean, naturalCode: Int): Int = if (buildOk) naturalCode else 2

/**
 * Human-readable coordinate for a capture: `default`, `500ms`, `scroll long`, `500ms · scroll end`.
 */
internal fun captureCoordLabel(c: CaptureResult): String =
  listOfNotNull(
      c.parameterLabel,
      c.advanceTimeMillis?.let { "${it}ms" },
      c.scroll?.let { "scroll ${it.mode.lowercase()}" },
    )
    .joinToString(" · ")
    .ifEmpty { "default" }

class ShowCommand(args: List<String>) : Command(args) {

  /**
   * `show` prints and selects on `@PreviewParameter` rows; [applyFilters] discards speculative
   * keeps.
   */
  override val rowAwareSelection: Boolean
    get() = true

  private val jsonOutput = "--json" in args
  // Auto-on for an interactive kitty-graphics TTY; `--images=off|kitty` overrides; `--json` always
  // disables it.
  private val imagesMode: TerminalImages.Mode =
    if (jsonOutput) TerminalImages.Mode.OFF
    else
      TerminalImages.resolve(
        modeArg = args.flagValue("--images"),
        env = { System.getenv(it) },
        isTty = System.console() != null,
      )

  /** `--link[=desktop|mobile|web]`: a ChatGPT / Codex sidebar deep link per shown preview. */
  private val linkRequest: ShowLinkRequest = ShowLinkRequest.parse(args)

  /** The project root the links' workspace id is derived from; resolved once, on first link. */
  private val linkProjectRoot: File by lazy { findProjectRoot() ?: File(".").absoluteFile }

  /** The deep link for [r], or null when `--link` is off / unavailable or the id has no URI. */
  private fun linkFor(r: PreviewResult): String? {
    val ready = linkRequest as? ShowLinkRequest.Ready ?: return null
    val uri = composePreviewUri(linkProjectRoot, r.module, r.id) ?: return null
    return OpenAiDeepLinks.previewLink(ready.plugin, uri, ready.surface)
  }

  override fun run() {
    // Say up front, before the build, why `--link` will print nothing — not after a long render.
    (linkRequest as? ShowLinkRequest.Unavailable)?.let { System.err.println(it.message) }
    noteUnappliedSettings("show")
    val outcome =
      renderAllModules(silenceStdout = jsonOutput, gradleArguments = gradleArgsWithForce())
    if (!outcome.buildOk) {
      System.err.println("Render failed")
      // A failed build can still have results (one broken preview fails the whole task); report
      // what rendered, mark the rest `[no PNG]`, and keep exit code 2. Bail only when nothing was
      // written.
      if (!canReportAfterBuildFailure(outcome.results)) {
        System.out.flush()
        exitProcess(2)
      }
      System.err.println(
        "Reporting the ${outcome.results.size} preview(s) that were discovered anyway — previews " +
          "that failed to render carry a null pngPath (`[no PNG]` in text output). Exit code " +
          "stays 2."
      )
    }

    if (outcome.discoveredPreviewCount == 0) {
      if (jsonOutput) println(encodeResponse(emptyList(), countsScope = emptyList()))
      else println("No previews found.")
      // Plugin applied but no `@Preview`s is a legitimate state, not an error (non-zero trips `bash
      // -e` in CI). Flush because System.exit doesn't.
      System.out.flush()
      exitProcess(0)
    }

    val all = outcome.results
    val modules = outcome.modules
    val filtered = applyFilters(all)
    // Counts cover the full set so `--changed-only` callers still see totals — unless the render
    // was narrowed, when unrendered previews must not count as missing.
    val countsScope = if (outcome.renderedIds == null) all else selectRequested(all)

    if (filtered.isEmpty()) {
      if (jsonOutput) println(encodeResponse(emptyList(), countsScope = countsScope))
      else println("No previews matched.")
      System.out.flush()
      // A build failure outranks "no match" (3 would claim a healthy build).
      exitProcess(showExitCode(outcome.buildOk, naturalCode = 3))
    }

    if (jsonOutput) {
      val encoded = encodeResponse(filtered, countsScope = countsScope)
      println(
        if (linkRequest is ShowLinkRequest.Ready) {
          injectPreviewLinks(encoded, filtered.map(::linkFor), pretty = !brief)
        } else encoded
      )
    } else {
      var lastModule: String? = null
      for (r in filtered) {
        if (modules.size > 1 && r.module != lastModule) {
          println("[${r.module}]")
          lastModule = r.module
        }
        val statusTag = previewStatusTag(r)
        val shaTag = r.sha256?.let { "  sha=${it.take(12)}" } ?: ""
        println("${r.functionName} (${r.id})$statusTag$shaTag")
        if (r.captures.size <= 1) {
          if (r.pngPath != null) println("  ${r.pngPath}")
        } else {
          for (c in r.captures) {
            val tag = captureStatusTag(c, r.params.kind)
            println("  [${captureCoordLabel(c)}]$tag ${c.pngPath ?: ""}")
          }
        }
        linkFor(r)?.let { println("  link: $it") }
        emitInlineImage(r)
      }
    }

    val missing = previewsMissingPng(filtered)
    if (missing.isNotEmpty()) {
      // The diagnostic prints regardless; `warn|ignore` only changes the exit code. Unset means
      // fail.
      val policy = missingRendersPolicy?.lowercase()
      val prefix =
        if (policy in setOf("warn", "ignore")) "missing-renders policy=$policy — " else ""
      // List the offenders and their `.error.json` sidecars so CI logs are self-diagnosing; task
      // outcomes keep a stale sidecar from being quoted when the render was skipped.
      System.err.println(
        missingRenderReport(
          missing = missing,
          manifests = outcome.manifests,
          total = filtered.size,
          taskOutcomes = outcome.taskOutcomes,
          prefix = prefix,
        )
      )
      System.out.flush()
      if (shouldFailOnMissingRenders()) exitProcess(2)
    }
    System.out.flush()
    // Honour the deferred build failure now that output has been emitted.
    if (!outcome.buildOk) exitProcess(showExitCode(false, naturalCode = 0))
  }

  /**
   * Emit PNG(s) inline: multi-capture previews become a kitty animation, single captures a still.
   * Captures without a PNG are skipped.
   */
  private fun emitInlineImage(r: PreviewResult) {
    if (imagesMode == TerminalImages.Mode.OFF) return
    val rendered = r.captures.filter { it.pngPath != null }
    if (rendered.isEmpty()) return
    val pngs = rendered.mapNotNull { c -> c.pngPath?.let { File(it) }?.takeIf { it.isFile } }
    if (pngs.size != rendered.size) return // some path didn't exist on disk — skip silently
    val bytes = pngs.map { fileSystem.read(it.path.toPath()) { readByteArray() } }
    if (bytes.size == 1) {
      TerminalImages.emitStill(System.out, bytes[0])
    } else {
      val frames = TerminalImages.framesFromCaptures(bytes, rendered.map { it.advanceTimeMillis })
      TerminalImages.emitAnimation(System.out, frames)
    }
    println()
  }
}

class ListCommand(args: List<String>) : Command(args) {

  /**
   * `list` selects on row ids too, from whatever an earlier render left on disk; it renders
   * nothing.
   */
  override val rowAwareSelection: Boolean
    get() = true

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
      // Discovery only: PNGs may not exist, so sha/changed are null and `--changed-only` is
      // ignored.
      val all = buildResults(manifests)
      val filtered = selectRequested(all)

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

  /** `render` selects on row ids like `show`, including for `--output`. */
  override val rowAwareSelection: Boolean
    get() = true

  private val output: String? = args.flagValue("--output")

  /**
   * `--bundle`: also pack each module's previews into `<module>/build/compose-previews/bundle.png`
   * via `composePreviewBundle`. Off by default since it adds a classpath walk and jar minimization.
   */
  private val bundle: Boolean = "--bundle" in args

  /**
   * `--embed-deps` (with `--bundle`): embed reachable third-party jars instead of Maven
   * coordinates, so the bundle renders offline. Forwarded as `-PbundleEmbedDeps=true`.
   */
  private val embedDeps: Boolean = "--embed-deps" in args

  /**
   * `--format png|svg` (default `png`). `svg` also emits the `compose/figma-svg` export per matched
   * preview; that is a daemon-only data product, so it drives a short-lived render daemon after the
   * render, like `bundle pack --with-semantics`.
   */
  private val formatFlag: String? =
    args.flagValue("--format")?.trim()?.lowercase()?.ifEmpty { null }

  override fun run() {
    noteUnappliedSettings("render")
    val svg =
      when (formatFlag) {
        null,
        "png" -> false
        "svg" -> true
        else -> {
          System.err.println(
            "unsupported --format '$formatFlag' for render; expected 'png' or 'svg'"
          )
          exitProcess(1)
        }
      }
    withGradle { gradle ->
      val resolved = resolveModules(gradle)
      // Discover first so selectors resolve to exact ids for the Gradle render ([renderModules]
      // does this for `show`).
      val discoverySucceeded = runDiscover(gradle, resolved, silenceStdout = false)
      val discoveryManifests = if (discoverySucceeded) readAllManifests(resolved) else emptyList()
      // `--bundle` packs whole modules and drops previews without PNGs, so it stays full-width.
      val modules =
        if (bundle) resolved
        else
          modulesMatchingPreviewRequest(
            resolved,
            discoveryManifests,
            exactId = exactId,
            filter = filter,
            previewRef = previewRef,
            discoverySucceeded = discoverySucceeded,
            rowAware = rowAwareSelection,
          )
      if (modules.isEmpty()) {
        System.err.println("No previews matched.")
        exitProcess(3)
      }
      val scope =
        if (bundle) PreviewRenderScope.FULL
        else previewRenderScope(modules, discoveryManifests, discoverySucceeded)
      val xrArgs =
        provisionXrCompositeArgs(gradle, modules, silenceStdout = false, discoverFirst = false)
      val tasks = previewTasksFor(modules.map { it.gradlePath }).toTypedArray()
      if (
        !runGradle(
          gradle,
          *tasks,
          arguments = gradleArgsWithForce(bundleGradleArgs()) + xrArgs + scope.gradleArgs,
        )
      ) {
        reportRenderFailures(gradle)
        exitProcess(2)
      }

      if (bundle) reportBundles(modules)

      val manifests = readAllManifests(modules)
      val all = buildResults(manifests, scope.renderedIds)
      // `render` ignores `--changed-only`; use a follow-up `show --changed-only`.
      val filtered = selectRequested(all)

      if (filtered.isEmpty()) {
        System.err.println("No previews matched.")
        exitProcess(3)
      }

      if (svg) {
        emitSvgOutputs(gradle, modules, filtered)
        return@withGradle
      }

      val missing = previewsMissingPng(filtered)

      if (output != null) {
        if (filtered.size != 1) {
          System.err.println(
            "--output requires a single match (got ${filtered.size}). " +
              "Narrow with --id <exact>, --filter <substring>, or --preview <ref>."
          )
          exitProcess(1)
        }
        val one = filtered.single()
        if (one.pngPath == null) {
          // Print the same missing-render report as `show`. `missing` is empty only when the absent
          // PNG is by design; `--output` still failed, and `--missing-renders` doesn't apply to
          // this exit.
          System.err.println(
            if (missing.isEmpty()) "Render produced no PNG for: ${one.id}"
            else
              missingRenderReport(
                missing = missing,
                manifests = manifests,
                total = filtered.size,
                taskOutcomes = gradle.lastTaskOutcomes(),
              )
          )
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
          val policy = missingRendersPolicy?.lowercase()
          val prefix =
            if (policy in setOf("warn", "ignore")) "missing-renders policy=$policy — " else ""
          // Same report `show` prints (sidecars plus task outcomes).
          System.err.println(
            missingRenderReport(
              missing = missing,
              manifests = manifests,
              total = filtered.size,
              taskOutcomes = gradle.lastTaskOutcomes(),
              prefix = prefix,
            )
          )
          if (shouldFailOnMissingRenders()) exitProcess(2)
        }
      }
    }
  }

  /**
   * `--format svg` output pass: regenerate each module's `daemon-launch.json` and drive a
   * short-lived [DaemonSemanticsFetcher] render so the `compose/figma-svg` extension writes each
   * preview's SVG.
   *
   * - Without `--bundle`: loose `.svg` files, at `--output` for a single match or beside the PNGs
   *   at
   *   `<module>/build/compose-previews/renders/<id>.svg`.
   * - With `--bundle`: injected into each module's `bundle.png` as `previews/<id>.figma.svg` (plus
   *   any `figma-raster/<node>.png` crops).
   *
   * Best-effort per preview, but exits non-zero if no SVG was produced at all.
   */
  private fun emitSvgOutputs(
    gradle: GradleConnection,
    modules: List<PreviewModule>,
    filtered: List<PreviewResult>,
  ) {
    // `--output` is a single loose file; `--bundle` injects into bundles. Mutually exclusive.
    if (output != null && bundle) {
      System.err.println("--output cannot be combined with --bundle for --format svg.")
      exitProcess(1)
    }
    if (output != null && filtered.size != 1) {
      System.err.println(
        "--output requires a single match (got ${filtered.size}). " +
          "Narrow with --id <exact>, --filter <substring>, or --preview <ref>."
      )
      exitProcess(1)
    }

    val moduleByPath = modules.associateBy { it.gradlePath }
    var totalWritten = 0
    for ((modulePath, rows) in filtered.groupBy { it.module }) {
      val module = moduleByPath[modulePath] ?: continue
      // Separate invocation: a failure only forfeits this module's SVGs.
      val daemonStarted =
        runGradle(
          gradle,
          ":$modulePath:composePreviewDaemonStart",
          arguments = gradleArgsWithForce(),
        )
      if (!daemonStarted) {
        System.err.println(
          "render --format svg: could not start the preview daemon for $modulePath " +
            "(composePreviewDaemonStart failed) — no SVG produced for its previews."
        )
        continue
      }

      val fetcher =
        DaemonSemanticsFetcher(onLog = { if (verbose) System.err.println("[daemon svg] $it") })
      val outcome =
        fetcher.fetch(
          projectDir = module.projectDir,
          moduleName = modulePath,
          previewIds = rows.map { it.id },
        )
      val svgById: Map<String, ByteArray>
      val rasterById: Map<String, Map<String, ByteArray>>
      val fontWarningsById: Map<String, ByteArray>
      when (outcome) {
        is DaemonSemanticsFetcher.Outcome.Ok -> {
          svgById = outcome.figmaSvgById
          rasterById = outcome.figmaRasterById
          fontWarningsById = outcome.figmaFontWarningsById
        }
        is DaemonSemanticsFetcher.Outcome.DescriptorMissing -> {
          System.err.println(
            "render --format svg: daemon-launch.json missing at ${outcome.expected.path} for " +
              "$modulePath — no SVG produced."
          )
          continue
        }
        is DaemonSemanticsFetcher.Outcome.OpenFailed -> {
          System.err.println(
            "render --format svg: could not open a render session for $modulePath " +
              "(${outcome.reason}) — no SVG produced."
          )
          continue
        }
      }

      totalWritten +=
        if (bundle)
          injectSvgIntoModuleBundle(module, modulePath, svgById, rasterById, fontWarningsById)
        else writeLooseSvgFiles(module, rows, svgById, rasterById)
      // Text drawn as missing-glyph boxes looks like a design choice, so warn explicitly.
      if (fontWarningsById.isNotEmpty()) {
        System.err.println(
          "render --format svg: ${fontWarningsById.size} preview(s) exported as missing-glyph " +
            "boxes (a font family the render drew could not be named):"
        )
        for (id in fontWarningsById.keys.sorted()) System.err.println("  $id")
      }

      val noSvg = rows.filter { it.id !in svgById }
      if (noSvg.isNotEmpty()) {
        System.err.println(
          "render --format svg: no figma-svg for ${noSvg.size} preview(s) in $modulePath " +
            "(backend has no figma-svg producer, or the preview drew no vector layers):"
        )
        for (r in noSvg) System.err.println("  ${r.id}")
      }
    }

    if (totalWritten == 0) {
      System.err.println("render --format svg produced no SVG output.")
      exitProcess(2)
    }
    if (output == null && !bundle) println("Wrote $totalWritten SVG file(s)")
  }

  /**
   * Write one module's figma-svg exports as loose `.svg` files and return the count; raster crops
   * go to a sibling `<id>.figma-raster/` dir ([RenderSvgOutput.write]).
   */
  private fun writeLooseSvgFiles(
    module: PreviewModule,
    rows: List<PreviewResult>,
    svgById: Map<String, ByteArray>,
    rasterById: Map<String, Map<String, ByteArray>>,
  ): Int {
    val rendersDir = module.projectDir.resolve("build/compose-previews/renders")
    var written = 0
    for (row in rows) {
      val svgBytes = svgById[row.id] ?: continue
      val target =
        if (output != null) File(output)
        else File(rendersDir, RenderSvgOutput.safeFilename(row.id) + ".svg")
      RenderSvgOutput.write(target, svgBytes, rasterById[row.id].orEmpty(), fileSystem)
      written++
      println(
        if (output != null) "Rendered ${row.id} to ${target.path}" else "Wrote ${target.path}"
      )
    }
    return written
  }

  /**
   * Inject one module's figma-svg exports and raster crops into its `bundle.png` as
   * `previews/<id>.figma.svg`. Returns the SVG count (0 if the bundle is missing, with a warning).
   */
  private fun injectSvgIntoModuleBundle(
    module: PreviewModule,
    modulePath: String,
    svgById: Map<String, ByteArray>,
    rasterById: Map<String, Map<String, ByteArray>>,
    fontWarningsById: Map<String, ByteArray>,
  ): Int {
    val bundleFile = module.projectDir.resolve("build/compose-previews/bundle.png")
    if (!bundleFile.isFile) {
      System.err.println(
        "render --format svg --bundle: expected bundle missing at ${bundleFile.path} for " +
          "$modulePath — cannot inject SVG."
      )
      return 0
    }
    val svgWritten = injectFigmaSvgIntoBundle(bundleFile, svgById, fileSystem)
    val rasterWritten = injectFigmaRasterIntoBundle(bundleFile, rasterById, fileSystem)
    // Only degraded previews have warnings; carried alongside the SVG they explain.
    injectFigmaFontWarningsIntoBundle(bundleFile, fontWarningsById, fileSystem)
    if (svgWritten > 0) {
      println(
        "Injected $svgWritten figma-svg" +
          (if (rasterWritten > 0) " + $rasterWritten raster crop(s)" else "") +
          " into ${bundleFile.path} as previews/<id>$BUNDLE_FIGMA_SVG_SUFFIX"
      )
    }
    return svgWritten
  }

  /**
   * Task list for this run, one per module, with `composePreviewBundle` after
   * `composePreviewRenderAll` under `--bundle` (it `mustRunAfter` the render, so it packs fresh
   * PNGs).
   */
  internal fun previewTasksFor(modulePaths: List<String>): List<String> = buildList {
    for (path in modulePaths) {
      add(":$path:composePreviewRenderAll")
      if (bundle) add(":$path:composePreviewBundle")
    }
  }

  /**
   * Extra Gradle properties for the bundle step: empty unless `--bundle`; `--embed-deps` adds
   * `-PbundleEmbedDeps=true`.
   */
  internal fun bundleGradleArgs(): List<String> =
    if (bundle && embedDeps) listOf("-PbundleEmbedDeps=true") else emptyList()

  /**
   * Print one line per freshly-packed bundle, read back via [BundleReader] so it reflects what
   * landed. A missing file is a warning, since the render already succeeded.
   */
  private fun reportBundles(modules: List<PreviewModule>) {
    for (m in modules) {
      val file = m.projectDir.resolve("build/compose-previews/bundle.png")
      if (!file.isFile) {
        System.err.println("Bundle expected but missing for ${m.gradlePath}: ${file.path}")
        continue
      }
      val summary =
        try {
          val meta = BundleReader.readMetadata(file)
          "${meta.manifest.previewIds.size} preview(s), resolution=${meta.manifest.resolution}"
        } catch (e: Exception) {
          "unreadable: ${e.message}"
        }
      println("Bundled ${file.path} (${file.length()} bytes; $summary)")
    }
  }
}

/**
 * "Render with extension X enabled, print X's canned report" — the shape behind `compose-preview
 * a11y`. Enables the extension via [implicitExtensions], runs `:composePreviewRenderAll`, and
 * delegates printing and exit policy to the named [ExtensionReportRenderer]. Subclasses only bind a
 * name to a renderer id.
 */
open class ReportCommand(args: List<String>, private val extensionId: String) : Command(args) {
  protected val jsonOutput: Boolean = "--json" in args
  // "errors" | "warnings" | "none". When not set, exit code mirrors Gradle.
  protected val failOn: String? = args.flagValue("--fail-on")

  override fun implicitExtensions(): List<String> = listOf(extensionId)

  /**
   * One preview a data-product hook has been asked to produce for.
   *
   * [previewId] addresses the daemon (always a discovered id); [entryId] keys the result. They
   * differ only for a `--permutations` variant: `Foo_dark` is fetched as `Foo` with [overrides]
   * (null for a plain preview) and filed under `Foo_dark`.
   */
  data class RequestedPreview(
    val previewId: String,
    val entryId: String,
    val overrides: PreviewOverrides? = null,
  ) {
    /** True when this is a client-side permutation rather than a declared preview. */
    val isPermutation: Boolean
      get() = entryId != previewId
  }

  /**
   * One module's share of the work [produceAdditionalDataProducts] has to do.
   *
   * [previews] is already narrowed to the selectors; fan out over it, not `manifest.previews`.
   * [consumerPreviewIds] is every id a consumer may look up (permutation-expanded), which coverage
   * is measured against. When [narrowed] is true the hook covers only part of the module and must
   * merge into the existing per-module sidecar rather than overwrite it.
   */
  data class DataProductRequest(
    val module: PreviewModule,
    val manifest: PreviewManifest,
    val previews: List<RequestedPreview>,
    val consumerPreviewIds: List<String>,
    val narrowed: Boolean,
  )

  /**
   * Hook between the Gradle build and result building, for out-of-band production (e.g. the daemon
   * a11y fetch in [A11yCommand]). `previews.json` is on disk; implementations write sidecars to
   * `build/compose-previews/<extension>.json` for the renderers to load. Default no-op.
   */
  protected open fun produceAdditionalDataProducts(requests: List<DataProductRequest>) {}

  /**
   * Per-module work list for [produceAdditionalDataProducts]: each manifest narrowed to the
   * request, dropping empty modules. Computed from the request itself because
   * [RawRenderOutcome.renderedIds] is also null when the request selected everything.
   */
  protected fun dataProductRequests(
    manifests: List<Pair<PreviewModule, PreviewManifest>>
  ): List<DataProductRequest> = manifests.mapNotNull { (module, manifest) ->
    val consumerIds = mutableListOf<String>()
    val requested = mutableListOf<RequestedPreview>()
    for (preview in manifest.previews) {
      // A `@PreviewHelper` opted out of a11y: exclude it from both the fetch and the coverage
      // universe.
      if (extensionId == "a11y" && !preview.includeInA11y) continue
      // Order matters: the daemon keys artefacts by preview id, so each permutation overwrites the
      // last. See `DaemonA11yFetcher.fetch`.
      for (expanded in PreviewPermutationsCli.expand(listOf(preview), permutations)) {
        consumerIds += expanded.id
        // Match as a manifest row so `--preview`'s class/function forms work, consistent with
        // module selection.
        if (!matchesRequest(expanded)) continue
        requested +=
          RequestedPreview(
            previewId = preview.id,
            entryId = expanded.id,
            overrides = PreviewPermutationsCli.overridesFor(preview, expanded),
          )
      }
    }
    if (requested.isEmpty()) null
    else
      DataProductRequest(
        module = module,
        manifest = manifest,
        previews = requested,
        consumerPreviewIds = consumerIds,
        narrowed = requested.size < consumerIds.size,
      )
  }

  /**
   * Message to print (exit 2, before any output) when the data product turned out to be
   * unavailable, so a broken daemon doesn't look like "no findings". Default null.
   */
  protected open fun atfUnavailableExitMessage(): String? = null

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

    // Expanded by hand (rather than [renderAllModules]) so the hook can run between Gradle and
    // loading.
    val raw =
      renderModules(
        silenceStdout = jsonOutput,
        gradleArguments = gradleArgsWithForce(),
        scopeToPreviewRequest = true,
      )
    val manifests = readAllManifests(raw.modules)
    // The unexpanded manifests: the daemon only knows discovered previews ([requestedPreviewIds]).
    produceAdditionalDataProducts(
      dataProductRequests(PreviewResultBuilder.readAllManifests(raw.modules))
    )
    // Abort when the data product was unavailable, so a daemon crash isn't a clean run.
    atfUnavailableExitMessage()?.let { message ->
      System.err.println(message)
      exitProcess(2)
    }
    val results = if (manifests.isEmpty()) emptyList() else buildResults(manifests, raw.renderedIds)
    val outcome =
      RenderModulesOutcome(
        buildOk = raw.buildOk,
        modules = raw.modules,
        manifests = manifests,
        results = results,
        discoveredPreviewCount =
          raw.discoveredPreviewCount.takeIf { it > 0 }
            ?: manifests.sumOf { it.second.previews.size },
        renderedIds = raw.renderedIds,
      )

    if (outcome.manifests.isEmpty()) {
      if (jsonOutput) println(encodeResponse(emptyList(), countsScope = null))
      else println("No previews discovered.")
      exitProcess(if (outcome.buildOk) 0 else 2)
    }

    val filtered =
      selectRequested(outcome.results).filter {
        renderer.hasData(it) && (!changedOnly || it.anyChanged())
      }

    if (jsonOutput) {
      println(encodeResponse(filtered, countsScope = null))
    } else {
      if (filtered.isEmpty()) renderer.printEmpty() else renderer.printAll(filtered)
    }

    // A renderer `--fail-on` threshold wins over a successful build; null defers to the Gradle
    // result.
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
 * `compose-preview a11y`: [ReportCommand] bound to the built-in `a11y` extension.
 *
 * a11y data comes from the preview daemon: after `composePreviewRenderAll`, a short-lived
 * [ee.schimke.composeai.render.session.RenderSession] per module fetches `a11y/atf` for the
 * requested previews only, and writes `build/compose-previews/accessibility.json` (merging when
 * narrowed) for [A11yReportRenderer] to load.
 */
open class A11yCommand(args: List<String>) : ReportCommand(args, "a11y") {
  /**
   * Whether any module was attempted and which failed, so [atfUnavailableExitMessage] can fail the
   * run when no module produced a11y data.
   */
  private var attemptedAnyModule: Boolean = false
  private var anyModuleAtfOk: Boolean = false
  private val unavailableModules: MutableList<String> = mutableListOf()

  override fun produceAdditionalDataProducts(requests: List<DataProductRequest>) {
    if (requests.isEmpty()) return
    // `daemon-launch.json` comes from `composePreviewDaemonStart`, which the render doesn't depend
    // on; run it separately (the warm Gradle daemon makes this cheap).
    val daemonStartOk = runDaemonStartTasks(requests.map { it.module })
    if (!daemonStartOk) {
      System.err.println(
        "compose-preview a11y: composePreviewDaemonStart failed; skipping daemon-driven a11y " +
          "fetch."
      )
      // A failed daemon start means ATF is unavailable for every module, so the run fails visibly.
      for (request in requests) {
        attemptedAnyModule = true
        unavailableModules += request.module.gradlePath
      }
      return
    }
    val fetcher = DaemonA11yFetcher(onLog = { System.err.println("[daemon a11y] $it") })
    for (request in requests) {
      val (module, manifest, previews, consumerPreviewIds, narrowed) = request
      attemptedAnyModule = true
      if (verbose && narrowed) {
        System.err.println(
          "compose-preview a11y: ${module.gradlePath} fetching ATF for ${previews.size} of " +
            "${consumerPreviewIds.size} preview(s); the rest keep whatever the last run recorded, " +
            "and the report is marked partial for any still uncovered."
        )
      }
      val outcome =
        fetcher.fetch(
          projectDir = module.projectDir,
          modulePath = module.gradlePath,
          moduleName = manifest.module,
          previews = previews,
          // A narrowed run carries the rest of the module's report forward and marks uncovered
          // previews as partial. Coverage uses the consumer's (permutation-expanded) id space.
          modulePreviewIds = consumerPreviewIds,
          narrowed = narrowed,
        )
      when (outcome) {
        is DaemonA11yFetcher.Outcome.Ok -> {
          if (outcome.atfAvailable) {
            anyModuleAtfOk = true
            if (verbose) {
              System.err.println(
                "compose-preview a11y: ${module.gradlePath} wrote ${outcome.reportFile.path} " +
                  "(${outcome.entryCount} entr${if (outcome.entryCount == 1) "y" else "ies"})"
              )
            }
          } else {
            unavailableModules += module.gradlePath
            System.err.println(
              "compose-preview a11y: ${module.gradlePath} ATF data unavailable — every " +
                "per-preview fetch failed (see daemon log above)."
            )
          }
        }
        is DaemonA11yFetcher.Outcome.DescriptorMissing -> {
          unavailableModules += module.gradlePath
          System.err.println(
            "compose-preview a11y: ${module.gradlePath} missing daemon-launch.json at " +
              "${outcome.expected.path}"
          )
        }
        is DaemonA11yFetcher.Outcome.OpenFailed -> {
          unavailableModules += module.gradlePath
          System.err.println(
            "compose-preview a11y: ${module.gradlePath} failed to open render session " +
              "(${outcome.reason})"
          )
        }
      }
    }
  }

  /**
   * Fail when ATF was requested and no module produced any data; otherwise a broken daemon looks
   * like a clean run. Null falls through to the default exit policy.
   */
  override fun atfUnavailableExitMessage(): String? {
    if (!attemptedAnyModule) return null
    if (anyModuleAtfOk) return null
    val moduleList =
      if (unavailableModules.isEmpty()) "all modules" else unavailableModules.joinToString(", ")
    return "compose-preview a11y: ATF data unavailable for $moduleList — failing run rather " +
      "than reporting an empty findings list. See daemon log above."
  }

  /**
   * Run `:<module>:composePreviewDaemonStart` so each module has a fresh `daemon-launch.json`;
   * returns false when the task failed.
   */
  private fun runDaemonStartTasks(modules: List<PreviewModule>): Boolean {
    // Once per module per run (`guidelines` fetches in rounds).
    val pending = modules.filter { it.gradlePath !in daemonStarted }
    if (pending.isEmpty()) return true
    var ok = true
    withGradle(silenceStdout = jsonOutput) { gradle ->
      val tasks = pending.map { ":${it.gradlePath}:composePreviewDaemonStart" }.toTypedArray()
      ok =
        withGradleStdout(jsonOutput) {
          runGradle(gradle, *tasks, arguments = gradleArgsWithForce())
        }
    }
    if (ok) daemonStarted += pending.map { it.gradlePath }
    return ok
  }

  private val daemonStarted = mutableSetOf<String>()
}

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

/**
 * `show --json --link`: add `"link"` to each `previews[i]` of [encoded] by position; null adds
 * nothing.
 */
internal fun injectPreviewLinks(encoded: String, links: List<String?>, pretty: Boolean): String {
  val codec = if (pretty) json else briefJson
  val root = codec.parseToJsonElement(encoded).jsonObject
  val previews = root["previews"]?.jsonArray ?: return encoded
  val linked =
    JsonArray(
      previews.mapIndexed { i, row ->
        val link = links.getOrNull(i)
        if (link == null || row !is JsonObject) row
        else JsonObject(row + ("link" to JsonPrimitive(link)))
      }
    )
  return codec.encodeToString(JsonObject.serializer(), JsonObject(root + ("previews" to linked)))
}

/**
 * The stderr note for a Gradle-render command ([command] is `show` / `render`) whose display
 * settings cannot apply; null when none are set.
 */
internal fun unappliedSettingsNote(command: String, settings: CliPreviewSettings): String? {
  val set =
    settings.setKeys().ifEmpty {
      return null
    }
  return "compose-preview: note: ${CliPreviewSettingsFile.defaultFile().path} sets " +
    "${set.joinToString(", ")}, which '$command' does not apply: it renders each preview as its " +
    "@Preview declares. `render-matrix` and `record` apply these settings (an explicit flag still " +
    "wins), as does the MCP server's render_preview."
}
