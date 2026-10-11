package ee.schimke.composeai.cli

import ee.schimke.composeai.guidelines.CatalogGuidelinesLoader
import ee.schimke.composeai.guidelines.GuidelineAnnotator
import ee.schimke.composeai.guidelines.GuidelineBudget
import ee.schimke.composeai.guidelines.GuidelineChecker
import ee.schimke.composeai.guidelines.GuidelineEngine
import ee.schimke.composeai.guidelines.GuidelineEvidenceHost
import ee.schimke.composeai.guidelines.GuidelineModel
import ee.schimke.composeai.guidelines.GuidelineResultCache
import ee.schimke.composeai.guidelines.GuidelineRunOptions
import ee.schimke.composeai.guidelines.GuidelineRunResult
import ee.schimke.composeai.guidelines.GuidelineSubjectKind
import ee.schimke.composeai.guidelines.GuidelineSurfaces
import ee.schimke.composeai.guidelines.OpenRouterClient
import ee.schimke.composeai.guidelines.PreviewGuidelineResult
import ee.schimke.composeai.guidelines.PreviewNode
import ee.schimke.composeai.guidelines.PreviewSubject
import ee.schimke.composeai.guidelines.SubjectPicture
import ee.schimke.composeai.guidelines.failures
import ee.schimke.composeai.guidelines.protocol.CatalogGuidelinesV1
import ee.schimke.composeai.guidelines.protocol.GuidelineVerdictV1
import ee.schimke.composeai.previewdata.AccessibilityNode
import ee.schimke.composeai.previewdata.AccessibilityReport
import ee.schimke.composeai.previewdata.PreviewResult
import ee.schimke.composeai.previewdata.PreviewResultBuilder
import java.io.File
import java.security.MessageDigest
import kotlin.system.exitProcess
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * `compose-preview guidelines` — checks rendered previews against their catalog's design guidelines
 * (`ui-builder.guidelines.json`) with a model through OpenRouter.
 *
 * It renders like every report command, then hands the renders, their sha256 and their source to
 * the `:design-guidelines` engine, which batches them, asks the model, fetches what a follow-up
 * round asks for ([CliEvidenceHost]: a preview's accessibility data through the same daemon fetch
 * [A11yCommand] drives, renders at other settings through the module's render daemon), and caches
 * each result by render hash under `build/compose-previews/guidelines/`. Accessibility data is
 * fetched for the previews whose rules ask for it, not for every preview up front. The results land
 * in `build/compose-previews/guidelines.json`. Each preview's source goes to the model with its
 * render, so rules about code are judged on the code.
 *
 * `--previews-json` / `--renders-dir` run the same engine over handoff renders with no Gradle,
 * which is what a CI publish job holds.
 *
 * The OpenRouter key is read from `COMPOSE_PREVIEW_OPENROUTER_KEY`, never from the command line. It
 * never reaches the project's build: `GradleConnection` withholds it from the environment the
 * Tooling API hands the Gradle daemon, and render daemons start from an allowlisted environment.
 */
class GuidelinesCommand(args: List<String>) : A11yCommand(args) {
  private val model: String = args.flagValue("--model") ?: OpenRouterClient.DEFAULT_MODEL
  private val maxCost: Double? = args.flagValue("--max-cost")?.toDoubleOrNull()
  private val roundsFlag: Int? = args.flagValue("--rounds")?.toIntOrNull()
  /** Follow-up rounds: 1 for the vision model, 3 for the jev checker, which starts minimal. */
  private val rounds: Int
    get() = roundsFlag ?: if (checker == GuidelineChecker.JEV) JEV_ROUNDS else 1

  /**
   * How long one model request may take, start to end, in seconds: a vision request over a dozen
   * screens can take minutes to answer. A request that runs past it is abandoned, and the engine
   * asks about fewer previews at once instead.
   */
  private val requestTimeoutSeconds: Long? =
    args.flagValue("--request-timeout")?.toLongOrNull()?.takeIf { it > 0 }
  /**
   * How long a streamed request may go without a token, in seconds, before it is cancelled (keep
   * alive comments do not count). Cancelling a stream stops the provider's bill where it can.
   */
  private val idleTimeoutSeconds: Long? =
    args.flagValue("--idle-timeout")?.toLongOrNull()?.takeIf { it > 0 }
  /** How many model requests may be in flight at once; 1 asks one batch at a time. */
  private val concurrency: Int =
    args.flagValue("--concurrency")?.toIntOrNull()?.takeIf { it > 0 }
      ?: GuidelineRunOptions.DEFAULT_CONCURRENCY
  private val stream: Boolean = "--no-stream" !in args
  /** OpenRouter's `provider.sort` (`price`, `throughput`, `latency`); none keeps its balancing. */
  private val providerSort: String? =
    args.flagValue("--provider-sort")?.takeIf { it in setOf("price", "throughput", "latency") }
  private val preferredMaxLatency: Double? =
    args.flagValue("--preferred-max-latency")?.toDoubleOrNull()?.takeIf { it > 0 }
  private val preferredMinThroughput: Double? =
    args.flagValue("--preferred-min-throughput")?.toDoubleOrNull()?.takeIf { it > 0 }
  /**
   * `--reasoning-effort`: OpenRouter's `reasoning.effort` for every request
   * ([OpenRouterClient.REASONING_EFFORTS]), or `default` to send none and leave the model at its
   * own. Reasoning is billed as output and shares `max_tokens` with the reply.
   */
  private val reasoningEffortFlag: String? = args.flagValue("--reasoning-effort")
  private val reasoningEffortValid: Boolean =
    reasoningEffortFlag == null ||
      reasoningEffortFlag == REASONING_DEFAULT ||
      reasoningEffortFlag in OpenRouterClient.REASONING_EFFORTS
  private val reasoningEffort: String? =
    when (reasoningEffortFlag) {
      null -> OpenRouterClient.DEFAULT_REASONING_EFFORT
      REASONING_DEFAULT -> null
      else -> reasoningEffortFlag
    }
  /**
   * Handoff mode's follow-up evidence: the captures the render job staged (see [HandoffInputs]).
   */
  private var handoffHost: GuidelineEvidenceHost? = null
  private val triage: Boolean = "--no-triage" !in args
  private val annotate: Boolean = "--annotate" in args
  private val guidelinesLocation: String? = args.flagValue("--guidelines")
  private val surfaceOverride: String? = args.flagValue("--surface")
  private val profileOverride: String? = args.flagValue("--profile")
  private val previewsJson: String? = args.flagValue("--previews-json")
  private val rendersDir: String? = args.flagValue("--renders-dir")
  private val a11yJson: String? = args.flagValue("--a11y-json")
  private val sourceRoot: String? = args.flagValue("--source-root")
  /**
   * `--checker vision|jev`: which model answers the rules. `jev` is EXPERIMENTAL — Jev decides the
   * structural rules from text only (source, accessibility nodes, measured checks) and leaves every
   * rule that needs the picture unchecked. Null when the value is not one of those.
   */
  private val checkerFlag: String? = args.flagValue("--checker")
  private val checker: GuidelineChecker? =
    if (checkerFlag == null) GuidelineChecker.VISION else GuidelineChecker.parse(checkerFlag)
  /**
   * `--compare-with <guidelines.json>`: another run's report to set this run's verdicts against.
   */
  private val compareWith: String? = args.flagValue("--compare-with")
  private var comparedTo: List<ModuleGuidelines>? = null

  /** The run's options, but for where the rules came from. */
  private fun runOptions(rulesSource: String): GuidelineRunOptions =
    GuidelineRunOptions(
        model = model,
        budget = GuidelineBudget(),
        maxRounds = rounds,
        triage = triage,
        maxCostUsd = maxCost,
        rulesSource = rulesSource,
        ranBy = System.getenv("USER")?.let { "cli:$it" },
      )
      .withConcurrency(concurrency)
      .withChecker(checker ?: GuidelineChecker.VISION)
      // The cache's half of the effort the client sends: a verdict at one is not reused at another.
      .withReasoningEffort(reasoningEffort)

  /** The model a report names: the one that answers under this checker. */
  private val reportModel: String
    get() = runOptions("").answeringModel

  /** What a report records of the checker: nothing for the default, so its output is unchanged. */
  private val reportChecker: String?
    get() = checker?.takeIf { it != GuidelineChecker.VISION }?.id

  override fun run() {
    if (checker == null) {
      System.err.println(
        "guidelines: unknown --checker $checkerFlag (expected vision, or the " + "experimental jev)"
      )
      exitProcess(2)
    }
    if (!reasoningEffortValid) {
      System.err.println(
        "guidelines: unknown --reasoning-effort $reasoningEffortFlag (expected one of " +
          (OpenRouterClient.REASONING_EFFORTS + REASONING_DEFAULT).joinToString() +
          ")"
      )
      exitProcess(2)
    }
    // Read before the run: a handoff run writes its report beside the renders, which may be the
    // very file it is asked to compare with.
    compareWith?.let { path ->
      comparedTo =
        GuidelinesComparison.load(File(path))
          ?: run {
            System.err.println("guidelines: --compare-with $path is not a guidelines.json report")
            exitProcess(2)
          }
    }
    val key = System.getenv(KEY_ENV)?.trim()?.takeIf { it.isNotEmpty() }
    if (key == null) {
      System.err.println(
        "guidelines: set $KEY_ENV to an OpenRouter key (openrouter.ai, Settings, then Keys). " +
          "The key is read from the environment only, never from the command line."
      )
      exitProcess(2)
    }
    val client =
      OpenRouterClient(
          key,
          http =
            OpenRouterClient.httpClient(
              requestTimeoutSeconds?.let { java.time.Duration.ofSeconds(it) }
                ?: OpenRouterClient.DEFAULT_REQUEST_TIMEOUT
            ),
        )
        .also {
          it.stream = stream
          idleTimeoutSeconds?.let { seconds ->
            it.idleTimeout = java.time.Duration.ofSeconds(seconds)
          }
          it.providerSort = providerSort
          it.preferredMaxLatencySeconds = preferredMaxLatency
          it.preferredMinThroughput = preferredMinThroughput
          it.reasoningEffort = reasoningEffort
        }
    if (previewsJson != null || rendersDir != null) exitProcess(runHandoff(client))

    val raw =
      renderModules(
        silenceStdout = jsonOutput,
        gradleArguments = gradleArgsWithForce(),
        scopeToPreviewRequest = true,
      )
    // Accessibility data is not fetched here for every preview: the engine asks the host for it
    // (`a11y` evidence) for the previews whose rules need it, through the same daemon fetch.
    val discovered = PreviewResultBuilder.readAllManifests(raw.modules)
    val manifests = readAllManifests(raw.modules)
    if (manifests.isEmpty()) {
      println("No previews discovered.")
      exitProcess(if (raw.buildOk) 0 else 2)
    }
    val results =
      selectRequested(buildResults(manifests, raw.renderedIds)).filter {
        !changedOnly || it.anyChanged()
      }
    val narrowed = results.size < manifests.sumOf { it.second.previews.size }

    val reports = mutableListOf<ModuleGuidelines>()
    var failed = false
    var incomplete = false
    for ((module, moduleResults) in results.groupBy { it.module }) {
      val projectDir =
        manifests.firstOrNull { it.first.gradlePath == module }?.first?.projectDir
          ?: moduleResults.firstOrNull()?.projectDirectory?.let(::File)
          ?: continue
      val buildDir = projectDir.resolve("build/compose-previews")
      val loaded =
        guidelinesLocation?.let { CatalogGuidelinesLoader.load(it) }
          ?: CatalogGuidelinesLoader.load(buildDir.resolve(CatalogGuidelinesV1.FILE_NAME))
      val guidelines = loaded.guidelines
      if (guidelines == null) {
        if (!jsonOutput) {
          println(
            "$module: no design guidelines" +
              (loaded.problem?.let { " ($it)" }
                ?: " — the catalog publishes no " +
                  "${CatalogGuidelinesV1.FILE_NAME}; pass --guidelines <file-or-url>")
          )
        }
        continue
      }
      val renders = moduleResults.associate { it.id to renderFile(it, projectDir) }
      val manifest = manifests.firstOrNull { it.first.gradlePath == module }
      val infos = manifest?.second?.previews.orEmpty().associateBy { it.id }
      val sources = { id: String ->
        infos[id]?.let { info ->
          val file = info.sourceFile ?: return@let null
          val line = info.bodyLine ?: return@let null
          PreviewSourceReader.readWithCallees(projectDir.resolve(file), line)
        }
      }
      // The same surface and profile a handoff run reads off the manifest, so a preview is asked
      // the same rules however it is checked.
      val kinds = HandoffInputs.readKinds(buildDir.resolve("previews.json"))
      val discoveredModule = discovered.firstOrNull { it.first.gradlePath == module }
      val subjects = moduleResults.mapNotNull { result ->
        subjectFor(
          result,
          renders[result.id],
          null,
          sources(result.id),
          kinds[result.id],
          HandoffInputs.longCaptureOf(infos[result.id], renders[result.id], buildDir),
          a11y = discoveredModule != null,
        )
      }
      // What a follow-up round may ask for: sources, renders at other settings through the
      // module's render daemon, and accessibility data fetched for the previews that ask.
      val host =
        CliEvidenceHost(
          projectDir = projectDir,
          moduleName = manifest?.second?.module ?: module,
          nodes = emptyMap(),
          sources = sources,
          a11yFetch =
            discoveredModule?.let { found ->
              { ids -> fetchA11y(found.first, found.second, ids, buildDir) }
            },
        )
      val cache = GuidelineResultCache(buildDir.resolve("guidelines"))
      val run =
        engine(
            client,
            cache,
            guidelines,
            guidelinesLocation ?: "${buildDir.path}/" + CatalogGuidelinesV1.FILE_NAME,
            host,
          )
          .run(guidelines, subjects)
      // A verdict kept under `+a11y` while the a11y fetch was failing was reached without that
      // evidence: forget it, so a run with a working daemon asks again.
      run.results
        .filter { !it.fromCache && host.a11yMissing(it.previewId) }
        .forEach { result ->
          subjects
            .firstOrNull { it.previewId == result.previewId }
            ?.let { cache.remove(it, guidelines, runOptions("").cacheModel) }
        }
      // A run over the whole catalog leaves the cache holding only its verdicts, so one carried
      // between CI runs does not grow with every render that ever changed.
      if (!narrowed && subjects.size == moduleResults.size) {
        cache.prune(subjects.map { it.previewId }.toSet())
      }
      val moduleReport =
        ModuleGuidelines.of(module, guidelines.catalog, reportModel, run, reportChecker)
      writeGuidelinesReport(buildDir, moduleReport, narrowed)
      // Outlines the nodes a finding names, fetched in any round; without them, regions only.
      if (annotate) annotateAll(run.results, subjects, host.knownNodes(), renders)
      reports += moduleReport
      failed = failed || tripped(run.results, guidelines)
      incomplete = incomplete || incomplete(module, run)
      if (!jsonOutput) GuidelinesReportRenderer.print(module, guidelines, run, checker)
    }
    if (jsonOutput)
      println(REPORT_JSON.encodeToString(ListSerializer(ModuleGuidelines.serializer()), reports))
    compare(reports)
    exitProcess(
      when {
        failOn != null && failOn !in setOf("warning", "warnings", "info", "none") -> {
          System.err.println("Unknown --fail-on value: $failOn (expected warning|info|none)")
          EXIT_UNKNOWN_FAIL_ON
        }
        else -> guidelinesExitCode(incomplete, failed, raw.buildOk)
      }
    )
  }

  /**
   * [ids]' accessibility data (nodes and ATF checks), fetched through the render daemon as the
   * `a11y` command does, narrowed to those previews; the module's `accessibility.json` is merged,
   * not replaced. Only what this fetch produced is returned: an entry from an earlier run may be of
   * an older render.
   */
  private fun fetchA11y(
    module: ee.schimke.composeai.previewdata.PreviewModule,
    manifest: ee.schimke.composeai.previewdata.PreviewManifest,
    ids: List<String>,
    buildDir: File,
  ): A11yEvidence {
    val wanted = ids.toSet()
    // Subjects carry the ids a consumer sees, `Foo_dark` under `--permutations`; the daemon is
    // addressed by the declared `Foo` with the permutation's overrides, as `a11y` itself does.
    val consumerIds = mutableListOf<String>()
    val previews = mutableListOf<RequestedPreview>()
    for (preview in manifest.previews) {
      if (!preview.includeInA11y) continue
      for (expanded in PreviewPermutationsCli.expand(listOf(preview), permutations)) {
        consumerIds += expanded.id
        if (expanded.id !in wanted) continue
        previews +=
          RequestedPreview(
            previewId = preview.id,
            entryId = expanded.id,
            overrides = PreviewPermutationsCli.overridesFor(preview, expanded),
          )
      }
    }
    if (previews.isEmpty()) return A11yEvidence()
    val report = buildDir.resolve("accessibility.json")
    val fetched = previews.map { it.entryId }.toSet()
    // A narrowed fetch merges into the report and keeps a preview's previous entry when its fetch
    // fails; that entry may be of an older render. Dropping the asked-for entries first means what
    // is read back is what this fetch produced, or nothing.
    dropA11yEntries(report, fetched)
    produceAdditionalDataProducts(
      listOf(
        DataProductRequest(
          module = module,
          manifest = manifest,
          previews = previews,
          consumerPreviewIds = consumerIds,
          narrowed = previews.size < consumerIds.size,
        )
      )
    )
    return A11yEvidence(
      nodes = readNodes(report).filterKeys { it in fetched },
      checks = HandoffInputs.readChecks(report).filterKeys { it in fetched },
    )
  }

  /** The engine over [client], caching in [cache], fetching follow-up evidence from [host]. */
  private fun engine(
    client: GuidelineModel,
    cache: GuidelineResultCache,
    guidelines: CatalogGuidelinesV1,
    rulesSource: String,
    host: GuidelineEvidenceHost = handoffHost ?: GuidelineEvidenceHost.None,
  ): GuidelineEngine =
    GuidelineEngine(
        model = client,
        host = host,
        cache = cache,
        options = runOptions(rulesSource),
      )
      .also { if (guidelines.rules.isEmpty()) System.err.println("guidelines: no rules to ask") }

  /**
   * Handoff mode: no Gradle, no daemon — what a CI publish job holds. Renders come from
   * `--renders-dir` (every PNG in it) or from `--previews-json`: either a flat list of ids, or the
   * module's real `previews.json`, whose captures name each render and whose `sourceFile` and
   * `bodyLine` give each preview's source under `--source-root` (the module directory).
   * `--a11y-json` (the a11y pipeline's `accessibility.json`) gives each preview's nodes, so
   * findings cite node ids and `--annotate` can outline them. A follow-up round (`--rounds`) can
   * ask only for the captures the render job staged beside a render ([HandoffEvidenceHost]):
   * nothing is rendered here.
   */
  private fun runHandoff(client: OpenRouterClient): Int {
    val location = guidelinesLocation
    if (location == null) {
      System.err.println("guidelines: handoff mode needs --guidelines <file-or-url>")
      return 2
    }
    val guidelines =
      CatalogGuidelinesLoader.load(location).let { loaded ->
        loaded.guidelines
          ?: run {
            System.err.println("guidelines: ${loaded.problem ?: "no guidelines at $location"}")
            return 2
          }
      }
    val inputs =
      HandoffInputs.read(
        previewsJson = previewsJson?.let(::File),
        rendersDir = rendersDir?.let(::File),
        a11yJson = a11yJson?.let(::File),
        sourceRoot = sourceRoot?.let(::File),
        surfaceOverride = surfaceOverride,
        profileOverride = profileOverride,
      )
    handoffHost = inputs.host
    val outDir = File(rendersDir ?: previewsJson?.let { File(it).absoluteFile.parent } ?: ".")
    val run =
      engine(client, GuidelineResultCache(outDir.resolve("guidelines")), guidelines, location)
        .run(guidelines, inputs.subjects)
    val report = ModuleGuidelines.of("handoff", guidelines.catalog, reportModel, run, reportChecker)
    outDir
      .resolve("guidelines.json")
      .writeText(REPORT_JSON.encodeToString(ModuleGuidelines.serializer(), report))
    if (annotate) {
      annotateAll(run.results, inputs.subjects, inputs.nodes, inputs.renders)
    }
    if (jsonOutput) println(REPORT_JSON.encodeToString(ModuleGuidelines.serializer(), report))
    else GuidelinesReportRenderer.print("handoff", guidelines, run, checker)
    compare(listOf(report))
    return guidelinesExitCode(
      incomplete = incomplete("handoff", run),
      failed = tripped(run.results, guidelines),
      buildOk = true,
    )
  }

  /**
   * With `--compare-with`, prints [reports]' verdicts set against that file's, per preview and
   * rule: on stdout, or stderr when stdout carries `--json`. Reads only the two reports.
   */
  private fun compare(reports: List<ModuleGuidelines>) {
    val path = compareWith ?: return
    val other = comparedTo ?: return
    val out = if (jsonOutput) System.err else System.out
    val comparison =
      GuidelinesComparison.compare(
        reports.flatMap { it.results },
        other.flatMap { it.results },
      )
    fun label(list: List<ModuleGuidelines>) =
      list
        .map { (it.checker ?: GuidelineChecker.VISION.id) + " " + it.model }
        .distinct()
        .joinToString()
    out.print(GuidelinesComparison.render(comparison, label(reports), "${label(other)} ($path)"))
  }

  /**
   * Whether [run] failed to judge previews it was asked to: requests that errored, were refused or
   * came back unreadable. Said on stderr, so a caller sees why the command did not exit 0.
   */
  private fun incomplete(module: String, run: GuidelineRunResult): Boolean {
    if (run.failedRequests == 0) return false
    System.err.println(
      "guidelines: $module: ${run.failedRequests} model request(s) failed; the previews in them " +
        "were not checked."
    )
    return true
  }

  private fun renderFile(result: PreviewResult, projectDir: File): File? =
    result.pngPath
      ?.let { path -> File(path).takeIf { it.isAbsolute } ?: projectDir.resolve(path) }
      ?.takeIf { it.isFile }

  private fun subjectFor(
    result: PreviewResult,
    png: File?,
    nodes: List<AccessibilityNode>?,
    source: String?,
    kind: GuidelineSubjectKind?,
    longCapture: File? = null,
    a11y: Boolean = false,
  ): PreviewSubject? {
    png ?: return null
    val bytes = png.readBytes()
    return PreviewSubject(
      previewId = result.id,
      label = result.functionName,
      // The manifest entry's own answer; a preview it does not list is classified as before.
      surface =
        surfaceOverride
          ?: kind?.surface
          ?: if (result.params.device != null) GuidelineSurfaces.SCREEN
          else GuidelineSurfaces.COMPONENT,
      profile = profileOverride ?: kind?.profile,
      // The identity a handoff run gives the same render ([HandoffInputs.renderHash]), so a PR's
      // check can be answered from this run's cache when nothing about the preview changed.
      renderHash = HandoffInputs.renderHash(result.sha256 ?: sha256(bytes), longCapture, a11y),
      pictures =
        listOf(
          SubjectPicture(
            KIND_DEVICE,
            bytes,
            result.params.widthDp ?: 0,
            result.params.heightDp ?: 0,
            // As in handoff mode: a capture scrolled to its END has scrolled the time text away.
            description =
              HandoffInputs.describeCapture(
                result.params.widthDp ?: 0,
                result.params.heightDp ?: 0,
                result.captures.firstOrNull()?.scroll?.mode,
              ),
          )
        ),
      nodes = nodes.orEmpty().mapIndexedNotNull { index, node -> node.toPreviewNode(index) },
      source = source,
    )
  }

  private fun readNodes(file: File): Map<String, List<AccessibilityNode>> {
    if (!file.isFile) return emptyMap()
    return runCatching {
        REPORT_JSON.decodeFromString(AccessibilityReport.serializer(), file.readText())
          .entries
          .associate { it.previewId to it.nodes }
      }
      .getOrDefault(emptyMap())
  }

  /** Writes `<render>.guidelines.png` beside each render with findings. */
  private fun annotateAll(
    results: List<PreviewGuidelineResult>,
    subjects: List<PreviewSubject>,
    nodes: Map<String, List<AccessibilityNode>>,
    renders: Map<String, File?>,
  ) {
    results.forEach { result ->
      val subject = subjects.firstOrNull { it.previewId == result.previewId } ?: return@forEach
      val failures = result.failures()
      if (failures.isEmpty()) return@forEach
      val picture = subject.pictures.firstOrNull() ?: return@forEach
      val previewNodes =
        nodes[result.previewId].orEmpty().mapIndexedNotNull { i, n -> n.toPreviewNode(i) }
      // Only the regions drawn on this picture: one on the long screenshot is in its coordinates.
      val onPicture = failures.map { verdict ->
        verdict
          .newBuilder()
          .apply {
            regions =
              verdict.regions.filter { it.pictureKind == null || it.pictureKind == picture.kind }
          }
          .build()
      }
      val out = GuidelineAnnotator.annotate(picture.png, previewNodes, onPicture, result.previewId)
      val render = renders[result.previewId] ?: return@forEach
      render.resolveSibling(render.nameWithoutExtension + ".guidelines.png").writeBytes(out)
    }
  }

  /** Whether a finding at or above the `--fail-on` severity was reported. */
  private fun tripped(
    results: List<PreviewGuidelineResult>,
    guidelines: CatalogGuidelinesV1,
  ): Boolean {
    val threshold = failOn ?: return false
    val severity = guidelines.rules.associate { it.id to it.severity }
    return results.any { result ->
      result.failures().any { verdict: GuidelineVerdictV1 ->
        when (threshold) {
          "warning",
          "warnings" -> severity[verdict.ruleId] == "warning"
          "info" -> true
          else -> false
        }
      }
    }
  }

  companion object {
    const val KEY_ENV: String = "COMPOSE_PREVIEW_OPENROUTER_KEY"
    /** The `--reasoning-effort` that sends none: the model reasons as it does by default. */
    private const val REASONING_DEFAULT = "default"
    /** The jev checker's follow-up rounds unless `--rounds` says otherwise. */
    private const val JEV_ROUNDS = 3
    private const val KIND_DEVICE = "device"
    internal val REPORT_JSON = Json {
      ignoreUnknownKeys = true
      explicitNulls = false
      prettyPrint = true
    }

    private fun sha256(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
  }
}

/**
 * One module's results, as `build/compose-previews/guidelines.json` holds them, with what the run
 * that wrote them could not do: [failedRequests] requests that came back as no verdicts, and the
 * engine's [problems] in its own words. A reader must not take a file whose previews were never
 * judged — every request failed, or no rule applied ([PreviewGuidelineResult.noRules]) — for a
 * clean pass; these say which it was. [requests] is null in a file written before they were
 * recorded.
 */
@Serializable
data class ModuleGuidelines(
  val module: String,
  val catalog: String,
  val model: String,
  val results: List<PreviewGuidelineResult>,
  val requests: Int? = null,
  val failedRequests: Int = 0,
  val problems: List<String> = emptyList(),
  /**
   * What the run spent, every request included: a reply that could not be used is paid for but
   * belongs to no result's record, so summing the records under-counts. Null in older files.
   */
  val costUsd: Double? = null,
  /**
   * The checker that answered when it was not the default vision model: `jev` for the EXPERIMENTAL
   * text-only checker. Null (and absent from the file) for a vision run.
   */
  val checker: String? = null,
  /**
   * Of the output tokens the run was billed for, how many were the model's reasoning: what to watch
   * when tuning `--reasoning-effort`. Null when none were reported, and in older files.
   */
  val reasoningTokens: Long? = null,
  /** The output tokens the run was billed for, reasoning included. Null as [reasoningTokens]. */
  val completionTokens: Long? = null,
) {
  companion object {
    fun of(
      module: String,
      catalog: String,
      model: String,
      run: GuidelineRunResult,
      checker: String? = null,
    ) =
      ModuleGuidelines(
        module,
        catalog,
        model,
        run.results,
        requests = run.requests,
        failedRequests = run.failedRequests,
        problems = run.problems,
        costUsd = run.costUsd,
        checker = checker,
        reasoningTokens = run.reasoningTokens.takeIf { it > 0 },
        completionTokens = run.completionTokens.takeIf { it > 0 },
      )
  }
}

/** The node a finding may cite, from an accessibility node: its stable ref, else its position. */
internal fun AccessibilityNode.toPreviewNode(index: Int): PreviewNode? =
  PreviewNode.parseBounds(ref ?: "n$index", boundsInScreen, role, label, states)

/** Prints a run's findings, a line per broken rule with its guide and the nodes it names. */
internal object GuidelinesReportRenderer {
  fun print(
    module: String,
    guidelines: CatalogGuidelinesV1,
    run: GuidelineRunResult,
    checker: GuidelineChecker? = GuidelineChecker.VISION,
  ) {
    val byId = guidelines.rules.associateBy { it.id }
    val ruleless = run.results.count { it.noRules != null }
    if (checker == GuidelineChecker.JEV) {
      println(
        "$module: checked by Jev (text-only, experimental): rules that need the picture are " +
          "left unchecked"
      )
    }
    println(
      "$module: ${run.results.size - ruleless} preview(s) checked against `${guidelines.catalog}` " +
        "guidelines v${guidelines.version} — ${run.requests} request(s), " +
        "$" +
        "%.4f".format(run.costUsd) +
        (run.results.count { it.fromCache }.takeIf { it > 0 }?.let { ", $it from cache" } ?: "") +
        (run.results.count { it.pending }.takeIf { it > 0 }?.let { ", $it pending" } ?: "") +
        (ruleless.takeIf { it > 0 }?.let { ", $it with no rule to ask" } ?: "") +
        (run.reasoningTokens
          .takeIf { it > 0 }
          ?.let { ", $it of ${run.completionTokens} output token(s) spent reasoning" } ?: "") +
        (run.results
          .sumOf { it.implicitPasses.size }
          .takeIf { it > 0 }
          ?.let { ", $it rule(s) passed implicitly (not among the reply's findings)" } ?: "")
    )
    run.results.forEach { result ->
      val failures = result.failures()
      if (failures.isEmpty() && result.unchecked.isEmpty()) return@forEach
      println("  ${result.previewId}")
      failures.forEach { verdict ->
        val rule = byId[verdict.ruleId]
        println(
          "    ${rule?.severity ?: "warning"} ${verdict.ruleId} (${(verdict.confidence * 100).toInt()}%)" +
            (if (verdict.nodeIds.isNotEmpty()) " on ${verdict.nodeIds.joinToString()}" else "") +
            ": ${verdict.reason}"
        )
        rule?.source?.let { println("      $it") }
      }
      if (result.unchecked.isNotEmpty()) {
        println("    unchecked: ${result.unchecked.joinToString()}")
      }
      result.record.servedModel?.let { served ->
        println(
          "    checked by $served" +
            (result.record.provider?.let { " on $it" } ?: "") +
            (result.record.routing?.let { " · routed by ${it.router}" } ?: "")
        )
      }
    }
    run.problems.forEach { System.err.println("  problem: $it") }
  }
}

/**
 * The guidelines command's exit status. A check that could not run is not a pass: previews it never
 * judged must not let CI through as clean, so an [incomplete] run is 2 even when what did come back
 * had findings. Findings at `--fail-on` are 1; a failed build with nothing else wrong is 2.
 */
internal fun guidelinesExitCode(incomplete: Boolean, failed: Boolean, buildOk: Boolean): Int =
  when {
    incomplete -> 2
    failed -> 1
    buildOk -> 0
    else -> 2
  }

/**
 * Writes [report] to `guidelines.json` under [buildDir]: every result the run returned, cached ones
 * included, so a publish carries the whole catalog and not only what this run asked about. A
 * [narrowed] run keeps the previous file's results for the previews it did not cover.
 */
internal fun writeGuidelinesReport(buildDir: File, report: ModuleGuidelines, narrowed: Boolean) {
  val file = buildDir.resolve("guidelines.json")
  val merged =
    if (narrowed && file.isFile) {
      val previous = runCatching {
        GuidelinesCommand.REPORT_JSON.decodeFromString(
          ModuleGuidelines.serializer(),
          file.readText(),
        )
      }
        .getOrNull()
      // Another checker's results are not kept: the report names one checker for all of them,
      // and a vision finding must not read as Jev's, nor the reverse.
      val kept =
        previous
          ?.takeIf { it.checker == report.checker }
          ?.results
          .orEmpty()
          .filter { old -> report.results.none { it.previewId == old.previewId } }
      report.copy(results = kept + report.results)
    } else report
  file.parentFile.mkdirs()
  file.writeText(
    GuidelinesCommand.REPORT_JSON.encodeToString(ModuleGuidelines.serializer(), merged)
  )
}
