package ee.schimke.composeai.cli

import ee.schimke.composeai.io.SystemFileSystem
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import kotlin.system.exitProcess
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath

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

abstract class Command(
  protected val args: List<String>,
  protected val fileSystem: FileSystem = SystemFileSystem,
) {
  protected val explicitModule: String? = args.flagValue("--module")
  protected val filter: String? = args.flagValue("--filter")
  protected val exactId: String? = args.flagValue("--id")
  protected val verbose: Boolean = "--verbose" in args || "-v" in args
  protected val progress: Boolean = verbose || "--progress" in args
  protected val timeoutSeconds: Long =
    args.flagValue("--timeout")?.toLongOrNull() ?: GradleConnection.DEFAULT_TIMEOUT_SECONDS
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
   * `--variant <name>` forwards as `-PcomposePreview.variant=<name>` on every Gradle invocation
   * this CLI makes — model queries AND task runs — via the connection's `extraArguments`. Used by
   * consumers with flavored application modules (e.g. `:app` with `demoDebug` / `prodDebug`
   * variants and no plain `debug`) to pin which variant the plugin attaches `composePreview*` tasks
   * to. Default is unset: the plugin's own `composePreview.variant` convention picks `debug` and
   * falls back to `*Debug` suffix matches in flavored modules — see issue #1546.
   */
  protected val variantOverride: String? =
    args.flagValue("--variant")?.trim()?.takeIf { it.isNotEmpty() }

  protected fun variantGradleArgs(): List<String> {
    val v = variantOverride ?: return emptyList()
    return listOf("-PcomposePreview.variant=$v")
  }

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

  protected val permutations: List<String> =
    PreviewPermutationsCli.clean(args.flagValuesAll("--permutations"))

  protected fun permutationsGradleArgs(): List<String> =
    if (permutations.isEmpty()) emptyList()
    else listOf("-P${PreviewPermutationsCli.PROPERTY}=${permutations.joinToString(",")}")

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

  /**
   * `--missing-renders <fail|warn|ignore>` passes through as
   * `-PcomposePreview.missingRenders=<value>` so the Gradle-plugin-side validation in
   * `composePreviewRenderAll` knows whether to escalate, warn, or stay silent when a preview listed
   * in the manifest produced no PNG. The CLI doesn't validate the value — invalid values fall
   * through to the plugin's "fail" default. Empty / absent means "don't pass the flag at all",
   * which keeps the historical behaviour intact.
   */
  protected val missingRendersPolicy: String? =
    args.flagValue("--missing-renders")?.trim()?.takeIf { it.isNotEmpty() }

  protected fun missingRendersGradleArgs(): List<String> {
    val v = missingRendersPolicy ?: return emptyList()
    return listOf("-PcomposePreview.missingRenders=$v")
  }

  /**
   * Whether the CLI's own "Render task completed but produced no PNG for N of M" post-check should
   * escalate to a non-zero exit. The Gradle-plugin-side validation in `composePreviewRenderAll`
   * already honours `composePreview.missingRenders` for the throw vs warn vs silent decision; this
   * mirrors the policy on the CLI side so a `warn` or `ignore` run actually surfaces as exit 0 to
   * the apply action / shell caller. Unknown values fall through to "fail" — same hard-fail
   * fallback the plugin uses.
   */
  protected fun shouldFailOnMissingRenders(): Boolean =
    when (missingRendersPolicy?.lowercase()) {
      "warn",
      "ignore" -> false
      else -> true
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
    val missingRendersArgs = missingRendersGradleArgs()
    val permutationsArgs = permutationsGradleArgs()
    val withExtras = extra + extensionArgs + missingRendersArgs + permutationsArgs
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
    val connection =
      withGradleStdout(silenceStdout) {
        // `--variant` goes on the connection rather than per-call so the
        // ProjectModel query (which picks up `composePreviewDiscover` task
        // presence to enumerate preview modules) sees the same variant the
        // task runs use. Without this, a flavored `:app` would still be
        // invisible to `findPreviewModules()` because its task only registers
        // when `-PcomposePreview.variant=demoDebug` is on the model query too.
        GradleConnection(root, verbose, progress, extraArguments = injectArgs + variantGradleArgs())
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
      val one = gradle.findPreviewModule(explicitModule, timeoutSeconds)
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
        printDiscoveryFailures(gradle.lastDiscoveryFailures)
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
        System.err.println(
          "Check Gradle wrapper/cache access, then rerun with --verbose for full output."
        )
        exitProcess(1)
      }
      System.err.println("No preview modules discovered in this project.")
      printDiscoveryFailures(gradle.lastDiscoveryFailures)
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
   * Auto-provision the native `xr-composite` binary into the shared cache the plugin's
   * `composePreviewCompositeXr` task discovers, but ONLY when there's XR work to do. Runs
   * `composePreviewDiscover` first (cheap + UP-TO-DATE on warm builds) so we can read each module's
   * `previews.json` and gate the network fetch on the presence of an `XR_SUBSPACE` preview — a
   * non-XR render never touches the network. On any failure (offline, no Release asset for a
   * `-SNAPSHOT`, 404) the provisioner logs a concise note and returns without failing, so the
   * render proceeds exactly as before (the composite still is best-effort, like the plugin's
   * graceful skip).
   *
   * Returns the extra Gradle arguments the render should carry —
   * `-PcomposePreview.xrCompositeBinary =<path>` when a binary was provisioned (so the render uses
   * it explicitly even if cache discovery misfires), else empty. Discovery via the cache path is
   * the general mechanism; passing the property is a belt-and-braces handoff.
   */
  /**
   * Run `:<module>:composePreviewDiscover` for every module as its own Gradle invocation, ahead of
   * the render. This is what makes `composePreview { shards = auto }` size the fork count correctly
   * on a cold CI runner: the plugin resolves the shard count at *configuration* time by reading
   * `build/compose-previews/previews.json`, which only exists once discover has run. Because
   * `composePreviewRenderAll` depends on discover, a *separate* prior discover invocation writes
   * the manifest, so when the render invocation configures (a distinct task set → its own
   * configuration-cache entry) it sees a fresh manifest and auto sizing engages on the first run.
   * On warm builds discover is UP-TO-DATE and near-free. Returns the build result; callers treat
   * failure as non-fatal (the render re-runs discover as a dependency and surfaces the real error).
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
    // Discover so previews.json exists before we read it to gate the XR fetch. Callers that already
    // ran [runDiscover] pass discoverFirst = false to avoid a redundant (UP-TO-DATE) pass. A
    // discovery failure isn't fatal — the subsequent render surfaces it; we just skip provisioning.
    if (discoverFirst && !runDiscover(gradle, modules, silenceStdout)) return emptyList()
    val hasXr =
      readAllManifests(modules).any { (_, manifest) ->
        manifest.previews.any { it.params.kind == XrCompositeProvision.XR_SUBSPACE_KIND }
      }
    if (!hasXr) return emptyList()
    val binary = XrCompositeProvision.ensureCached(BUNDLE_VERSION) ?: return emptyList()
    return listOf("-PcomposePreview.xrCompositeBinary=${binary.absolutePath}")
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
    val discoveredPreviewCount: Int,
    /**
     * Preview ids this run rendered, or `null` when it rendered everything the modules declare.
     * Non-null means the Gradle drive was narrowed to the `--id` / `--filter` request
     * (issue #3730), so the previews outside it carry whatever the *previous* run left on disk —
     * often nothing at all. Reporting has to say so rather than count them as render failures.
     */
    val renderedIds: Set<String>? = null,
  )

  /**
   * Lower-level outcome of [renderModules] — only the gradle build result and the (optionally
   * filtered) module list, leaving manifest reads + result building to the caller. Used by commands
   * whose manifests don't fit the `PreviewManifest` / `PreviewResult` shape (today:
   * `show-resources`, which has its own resource-manifest type).
   */
  protected data class RawRenderOutcome(
    val buildOk: Boolean,
    val modules: List<PreviewModule>,
    val discoveredPreviewCount: Int,
    val taskOutcomes: Map<String, GradleTaskOutcome> = emptyMap(),
    /**
     * Preview ids this run actually asked Gradle to render, or `null` when it rendered everything.
     * Threaded into [buildResults] so a narrowed render doesn't drop the `.cli-state.json` shas of
     * the previews it deliberately skipped — see [PreviewRenderScope].
     */
    val renderedIds: Set<String>? = null,
  )

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
    scopeToPreviewRequest: Boolean = false,
  ): RawRenderOutcome {
    var outcome: RawRenderOutcome? = null
    withGradle(silenceStdout = silenceStdout) { gradle ->
      val modules = withGradleStdout(silenceStdout) { resolveModules(gradle).filter(moduleFilter) }
      outcome =
        if (modules.isEmpty()) RawRenderOutcome(true, emptyList(), 0)
        else {
          // Explicit discover pass before the render so `shards=auto` sees a fresh previews.json at
          // render-configuration time (see [runDiscover]). Kept as a first-class step — not an
          // incidental side effect of XR provisioning — so shard sizing can't regress if the XR
          // path changes. provisionXr therefore skips its own (now-redundant) discover.
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
          // Narrow the render itself to the requested previews rather than rendering the module and
          // discarding the rows afterwards (issue #3730). Only for the preview-shaped pipeline:
          // `show-resources` drives a resource manifest whose entries aren't `@Preview`s at all.
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
   * The `-PcomposePreview.idFilter` narrowing for this invocation's `--id` / `--filter`, plus the
   * stderr diagnostics that go with it. Shared by the [renderModules] pipeline (`show`, `a11y`,
   * reports) and by `render`, which drives Gradle itself.
   *
   * Returns [PreviewRenderScope.FULL] — render everything, the pre-#3730 behaviour — whenever the
   * request can't be resolved to an exact id list: no filter, no modules, or a discovery pass that
   * failed (the render task depends on discovery and is the authoritative retry, so a stale or
   * missing manifest must never be allowed to narrow it).
   */
  internal fun previewRenderScope(
    renderModules: List<PreviewModule>,
    discoveryManifests: List<Pair<PreviewModule, PreviewManifest>>,
    discoverySucceeded: Boolean,
  ): PreviewRenderScope.Scope {
    if (exactId == null && filter == null) return PreviewRenderScope.FULL
    if (renderModules.isEmpty()) return PreviewRenderScope.FULL
    val flag = if (exactId != null) "--id" else "--filter"
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
        permutations = permutations,
      )
    scope.note?.let { System.err.println("compose-preview: $flag $it; rendering the full module.") }
    if (verbose && scope.narrowed) {
      System.err.println(
        "compose-preview: $flag narrowed the render to ${scope.renderedIds?.size ?: 0} preview(s) " +
          "via -P${PreviewRenderScope.GRADLE_PROPERTY}"
      )
    }
    return scope
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
      renderedIds = raw.renderedIds,
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
   *
   * [renderedIds] names the previews this run actually re-rendered, or `null` for "all of them". It
   * only matters once the render is narrowed (issue #3730): a preview that was deliberately skipped
   * and has no PNG on disk must keep the sha the previous run recorded, or the next full render
   * would report it as `changed` purely because the CLI forgot it. A skipped preview whose PNG *is*
   * on disk needs nothing special — its sha still matches, so it reads as unchanged.
   */
  protected fun buildResults(
    manifests: List<Pair<PreviewModule, PreviewManifest>>,
    renderedIds: Set<String>? = null,
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
   * Re-adopt every `.cli-state.json` entry belonging to [id] that this run didn't rewrite
   * (issue #3730).
   *
   * A narrowed render leaves the previews it skipped with no PNG to hash, so
   * [applyImageOverrideAndStateDiff] writes no sha for them — and a dropped entry reads as
   * "first-ever render" next time, which would make the following full render report every skipped
   * preview as `changed`. The render didn't happen, so nothing about those previews' last known
   * pixels changed either: keep what the previous run recorded.
   *
   * It has to work off the prior *keys* rather than the result's captures, because a
   * `@PreviewParameter` preview whose fan-out files are all absent has **no** captures — the CLI
   * globs those rows off disk, so an unrendered parameterized preview presents as zero rows rather
   * than one empty one, and there is nothing to hang a carried-forward sha on. Matching is scoped
   * to the `<id>` / `<id>#…` family so a sibling (`Foo_Dark` next to `Foo`) can't be dragged along,
   * and [MutableMap.putIfAbsent] means a sha this run actually computed always wins.
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
      // Exclude kinds that never emit a PNG (see [NON_PNG_PREVIEW_KINDS]) and `optional` captures
      // so `counts.missing` matches what `--missing-renders` actually gates on — an
      // `@XrSubspacePreview` with no composite or a desktop `@ColorCatalog` sheet still isn't a
      // render failure.
      missing =
        results.count { r ->
          r.params.kind !in NON_PNG_PREVIEW_KINDS &&
            r.captures.all { c -> c.pngPath == null } &&
            r.captures.any { c -> !c.optional }
        },
    )

  protected fun matchesRequest(result: PreviewResult): Boolean = matchesRequest(result.id)

  protected fun matchesRequest(id: String): Boolean =
    previewIdMatchesRequest(id, exactId = exactId, filter = filter)

  /**
   * The ids in [manifest] this invocation's `--id` / `--filter` asks about — every id when there is
   * no request. The manifest-side counterpart of [matchesRequest], for the paths that fan out per
   * preview *before* there are [PreviewResult]s to filter (issue #3742).
   *
   * Takes the **unexpanded** discovery manifest (`PreviewResultBuilder.readAllManifests`, *not*
   * [readAllManifests], which layers [PreviewPermutationsCli] expansion on top) and returns
   * unexpanded ids, because the consumer is the daemon: `PreviewIndex.byId` resolves against the
   * `previews.json` the plugin wrote, so `Foo` is addressable and the CLI-synthesised `Foo_dark` is
   * not. Matching still happens against the *expanded* ids, since that is what the user saw in
   * `show` output — so `--id Foo_dark` selects `Foo`, the preview the daemon can actually render,
   * and `--filter Foo` under `--permutations accessibility` selects it once rather than four times.
   *
   * The two halves have to stay on opposite sides of the expansion: matching the unexpanded ids
   * would miss a permutation request entirely, and returning the expanded ones would hand the
   * daemon an id it answers with "unknown preview".
   */
  protected fun requestedPreviewIds(manifest: PreviewManifest): List<String> =
    manifest.previews
      .filter { preview ->
        PreviewPermutationsCli.expand(listOf(preview), permutations).any { matchesRequest(it.id) }
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
      discoverySucceeded = discoverySucceeded,
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

  protected fun findProjectRoot(): File? {
    var dir: File? = File(".").absoluteFile
    while (dir != null) {
      if (File(dir, "gradlew").exists()) return dir
      dir = dir.parentFile
    }
    return null
  }
}

/**
 * Preview kinds whose render legitimately never emits a PNG, so a null `pngPath` is expected rather
 * than a render failure. Currently just XR subspace previews: they render to a `scene.json`, and
 * the composite still PNG is an optional extra that only materialises when the `xr-composite`
 * binary is provisioned (it 404s on most CI runners). Gating `--missing-renders fail` on their
 * absent PNG would fail every run that ships an `@XrSubspacePreview`.
 *
 * Keep this in sync with `NON_PNG_PREVIEW_KINDS` in `.github/actions/lib/compare-previews.py`,
 * which already excludes the same kinds from its PR-comment "Render Failures" section. When the two
 * disagree the CLI fails the job while the comparison tool reports nothing — a red check with no
 * comment explaining it.
 */
/**
 * Prints per-project discovery failures (modules skipped because building their
 * `ComposePreviewModel` threw) to stderr. Called after a discovery comes back empty so the user
 * sees *why* — not the bare "No preview modules discovered" that hid the cause (issue #3). The
 * convention-plugin double-apply collision, an unresolved classpath dep, or a config-cache problem
 * all show up here. No-op when there were no failures (a genuinely plugin-free build). Capped so a
 * large multi-module build doesn't flood the terminal.
 */
internal fun printDiscoveryFailures(
  failures: List<ProjectDiscoveryFailure>,
  limit: Int = 10,
  err: (String) -> Unit = System.err::println,
) {
  if (failures.isEmpty()) return
  // When the failures are dominated by the "auto-injected plugin can't see AGP" signature there's
  // a single actionable cause — emit the guidance instead of N cryptic NoClassDefFoundError stacks
  // (issue #1947).
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
 * Recognises the "auto-injected plugin can't see AGP" failure signature: a `NoClassDefFoundError` /
 * `ClassNotFoundException` on an AGP variant-API class (`com.android.build.api.variant.*`, e.g.
 * `AndroidComponentsExtension`). The `NoClassDefFoundError` form carries the internal name
 * (`com/android/build/api/variant/…`) and the `ClassNotFoundException` form the dotted name, so we
 * match either separator.
 *
 * This arises when AGP is supplied by an **included build's convention plugin** (the `build-logic`
 * pattern): AGP's classes live on the convention plugin's classloader, but auto-inject puts
 * `ee.schimke.composeai.preview` on each project's *own* buildscript classpath — a sibling
 * classloader that can't see AGP — so the plugin's `apply()` throws the moment it touches
 * `AndroidComponentsExtension`. See [agpClassloaderGuidance] for the user-facing remedy
 * (issue #1947).
 */
internal fun isAgpClassloaderFailure(message: String): Boolean {
  val mentionsAgpVariantApi =
    Regex("""com[./]android[./]build[./]api[./]variant""").containsMatchIn(message)
  val classloaderError =
    message.contains("NoClassDefFoundError") || message.contains("ClassNotFoundException")
  return mentionsAgpVariantApi && classloaderError
}

/**
 * When discovery failures are *dominated* by the AGP-classloader signature
 * ([isAgpClassloaderFailure]), returns one actionable message explaining that auto-inject can't be
 * made to work for the included-build / convention-plugin layout and how to apply the plugin from
 * the convention plugin instead. Returns `null` otherwise so [printDiscoveryFailures] falls back to
 * the per-project list — a lone AGP error amid many unrelated configuration failures shouldn't
 * suppress the others.
 *
 * "Dominated" = the signature accounts for at least half the failures. Auto-inject genuinely can't
 * reach AGP's classloader here (there is no init-script API to add a dependency to an included
 * build's classpath), so this is a guidance fix, not a render fix (issue #1947).
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

internal fun previewIdMatchesRequest(id: String, exactId: String?, filter: String?): Boolean {
  if (exactId != null && id != exactId) return false
  if (filter != null && !id.contains(filter, ignoreCase = true)) return false
  return true
}

internal fun modulesMatchingPreviewRequest(
  modules: List<PreviewModule>,
  manifests: List<Pair<PreviewModule, PreviewManifest>>,
  exactId: String?,
  filter: String?,
  discoverySucceeded: Boolean = true,
): List<PreviewModule> {
  // The render task depends on discovery and is the authoritative retry. Do not let missing or
  // stale discovery manifests suppress that retry after the separate optimization pass fails.
  if (!discoverySucceeded) return modules
  if (exactId == null && filter == null) return modules
  val matchingPaths =
    manifests
      .filter { (_, manifest) ->
        manifest.previews.any { previewIdMatchesRequest(it.id, exactId = exactId, filter = filter) }
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
 * Previews that finished rendering but produced no PNG for at least one capture — the set
 * `--missing-renders` gates on and the diagnostic enumerates. Excludes [NON_PNG_PREVIEW_KINDS],
 * whose empty `pngPath` is by design, and `optional` captures, whose missing PNG is expected
 * (best-effort artefacts like a `@ColorCatalog` sheet on the desktop backend — see
 * `Capture.optional`). Pulled out as a pure function so the policy is unit-testable without
 * standing up a Gradle render.
 */
internal fun previewsMissingPng(results: List<PreviewResult>): List<PreviewResult> =
  results.filter { r ->
    r.params.kind !in NON_PNG_PREVIEW_KINDS && r.captures.any { it.pngPath == null && !it.optional }
  }

/**
 * Whether `show` can still emit a useful report after gradle reported failure.
 *
 * `composePreviewRenderAll` fails the **whole task** when any single preview fails to render, so
 * one persistently broken preview in a module of sixty used to make `show` discard the fifty-nine
 * good ones and print nothing but "Render failed" — no `pngPath`, no `sha256`, no `changed`. That
 * makes the documented iterate loop ("re-render, read the entries marked changed") unusable in any
 * real repo. `renderAllModules` reads every manifest regardless of the build result, so whenever it
 * produced results there is something worth reporting; only a build that died before writing any
 * manifest leaves genuinely nothing to say. The exit code is unaffected either way — see
 * [showExitCode].
 *
 * Pure function so the policy is unit-testable without standing up a Gradle render.
 */
internal fun canReportAfterBuildFailure(results: List<PreviewResult>): Boolean =
  results.isNotEmpty()

/**
 * `show`'s exit code: a gradle failure (2) outranks whatever the output itself would have reported,
 * so emitting partial results never downgrades a failed build to a success — and never reports "no
 * previews matched" (3) for a build that never got far enough to know.
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
  private val jsonOutput = "--json" in args
  // Auto-on when stdout is an interactive TTY in a kitty-graphics-capable terminal. Users
  // opt out with `--images=off`; `--images=kitty` forces it on (still TTY-gated). `--json`
  // always wins — escape sequences would corrupt the JSON envelope.
  private val imagesMode: TerminalImages.Mode =
    if (jsonOutput) TerminalImages.Mode.OFF
    else
      TerminalImages.resolve(
        modeArg = args.flagValue("--images"),
        env = { System.getenv(it) },
        isTty = System.console() != null,
      )

  override fun run() {
    val outcome =
      renderAllModules(silenceStdout = jsonOutput, gradleArguments = gradleArgsWithForce())
    if (!outcome.buildOk) {
      System.err.println("Render failed")
      // A failed build does not imply there is nothing to report. `renderAllModules` reads every
      // module's manifest and builds results whether or not gradle succeeded (see
      // [RenderModulesOutcome.buildOk]), and `composePreviewRenderAll` fails the *whole task* when
      // any single preview fails to render — so one broken preview in a module of sixty used to
      // discard the fifty-nine good ones, emitting no JSON at all: no `pngPath`, no `sha256`, no
      // `changed`. That makes the documented iterate loop ("re-render, read the changed entries")
      // unusable in any repo with one persistently broken preview, and pushes agents into
      // hand-globbing the renders directory. Surface what did render, mark the rest via the
      // existing per-preview `[no PNG]` / null-`pngPath` channel, and keep the exit code at 2 so
      // scripts gating on success are unaffected. Only bail early when there is genuinely nothing
      // to show (gradle died before any manifest was written).
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
    // Counts reflect the full discovered set so an agent using `--changed-only` can still see
    // "60 unchanged, 0 changed" and skip a follow-up query — but only when the run actually
    // rendered that set. Once `--id` / `--filter` narrows the Gradle drive (issue #3730) the
    // previews outside the request were deliberately not rendered, so counting their absent PNGs
    // as `missing` would report 63 render failures for a run that did exactly what was asked.
    val countsScope = if (outcome.renderedIds == null) all else all.filter { matchesRequest(it) }

    if (filtered.isEmpty()) {
      if (jsonOutput) println(encodeResponse(emptyList(), countsScope = countsScope))
      else println("No previews matched.")
      System.out.flush()
      // A build failure outranks "no match": exit 3 advertises a healthy build that simply had
      // nothing matching the filter, which would be a lie here.
      exitProcess(showExitCode(outcome.buildOk, naturalCode = 3))
    }

    if (jsonOutput) {
      println(encodeResponse(filtered, countsScope = countsScope))
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
            println("  [${captureCoordLabel(c)}]$tag ${c.pngPath ?: ""}")
          }
        }
        emitInlineImage(r)
      }
    }

    // "Missing" = at least one capture failed to produce a PNG, excluding kinds that never emit
    // one (see [previewsMissingPng] / [NON_PNG_PREVIEW_KINDS]).
    val missing = previewsMissingPng(filtered)
    if (missing.isNotEmpty()) {
      // Diagnostic stays under warn/ignore so CI logs remain grep-able — only the exit code
      // changes. `--missing-renders warn|ignore` is the explicit opt-down; everything else
      // (including unset) keeps the historical hard fail.
      val policy = missingRendersPolicy?.lowercase()
      val prefix =
        if (policy in setOf("warn", "ignore")) "missing-renders policy=$policy — " else ""
      System.err.println(
        "${prefix}Render task completed but produced no PNG for ${missing.size} of " +
          "${filtered.size} preview(s):"
      )
      // List the offenders so the CI log is self-diagnosing — no need to download the
      // `composePreviewRender-reports` artifact just to learn *which* previews failed.
      for (r in missing) {
        val nullCoords =
          r.captures
            .filter { it.pngPath == null && !it.optional }
            .joinToString(", ") { captureCoordLabel(it) }
            .ifEmpty { "default" }
        val moduleTag = if (r.module.isNotBlank()) " (${r.module})" else ""
        System.err.println("  - ${r.id}$moduleTag — no PNG for: $nullCoords")
      }
      System.err.println(
        "Check the Gradle output above — a common cause is the `composePreviewRender` task " +
          "reporting NO-SOURCE, which means the renderer test class wasn't found on " +
          "testClassesDirs. Per-preview stack traces are in the `composePreviewRender-reports` " +
          "artifact attached to the run."
      )
      System.out.flush()
      if (shouldFailOnMissingRenders()) exitProcess(2)
    }
    System.out.flush()
    // Output has been emitted; now honour the build failure we deferred above. Left as a guarded
    // exit rather than an unconditional `exitProcess(showExitCode(...))` so the success path still
    // returns normally to the caller instead of taking the process down from inside a subcommand.
    if (!outcome.buildOk) exitProcess(showExitCode(false, naturalCode = 0))
  }

  /**
   * Emit the rendered PNG(s) inline using the resolved terminal-images mode. Multi-capture previews
   * — paused-clock frames with increasing `advanceTimeMillis` — become a native kitty animation;
   * single-capture previews emit a still. Captures with no PNG (render produced nothing) are
   * skipped so the animation doesn't include a phantom hole; the surrounding `[no PNG]` text tags
   * still tell the user what happened.
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

  /**
   * `--bundle` opt-in: after rendering, also pack each module's previews into a portable PNG+ZIP
   * bundle (`<module>/build/compose-previews/bundle.png`) via the `composePreviewBundle` task — one
   * bundle per module, containing all of that module's previews. Off by default: the bundle step
   * adds a classpath closure walk + jar minimization on top of the render, which is wasted work on
   * the fast iterate loop where you only want PNGs. Reach for it when you want a shareable artifact
   * (see `compose-preview bundle` for inspect/extract/render of the result).
   */
  private val bundle: Boolean = "--bundle" in args

  /**
   * `--embed-deps` (only meaningful with `--bundle`): carry reachable third-party jars inside the
   * bundle instead of referencing Maven coordinates. Bigger file, but renders offline with no build
   * system on the other end. Forwarded as `-PbundleEmbedDeps=true`.
   */
  private val embedDeps: Boolean = "--embed-deps" in args

  /**
   * `--format png|svg` (default `png`). `svg` emits the layered, editable `compose/figma-svg`
   * vector export per matched preview instead of stopping at the raster PNG. Because the standalone
   * `composePreviewRender` task never produces figma-svg (it's a daemon-only structured data
   * product — see `docs/DATA_PRODUCTS.md`), `--format svg` drives a short-lived render daemon after
   * the render, exactly like `bundle pack --with-semantics`. The PNGs are still produced; the SVGs
   * are the additional deliverable.
   */
  private val formatFlag: String? =
    args.flagValue("--format")?.trim()?.lowercase()?.ifEmpty { null }

  override fun run() {
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
      // Discover first so `--id` / `--filter` can be resolved to the exact ids Gradle should
      // render, instead of rendering every module at full width and dropping the rows afterwards
      // (issue #3730). `show` gets this from the shared [renderModules] pipeline; `render` drives
      // Gradle itself, so it repeats the two steps here.
      val discoverySucceeded = runDiscover(gradle, resolved, silenceStdout = false)
      val discoveryManifests = if (discoverySucceeded) readAllManifests(resolved) else emptyList()
      // `--bundle` packs a whole *module's* previews into one portable artifact, and
      // `BundlePreviewTask` omits any preview whose PNG isn't on disk — so narrowing under
      // `--bundle` would quietly ship a bundle containing a single preview, and dropping the
      // non-matching modules would stop producing their bundles at all. That command keeps its
      // pre-#3730 full-width behaviour; the render is the cheap half of it anyway.
      val modules =
        if (bundle) resolved
        else
          modulesMatchingPreviewRequest(
            resolved,
            discoveryManifests,
            exactId = exactId,
            filter = filter,
            discoverySucceeded = discoverySucceeded,
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
      // `render` ignores `--changed-only` so the agent can ask "render
      // the world, but report only what changed" via a follow-up
      // `show --changed-only`.
      val filtered = all.filter { matchesRequest(it) }

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
          val policy = missingRendersPolicy?.lowercase()
          val prefix =
            if (policy in setOf("warn", "ignore")) "missing-renders policy=$policy — " else ""
          System.err.println(
            "${prefix}Render task completed but produced no PNG for ${missing.size} preview(s):"
          )
          for (r in missing) System.err.println("  ${r.id}")
          if (shouldFailOnMissingRenders()) exitProcess(2)
        }
      }
    }
  }

  /**
   * `--format svg` output pass. The standalone render never produces figma-svg (a daemon-only
   * structured data product), so — exactly like `bundle pack --with-semantics` — we regenerate each
   * module's `daemon-launch.json` and drive a short-lived [DaemonSemanticsFetcher] render so the
   * always-on `compose/figma-svg` extension writes each preview's sidecar, then land those bytes.
   *
   * Where they land depends on `--bundle`:
   * - **without `--bundle`**: loose `.svg` files. With `--output` a single matched preview's SVG is
   *   written to that path; otherwise each lands at
   *   `<module>/build/compose-previews/renders/<id>.svg` beside the PNGs.
   * - **with `--bundle`**: the SVGs (and any hybrid `figma-raster/<node>.png` crops) are injected
   *   into each module's freshly-packed `bundle.png` as `previews/<id>.figma.svg`, the same carrier
   *   `bundle pack --with-semantics` uses — so the packaged live bundle ships the editable vector
   *   per preview alongside the raster cover.
   *
   * Best-effort per preview (a backend with no figma-svg producer, or a preview that drew no vector
   * layers, is simply skipped with a stderr note), but if *nothing* was produced the command exits
   * non-zero — `--format svg` asked for SVG and got none.
   */
  private fun emitSvgOutputs(
    gradle: GradleConnection,
    modules: List<PreviewModule>,
    filtered: List<PreviewResult>,
  ) {
    // A single-file `--output` target only makes sense for loose output; `--bundle` injects into
    // each module's bundle, so the two are mutually exclusive.
    if (output != null && bundle) {
      System.err.println("--output cannot be combined with --bundle for --format svg.")
      exitProcess(1)
    }
    if (output != null && filtered.size != 1) {
      System.err.println(
        "--output requires a single match (got ${filtered.size}). " +
          "Narrow with --id <exact> or --filter <substring>."
      )
      exitProcess(1)
    }

    val moduleByPath = modules.associateBy { it.gradlePath }
    var totalWritten = 0
    for ((modulePath, rows) in filtered.groupBy { it.module }) {
      val module = moduleByPath[modulePath] ?: continue
      // Regenerate the launch descriptor against the current classpath in a separate invocation —
      // its failure only forfeits this module's SVGs, it must not abort the whole command.
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
      when (outcome) {
        is DaemonSemanticsFetcher.Outcome.Ok -> {
          svgById = outcome.figmaSvgById
          rasterById = outcome.figmaRasterById
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
        if (bundle) injectSvgIntoModuleBundle(module, modulePath, svgById, rasterById)
        else writeLooseSvgFiles(module, rows, svgById, rasterById)

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
   * Write one module's figma-svg exports as loose `.svg` files (the non-`--bundle` path) and return
   * the number written. A hybrid preview's `figma-raster/<node>.png` crops ride along in a sibling
   * `<id>.figma-raster/` dir via [RenderSvgOutput.write].
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
   * Inject one module's figma-svg exports (and hybrid raster crops) into its freshly-packed
   * `bundle.png` as `previews/<id>.figma.svg` — the same carrier + reusable injectors `bundle pack
   * --with-semantics` uses. Returns the number of SVG entries written (0 if the bundle is missing).
   * Best-effort: a missing bundle warns rather than aborting the whole command.
   */
  private fun injectSvgIntoModuleBundle(
    module: PreviewModule,
    modulePath: String,
    svgById: Map<String, ByteArray>,
    rasterById: Map<String, Map<String, ByteArray>>,
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
   * Gradle task list for this render run, one entry per module. With `--bundle` set, each module's
   * `composePreviewBundle` is appended right after its `composePreviewRenderAll` so both run in a
   * single Gradle invocation: `composePreviewRenderAll` depends on `composePreviewRender`, and the
   * bundle task declares `mustRunAfter("composePreviewRender")`, so the bundle packs the PNGs this
   * run just produced.
   */
  internal fun previewTasksFor(modulePaths: List<String>): List<String> = buildList {
    for (path in modulePaths) {
      add(":$path:composePreviewRenderAll")
      if (bundle) add(":$path:composePreviewBundle")
    }
  }

  /**
   * Extra Gradle properties for the bundle step. Empty unless `--bundle` is set; `--embed-deps`
   * (only meaningful alongside `--bundle`) adds `-PbundleEmbedDeps=true` so reachable third-party
   * jars are carried inside the bundle rather than referenced by Maven coordinate.
   */
  internal fun bundleGradleArgs(): List<String> =
    if (bundle && embedDeps) listOf("-PbundleEmbedDeps=true") else emptyList()

  /**
   * Print one line per module's freshly-packed bundle. Reuses [BundleReader] to read back the
   * polyglot we just wrote so the summary reflects what actually landed on disk (preview count,
   * resolution mode) rather than what we asked for. A missing file is a warning, not a hard fail —
   * the render itself already succeeded by the time we get here.
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
   * One module's share of the work [produceAdditionalDataProducts] has to do.
   *
   * [previewIds] is **already narrowed** to the invocation's `--id` / `--filter` — implementations
   * fan out over it rather than over `manifest.previews`, so `a11y --filter Foo` pays for one
   * per-preview daemon render instead of the module's full set (issue #3742). A module none of
   * whose previews the request selects never becomes a request at all.
   *
   * [narrowed] is true exactly when [previewIds] is a strict subset of the module's previews, i.e.
   * when whatever this hook writes covers only part of the module. The sidecars are *per-module*
   * reports, so a narrowed run that writes one wholesale discards the findings for previews the
   * user didn't ask about — the analogue of the `.cli-state.json` problem #3730 had to solve.
   * Implementations must merge into whatever is already on disk when this is set, not clobber it.
   */
  data class DataProductRequest(
    val module: PreviewModule,
    val manifest: PreviewManifest,
    val previewIds: List<String>,
    val narrowed: Boolean,
  )

  /**
   * Subclass hook called between the gradle build and the result-building / reporting step.
   * Subclasses use this to spin up additional production paths (the daemon-driven a11y fetch in
   * [A11yCommand]) that write sidecar JSON the extension renderer's `load` pass then picks up when
   * [buildResults] runs. Default no-op.
   *
   * At the point this is called, the standard `composePreviewRenderAll` gradle task has already run
   * and each module's `previews.json` is on disk. Implementations walk [requests] — one per module
   * with at least one requested preview — drive any out-of-band production for
   * [DataProductRequest.previewIds], and write the resulting sidecars to the conventional
   * `build/compose-previews/<extension>.json` locations the renderers read.
   */
  protected open fun produceAdditionalDataProducts(requests: List<DataProductRequest>) {}

  /**
   * Resolve the per-module work list for [produceAdditionalDataProducts]: each module's manifest
   * narrowed to the `--id` / `--filter` request, dropping the modules the request leaves empty.
   *
   * The narrowing is computed from the request itself rather than from
   * [RawRenderOutcome.renderedIds]: that set is `null` both when there was no request and when the
   * request happened to select every preview (the render declines to narrow in that case, because a
   * filtered `composePreviewRender` isn't build-cacheable), so it cannot answer "what did the user
   * ask about". [requestedPreviewIds] can — in the unexpanded, daemon-addressable id space these
   * manifests are read in.
   */
  protected fun dataProductRequests(
    manifests: List<Pair<PreviewModule, PreviewManifest>>
  ): List<DataProductRequest> = manifests.mapNotNull { (module, manifest) ->
    val requested = requestedPreviewIds(manifest)
    if (requested.isEmpty()) null
    else {
      warnAboutPermutationSubstitution(module, requested)
      DataProductRequest(
        module = module,
        manifest = manifest,
        previewIds = requested,
        narrowed = requested.size < manifest.previews.size,
      )
    }
  }

  /**
   * Say out loud when the request only matched through `--permutations` expansion, so this run
   * fetched the *declared* preview instead (see [requestedPreviewIds]).
   *
   * Data products are produced for the declared preview at its own parameters: the daemon has no
   * per-permutation lane, and its artefacts are keyed by preview id
   * (`build/compose-previews/data/<id>/`), so several permutations of one preview would overwrite
   * each other's overlay and hierarchy files. What the user sees is that the permutation row they
   * asked about carries no data — and without this line, that reads as a clean result.
   */
  private fun warnAboutPermutationSubstitution(module: PreviewModule, requested: List<String>) {
    val substituted = requested.filterNot { matchesRequest(it) }
    if (substituted.isEmpty()) return
    System.err.println(
      "compose-preview $extensionId: ${module.gradlePath} — the request matched " +
        "${substituted.size} preview(s) only through --permutations expansion, so $extensionId " +
        "data is produced for the declared preview(s) (${substituted.joinToString(", ")}) at " +
        "their own parameters. The permutation row itself will carry no $extensionId data; " +
        "per-permutation production is not implemented."
    )
  }

  /**
   * Optional subclass hook for "the data product we just tried to produce wasn't actually
   * available" — e.g. `compose-preview a11y` couldn't get ATF data from the daemon for any module.
   * Returning a non-null message causes [run] to print it to stderr and exit with code 2 *before*
   * the JSON / table output, so the consumer never sees a misleading "no findings" report. Default:
   * null (no override).
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

    // Hand-roll the renderModules→manifests→buildResults pipeline so the subclass hook can slot
    // between gradle finish and renderer load. `renderAllModules` is the same shape but
    // single-shot — no hook seam — so we expand it here.
    val raw =
      renderModules(
        silenceStdout = jsonOutput,
        gradleArguments = gradleArgsWithForce(),
        scopeToPreviewRequest = true,
      )
    val manifests = readAllManifests(raw.modules)
    // Deliberately the *unexpanded* manifests, not `manifests` — the hook drives the daemon, which
    // only knows the previews the plugin discovered. See [requestedPreviewIds].
    produceAdditionalDataProducts(
      dataProductRequests(PreviewResultBuilder.readAllManifests(raw.modules))
    )
    // After production runs, give the subclass a chance to abort the run when its data product
    // wasn't actually available — without this, a daemon-crash in `compose-preview a11y` looks
    // identical to a clean run with zero findings (issue #1453).
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
 * `composePreviewRenderAll` build completes, walks the requested previews through `data/fetch` for
 * `a11y/atf`, aggregates the findings into the canonical
 * `build/compose-previews/accessibility.json` shape that [A11yReportRenderer] then loads through
 * its disk-fallback path, and closes the session. The daemon is short-lived — spawned, drained,
 * shut down — so there's no persistent server for the agent / CI script to manage.
 *
 * "The requested previews" is load-bearing: each `a11y/atf` fetch is a per-preview daemon render,
 * so fanning out over the whole module would make `a11y --id Foo` cost 66 renders on a 66-preview
 * module to print one row (issue #3742). [ReportCommand.DataProductRequest] hands this hook the ids
 * the `--id` / `--filter` request actually selects, and the resulting partial report merges into
 * the module's existing `accessibility.json` rather than replacing it.
 *
 * The session is opened via the public `:render-session-api` / `:render-session-subprocess`
 * library; everything the CLI does here is reachable from any third-party tooling that compiles
 * against the same coordinates.
 */
class A11yCommand(args: List<String>) : ReportCommand(args, "a11y") {
  /**
   * Tracks ATF availability across modules so [run] can fail the CLI when no module successfully
   * produced any a11y data. Read by [atfUnavailableExitMessage]; set by
   * [produceAdditionalDataProducts]. The list of [unavailableModules] is used purely for the
   * user-facing error message.
   */
  private var attemptedAnyModule: Boolean = false
  private var anyModuleAtfOk: Boolean = false
  private val unavailableModules: MutableList<String> = mutableListOf()

  override fun produceAdditionalDataProducts(requests: List<DataProductRequest>) {
    if (requests.isEmpty()) return
    // The daemon launch descriptor (`daemon-launch.json`) is written by
    // `composePreviewDaemonStart`, which the standalone `composePreviewRenderAll` task does not
    // depend
    // on. Run it in a second gradle invocation so the descriptor is fresh against the
    // consumer's current classpath. Gradle's daemon reuses the warm JVM started by the first
    // invocation, so the cold-start cost is paid once per CLI run, not once per gradle task.
    val daemonStartOk = runDaemonStartTasks(requests.map { it.module })
    if (!daemonStartOk) {
      System.err.println(
        "compose-preview a11y: composePreviewDaemonStart failed; skipping daemon-driven a11y " +
          "fetch."
      )
      // Treat a failed daemon-start as ATF-unavailable for every module we were going to ask
      // about — the user needs to see the run fail rather than receive a misleading "no findings"
      // report.
      for (request in requests) {
        attemptedAnyModule = true
        unavailableModules += request.module.gradlePath
      }
      return
    }
    val fetcher = DaemonA11yFetcher(onLog = { System.err.println("[daemon a11y] $it") })
    for (request in requests) {
      val (module, manifest, previewIds, narrowed) = request
      attemptedAnyModule = true
      if (verbose && narrowed) {
        System.err.println(
          "compose-preview a11y: ${module.gradlePath} fetching ATF for ${previewIds.size} of " +
            "${manifest.previews.size} preview(s); the rest keep whatever the last run recorded, " +
            "and the report is marked partial for any still uncovered."
        )
      }
      val outcome =
        fetcher.fetch(
          projectDir = module.projectDir,
          modulePath = module.gradlePath,
          moduleName = manifest.module,
          previewIds = previewIds,
          // A narrowed run only speaks for the previews it fetched, so the fetcher carries the
          // rest of the module's report forward and marks what it still doesn't cover as partial
          // rather than publishing a module-wide report full of silent gaps (issue #3742).
          modulePreviewIds = manifest.previews.map { it.id },
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
   * Fail the CLI when ATF was requested for at least one module and no module produced any ATF
   * data. Without this, a broken daemon (classpath issue, missing descriptor) silently degrades to
   * a "no findings" report indistinguishable from a healthy clean run — see issue #1453. Returning
   * `null` falls through to the default exit-code policy.
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
