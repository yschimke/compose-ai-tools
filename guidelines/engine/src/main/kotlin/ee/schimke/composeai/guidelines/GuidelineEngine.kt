package ee.schimke.composeai.guidelines

import ee.schimke.composeai.guidelines.protocol.CatalogGuidelinesV1
import ee.schimke.composeai.guidelines.protocol.GuidelineEvidenceNeedV1
import ee.schimke.composeai.guidelines.protocol.GuidelineRecordV1
import ee.schimke.composeai.guidelines.protocol.GuidelineRuleV1
import ee.schimke.composeai.guidelines.protocol.GuidelineSubjectV1
import ee.schimke.composeai.guidelines.protocol.GuidelineVerdictV1
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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
   * One up-front line about evidence [previewId] may be asked for (node count, scrolling, which
   * checks reported), so the model knows whether asking would help. Null when answering would need
   * a fetch or render.
   */
  public fun summary(previewId: String): String? = null

  /**
   * Called once per round before evidence is gathered, with each subject's needs, so a host that
   * renders to produce evidence can do it in one pass. The per-preview calls follow.
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
 * What a run does when a request fails. A possibly-transient failure (no answer, timeout, 408, 429,
 * 5xx, a provider error, no usable verdict) is retried with backoff up to [maxAttempts], honouring
 * `Retry-After`; a batch that still fails is split in half down to single subjects ([split]). A
 * timed-out multi-subject batch is split immediately, since retrying would take as long again.
 *
 * Every retry and half is a new request under the run's cost cap. A problem is recorded once per
 * request that finally failed.
 */
public data class GuidelineRetry(
  /** Tries of one request, the first included; 1 asks once. */
  val maxAttempts: Int = 2,
  /** The wait before the first retry, doubled for each after it. */
  val initialDelayMillis: Long = 2_000,
  /** The longest wait before a retry; a longer `Retry-After` counts as the last try failing. */
  val maxDelayMillis: Long = 60_000,
  /** Whether a batch that keeps failing is split in half and each half asked. */
  val split: Boolean = true,
  /**
   * How many failed tries the whole run may follow with a retry or split; past it each request is
   * asked once, so a provider outage doesn't slow the run. With concurrent requests, which failures
   * get the allowance depends on timing.
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
   * Do not start a request expected (at the dearest request's cost so far) to take spend past this
   * many dollars; what is left is reported unchecked.
   */
  val maxCostUsd: Double? = null,
  /** Where the rules came from, linked from each request's provenance. */
  val rulesSource: String = CatalogGuidelinesV1.FILE_NAME,
  val ranBy: String? = null,
) {
  /**
   * Retrying and splitting a request that failed. A body property to keep the constructor and
   * `copy` ABI: set it with [withRetry]; `copy` resets it.
   */
  public var retry: GuidelineRetry = GuidelineRetry()
    private set

  /**
   * How many model requests may be in flight at once; 1 asks one batch at a time. Each batch's
   * triage, request, retries, halves and follow-ups run on one worker. The cost cap still holds
   * (in-flight expected cost counts, and a capped run asks one at a time until a cost is known). A
   * rate limit pauses every worker.
   *
   * [GuidelineModel.complete] and [GuidelineModel.decide] are called concurrently when above 1; the
   * [GuidelineEvidenceHost] never is. A body property like [retry]: set it with [withConcurrency].
   */
  public var concurrency: Int = DEFAULT_CONCURRENCY
    private set

  /** These options, retrying as [retry] says. */
  public fun withRetry(retry: GuidelineRetry): GuidelineRunOptions =
    carry().also { it.retry = retry }

  /**
   * Which model answers the rules: [GuidelineChecker.VISION] (default) or the EXPERIMENTAL
   * text-only [GuidelineChecker.JEV]. A body property like [retry]: set it with [withChecker].
   */
  public var checker: GuidelineChecker = GuidelineChecker.VISION
    private set

  /** These options, answered by [checker]. */
  public fun withChecker(checker: GuidelineChecker): GuidelineRunOptions =
    carry().also { it.checker = checker }

  /**
   * The model that answers: [model], except that the jev checker asked with the vision default left
   * in place asks [JevTriage.MODEL].
   */
  public val answeringModel: String
    get() =
      if (checker == GuidelineChecker.JEV && model == OpenRouterClient.DEFAULT_MODEL)
        JevTriage.MODEL
      else model

  /**
   * The model identity a result is cached under ([GuidelineResultCache.get]): [model] for the
   * vision checker at the default [reasoningEffort], [model] naming its effort at any other, and
   * one naming the checker for any other checker, so a vision verdict and a Jev verdict — or two
   * verdicts reached at different efforts — never answer for one another.
   */
  public val cacheModel: String
    get() =
      when (checker) {
        GuidelineChecker.VISION ->
          if (reasoningEffort == OpenRouterClient.DEFAULT_REASONING_EFFORT) model
          else "$model#reasoning=${reasoningEffort ?: "default"}"
        else -> "checker:${checker.id}@${JevChecker.FORMAT}/$answeringModel"
      }.let { identity -> judge?.let { "$it/$identity" } ?: identity }

  /**
   * Who answers besides the model name, when that is not OpenRouter: `prompt` for an agent
   * answering [DeferredReplyModel]'s requests, `openai:<origin>` for an OpenAI-compatible endpoint.
   * Part of [cacheModel], so the same `--model` at two endpoints, or at an endpoint and on
   * OpenRouter, never answers for the other. Null (OpenRouter) keeps existing cache keys. A body
   * property like [retry]: set it with [withJudge].
   */
  public var judge: String? = null
    private set

  /** These options, answered by [judge]. */
  public fun withJudge(judge: String?): GuidelineRunOptions = carry().also { it.judge = judge }

  /** These options, with up to [concurrency] requests in flight at once. */
  public fun withConcurrency(concurrency: Int): GuidelineRunOptions =
    carry().also { it.concurrency = concurrency.coerceAtLeast(1) }

  /**
   * The reasoning effort the model is asked at ([OpenRouterClient.reasoningEffort]; null: the
   * model's own default), as far as the cache is concerned: a verdict reached at one effort is not
   * reused at another ([cacheModel]). The run does not send it — the model does — so a host sets
   * both. A body property like [retry]: set it with [withReasoningEffort].
   */
  public var reasoningEffort: String? = OpenRouterClient.DEFAULT_REASONING_EFFORT
    private set

  /** These options, for a model asked at [reasoningEffort]. */
  public fun withReasoningEffort(reasoningEffort: String?): GuidelineRunOptions =
    carry().also { it.reasoningEffort = reasoningEffort }

  /**
   * What [model] charges, so each request's cost is predicted before it is asked
   * ([PreviewGuidelineRequests.predictedCostUsd]): the cap reserves that prediction, scaled by how
   * far the answers so far ran past theirs, and [GuidelineRunResult.predictedCostUsd] reports the
   * whole run's. Null (the default) keeps the cap expecting each request to cost what the dearest
   * so far did. The vision checker's only: the Jev checker keeps its own cap, and
   * [GuidelineEngine.plan] predicts nothing for it. A body property like [retry]: set it with
   * [withPrice].
   */
  public var price: GuidelinePrice? = null
    private set

  /** These options, predicting costs at [price]. */
  public fun withPrice(price: GuidelinePrice?): GuidelineRunOptions =
    carry().also { it.price = price }

  /**
   * How many sizes of each preview are asked ([PreviewGuidelineRequests.sizeless]): the largest,
   * the smallest, then inward ([PreviewGuidelineRequests.extremesFirst]). A named variant, theme or
   * font scale is a preview of its own, never cut. The rest come back asked nothing, naming the
   * size asked in their place ([PreviewGuidelineResult.variantOf]). Null (the default) asks every
   * size. The vision checker's only, like [price]. A body property like [retry]: set it with
   * [withMaxVariants].
   */
  public var maxVariants: Int? = null
    private set

  /** These options, asking at most [maxVariants] sizes of each preview; null asks all. */
  public fun withMaxVariants(maxVariants: Int?): GuidelineRunOptions =
    carry().also { it.maxVariants = maxVariants?.takeIf { n -> n > 0 } }

  /**
   * Ask one size of each preview per width class ([PreviewGuidelineRequests.sizeClass]), the
   * largest in it, instead of [maxVariants]' count: a Wear preview at 192, 204, 216, 225 and 240dp
   * is asked at 216 and 240. A body property like [retry]: set it with [withVariantsPerSizeClass].
   */
  public var variantsPerSizeClass: Boolean = false
    private set

  /** These options, asking one size of each preview per width class when [perSizeClass]. */
  public fun withVariantsPerSizeClass(perSizeClass: Boolean): GuidelineRunOptions =
    carry().also { it.variantsPerSizeClass = perSizeClass }

  /**
   * What the OpenRouter key may still spend ([OpenRouterClient.keyStatus]): the run is capped at
   * [OpenRouterKeyStatus.remainingUsd] as well as [maxCostUsd], whichever is lower, so a key at its
   * limit is asked nothing instead of answering every request 403. Null (the default) asks without
   * knowing. A body property like [retry]: set it with [withKeyStatus].
   */
  public var keyStatus: OpenRouterKeyStatus? = null
    private set

  /** These options, capped at what [keyStatus] says the key has left. */
  public fun withKeyStatus(keyStatus: OpenRouterKeyStatus?): GuidelineRunOptions =
    carry().also { it.keyStatus = keyStatus }

  /** The lower of [maxCostUsd] and what [keyStatus] has left; null when neither caps the run. */
  internal val effectiveCapUsd: Double?
    get() = listOfNotNull(maxCostUsd, keyStatus?.remainingUsd).minOrNull()

  /** A copy carrying every body property, which `copy` would reset. */
  private fun carry(): GuidelineRunOptions =
    copy().also {
      it.maxVariants = maxVariants
      it.variantsPerSizeClass = variantsPerSizeClass
      it.retry = retry
      it.concurrency = concurrency
      it.checker = checker
      it.reasoningEffort = reasoningEffort
      it.judge = judge
      it.price = price
      it.keyStatus = keyStatus
    }

  public companion object {
    /**
     * Default [concurrency]: batches return in about the time of one, well under OpenRouter's
     * in-flight budget for a funded key.
     */
    public const val DEFAULT_CONCURRENCY: Int = 4
  }
}

/** A whole run: one result per subject, what it cost, and what went wrong on the way. */
public data class GuidelineRunResult(
  val results: List<PreviewGuidelineResult>,
  val costUsd: Double,
  val requests: Int,
  val problems: List<String>,
  /**
   * Requests that did not come back as verdicts (transport error, non-2xx, unreadable reply). Their
   * previews are unchecked, so the run is incomplete — unlike hitting the cost cap, which is
   * chosen.
   */
  val failedRequests: Int = 0,
) {
  /**
   * Evidence the host failed to supply — a fetch or a host call that threw — for previews that were
   * waiting on it: not a model request, but the same kind of incompleteness, since those previews'
   * rules are left unchecked. Counted once per failed fetch, however many previews it was for. A
   * body property, so the constructor and `copy` keep their ABI.
   */
  public var failedEvidence: Int = 0
    internal set

  /**
   * The output tokens the run's requests were billed for, replies that could not be used included
   * (`usage.completion_tokens`). A body property, so the constructor and `copy` keep their ABI.
   */
  public var completionTokens: Long = 0
    internal set

  /**
   * How many of [completionTokens] were the model's reasoning
   * (`usage.completion_tokens_details.reasoning_tokens`): billed, never shown. What to watch when
   * tuning the reasoning effort.
   */
  public var reasoningTokens: Long = 0
    internal set

  /**
   * Requests whose reply spent its whole token budget reasoning and were asked once more with
   * reasoning off ([GuidelineModel.completeWithoutReasoning]).
   */
  public var reasoningRetries: Int = 0
    internal set

  /**
   * Requests the model deferred ([ModelResponse.DEFERRED]): written out for someone else (an agent)
   * to answer, their previews pending. Not failures; the next run reads the answers.
   */
  public var deferredRequests: Int = 0
    internal set

  /**
   * What the run's requests were predicted to cost before any was asked, at
   * [GuidelineRunOptions.price]; null without one. Set beside [costUsd] to see how far the
   * prediction was off.
   */
  public var predictedCostUsd: Double? = null
    internal set

  /**
   * What the priced requests billed over what they were predicted to cost, before any learned
   * scaling: 1.0 is a perfect prediction. Null without a price, or when nothing priced was billed.
   * Learned per model across runs ([GuidelineResultCache]), so the next run predicts closer.
   */
  public var observedCostRatio: Double? = null
    internal set

  /**
   * Whether OpenRouter refused a request because the key's spending limit is reached (a 403 `Key
   * limit exceeded`, or a 402 that is not the in-flight budget): the run stopped asking at once,
   * and a host running several should ask nothing more with the key.
   */
  public var keyLimitReached: Boolean = false
    internal set
}

/**
 * Checks rendered previews against a catalog's guidelines.
 *
 * Unchanged renders are answered from [cache]. The rest are batched, unseen previews before stale
 * ones and each composable's first variant before any composable's second
 * ([PreviewGuidelineRequests.breadthFirst], [PreviewGuidelineRequests.batches]); an optional Jev
 * triage picks extra evidence per subject and the [host] fetches only that. Rules answered
 * `needs_evidence` are re-asked with that evidence up to [GuidelineRunOptions.maxRounds]; a rule
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

  /** The monotonic clock, in milliseconds, a run-wide pause is measured on; tests replace it. */
  internal var now: () -> Long = { System.nanoTime() / 1_000_000 }

  /** Held while the [host] is asked anything: a host need not be safe to call concurrently. */
  private val hostLock = Any()

  /**
   * Checks [subjects] against [guidelines]. Results and problems come back in batch-formation order
   * regardless of which concurrent request finished first.
   */
  public fun run(
    guidelines: CatalogGuidelinesV1,
    subjects: List<PreviewSubject>,
  ): GuidelineRunResult {
    // EXPERIMENTAL, opt-in: a separate path, so the vision run below is unchanged by it.
    if (options.checker == GuidelineChecker.JEV) {
      return JevChecker(model, host, cache, options, clock)
        .also { it.sleep = sleep }
        .run(guidelines, subjects)
    }
    val problems = mutableListOf<String>()
    val results = mutableListOf<PreviewGuidelineResult>()
    // What earlier runs learned about this model: how far its bills ran from the prediction, and
    // which parameters its providers refuse, so this one starts from there.
    val ledger = cache?.ledger
    val learned = ledger?.get(options.cacheModel)
    val started = clock()
    val client = model as? OpenRouterClient
    // Each run starts from its own model's record, not from what the client a host shares between
    // runs learned for another: a refusal still fresh is acted on, a stale one is tested again.
    // The newest still-fresh refusal of the record or of the client wins; a stale one is dropped.
    fun freshest(vararg at: Long?): Long? =
      at.filterNotNull().filter { GuidelineModelRecord().fresh(it, started) }.maxOrNull()
    val relaxedAt =
      freshest(learned?.relaxedAt, client?.relaxedSince?.takeIf { client.relaxedParameters })
    val noReasoningAt =
      freshest(
        learned?.noReasoningAt,
        client?.noReasoningSince?.takeIf { client.reasoningUnsupported },
      )
    val presetRelaxed = relaxedAt != null
    client?.learned(relaxedAt, noReasoningAt)
    val learnedRatio = learned?.costRatio?.takeIf { options.price != null }
    // The cap: the lower of the cost cap and what the key has left.
    val cap = options.effectiveCapUsd
    var spent = 0.0
    var requests = 0
    var failedRequests = 0
    // Requests a [ModelResponse.DEFERRED] model left for someone else to answer: not failures.
    var deferredRequests = 0
    // The dearest request so far: what the next one is expected to cost, so the cap is not crossed
    // by a request started just under it. Before the first answer there is nothing to go on.
    var dearest = 0.0
    // With a price: how far the dearest answer so far ran past its prediction, which scales every
    // later prediction. Null until a priced request has come back, when predictions stand as made.
    var dearestRatio: Double? = null
    // Priced requests' billed cost and their unscaled predictions: the ratio the ledger learns.
    var pricedSpent = 0.0
    var pricedPredicted = 0.0
    // Set when OpenRouter refused a request for the key's spending limit: nothing more is asked.
    var keyLimitReached = false
    var keyLimitProblem: String? = null
    // What the request the cap last turned away was expected to cost, for the report.
    var lastExpected = 0.0
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
    // Output tokens billed, how many of them were reasoning, and the replies that spent the whole
    // budget reasoning: asked again with reasoning off, which no other failure's allowance pays.
    var completionTokens = 0L
    var reasoningTokens = 0L
    var reasoningExhausted = 0
    var reasoningRetries = 0
    var reasoningOffAnswered = 0
    // Replies cut at max_tokens that were still read: their `others` statements were not taken.
    var cutReplies = 0
    // Batches split after their request kept failing, as "<n> previews (<why>)".
    // Verdicts dropped because they answer a question nobody asked: rule ids the request never
    // listed for that subject (by id, counted), and subjects outside the request.
    val invented = linkedMapOf<String, Int>()
    // Subjects a reply left rules of with neither a verdict nor an `others` statement, and batches
    // whose set-wide rules it left so.
    var unstated = 0
    var setUnstated = 0
    val strays = sortedMapOf<String, MutableList<String>>()

    // Everything above and below that workers share is read and written holding [lock].
    val lock = ReentrantLock()
    val changed = lock.newCondition()
    // The cap's expected cost of the requests in flight, how many there are, and how many have
    // come back: what is reserved is what each was expected to cost when it started.
    var reserved = 0.0
    var inFlight = 0
    var settledRequests = 0
    // No request starts before this (on [now]): a 429, a 503 or OpenRouter's in-flight budget
    // asked the whole run to wait, not only the request that met it.
    var pausedUntil = 0L
    // Per batch, keyed by its place in the queue ("00003", its halves "00003.0" and "00003.1"),
    // so the problems read in queue order whichever request finished first.
    val failures = sortedMapOf<String, MutableList<String>>()
    val splitsByKey = sortedMapOf<String, String>()
    val inventedByKey = sortedMapOf<String, LinkedHashMap<String, Int>>()
    val batchResults = mutableMapOf<String, PreviewGuidelineResult>()
    var active = 0
    var crashed: Throwable? = null

    /** The run-wide allowance for retries and splits: whether a failed try may be followed up. */
    fun mayRecover(): Boolean = lock.withLock { failedAttempts <= options.retry.maxFailedAttempts }

    /** [batch]'s predicted cost at [GuidelineRunOptions.price]; null without one. */
    fun predict(batch: GuidelineBatch): Double? =
      options.price?.let {
        PreviewGuidelineRequests.predictedCostUsd(guidelines, batch, it, options.reasoningEffort)
      }

    /**
     * What a request [predicted] to cost that much is expected to: the prediction scaled by how far
     * the dearest answer so far ran past its own, or — with no prediction — what the dearest
     * request so far cost. Holding [lock].
     */
    fun expectedLocked(predicted: Double?): Double =
      if (predicted == null || predicted <= 0.0) dearest
      else predicted * (dearestRatio ?: learnedRatio ?: 1.0)

    /**
     * Whether one more request, expected to cost [expected], fits the cap on top of what is spent
     * and what the requests in flight are expected to cost. Holding [lock].
     */
    fun fitsLocked(expected: Double): Fit {
      if (keyLimitReached) return Fit.NO
      val limit = cap ?: return Fit.YES
      val committed = spent + unpriced + reserved
      return when {
        // Before any request has come back nothing has tested an expectation: one at a time, so
        // concurrent starts cannot jointly cross the cap.
        settledRequests == 0 && inFlight > 0 -> Fit.WAIT
        committed < limit && committed + expected <= limit -> Fit.YES
        // A request in flight may come back cheaper than reserved and leave room.
        inFlight > 0 -> Fit.WAIT
        else -> Fit.NO.also { lastExpected = expected }
      }
    }

    /** Reserves what a request [predicted] to cost is expected to, as it starts. Holding [lock]. */
    fun reserveLocked(predicted: Double?): Reservation {
      val amount = if (cap == null) 0.0 else expectedLocked(predicted)
      reserved += amount
      inFlight++
      return Reservation(amount, predicted)
    }

    /**
     * A reservation for one more request [predicted] to cost that much, waiting for room; null when
     * the cap has none.
     */
    fun reserve(predicted: Double?): Reservation? = lock.withLock {
      while (true) {
        when (fitsLocked(expectedLocked(predicted))) {
          Fit.YES -> return@withLock reserveLocked(predicted)
          Fit.WAIT -> changed.await()
          Fit.NO -> return@withLock null
        }
      }
      @Suppress("UNREACHABLE_CODE") null
    }

    /** Settles [reservation] with what its request [cost]; [unanswered] when none came back. */
    fun settle(reservation: Reservation, cost: Double, unanswered: Boolean) = lock.withLock {
      reserved -= reservation.amount
      inFlight--
      settledRequests++
      spent += cost
      dearest = maxOf(dearest, cost)
      val predicted = reservation.predicted
      if (predicted != null && predicted > 0.0 && cost > 0.0) {
        dearestRatio = maxOf(dearestRatio ?: 0.0, cost / predicted)
        pricedSpent += cost
        pricedPredicted += predicted
      }
      // Abandoned without an answer: it may still have been billed, so it counts against the
      // cap at what the dearest request cost, though not reported as spent.
      if (unanswered && cost == 0.0) {
        abandoned++
        unpriced += dearest
      }
      changed.signalAll()
    }

    /**
     * Waits out the run-wide pause, if one is set, until it has passed: another rate limit may
     * extend it while this worker sleeps.
     */
    fun pause() {
      while (true) {
        val wait = lock.withLock { pausedUntil - now() }
        if (wait <= 0) return
        sleep(wait)
      }
    }

    val (asked, otherVariants) = limitVariants(guidelines, subjects)
    results += otherVariants
    val prepared = prepare(guidelines, asked)
    results += prepared.skipped
    prepared.skipped
      .groupBy { it.noRules!! }
      .forEach { (reason, skipped) ->
        problems += "${skipped.size} preview(s) were not checked: $reason"
      }
    results += prepared.cached

    // A queue, not a list: a batch whose request keeps failing comes back as its two halves, asked
    // next, already triaged.
    val queue = ArrayDeque<QueuedBatch>()
    val formed = prepared.formed
    formed.forEachIndexed { index, batch ->
      queue.addLast(QueuedBatch(batch, triaged = false, key = "%06d".format(index)))
    }
    // Where each batched subject's result goes: the order the batches were formed in.
    val batchedOrder = formed.flatMap { batch -> batch.subjects.map { it.previewId } }

    /** [batch]'s subjects, unchecked: not asked, or their request failed. Holding [lock]. */
    fun uncheckedLocked(batch: GuidelineBatch) {
      batch.subjects.forEach { batchResults[it.previewId] = unchecked(guidelines, it) }
    }

    /**
     * The next batch to ask, with the reservation its first request starts under: always the head
     * of the queue, so a capped run asks a prefix of it, as one request at a time would. Null when
     * nothing is left, or a worker failed.
     */
    fun next(): Pair<QueuedBatch, Reservation>? = lock.withLock {
      while (true) {
        if (crashed != null) return@withLock null
        if (queue.isEmpty()) {
          if (active == 0) return@withLock null
          // A batch still being asked may come back as two halves.
          changed.await()
          continue
        }
        val predicted = predict(queue.first().batch)
        when (fitsLocked(expectedLocked(predicted))) {
          Fit.YES -> {
            active++
            return@withLock queue.removeFirst() to reserveLocked(predicted)
          }
          Fit.WAIT -> changed.await()
          Fit.NO -> {
            val skipped = queue.removeFirst()
            uncheckedLocked(skipped.batch)
            capped += skipped.batch.subjects.size
            changed.signalAll()
          }
        }
      }
      @Suppress("UNREACHABLE_CODE") null
    }

    /** Asks [task]'s batch, its first request under [first]: triage, retries, splits, rounds. */
    fun process(task: QueuedBatch, first: Reservation) {
      val batch0 = task.batch
      val triaged = task.triaged
      // Triage: fetch the evidence Jev expects to matter before the vision model sees the batch.
      var batch = batch0
      if (!triaged && options.triage && synchronized(hostLock) { host.available }.isNotEmpty()) {
        val rulesSummary =
          guidelines.subjectRules(batch.surface).joinToString("\n") { "${it.id}: ${it.check}" }
        // Only offer what this host can supply: a decision for a render the host cannot draw
        // would cost a question and fetch nothing.
        val offered = synchronized(hostLock) { host.available }
        val offers = JevTriage.DEFAULT_OFFERS.filter { servedKind(it.need.kind, offered) != null }
        val reply =
          offers
            .takeIf { it.isNotEmpty() }
            ?.let { runCatching { model.decide(JevTriage.body(batch, rulesSummary, offers)) } }
            ?.getOrNull()
        if (reply != null && reply.status in 200..299) {
          val wants =
            JevTriage.wanted(reply.body, batch, offers, threshold = options.triageThreshold)
          if (wants.isNotEmpty()) batch = synchronized(hostLock) { withEvidence(batch, wants) }
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

      /**
       * Asks [target], its first try under [reservation]: null when its verdicts are in, or why it
       * finally failed.
       */
      fun ask(
        target: GuidelineBatch,
        round: Int,
        onlyRules: Map<String, Set<String>>?,
        reservation: Reservation,
      ): FailedRequest? {
        val request =
          synchronized(hostLock) {
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
          }
        val (perSubject, setRules) =
          PreviewGuidelineRequests.askedRules(guidelines, target, round, onlyRules)
        val askedOf = perSubject.mapValues { (_, rules) -> rules.map { it.id }.toSet() }
        val askedOnce = setRules.map { it.id }.toSet()
        val order = request.pictures.map { (it.subjectId ?: "") to it.kind }
        var attempt = 0
        var kept: List<GuidelineVerdictV1>
        var answer: GuidelineReply
        var held = reservation
        // Set once a reply spent its whole budget reasoning: the next try turns reasoning off.
        var reasoningOff = false
        while (true) {
          attempt++
          pause()
          val response = runCatching {
            if (reasoningOff) model.completeWithoutReasoning(request, options.model)
            else model.complete(request, options.model)
          }
            .getOrElse {
              settle(held, 0.0, unanswered = false)
              return FailedRequest("request failed: ${it.message}", FailureKind.FATAL)
            }
          // Left for someone else to answer, not asked: nothing spent, nothing to retry.
          if (response.status == ModelResponse.DEFERRED) {
            settle(held, 0.0, unanswered = false)
            return FailedRequest(response.body, FailureKind.DEFERRED)
          }
          // Paid for whether or not the answer can be used; an error in place of a completion can
          // carry a cost too.
          val cost = GuidelineResponse.cost(response.body) ?: 0.0
          val tokens = GuidelineResponse.tokens(response.body)
          lock.withLock {
            requests++
            tokens?.let {
              completionTokens += it.completion
              reasoningTokens += it.reasoning
            }
          }
          settle(held, cost, unanswered = response.status == ModelResponse.NO_ANSWER)
          batchSpent += cost
          val failure: FailedRequest =
            if (response.status in 200..299) {
              val error = GuidelineResponse.failure(response.body)
              if (error != null) {
                error
              } else {
                val parsed = GuidelineResponse.parse(response.body, target, order)
                val reply = parsed.getOrNull()
                if (reply != null) {
                  lock.withLock { strays.getOrPut(task.key) { mutableListOf() } += reply.strays }
                  // Only a verdict on a rule this request listed for that subject (or for the
                  // set) is an answer; anything else is a rule the model made up and has no guide
                  // to link.
                  val (valid, dropped) =
                    reply.verdicts.partition { verdict ->
                      val subjectId = verdict.subjectId
                      if (subjectId == null) verdict.ruleId in askedOnce
                      else verdict.ruleId in askedOf[subjectId].orEmpty()
                    }
                  lock.withLock {
                    val mine = inventedByKey.getOrPut(task.key) { linkedMapOf() }
                    dropped.forEach { mine.merge(it.ruleId, 1, Int::plus) }
                  }
                  // A reply listing no finding but stating that everything else passes is the
                  // common, cheap answer, not an empty one.
                  if (valid.isNotEmpty() || reply.othersPass.isNotEmpty()) {
                    kept = valid
                    answer = reply
                    lock.withLock {
                      if (reply.truncated) cutReplies++
                      if (reasoningOff) reasoningOffAnswered++
                    }
                    break
                  }
                  FailedRequest(
                    "the reply answered no rule it was asked (it named " +
                      dropped.map { it.ruleId }.distinct().take(8).joinToString() +
                      ")",
                    FailureKind.UNUSABLE,
                  )
                } else {
                  GuidelineResponse.reasoningExhausted(response.body)
                    ?: FailedRequest(
                      "unreadable reply: ${parsed.exceptionOrNull()?.message}",
                      FailureKind.UNUSABLE,
                    )
                }
              }
            } else {
              FailedRequest.of(response)
            }
          if (failure.kind == FailureKind.KEY_LIMIT) {
            // The key's, not this request's: every request after it would be refused the same, so
            // the run stops asking rather than meet it once per batch.
            lock.withLock {
              keyLimitReached = true
              if (keyLimitProblem == null) keyLimitProblem = failure.problem
              changed.signalAll()
            }
            return failure
          }
          if (failure.kind == FailureKind.REASONING_EXHAUSTED) {
            // Not this request's size, so not split, and not the provider failing, so not paid
            // from the run's allowance for retries: one more try with reasoning off, which
            // leaves the whole budget to the reply. Bounded at one, so it cannot starve the rest.
            lock.withLock { reasoningExhausted++ }
            if (reasoningOff) {
              return failure.copy(problem = failure.problem + ", even with reasoning off")
            }
            val noRoomLeft =
              failure.copy(
                problem = failure.problem + "; not asked again: the cost cap left no room"
              )
            val again = predict(target)
            if (lock.withLock { fitsLocked(expectedLocked(again)) } == Fit.NO) return noRoomLeft
            reasoningOff = true
            lock.withLock { reasoningRetries++ }
            held = reserve(again) ?: return noRoomLeft
            continue
          }
          lock.withLock { failedAttempts++ }
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
              else -> null
            }
          if (stop != null) return failure.copy(problem = failure.problem + tries + stop)
          val noRoom =
            failure.copy(
              problem = failure.problem + tries + "; not asked again: the cost cap left no room"
            )
          // Not waited for when the cap has no room for it whatever comes back.
          val again = predict(target)
          if (lock.withLock { fitsLocked(expectedLocked(again)) } == Fit.NO) return noRoom
          when (failure.kind) {
            // A reply with nothing usable is usually a bad draw, not a bad question: ask at once.
            FailureKind.UNUSABLE -> lock.withLock { retried++ }
            // A rate limit is the account's, not this request's: every request waits it out.
            FailureKind.RATE_LIMITED ->
              lock.withLock {
                retriedTransient++
                pausedUntil = maxOf(pausedUntil, now() + wait)
              }
            else -> {
              lock.withLock { retriedTransient++ }
              sleep(wait)
            }
          }
          held = reserve(again) ?: return noRoom
        }
        served += answer.served
        // A region belongs to the subject whose picture it is drawn on, which may differ from the
        // verdict's preview. Moved after every verdict is in so a later verdict can't overwrite it.
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

      val failure = ask(batch, 0, null, first)
      if (failure?.kind == FailureKind.DEFERRED) {
        // Pending, not failed: the next run reads the answer and carries on from here.
        lock.withLock {
          deferredRequests++
          uncheckedLocked(batch)
        }
        return
      }
      if (failure?.kind == FailureKind.KEY_LIMIT) {
        // Not asked, as when the cap is reached: unchecked, and asked again once the key has room.
        lock.withLock {
          uncheckedLocked(batch)
          capped += batch.subjects.size
        }
        return
      }
      if (failure != null) {
        if (failure.splittable && batch.subjects.size > 1 && options.retry.split && mayRecover()) {
          // One slow or failing batch must not sink every preview in it: ask its halves, next.
          // Each is a request of its own under the cap, checked as it comes off the queue.
          val half = (batch.subjects.size + 1) / 2
          lock.withLock {
            queue.addFirst(
              QueuedBatch(batch.copy(subjects = batch.subjects.drop(half)), true, task.key + ".1")
            )
            queue.addFirst(
              QueuedBatch(batch.copy(subjects = batch.subjects.take(half)), true, task.key + ".0")
            )
            splitsByKey[task.key] = "${batch.subjects.size} previews (${failure.problem.take(120)})"
            changed.signalAll()
          }
          return
        }
        lock.withLock {
          failures.getOrPut(task.key) { mutableListOf() } += failure.problem
          failedRequests++
          uncheckedLocked(batch)
        }
        return
      }

      // Evidence rounds: re-ask only the subjects and rules the model could not decide.
      var current = batch
      // Subjects whose follow-up was cut short by the cap or a failed request: their undecided
      // rules were never re-asked, so their result is not cached and the next run asks again.
      var interrupted = emptySet<String>()
      // Set when a follow-up waits on a deferred reply: the whole batch is then still pending.
      var awaitingReply = false
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
        if (undecided.isEmpty() || synchronized(hostLock) { host.available }.isEmpty()) break
        // A follow-up is a request like any other: it waits its turn under the cap, expected to
        // cost
        // at most what asking the whole batch again would.
        val slot = reserve(predict(current))
        if (slot == null) {
          interrupted = undecided.keys
          break
        }
        val needs = undecided.mapValues { (_, list) -> list.flatMap { it.needs }.distinct() }
        val gathered = synchronized(hostLock) { withEvidence(current, needs) }
        val followUp =
          GuidelineBatch(
            gathered.surface,
            gathered.subjects.filter { it.previewId in undecided },
          )
        val followUpFailure =
          ask(
            followUp,
            round,
            undecided.mapValues { (_, l) -> l.map { it.ruleId }.toSet() },
            slot,
          )
        if (followUpFailure != null) {
          lock.withLock {
            if (followUpFailure.kind == FailureKind.DEFERRED) {
              deferredRequests++
            } else if (followUpFailure.kind != FailureKind.KEY_LIMIT) {
              failures.getOrPut(task.key) { mutableListOf() } += followUpFailure.problem
              failedRequests++
            }
          }
          interrupted =
            if (followUpFailure.kind == FailureKind.DEFERRED) {
              awaitingReply = true
              // Replayed from its answers next run, which only works if the next run batches the
              // same previews: none of this batch is cached until all of it is answered.
              batch.subjects.map { it.previewId }.toSet()
            } else {
              undecided.keys
            }
          break
        }
        current = gathered
      }

      // Set-wide rules no verdict or `others` statement decided leave the batch unjudged, so none
      // of its subjects is cached as complete.
      val setAsked = PreviewGuidelineRequests.askedRules(guidelines, batch, 0, null).second
      val setUnchecked =
        setAsked
          .map { it.id }
          .filter { id ->
            setVerdicts.none { it.ruleId == id && it.verdict != GuidelineVerdictV1.NEEDS_EVIDENCE }
          }
      val setUncovered = setUnchecked.isNotEmpty()
      lock.withLock {
        if (setUncovered) setUnstated++
        unstated += uncovered.size
      }
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
              pending = awaitingReply,
            )
            .also { result ->
              result.implicitPasses =
                (asked.map { it.id } + setAsked.map { it.id })
                  .filter { it in implicit[subject.previewId].orEmpty() || it in setImplicit }
                  .filter { id -> mine.any { it.ruleId == id && it.verdict == PASS } }
            }
        lock.withLock { batchResults[subject.previewId] = result }
        // Keyed on the subject as the caller passed it: batching may truncate source and rounds
        // attach evidence, which the next run's lookup won't have.
        val arrived = prepared.pending.firstOrNull { it.previewId == subject.previewId } ?: subject
        // A reply that left rules of it unanswered is not kept either: the next run asks again
        // rather than reuse a result that is part unchecked for no reason of the rules'.
        if (
          subject.previewId !in interrupted &&
            !(subject.previewId in uncovered && unchecked.isNotEmpty()) &&
            !setUncovered
        )
          cache?.let {
            synchronized(it) { it.put(result, arrived, guidelines, options.cacheModel) }
          }
      }
    }

    /** One worker: batches off the queue until none is left. */
    fun work() {
      while (true) {
        val (task, first) = next() ?: return
        try {
          process(task, first)
        } catch (e: Throwable) {
          lock.withLock { if (crashed == null) crashed = e }
          throw e
        } finally {
          lock.withLock {
            active--
            changed.signalAll()
          }
        }
      }
    }

    val workers = options.concurrency.coerceAtLeast(1).coerceAtMost(formed.size.coerceAtLeast(1))
    if (workers == 1) {
      work()
    } else {
      val threads =
        (1..workers).map { n ->
          Thread({ runCatching { work() } }, "guidelines-request-$n").apply {
            isDaemon = true
            start()
          }
        }
      threads.forEach { it.join() }
      crashed?.let { throw it }
    }
    results += batchedOrder.mapNotNull { batchResults[it] }
    failures.values.forEach { problems += it }
    val splits = splitsByKey.values.toList()
    inventedByKey.values.forEach { mine ->
      mine.forEach { (id, n) -> invented.merge(id, n, Int::plus) }
    }

    val keyLeft = options.keyStatus?.remainingUsd
    if (keyLimitReached) {
      problems +=
        "OpenRouter refused a request because the key's spending limit is reached; " +
          "$capped previews were not checked and nothing more was asked (\$${money(spent)} " +
          "spent; ${keyLimitProblem?.take(160)})"
    } else if (capped > 0 && keyLeft != null && keyLeft == cap) {
      problems +=
        "${options.keyStatus?.describe()}, below the cost cap; $capped previews were not " +
          "checked (\$${money(spent)} spent" +
          (if (lastExpected > 0.0)
            "; the next request was expected to cost about \$${money(lastExpected)}"
          else "") +
          ")"
    } else if (capped > 0) {
      problems +=
        "the cost cap (\$${money(options.maxCostUsd ?: 0.0)}) was reached; $capped previews were " +
          "not checked (\$${money(spent)} spent; the next request was expected to cost about " +
          "\$${money(lastExpected)})"
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
    if (setUnstated > 0) {
      problems +=
        "$setUnstated batch(es) got a reply that decided none of the rules judged once across " +
          "the batch; their previews are not cached, so the next run asks again"
    }
    val strayed = strays.values.flatten()
    if (strayed.isNotEmpty()) {
      problems +=
        "${strayed.size} verdict(s) named a subject outside their request and were dropped: " +
          strayed.take(8).joinToString()
    }
    if (retried > 0) {
      problems += "$retried request(s) were asked again after a reply with no usable verdict"
    }
    if (reasoningExhausted > 0) {
      problems +=
        "$reasoningExhausted reply(ies) spent the whole token budget reasoning and wrote no " +
          "usable reply ($reasoningTokens reasoning token(s) billed in all); $reasoningRetries " +
          "request(s) were asked again with reasoning off, and $reasoningOffAnswered answered. " +
          "A lower reasoning effort leaves more of max_tokens to the reply"
    }
    if (cutReplies > 0) {
      problems +=
        "$cutReplies reply(ies) were cut at max_tokens: their findings were kept, but not their " +
          "statement that every other rule passes, so those rules are reported unchecked"
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
    if (client?.relaxedParameters == true) {
      problems +=
        "no provider of ${options.model} honours every parameter sent (the strict reply schema, " +
          "max_tokens), so requests were routed without provider.require_parameters and a " +
          "reply may ignore the schema" +
          (if (presetRelaxed) " (learned on an earlier run; tried strictly again after a week)"
          else "")
    }
    if (abandoned > 0 && unpriced > 0.0 && cap != null) {
      problems +=
        "$abandoned request(s) got no answer and may still have been billed; the cost cap " +
          "counted \$${money(unpriced)} for them"
    }
    // A variant stands on the size asked in its place: when that one was not answered (capped,
    // failed or deferred), neither is it, and it is reported missed rather than covered; rules left
    // unchecked there are unchecked here too.
    val byId = results.associateBy { it.previewId }
    val subjectsById = subjects.associateBy { it.previewId }
    val settled = results.map { result ->
      val anchorId = result.variantOf ?: return@map result
      val anchor = byId[anchorId]
      when {
        anchor == null || anchor.pending ->
          subjectsById[result.previewId]?.let { unchecked(guidelines, it) } ?: result
        anchor.unchecked.isNotEmpty() ->
          result.copy(unchecked = anchor.unchecked).withBodyOf(result)
        else -> result
      }
    }
    val observedRatio = (pricedSpent / pricedPredicted).takeIf { pricedPredicted > 0.0 }
    // Only what there is to learn: a priced bill, or how an OpenRouter model's providers answered.
    if (ledger != null && requests > 0 && (observedRatio != null || client != null)) {
      ledger.put(
        options.cacheModel,
        learn(learned, observedRatio, client),
      )
    }
    return GuidelineRunResult(settled, spent, requests, problems, failedRequests).also {
      it.predictedCostUsd =
        options.price?.let {
          formed.sumOf { batch -> predict(batch) ?: 0.0 } * (learnedRatio ?: 1.0)
        }
      it.observedCostRatio = observedRatio
      it.keyLimitReached = keyLimitReached
      it.completionTokens = completionTokens
      it.reasoningTokens = reasoningTokens
      it.reasoningRetries = reasoningRetries
      it.deferredRequests = deferredRequests
    }
  }

  /**
   * [learned] moved by what this run saw: the cost ratio half way to [observedRatio], and each
   * parameter refusal as [client] holds it — when it was met, or the earlier record this run
   * started from (so it is tested again once stale) — and cleared when strict routing answered.
   */
  private fun learn(
    learned: GuidelineModelRecord?,
    observedRatio: Double?,
    client: OpenRouterClient?,
  ): GuidelineModelRecord {
    val before = learned ?: GuidelineModelRecord()
    val ratio =
      observedRatio
        ?.let { seen ->
          before.costRatio?.let { it + (seen - it) * GuidelineModelRecord.SMOOTHING } ?: seen
        }
        ?.coerceIn(GuidelineModelRecord.MIN_RATIO, GuidelineModelRecord.MAX_RATIO)
    if (client == null) {
      return before.copy(
        costRatio = ratio ?: before.costRatio,
        pricedRuns = before.pricedRuns + if (ratio != null) 1 else 0,
      )
    }
    return GuidelineModelRecord(
      costRatio = ratio ?: before.costRatio,
      pricedRuns = before.pricedRuns + if (ratio != null) 1 else 0,
      relaxedAt = client.relaxedSince.takeIf { client.relaxedParameters },
      noReasoningAt = client.noReasoningSince.takeIf { client.reasoningUnsupported },
    )
  }

  /**
   * What [run] would ask about [subjects], without asking: how many previews and requests, and —
   * with a [GuidelineRunOptions.price] — what they are predicted to cost and how many the cost cap
   * is expected to reach. Reads the [cache]; never calls the model or the [host].
   */
  public fun plan(guidelines: CatalogGuidelinesV1, subjects: List<PreviewSubject>): GuidelinePlan {
    // The Jev checker asks every variant, as its run does.
    val asked =
      if (options.checker == GuidelineChecker.VISION) limitVariants(guidelines, subjects).first
      else subjects
    val prepared = prepare(guidelines, asked)
    // Prediction prices the vision checker's batches; the Jev checker asks differently.
    val price = options.price.takeIf { options.checker == GuidelineChecker.VISION }
    // Scaled as the run's would be, by how far this model's bills ran from prediction before.
    val learnedRatio = price?.let { cache?.ledger?.get(options.cacheModel)?.costRatio } ?: 1.0
    val predictions = price?.let { p ->
      prepared.formed.map { batch ->
        PreviewGuidelineRequests.predictedCostUsd(guidelines, batch, p, options.reasoningEffort) *
          learnedRatio
      }
    }
    val previews = prepared.formed.sumOf { it.subjects.size }
    // As the run's cap does: a request that would cross it is skipped and the next one tried.
    val reached = predictions?.let {
      val cap = options.effectiveCapUsd ?: return@let previews
      var committed = 0.0
      prepared.formed.zip(it).sumOf { (batch, cost) ->
        if (committed + cost <= cap) {
          committed += cost
          batch.subjects.size
        } else 0
      }
    }
    return GuidelinePlan(
      previews = previews,
      cached = prepared.cached.size,
      requests = prepared.formed.size,
      predictedCostUsd = predictions?.sum(),
      predictedChecked = reached,
    )
  }

  /**
   * [subjects] cut to the sizes of each preview ([PreviewGuidelineRequests.sizeless]) that
   * [GuidelineRunOptions.maxVariants] or [GuidelineRunOptions.variantsPerSizeClass] keep, in the
   * order given; the rest as results asked nothing that name the size asked in their place. A
   * subject no rule applies to is left to [prepare], which reports it.
   */
  private fun limitVariants(
    guidelines: CatalogGuidelinesV1,
    subjects: List<PreviewSubject>,
  ): Pair<List<PreviewSubject>, List<PreviewGuidelineResult>> {
    val limit = options.maxVariants
    if (limit == null && !options.variantsPerSizeClass) return subjects to emptyList()
    val askable = subjects.filter { guidelines.noRulesFor(it) == null }
    // Per preview, the size kept in each dropped size's place: one of its width class, then the
    // nearest by width.
    fun width(subject: PreviewSubject) = PreviewGuidelineRequests.widthDp(subject)
    fun widthClass(subject: PreviewSubject) = PreviewGuidelineRequests.sizeClass(width(subject))
    val keptFor = mutableMapOf<String, PreviewSubject>()
    askable
      .groupBy { PreviewGuidelineRequests.sizeless(it) }
      .values
      .forEach { sizes ->
        val kept =
          if (options.variantsPerSizeClass) {
            sizes.groupBy { widthClass(it) }.values.map { inClass -> inClass.maxBy { width(it) } }
          } else {
            PreviewGuidelineRequests.extremesFirst(sizes).take(limit!!)
          }
        sizes.forEach { size ->
          keptFor[size.previewId] =
            if (size in kept) size
            else
              kept.minWith(
                compareBy<PreviewSubject>(
                  { widthClass(it) != widthClass(size) },
                  { Math.abs(width(it) - width(size)) },
                )
              )
        }
      }
    val asked = mutableListOf<PreviewSubject>()
    val skipped = mutableListOf<PreviewGuidelineResult>()
    subjects.forEach { subject ->
      val instead = keptFor[subject.previewId]
      if (instead == null || instead === subject) {
        asked += subject
      } else {
        skipped +=
          PreviewGuidelineResult(
              previewId = subject.previewId,
              renderHash = subject.renderHash,
              record = record(guidelines, subject, emptyList(), emptyList(), null, 0.0),
            )
            .also { it.variantOf = instead.previewId }
      }
    }
    return asked to skipped
  }

  /**
   * [subjects] sorted for a run: [Prepared.skipped] when no rule applies to them, [Prepared.cached]
   * when the [cache] answers them, and the rest batched into [Prepared.formed] in the order they
   * are asked — never-checked previews before stale ones, so a capped run widens coverage before
   * re-asking, and each composable's first variant before any composable's second
   * ([PreviewGuidelineRequests.breadthFirst]).
   */
  private fun prepare(guidelines: CatalogGuidelinesV1, subjects: List<PreviewSubject>): Prepared {
    // A subject no rule applies to costs no request: asked about nothing, a model can only answer
    // nothing, which reads as an unreadable reply at best and a clean pass at worst.
    val skipped = mutableListOf<PreviewGuidelineResult>()
    val askable = subjects.filter { subject ->
      val reason = guidelines.noRulesFor(subject) ?: return@filter true
      skipped += noRules(guidelines, subject, reason)
      false
    }
    val cached = mutableListOf<PreviewGuidelineResult>()
    val pending = askable.filter { subject ->
      val hit = cache?.get(subject, guidelines, options.cacheModel)
      if (hit != null) cached += hit
      hit == null
    }
    // Batched apart, since surface grouping would otherwise interleave them.
    val (unseen, stale) = pending.partition { cache?.checked(it.previewId) != true }
    val formed = breadthBatches(guidelines, unseen) + breadthBatches(guidelines, stale)
    return Prepared(skipped, cached, pending, formed)
  }

  /**
   * [subjects] batched breadth first: batches group by surface, so they are put back in the order
   * of their first subject's place in [PreviewGuidelineRequests.breadthFirst], and a second
   * surface's first variant is not left behind a first surface's later batches.
   */
  private fun breadthBatches(
    guidelines: CatalogGuidelinesV1,
    subjects: List<PreviewSubject>,
  ): List<GuidelineBatch> {
    val ordered = PreviewGuidelineRequests.breadthFirst(subjects)
    val rank = ordered.withIndex().associate { (index, subject) -> subject.previewId to index }
    return PreviewGuidelineRequests.batches(guidelines, ordered, options.budget).sortedBy { batch ->
      batch.subjects.minOf { rank[it.previewId] ?: Int.MAX_VALUE }
    }
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
                // A widget is never rendered past its host's largest font scale, whatever the
                // triage offer or the model asked for.
                val capped = need.cappedFor(subject)
                val already =
                  updated.pictures.any {
                    it.theme == capped.theme &&
                      it.fontScale == capped.fontScale &&
                      it.device == capped.device
                  }
                if (!already) {
                  host.render(subject.previewId, capped)?.let {
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
/** A run's subjects sorted before any is asked ([GuidelineEngine.prepare]). */
private class Prepared(
  val skipped: List<PreviewGuidelineResult>,
  val cached: List<PreviewGuidelineResult>,
  /** The subjects left to ask, as the caller passed them: what their results are cached under. */
  val pending: List<PreviewSubject>,
  val formed: List<GuidelineBatch>,
)

private data class QueuedBatch(val batch: GuidelineBatch, val triaged: Boolean, val key: String)

/** What a request in flight was expected to cost when it started, held against the cap. */
private class Reservation(val amount: Double, val predicted: Double? = null)

/** Whether one more request fits the cost cap now, may once a request in flight is back, or not. */
private enum class Fit {
  YES,
  WAIT,
  NO,
}

/** Why a request did not come back as verdicts, and what may still be done about it. */
internal enum class FailureKind {
  /** It may pass: no answer, 408, 5xx, a provider error. Asked again, then split. */
  TRANSIENT,
  /**
   * 429, 503 (no provider can take it now) or 402 from OpenRouter's in-flight budget: asked again
   * after the wait it named, which every request of the run waits out; asking about fewer subjects
   * would not help.
   */
  RATE_LIMITED,
  /** Our own timeout abandoned it: split at once when it has several subjects. */
  TIMEOUT,
  /** 413: too big to ask. Split, never asked again as it is. */
  TOO_LARGE,
  /** A reply with no usable verdict. Asked again at once, then split. */
  UNUSABLE,
  /**
   * The reply spent its token budget reasoning and left none for the answer. Asked once more with
   * reasoning off; never split, since each half would reason as much, and never counted against
   * [GuidelineRetry.maxFailedAttempts].
   */
  REASONING_EXHAUSTED,
  /**
   * The key's spending limit is reached (a 403 `Key limit exceeded`) or the account has no credit
   * (a 402 that is not the in-flight budget): every later request would be refused the same, so the
   * run stops asking and reports what it did not check as capped, not failed.
   */
  KEY_LIMIT,
  /** Nothing to retry: an invalid key (401), forbidden (403), a bad request. */
  FATAL,
  /** Left to be answered outside the run ([ModelResponse.DEFERRED]): pending, never retried. */
  DEFERRED,
}

/**
 * Whether an OpenRouter 403 [body] is the key's spending limit (`Key limit exceeded`), not a key
 * that is forbidden outright.
 */
internal fun keyLimit(body: String): Boolean =
  body.contains("limit exceeded", ignoreCase = true) ||
    body.contains("key limit", ignoreCase = true)

/** Whether an OpenRouter error [body] names its in-flight budget as the limit it hit. */
internal fun inFlightBudget(body: String): Boolean = body.contains(IN_FLIGHT_BUDGET)

/** `error.metadata.limit_source` when a 402 is the key's requests in flight, not its balance. */
internal const val IN_FLIGHT_BUDGET: String = "openrouter_in_flight_budget"

/** A request that failed, in the words the run's problems use. */
internal data class FailedRequest(val problem: String, val kind: FailureKind) {
  /** Whether asking about fewer subjects at once might succeed. */
  val splittable: Boolean
    get() =
      kind != FailureKind.FATAL &&
        kind != FailureKind.KEY_LIMIT &&
        kind != FailureKind.RATE_LIMITED &&
        kind != FailureKind.REASONING_EXHAUSTED &&
        kind != FailureKind.DEFERRED

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
      // OpenRouter's errors (openrouter.ai/docs/api-reference/errors): 408, 429, 502 and 503 may
      // pass; a 402 is retryable only when it is the in-flight budget, the estimated cost of the
      // key's requests running at once, rather than the balance or a key limit.
      val kind =
        when {
          status == 408 -> FailureKind.TRANSIENT
          status == 413 -> FailureKind.TOO_LARGE
          status == 429 || status == 503 -> FailureKind.RATE_LIMITED
          status == 402 && inFlightBudget(response.body) -> FailureKind.RATE_LIMITED
          status == 402 || (status == 403 && keyLimit(response.body)) -> FailureKind.KEY_LIMIT
          status in 500..599 -> FailureKind.TRANSIENT
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

/** [this] with its font scale held to [subject]'s [GuidelineSurfaces.maxFontScale]. */
internal fun GuidelineEvidenceNeedV1.cappedFor(subject: PreviewSubject): GuidelineEvidenceNeedV1 {
  val requested = fontScale ?: return this
  val cap = GuidelineSurfaces.maxFontScale(subject.surface, subject.profile) ?: return this
  if (requested <= cap) return this
  return newBuilder().apply { fontScale = cap }.build()
}
