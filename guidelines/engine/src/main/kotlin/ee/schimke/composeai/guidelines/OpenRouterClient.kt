package ee.schimke.composeai.guidelines

import ee.schimke.composeai.guidelines.protocol.GuidelineRequestV1
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
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

/**
 * What a guidelines model call answers: the HTTP status and body, as text.
 *
 * A [status] of [NO_ANSWER] means no HTTP answer arrived at all — the transport gave up (a timeout,
 * a refused or reset connection) — and [transportError] says which; it is not a status the server
 * sent.
 */
public data class ModelResponse(val status: Int, val body: String) {
  /**
   * How long the server asked to be left alone before the next try (its `Retry-After`), when it
   * said. A body property, so the constructor and `copy` keep their ABI.
   */
  public var retryAfterMillis: Long? = null

  /** Why no answer arrived, when [status] is [NO_ANSWER]: which timeout passed, or what failed. */
  public var transportError: String? = null

  /**
   * Whether the caller's own timeout abandoned the request ([status] [NO_ANSWER]). Asking the same
   * request again is likely to take as long again; asking about fewer subjects is not.
   */
  public var timedOut: Boolean = false

  public companion object {
    /** The [status] of a request that got no HTTP answer at all. */
    public const val NO_ANSWER: Int = 0

    /** A request that got no answer, for [reason]; [timedOut] when a timeout gave up on it. */
    public fun noAnswer(reason: String, timedOut: Boolean = false): ModelResponse =
      ModelResponse(
          NO_ANSWER,
          buildJsonObject { putJsonObject("error") { put("message", reason) } }.toString(),
        )
        .also {
          it.transportError = reason
          it.timedOut = timedOut
        }
  }
}

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
 * [apiKey] is never logged.
 *
 * Each call is made once. Retrying is [GuidelineEngine]'s, which knows the cost cap and can ask
 * about fewer subjects instead; a request that got no answer comes back as
 * [ModelResponse.NO_ANSWER] with what gave up on it, and a `Retry-After` as
 * [ModelResponse.retryAfterMillis]. [http]'s call timeout bounds one request ([httpClient] builds
 * one with another).
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
    post("$baseUrl/api/alpha/decisions", body.toString())

  private fun post(url: String, body: String): ModelResponse =
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
        .use { response ->
          ModelResponse(response.code, response.body.string()).also {
            it.retryAfterMillis = retryAfterMillis(response.header("Retry-After"))
          }
        }
    } catch (e: IOException) {
      noAnswer(e, http)
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

    /** How long one request may take, start to end, unless the host says otherwise. */
    public val DEFAULT_REQUEST_TIMEOUT: Duration = Duration.ofSeconds(300)

    /**
     * An HTTP client whose calls give up after [requestTimeout], start to end: the bound on one
     * guidelines request. The read timeout is the same, not shorter: OpenRouter keeps a slow
     * completion's connection alive with whitespace while the model is still writing, so bytes keep
     * arriving and the whole call is what has to be bounded.
     */
    public fun httpClient(requestTimeout: Duration = DEFAULT_REQUEST_TIMEOUT): OkHttpClient =
      OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(requestTimeout)
        .callTimeout(requestTimeout)
        .build()

    private val DEFAULT_HTTP: OkHttpClient by lazy { httpClient() }

    /**
     * [e] as a request that got no answer, saying which limit of [http] gave up on it. OkHttp says
     * only `timeout` for both its call timeout (an [InterruptedIOException]) and a read or connect
     * timeout (a [SocketTimeoutException]), which is what reached the report as `the model answered
     * 0: {"error":{"message":"timeout"}}`.
     */
    internal fun noAnswer(e: IOException, http: OkHttpClient): ModelResponse {
      fun seconds(millis: Int) = "${millis / 1000} s"
      return when {
        e is SocketTimeoutException && e.message?.contains("connect", ignoreCase = true) == true ->
          ModelResponse.noAnswer(
            // Not [ModelResponse.timedOut]: nothing was asked yet, so a smaller request would
            // connect no faster.
            "could not connect within the ${seconds(http.connectTimeoutMillis)} connect timeout"
          )
        e is SocketTimeoutException ->
          ModelResponse.noAnswer(
            "no bytes arrived for the ${seconds(http.readTimeoutMillis)} read timeout",
            timedOut = true,
          )
        e is InterruptedIOException && e.message == "timeout" ->
          ModelResponse.noAnswer(
            "no complete answer within the ${seconds(http.callTimeoutMillis)} request timeout " +
              "(the request was abandoned)",
            timedOut = true,
          )
        else ->
          ModelResponse.noAnswer(
            "the connection failed (${e::class.java.simpleName}: ${e.message ?: "no message"})"
          )
      }
    }

    /** A `Retry-After` header — delta-seconds or an HTTP date — in milliseconds from now. */
    internal fun retryAfterMillis(header: String?, now: Long = System.currentTimeMillis()): Long? {
      val value = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
      value.toLongOrNull()?.let {
        return (it * 1000).coerceAtLeast(0)
      }
      value.toDoubleOrNull()?.let {
        return (it * 1000).toLong().coerceAtLeast(0)
      }
      return runCatching {
        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
      }
        .getOrNull()
        ?.let { (it - now).coerceAtLeast(0) }
    }
  }
}
