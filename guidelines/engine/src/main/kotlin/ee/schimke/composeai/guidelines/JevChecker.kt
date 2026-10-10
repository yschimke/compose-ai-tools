package ee.schimke.composeai.guidelines

import ee.schimke.composeai.guidelines.protocol.CatalogGuidelinesV1
import ee.schimke.composeai.guidelines.protocol.GuidelineEvidenceNeedV1
import ee.schimke.composeai.guidelines.protocol.GuidelineRecordV1
import ee.schimke.composeai.guidelines.protocol.GuidelineRuleV1
import ee.schimke.composeai.guidelines.protocol.GuidelineSubjectV1
import ee.schimke.composeai.guidelines.protocol.GuidelineVerdictV1
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Which model answers a run's rules ([GuidelineRunOptions.withChecker]).
 *
 * [VISION] is the default and the established check: a vision model sees each render and writes a
 * verdict per rule. [JEV] is EXPERIMENTAL: Jev, TypeSafe's decision model, decides each structural
 * rule from text alone — the preview's source, its accessibility nodes and the measured ATF checks
 * — and leaves every rule that needs the picture unchecked.
 */
public enum class GuidelineChecker(public val id: String) {
  /** A vision model judges the render, its source and its evidence (the default). */
  VISION("vision"),

  /**
   * EXPERIMENTAL. Jev decides each structural rule from text evidence only; visual rules, and any
   * rule Jev cannot tell from the text, are reported unchecked ([JevChecker.TEXT_ONLY_REASON]).
   */
  JEV("jev");

  public companion object {
    /** The checker named [value] (`vision` or `jev`), or null for anything else. */
    public fun parse(value: String?): GuidelineChecker? = entries.firstOrNull {
      it.id == value?.trim()?.lowercase()
    }
  }
}

/**
 * EXPERIMENTAL: the text-only checker. Each subject's structural rules are asked of Jev through
 * OpenRouter's decisions API (`POST /api/alpha/decisions`), one Choice question per rule —
 * `cannot_tell`, `not_applicable`, `fail` or `pass` — over one `state` holding that subject's
 * source, accessibility nodes and measured checks. The questions of one subject share a request
 * (they are answered in parallel against the same state, so a request per subject is what the API
 * is priced and limited for), and subjects are asked with bounded parallelism.
 *
 * Jev returns probabilities, never text, so a verdict's reason is short and written here; where a
 * subject has accessibility nodes, a second Choice per rule over the node ids lets a `fail` cite
 * the node it is about. Visual rules are never asked: they are recorded `needs_evidence` with
 * [TEXT_ONLY_REASON], which reports them unchecked, never passed — as is any rule Jev answers
 * `cannot_tell`, or answers below [MIN_PROBABILITY].
 *
 * Results are cached under [GuidelineRunOptions.cacheModel], which names the checker, so a vision
 * verdict and a Jev verdict never answer for one another.
 */
internal class JevChecker(
  private val model: GuidelineModel,
  private val host: GuidelineEvidenceHost,
  private val cache: GuidelineResultCache?,
  private val options: GuidelineRunOptions,
  private val clock: () -> Long,
) {
  /** How a retry waits; tests replace it. */
  var sleep: (Long) -> Unit = { Thread.sleep(it) }

  /** How many subjects are asked at once. */
  var parallelism: Int = DEFAULT_PARALLELISM

  private val jevModel: String = options.answeringModel
  private val cacheModel: String = options.cacheModel

  fun run(guidelines: CatalogGuidelinesV1, subjects: List<PreviewSubject>): GuidelineRunResult {
    val problems = mutableListOf<String>()
    val results = mutableListOf<PreviewGuidelineResult>()

    val askable = subjects.filter { subject ->
      val reason = guidelines.noRulesFor(subject) ?: return@filter true
      results +=
        result(guidelines, subject, emptyList(), emptyList(), null, 0.0).also {
          it.noRules = reason
        }
      false
    }
    results
      .filter { it.noRules != null }
      .groupBy { it.noRules!! }
      .forEach { (reason, skipped) ->
        problems += "${skipped.size} preview(s) were not checked: $reason"
      }

    val pending = askable.filter { subject ->
      val hit = cache?.get(subject, guidelines, cacheModel)
      if (hit != null) results += hit
      hit == null
    }
    val (unseen, stale) = pending.partition { cache?.checked(it.previewId) != true }
    val ordered = unseen + stale

    // The main evidence: fetched up front, for the subjects being asked only, in one prefetch.
    val prepared = withTextEvidence(ordered)

    val ledger = Ledger(options.maxCostUsd, options.retry.maxFailedAttempts)
    val outcomes: List<Outcome> =
      if (prepared.isEmpty()) emptyList()
      else {
        val pool =
          Executors.newFixedThreadPool(parallelism.coerceIn(1, MAX_PARALLELISM)) { runnable ->
            Thread(runnable, "jev-checker").apply { isDaemon = true }
          }
        try {
          prepared
            .map { subject ->
              pool.submit(
                Callable {
                  // One subject's error is its own failed request, not the run's.
                  runCatching { check(guidelines, subject, ledger) }
                    .getOrElse {
                      Outcome(subject, failure = "the jev checker failed: ${it.message}")
                    }
                }
              )
            }
            .map { it.get() }
        } finally {
          pool.shutdown()
          pool.awaitTermination(1, TimeUnit.MINUTES)
        }
      }

    var capped = 0
    var failedRequests = 0
    var textOnly = 0
    var cannotTell = 0
    val textOnlyPreviews = mutableSetOf<String>()
    outcomes.forEach { outcome ->
      val subject = outcome.subject
      val arrived = pending.first { it.previewId == subject.previewId }
      when {
        outcome.capped -> {
          capped++
          results += pendingResult(guidelines, subject)
        }
        outcome.failure != null -> {
          failedRequests++
          problems += "${subject.previewId}: ${outcome.failure}"
          results += pendingResult(guidelines, subject)
        }
        else -> {
          val asked = askedRules(guidelines, subject)
          val result =
            result(guidelines, subject, asked, outcome.verdicts, outcome.served, outcome.cost)
          results += result
          outcome.verdicts
            .filter { it.verdict == GuidelineVerdictV1.NEEDS_EVIDENCE }
            .forEach { verdict ->
              textOnlyPreviews += subject.previewId
              if (asked.firstOrNull { it.id == verdict.ruleId }?.kind == KIND_VISUAL) textOnly++
              else cannotTell++
            }
          // Keyed on the subject as the caller handed it in, as the vision engine does: the
          // evidence attached here is not on the next run's lookup.
          cache?.put(result, arrived, guidelines, cacheModel)
        }
      }
    }

    if (textOnly + cannotTell > 0) {
      problems +=
        "${textOnly + cannotTell} rule verdict(s) on ${textOnlyPreviews.size} preview(s) were " +
          "left unchecked: $TEXT_ONLY_REASON ($textOnly visual rule(s) never asked, " +
          "$cannotTell Jev could not tell from the text)"
    }
    val setRules = guidelines.setRules()
    if (setRules.isNotEmpty() && prepared.isNotEmpty()) {
      problems +=
        "${setRules.size} set-scoped rule(s) were not asked (${setRules.joinToString { it.id }}): " +
          "the jev checker judges each preview on its own"
    }
    if (ledger.invented > 0) {
      problems +=
        "${ledger.invented} answer(s) named a question their request did not ask and were dropped"
    }
    if (ledger.retried > 0) {
      problems +=
        "${ledger.retried} decisions request(s) were asked again after a failure that may pass"
    }
    if (capped > 0) {
      problems +=
        "the cost cap (\$${money(options.maxCostUsd ?: 0.0)}) was reached; $capped previews were " +
          "not checked (\$${money(ledger.spent)} spent)"
    }
    return GuidelineRunResult(results, ledger.spent, ledger.requests, problems, failedRequests)
  }

  /** The rules a subject is asked under this checker: the same list the vision engine asks. */
  private fun askedRules(guidelines: CatalogGuidelinesV1, subject: PreviewSubject) =
    guidelines.subjectRules(subject.surface, subject.profile, subject.pictures.isNotEmpty())

  /**
   * [subjects] with their accessibility data (nodes and measured checks) and source attached, from
   * the host, for those it offers them for. One prefetch for all of them, as a round does.
   */
  private fun withTextEvidence(subjects: List<PreviewSubject>): List<PreviewSubject> {
    val a11yNeed =
      GuidelineEvidenceNeedV1.Builder(PreviewGuidelineRequests.KIND_A11Y)
        .apply { reason = "jev checker: the text evidence it judges from" }
        .build()
    val wanted =
      subjects
        .filter { it.nodes.isEmpty() && it.checks.isEmpty() }
        .mapNotNull { subject ->
          val kind =
            servedKind(PreviewGuidelineRequests.KIND_A11Y, host.available(subject.previewId))
              ?: return@mapNotNull null
          subject.previewId to
            listOf(
              if (kind == a11yNeed.kind) a11yNeed
              else a11yNeed.newBuilder().apply { this.kind = kind }.build()
            )
        }
        .toMap()
    if (wanted.isNotEmpty()) host.prefetch(wanted)
    return subjects.map { subject ->
      var updated = subject
      if (subject.previewId in wanted) {
        host.nodes(subject.previewId)?.let { updated = updated.copy(nodes = it) }
        host.checks(subject.previewId)?.let { updated = updated.copy(checks = it) }
      }
      if (
        updated.source == null &&
          GuidelineEvidenceNeedV1.KIND_SOURCE in host.available(subject.previewId)
      ) {
        host.source(subject.previewId)?.let { updated = updated.copy(source = it) }
      }
      updated
    }
  }

  /** One subject's verdicts: its structural rules asked of Jev, its visual ones left unchecked. */
  private fun check(
    guidelines: CatalogGuidelinesV1,
    subject: PreviewSubject,
    ledger: Ledger,
  ): Outcome {
    val asked = askedRules(guidelines, subject)
    val (visual, textual) = asked.partition { it.kind == KIND_VISUAL }
    val verdicts = mutableListOf<GuidelineVerdictV1>()
    visual.forEach { verdicts += unchecked(subject, it, TEXT_ONLY_REASON) }
    if (textual.isEmpty()) return Outcome(subject, verdicts)

    var served: GuidelineServed? = null
    var cost = 0.0
    for (chunk in JevRuleRequests.chunks(guidelines, subject, textual)) {
      var reservation = ledger.reserve() ?: return Outcome(subject, capped = true)
      val body = JevRuleRequests.body(guidelines, subject, chunk, jevModel)
      var attempt = 0
      while (true) {
        attempt++
        val response = runCatching {
          model.decide(body)
        }
          .getOrElse {
            ledger.settle(reservation, 0.0, counted = false)
            return Outcome(subject, failure = "decisions request failed: ${it.message}")
          }
        val spent = GuidelineResponse.cost(response.body) ?: 0.0
        cost += spent
        ledger.settle(reservation, spent, counted = true)
        val failure: FailedRequest =
          if (response.status in 200..299) {
            val parsed = JevRuleRequests.read(response.body, subject, chunk)
            if (parsed != null) {
              ledger.invent(parsed.invented)
              verdicts += parsed.verdicts
              served = parsed.served
              break
            }
            FailedRequest(
              "the decisions reply answered none of its questions: ${response.body.take(200)}",
              FailureKind.UNUSABLE,
            )
          } else FailedRequest.of(response)
        val wait =
          response.retryAfterMillis
            ?: (options.retry.initialDelayMillis shl (attempt - 1).coerceAtMost(20)).coerceAtMost(
              options.retry.maxDelayMillis
            )
        val retry =
          failure.kind != FailureKind.FATAL &&
            failure.kind != FailureKind.TOO_LARGE &&
            attempt < options.retry.maxAttempts &&
            wait <= options.retry.maxDelayMillis &&
            ledger.mayRetry()
        if (!retry) {
          return Outcome(
            subject,
            failure = failure.problem + if (attempt > 1) " (after $attempt tries)" else "",
          )
        }
        ledger.retry()
        if (failure.kind != FailureKind.UNUSABLE) sleep(wait)
        reservation = ledger.reserve() ?: return Outcome(subject, capped = true)
      }
    }
    return Outcome(subject, verdicts, served, cost)
  }

  private fun result(
    guidelines: CatalogGuidelinesV1,
    subject: PreviewSubject,
    asked: List<GuidelineRuleV1>,
    verdicts: List<GuidelineVerdictV1>,
    served: GuidelineServed?,
    cost: Double,
  ): PreviewGuidelineResult =
    PreviewGuidelineResult(
      previewId = subject.previewId,
      renderHash = subject.renderHash,
      record =
        GuidelineRecordV1.Builder(
            revision = 0,
            model = jevModel,
            rulesVersion = guidelines.version,
            asked = asked.map { it.id },
            verdicts = verdicts,
          )
          .apply {
            previewId = subject.previewId
            subjects =
              listOf(
                GuidelineSubjectV1.Builder(subject.previewId, GuidelineSubjectV1.KIND_PREVIEW)
                  .apply {
                    renderHash = subject.renderHash
                    label = subject.label
                  }
                  .build()
              )
            ranBy = options.ranBy
            recordedAtEpochMillis = clock()
            servedModel = served?.model
            provider = served?.provider
            costUsd = cost.takeIf { it > 0.0 }
            generationId = served?.generationId
          }
          .build(),
      unchecked =
        asked
          .map { it.id }
          .filter { id ->
            val verdict = verdicts.firstOrNull { it.ruleId == id }
            verdict == null || verdict.verdict == GuidelineVerdictV1.NEEDS_EVIDENCE
          },
    )

  private fun pendingResult(
    guidelines: CatalogGuidelinesV1,
    subject: PreviewSubject,
  ): PreviewGuidelineResult {
    val asked = askedRules(guidelines, subject)
    return result(guidelines, subject, asked, emptyList(), null, 0.0)
      .copy(unchecked = asked.map { it.id }, pending = true)
  }

  private fun money(value: Double): String = String.format(java.util.Locale.ROOT, "%.4f", value)

  /** One subject's answer, or why it has none. */
  private class Outcome(
    val subject: PreviewSubject,
    val verdicts: List<GuidelineVerdictV1> = emptyList(),
    val served: GuidelineServed? = null,
    val cost: Double = 0.0,
    val capped: Boolean = false,
    val failure: String? = null,
  )

  /**
   * The run's spend and counters, shared by the subjects asked in parallel. A request is started
   * only when the cap can afford one more costing what the dearest so far did, counting those in
   * flight at that price.
   */
  private class Ledger(private val cap: Double?, private val maxFailed: Int) {
    var spent = 0.0
      private set

    var requests = 0
      private set

    var invented = 0
      private set

    var retried = 0
      private set

    private var dearest = 0.0
    private var inFlight = 0.0
    private var failed = 0

    @Synchronized
    fun reserve(): Double? {
      cap ?: return 0.0
      val committed = spent + inFlight
      if (committed >= cap || committed + dearest > cap) return null
      inFlight += dearest
      return dearest
    }

    @Synchronized
    fun settle(reservation: Double, cost: Double, counted: Boolean) {
      inFlight = (inFlight - reservation).coerceAtLeast(0.0)
      if (counted) requests++
      spent += cost
      dearest = maxOf(dearest, cost)
    }

    @Synchronized
    fun invent(n: Int) {
      invented += n
    }

    @Synchronized fun mayRetry(): Boolean = failed < maxFailed

    @Synchronized
    fun retry() {
      failed++
      retried++
    }
  }

  companion object {
    /**
     * Why a rule the jev checker did not decide is unchecked: a visual rule, never asked, or one
     * Jev could not tell from the text.
     */
    const val TEXT_ONLY_REASON: String = "needs the picture; the jev checker is text-only"

    /** The least probability a verdict is taken at; below it the rule is left unchecked. */
    const val MIN_PROBABILITY: Double = 0.5

    /** Subjects asked at once; Jev's own limits are far above it (80 requests a second). */
    const val DEFAULT_PARALLELISM: Int = 4

    private const val MAX_PARALLELISM: Int = 16

    private val KIND_VISUAL: String = GuidelineRuleV1.KIND_VISUAL

    internal fun unchecked(
      subject: PreviewSubject,
      rule: GuidelineRuleV1,
      reason: String,
    ): GuidelineVerdictV1 =
      GuidelineVerdictV1.Builder(rule.id, GuidelineVerdictV1.NEEDS_EVIDENCE)
        .apply {
          subjectId = subject.previewId
          this.reason = reason
        }
        .build()
  }
}

/** The decisions requests the jev checker sends, and how it reads their answers. */
internal object JevRuleRequests {
  /** The verdict options, `cannot_tell` first: Jev leans toward the first option of a Choice. */
  val OPTIONS: List<Pair<String, String>> =
    listOf(
      "cannot_tell" to
        "The text evidence (the Kotlin source, the accessibility nodes and the measured checks) " +
          "does not settle it: answering needs the rendered picture, or evidence not given here.",
      "not_applicable" to
        "The rule is about something this preview does not contain, or the rule says to answer " +
          "not_applicable for a preview like this one.",
      "fail" to
        "The text evidence shows the preview breaks the rule: the answer to its check is no.",
      "pass" to
        "The text evidence shows the preview follows the rule: the answer to its check is yes.",
    )

  /** A rule's node question's option for "no node": not broken, or broken on no single node. */
  const val NO_NODE: String = "none"

  /** The most nodes and checks a subject's state carries, as the vision request caps them. */
  private const val MAX_NODES = 80
  private const val MAX_CHECKS = 40

  /**
   * Jev's limits: 32k tokens for the state plus the longest question, 64k for the state plus every
   * question. A subject whose questions would pass the second is asked in several requests.
   */
  private const val MAX_REQUEST_TOKENS = 56_000

  /** [rules] in groups whose request fits Jev's context alongside [subject]'s state. */
  fun chunks(
    guidelines: CatalogGuidelinesV1,
    subject: PreviewSubject,
    rules: List<GuidelineRuleV1>,
  ): List<List<GuidelineRuleV1>> {
    val stateTokens = state(guidelines, subject).toString().length / 4
    val room = (MAX_REQUEST_TOKENS - stateTokens).coerceAtLeast(2_000)
    val out = mutableListOf<MutableList<GuidelineRuleV1>>()
    var used = 0
    rules.forEach { rule ->
      val tokens = questionTokens(rule, subject)
      if (out.isEmpty() || used + tokens > room) {
        out += mutableListOf<GuidelineRuleV1>()
        used = 0
      }
      out.last() += rule
      used += tokens
    }
    return out
  }

  private fun questionTokens(rule: GuidelineRuleV1, subject: PreviewSubject): Int =
    (rule.check.length + rule.guidance.length + OPTIONS.sumOf { it.second.length } + 200) / 4 +
      if (subject.nodes.isEmpty()) 0 else 60 + subject.nodes.take(MAX_NODES).size * 3

  /** The question key of the [index]th rule, and of its node question. */
  fun key(index: Int): String = "r${index + 1}"

  fun nodeKey(index: Int): String = "${key(index)}_node"

  /** The decisions request for [rules] of [subject]: one Choice per rule, keyed `r1`, `r2`, …. */
  fun body(
    guidelines: CatalogGuidelinesV1,
    subject: PreviewSubject,
    rules: List<GuidelineRuleV1>,
    model: String,
  ): JsonObject = buildJsonObject {
    put("model", model)
    put("state", state(guidelines, subject))
    val nodeIds = subject.nodes.take(MAX_NODES).map { it.id }.distinct()
    putJsonObject("questions") {
      rules.forEachIndexed { index, rule ->
        putJsonObject(key(index)) {
          put("type", "choice")
          putJsonObject("instructions") {
            put(
              "question",
              "Judge the preview in `preview` against this design rule (`${rule.id}`), from the " +
                "text evidence only. The rule's check: ${rule.check}",
            )
            put("guidance", rule.guidance)
          }
          putJsonObject("criteria") { OPTIONS.forEach { (option, text) -> put(option, text) } }
        }
        if (nodeIds.isNotEmpty()) {
          putJsonObject(nodeKey(index)) {
            put("type", "choice")
            put(
              "instructions",
              "If the preview breaks the rule `${rule.id}` (${rule.check.take(300)}), which " +
                "accessibility node in `accessibility.nodes` is it broken on? Answer " +
                "$NO_NODE when the rule is not broken, or no single node is the one.",
            )
            putJsonObject("criteria") {
              put(NO_NODE, "The rule is not broken, or not on one node listed.")
              nodeIds.forEach { put(it, JsonNull) }
            }
          }
        }
      }
    }
  }

  /** What Jev is shown about [subject]: text only, no picture. */
  fun state(guidelines: CatalogGuidelinesV1, subject: PreviewSubject): JsonObject =
    buildJsonObject {
      put(
        "task",
        "Decide whether one rendered Jetpack Compose @Preview follows design rules. You cannot " +
          "see the render: judge only from the text below. A rule about how the preview looks " +
          "(colour, spacing, alignment, clipping, emphasis) that the source and the " +
          "accessibility data do not settle is cannot_tell.",
      )
      put("platform", guidelines.platform)
      put("catalog", guidelines.catalog)
      putJsonObject("preview") {
        put("name", subject.label)
        put("surface", subject.surface)
        subject.profile?.let { put("profile", it) }
        subject.pictures
          .mapNotNull { it.description }
          .takeIf { it.isNotEmpty() }
          ?.let { put("capture", it.joinToString(" ")) }
      }
      put(
        "source",
        subject.source?.take(PreviewGuidelineRequests.MAX_SOURCE_CHARS)?.trimEnd()
          ?: "not available",
      )
      if (subject.nodes.isEmpty() && subject.checks.isEmpty()) {
        put("accessibility", "not available for this preview")
      } else {
        putJsonObject("accessibility") {
          val viewport =
            subject.pictures.firstOrNull()?.let { PreviewGuidelineRequests.pngSize(it.png) }
          viewport?.let { (w, h) ->
            put(
              "viewport",
              "$w×$h px; a node marked off:<edge> extends past that edge. Content past the edge " +
                "of a scrollable node is scrolled away, not clipped.",
            )
          }
          putJsonArray("nodes") {
            add(JsonPrimitive("id | role | label | bounds left,top,right,bottom px | states"))
            subject.nodes.take(MAX_NODES).forEach { node ->
              val off = viewport?.let { (w, h) -> PreviewGuidelineRequests.offEdges(node, w, h) }
              val states =
                node.states +
                  off
                    .orEmpty()
                    .takeIf { it.isNotEmpty() }
                    ?.let { listOf("off:" + it.joinToString("+")) }
                    .orEmpty()
              add(
                JsonPrimitive(
                  "${node.id} | ${node.role ?: "-"} | ${node.label.take(60)} | " +
                    "${node.left},${node.top},${node.right},${node.bottom} | " +
                    states.joinToString(" ").ifEmpty { "-" }
                )
              )
            }
          }
          putJsonArray("measured_checks") {
            if (subject.checks.isEmpty()) {
              add(JsonPrimitive("the Accessibility Test Framework reported nothing on this render"))
            }
            subject.checks.take(MAX_CHECKS).forEach { check ->
              add(
                JsonPrimitive(
                  "${check.type} ${check.level} on ${check.element?.take(60) ?: "-"}: " +
                    check.message.take(240)
                )
              )
            }
          }
        }
      }
    }

  /** What one reply decided for [rules], or null when it answered none of them. */
  class Parsed(
    val verdicts: List<GuidelineVerdictV1>,
    val served: GuidelineServed,
    val invented: Int,
  )

  fun read(body: String, subject: PreviewSubject, rules: List<GuidelineRuleV1>): Parsed? {
    val root = runCatching { GUIDELINES_JSON.parseToJsonElement(body).jsonObject }.getOrNull()
    val answers = root?.get("answers") as? JsonObject ?: return null
    val asked = rules.indices.flatMap { listOf(key(it), nodeKey(it)) }.toSet()
    val invented = answers.keys.count { it !in asked }
    var answered = 0
    val verdicts = rules.mapIndexed { index, rule ->
      val answer = answers[key(index)] as? JsonObject
      val choice = (answer?.get("choice") as? JsonPrimitive)?.contentOrNull
      val probability =
        ((answer?.get("probabilities") as? JsonObject)?.get(choice) as? JsonPrimitive)?.doubleOrNull
          ?: (answer?.get("confidence") as? JsonPrimitive)?.doubleOrNull
          ?: 0.0
      if (choice != null && choice in OPTIONS.map { it.first }) answered++
      val p = String.format(java.util.Locale.ROOT, "%.2f", probability)
      when {
        choice == null || choice !in OPTIONS.map { it.first } ->
          JevChecker.unchecked(subject, rule, "${JevChecker.TEXT_ONLY_REASON} (Jev gave no answer)")
        choice == "cannot_tell" ->
          JevChecker.unchecked(
            subject,
            rule,
            "${JevChecker.TEXT_ONLY_REASON} (Jev could not tell from the text, p $p)",
          )
        probability < JevChecker.MIN_PROBABILITY ->
          JevChecker.unchecked(
            subject,
            rule,
            "${JevChecker.TEXT_ONLY_REASON} (Jev leaned $choice at only p $p)",
          )
        else -> {
          val verdict =
            when (choice) {
              "pass" -> GuidelineVerdictV1.PASS
              "fail" -> GuidelineVerdictV1.FAIL
              else -> GuidelineVerdictV1.NOT_APPLICABLE
            }
          val node =
            if (verdict == GuidelineVerdictV1.FAIL) cited(answers[nodeKey(index)], subject)
            else null
          GuidelineVerdictV1.Builder(rule.id, verdict)
            .apply {
              subjectId = subject.previewId
              confidence = probability
              nodeIds = listOfNotNull(node?.id)
              reason =
                when (verdict) {
                  GuidelineVerdictV1.FAIL ->
                    "Jev (text-only) judged this broken from the source and accessibility data" +
                      (node?.let { " on ${it.id} (${it.role ?: "node"} '${it.label.take(40)}')" }
                        ?: "") +
                      " (p $p)."
                  GuidelineVerdictV1.PASS -> "Jev (text-only): follows it (p $p)."
                  else -> "Jev (text-only): not applicable (p $p)."
                }
            }
            .build()
        }
      }
    }
    if (answered == 0) return null
    return Parsed(verdicts, GuidelineResponse.served(root), invented)
  }

  /** The node a rule's node question chose, when it is one of [subject]'s and chosen firmly. */
  private fun cited(answer: Any?, subject: PreviewSubject): PreviewNode? {
    val obj = answer as? JsonObject ?: return null
    val choice = (obj["choice"] as? JsonPrimitive)?.contentOrNull ?: return null
    if (choice == NO_NODE) return null
    val p =
      ((obj["probabilities"] as? JsonObject)?.get(choice) as? JsonPrimitive)?.doubleOrNull ?: 0.0
    if (p < JevChecker.MIN_PROBABILITY) return null
    return subject.nodes.firstOrNull { it.id == choice }
  }
}
