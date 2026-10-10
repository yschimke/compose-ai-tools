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
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.Serializable
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
 * rule from text alone and leaves every rule that needs the picture unchecked.
 */
public enum class GuidelineChecker(public val id: String) {
  /** A vision model judges the render, its source and its evidence (the default). */
  VISION("vision"),

  /**
   * EXPERIMENTAL. Jev decides each structural rule from text evidence only, asking for more over up
   * to [GuidelineRunOptions.maxRounds] follow-up rounds; visual rules, and any rule still
   * `cannot_tell` after them, are reported unchecked ([JevChecker.TEXT_ONLY_REASON]).
   */
  JEV("jev");

  public companion object {
    /** The checker named [value] (`vision` or `jev`), or null for anything else. */
    public fun parse(value: String?): GuidelineChecker? = entries.firstOrNull {
      it.id == value?.trim()?.lowercase()
    }
  }
}

/** How the jev checker reached one rule's answer, for tuning it against a vision run. */
@Serializable
public data class JevRuleTrace(
  val ruleId: String,
  /** The round that settled it (0 is the first ask). */
  val round: Int,
  /**
   * Jev's final choice: `pass`, `fail`, `not_applicable`, `cannot_tell`, or `visual` (not asked).
   */
  val choice: String,
  val probability: Double = 0.0,
  /** The kinds of computed fact the question was handed ([GuidelineFacts]). */
  val facts: List<String> = emptyList(),
  /** The evidence kinds it asked for on the way (`a11y`, `source`, `facts`). */
  val requested: List<String> = emptyList(),
)

/** How the jev checker reached one preview's answers: rounds, requests, spend, time. */
@Serializable
public data class JevSubjectTrace(
  val rounds: Int,
  val requests: Int,
  val latencyMillis: Long,
  val costUsd: Double,
  /** The kinds of computed fact it had by the end. */
  val facts: List<String> = emptyList(),
  val rules: List<JevRuleTrace> = emptyList(),
)

/**
 * EXPERIMENTAL: the text-only checker, minimal first.
 *
 * Round 0 shows Jev, per subject, a compact source excerpt (the preview's body, and only the
 * signatures of the wrappers it calls), the ~30-token accessibility summary line, and a one-line
 * list of the [GuidelineFacts] computed from what is already in hand; each structural rule is one
 * Choice whose instructions carry the rule's check, its guidance and the facts that bear on it
 * ([JevRuleShapes]), decisive ones first. Besides `cannot_tell`, `not_applicable`, `fail` and
 * `pass`, a question offers one `needs:<kind>` option per evidence kind the subject could still be
 * shown — `needs:a11y` (the full nodes and measured checks, which the host may have to fetch),
 * `needs:source` (the whole source with its wrappers), `needs:facts` (every computed fact) — and a
 * follow-up round serves what was asked, one host prefetch for all subjects, re-asking only the
 * rules that asked. `cannot_tell` while evidence is still offered counts as asking for all of it.
 * Only after the last round does `cannot_tell` become unchecked ([TEXT_ONLY_REASON]); a visual rule
 * is never asked. Nothing is passed silently.
 *
 * Jev returns probabilities, never text, so a verdict's reason is the decisive fact it was shown,
 * and its `nodeIds` come from a Choice over the node ids in play, or from that fact's node.
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

  /** How many subjects are asked at once: the run's `--concurrency` ([GuidelineRunOptions]). */
  var parallelism: Int = options.concurrency

  /**
   * For comparison only, off by default: fetch and show every subject's full accessibility data
   * before round 0, as the first version of this checker did, rather than minimal first.
   */
  var a11yUpFront: Boolean = false

  private val jevModel: String = options.answeringModel
  private val cacheModel: String = options.cacheModel

  /** One subject through the rounds. Touched by one worker at a time, and between rounds. */
  private inner class Asked(val arrived: PreviewSubject, guidelines: CatalogGuidelinesV1) {
    /** The cache held an older result for it: asked after every never-checked subject. */
    var stale = false
    var subject: PreviewSubject = arrived
    val asked: List<GuidelineRuleV1> =
      guidelines.subjectRules(arrived.surface, arrived.profile, arrived.pictures.isNotEmpty())
    var open: List<GuidelineRuleV1> = asked.filter { it.kind != KIND_VISUAL }
    val verdicts = linkedMapOf<String, GuidelineVerdictV1>()
    val traces = linkedMapOf<String, JevRuleTrace>()
    val requested = mutableMapOf<String, MutableSet<String>>()
    /** Evidence kinds asked for this round, by rule. */
    val wants = mutableMapOf<String, MutableSet<String>>()
    var a11yInHand = false
    var a11yFull = false
    var sourceFull = false
    var factsFull = false
    var facts: List<GuidelineFact> = emptyList()
    var cost = 0.0
    var requests = 0
    var latencyNanos = 0L
    var rounds = 0
    var served: GuidelineServed? = null
    var failure: String? = null
    var capped = false
    /** A follow-up round was cut short: what it settled stands, but the result is not cached. */
    var interrupted = false

    fun view(): JevRuleRequests.View =
      JevRuleRequests.View(
        subject = subject,
        facts = facts,
        a11yFull = a11yFull,
        a11ySummary =
          if (a11yInHand || a11yFull)
            PreviewGuidelineRequests.a11ySummary(subject.nodes, subject.checks)
              .substringBefore(". Ask for")
          else host.summary(subject.previewId)?.substringBefore(". Ask for"),
        sourceFull = sourceFull,
        factsFull = factsFull,
      )

    /** The evidence kinds this subject could still be shown. */
    fun offered(): List<String> = buildList {
      if (
        !a11yFull &&
          servedKind(PreviewGuidelineRequests.KIND_A11Y, host.available(subject.previewId)) != null
      )
        add(KIND_A11Y)
      val source = subject.source
      if (
        !sourceFull &&
          ((source != null && JevRuleRequests.excerpt(source) != source) ||
            (source == null &&
              GuidelineEvidenceNeedV1.KIND_SOURCE in host.available(subject.previewId)))
      )
        add(KIND_SOURCE)
      if (!factsFull && facts.isNotEmpty()) add(KIND_FACTS)
    }

    fun refreshFacts(platform: String) {
      facts = GuidelineFacts.of(subject, platform)
    }

    fun trace(rule: GuidelineRuleV1, round: Int, choice: String, probability: Double) =
      JevRuleTrace(
        rule.id,
        round,
        choice,
        probability,
        JevRuleShapes.relevant(rule, facts).map { it.kind }.distinct(),
        requested[rule.id].orEmpty().toList(),
      )
  }

  fun run(guidelines: CatalogGuidelinesV1, subjects: List<PreviewSubject>): GuidelineRunResult {
    val problems = mutableListOf<String>()
    val results = mutableListOf<PreviewGuidelineResult>()

    val askable = subjects.filter { subject ->
      // A subject only set-scoped rules apply to is asked nothing here: this checker never asks
      // set rules, so it must not read as checked.
      val reason =
        guidelines.noRulesFor(subject)
          ?: ("only set-scoped rules of the `${guidelines.catalog}` guidelines apply, and the " +
              "jev checker judges each preview on its own")
            .takeIf {
              guidelines
                .subjectRules(subject.surface, subject.profile, subject.pictures.isNotEmpty())
                .isEmpty()
            }
          ?: return@filter true
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
    val states =
      unseen.map { Asked(it, guidelines) } +
        stale.map { Asked(it, guidelines).also { state -> state.stale = true } }
    prepare(states, guidelines.platform)

    val ledger = requestPool ?: Ledger(options.maxCostUsd, options.retry.maxFailedAttempts)
    val maxRounds = options.maxRounds.coerceAtLeast(0)
    val pool =
      Executors.newFixedThreadPool(parallelism.coerceIn(1, MAX_PARALLELISM)) { runnable ->
        Thread(runnable, "jev-checker").apply { isDaemon = true }
      }
    try {
      for (round in 0..maxRounds) {
        val active = states.filter {
          it.open.isNotEmpty() && it.failure == null && !it.capped && !it.interrupted
        }
        if (active.isEmpty()) break
        val last = round == maxRounds
        // What each subject is offered and shown is read from the host here, on this thread: the
        // host is never called from several threads at once.
        val asks = active.associateWith { state ->
          (if (last) emptyList() else state.offered()) to state.view()
        }
        // Under a cap, never-checked subjects are asked as a wave before any stale one may
        // contend for what the cap has left, so a capped run widens coverage first.
        val waves =
          if (options.maxCostUsd == null) listOf(active)
          else active.partition { !it.stale }.toList().filter { it.isNotEmpty() }
        waves.forEach { wave ->
          wave
            .map { state ->
              pool.submit(
                Callable {
                  // One subject's error is its own failed request, not the run's.
                  runCatching {
                    val (offered, view) = asks.getValue(state)
                    ask(guidelines, state, round, offered, view, ledger)
                  }
                    .onFailure { state.failure = "the jev checker failed: ${it.message}" }
                }
              )
            }
            .forEach { it.get() }
        }
        serve(active.filter { it.failure == null && !it.capped }, guidelines.platform)
      }
    } finally {
      pool.shutdown()
      pool.awaitTermination(1, TimeUnit.MINUTES)
    }

    var capped = 0
    var failedRequests = 0
    val asking = mutableMapOf<String, Int>()
    states.forEach { state ->
      val subject = state.subject
      state.requested.values.flatten().forEach { asking.merge(it, 1, Int::plus) }
      val decided = state.verdicts.values.any { it.verdict != NEEDS }
      when {
        state.capped && !decided -> {
          capped++
          results += pendingResult(guidelines, state.arrived)
        }
        state.failure != null && !decided -> {
          failedRequests++
          problems += "${subject.previewId}: ${state.failure}"
          results += pendingResult(guidelines, state.arrived)
        }
        else -> {
          if (state.failure != null) {
            failedRequests++
            problems += "${subject.previewId}: ${state.failure} (in a follow-up round)"
          }
          if (state.capped) capped++
          // Whatever is still open was never settled: unchecked, not passed.
          state.open.forEach { rule ->
            state.verdicts[rule.id] =
              unchecked(
                subject,
                rule,
                "$TEXT_ONLY_REASON (Jev could not tell from the text after " +
                  "${state.rounds} round(s))",
              )
            state.traces.putIfAbsent(
              rule.id,
              state.trace(rule, (state.rounds - 1).coerceAtLeast(0), CANNOT_TELL, 0.0),
            )
          }
          state.asked
            .filter { it.kind == KIND_VISUAL }
            .forEach { rule ->
              state.verdicts[rule.id] = unchecked(subject, rule, TEXT_ONLY_REASON)
              state.traces[rule.id] = JevRuleTrace(rule.id, 0, VISUAL, 0.0)
            }
          val verdicts = state.asked.mapNotNull { state.verdicts[it.id] }
          val result =
            result(guidelines, subject, state.asked, verdicts, state.served, state.cost).also {
              it.jev =
                JevSubjectTrace(
                  rounds = state.rounds,
                  requests = state.requests,
                  latencyMillis = state.latencyNanos / 1_000_000,
                  costUsd = state.cost,
                  facts = state.facts.map { f -> f.kind }.distinct(),
                  rules = state.asked.mapNotNull { r -> state.traces[r.id] },
                )
            }
          results += result
          // Keyed on the subject as the caller handed it in, as the vision engine does: the
          // evidence attached on the way is not on the next run's lookup.
          if (state.failure == null && !state.capped && !state.interrupted)
            cache?.put(result, state.arrived, guidelines, cacheModel)
        }
      }
    }

    // Counted over every result, cached ones included: a run answered wholly from the cache still
    // says what the checker could not judge.
    val kinds = guidelines.rules.associate { it.id to it.kind }
    var textOnly = 0
    var cannotTell = 0
    val textOnlyPreviews = mutableSetOf<String>()
    results
      .filter { it.noRules == null && !it.pending }
      .forEach { result ->
        result.record.verdicts
          .filter { it.verdict == NEEDS }
          .forEach { verdict ->
            textOnlyPreviews += result.previewId
            if (kinds[verdict.ruleId] == KIND_VISUAL) textOnly++ else cannotTell++
          }
      }
    if (textOnly + cannotTell > 0) {
      problems +=
        "${textOnly + cannotTell} rule verdict(s) on ${textOnlyPreviews.size} preview(s) were " +
          "left unchecked: $TEXT_ONLY_REASON ($textOnly visual rule(s) never asked, " +
          "$cannotTell Jev could not tell from the text)"
    }
    if (asking.isNotEmpty()) {
      problems +=
        "Jev asked for more evidence: " +
          asking.entries.sortedByDescending { it.value }.joinToString { (k, n) -> "$k ×$n" } +
          " (rule-level requests, served in follow-up rounds)"
    }
    val setRules = guidelines.setRules()
    if (setRules.isNotEmpty() && askable.isNotEmpty()) {
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
          "not checked, or not followed up (\$${money(ledger.spent)} spent)"
    }
    return GuidelineRunResult(results, ledger.spent, ledger.requests, problems, failedRequests)
  }

  /**
   * Before round 0: the facts computable from what is already in hand. Accessibility data the host
   * holds without fetching (it can [GuidelineEvidenceHost.summary] it: a staged
   * `accessibility.json`, or an earlier fetch) feeds the facts and the summary line, though the
   * node list itself waits to be asked for; data the host would have to fetch waits for
   * `needs:a11y`. With [a11yUpFront], everything is fetched and shown at once instead.
   */
  private fun prepare(states: List<Asked>, platform: String) {
    if (a11yUpFront) {
      val wanted =
        states
          .filter {
            servedKind(PreviewGuidelineRequests.KIND_A11Y, host.available(it.subject.previewId)) !=
              null
          }
          .associate { it.subject.previewId to listOf(A11Y_NEED) }
      if (wanted.isNotEmpty()) host.prefetch(wanted)
    }
    states.forEach { state ->
      val id = state.subject.previewId
      val offered = servedKind(PreviewGuidelineRequests.KIND_A11Y, host.available(id)) != null
      if (state.subject.nodes.isNotEmpty() || state.subject.checks.isNotEmpty()) {
        state.a11yInHand = true
      } else if (offered && (a11yUpFront || host.summary(id) != null)) {
        loadA11y(state)
        state.a11yInHand = state.subject.nodes.isNotEmpty() || state.subject.checks.isNotEmpty()
      }
      if (a11yUpFront && state.a11yInHand) state.a11yFull = true
      state.refreshFacts(platform)
    }
  }

  private fun loadA11y(state: Asked) {
    val id = state.subject.previewId
    host.nodes(id)?.let { state.subject = state.subject.copy(nodes = it) }
    host.checks(id)?.let { state.subject = state.subject.copy(checks = it) }
  }

  /**
   * After a round: serves what its subjects asked for — one prefetch for every subject wanting
   * accessibility data — and re-opens only the rules that asked. A rule that asked for nothing new
   * that could be served is settled unchecked.
   */
  private fun serve(active: List<Asked>, platform: String) {
    val prefetch =
      active
        .filter { state -> state.wants.values.any { KIND_A11Y in it } && !state.a11yInHand }
        .associate { it.subject.previewId to listOf(A11Y_NEED) }
    if (prefetch.isNotEmpty()) host.prefetch(prefetch)
    active.forEach { state ->
      val asked = state.wants.values.flatten().toSet()
      var servedSomething = false
      if (KIND_A11Y in asked && !state.a11yFull) {
        if (!state.a11yInHand) loadA11y(state)
        state.a11yFull = state.subject.nodes.isNotEmpty() || state.subject.checks.isNotEmpty()
        state.a11yInHand = state.a11yFull
        servedSomething = servedSomething || state.a11yFull
      }
      if (KIND_SOURCE in asked && !state.sourceFull) {
        if (state.subject.source == null) {
          host.source(state.subject.previewId)?.let {
            state.subject = state.subject.copy(source = it)
          }
        }
        state.sourceFull = state.subject.source != null
        servedSomething = servedSomething || state.sourceFull
      }
      if (KIND_FACTS in asked && !state.factsFull) {
        state.factsFull = true
        servedSomething = true
      }
      state.refreshFacts(platform)
      val reopen = state.open.filter { it.id in state.wants }
      if (servedSomething) {
        state.open = reopen
      } else {
        // Asked only for what could not be had: settled unchecked now, not re-asked.
        reopen.forEach { rule ->
          state.verdicts[rule.id] =
            unchecked(
              state.subject,
              rule,
              "$TEXT_ONLY_REASON (the evidence Jev asked for is not available)",
            )
          state.traces.putIfAbsent(
            rule.id,
            state.trace(rule, (state.rounds - 1).coerceAtLeast(0), CANNOT_TELL, 0.0),
          )
        }
        state.open = emptyList()
      }
      state.wants.clear()
    }
  }

  /** One round for one subject: its open rules asked, answers recorded, wants noted. */
  private fun ask(
    guidelines: CatalogGuidelinesV1,
    state: Asked,
    round: Int,
    offered: List<String>,
    view: JevRuleRequests.View,
    ledger: DecisionsPool,
  ) {
    state.rounds = round + 1
    val stillOpen = mutableListOf<GuidelineRuleV1>()
    val chunks = JevRuleRequests.chunks(guidelines, view, state.open, offered)
    var asking = 0
    fun stop(capped: Boolean = false, failure: String? = null) {
      if (capped) state.capped = true
      if (failure != null) state.failure = failure
      if (round > 0) state.interrupted = true
      // What earlier chunks settled stands; only this chunk and those after it stay open.
      state.open = stillOpen + chunks.drop(asking).flatten()
    }
    for ((index, chunk) in chunks.withIndex()) {
      asking = index
      // Built before the reservation: nothing between reserving and settling may throw, or the
      // requests waiting on the first one's price would wait for ever.
      val body = JevRuleRequests.body(guidelines, view, chunk, jevModel, offered)
      var reservation = ledger.reserve() ?: return stop(capped = true)
      var attempt = 0
      while (true) {
        attempt++
        // A rate limit any worker met holds every worker, not only the one that met it.
        ledger.awaitPause(sleep)
        val started = System.nanoTime()
        val response = runCatching {
          model.decide(body)
        }
          .getOrElse {
            ledger.settle(reservation, 0.0, counted = false)
            return stop(failure = "decisions request failed: ${it.message}")
          }
        state.latencyNanos += System.nanoTime() - started
        val spent = GuidelineResponse.cost(response.body) ?: 0.0
        state.cost += spent
        state.requests++
        val refused = if (response.status in 200..299) null else FailedRequest.of(response)
        val backoff =
          response.retryAfterMillis
            ?: (options.retry.initialDelayMillis shl (attempt - 1).coerceAtMost(20)).coerceAtMost(
              options.retry.maxDelayMillis
            )
        // A rate limit holds every worker, and is published before this settlement wakes the
        // workers waiting on it, whether or not this request is asked again.
        if (refused?.kind == FailureKind.RATE_LIMITED) ledger.pauseAll(backoff)
        ledger.settle(reservation, spent, counted = true)
        val failure: FailedRequest =
          if (response.status in 200..299) {
            val parsed = JevRuleRequests.read(response.body, view, chunk, offered)
            if (parsed != null) {
              ledger.invent(parsed.invented)
              state.served = parsed.served
              parsed.answers.forEach { answer -> record(state, answer, offered, round) }
              stillOpen += chunk.filter { it.id in state.wants }
              break
            }
            FailedRequest(
              "the decisions reply answered none of its questions: ${response.body.take(200)}",
              FailureKind.UNUSABLE,
            )
          } else refused!!
        val wait = backoff
        val retry =
          failure.kind != FailureKind.FATAL &&
            failure.kind != FailureKind.TOO_LARGE &&
            attempt < options.retry.maxAttempts &&
            wait <= options.retry.maxDelayMillis &&
            ledger.tryRetry()
        if (!retry) {
          return stop(
            failure = failure.problem + if (attempt > 1) " (after $attempt tries)" else ""
          )
        }
        when (failure.kind) {
          FailureKind.UNUSABLE -> {}
          // Already published as a run-wide pause; awaited at the top of the next attempt.
          FailureKind.RATE_LIMITED -> {}
          else -> sleep(wait)
        }
        reservation = ledger.reserve() ?: return stop(capped = true)
      }
    }
    state.open = stillOpen
  }

  /** One answer into [state]: a verdict, or what it wants next round. */
  private fun record(
    state: Asked,
    answer: JevRuleRequests.Answer,
    offered: List<String>,
    round: Int,
  ) {
    val rule = answer.rule
    val wanted =
      when {
        answer.needs != null -> setOf(answer.needs)
        // `cannot_tell` (or no firm answer) while evidence is still on offer: ask for all of it.
        answer.verdict == null && offered.isNotEmpty() -> offered.toSet()
        else -> emptySet()
      }
    if (wanted.isNotEmpty()) {
      state.wants.getOrPut(rule.id) { mutableSetOf() } += wanted
      state.requested.getOrPut(rule.id) { mutableSetOf() } += wanted
      return
    }
    val p = String.format(java.util.Locale.ROOT, "%.2f", answer.probability)
    val verdict = answer.verdict
    if (verdict == null) {
      state.verdicts[rule.id] =
        unchecked(
          state.subject,
          rule,
          "$TEXT_ONLY_REASON (Jev could not tell from the text after ${round + 1} round(s), " +
            "p $p)",
        )
      state.traces[rule.id] = state.trace(rule, round, CANNOT_TELL, answer.probability)
      return
    }
    val relevant = JevRuleShapes.relevant(rule, state.facts)
    val cite =
      if (verdict == GuidelineVerdictV1.FAIL)
        answer.node ?: relevant.firstOrNull { it.decisive && it.nodeId != null }?.nodeId
      else null
    val because = relevant.firstOrNull { it.decisive }?.text ?: relevant.firstOrNull()?.text
    state.verdicts[rule.id] =
      GuidelineVerdictV1.Builder(rule.id, verdict)
        .apply {
          subjectId = state.subject.previewId
          confidence = answer.probability
          nodeIds = listOfNotNull(cite)
          reason =
            "Jev (text-only, round ${round + 1}): " +
              when (verdict) {
                GuidelineVerdictV1.FAIL ->
                  (because?.removeSuffix(".") ?: "judged broken from the source") + " (p $p)."
                GuidelineVerdictV1.PASS -> "follows it (p $p)."
                else -> "not applicable (p $p)."
              }
        }
        .build()
    state.traces[rule.id] = state.trace(rule, round, answer.choice, answer.probability)
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
            verdict == null || verdict.verdict == NEEDS
          },
    )

  private fun pendingResult(
    guidelines: CatalogGuidelinesV1,
    subject: PreviewSubject,
  ): PreviewGuidelineResult {
    val asked =
      guidelines.subjectRules(subject.surface, subject.profile, subject.pictures.isNotEmpty())
    return result(guidelines, subject, asked, emptyList(), null, 0.0)
      .copy(unchecked = asked.map { it.id }, pending = true)
  }

  private fun money(value: Double): String = String.format(java.util.Locale.ROOT, "%.4f", value)

  /**
   * What the jev checker needs from a run's request pool: cost reservations under the cap, a
   * run-wide pause on a rate limit, and the retry allowance. [Ledger] is this checker's
   * own; #5805's concurrency pool (cost reservation and a global `Retry-After` pause for the vision
   * path) is meant to implement it once it lands, through [requestPool], so both checkers share one
   * pool and one set of rules.
   */
  internal interface DecisionsPool {
    val spent: Double
    val requests: Int
    val invented: Int
    val retried: Int

    /** A reservation for one more request, or null when the cost cap cannot afford it. */
    fun reserve(): Double?

    fun settle(reservation: Double, cost: Double, counted: Boolean)

    /** Holds every worker for [millis], as a `Retry-After` on a 429 asks. */
    fun pauseAll(millis: Long)

    /** Waits out a pause [pauseAll] set, if one is still running. */
    fun awaitPause(sleep: (Long) -> Unit)

    /**
     * Claims one of the run's retries, or false when they are used up: one atomic step, so workers
     * failing together cannot all take the last one.
     */
    fun tryRetry(): Boolean

    fun invent(n: Int)
  }

  /** A pool to use in place of this checker's own [Ledger]; null uses its own. */
  var requestPool: DecisionsPool? = null

  /**
   * The run's spend and counters, shared by the subjects asked in parallel. A request is started
   * only when the cap can afford one more costing what the dearest so far did, counting those in
   * flight at that price.
   */
  private class Ledger(private val cap: Double?, private val maxFailed: Int) : DecisionsPool {
    override var spent = 0.0
      private set

    override var requests = 0
      private set

    override var invented = 0
      private set

    override var retried = 0
      private set

    private var dearest = 0.0
    private var inFlight = 0.0
    private var started = 0
    private var failed = 0

    /** Whether a reply has named its cost, so [dearest] is a price rather than a guess of zero. */
    private var priced = false
    private val lock = ReentrantLock()
    private val settled = lock.newCondition()

    /**
     * A reservation for one more request, or null when the cap cannot afford it. Under a cap,
     * nothing is started beside the first request until it has come back: before then the price is
     * unknown, and parallel requests reserved at zero could all cross the cap.
     */
    private var pausedUntil = 0L

    override fun pauseAll(millis: Long) = lock.withLock {
      pausedUntil = maxOf(pausedUntil, System.currentTimeMillis() + millis)
    }

    override fun awaitPause(sleep: (Long) -> Unit) {
      while (true) {
        val wait = lock.withLock { pausedUntil - System.currentTimeMillis() }
        if (wait <= 0) return
        sleep(wait)
      }
    }

    override fun reserve(): Double? = lock.withLock {
      if (cap == null) {
        started++
        return 0.0
      }
      while (!priced && started > 0) settled.await()
      val committed = spent + inFlight
      if (committed >= cap || committed + dearest > cap) return null
      started++
      inFlight += dearest
      dearest
    }

    override fun settle(reservation: Double, cost: Double, counted: Boolean) = lock.withLock {
      inFlight = (inFlight - reservation).coerceAtLeast(0.0)
      started--
      if (counted) requests++
      // Only a reply that named a cost prices the pool: a 429 or 5xx with none would leave
      // [dearest] at zero and let every waiting worker reserve nothing. Until one does, requests
      // under a cap go one at a time.
      if (cost > 0.0) priced = true
      spent += cost
      dearest = maxOf(dearest, cost)
      settled.signalAll()
    }

    override fun invent(n: Int) = lock.withLock { invented += n }

    override fun tryRetry(): Boolean = lock.withLock {
      if (failed >= maxFailed) return false
      failed++
      retried++
      true
    }
  }

  companion object {
    /**
     * Why a rule the jev checker did not decide is unchecked: a visual rule, never asked, or one
     * Jev could not tell from the text after its follow-up rounds.
     */
    const val TEXT_ONLY_REASON: String = "needs the picture; the jev checker is text-only"

    /** The least probability a verdict is taken at; below it the rule counts as `cannot_tell`. */
    const val MIN_PROBABILITY: Double = 0.5

    /**
     * Bumped when what the jev checker asks or computes changes its verdicts; part of
     * [GuidelineRunOptions.cacheModel].
     */
    const val FORMAT: Int = 2

    const val KIND_A11Y: String = "a11y"
    const val KIND_SOURCE: String = "source"
    const val KIND_FACTS: String = "facts"

    private const val MAX_PARALLELISM: Int = 16
    private const val CANNOT_TELL = "cannot_tell"
    private const val VISUAL = "visual"
    private val NEEDS: String = GuidelineVerdictV1.NEEDS_EVIDENCE
    private val KIND_VISUAL: String = GuidelineRuleV1.KIND_VISUAL

    private val A11Y_NEED: GuidelineEvidenceNeedV1 =
      GuidelineEvidenceNeedV1.Builder(PreviewGuidelineRequests.KIND_A11Y)
        .apply { reason = "jev checker: the accessibility data it asked for" }
        .build()

    internal fun unchecked(
      subject: PreviewSubject,
      rule: GuidelineRuleV1,
      reason: String,
    ): GuidelineVerdictV1 =
      GuidelineVerdictV1.Builder(rule.id, NEEDS)
        .apply {
          subjectId = subject.previewId
          this.reason = reason
        }
        .build()
  }
}

/**
 * Which computed facts bear on which rule, and what a `fail` looks like for it: data, matched
 * against a rule's id and check text, so a catalog's new rule picks up the facts its words name.
 * Extend [SHAPES] to teach the checker a new kind of rule.
 */
internal object JevRuleShapes {
  class Shape(val name: String, val pattern: Regex, val facts: List<String>, val failWhen: String)

  val SHAPES: List<Shape> =
    listOf(
      Shape(
        "touch target",
        Regex("touch|tap (?:area|target)|target|48 ?dp|tappable"),
        listOf(GuidelineFacts.TOUCH_TARGET, GuidelineFacts.NODE_SIZE, GuidelineFacts.FIXED_SIZE),
        "a touch-target fact shows a control smaller than 48dp, or ATF reports a " +
          "TouchTargetSizeCheck error",
      ),
      Shape(
        "contrast",
        Regex("contrast"),
        listOf(GuidelineFacts.CONTRAST, GuidelineFacts.ATF, GuidelineFacts.COLOUR),
        "a contrast fact shows a ratio below what the rule asks (4.5:1 for text)",
      ),
      Shape(
        "colour",
        Regex("colou?r|tint|container role|hex"),
        listOf(GuidelineFacts.COLOUR, GuidelineFacts.THEME),
        "the source hard-codes a colour where the rule asks for theme colour roles",
      ),
      Shape(
        "type",
        Regex("font|typograph|type scale|text size|\\bsp\\b|style|numeral"),
        listOf(GuidelineFacts.FONT_SIZE, GuidelineFacts.THEME),
        "the source sets a literal text size where the rule asks for theme type styles",
      ),
      Shape(
        "clipping",
        Regex("clip|cut|truncat|whole|overflow|ellipsi|\\bfits?\\b|drawn|round|off.?screen|edge"),
        listOf(
          GuidelineFacts.VIEWPORT_CLIP,
          GuidelineFacts.ROUND_MASK,
          GuidelineFacts.TEXT_CUT,
          GuidelineFacts.SCROLL,
          GuidelineFacts.TEXT_OVERFLOW,
          GuidelineFacts.CAPTURE,
        ),
        "a fact shows content cut off by the screen, by a container that does not scroll, or by " +
          "the round display",
      ),
      Shape(
        "button emphasis",
        Regex("button|emphasis|filled|primary|actions?\\b"),
        listOf(GuidelineFacts.BUTTONS),
        "the button calls break the rule, such as two or more filled Button calls for one " +
          "primary action",
      ),
      Shape(
        "strings",
        Regex("string|resource|hard-?coded|locali"),
        listOf(GuidelineFacts.STRINGS),
        "user-visible text is hard-coded where the rule asks for string resources",
      ),
      Shape(
        "fixed size",
        Regex("fixed|responsive|available width|fill|size modifier|width"),
        listOf(GuidelineFacts.FIXED_SIZE),
        "the source fixes a size where the rule asks for one that adapts",
      ),
      Shape(
        "descriptions",
        Regex("content ?description|icon-only|labell?ed|describ|decorative"),
        listOf(GuidelineFacts.DESCRIPTIONS, GuidelineFacts.NODE_SIZE),
        "a control the rule covers has no description, or a decorative one has one",
      ),
      Shape(
        "scrolling",
        Regex("scroll|\\blist"),
        listOf(GuidelineFacts.SCROLL, GuidelineFacts.CAPTURE, GuidelineFacts.VIEWPORT_CLIP),
        "the scroll facts show the rule broken",
      ),
      Shape(
        "overlap",
        Regex("overlap|time ?text|clear of"),
        listOf(GuidelineFacts.OVERLAP, GuidelineFacts.VIEWPORT_CLIP),
        "an overlap fact shows two elements the rule says must not overlap",
      ),
    )

  fun shapes(rule: GuidelineRuleV1): List<Shape> {
    val text = (rule.id + " " + rule.check).lowercase()
    return SHAPES.filter { it.pattern.containsMatchIn(text) }
  }

  /** [facts] that bear on [rule], decisive first, at most [max]. */
  fun relevant(
    rule: GuidelineRuleV1,
    facts: List<GuidelineFact>,
    max: Int = 8,
  ): List<GuidelineFact> {
    val kinds = shapes(rule).flatMap { it.facts }.toSet()
    return facts.filter { it.kind in kinds }.sortedByDescending { it.decisive }.take(max)
  }

  fun failWhen(rule: GuidelineRuleV1): String? =
    shapes(rule).map { it.failWhen }.takeIf { it.isNotEmpty() }?.joinToString("; or ")
}

/** The decisions requests the jev checker sends, and how it reads their answers. */
internal object JevRuleRequests {
  /** What a subject's request shows this round. */
  class View(
    val subject: PreviewSubject,
    val facts: List<GuidelineFact>,
    val a11yFull: Boolean,
    val a11ySummary: String?,
    val sourceFull: Boolean,
    val factsFull: Boolean,
  )

  /** One rule's answer: a verdict, a request for evidence, or neither (`cannot_tell`). */
  class Answer(
    val rule: GuidelineRuleV1,
    val choice: String,
    val probability: Double,
    val verdict: String?,
    val needs: String?,
    val node: String?,
  )

  class Parsed(val answers: List<Answer>, val served: GuidelineServed, val invented: Int)

  const val CANNOT_TELL: String = "cannot_tell"
  const val NEEDS_PREFIX: String = "needs:"

  /** A rule's node question's option for "no node": not broken, or broken on no single node. */
  const val NO_NODE: String = "none"

  private val NEEDS_TEXT =
    mapOf(
      JevChecker.KIND_A11Y to
        "The answer turns on the accessibility nodes or the measured checks, which are only " +
          "summarised so far: ask for the full list.",
      JevChecker.KIND_SOURCE to
        "The answer turns on code not in the excerpt (the wrapper bodies, or the rest of the " +
          "preview): ask for the whole source.",
      JevChecker.KIND_FACTS to
        "The answer turns on computed facts not listed with this question: ask for all of them.",
    )

  private const val MAX_NODES = 80
  private const val MAX_CHECKS = 40
  private const val EXCERPT_CHARS = 1_500
  private const val MAX_REQUEST_TOKENS = 56_000

  /**
   * The compact source a first round shows: the preview's own body, trimmed, and only the signature
   * line of each wrapper it calls (`PreviewSourceReader` appends their bodies after a `// Name,
   * which the preview calls` line).
   */
  fun excerpt(source: String): String {
    val parts = source.split("\n\n// ")
    val body =
      parts.first().let { if (it.length > EXCERPT_CHARS) it.take(EXCERPT_CHARS) + "\n…" else it }
    val wrappers =
      parts.drop(1).mapNotNull { part ->
        val lines = part.lines()
        val signature = lines.drop(1).firstOrNull { it.contains("fun ") } ?: return@mapNotNull null
        "// " + lines.first() + "\n" + signature.trim() + " …"
      }
    return (listOf(body) + wrappers).joinToString("\n\n")
  }

  /** [rules] in groups whose request fits Jev's context alongside the subject's state. */
  fun chunks(
    guidelines: CatalogGuidelinesV1,
    view: View,
    rules: List<GuidelineRuleV1>,
    offered: List<String>,
  ): List<List<GuidelineRuleV1>> {
    val stateTokens = state(guidelines, view).toString().length / 4
    val room = (MAX_REQUEST_TOKENS - stateTokens).coerceAtLeast(2_000)
    val out = mutableListOf<MutableList<GuidelineRuleV1>>()
    var used = 0
    rules.forEach { rule ->
      val tokens = question(rule, view, offered).toString().length / 4 + 40
      if (out.isEmpty() || used + tokens > room) {
        out += mutableListOf<GuidelineRuleV1>()
        used = 0
      }
      out.last() += rule
      used += tokens
    }
    return out
  }

  fun key(index: Int): String = "r${index + 1}"

  fun nodeKey(index: Int): String = "${key(index)}_node"

  /** The node ids a finding on [rule] may cite this round: those its facts name, or every node. */
  fun candidates(rule: GuidelineRuleV1, view: View): List<String> =
    (JevRuleShapes.relevant(rule, view.facts).mapNotNull { it.nodeId } +
        if (view.a11yFull) view.subject.nodes.take(MAX_NODES).map { it.id } else emptyList())
      .distinct()
      .take(250)

  private fun question(rule: GuidelineRuleV1, view: View, offered: List<String>): JsonObject =
    buildJsonObject {
      put("type", "choice")
      putJsonObject("instructions") {
        put("question", "For this preview, answer the rule's check: ${rule.check}")
        put("rule", rule.id)
        put("guidance", rule.guidance)
        val relevant = JevRuleShapes.relevant(rule, view.facts)
        if (relevant.isEmpty()) {
          put("relevant_facts", "none of the computed facts bear on this rule")
        } else {
          putJsonArray("relevant_facts") { relevant.forEach { add(JsonPrimitive(it.text)) } }
        }
      }
      putJsonObject("criteria") {
        put(
          CANNOT_TELL,
          "The text shown (the source, the accessibility summary and the facts) does not settle " +
            "it, and no evidence offered below would: answering needs the rendered picture.",
        )
        offered.forEach { kind -> put(NEEDS_PREFIX + kind, NEEDS_TEXT.getValue(kind)) }
        put(
          "not_applicable",
          "The rule is about something this preview does not contain, or the rule says to " +
            "answer not_applicable for a preview like this one.",
        )
        put(
          "fail",
          "No: the text shows the preview breaks the rule." +
            (JevRuleShapes.failWhen(rule)?.let { " For this rule that is when $it." } ?: ""),
        )
        put("pass", "Yes: the text shows the preview follows the rule.")
      }
    }

  /** The decisions request for [rules] of [view]'s subject: one Choice per rule. */
  fun body(
    guidelines: CatalogGuidelinesV1,
    view: View,
    rules: List<GuidelineRuleV1>,
    model: String,
    offered: List<String>,
  ): JsonObject = buildJsonObject {
    put("model", model)
    put("state", state(guidelines, view))
    putJsonObject("questions") {
      rules.forEachIndexed { index, rule ->
        put(key(index), question(rule, view, offered))
        val nodeIds = candidates(rule, view)
        if (nodeIds.isNotEmpty()) {
          putJsonObject(nodeKey(index)) {
            put("type", "choice")
            put(
              "instructions",
              "If the preview breaks the rule `${rule.id}` (${rule.check.take(300)}), which " +
                "accessibility node is it broken on? Answer $NO_NODE when the rule is not " +
                "broken, or no single node listed is the one.",
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

  /** What Jev is shown about the subject this round: text only, no picture. */
  fun state(guidelines: CatalogGuidelinesV1, view: View): JsonObject = buildJsonObject {
    val subject = view.subject
    put(
      "task",
      "Decide whether one rendered Jetpack Compose @Preview follows design rules. You cannot see " +
        "the render: judge only from the text below — the source, the accessibility data and " +
        "the facts computed from them. Where a rule needs more than is shown and more is " +
        "offered, ask for it rather than guess.",
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
    val source = subject.source
    put(
      if (view.sourceFull) "source" else "source_excerpt",
      when {
        source == null -> "not available"
        view.sourceFull -> source.take(PreviewGuidelineRequests.MAX_SOURCE_CHARS).trimEnd()
        else -> excerpt(source)
      },
    )
    if (!view.a11yFull) {
      put("accessibility", view.a11ySummary ?: "not fetched")
    } else {
      putJsonObject("accessibility") {
        val viewport =
          subject.pictures.firstOrNull()?.let { PreviewGuidelineRequests.pngSize(it.png) }
        viewport?.let { (w, h) ->
          put("viewport", "$w×$h px; off:<edge> marks a node extending past that edge.")
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
            // An empty list may mean ATF never ran: no claim that it measured clean.
            add(JsonPrimitive("no Accessibility Test Framework results are attached"))
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
    if (view.factsFull) {
      putJsonArray("facts") { view.facts.forEach { add(JsonPrimitive(it.text)) } }
    } else {
      put("facts", GuidelineFacts.summary(view.facts))
    }
  }

  /** What one reply said about [rules], or null when it answered none of them. */
  fun read(body: String, view: View, rules: List<GuidelineRuleV1>, offered: List<String>): Parsed? {
    val root = runCatching { GUIDELINES_JSON.parseToJsonElement(body).jsonObject }.getOrNull()
    val answers = root?.get("answers") as? JsonObject ?: return null
    val asked = rules.indices.flatMap { listOf(key(it), nodeKey(it)) }.toSet()
    val invented = answers.keys.count { it !in asked }
    val valid =
      setOf(CANNOT_TELL, "not_applicable", "fail", "pass") + offered.map { NEEDS_PREFIX + it }
    var answered = 0
    val out = rules.mapIndexed { index, rule ->
      val answer = answers[key(index)] as? JsonObject
      val choice = (answer?.get("choice") as? JsonPrimitive)?.contentOrNull
      val probability =
        ((answer?.get("probabilities") as? JsonObject)?.get(choice) as? JsonPrimitive)?.doubleOrNull
          ?: (answer?.get("confidence") as? JsonPrimitive)?.doubleOrNull
          ?: 0.0
      if (choice != null && choice in valid) answered++
      val needs =
        choice?.takeIf { it in valid && it.startsWith(NEEDS_PREFIX) }?.removePrefix(NEEDS_PREFIX)
      val verdict =
        when {
          choice == null || choice !in valid || needs != null || choice == CANNOT_TELL -> null
          probability < JevChecker.MIN_PROBABILITY -> null
          choice == "pass" -> GuidelineVerdictV1.PASS
          choice == "fail" -> GuidelineVerdictV1.FAIL
          else -> GuidelineVerdictV1.NOT_APPLICABLE
        }
      val node =
        if (verdict == GuidelineVerdictV1.FAIL)
          cited(answers[nodeKey(index)], candidates(rule, view))
        else null
      Answer(rule, choice ?: CANNOT_TELL, probability, verdict, needs, node)
    }
    if (answered == 0) return null
    return Parsed(out, GuidelineResponse.served(root), invented)
  }

  /** The node a rule's node question chose firmly, when it is one of [candidates]. */
  private fun cited(answer: Any?, candidates: List<String>): String? {
    val obj = answer as? JsonObject ?: return null
    val choice = (obj["choice"] as? JsonPrimitive)?.contentOrNull ?: return null
    if (choice == NO_NODE) return null
    val p =
      ((obj["probabilities"] as? JsonObject)?.get(choice) as? JsonPrimitive)?.doubleOrNull ?: 0.0
    if (p < JevChecker.MIN_PROBABILITY) return null
    return choice.takeIf { it in candidates }
  }
}
