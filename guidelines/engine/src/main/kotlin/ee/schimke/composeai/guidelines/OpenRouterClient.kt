package ee.schimke.composeai.guidelines

import ee.schimke.composeai.guidelines.protocol.GuidelineRequestV1
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** What a guidelines model call answers: the HTTP status and body, as text. */
public data class ModelResponse(val status: Int, val body: String)

/**
 * The model a guidelines check asks. [OpenRouterClient] is the real one; tests and hosts with their
 * own transport implement this.
 */
public interface GuidelineModel {
  /** The chat completion for [request], asked of [model]. */
  public fun complete(request: GuidelineRequestV1, model: String): ModelResponse

  /** A decisions call (Jev), with [body] as OpenRouter's decisions API takes it. */
  public fun decide(body: JsonObject): ModelResponse =
    ModelResponse(501, "{\"error\":\"no decisions endpoint\"}")
}

/**
 * OpenRouter: chat completions for the verdicts and the decisions endpoint for Jev triage. Sends
 * `X-OpenRouter-Metadata: enabled`, so a routed model's choice comes back to be recorded. The
 * [apiKey] is never logged; a 429 or 5xx is retried once.
 */
public class OpenRouterClient(
  private val apiKey: String,
  private val http: OkHttpClient = DEFAULT_HTTP,
  private val baseUrl: String = "https://openrouter.ai",
  private val title: String = "compose-preview guidelines",
) : GuidelineModel {
  override fun complete(request: GuidelineRequestV1, model: String): ModelResponse =
    post("$baseUrl/api/v1/chat/completions", chatBody(request, model).toString())

  override fun decide(body: JsonObject): ModelResponse =
    post("$baseUrl/api/alpha/decisions", body.toString(), retry = false)

  private fun post(url: String, body: String, retry: Boolean = true): ModelResponse {
    var attempt = 0
    while (true) {
      attempt++
      val response =
        try {
          http
            .newCall(
              Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .header("X-Title", title)
                .header("X-OpenRouter-Metadata", "enabled")
                .post(body.toRequestBody(JSON))
                .build()
            )
            .execute()
            .use { ModelResponse(it.code, it.body.string()) }
        } catch (e: IOException) {
          ModelResponse(0, "{\"error\":{\"message\":\"${e.message?.replace("\"", "'")}\"}}")
        }
      val transient = response.status == 0 || response.status == 429 || response.status >= 500
      if (!transient || !retry || attempt >= 2) return response
      Thread.sleep(2_000)
    }
  }

  public companion object {
    /** The model a guidelines check asks unless told otherwise. */
    public const val DEFAULT_MODEL: String = "deepseek/deepseek-v4.1-flash"

    /** The chat-completions body for [request]: the text first, then every picture in order. */
    public fun chatBody(request: GuidelineRequestV1, model: String): JsonObject = buildJsonObject {
      put("model", model)
      put("temperature", 0)
      putJsonArray("messages") {
        add(
          buildJsonObject {
            put("role", "system")
            put("content", request.systemPrompt)
          }
        )
        add(
          buildJsonObject {
            put("role", "user")
            put(
              "content",
              buildJsonArray {
                add(
                  buildJsonObject {
                    put("type", "text")
                    put("text", request.userText)
                  }
                )
                request.pictures.forEach { picture ->
                  val url = picture.dataUrl ?: return@forEach
                  add(
                    buildJsonObject {
                      put("type", "image_url")
                      putJsonObject("image_url") { put("url", url) }
                    }
                  )
                }
              },
            )
          }
        )
      }
      putJsonObject("response_format") {
        put("type", "json_schema")
        putJsonObject("json_schema") {
          put("name", "guideline_verdicts")
          put("strict", true)
          put("schema", request.responseSchema)
        }
      }
    }

    private val JSON = "application/json".toMediaType()

    private val DEFAULT_HTTP: OkHttpClient =
      OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(240, TimeUnit.SECONDS)
        .callTimeout(300, TimeUnit.SECONDS)
        .build()
  }
}
