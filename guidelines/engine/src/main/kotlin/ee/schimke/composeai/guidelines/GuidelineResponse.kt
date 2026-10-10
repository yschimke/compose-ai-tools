package ee.schimke.composeai.guidelines

import ee.schimke.composeai.guidelines.protocol.GuidelineEvidenceNeedV1
import ee.schimke.composeai.guidelines.protocol.GuidelineRegionV1
import ee.schimke.composeai.guidelines.protocol.GuidelineRoutingV1
import ee.schimke.composeai.guidelines.protocol.GuidelineVerdictV1
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** Which model answered a request, and how it was chosen, as OpenRouter reports it. */
public data class GuidelineServed(
  val model: String? = null,
  val provider: String? = null,
  val costUsd: Double? = null,
  val generationId: String? = null,
  val routing: GuidelineRoutingV1? = null,
)

/** A model's answer to one batched request, mapped back to preview ids. */
public data class GuidelineReply(
  /** Each verdict carries the regions it points at ([GuidelineVerdictV1.regions]). */
  val verdicts: List<GuidelineVerdictV1>,
  val served: GuidelineServed,
) {
  /**
   * The verdicts left out of [verdicts] because their `subjectId` named no subject of the batch, as
   * `subjectId/ruleId`. A body property, so the constructor and `copy` keep their ABI.
   */
  public var strays: List<String> = emptyList()
    internal set
}

/** Reading a chat completion's answer to a [PreviewGuidelineRequests.request]. */
public object GuidelineResponse {
  /**
   * The verdicts in completion [body] for [batch]. Subject aliases (`s1`) become preview ids; a
   * region's picture number becomes its subject and picture kind, using [pictureOrder] — the
   * request's pictures as (preview id, kind), in order.
   */
  public fun parse(
    body: String,
    batch: GuidelineBatch,
    pictureOrder: List<Pair<String, String>>,
  ): Result<GuidelineReply> = runCatching {
    val completion = GUIDELINES_JSON.parseToJsonElement(body).jsonObject
    val content =
      ((completion["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)
        ?.get("message")
        ?.let { it as? JsonObject }
        ?.get("content")
        ?.let { (it as? JsonPrimitive)?.contentOrNull }
        ?: error("no message content in the completion")
    val start = content.indexOf('{')
    val end = content.lastIndexOf('}')
    require(start >= 0 && end > start) { "no JSON object in: ${content.take(200)}" }
    val root = GUIDELINES_JSON.parseToJsonElement(content.substring(start, end + 1)).jsonObject
    val byAlias = batch.aliases.entries.associate { (id, alias) -> alias to id }
    val verdicts = mutableListOf<GuidelineVerdictV1>()
    val strays = mutableListOf<String>()
    (root["verdicts"] as? JsonArray).orEmpty().forEach { element ->
      val item = element as? JsonObject ?: return@forEach
      val ruleId = item.text("ruleId") ?: return@forEach
      val verdict = item.text("verdict") ?: return@forEach
      val alias = item.text("subjectId")
      val subjectId = alias?.let { byAlias[it] ?: it.takeIf { id -> id in batch.aliases } }
      if (alias != null && subjectId == null) {
        strays += "$alias/$ruleId"
        return@forEach
      }
      val regions =
        (item["regions"] as? JsonArray).orEmpty().mapNotNull { region ->
          val r = region as? JsonObject ?: return@mapNotNull null
          val picture = (r["picture"] as? JsonPrimitive)?.intOrNull
          val drawnOn = picture?.let { pictureOrder.getOrNull(it - 1) }
          val box =
            listOf("x", "y", "width", "height").map {
              (r[it] as? JsonPrimitive)?.doubleOrNull?.coerceIn(0.0, 1.0)
            }
          if (box.any { it == null }) return@mapNotNull null
          GuidelineRegionV1.Builder(box[0]!!, box[1]!!, box[2]!!, box[3]!!)
            .apply {
              this.subjectId = drawnOn?.first ?: subjectId
              pictureKind = drawnOn?.second
              label = r.text("label")
            }
            .build()
        }
      verdicts +=
        GuidelineVerdictV1.Builder(ruleId, verdict)
          .apply {
            this.subjectId = subjectId
            confidence = (item["confidence"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
            nodeIds =
              (item["nodeIds"] as? JsonArray).orEmpty().mapNotNull {
                (it as? JsonPrimitive)?.contentOrNull
              }
            reason = item.text("reason").orEmpty()
            needs =
              (item["needs"] as? JsonArray).orEmpty().mapNotNull { need ->
                val n = need as? JsonObject ?: return@mapNotNull null
                val kind = n.text("kind") ?: return@mapNotNull null
                GuidelineEvidenceNeedV1.Builder(kind)
                  .apply {
                    theme = n.text("theme")
                    fontScale = (n["fontScale"] as? JsonPrimitive)?.doubleOrNull
                    device = n.text("device")
                    reason = n.text("reason").orEmpty()
                  }
                  .build()
              }
            this.regions = regions
          }
          .build()
    }
    if (verdicts.isEmpty()) {
      error(
        "the reply held no verdicts" +
          (if (strays.isEmpty()) "" else " for this batch's subjects (it named ${strays.take(5)})")
      )
    }
    GuidelineReply(verdicts, served(completion)).also { it.strays = strays }
  }

  /**
   * What the completion [body] cost, whether or not its answer can be read: a reply that held no
   * usable verdict was still paid for. Null when the body is not a completion or names no cost.
   */
  internal fun cost(body: String): Double? = runCatching {
    ((GUIDELINES_JSON.parseToJsonElement(body).jsonObject["usage"] as? JsonObject)?.get("cost")
        as? JsonPrimitive)
      ?.doubleOrNull
  }
    .getOrNull()
    ?.takeIf { it.isFinite() && it > 0.0 }

  /** The served model, provider, cost, id and routing in a completion body. */
  public fun served(completion: JsonObject): GuidelineServed {
    val model = completion.text("model")
    val routing =
      ((completion["openrouter_metadata"] as? JsonObject)?.get("pipeline") as? JsonArray)
        ?.mapNotNull { it as? JsonObject }
        ?.firstOrNull { it.text("name") == "jev-router" }
        ?.let { stage ->
          val data = stage["data"] as? JsonObject ?: return@let null
          val probability =
            (data["selection_probabilities"] as? JsonArray)
              ?.mapNotNull { it as? JsonObject }
              ?.firstOrNull { candidate ->
                val id = candidate.text("model")
                model != null && id != null && (id == model || id.startsWith("$model-"))
              }
              ?.let { (it["probability"] as? JsonPrimitive)?.doubleOrNull }
          GuidelineRoutingV1.Builder("typesafe/jev-router")
            .apply {
              version = data.text("version")
              reason = data.text("reason")
              this.probability = probability
              scores =
                (data["answers"] as? JsonObject)
                  ?.mapNotNull { (key, value) ->
                    ((value as? JsonObject)?.get("noul") as? JsonPrimitive)?.doubleOrNull?.let {
                      key to it
                    }
                  }
                  ?.toMap()
                  .orEmpty()
            }
            .build()
        }
    return GuidelineServed(
      model = model,
      provider = completion.text("provider"),
      costUsd = ((completion["usage"] as? JsonObject)?.get("cost") as? JsonPrimitive)?.doubleOrNull,
      generationId = completion.text("id"),
      routing = routing,
    )
  }

  private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
}
