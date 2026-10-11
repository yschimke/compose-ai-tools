package ee.schimke.composeai.guidelines

import ee.schimke.composeai.guidelines.protocol.GuidelineRequestV1
import java.io.IOException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * A plain OpenAI-style chat-completions endpoint: OpenAI itself, Gemini's OpenAI-compatible
 * endpoint, a local Ollama, LM Studio or vLLM server, or a LiteLLM proxy in front of anything else.
 * [baseUrl] is everything before `/chat/completions`, version path included
 * (`https://api.openai.com/v1`, `http://localhost:11434/v1`).
 *
 * The request is [OpenRouterClient.chatBody] without what only OpenRouter reads (provider routing,
 * its `reasoning` object, streaming), so the prompt and reply schema are the same ones. What
 * OpenRouter adds is missing here, and a run says so rather than pretending:
 * - no cost in the reply, so a cost cap has nothing to count;
 * - no decisions endpoint, so no Jev triage ([decide] answers 501);
 * - a server that refuses the strict JSON schema is asked once more with `json_object`, and the
 *   engine's reply checks hold the answer to the schema instead.
 *
 * [apiKey] is null for a local server that takes none. It is never logged. Thread-safe.
 */
public class OpenAiCompatibleClient(
  baseUrl: String,
  private val apiKey: String?,
  private val http: OkHttpClient = OpenRouterClient.httpClient(),
) : GuidelineModel {
  private val url = baseUrl.trim().trimEnd('/') + "/chat/completions"

  /** OpenAI's own API wants `max_completion_tokens`; everything else still reads `max_tokens`. */
  private val completionTokensName =
    if (java.net.URI(url).host?.lowercase() == "api.openai.com") "max_completion_tokens"
    else "max_tokens"

  /** Set once the server refuses the strict reply schema: later requests ask for `json_object`. */
  @Volatile
  public var schemaRefused: Boolean = false
    private set

  override fun complete(request: GuidelineRequestV1, model: String): ModelResponse {
    if (!schemaRefused) {
      val strict = post(body(request, model, strictSchema = true))
      if (!refusesSchema(strict)) return strict
      schemaRefused = true
    }
    return post(body(request, model, strictSchema = false))
  }

  internal fun body(request: GuidelineRequestV1, model: String, strictSchema: Boolean): String {
    val chat =
      OpenRouterClient.chatBody(request, model, reasoningEffort = null).let { full ->
        JsonObject(full - "provider")
      }
    return buildJsonObject {
      chat.forEach { (key, value) ->
        when (key) {
          "max_tokens" -> put(completionTokensName, value)
          "response_format" ->
            if (strictSchema) put(key, value)
            else put(key, buildJsonObject { put("type", "json_object") })
          else -> put(key, value)
        }
      }
    }
      .toString()
  }

  private fun post(body: String): ModelResponse =
    try {
      val builder = Request.Builder().url(url).post(body.toRequestBody(JSON))
      apiKey?.let { builder.header("Authorization", "Bearer $it") }
      http.newCall(builder.build()).execute().use { response ->
        ModelResponse(response.code, response.body.string()).also {
          it.retryAfterMillis = OpenRouterClient.retryAfterMillis(response.header("Retry-After"))
        }
      }
    } catch (e: IOException) {
      OpenRouterClient.noAnswer(e, http)
    }

  private fun refusesSchema(response: ModelResponse): Boolean =
    response.status in setOf(400, 422) &&
      SCHEMA_WORDS.any { response.body.contains(it, ignoreCase = true) }

  public companion object {
    private val JSON = "application/json".toMediaType()
    private val SCHEMA_WORDS = listOf("response_format", "json_schema", "schema")

    /** Whether [baseUrl] is this machine, where a server commonly takes no key. */
    public fun isLoopback(baseUrl: String): Boolean =
      runCatching { java.net.URI(baseUrl.trim()).host?.lowercase() }
        .getOrNull()
        ?.let { it == "localhost" || it == "127.0.0.1" || it == "::1" || it == "[::1]" } ?: false
  }
}
