package ee.schimke.composeai.cli

import ee.schimke.composeai.guidelines.CatalogGuidelinesLoader
import ee.schimke.composeai.guidelines.GuidelineAnnotator
import ee.schimke.composeai.guidelines.GuidelineBudget
import ee.schimke.composeai.guidelines.GuidelineEngine
import ee.schimke.composeai.guidelines.GuidelineEvidenceHost
import ee.schimke.composeai.guidelines.GuidelineModel
import ee.schimke.composeai.guidelines.GuidelineResultCache
import ee.schimke.composeai.guidelines.GuidelineRunOptions
import ee.schimke.composeai.guidelines.GuidelineRunResult
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
 * It renders like every report command, then runs the `a11y` fetch [A11yCommand] already drives,
 * because each preview's accessibility nodes are what a finding points at; then it hands the
 * renders, their sha256 and their nodes to the `:design-guidelines` engine, which batches them,
 * asks the model, fetches what a follow-up round asks for ([CliEvidenceHost]: nodes, source, and
 * renders at other settings through the module's render daemon), and caches each result by render
 * hash under `build/compose-previews/guidelines/`. The results land in
 * `build/compose-previews/guidelines.json`. Each preview's source goes to the model with its
 * render, so rules about code are judged on the code.
 *
 * `--previews-json` / `--renders-dir` run the same engine over handoff renders with no Gradle,
 * which is what a CI publish job holds.
 *
 * The OpenRouter key is read from `COMPOSE_PREVIEW_OPENROUTER_KEY`, never from the command line.
 */
class GuidelinesCommand(args: List<String>) : A11yCommand(args) {
  private val model: String = args.flagValue("--model") ?: OpenRouterClient.DEFAULT_MODEL
  private val maxCost: Double? = args.flagValue("--max-cost")?.toDoubleOrNull()
  private val rounds: Int = args.flagValue("--rounds")?.toIntOrNull() ?: 1
  private val triage: Boolean = "--no-triage" !in args
  private val annotate: Boolean = "--annotate" in args
  private val guidelinesLocation: String? = args.flagValue("--guidelines")
  private val surfaceOverride: String? = args.flagValue("--surface")
  private val previewsJson: String? = args.flagValue("--previews-json")
  private val rendersDir: String? = args.flagValue("--renders-dir")
  private val a11yJson: String? = args.flagValue("--a11y-json")
  private val sourceRoot: String? = args.flagValue("--source-root")

  override fun run() {
    val key = System.getenv(KEY_ENV)?.trim()?.takeIf { it.isNotEmpty() }
    if (key == null) {
      System.err.println(
        "guidelines: set $KEY_ENV to an OpenRouter key (openrouter.ai, Settings, then Keys). " +
          "The key is read from the environment only, never from the command line."
      )
      exitProcess(2)
    }
    val client = OpenRouterClient(key)
    if (previewsJson != null || rendersDir != null) exitProcess(runHandoff(client))

    val raw =
      renderModules(
        silenceStdout = jsonOutput,
        gradleArguments = gradleArgsWithForce(),
        scopeToPreviewRequest = true,
      )
    // The accessibility nodes every finding points at: the same daemon fetch `a11y` runs.
    produceAdditionalDataProducts(
      dataProductRequests(PreviewResultBuilder.readAllManifests(raw.modules))
    )
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
      val nodes = readNodes(buildDir.resolve("accessibility.json"))
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
      val subjects = moduleResults.mapNotNull { result ->
        subjectFor(result, renders[result.id], nodes[result.id], sources(result.id))
      }
      // What a follow-up round may ask for: this run's nodes and sources, and renders at other
      // settings through the module's render daemon.
      val host =
        CliEvidenceHost(
          projectDir = projectDir,
          moduleName = manifest?.second?.module ?: module,
          nodes = nodes,
          sources = sources,
        )
      val run =
        engine(
            client,
            buildDir,
            guidelines,
            guidelinesLocation ?: "${buildDir.path}/" + CatalogGuidelinesV1.FILE_NAME,
            host,
          )
          .run(guidelines, subjects)
      writeReport(
        buildDir,
        ModuleGuidelines(module, guidelines.catalog, model, run.results),
        narrowed,
      )
      if (annotate) annotateAll(run.results, subjects, nodes, renders)
      reports += ModuleGuidelines(module, guidelines.catalog, model, run.results)
      failed = failed || tripped(run.results, guidelines)
      if (!jsonOutput) GuidelinesReportRenderer.print(module, guidelines, run)
    }
    if (jsonOutput)
      println(REPORT_JSON.encodeToString(ListSerializer(ModuleGuidelines.serializer()), reports))
    exitProcess(
      when {
        failOn != null && failOn !in setOf("warning", "warnings", "info", "none") -> {
          System.err.println("Unknown --fail-on value: $failOn (expected warning|info|none)")
          EXIT_UNKNOWN_FAIL_ON
        }
        failed -> 1
        raw.buildOk -> 0
        else -> 2
      }
    )
  }

  /**
   * The engine over [client], caching under [buildDir], fetching follow-up evidence from [host].
   */
  private fun engine(
    client: GuidelineModel,
    buildDir: File,
    guidelines: CatalogGuidelinesV1,
    rulesSource: String,
    host: GuidelineEvidenceHost = GuidelineEvidenceHost.None,
  ): GuidelineEngine =
    GuidelineEngine(
        model = client,
        host = host,
        cache = GuidelineResultCache(buildDir.resolve("guidelines")),
        options =
          GuidelineRunOptions(
            model = model,
            budget = GuidelineBudget(),
            maxRounds = rounds,
            triage = triage,
            maxCostUsd = maxCost,
            rulesSource = rulesSource,
            ranBy = System.getenv("USER")?.let { "cli:$it" },
          ),
      )
      .also { if (guidelines.rules.isEmpty()) System.err.println("guidelines: no rules to ask") }

  /**
   * Handoff mode: no Gradle, no daemon — what a CI publish job holds. Renders come from
   * `--renders-dir` (every PNG in it) or from `--previews-json`: either a flat list of ids, or the
   * module's real `previews.json`, whose captures name each render and whose `sourceFile` and
   * `bodyLine` give each preview's source under `--source-root` (the module directory).
   * `--a11y-json` (the a11y pipeline's `accessibility.json`) gives each preview's nodes, so
   * findings cite node ids and `--annotate` can outline them. A follow-up round can ask for nothing
   * more: the host lists no fetchable evidence.
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
      )
    val outDir = File(rendersDir ?: previewsJson?.let { File(it).absoluteFile.parent } ?: ".")
    val run = engine(client, outDir, guidelines, location).run(guidelines, inputs.subjects)
    val report = ModuleGuidelines("handoff", guidelines.catalog, model, run.results)
    outDir
      .resolve("guidelines.json")
      .writeText(REPORT_JSON.encodeToString(ModuleGuidelines.serializer(), report))
    if (annotate) {
      annotateAll(run.results, inputs.subjects, inputs.nodes, inputs.renders)
    }
    if (jsonOutput) println(REPORT_JSON.encodeToString(ModuleGuidelines.serializer(), report))
    else GuidelinesReportRenderer.print("handoff", guidelines, run)
    return if (tripped(run.results, guidelines)) 1 else 0
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
  ): PreviewSubject? {
    png ?: return null
    val bytes = png.readBytes()
    return PreviewSubject(
      previewId = result.id,
      label = result.functionName,
      surface =
        surfaceOverride
          ?: if (result.params.device != null) GuidelineSurfaces.SCREEN
          else GuidelineSurfaces.COMPONENT,
      renderHash = result.sha256 ?: sha256(bytes),
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

  private fun writeReport(buildDir: File, report: ModuleGuidelines, narrowed: Boolean) {
    val file = buildDir.resolve("guidelines.json")
    val merged =
      if (narrowed && file.isFile) {
        val previous = runCatching {
          REPORT_JSON.decodeFromString(ModuleGuidelines.serializer(), file.readText())
        }
          .getOrNull()
        val kept =
          previous?.results.orEmpty().filter { old ->
            report.results.none { it.previewId == old.previewId }
          }
        report.copy(results = kept + report.results)
      } else report
    file.parentFile.mkdirs()
    file.writeText(REPORT_JSON.encodeToString(ModuleGuidelines.serializer(), merged))
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
      val out = GuidelineAnnotator.annotate(picture.png, previewNodes, failures, result.previewId)
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
    private const val KIND_DEVICE = "device"
    private val REPORT_JSON = Json {
      ignoreUnknownKeys = true
      explicitNulls = false
      prettyPrint = true
    }

    private fun sha256(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
  }
}

/** One module's results, as `build/compose-previews/guidelines.json` holds them. */
@Serializable
data class ModuleGuidelines(
  val module: String,
  val catalog: String,
  val model: String,
  val results: List<PreviewGuidelineResult>,
)

/** The node a finding may cite, from an accessibility node: its stable ref, else its position. */
internal fun AccessibilityNode.toPreviewNode(index: Int): PreviewNode? =
  PreviewNode.parseBounds(ref ?: "n$index", boundsInScreen, role, label)

/** Prints a run's findings, a line per broken rule with its guide and the nodes it names. */
internal object GuidelinesReportRenderer {
  fun print(module: String, guidelines: CatalogGuidelinesV1, run: GuidelineRunResult) {
    val byId = guidelines.rules.associateBy { it.id }
    println(
      "$module: ${run.results.size} preview(s) checked against `${guidelines.catalog}` " +
        "guidelines v${guidelines.version} — ${run.requests} request(s), " +
        "$" +
        "%.4f".format(run.costUsd) +
        (run.results.count { it.fromCache }.takeIf { it > 0 }?.let { ", $it from cache" } ?: "")
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
