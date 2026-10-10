package ee.schimke.composeai.cli

import ee.schimke.composeai.guidelines.PreviewGuidelineResult
import ee.schimke.composeai.guidelines.protocol.GuidelineVerdictV1
import java.io.File
import java.util.Locale
import kotlinx.serialization.builtins.ListSerializer

/**
 * `guidelines --compare-with <guidelines.json>`: this run's verdicts set against another run's, per
 * (preview, rule), so the experimental jev checker can be evaluated against a vision run of the
 * same previews (or any two runs against each other). Reads only the two reports; asks nothing.
 */
internal object GuidelinesComparison {
  /** How one (preview, rule) compares. "Decided" is pass, fail or not_applicable. */
  enum class Outcome(val label: String) {
    AGREE("agree"),
    DISAGREE("disagree"),
    /** Decided here, unchecked there. */
    ONLY_HERE("decided here only"),
    /** Unchecked here, decided there: what this run could not judge. */
    ONLY_THERE("decided there only"),
    NEITHER("unchecked in both"),
  }

  data class Row(
    val previewId: String,
    val ruleId: String,
    val here: String,
    val hereConfidence: Double?,
    val there: String,
    val thereConfidence: Double?,
  ) {
    val outcome: Outcome
      get() {
        val a = here in DECIDED
        val b = there in DECIDED
        return when {
          a && b -> if (here == there) Outcome.AGREE else Outcome.DISAGREE
          a -> Outcome.ONLY_HERE
          b -> Outcome.ONLY_THERE
          else -> Outcome.NEITHER
        }
      }
  }

  data class Comparison(
    val rows: List<Row>,
    val onlyHere: List<String>,
    val onlyThere: List<String>,
  )

  private val DECIDED =
    setOf(GuidelineVerdictV1.PASS, GuidelineVerdictV1.FAIL, GuidelineVerdictV1.NOT_APPLICABLE)

  /** The reports in [file]: one module's `guidelines.json`, or the list `--json` prints. */
  fun load(file: File): List<ModuleGuidelines>? {
    if (!file.isFile) return null
    val text = file.readText()
    return runCatching {
      listOf(GuidelinesCommand.REPORT_JSON.decodeFromString(ModuleGuidelines.serializer(), text))
    }
      .recoverCatching {
        GuidelinesCommand.REPORT_JSON.decodeFromString(
          ListSerializer(ModuleGuidelines.serializer()),
          text,
        )
      }
      .getOrNull()
  }

  /** What [result] says of [ruleId]: a decided verdict, or `unchecked`. */
  private fun verdictOf(result: PreviewGuidelineResult, ruleId: String): Pair<String, Double?> {
    if (result.pending || ruleId in result.unchecked) return UNCHECKED to null
    val verdict =
      result.record.verdicts.firstOrNull {
        it.ruleId == ruleId && (it.subjectId == null || it.subjectId == result.previewId)
      } ?: return UNCHECKED to null
    if (verdict.verdict !in DECIDED) return UNCHECKED to null
    return verdict.verdict to verdict.confidence
  }

  private const val UNCHECKED = "unchecked"

  fun compare(here: List<PreviewGuidelineResult>, there: List<PreviewGuidelineResult>): Comparison {
    val mine = here.filter { it.noRules == null }.associateBy { it.previewId }
    val theirs = there.filter { it.noRules == null }.associateBy { it.previewId }
    val both = mine.keys.filter { it in theirs }.sorted()
    val rows = both.flatMap { id ->
      val a = mine.getValue(id)
      val b = theirs.getValue(id)
      (a.record.asked + b.record.asked).distinct().sorted().map { rule ->
        val (h, hc) = verdictOf(a, rule)
        val (t, tc) = verdictOf(b, rule)
        Row(id, rule, h, hc, t, tc)
      }
    }
    return Comparison(
      rows,
      onlyHere = (mine.keys - theirs.keys).sorted(),
      onlyThere = (theirs.keys - mine.keys).sorted(),
    )
  }

  /** [comparison] as text: totals, a line per rule, and every disagreement. */
  fun render(comparison: Comparison, hereLabel: String, thereLabel: String): String = buildString {
    val rows = comparison.rows
    val counts = rows.groupingBy { it.outcome }.eachCount()
    fun n(outcome: Outcome) = counts[outcome] ?: 0
    appendLine("Comparison: $hereLabel (here) vs $thereLabel (there)")
    appendLine(
      "  previews in both: ${rows.map { it.previewId }.distinct().size}" +
        " (${comparison.onlyHere.size} only here, ${comparison.onlyThere.size} only there)"
    )
    appendLine(
      "  rule verdicts: ${rows.size} — " +
        Outcome.entries.joinToString(", ") { "${it.label} ${n(it)}" }
    )
    val decidedBoth = n(Outcome.AGREE) + n(Outcome.DISAGREE)
    if (decidedBoth > 0) {
      appendLine(
        "  agreement where both decided: " +
          String.format(Locale.ROOT, "%.1f%%", 100.0 * n(Outcome.AGREE) / decidedBoth) +
          " ($decidedBoth verdicts)"
      )
    }
    val failsThere = rows.count { it.there == GuidelineVerdictV1.FAIL }
    if (failsThere > 0) {
      val found = rows.count { it.there == GuidelineVerdictV1.FAIL && it.here == it.there }
      appendLine("  findings there also found here: $found of $failsThere")
    }
    if (rows.isNotEmpty()) {
      appendLine("  by rule (agree / disagree / decided here only / decided there only / neither):")
      rows
        .groupBy { it.ruleId }
        .toSortedMap()
        .forEach { (rule, list) ->
          val c = list.groupingBy { it.outcome }.eachCount()
          appendLine(
            "    $rule: " + Outcome.entries.joinToString(" / ") { (c[it] ?: 0).toString() }
          )
        }
    }
    val disagreements = rows.filter { it.outcome == Outcome.DISAGREE }
    if (disagreements.isNotEmpty()) {
      appendLine("  disagreements:")
      disagreements.forEach { row ->
        appendLine(
          "    ${row.previewId} ${row.ruleId}: here ${shown(row.here, row.hereConfidence)}, " +
            "there ${shown(row.there, row.thereConfidence)}"
        )
      }
    }
  }

  private fun shown(verdict: String, confidence: Double?): String =
    confidence?.let { verdict + String.format(Locale.ROOT, " (%.2f)", it) } ?: verdict
}
