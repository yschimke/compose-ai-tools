package ee.schimke.composeai.guidelines

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * What an OpenRouter key may still spend (`GET /api/v1/key`): a run caps itself at [remainingUsd]
 * ([GuidelineRunOptions.keyStatus]) rather than learning it from a 403 per request. A key's limit
 * is the account's, not the model's: every model asked with the key draws on it.
 */
public data class OpenRouterKeyStatus(
  /** The key's spending limit in US dollars; null when it has none. */
  val limitUsd: Double?,
  /** What the key has spent, as OpenRouter counts it; null when it does not say. */
  val usageUsd: Double?,
  /** What is left of [limitUsd] in the current period; null when the key has no limit. */
  val remainingUsd: Double?,
  /** How often the limit resets (`daily`, `weekly`, `monthly`); null when it never does. */
  val reset: String?,
) {
  /** "the OpenRouter key has $0.12 left of its $5.00 weekly limit", or of its limit. */
  public fun describe(left: Double? = remainingUsd): String =
    "the OpenRouter key has \$" +
      "%.2f".format(left ?: 0.0) +
      " left of its " +
      (limitUsd?.let { "\$" + "%.2f".format(it) + " " } ?: "") +
      (reset?.let { "$it " } ?: "") +
      "limit"

  public companion object {
    private val JSON = Json { ignoreUnknownKeys = true }

    /**
     * [body], OpenRouter's answer to `GET /api/v1/key`, read field by field so a renamed or added
     * one costs nothing but itself; null when it is not that answer at all.
     */
    public fun parse(body: String): OpenRouterKeyStatus? {
      val root = runCatching { JSON.parseToJsonElement(body) }.getOrNull() as? JsonObject
      val data = (root?.get("data") ?: root) as? JsonObject ?: return null
      val limit = data.number("limit")
      val usage = data.number("usage")
      val reset = (data["limit_reset"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
      // `limit_remaining` is the current period's; usage counts every period, so without it only a
      // limit that never resets can be worked out.
      val remaining =
        data.number("limit_remaining")
          ?: if (limit != null && usage != null && reset == null) limit - usage else null
      if (limit == null && usage == null && remaining == null) return null
      return OpenRouterKeyStatus(limit, usage, remaining?.coerceAtLeast(0.0), reset)
    }

    private fun JsonObject.number(name: String): Double? =
      (get(name) as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
  }
}
