package ee.schimke.composeai.cli

import ee.schimke.composeai.guidelines.CatalogGuidelinesLoader
import ee.schimke.composeai.guidelines.DeferredReplyModel
import ee.schimke.composeai.guidelines.GuidelineAnnotator
import ee.schimke.composeai.guidelines.GuidelineBudget
import ee.schimke.composeai.guidelines.GuidelineChecker
import ee.schimke.composeai.guidelines.GuidelineCredentials
import ee.schimke.composeai.guidelines.GuidelineEngine
import ee.schimke.composeai.guidelines.GuidelineEvidenceHost
import ee.schimke.composeai.guidelines.GuidelineModel
import ee.schimke.composeai.guidelines.GuidelinePlan
import ee.schimke.composeai.guidelines.GuidelinePrice
import ee.schimke.composeai.guidelines.GuidelineResultCache
import ee.schimke.composeai.guidelines.GuidelineRetry
import ee.schimke.composeai.guidelines.GuidelineRunOptions
import ee.schimke.composeai.guidelines.GuidelineRunResult
import ee.schimke.composeai.guidelines.GuidelineSubjectKind
import ee.schimke.composeai.guidelines.GuidelineSurfaces
import ee.schimke.composeai.guidelines.OpenAiCompatibleClient
import ee.schimke.composeai.guidelines.OpenRouterClient
import ee.schimke.composeai.guidelines.OpenRouterKeyStatus
import ee.schimke.composeai.guidelines.OpenRouterModels
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
 * Renders like every report command, then hands renders, sha256s and sources to the
 * `:design-guidelines` engine, which batches them, asks the model, serves follow-up evidence
 * ([CliEvidenceHost]: accessibility data via the [A11yCommand] daemon fetch, renders at other
 * settings), and caches results by render hash under `build/compose-previews/guidelines/`. Results
 * land in `build/compose-previews/guidelines.json`.
 *
 * `--previews-json` / `--renders-dir` run the engine over handoff renders with no Gradle (CI
 * publish).
 *
 * The OpenRouter key comes from `COMPOSE_PREVIEW_OPENROUTER_KEY`, else from the user's credentials
 * file (`guidelines key set`, [GuidelineCredentials]); never from the command line. The variable is
 * withheld from the Gradle daemon's environment and from render daemons.
 */
class GuidelinesCommand(args: List<String>, private val review: Boolean = false) :
  A11yCommand(args) {
  /**
   * Who judges ([GuidelinesBackend]): `openrouter` (the default for `guidelines`) asks a model with
   * the user's key; `prompt` writes each request out for the agent running the command, and reads
   * its replies on the next run ([DeferredReplyModel]); `openai` asks an OpenAI-compatible endpoint
   * at `--base-url`; `auto` (the default for `review`) picks one in [run].
   */
  private val requestedBackend: String? = args.flagValue("--backend")
  private var backend: String =
    requestedBackend ?: if (review) GuidelinesBackend.AUTO else GuidelinesBackend.OPENROUTER
  private val prompted: Boolean
    get() = backend == GuidelinesBackend.PROMPT

  /** `--backend openai`'s endpoint, version path included (`http://localhost:11434/v1`). */
  private val baseUrl: String? = args.flagValue("--base-url")

  private val modelFlag: String? = args.flagValue("--model")
  private val model: String
    get() =
      modelFlag
        ?: when (backend) {
          GuidelinesBackend.PROMPT -> DeferredReplyModel.DEFAULT_MODEL
          else -> OpenRouterClient.DEFAULT_MODEL
        }

  private val maxCostFlag: Double? = args.flagValue("--max-cost")?.toDoubleOrNull()
  /**
   * `--max-variants <n|size-class>`: ask n sizes of each preview, the largest and smallest first
   * ([GuidelineRunOptions.maxVariants]), or the largest in each width class
   * ([GuidelineRunOptions.variantsPerSizeClass]); unset asks every size.
   */
  private val maxVariantsFlag: String? = args.flagValue("--max-variants")
  private val maxVariants: Int? = maxVariantsFlag?.toIntOrNull()?.takeIf { it > 0 }
  private val variantsPerSizeClass: Boolean = maxVariantsFlag == SIZE_CLASS
  /**
   * `review` caps a provider run unless told otherwise; `guidelines` keeps its uncapped default.
   */
  private val maxCost: Double?
    get() =
      maxCostFlag
        ?: REVIEW_DEFAULT_MAX_COST.takeIf { review && backend == GuidelinesBackend.OPENROUTER }

  private val roundsFlag: Int? = args.flagValue("--rounds")?.toIntOrNull()
  /** Follow-up rounds: 1 for the vision model, 3 for the jev checker, which starts minimal. */
  private val rounds: Int
    get() = roundsFlag ?: if (checker == GuidelineChecker.JEV) JEV_ROUNDS else 1

  /**
   * Total time one model request may take, in seconds; an overrun is abandoned and the engine asks
   * about fewer previews at once.
   */
  private val requestTimeoutSeconds: Long? =
    args.flagValue("--request-timeout")?.toLongOrNull()?.takeIf { it > 0 }
  /**
   * How long a streamed request may go without a token (keep-alives don't count) before it is
   * cancelled, which stops the provider's bill where possible.
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
  /**
   * `--estimate`: say what a run would ask and what it is predicted to cost
   * ([GuidelineEngine.plan]), and stop. Asks no model, so it needs no key.
   */
  private val estimateOnly: Boolean = "--estimate" in args

  /**
   * What the answering model costs on OpenRouter, so a run predicts each request before asking it
   * and the cap reserves that ([GuidelineRunOptions.price]); null on another backend or when the
   * model list cannot be read, and the cap falls back to the dearest request so far.
   */
  private val price: GuidelinePrice? by lazy {
    // The Jev checker caps its own requests, which a price does not predict.
    if (backend != GuidelinesBackend.OPENROUTER || checker == GuidelineChecker.JEV) return@lazy null
    // Not [reportModel]: that reads [runOptions], which reads this.
    val answering =
      GuidelineRunOptions(model = model).withChecker(checker ?: GuidelineChecker.VISION)
    runCatching { GuidelinePrice.of(OpenRouterModels.fetch(), answering.answeringModel) }
      .onFailure {
        System.err.println("guidelines: no price to predict costs with (${it.message})")
      }
      .getOrNull()
  }
  private val guidelinesLocation: String? = args.flagValue("--guidelines")
  private val surfaceOverride: String? = args.flagValue("--surface")
  private val profileOverride: String? = args.flagValue("--profile")
  private val previewsJson: String? = args.flagValue("--previews-json")
  private val rendersDir: String? = args.flagValue("--renders-dir")
  private val a11yJson: String? = args.flagValue("--a11y-json")
  private val sourceRoot: String? = args.flagValue("--source-root")
  /**
   * `--checker vision|jev`. `jev` is EXPERIMENTAL: text-only, leaving rules that need the picture
   * unchecked. Null for any other value.
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
        // Triage asks OpenRouter's decisions API, which no other backend has.
        triage = triage && backend == GuidelinesBackend.OPENROUTER,
        maxCostUsd = maxCost,
        rulesSource = rulesSource,
        ranBy = System.getenv("USER")?.let { "cli:$it" },
      )
      .withConcurrency(concurrency)
      .withChecker(checker ?: GuidelineChecker.VISION)
      // The cache's half of the effort the client sends: a verdict at one is not reused at another.
      .withReasoningEffort(reasoningEffort)
      // An agent's unreadable reply is its to fix: asking again would read the same file, and
      // halves would be new requests for it to answer.
      .let { if (prompted) it.withRetry(GuidelineRetry(maxAttempts = 1, split = false)) else it }
      // Who answered, beyond the model name: a verdict from one backend or endpoint never answers
      // for another that happens to share a --model.
      .withJudge(judge)
      .withPrice(price)
      .withMaxVariants(maxVariants)
      .withVariantsPerSizeClass(variantsPerSizeClass)

  /** [GuidelineRunOptions.judge] for this run's backend; null for OpenRouter, as before. */
  private val judge: String?
    get() =
      when (backend) {
        GuidelinesBackend.PROMPT -> GuidelinesBackend.PROMPT
        GuidelinesBackend.OPENAI ->
          baseUrl
            ?.let { runCatching { GuidelineCredentials.endpointProvider(it) }.getOrNull() }
            ?.let { "openai:" + it.removePrefix("endpoint:") }
        else -> null
      }

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
    val command = if (review) "review" else "guidelines"
    if (backend !in GuidelinesBackend.ALL) {
      System.err.println(
        "$command: unknown --backend $backend (expected ${GuidelinesBackend.ALL.joinToString()})"
      )
      exitProcess(2)
    }
    val handoff = previewsJson != null || rendersDir != null
    if (backend == GuidelinesBackend.AUTO) {
      val key = GuidelineCredentials.openRouter()
      val choice =
        GuidelinesBackend.choose(
          requestedBackend,
          review,
          System::getenv,
          key,
          trusted =
            key?.stored == true && GuidelineCredentials.isTrusted(File(key.source), project()),
          terminal = System.console() != null,
        )
      backend = choice.backend
      System.err.println("$command: backend ${choice.backend} (${choice.reason})")
    }
    // Before a client, a price lookup or a guidelines URL is touched.
    refuseRemoteGuidelinesBackend(backend, baseUrl)
    if (backend != GuidelinesBackend.OPENROUTER && checker == GuidelineChecker.JEV) {
      System.err.println(
        "$command: --checker jev asks OpenRouter's decisions API; it needs " +
          "--backend ${GuidelinesBackend.OPENROUTER}"
      )
      exitProcess(2)
    }
    if (prompted && handoff) {
      System.err.println(
        "$command: --backend ${GuidelinesBackend.PROMPT} runs in a Gradle project; handoff mode " +
          "needs a model backend"
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
    val client: GuidelineModel? =
      when {
        // A plan asks nothing, so it needs no key.
        estimateOnly -> NoModel
        backend == GuidelinesBackend.OPENROUTER -> openRouterClient()
        backend == GuidelinesBackend.OPENAI -> openAiClient()
        else -> null
      }
    if (handoff) exitProcess(runHandoff(client!!))

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
    val waiting = mutableListOf<File>()
    var failed = false
    var incomplete = false
    for ((module, moduleResults) in results.groupBy { it.module }) {
      val projectDir =
        manifests.firstOrNull { it.first.gradlePath == module }?.first?.projectDir
          ?: moduleResults.firstOrNull()?.projectDirectory?.let(::File)
          ?: continue
      val buildDir = projectDir.resolve("build/compose-previews")
      val loaded =
        guidelinesLocation?.let { loadGuidelines(it) }
          ?: loadGuidelines(buildDir.resolve(CatalogGuidelinesV1.FILE_NAME).path)
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
      if (estimateOnly) {
        val rulesSource = guidelinesLocation ?: "${buildDir.path}/" + CatalogGuidelinesV1.FILE_NAME
        printPlan(
          module,
          engine(client!!, cache, guidelines, rulesSource, host).plan(guidelines, subjects),
        )
        continue
      }
      // Prompt mode: requests are written under the module for the agent, answers read back.
      val deferred =
        if (prompted) DeferredReplyModel(buildDir.resolve(REVIEW_DIR), model, rerunCommand())
        else null
      val run =
        engine(
            deferred ?: client!!,
            cache,
            guidelines,
            guidelinesLocation ?: "${buildDir.path}/" + CatalogGuidelinesV1.FILE_NAME,
            host,
          )
          .run(guidelines, subjects)
      spentWithKey(run)
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
      deferred?.let { prompts ->
        waiting += prompts.waiting
        // A run over the whole selection keeps only the requests the current previews make.
        if (!narrowed && subjects.size == moduleResults.size) prompts.prune()
      }
      if (!jsonOutput) GuidelinesReportRenderer.print(module, guidelines, run, checker)
    }
    if (jsonOutput)
      println(REPORT_JSON.encodeToString(ListSerializer(ModuleGuidelines.serializer()), reports))
    compare(reports)
    if (waiting.isNotEmpty()) printWaiting(waiting)
    exitProcess(
      when {
        waiting.isNotEmpty() && !incomplete -> EXIT_WAITING_FOR_REPLIES
        failOn != null && failOn !in setOf("warning", "warnings", "info", "none") -> {
          System.err.println("Unknown --fail-on value: $failOn (expected warning|info|none)")
          EXIT_UNKNOWN_FAIL_ON
        }
        else -> guidelinesExitCode(incomplete, failed, raw.buildOk)
      }
    )
  }

  /**
   * Tells whoever ran the command (in prompt mode, an agent) which requests wait for a reply: on
   * stdout unless it carries `--json`, so the report stays parseable.
   */
  private fun printWaiting(waiting: List<File>) {
    val out = if (jsonOutput) System.err else System.out
    out.println()
    out.println(
      "${waiting.size} review request(s) wait for a reply. For each, read request.md, look at " +
        "every picture it lists, and write only the JSON reply it asks for to reply.json beside " +
        "it (a subagent can take one each). Then run `${rerunCommand()}` again: it reads the " +
        "replies, and asks again where a reply needs more evidence."
    )
    waiting.forEach { out.println("  ${it.path}") }
  }

  /** This command line again, for a request file to name. */
  private fun rerunCommand(): String =
    (listOf("compose-preview", if (review) "review" else "guidelines") + args).joinToString(" ") {
      if (it.matches(Regex("[A-Za-z0-9_./:=@,+%-]+"))) it else "'" + it.replace("'", "'\\''") + "'"
    }

  /**
   * The project a stored key must be trusted for: the source root in handoff mode, else Gradle's.
   */
  private fun project(): File {
    val handoff = previewsJson != null || rendersDir != null
    return (if (handoff) sourceRoot?.let(::File) else findProjectRoot()) ?: File(".").absoluteFile
  }

  /**
   * Exits unless the stored key in [resolved] may be spent on this project. [envVar] is the
   * variable that would supply the key instead, which the refusal names.
   */
  private fun requireTrusted(
    resolved: GuidelineCredentials.Resolved,
    envVar: String = GuidelineCredentials.ENV,
  ) {
    if (!resolved.stored) return
    if (!GuidelinesKeyCommand.confirmTrust(File(resolved.source), project(), envVar)) {
      exitProcess(2)
    }
  }

  /**
   * The client for an OpenAI-compatible endpoint at `--base-url`, with the key from
   * `COMPOSE_PREVIEW_MODEL_KEY` or the one stored for that endpoint (`guidelines key set --for`); a
   * loopback server may take none. Exits when the endpoint or the model is missing.
   */
  private fun openAiClient(): OpenAiCompatibleClient {
    val url =
      baseUrl
        ?: run {
          System.err.println(
            "guidelines: --backend ${GuidelinesBackend.OPENAI} needs --base-url, the endpoint " +
              "before /chat/completions (https://api.openai.com/v1, http://localhost:11434/v1)"
          )
          exitProcess(2)
        }
    if (modelFlag == null) {
      System.err.println(
        "guidelines: --backend ${GuidelinesBackend.OPENAI} needs --model, the endpoint's own " +
          "name for a model that reads pictures"
      )
      exitProcess(2)
    }
    val provider = runCatching {
      GuidelineCredentials.endpointProvider(url)
    }
      .getOrElse {
        System.err.println("guidelines: --base-url $url is not an http(s) URL")
        exitProcess(2)
      }
    val resolved =
      GuidelineCredentials.resolve(provider, GuidelineCredentials.MODEL_ENV) {
        System.err.println("guidelines: $it")
      }
    if (resolved == null && !OpenAiCompatibleClient.isLoopback(url)) {
      System.err.println(
        "guidelines: no key for $url. Store one with `compose-preview guidelines key set --for " +
          "$url`, or set ${GuidelineCredentials.MODEL_ENV}. It is never read from the command line."
      )
      exitProcess(2)
    }
    resolved?.let { requireTrusted(it, GuidelineCredentials.MODEL_ENV) }
    if (maxCostFlag != null) {
      System.err.println(
        "guidelines: an OpenAI-compatible endpoint reports no cost, so --max-cost cannot stop " +
          "this run; narrow the selection instead"
      )
    }
    return OpenAiCompatibleClient(
      url,
      resolved?.key,
      OpenRouterClient.httpClient(
        requestTimeoutSeconds?.let { java.time.Duration.ofSeconds(it) }
          ?: OpenRouterClient.DEFAULT_REQUEST_TIMEOUT
      ),
    )
  }

  /**
   * The OpenRouter client, with the key from the environment or the credentials file; exits when
   * there is none, or when a stored key is not trusted for this project.
   */
  private fun openRouterClient(): OpenRouterClient {
    val resolved =
      GuidelineCredentials.openRouter { System.err.println("guidelines: $it") }
        ?: run {
          System.err.println(
            "guidelines: no OpenRouter key (openrouter.ai, Settings, then Keys). Store one with " +
              "`compose-preview guidelines key set`, or set $KEY_ENV. It is never read from the " +
              "command line."
          )
          exitProcess(2)
        }
    // A stored key is spent only on a project its owner trusted, asked before anything is built.
    requireTrusted(resolved)
    val client =
      OpenRouterClient(
          resolved.key,
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
    return client
  }

  /**
   * [ids]' accessibility data, fetched through the render daemon as `a11y` does and merged into the
   * module's `accessibility.json`. Returns only what this fetch produced (earlier entries may be
   * stale).
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
    // Drop the asked-for entries first so what's read back is from this fetch or nothing.
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
        options = runOptions(rulesSource).withKeyStatus(keyLeft(client)),
      )
      .also { if (guidelines.rules.isEmpty()) System.err.println("guidelines: no rules to ask") }

  /** What the OpenRouter key had left when first asked, read once per command. */
  private val keyStatusAtStart: OpenRouterKeyStatus? by lazy {
    openRouterKeyClient?.keyStatus()?.also { status ->
      if (status.remainingUsd != null) System.err.println("guidelines: ${status.describe()}")
    }
  }
  private var openRouterKeyClient: OpenRouterClient? = null
  /** What this command's runs have spent with the key so far, across modules. */
  private var keySpent = 0.0
  /** Set once a run met the key's spending limit: later modules ask nothing. */
  private var keyLimitReached = false

  /**
   * What the key [client] asks with has left for the next run: what it had at the start, less what
   * this command spent since; null for any other backend, or when OpenRouter does not say.
   */
  private fun keyLeft(client: GuidelineModel): OpenRouterKeyStatus? {
    openRouterKeyClient = client as? OpenRouterClient ?: return null
    val status = keyStatusAtStart ?: return null
    if (keyLimitReached) return status.copy(remainingUsd = 0.0)
    val remaining = status.remainingUsd ?: return status
    return status.copy(remainingUsd = (remaining - keySpent).coerceAtLeast(0.0))
  }

  /** Counts [run] against the key, so the next module's run is capped at what is left. */
  private fun spentWithKey(run: GuidelineRunResult) {
    keySpent += run.costUsd
    if (run.keyLimitReached) keyLimitReached = true
  }

  /**
   * Handoff mode: no Gradle or daemon. Renders come from `--renders-dir` or `--previews-json` (an
   * id list, or a real `previews.json` whose `sourceFile` / `bodyLine` locate sources under
   * `--source-root`). `--a11y-json` supplies nodes for citations and `--annotate`. Follow-up rounds
   * can only use captures staged beside a render ([HandoffEvidenceHost]).
   */
  private fun runHandoff(client: GuidelineModel): Int {
    val location = guidelinesLocation
    if (location == null) {
      System.err.println("guidelines: handoff mode needs --guidelines <file-or-url>")
      return 2
    }
    val guidelines =
      loadGuidelines(location).let { loaded ->
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
    if (estimateOnly) {
      val cache = GuidelineResultCache(outDir.resolve("guidelines"))
      printPlan(
        "handoff",
        engine(client, cache, guidelines, location).plan(guidelines, inputs.subjects),
      )
      return 0
    }
    val run =
      engine(client, GuidelineResultCache(outDir.resolve("guidelines")), guidelines, location)
        .run(guidelines, inputs.subjects)
    spentWithKey(run)
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

  /** `--estimate`'s answer for [module]: what a run would ask, and what it is predicted to cost. */
  private fun printPlan(module: String, plan: GuidelinePlan) {
    val cap = maxCost
    val cost =
      plan.predictedCostUsd?.let { predicted ->
        ", predicted about $" +
          "%.4f".format(predicted) +
          (cap?.let { limit ->
            val reached = plan.predictedChecked ?: plan.previews
            if (reached >= plan.previews)
              "; the $" + "%.2f".format(limit) + " cap covers all of them"
            else "; the $" + "%.2f".format(limit) + " cap is expected to reach $reached of them"
          } ?: "")
      }
        ?: if (checker == GuidelineChecker.JEV)
          " (vision batching; the Jev checker asks per subject and predicts no cost)"
        else ", no price to predict a cost with"
    println(
      "$module: ${plan.previews} preview(s) to ask in ${plan.requests} request(s)" +
        (plan.cached.takeIf { it > 0 }?.let { ", $it from cache" } ?: "") +
        cost
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
   * Whether [run] failed to judge previews it was asked to (errors, refusals, unreadable replies);
   * said on stderr.
   */
  private fun incomplete(module: String, run: GuidelineRunResult): Boolean {
    if (run.failedRequests == 0 && run.failedEvidence == 0) return false
    if (run.failedRequests > 0) {
      System.err.println(
        "guidelines: $module: ${run.failedRequests} model request(s) failed; the previews in " +
          "them were not checked."
      )
    }
    if (run.failedEvidence > 0) {
      System.err.println(
        "guidelines: $module: ${run.failedEvidence} evidence fetch(es) failed; the rules waiting " +
          "on them were not checked."
      )
    }
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
      // Same identity a handoff run uses ([HandoffInputs.renderHash]), so a PR check can reuse this
      // cache.
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
              result.captures.firstOrNull()?.scroll.let { scroll ->
                HandoffInputs.describeCapture(
                  result.params.widthDp ?: 0,
                  result.params.heightDp ?: 0,
                  scroll?.mode,
                  scroll?.let { ScrollLimit(it.maxScrollPx, it.atEnd, it.reachedPx, it.axis) },
                )
              },
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
    const val KEY_ENV: String = GuidelineCredentials.ENV
    /** `review`'s cost cap on OpenRouter when `--max-cost` names none, in US dollars. */
    const val REVIEW_DEFAULT_MAX_COST: Double = 0.25
    /** Where prompt mode writes requests and reads replies, under `build/compose-previews/`. */
    const val REVIEW_DIR: String = "review"
    /** Prompt mode left requests waiting for a reply: nothing failed, the review is not done. */
    const val EXIT_WAITING_FOR_REPLIES: Int = 3
    /** The `--reasoning-effort` that sends none: the model reasons as it does by default. */
    private const val REASONING_DEFAULT = "default"
    /** `--max-variants size-class`: one size of each preview per width class. */
    private const val SIZE_CLASS = "size-class"
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
 * One module's results as `build/compose-previews/guidelines.json` holds them, plus what the run
 * could not do ([failedRequests], the engine's [problems]), so an unjudged file isn't read as a
 * clean pass. [requests] is null in older files.
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
  /** Total spend, including unusable replies that belong to no result. Null in older files. */
  val costUsd: Double? = null,
  /**
   * The checker that answered when it was not the default vision model: `jev` for the EXPERIMENTAL
   * text-only checker. Null (and absent from the file) for a vision run.
   */
  val checker: String? = null,
  /** Evidence fetches that failed ([GuidelineRunResult.failedEvidence]); absent when none did. */
  val failedEvidence: Int = 0,
  /**
   * Of the output tokens the run was billed for, how many were the model's reasoning: what to watch
   * when tuning `--reasoning-effort`. Null when none were reported, and in older files.
   */
  val reasoningTokens: Long? = null,
  /** The output tokens the run was billed for, reasoning included. Null as [reasoningTokens]. */
  val completionTokens: Long? = null,
  /**
   * What the run's requests were predicted to cost before any was asked
   * ([GuidelineRunResult.predictedCostUsd]); null without a price, and in older files.
   */
  val predictedCostUsd: Double? = null,
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
        failedEvidence = run.failedEvidence,
        reasoningTokens = run.reasoningTokens.takeIf { it > 0 },
        completionTokens = run.completionTokens.takeIf { it > 0 },
        predictedCostUsd = run.predictedCostUsd,
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
    val otherVariants = run.results.count { it.variantOf != null }
    if (checker == GuidelineChecker.JEV) {
      println(
        "$module: checked by Jev (text-only, experimental): rules that need the picture are " +
          "left unchecked"
      )
    }
    println(
      "$module: ${run.results.size - ruleless - otherVariants} preview(s) checked against `${guidelines.catalog}` " +
        "guidelines v${guidelines.version} — ${run.requests} request(s), " +
        "$" +
        "%.4f".format(run.costUsd) +
        (run.predictedCostUsd?.let { " (predicted $" + "%.4f".format(it) + ")" } ?: "") +
        (run.results.count { it.fromCache }.takeIf { it > 0 }?.let { ", $it from cache" } ?: "") +
        (run.results.count { it.pending }.takeIf { it > 0 }?.let { ", $it pending" } ?: "") +
        (ruleless.takeIf { it > 0 }?.let { ", $it with no rule to ask" } ?: "") +
        (otherVariants.takeIf { it > 0 }?.let { ", $it more variant(s) not asked (--max-variants)" }
          ?: "") +
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
 * Exit status: an [incomplete] run is 2 (unjudged previews must not pass CI), findings at
 * `--fail-on` are 1, and a failed build with nothing else wrong is 2.
 */
internal fun guidelinesExitCode(incomplete: Boolean, failed: Boolean, buildOk: Boolean): Int =
  when {
    incomplete -> 2
    failed -> 1
    buildOk -> 0
    else -> 2
  }

/**
 * Write [report] to `guidelines.json` under [buildDir], including cached results so a publish
 * carries the whole catalog. A [narrowed] run keeps the previous file's other previews.
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

/** `--estimate`'s model: a plan asks nothing, so nothing here is ever called. */
private object NoModel : GuidelineModel {
  override fun complete(
    request: ee.schimke.composeai.guidelines.protocol.GuidelineRequestV1,
    model: String,
  ): ee.schimke.composeai.guidelines.ModelResponse = error("--estimate asks no model")
}

/**
 * Refuses, under [NetworkPolicy]'s switch, a guidelines backend that sends rendered previews off
 * this machine: OpenRouter, or an OpenAI-compatible endpoint that is not loopback. The prompt
 * backend (the calling agent answers) and a local model server stay usable.
 */
internal fun refuseRemoteGuidelinesBackend(
  backend: String,
  baseUrl: String?,
  disabled: Boolean = NetworkPolicy.disabled,
) {
  when (backend) {
    GuidelinesBackend.OPENROUTER ->
      NetworkPolicy.require(
        "guidelines --backend ${GuidelinesBackend.OPENROUTER} (sends renders to openrouter.ai)",
        "Use --backend ${GuidelinesBackend.PROMPT}, or --backend ${GuidelinesBackend.OPENAI} " +
          "with a loopback --base-url (a model server on this machine).",
        disabled = disabled,
      )
    GuidelinesBackend.OPENAI ->
      if (baseUrl != null) {
        NetworkPolicy.requireForUrl(
          baseUrl,
          "guidelines --backend ${GuidelinesBackend.OPENAI} at $baseUrl (sends renders to it)",
          "Use a loopback --base-url (a model server on this machine), or --backend " +
            "${GuidelinesBackend.PROMPT}.",
          disabled = disabled,
        )
      }
  }
}

/**
 * [CatalogGuidelinesLoader.load], refusing under [NetworkPolicy]'s switch a guidelines URL or a
 * file whose `includes` would be fetched, rather than reading a partial rule set.
 */
internal fun loadGuidelines(
  location: String,
  disabled: Boolean = NetworkPolicy.disabled,
): CatalogGuidelinesLoader.Loaded {
  if (!disabled) return CatalogGuidelinesLoader.load(location)
  // Every URL, loopback included: its `includes` are https packs fetched from wherever they live.
  if (location.startsWith("http://") || location.startsWith("https://")) {
    throw NetworkPolicy.refusal(
      "Reading guidelines from $location",
      "Download the guidelines file (and the packs it includes) and pass its path to --guidelines.",
    )
  }
  val file = File(location)
  if (!file.isFile) return CatalogGuidelinesLoader.Loaded(null)
  var refused: NetworkDisabledException? = null
  val loaded =
    CatalogGuidelinesLoader.parse(file.readText(), null) { url ->
      throw NetworkPolicy.refusal(
          "Fetching the guidelines pack $url that $location includes",
          "Inline the pack's rules into $location, or point --guidelines at a copy without " +
            "remote includes.",
        )
        .also { refused = it }
    }
  refused?.let { throw it }
  return loaded
}
