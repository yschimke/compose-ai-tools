package ee.schimke.composeai.guidelines

import ee.schimke.composeai.guidelines.protocol.GuidelineEvidenceNeedV1
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * A cheap pre-pass that asks Jev, TypeSafe's decision model, which extra evidence each subject
 * needs before the vision model judges it — a dark-theme render, a large-font render, its
 * accessibility nodes — so a host draws them only where they would change an answer.
 *
 * Jev is text only (it never sees a picture), answers typed probabilities, and costs a few
 * hundredths of a cent; it is never asked for verdicts. Fail-soft: any error is "nothing wanted".
 */
public object JevTriage {
  public const val MODEL: String = "typesafe/jev-1.13"

  /** One kind of evidence Jev is asked about, and the need it becomes when wanted. */
  public data class Offer(val key: String, val question: String, val need: GuidelineEvidenceNeedV1)

  public val DARK_THEME: Offer =
    Offer(
      "dark_theme",
      "Would a render in the dark theme be needed to judge this subject's colour, contrast or " +
        "background rules?",
      GuidelineEvidenceNeedV1.Builder(GuidelineEvidenceNeedV1.KIND_RENDER)
        .apply {
          theme = "dark"
          reason = "triage: colour and contrast in dark theme"
        }
        .build(),
    )

  public val LARGE_FONT: Offer =
    Offer(
      "large_font",
      "Would a render at a large font scale be needed to judge whether this subject's text " +
        "truncates, overlaps or clips?",
      GuidelineEvidenceNeedV1.Builder(GuidelineEvidenceNeedV1.KIND_RENDER)
        .apply {
          fontScale = 1.5
          reason = "triage: text at a large font scale"
        }
        .build(),
    )

  public val A11Y: Offer =
    Offer(
      "a11y",
      "Would this subject's accessibility nodes (labels, roles, tap-target bounds) be needed to " +
        "judge its rules?",
      GuidelineEvidenceNeedV1.Builder(GuidelineEvidenceNeedV1.KIND_A11Y_HIERARCHY)
        .apply { reason = "triage: labels, roles and tap targets" }
        .build(),
    )

  public val DEFAULT_OFFERS: List<Offer> = listOf(DARK_THEME, LARGE_FONT, A11Y)

  /** The decisions request: one yes/no per (subject, offer), keyed `<alias>__<offer>`. */
  public fun body(
    batch: GuidelineBatch,
    rulesSummary: String,
    offers: List<Offer> = DEFAULT_OFFERS,
    model: String = MODEL,
  ): JsonObject = buildJsonObject {
    put("model", model)
    putJsonObject("state") {
      put(
        "task",
        "Decide which extra evidence would help a reviewer judge each rendered UI preview " +
          "against these guidelines. The reviewer already has one default render of each.",
      )
      put("guidelines", rulesSummary.take(MAX_STATE_CHARS / 2))
      put(
        "subjects",
        batch.subjects
          .joinToString("\n") { subject ->
            val alias = batch.aliases.getValue(subject.previewId)
            "$alias: ${subject.label}" +
              (if (subject.nodes.isEmpty()) " (no accessibility nodes yet)" else "") +
              subject.nodes.take(30).joinToString(prefix = " nodes: ") {
                "${it.role ?: "-"} '${it.label.take(30)}'"
              }
          }
          .take(MAX_STATE_CHARS / 2),
      )
    }
    putJsonObject("questions") {
      batch.subjects.forEach { subject ->
        val alias = batch.aliases.getValue(subject.previewId)
        offers.forEach { offer ->
          putJsonObject("${alias}__${offer.key}") {
            put("type", "noul")
            put("instructions", "For $alias: ${offer.question}")
          }
        }
      }
    }
  }

  /**
   * The needs Jev wanted, by preview id: offers it gave at least [threshold]. An accessibility
   * offer is dropped for a subject that already has its nodes.
   */
  public fun wanted(
    body: String,
    batch: GuidelineBatch,
    offers: List<Offer> = DEFAULT_OFFERS,
    threshold: Double = 0.5,
  ): Map<String, List<GuidelineEvidenceNeedV1>> = runCatching {
    val answers =
      GUIDELINES_JSON.parseToJsonElement(body).jsonObject["answers"] as? JsonObject
        ?: return@runCatching emptyMap()
    batch.subjects
      .associate { subject ->
        val alias = batch.aliases.getValue(subject.previewId)
        subject.previewId to
          offers
            .filter { offer ->
              val p =
                ((answers["${alias}__${offer.key}"] as? JsonObject)?.get("noul") as? JsonPrimitive)
                  ?.doubleOrNull ?: 0.0
              p >= threshold && !(offer == A11Y && subject.nodes.isNotEmpty())
            }
            .map { it.need }
      }
      .filterValues { it.isNotEmpty() }
  }
    .getOrDefault(emptyMap())

  /** Jev's prompt cap is 32k tokens; the state stays well under it. */
  private const val MAX_STATE_CHARS = 80_000
}
