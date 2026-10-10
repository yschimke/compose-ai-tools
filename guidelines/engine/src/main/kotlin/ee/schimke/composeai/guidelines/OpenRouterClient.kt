package ee.schimke.composeai.guidelines

import ee.schimke.composeai.guidelines.protocol.GuidelineRequestV1
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
 *
 * Completions are streamed ([stream]) and handed back assembled, shaped as a non-streamed
 * completion, so nothing downstream changes. Streaming is what makes a slow request cheap to give
 * up on: OpenRouter keeps a connection alive with `: OPENROUTER PROCESSING` comments, so no read
 * timeout ever fired, and "for non-streaming requests or unsupported providers, the model will
 * continue processing and you will be billed for the complete response" — a non-streamed request
 * abandoned at the timeout was paid for in full. A streamed one is cancelled, which stops the
 * provider (and its bill) where the provider supports it, as soon as no token has arrived for
 * [idleTimeout]: keep-alive comments do not count. [http]'s call timeout still caps the whole
 * request.
 *
 * Calls may be made from several threads at once.
 */
public class OpenRouterClient(
  private val apiKey: String,
  private val http: OkHttpClient = DEFAULT_HTTP,
  private val baseUrl: String = "https://openrouter.ai",
  private val title: String = "compose-preview guidelines",
) : GuidelineModel {
  /**
   * Whether a request was routed without `provider.require_parameters`: no provider of the model
   * honoured every parameter sent (the strict JSON schema, `max_tokens`), so OpenRouter answered
   * 404 and the request was sent again without the requirement, as it is for the rest of this
   * client's life. Its replies may then ignore the schema; one that does is unreadable and asked
   * again like any other.
   */
  @Volatile
  public var relaxedParameters: Boolean = false
    private set

  /**
   * Whether completions are streamed (`stream: true`) and given up on after [idleTimeout] without a
   * token. Off sends one request and waits for the whole answer, as before.
   */
  @Volatile public var stream: Boolean = true

  /**
   * How long a streamed completion may go without a token — keep-alive comments do not count —
   * before it is cancelled and reported as a timeout.
   */
  @Volatile public var idleTimeout: Duration = DEFAULT_IDLE_TIMEOUT

  /**
   * OpenRouter's `provider.sort`: `price`, `throughput` or `latency`. Null (the default) keeps its
   * load balancing across providers, weighted to price; any value turns that off and tries
   * providers in that order instead, which buys speed with a dearer or less spread-out route.
   */
  @Volatile public var providerSort: String? = null

  /**
   * OpenRouter's `provider.preferred_min_throughput`, tokens a second: providers slower than it are
   * tried last, never excluded. Null sends none.
   */
  @Volatile public var preferredMinThroughput: Double? = null

  /**
   * OpenRouter's `provider.preferred_max_latency`, seconds to the first token: providers slower
   * than it are tried last, never excluded. Null sends none.
   */
  @Volatile public var preferredMaxLatencySeconds: Double? = null

  override fun complete(request: GuidelineRequestV1, model: String): ModelResponse {
    val url = "$baseUrl/api/v1/chat/completions"
    fun send(requireParameters: Boolean): ModelResponse {
      val body =
        chatBody(
          request,
          model,
          requireParameters,
          routing =
            ProviderRouting(providerSort, preferredMinThroughput, preferredMaxLatencySeconds),
          stream = stream,
        )
      return if (stream) streamed(url, body.toString()) else post(url, body.toString())
    }
    if (!relaxedParameters) {
      val strict = send(requireParameters = true)
      if (!noEndpointHonours(strict)) return strict
      relaxedParameters = true
    }
    return send(requireParameters = false)
  }

  /**
   * A streamed completion, assembled. Cancelled, and answered as a timeout, once [idleTimeout]
   * passes with no data event (a keep-alive comment is not one); the socket's read timeout is the
   * same, for a connection that sends nothing at all.
   */
  private fun streamed(url: String, body: String): ModelResponse {
    val idleMillis = idleTimeout.toMillis().coerceAtLeast(1)
    val client = http.newBuilder().readTimeout(idleTimeout).build()
    val call = client.newCall(request(url, body).newBuilder().header("Accept", SSE).build())
    var idled = false
    return try {
      call.execute().use { response ->
        val retryAfter = retryAfterMillis(response.header("Retry-After"))
        if (!response.isSuccessful || response.header("Content-Type")?.contains(SSE) != true) {
          return ModelResponse(response.code, response.body.string()).also {
            it.retryAfterMillis = retryAfter
          }
        }
        val stream = StreamAssembler()
        val source = response.body.source()
        var lastProgress = System.nanoTime()
        while (!stream.done) {
          val line = source.readUtf8Line() ?: break
          if (stream.line(line)) lastProgress = System.nanoTime()
          if ((System.nanoTime() - lastProgress) / 1_000_000 > idleMillis) {
            idled = true
            call.cancel()
            return idleAnswer()
          }
        }
        stream.completion()?.let { ModelResponse(response.code, it) }
          ?: ModelResponse.noAnswer(
            "the stream ended before the reply did (no finish_reason, no [DONE])"
          )
      }
    } catch (e: IOException) {
      if (idled) idleAnswer() else noAnswer(e, client)
    }
  }

  private fun idleAnswer(): ModelResponse =
    ModelResponse.noAnswer(
      "no token arrived for the ${idleTimeout.seconds} s idle timeout (only keep-alives); the " +
        "stream was cancelled",
      timedOut = true,
    )

  private fun request(url: String, body: String): Request =
    Request.Builder()
      .url(url)
      .header("Authorization", "Bearer $apiKey")
      .header("X-Title", title)
      .header("X-OpenRouter-Metadata", "enabled")
      .post(body.toRequestBody(JSON))
      .build()

  override fun decide(body: JsonObject): ModelResponse =
    post("$baseUrl/api/alpha/decisions", body.toString())

  private fun post(url: String, body: String): ModelResponse =
    try {
      http.newCall(request(url, body)).execute().use { response ->
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

    /**
     * The chat-completions body for [request]: the text first, then every picture in order, held to
     * the reply schema, its length bounded by [PreviewGuidelineRequests.replyTokenLimit], and
     * routed only to providers that honour all of that (`provider.require_parameters`).
     */
    public fun chatBody(request: GuidelineRequestV1, model: String): JsonObject =
      chatBody(request, model, requireParameters = true)

    internal fun chatBody(
      request: GuidelineRequestV1,
      model: String,
      requireParameters: Boolean,
      routing: ProviderRouting = ProviderRouting(),
      stream: Boolean = false,
    ): JsonObject = buildJsonObject {
      put("model", model)
      put("temperature", 0)
      put("max_tokens", PreviewGuidelineRequests.replyTokenLimit(request))
      if (stream) put("stream", true)
      if (requireParameters || !routing.isEmpty) {
        putJsonObject("provider") {
          // OpenRouter's structured-outputs guidance: a strict `json_schema` is honoured only by
          // some providers, and without this a request may be routed to one that ignores it.
          if (requireParameters) put("require_parameters", true)
          routing.sort?.let { put("sort", it) }
          routing.minThroughput?.let { put("preferred_min_throughput", it) }
          routing.maxLatencySeconds?.let { put("preferred_max_latency", it) }
        }
      }
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

    /**
     * Whether [response] is OpenRouter saying no provider of the model supports every parameter the
     * request sent: a 404 `No endpoints found …`, what `require_parameters` answers when none
     * qualifies.
     */
    internal fun noEndpointHonours(response: ModelResponse): Boolean =
      response.status == 404 && response.body.contains("No endpoints found", ignoreCase = true)

    private val JSON = "application/json".toMediaType()

    private const val SSE = "text/event-stream"

    /** How long a streamed completion may go without a token unless the host says otherwise. */
    public val DEFAULT_IDLE_TIMEOUT: Duration = Duration.ofSeconds(120)

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

/** OpenRouter's speed preferences for a request's `provider` object; all optional. */
internal data class ProviderRouting(
  val sort: String? = null,
  val minThroughput: Double? = null,
  val maxLatencySeconds: Double? = null,
) {
  val isEmpty: Boolean
    get() = sort == null && minThroughput == null && maxLatencySeconds == null
}

/**
 * A streamed chat completion read line by line (server-sent events), assembled into the
 * non-streamed shape [GuidelineResponse] reads: the content deltas joined into one message, the
 * last `finish_reason`, the `usage` (with its cost) from the final chunk, an `error` event
 * mid-stream, and every other top-level field (`id`, `model`, `provider`, `openrouter_metadata`) as
 * the last chunk carrying it had it.
 */
internal class StreamAssembler {
  private val content = StringBuilder()
  private val top = linkedMapOf<String, JsonElement>()
  private val data = StringBuilder()
  private var finishReason: String? = null
  private var choiceError: JsonElement? = null
  private var events = 0

  /** Whether the stream said `[DONE]`. */
  var done: Boolean = false
    private set

  /**
   * Takes one line of the stream; true when it ended a data event — progress, unlike a comment (`:
   * OPENROUTER PROCESSING`) or a blank line between events.
   */
  fun line(line: String): Boolean {
    when {
      line.startsWith(":") -> return false
      line.isEmpty() -> return dispatch()
      line.startsWith("data:") -> {
        if (data.isNotEmpty()) data.append('\n')
        data.append(line.removePrefix("data:").removePrefix(" "))
        // OpenRouter sends one JSON object per data line; dispatch it at once rather than wait
        // for the blank line, so a stream cut after its last event still counts it.
        return dispatch()
      }
      else -> return false
    }
  }

  private fun dispatch(): Boolean {
    if (data.isEmpty()) return false
    val text = data.toString().trim()
    data.setLength(0)
    if (text == "[DONE]") {
      done = true
      return true
    }
    val chunk =
      runCatching { GUIDELINES_JSON.parseToJsonElement(text) as? JsonObject }.getOrNull()
        ?: return false
    events++
    chunk.forEach { (key, value) -> if (key != "choices") top[key] = value }
    val choice = (chunk["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
    if (choice != null) {
      val delta = choice["delta"] as? JsonObject
      ((delta?.get("content") as? JsonPrimitive)?.contentOrNull)?.let { content.append(it) }
      (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull?.let { finishReason = it }
      choice["error"]?.let { choiceError = it }
    }
    return true
  }

  /**
   * The completion assembled from what arrived, or null when the stream ended without saying it was
   * finished (no `finish_reason`, no `[DONE]`, no error).
   */
  fun completion(): String? {
    dispatch()
    if (!done && finishReason == null && top["error"] == null) return null
    if (events == 0) return null
    return buildJsonObject {
      top.forEach { (key, value) -> put(key, value) }
      putJsonArray("choices") {
        add(
          buildJsonObject {
            put("index", 0)
            putJsonObject("message") {
              put("role", "assistant")
              put("content", content.toString())
            }
            put("finish_reason", finishReason)
            choiceError?.let { put("error", it) }
          }
        )
      }
    }
      .toString()
  }
}
