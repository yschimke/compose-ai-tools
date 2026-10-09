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

  /** [previewId]'s accessibility nodes, or null when the host cannot get them. */
  public fun nodes(previewId: String): List<PreviewNode>? = null

  /** [previewId] rendered as [need] asks (theme, font scale, device), or null. */
  public fun render(previewId: String, need: GuidelineEvidenceNeedV1): SubjectPicture? = null

  /** [previewId]'s source, or null. */
  public fun source(previewId: String): String? = null

  /** A host with nothing to fetch. */
  public object None : GuidelineEvidenceHost {
    override val available: List<String> = emptyList()
  }
}

/** How a run behaves. */
public data class GuidelineRunOptions(
  val model: String = OpenRouterClient.DEFAULT_MODEL,
  val budget: GuidelineBudget = GuidelineBudget(),
  /** Follow-up rounds for `needs_evidence` verdicts; 0 asks once. */
  val maxRounds: Int = 1,
  val triage: Boolean = true,
  val triageThreshold: Double = 0.5,
  /** Stop asking once this many dollars are spent; what is left is reported unchecked. */
  val maxCostUsd: Double? = null,
  /** Where the rules came from, linked from each request's provenance. */
  val rulesSource: String = CatalogGuidelinesV1.FILE_NAME,
  val ranBy: String? = null,
)

/** A whole run: one result per subject, what it cost, and what went wrong on the way. */
public data class GuidelineRunResult(
  val results: List<PreviewGuidelineResult>,
  val costUsd: Double,
  val requests: Int,
  val problems: List<String>,
)

/**
 * Checks rendered previews against a catalog's guidelines.
 *
 * Previews whose render is unchanged are answered from [cache]. The rest go in batches
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
  public fun run(
    guidelines: CatalogGuidelinesV1,
    subjects: List<PreviewSubject>,
  ): GuidelineRunResult {
    val problems = mutableListOf<String>()
    val results = mutableListOf<PreviewGuidelineResult>()
    var spent = 0.0
    var requests = 0

    val pending = subjects.filter { subject ->
      val hit = cache?.get(subject.previewId, subject.renderHash, guidelines.version, options.model)
      if (hit != null) results += hit
      hit == null
    }

    for (batch0 in PreviewGuidelineRequests.batches(guidelines, pending, options.budget)) {
      if (options.maxCostUsd != null && spent >= options.maxCostUsd) {
        batch0.subjects.forEach { results += unchecked(guidelines, it) }
        problems += "the cost cap was reached; ${batch0.subjects.size} previews were not checked"
        continue
      }
      // Triage: fetch the evidence Jev expects to matter before the vision model sees the batch.
      var batch = batch0
      if (options.triage && host.available.isNotEmpty()) {
        val rulesSummary =
          guidelines.subjectRules(batch.surface).joinToString("\n") { "${it.id}: ${it.check}" }
        val reply = runCatching { model.decide(JevTriage.body(batch, rulesSummary)) }.getOrNull()
        if (reply != null && reply.status in 200..299) {
          val wants = JevTriage.wanted(reply.body, batch, threshold = options.triageThreshold)
          if (wants.isNotEmpty()) batch = withEvidence(batch, wants)
        }
      }

      val verdicts = mutableMapOf<String, MutableMap<String, GuidelineVerdictV1>>()
      val served = mutableListOf<GuidelineServed>()
      val setVerdicts = mutableListOf<GuidelineVerdictV1>()

      fun ask(target: GuidelineBatch, round: Int, onlyRules: Map<String, Set<String>>?): Boolean {
        val request =
          PreviewGuidelineRequests.request(
            guidelines,
            target,
            options.rulesSource,
            host.available,
            round,
            onlyRules,
          )
        val response = runCatching {
          model.complete(request, options.model)
        }
          .getOrElse {
            problems += "request failed: ${it.message}"
            return false
          }
        requests++
        if (response.status !in 200..299) {
          problems += "the model answered ${response.status}: ${response.body.take(200)}"
          return false
        }
        val order = request.pictures.map { (it.subjectId ?: "") to it.kind }
        val reply =
          GuidelineResponse.parse(response.body, target, order).getOrElse {
            problems += "unreadable reply: ${it.message}"
            return false
          }
        spent += reply.served.costUsd ?: 0.0
        served += reply.served
        reply.verdicts.forEach { verdict ->
          val subjectId = verdict.subjectId
          if (subjectId == null) setVerdicts += verdict
          else verdicts.getOrPut(subjectId) { mutableMapOf() }[verdict.ruleId] = verdict
        }
        return true
      }

      if (!ask(batch, 0, null)) {
        batch.subjects.forEach { results += unchecked(guidelines, it) }
        continue
      }

      // Evidence rounds: re-ask only the subjects and rules the model could not decide.
      var current = batch
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
        if (options.maxCostUsd != null && spent >= options.maxCostUsd) break
        val needs = undecided.mapValues { (_, list) -> list.flatMap { it.needs }.distinct() }
        val gathered = withEvidence(current, needs)
        val followUp =
          GuidelineBatch(
            gathered.surface,
            gathered.subjects.filter { it.previewId in undecided },
          )
        if (!ask(followUp, round, undecided.mapValues { (_, l) -> l.map { it.ruleId }.toSet() })) {
          break
        }
        current = gathered
      }

      val batchCost = served.sumOf { it.costUsd ?: 0.0 }
      val share = if (batch.subjects.isEmpty()) 0.0 else batchCost / batch.subjects.size
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
        results += result
        cache?.put(result, guidelines.version, options.model)
      }
    }
    return GuidelineRunResult(results, spent, requests, problems)
  }

  /** [batch] with the evidence in [needs] fetched from the host and attached to its subjects. */
  private fun withEvidence(
    batch: GuidelineBatch,
    needs: Map<String, List<GuidelineEvidenceNeedV1>>,
  ): GuidelineBatch =
    batch.copy(
      subjects =
        batch.subjects.map { subject ->
          val wanted = needs[subject.previewId] ?: return@map subject
          var updated = subject
          wanted.forEach { need ->
            when (need.kind) {
              GuidelineEvidenceNeedV1.KIND_A11Y_HIERARCHY,
              GuidelineEvidenceNeedV1.KIND_SEMANTICS ->
                if (updated.nodes.isEmpty()) {
                  host.nodes(subject.previewId)?.let { updated = updated.copy(nodes = it) }
                }
              GuidelineEvidenceNeedV1.KIND_SOURCE ->
                if (updated.source == null) {
                  host.source(subject.previewId)?.let { updated = updated.copy(source = it) }
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
    )
  }
}

/**
 * The findings in [result]: its `fail` verdicts at or above [minConfidence], most certain first.
 */
public fun PreviewGuidelineResult.failures(minConfidence: Double = 0.5): List<GuidelineVerdictV1> =
  record.verdicts
    .filter { it.verdict == GuidelineVerdictV1.FAIL && it.confidence >= minConfidence }
    .sortedByDescending { it.confidence }
