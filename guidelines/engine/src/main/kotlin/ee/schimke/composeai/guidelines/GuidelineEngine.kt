package ee.schimke.composeai.guidelines

import ee.schimke.composeai.guidelines.protocol.CatalogGuidelinesV1
import ee.schimke.composeai.guidelines.protocol.GuidelineEvidenceNeedV1
import ee.schimke.composeai.guidelines.protocol.GuidelineRecordV1
import ee.schimke.composeai.guidelines.protocol.GuidelineRuleV1
import ee.schimke.composeai.guidelines.protocol.GuidelineSubjectV1
import ee.schimke.composeai.guidelines.protocol.GuidelineVerdictV1

/**
 * What a host can fetch when a model asks for more than the first pass gave it: the CLI over its
 * render session, the MCP server over its daemons, a CI step over handoff renders (which can fetch
 * nothing more, and says so by listing no [available] kinds).
 */
public interface GuidelineEvidenceHost {
  /** The evidence kinds this host can supply (`GuidelineEvidenceNeedV1.KIND_*`). */
  public val available: List<String>

  /**
   * The kinds it can supply for [previewId]: [available] unless some exist only for some previews
   * (an already-rendered scroll capture, [PreviewGuidelineRequests.KIND_SCROLL_CAPTURE]).
   */
  public fun available(previewId: String): List<String> = available

  /** [previewId]'s accessibility nodes, or null when the host cannot get them. */
  public fun nodes(previewId: String): List<PreviewNode>? = null

  /**
   * [previewId]'s measured accessibility checks (ATF results on its render), or null when the host
   * cannot get them. Served with [nodes] for a [PreviewGuidelineRequests.KIND_A11Y] need.
   */
  public fun checks(previewId: String): List<PreviewCheck>? = null

  /**
   * One line the request shows up front about evidence [previewId] may be asked for, so the model
   * knows whether asking would help: for accessibility data, how many nodes, whether one scrolls,
   * which checks reported. Null when the host has nothing to say without fetching it; a host that
   * would have to render to answer returns null rather than render.
   */
  public fun summary(previewId: String): String? = null

  /**
   * Called once before a round's evidence is gathered, with each subject's needs (only kinds the
   * host advertised for it), so a host that produces evidence by rendering — the CLI fetching
   * accessibility data through a render daemon — can produce all of it in one pass rather than one
   * session per preview. The per-preview calls ([nodes], [checks], [render], [source]) follow.
   */
  public fun prefetch(needs: Map<String, List<GuidelineEvidenceNeedV1>>) {}

  /**
   * [previewId] rendered as [need] asks (theme, font scale, device), or the already-rendered
   * capture a [PreviewGuidelineRequests.KIND_SCROLL_CAPTURE] need asks for, or null.
   */
  public fun render(previewId: String, need: GuidelineEvidenceNeedV1): SubjectPicture? = null

  /** [previewId]'s source, or null. */
  public fun source(previewId: String): String? = null

  /** A host with nothing to fetch. */
  public object None : GuidelineEvidenceHost {
    override val available: List<String> = emptyList()
  }
}

/**
 * What a run does when a request fails. A request that failed for a reason that may pass — no
 * answer, a timeout, 408, 429, 5xx, an error OpenRouter returned in place of a completion, or a
 * reply with no usable verdict — is asked again after a backoff, up to [maxAttempts] tries,
 * honouring the server's `Retry-After`. A batch that still fails is split in half, and each half
 * asked, down to single subjects ([split]), so one slow batch cannot leave every preview in it
 * unchecked. A timeout on a batch of several subjects is split at once rather than asked again: the
 * same request would take as long again.
 *
 * Each retry and each half is a new request under the run's cost cap: none is started that the cap
 * cannot afford, and whatever a failed reply cost is counted. A problem is recorded once for each
 * request that finally failed, not for every attempt.
 */
public data class GuidelineRetry(
  /** Tries of one request, the first included; 1 asks once. */
  val maxAttempts: Int = 2,
  /** The wait before the first retry, doubled for each after it. */
  val initialDelayMillis: Long = 2_000,
  /**
   * The longest wait before a retry. A `Retry-After` asking for longer is not waited for: the
   * request is treated as having failed its last try.
   */
  val maxDelayMillis: Long = 60_000,
  /** Whether a batch that keeps failing is split in half and each half asked. */
  val split: Boolean = true,
  /**
   * How many failed tries the whole run may follow with a retry or a split. Past it, each request
   * is asked once: when the provider is down, retrying every batch only makes the run slower.
   */
  val maxFailedAttempts: Int = 8,
)

/** How a run behaves. */
public data class GuidelineRunOptions(
  val model: String = OpenRouterClient.DEFAULT_MODEL,
  val budget: GuidelineBudget = GuidelineBudget(),
  /** Follow-up rounds for `needs_evidence` verdicts; 0 asks once. */
  val maxRounds: Int = 1,
  val triage: Boolean = true,
  val triageThreshold: Double = 0.5,
  /**
   * Do not start a request expected to take the spend past this many dollars (one costing what the
   * dearest request of the run so far did); what is left is reported unchecked.
   */
  val maxCostUsd: Double? = null,
  /** Where the rules came from, linked from each request's provenance. */
  val rulesSource: String = CatalogGuidelinesV1.FILE_NAME,
  val ranBy: String? = null,
) {
  /**
   * Retrying and splitting a request that failed. A body property, so the constructor and `copy`
   * keep their ABI: set it with [withRetry], and note that `copy` resets it to the default.
   */
  public var retry: GuidelineRetry = GuidelineRetry()
    private set

  /** These options, retrying as [retry] says. */
  public fun withRetry(retry: GuidelineRetry): GuidelineRunOptions =
    copy().also { it.retry = retry }
}

/** A whole run: one result per subject, what it cost, and what went wrong on the way. */
public data class GuidelineRunResult(
  val results: List<PreviewGuidelineResult>,
  val costUsd: Double,
  val requests: Int,
  val problems: List<String>,
  /**
   * Requests that did not come back as verdicts: a transport error, a non-2xx answer (an invalid
   * key, 429, 5xx) or an unreadable reply. Their previews are reported unchecked, so a run with any
   * is incomplete, unlike stopping at the cost cap, which is a limit the caller chose.
   */
  val failedRequests: Int = 0,
)

/**
 * Checks rendered previews against a catalog's guidelines.
 *
 * Previews whose render is unchanged are answered from [cache]. The rest go in batches, those the
 * cache has never seen ahead of those it holds a stale verdict for
 * ([PreviewGuidelineRequests.batches]); before each batch an optional Jev triage decides which
 * extra evidence (a dark or large-font render, accessibility nodes) each subject needs, and the
 * [host] fetches only that. After round 0, rules the model answered `needs_evidence` are re-asked
 * for those subjects only, with what it asked for, up to [GuidelineRunOptions.maxRounds]; a rule
 * still undecided is reported unchecked, never passed.
 */
public class GuidelineEngine(
  private val model: GuidelineModel,
  private val host: GuidelineEvidenceHost = GuidelineEvidenceHost.None,
  private val cache: GuidelineResultCache? = null,
  private val options: GuidelineRunOptions = GuidelineRunOptions(),
  private val clock: () -> Long = System::currentTimeMillis,
) {
  /** How a retry waits; tests replace it. */
  internal var sleep: (Long) -> Unit = { Thread.sleep(it) }

  public fun run(
    guidelines: CatalogGuidelinesV1,
    subjects: List<PreviewSubject>,
  ): GuidelineRunResult {
    val problems = mutableListOf<String>()
    val results = mutableListOf<PreviewGuidelineResult>()
    var spent = 0.0
    var requests = 0
    var failedRequests = 0
    // The dearest request so far: what the next one is expected to cost, so the cap is not crossed
    // by a request started just under it. Before the first answer there is nothing to go on.
    var dearest = 0.0
    var capped = 0
    var retried = 0
    // Requests asked again after a failure that may pass (no answer, 408, 429, 5xx, a provider
    // error), and every failed try, which the run-wide allowance for retries and splits counts.
    var retriedTransient = 0
    var failedAttempts = 0
    // Requests abandoned without an answer: one may still have been billed, so each is counted
    // against the cap at what the dearest request cost, though not reported as spent.
    var unpriced = 0.0
    var abandoned = 0
    // Batches split after their request kept failing, as "<n> previews (<why>)".
    val splits = mutableListOf<String>()
    // Verdicts dropped because they answer a question nobody asked: rule ids the request never
    // listed for that subject (by id, counted), and subjects outside the request.
    val invented = linkedMapOf<String, Int>()
    // Subjects a reply left rules of with neither a verdict nor an `others` statement.
    var unstated = 0
    val strays = mutableListOf<String>()

    /** Whether one more request, expected to cost what the dearest so far did, fits the cap. */
    fun affordable(): Boolean {
      val cap = options.maxCostUsd ?: return true
      val committed = spent + unpriced
      return committed < cap && committed + dearest <= cap
    }

    /** Whether the run may still follow a failed try with a retry or a split. */
    fun mayRecover(): Boolean = failedAttempts <= options.retry.maxFailedAttempts

    // A subject no rule applies to costs no request: asked about nothing, a model can only answer
    // nothing, which reads as an unreadable reply at best and a clean pass at worst.
    val askable = subjects.filter { subject ->
      val reason = guidelines.noRulesFor(subject) ?: return@filter true
      results += noRules(guidelines, subject, reason)
      false
    }
    results
      .filter { it.noRules != null }
      .groupBy { it.noRules!! }
      .forEach { (reason, skipped) ->
        problems += "${skipped.size} preview(s) were not checked: $reason"
      }

    val pending = askable.filter { subject ->
      val hit = cache?.get(subject, guidelines, options.model)
      if (hit != null) results += hit
      hit == null
    }

    // Previews never checked go first, so a capped run spends its budget widening coverage before
    // re-asking previews whose earlier verdict went stale. Batched apart: batching groups by
    // surface, which would otherwise interleave the two.
    val (unseen, stale) = pending.partition { cache?.checked(it.previewId) != true }
    // A queue, not a list: a batch whose request keeps failing comes back as its two halves, asked
    // next, already triaged.
    val queue = ArrayDeque<QueuedBatch>()
    (PreviewGuidelineRequests.batches(guidelines, unseen, options.budget) +
        PreviewGuidelineRequests.batches(guidelines, stale, options.budget))
      .forEach { queue.addLast(QueuedBatch(it, triaged = false)) }
    while (queue.isNotEmpty()) {
      val (batch0, triaged) = queue.removeFirst()
      if (!affordable()) {
        batch0.subjects.forEach { results += unchecked(guidelines, it) }
        capped += batch0.subjects.size
        continue
      }
      // Triage: fetch the evidence Jev expects to matter before the vision model sees the batch.
      var batch = batch0
      if (!triaged && options.triage && host.available.isNotEmpty()) {
        val rulesSummary =
          guidelines.subjectRules(batch.surface).joinToString("\n") { "${it.id}: ${it.check}" }
        // Only offer what this host can supply: a decision for a render the host cannot draw
        // would cost a question and fetch nothing.
        val offers =
          JevTriage.DEFAULT_OFFERS.filter { servedKind(it.need.kind, host.available) != null }
        val reply =
          offers
            .takeIf { it.isNotEmpty() }
            ?.let { runCatching { model.decide(JevTriage.body(batch, rulesSummary, offers)) } }
            ?.getOrNull()
        if (reply != null && reply.status in 200..299) {
          val wants =
            JevTriage.wanted(reply.body, batch, offers, threshold = options.triageThreshold)
          if (wants.isNotEmpty()) batch = withEvidence(batch, wants)
        }
      }

      val verdicts = mutableMapOf<String, MutableMap<String, GuidelineVerdictV1>>()
      val served = mutableListOf<GuidelineServed>()
      val setVerdicts = mutableListOf<GuidelineVerdictV1>()
      // Rules passed by an `others` statement rather than listed, by preview id, and of the set.
      val implicit = mutableMapOf<String, MutableSet<String>>()
      val setImplicit = mutableSetOf<String>()
      // Subjects a reply left rules of with neither a verdict nor an `others` statement.
      val uncovered = mutableSetOf<String>()
      // Everything this batch's requests cost, replies that could not be used included.
      var batchSpent = 0.0

      /** Asks [target]: null when its verdicts are in, or why it finally failed. */
      fun ask(
        target: GuidelineBatch,
        round: Int,
        onlyRules: Map<String, Set<String>>?,
      ): FailedRequest? {
        val request =
          PreviewGuidelineRequests.request(
            guidelines,
            target,
            options.rulesSource,
            target.subjects.flatMap { host.available(it.previewId) }.distinct(),
            round,
            onlyRules,
            target.subjects.associate { it.previewId to host.available(it.previewId) },
            // The summary stands in for accessibility data not yet attached; once a round has
            // brought the nodes and checks, they speak for themselves.
            target.subjects
              .filter { it.nodes.isEmpty() && it.checks.isEmpty() }
              .mapNotNull { subject ->
                host.summary(subject.previewId)?.let { subject.previewId to it }
              }
              .toMap(),
          )
        val (perSubject, setRules) =
          PreviewGuidelineRequests.askedRules(guidelines, target, round, onlyRules)
        val askedOf = perSubject.mapValues { (_, rules) -> rules.map { it.id }.toSet() }
        val askedOnce = setRules.map { it.id }.toSet()
        val order = request.pictures.map { (it.subjectId ?: "") to it.kind }
        var attempt = 0
        var kept: List<GuidelineVerdictV1>
        var answer: GuidelineReply
        while (true) {
          attempt++
          val response = runCatching {
            model.complete(request, options.model)
          }
            .getOrElse {
              return FailedRequest("request failed: ${it.message}", FailureKind.FATAL)
            }
          requests++
          // Paid for whether or not the answer can be used; an error in place of a completion can
          // carry a cost too.
          val cost = GuidelineResponse.cost(response.body) ?: 0.0
          spent += cost
          batchSpent += cost
          dearest = maxOf(dearest, cost)
          val failure: FailedRequest =
            if (response.status in 200..299) {
              val error = GuidelineResponse.failure(response.body)
              if (error != null) {
                error
              } else {
                val parsed = GuidelineResponse.parse(response.body, target, order)
                val reply = parsed.getOrNull()
                if (reply != null) {
                  strays += reply.strays
                  // Only a verdict on a rule this request listed for that subject (or for the
                  // set) is an answer; anything else is a rule the model made up and has no guide
                  // to link.
                  val (valid, dropped) =
                    reply.verdicts.partition { verdict ->
                      val subjectId = verdict.subjectId
                      if (subjectId == null) verdict.ruleId in askedOnce
                      else verdict.ruleId in askedOf[subjectId].orEmpty()
                    }
                  dropped.forEach { invented.merge(it.ruleId, 1, Int::plus) }
                  // A reply listing no finding but stating that everything else passes is the
                  // common, cheap answer, not an empty one.
                  if (valid.isNotEmpty() || reply.othersPass.isNotEmpty()) {
                    kept = valid
                    answer = reply
                    break
                  }
                  FailedRequest(
                    "the reply answered no rule it was asked (it named " +
                      dropped.map { it.ruleId }.distinct().take(8).joinToString() +
                      ")",
                    FailureKind.UNUSABLE,
                  )
                } else {
                  FailedRequest(
                    "unreadable reply: ${parsed.exceptionOrNull()?.message}",
                    FailureKind.UNUSABLE,
                  )
                }
              }
            } else {
              FailedRequest.of(response)
            }
          failedAttempts++
          if (response.status == ModelResponse.NO_ANSWER && cost == 0.0) {
            abandoned++
            unpriced += dearest
          }
          val tries = if (attempt > 1) " (after $attempt tries)" else ""
          val wait =
            response.retryAfterMillis
              ?: (options.retry.initialDelayMillis shl (attempt - 1).coerceAtMost(20)).coerceAtMost(
                options.retry.maxDelayMillis
              )
          val stop =
            when {
              failure.kind == FailureKind.FATAL || failure.kind == FailureKind.TOO_LARGE -> ""
              // Our own timeout gave up on it: the same request would take as long again, and
              // its halves are the retry.
              failure.kind == FailureKind.TIMEOUT &&
                target.subjects.size > 1 &&
                options.retry.split &&
                round == 0 -> ""
              attempt >= options.retry.maxAttempts -> ""
              !mayRecover() -> "; not asked again: the run's failures used up its retries"
              wait > options.retry.maxDelayMillis ->
                "; not asked again: the server asked for a ${wait / 1000} s wait"
              !affordable() -> "; not asked again: the cost cap left no room"
              else -> null
            }
          if (stop != null) return failure.copy(problem = failure.problem + tries + stop)
          if (failure.kind == FailureKind.UNUSABLE) {
            // A reply with nothing usable is usually a bad draw, not a bad question: ask at once.
            retried++
          } else {
            retriedTransient++
            sleep(wait)
          }
        }
        served += answer.served
        // A region lives with the subject whose picture it is drawn on: a verdict about one
        // preview may point at another's picture, and nested only in the first it would be
        // filtered out of both previews' overlays. Moved after every verdict is in, so a later
        // verdict for the owner cannot overwrite it.
        val inBatch = target.subjects.map { it.previewId }.toSet()
        kept.forEach { verdict ->
          val subjectId = verdict.subjectId
          if (subjectId == null) {
            setVerdicts.removeAll { it.ruleId == verdict.ruleId }
            setVerdicts += verdict
            setImplicit -= verdict.ruleId
          } else {
            verdicts.getOrPut(subjectId) { mutableMapOf() }[verdict.ruleId] = verdict
            implicit[subjectId]?.remove(verdict.ruleId)
          }
        }
        // The reply lists only what does not pass; its `others` statement passes the rest of what
        // was asked. A rule neither listed nor covered by a statement stays unchecked.
        target.subjects.forEach { subject ->
          val id = subject.previewId
          val listed = kept.filter { it.subjectId == id }.map { it.ruleId }.toSet()
          val rest = askedOf[id].orEmpty() - listed
          if (rest.isEmpty()) return@forEach
          val confidence = answer.othersPass[id]
          if (confidence == null) {
            uncovered += id
            return@forEach
          }
          val mine = verdicts.getOrPut(id) { mutableMapOf() }
          rest.forEach { ruleId ->
            mine[ruleId] = implicitPass(ruleId, id, confidence)
            implicit.getOrPut(id) { mutableSetOf() } += ruleId
          }
        }
        val setListed = kept.filter { it.subjectId == null }.map { it.ruleId }.toSet()
        val setRest = askedOnce - setListed
        answer.othersPass[GuidelineReply.SET]?.let { confidence ->
          setRest.forEach { ruleId ->
            setVerdicts.removeAll { it.ruleId == ruleId }
            setVerdicts += implicitPass(ruleId, null, confidence)
            setImplicit += ruleId
          }
        }
        kept.forEach { reported ->
          val from = reported.subjectId ?: return@forEach
          if (reported.verdict != GuidelineVerdictV1.FAIL) return@forEach
          reported.regions
            .filter { it.subjectId != null && it.subjectId != from && it.subjectId in inBatch }
            .forEach { region ->
              val owner = region.subjectId!!
              val existing = verdicts[owner]?.get(reported.ruleId)
              // A rule the owner was never asked (another surface or profile) does not become a
              // finding on it: the region stays with the verdict that named it.
              val ownerSubject = target.subjects.firstOrNull { it.previewId == owner }
              val applies =
                ownerSubject != null &&
                  guidelines
                    .subjectRules(
                      ownerSubject.surface,
                      ownerSubject.profile,
                      ownerSubject.pictures.isNotEmpty(),
                    )
                    .any { it.id == reported.ruleId }
              if (existing == null && !applies) return@forEach
              val mine = verdicts.getOrPut(owner) { mutableMapOf() }
              // The owner's own failure gains the region; a preview the model did not judge on
              // that rule gets the finding where it was seen. One the model passed keeps its
              // verdict, and the region stays with the verdict that named it, undrawn.
              val moved =
                when {
                  existing == null ->
                    reported
                      .newBuilder()
                      .apply {
                        subjectId = owner
                        nodeIds = emptyList()
                        regions = listOf(region)
                        reason = "Seen while judging $from: ${reported.reason}"
                      }
                      .build()
                  existing.verdict == GuidelineVerdictV1.FAIL ->
                    existing.newBuilder().apply { regions = existing.regions + region }.build()
                  else -> return@forEach
                }
              mine[reported.ruleId] = moved
              val source = verdicts.getValue(from)
              source[reported.ruleId]?.let { current ->
                source[reported.ruleId] =
                  current.newBuilder().apply { regions = current.regions - region }.build()
              }
            }
        }
        return null
      }

      val failure = ask(batch, 0, null)
      if (failure != null) {
        if (failure.splittable && batch.subjects.size > 1 && options.retry.split && mayRecover()) {
          // One slow or failing batch must not sink every preview in it: ask its halves, next.
          // Each is a request of its own under the cap, checked as it comes off the queue.
          val half = (batch.subjects.size + 1) / 2
          queue.addFirst(QueuedBatch(batch.copy(subjects = batch.subjects.drop(half)), true))
          queue.addFirst(QueuedBatch(batch.copy(subjects = batch.subjects.take(half)), true))
          splits += "${batch.subjects.size} previews (${failure.problem.take(120)})"
          continue
        }
        problems += failure.problem
        failedRequests++
        batch.subjects.forEach { results += unchecked(guidelines, it) }
        continue
      }

      // Evidence rounds: re-ask only the subjects and rules the model could not decide.
      var current = batch
      // Subjects whose follow-up was cut short by the cap or a failed request: their undecided
      // rules were never re-asked, so their result is not cached and the next run asks again.
      var interrupted = emptySet<String>()
      for (round in 1..options.maxRounds) {
        val undecided =
          current.subjects
            .associate { subject ->
              subject.previewId to
                verdicts[subject.previewId].orEmpty().values.filter {
                  it.verdict == GuidelineVerdictV1.NEEDS_EVIDENCE
                }
            }
            .filterValues { it.isNotEmpty() }
        if (undecided.isEmpty() || host.available.isEmpty()) break
        if (!affordable()) {
          interrupted = undecided.keys
          break
        }
        val needs = undecided.mapValues { (_, list) -> list.flatMap { it.needs }.distinct() }
        val gathered = withEvidence(current, needs)
        val followUp =
          GuidelineBatch(
            gathered.surface,
            gathered.subjects.filter { it.previewId in undecided },
          )
        val followUpFailure =
          ask(followUp, round, undecided.mapValues { (_, l) -> l.map { it.ruleId }.toSet() })
        if (followUpFailure != null) {
          problems += followUpFailure.problem
          failedRequests++
          interrupted = undecided.keys
          break
        }
        current = gathered
      }

      unstated += uncovered.size
      val share = if (batch.subjects.isEmpty()) 0.0 else batchSpent / batch.subjects.size
      val last = served.lastOrNull()
      batch.subjects.forEach { subject ->
        val asked =
          guidelines.subjectRules(subject.surface, subject.profile, subject.pictures.isNotEmpty())
        val mine = verdicts[subject.previewId].orEmpty().values.toList() + setVerdicts
        val unchecked =
          asked
            .map { it.id }
            .filter { id ->
              val v = verdicts[subject.previewId]?.get(id)
              v == null || v.verdict == GuidelineVerdictV1.NEEDS_EVIDENCE
            }
        val result =
          PreviewGuidelineResult(
              previewId = subject.previewId,
              renderHash = subject.renderHash,
              record = record(guidelines, subject, asked, mine, last, share),
              unchecked = unchecked,
            )
            .also { result ->
              result.implicitPasses =
                asked
                  .map { it.id }
                  .filter { it in implicit[subject.previewId].orEmpty() || it in setImplicit }
                  .filter { id -> mine.any { it.ruleId == id && it.verdict == PASS } }
            }
        results += result
        // Keyed on the subject as the caller handed it in: batching may truncate its source to fit
        // the budget, and triage and follow-up rounds attach evidence, none of which the next
        // run's lookup (over the caller's subject) will have.
        val arrived = pending.firstOrNull { it.previewId == subject.previewId } ?: subject
        // A reply that left rules of it unanswered is not kept either: the next run asks again
        // rather than reuse a result that is part unchecked for no reason of the rules'.
        if (
          subject.previewId !in interrupted &&
            !(subject.previewId in uncovered && unchecked.isNotEmpty())
        )
          cache?.put(result, arrived, guidelines, options.model)
      }
    }
    if (capped > 0) {
      problems +=
        "the cost cap (\$${money(options.maxCostUsd ?: 0.0)}) was reached; $capped previews were " +
          "not checked (\$${money(spent)} spent; the next request was expected to cost about " +
          "\$${money(dearest)})"
    }
    if (invented.isNotEmpty()) {
      problems +=
        "${invented.values.sum()} verdict(s) named a rule their request did not ask and were " +
          "dropped: " +
          invented.entries
            .sortedByDescending { it.value }
            .take(12)
            .joinToString { (id, n) -> if (n > 1) "$id ×$n" else id } +
          (if (invented.size > 12) " and ${invented.size - 12} more" else "")
    }
    if (unstated > 0) {
      problems +=
        "$unstated preview(s) got a reply that neither listed some of their rules nor stated " +
          "that the rest pass; those rules are reported unchecked"
    }
    if (strays.isNotEmpty()) {
      problems +=
        "${strays.size} verdict(s) named a subject outside their request and were dropped: " +
          strays.take(8).joinToString()
    }
    if (retried > 0) {
      problems += "$retried request(s) were asked again after a reply with no usable verdict"
    }
    if (retriedTransient > 0) {
      problems +=
        "$retriedTransient request(s) were asked again after a failure that may pass (no " +
          "answer, a timeout, 408, 429, 5xx or a provider error)"
    }
    if (splits.isNotEmpty()) {
      problems +=
        "${splits.size} request(s) kept failing and were split into smaller ones: " +
          splits.take(4).joinToString("; ") +
          (if (splits.size > 4) " and ${splits.size - 4} more" else "")
    }
    if ((model as? OpenRouterClient)?.relaxedParameters == true) {
      problems +=
        "no provider of ${options.model} honours every parameter sent (the strict reply schema, " +
          "max_tokens), so requests were routed without provider.require_parameters and a " +
          "reply may ignore the schema"
    }
    if (abandoned > 0 && unpriced > 0.0 && options.maxCostUsd != null) {
      problems +=
        "$abandoned request(s) got no answer and may still have been billed; the cost cap " +
          "counted \$${money(unpriced)} for them"
    }
    return GuidelineRunResult(results, spent, requests, problems, failedRequests)
  }

  /** [ruleId] passed by a reply's `others` statement for [subjectId] (null: the set). */
  private fun implicitPass(ruleId: String, subjectId: String?, confidence: Double) =
    GuidelineVerdictV1.Builder(ruleId, PASS)
      .apply {
        this.subjectId = subjectId
        this.confidence = confidence
        reason = IMPLICIT_PASS_REASON
      }
      .build()

  private fun money(value: Double): String = String.format(java.util.Locale.ROOT, "%.4f", value)

  /** [batch] with the evidence in [needs] fetched from the host and attached to its subjects. */
  private fun withEvidence(
    batch: GuidelineBatch,
    needs: Map<String, List<GuidelineEvidenceNeedV1>>,
  ): GuidelineBatch {
    // Never ask the host for a kind it did not advertise, whoever asked for it: Jev's triage or
    // the model's own `needs_evidence`. A need for the protocol's `a11y-hierarchy` / `semantics`
    // is served as `a11y` by a host offering that instead.
    val served =
      batch.subjects
        .associate { subject ->
          val offered = host.available(subject.previewId)
          subject.previewId to
            needs[subject.previewId].orEmpty().mapNotNull { need ->
              val kind = servedKind(need.kind, offered) ?: return@mapNotNull null
              if (kind == need.kind) need else need.newBuilder().apply { this.kind = kind }.build()
            }
        }
        .filterValues { it.isNotEmpty() }
    if (served.isNotEmpty()) host.prefetch(served)
    return batch.copy(
      subjects =
        batch.subjects.map { subject ->
          val wanted = served[subject.previewId] ?: return@map subject
          var updated = subject
          wanted.forEach { need ->
            when (need.kind) {
              GuidelineEvidenceNeedV1.KIND_A11Y_HIERARCHY,
              GuidelineEvidenceNeedV1.KIND_SEMANTICS ->
                if (updated.nodes.isEmpty()) {
                  host.nodes(subject.previewId)?.let { updated = updated.copy(nodes = it) }
                }
              PreviewGuidelineRequests.KIND_A11Y -> {
                if (updated.nodes.isEmpty()) {
                  host.nodes(subject.previewId)?.let { updated = updated.copy(nodes = it) }
                }
                if (updated.checks.isEmpty()) {
                  host.checks(subject.previewId)?.let { updated = updated.copy(checks = it) }
                }
              }
              GuidelineEvidenceNeedV1.KIND_SOURCE ->
                if (updated.source == null) {
                  host.source(subject.previewId)?.let { updated = updated.copy(source = it) }
                }
              PreviewGuidelineRequests.KIND_SCROLL_CAPTURE ->
                if (
                  updated.pictures.none { it.kind == PreviewGuidelineRequests.KIND_SCROLL_CAPTURE }
                ) {
                  host.render(subject.previewId, need)?.let {
                    updated = updated.copy(pictures = updated.pictures + it)
                  }
                }
              GuidelineEvidenceNeedV1.KIND_RENDER -> {
                val already =
                  updated.pictures.any {
                    it.theme == need.theme &&
                      it.fontScale == need.fontScale &&
                      it.device == need.device
                  }
                if (!already) {
                  host.render(subject.previewId, need)?.let {
                    updated = updated.copy(pictures = updated.pictures + it)
                  }
                }
              }
            }
          }
          updated
        }
    )
  }

  private fun record(
    guidelines: CatalogGuidelinesV1,
    subject: PreviewSubject,
    asked: List<GuidelineRuleV1>,
    verdicts: List<GuidelineVerdictV1>,
    served: GuidelineServed?,
    cost: Double,
  ): GuidelineRecordV1 =
    GuidelineRecordV1.Builder(
        revision = 0,
        model = options.model,
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
        routing = served?.routing
      }
      .build()

  /** [subject]'s result when no rule applies to it: nothing asked, nothing unchecked. */
  private fun noRules(
    guidelines: CatalogGuidelinesV1,
    subject: PreviewSubject,
    reason: String,
  ): PreviewGuidelineResult =
    PreviewGuidelineResult(
        previewId = subject.previewId,
        renderHash = subject.renderHash,
        record = record(guidelines, subject, emptyList(), emptyList(), null, 0.0),
      )
      .also { it.noRules = reason }

  private fun unchecked(
    guidelines: CatalogGuidelinesV1,
    subject: PreviewSubject,
  ): PreviewGuidelineResult {
    val asked =
      guidelines.subjectRules(subject.surface, subject.profile, subject.pictures.isNotEmpty())
    return PreviewGuidelineResult(
      previewId = subject.previewId,
      renderHash = subject.renderHash,
      record = record(guidelines, subject, asked, emptyList(), null, 0.0),
      unchecked = asked.map { it.id },
      pending = true,
    )
  }
}

private const val PASS = GuidelineVerdictV1.PASS

/**
 * The `reason` of a pass a reply did not list: covered by its statement that every rule it left out
 * passes ([PreviewGuidelineResult.implicitPasses] names them).
 */
public const val IMPLICIT_PASS_REASON: String =
  "Passed implicitly: not among the reply's findings, which stated every other rule passes."

/** A batch waiting to be asked; [triaged] once Jev has already been asked about its subjects. */
private data class QueuedBatch(val batch: GuidelineBatch, val triaged: Boolean)

/** Why a request did not come back as verdicts, and what may still be done about it. */
internal enum class FailureKind {
  /** It may pass: no answer, 408, 5xx, a provider error. Asked again, then split. */
  TRANSIENT,
  /** 429: asked again after the wait it named; asking about fewer subjects would not help. */
  RATE_LIMITED,
  /** Our own timeout abandoned it: split at once when it has several subjects. */
  TIMEOUT,
  /** 413: too big to ask. Split, never asked again as it is. */
  TOO_LARGE,
  /** A reply with no usable verdict. Asked again at once, then split. */
  UNUSABLE,
  /** Nothing to retry: an invalid key, no credit, a malformed request. */
  FATAL,
}

/** A request that failed, in the words the run's problems use. */
internal data class FailedRequest(val problem: String, val kind: FailureKind) {
  /** Whether asking about fewer subjects at once might succeed. */
  val splittable: Boolean
    get() = kind != FailureKind.FATAL && kind != FailureKind.RATE_LIMITED

  companion object {
    /** [response], not a 2xx, as a failure. */
    fun of(response: ModelResponse): FailedRequest {
      val status = response.status
      if (status == ModelResponse.NO_ANSWER) {
        return FailedRequest(
          "the request got no answer: " + (response.transportError ?: response.body.take(200)),
          if (response.timedOut) FailureKind.TIMEOUT else FailureKind.TRANSIENT,
        )
      }
      val kind =
        when (status) {
          408 -> FailureKind.TRANSIENT
          413 -> FailureKind.TOO_LARGE
          429 -> FailureKind.RATE_LIMITED
          in 500..599 -> FailureKind.TRANSIENT
          else -> FailureKind.FATAL
        }
      return FailedRequest("the model answered $status: ${response.body.take(200)}", kind)
    }
  }
}

/**
 * The kind [offered] serves a need for [kind] as: [kind] itself; `a11y` for the protocol's
 * `a11y-hierarchy` / `semantics` (both are the nodes, and `a11y` carries them with the measured
 * checks); `a11y-hierarchy` for `a11y` from a host offering only the nodes; or null when it is not
 * served.
 */
internal fun servedKind(kind: String, offered: List<String>): String? {
  val nodeKinds =
    setOf(GuidelineEvidenceNeedV1.KIND_A11Y_HIERARCHY, GuidelineEvidenceNeedV1.KIND_SEMANTICS)
  return when {
    kind in offered -> kind
    kind in nodeKinds && PreviewGuidelineRequests.KIND_A11Y in offered ->
      PreviewGuidelineRequests.KIND_A11Y
    kind == PreviewGuidelineRequests.KIND_A11Y -> nodeKinds.firstOrNull { it in offered }
    else -> null
  }
}

/**
 * The findings in [result]: its `fail` verdicts at or above [minConfidence], most certain first.
 */
public fun PreviewGuidelineResult.failures(minConfidence: Double = 0.5): List<GuidelineVerdictV1> =
  record.verdicts
    .filter { it.verdict == GuidelineVerdictV1.FAIL && it.confidence >= minConfidence }
    .sortedByDescending { it.confidence }
