package ee.schimke.composeai.guidelines

import ee.schimke.composeai.guidelines.protocol.CatalogGuidelinesV1
import ee.schimke.composeai.guidelines.protocol.GuidelineVerdictV1

/**
 * EXPERIMENTAL, jev checker only: which of Jev's answers a vision model answers again
 * ([GuidelineRunOptions.withRecheck]). Jev answers every rule first; a rule it left unchecked, or
 * answered below [below], is asked again of [model] for that preview alone, and its verdict
 * replaces Jev's. Everything else keeps Jev's cheap answer.
 */
public data class GuidelineRecheck(
  /** The vision model that answers again: an OpenRouter id, as `--model` takes for vision. */
  val model: String,
  /** A pass, fail or not_applicable Jev gave below this probability is asked again. */
  val below: Double = DEFAULT_BELOW,
) {
  public companion object {
    /**
     * Where Jev's answer is taken: OpenRouter's Jev-verified cascade accepts at 0.8 and escalates
     * the rest. In a 40-render eval every wrong-leaning answer sat below it.
     */
    public const val DEFAULT_BELOW: Double = 0.8
  }
}

/**
 * The second pass of a jev run with a [GuidelineRecheck]: the vision engine, asked only the rules
 * Jev did not settle firmly, per preview, merged back into Jev's results. Vision results are cached
 * under the vision model's own key, so a recheck is not paid for twice.
 */
internal class JevRecheck(
  private val options: GuidelineRunOptions,
  private val recheck: GuidelineRecheck,
  /** A vision engine for these options: the caller's model, host and cache. */
  private val engine: (GuidelineRunOptions) -> GuidelineEngine,
) {
  fun run(
    guidelines: CatalogGuidelinesV1,
    subjects: List<PreviewSubject>,
    jev: GuidelineRunResult,
  ): GuidelineRunResult {
    val bySubject = subjects.associateBy { it.previewId }
    val wanted: Map<String, Set<String>> =
      jev.results
        .filter { it.noRules == null && !it.pending && it.variantOf == null }
        .associate { result ->
          result.previewId to
            result.record.verdicts.filter { recheckable(it) }.map { it.ruleId }.toSet()
        }
        .filterValues { it.isNotEmpty() }
    if (wanted.isEmpty()) return jev

    val visionOptions = options.forRecheck(recheck.model, jev.costUsd)
    // Previews wanting the same rules are asked together, of a catalog cut down to those rules.
    val runs =
      wanted.entries.groupBy({ it.value }, { it.key }).map { (rules, ids) ->
        val cut =
          guidelines
            .newBuilder()
            .apply {
              this.rules = guidelines.rules.filter { it.id in rules }
              includes = emptyList()
            }
            .build()
        engine(visionOptions).run(cut, ids.mapNotNull { bySubject[it] })
      }
    val visionById = runs.flatMap { it.results }.associateBy { it.previewId }

    var asked = 0
    var changed = 0
    val results =
      jev.results.map { result ->
        val rules = wanted[result.previewId] ?: return@map result
        val vision = visionById[result.previewId] ?: return@map result
        val answers = vision.record.verdicts.associateBy { it.ruleId }
        asked += rules.size
        val verdicts =
          result.record.verdicts.map { verdict ->
            val again = answers[verdict.ruleId]?.takeIf { verdict.ruleId in rules }
            if (again == null) verdict
            else {
              if (again.verdict != verdict.verdict) changed++
              again
                .newBuilder()
                .apply {
                  reason =
                    "Rechecked by ${recheck.model} (Jev: ${describe(verdict)}): " +
                      (again.reason ?: "")
                }
                .build()
            }
          }
        val cost = (result.record.costUsd ?: 0.0) + (vision.record.costUsd ?: 0.0)
        result
          .copy(
            record =
              result.record
                .newBuilder()
                .apply {
                  this.verdicts = verdicts
                  costUsd = cost.takeIf { it > 0.0 }
                }
                .build(),
            unchecked =
              result.record.asked.filter { id ->
                val v = verdicts.firstOrNull { it.ruleId == id }
                v == null || v.verdict == GuidelineVerdictV1.NEEDS_EVIDENCE
              },
          )
          .also {
            it.noRules = result.noRules
            it.implicitPasses = result.implicitPasses
            it.variantOf = result.variantOf
            it.jev = result.jev
          }
      }

    val stillUnchecked =
      results.filter { it.noRules == null && !it.pending }.sumOf { it.unchecked.size }
    val problems =
      jev.problems.filterNot { it.contains(UNCHECKED_LINE) } +
        ("$asked rule verdict(s) on ${wanted.size} preview(s) Jev left unchecked or answered " +
          "below p ${recheck.below} were asked again of ${recheck.model}: $changed changed, " +
          "$stillUnchecked still unchecked") +
        runs.flatMap { it.problems }.map { "recheck: $it" }
    return GuidelineRunResult(
        results = results,
        costUsd = jev.costUsd + runs.sumOf { it.costUsd },
        requests = jev.requests + runs.sumOf { it.requests },
        problems = problems,
        failedRequests = jev.failedRequests + runs.sumOf { it.failedRequests },
      )
      .also {
        it.failedEvidence = jev.failedEvidence + runs.sumOf { r -> r.failedEvidence }
        it.completionTokens = jev.completionTokens + runs.sumOf { r -> r.completionTokens }
        it.reasoningTokens = jev.reasoningTokens + runs.sumOf { r -> r.reasoningTokens }
        it.reasoningRetries = jev.reasoningRetries + runs.sumOf { r -> r.reasoningRetries }
      }
  }

  private fun recheckable(verdict: GuidelineVerdictV1): Boolean =
    verdict.verdict == GuidelineVerdictV1.NEEDS_EVIDENCE || verdict.confidence < recheck.below

  private fun describe(verdict: GuidelineVerdictV1): String =
    if (verdict.verdict == GuidelineVerdictV1.NEEDS_EVIDENCE) "undecided"
    else "${verdict.verdict} p ${String.format(java.util.Locale.ROOT, "%.2f", verdict.confidence)}"

  private companion object {
    /** The jev run's count of what it left unchecked: stale once rechecked, so restated. */
    const val UNCHECKED_LINE = "were left unchecked ("
  }
}
