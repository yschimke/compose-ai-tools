package ee.schimke.composeai.guidelines

import java.io.IOException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * OpenRouter's model list (`GET /api/v1/models`, no key needed), reduced to what choosing a
 * guidelines model needs: whether it reads pictures, whether it honours a strict reply schema, and
 * what a million tokens each way cost. Prices are OpenRouter's own, in US dollars.
 */
public object OpenRouterModels {
  /** One model, as the list describes it. */
  public data class Model(
    val id: String,
    val name: String,
    val contextLength: Long?,
    /** US dollars per million input tokens, or null when the list gives none. */
    val promptPerMillion: Double?,
    /** US dollars per million output tokens, or null when the list gives none. */
    val completionPerMillion: Double?,
    /** Takes image input, which the vision checker needs for its pictures. */
    val vision: Boolean,
    /** Honours `response_format` with a JSON schema (`structured_outputs`). */
    val structuredOutputs: Boolean,
  ) {
    /** Usable by `compose-preview guidelines --model`: reads pictures and keeps to the schema. */
    val suitable: Boolean
      get() = vision && structuredOutputs
  }

  /** The models in a `/api/v1/models` [body]; entries it cannot read are left out. */
  public fun parse(body: String): List<Model> {
    val data = GUIDELINES_JSON.parseToJsonElement(body).jsonObject["data"] as? JsonArray
    return data.orEmpty().mapNotNull { element ->
      val item = element as? JsonObject ?: return@mapNotNull null
      val id = item.text("id") ?: return@mapNotNull null
      val architecture = item["architecture"] as? JsonObject
      val inputs =
        (architecture?.get("input_modalities") as? JsonArray)
          ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
          .orEmpty()
      val parameters =
        (item["supported_parameters"] as? JsonArray)
          ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
          .orEmpty()
      val pricing = item["pricing"] as? JsonObject
      Model(
        id = id,
        name = item.text("name") ?: id,
        contextLength = (item["context_length"] as? JsonPrimitive)?.longOrNull,
        promptPerMillion = pricing?.perMillion("prompt"),
        completionPerMillion = pricing?.perMillion("completion"),
        vision = "image" in inputs,
        structuredOutputs = "structured_outputs" in parameters,
      )
    }
  }

  /** The list, fetched from [baseUrl]; throws [IOException] when it cannot be read. */
  public fun fetch(
    http: OkHttpClient = OpenRouterClient.httpClient(),
    baseUrl: String = "https://openrouter.ai",
  ): List<Model> {
    val request = Request.Builder().url("$baseUrl/api/v1/models").get().build()
    http.newCall(request).execute().use { response ->
      if (!response.isSuccessful) throw IOException("the model list answered ${response.code}")
      return parse(response.body.string())
    }
  }

  private fun JsonObject.text(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull

  /** A per-token price string (`"0.0000003"`) as dollars per million tokens. */
  private fun JsonObject.perMillion(name: String): Double? =
    ((get(name) as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
        ?: (get(name) as? JsonPrimitive)?.doubleOrNull)
      ?.takeIf { it >= 0 }
      ?.let { it * 1_000_000 }
}
