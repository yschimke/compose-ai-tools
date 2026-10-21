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

    /**
     * The [status] of a request the model left for someone else to answer ([DeferredReplyModel]):
     * its previews stay pending, and it is neither a failure nor retried. [body] says where it
     * went.
     */
    public const val DEFERRED: Int = -1

    /** A request left to be answered elsewhere, described by [where]. */
    public fun deferred(where: String): ModelResponse = ModelResponse(DEFERRED, where)

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

  /**
   * [request] asked again with the model's reasoning turned off: the one retry after a reply that
   * spent its whole token budget reasoning and left no room for the answer. A model with no such
   * switch answers it as [complete] does.
   */
  public fun completeWithoutReasoning(request: GuidelineRequestV1, model: String): ModelResponse =
    complete(request, model)

  /** A decisions call (Jev), with [body] as OpenRouter's decisions API takes it. */
  public fun decide(body: JsonObject): ModelResponse =
    ModelResponse(501, "{\"error\":\"no decisions endpoint\"}")
}

/**
 * OpenRouter: chat completions for verdicts and the decisions endpoint for Jev triage. Sends
 * `X-OpenRouter-Metadata: enabled` so the routed model is recorded. [apiKey] is never logged.
 *
 * Each call is made once; retrying is [GuidelineEngine]'s job. A request with no answer returns
 * [ModelResponse.NO_ANSWER], and a `Retry-After` is exposed as [ModelResponse.retryAfterMillis].
 * [http]'s call timeout bounds one request.
 *
 * Completions are streamed ([stream]) and reassembled into the non-streamed shape. Streaming makes
 * abandoning a slow request cheap: OpenRouter's keep-alive comments defeat read timeouts, and an
 * abandoned non-streamed request is billed in full, whereas a streamed one is cancelled after
 * [idleTimeout] with no token.
 *
 * Thread-safe.
 */
public class OpenRouterClient(
  private val apiKey: String,
  private val http: OkHttpClient = DEFAULT_HTTP,
  private val baseUrl: String = "https://openrouter.ai",
  private val title: String = "compose-preview guidelines",
) : GuidelineModel {
  /**
   * Whether requests are routed without `provider.require_parameters`: set after OpenRouter answers
   * 404 because no provider honours every parameter (strict schema, `max_tokens`). Replies may then
   * ignore the schema and be retried as unreadable.
   */
  @Volatile
  public var relaxedParameters: Boolean = false
    private set

  /**
   * Whether requests are sent without the `reasoning` object: no provider of the model honoured it
   * with the rest (`provider.require_parameters` answered 404), as for a model that does not
   * reason, so it is left out for the rest of this client's life and the model reasons, or not, as
   * it does by default.
   */
  @Volatile
  public var reasoningUnsupported: Boolean = false
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
   * OpenRouter's `provider.sort`: `price`, `throughput` or `latency`. Null keeps its price-weighted
   * load balancing; a value tries providers in that order instead.
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

  /**
   * OpenRouter's unified `reasoning.effort` for every completion: `none`, `minimal`, `low`,
   * `medium`, `high`, `xhigh` or `max` ([REASONING_EFFORTS]), or null to send no `reasoning` object
   * and leave the model at its own default (`high` for [DEFAULT_MODEL]). A model takes only some of
   * them (its OpenRouter model page lists `supported_efforts`). Defaults to
   * [DEFAULT_REASONING_EFFORT].
   *
   * Reasoning tokens are output tokens: billed, and counted against the request's `max_tokens` with
   * the visible reply. So `max_tokens` is sized for the effort
   * ([PreviewGuidelineRequests.replyTokenLimit]), and a reply that spent all of it reasoning is
   * asked once more with reasoning off ([completeWithoutReasoning]) rather than split.
   */
  @Volatile public var reasoningEffort: String? = DEFAULT_REASONING_EFFORT

  override fun complete(request: GuidelineRequestV1, model: String): ModelResponse =
    complete(request, model, reasoningEffort)

  override fun completeWithoutReasoning(request: GuidelineRequestV1, model: String): ModelResponse =
    complete(request, model, REASONING_OFF)

  private fun complete(
    request: GuidelineRequestV1,
    model: String,
    effort: String?,
  ): ModelResponse {
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
          reasoningEffort = effort.takeUnless { reasoningUnsupported },
        )
      return if (stream) streamed(url, body.toString()) else post(url, body.toString())
    }
    if (!relaxedParameters) {
      val strict = send(requireParameters = true)
      if (!noEndpointHonours(strict)) return strict
      // A model that takes no `reasoning` has no provider honouring it: drop that before giving
      // up the strict reply schema, which matters more.
      if (effort != null && !reasoningUnsupported) {
        reasoningUnsupported = true
        noReasoningSince = System.currentTimeMillis()
        val plain = send(requireParameters = true)
        if (!noEndpointHonours(plain)) return plain
      }
      relaxedParameters = true
      relaxedSince = System.currentTimeMillis()
    }
    return send(requireParameters = false)
  }

  /**
   * A streamed completion, assembled. Cancelled and answered as a timeout after [idleTimeout] with
   * no data event (keep-alive comments don't count).
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

  /**
   * What this key may still spend (`GET /api/v1/key`); null when OpenRouter cannot be asked or does
   * not say, and the run is capped by its own cost cap only.
   */
  public fun keyStatus(): OpenRouterKeyStatus? =
    try {
      val request =
        Request.Builder()
          .url("$baseUrl/api/v1/key")
          .header("Authorization", "Bearer $apiKey")
          .header("X-Title", title)
          .get()
          .build()
      http.newCall(request).execute().use { response ->
        if (response.isSuccessful) OpenRouterKeyStatus.parse(response.body.string()) else null
      }
    } catch (e: IOException) {
      null
    }

  /**
   * Starts this client where an earlier run's left off ([GuidelineModelLedger]): routed without
   * `provider.require_parameters` when no provider honoured them, and without `reasoning` when that
   * was what none honoured, so the run does not pay a rejected request to learn it again.
   */
  internal fun learned(relaxedAt: Long?, noReasoningAt: Long?) {
    relaxedParameters = relaxedAt != null
    relaxedSince = relaxedAt
    reasoningUnsupported = noReasoningAt != null
    noReasoningSince = noReasoningAt
  }

  /**
   * When [relaxedParameters] was learned (epoch millis): when OpenRouter refused, or the refusal an
   * earlier run recorded ([learned]). A client shared by several runs carries it between them, so a
   * run that never met a refusal does not record one as new.
   */
  @Volatile
  internal var relaxedSince: Long? = null
    private set

  /** As [relaxedSince], for [reasoningUnsupported]. */
  @Volatile
  internal var noReasoningSince: Long? = null
    private set

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
     * [reasoningEffort] unless told otherwise. [DEFAULT_MODEL] reasons by default, at `high`, and
     * takes `low`, `high` and `max` (no `minimal`); at `high` a batch of screens spent its whole
     * `max_tokens` reasoning and came back with no reply at all, billed. `low` keeps a little
     * thinking for the rules that need it — off (`none`) is the fallback for a reply that still
     * runs out, not the default, since a judgement made with none is a guess at the harder rules.
     */
    public const val DEFAULT_REASONING_EFFORT: String = "low"

    /** The [reasoningEffort] that turns reasoning off. */
    public const val REASONING_OFF: String = "none"

    /** Every `reasoning.effort` OpenRouter documents, least first. */
    public val REASONING_EFFORTS: List<String> =
      listOf(REASONING_OFF, "minimal", "low", "medium", "high", "xhigh", "max")

    /**
     * The chat-completions body for [request]: the text first, then every picture in order, held to
     * the reply schema, its length bounded by [PreviewGuidelineRequests.replyTokenLimit], and
     * routed only to providers that honour all of that (`provider.require_parameters`).
     */
    public fun chatBody(request: GuidelineRequestV1, model: String): JsonObject =
      chatBody(request, model, requireParameters = true)

    /**
     * [chatBody] at [reasoningEffort] (null: no `reasoning` object, the model's own default), its
     * `max_tokens` sized for that effort.
     */
    public fun chatBody(
      request: GuidelineRequestV1,
      model: String,
      reasoningEffort: String?,
    ): JsonObject =
      chatBody(request, model, requireParameters = true, reasoningEffort = reasoningEffort)

    internal fun chatBody(
      request: GuidelineRequestV1,
      model: String,
      requireParameters: Boolean,
      routing: ProviderRouting = ProviderRouting(),
      stream: Boolean = false,
      reasoningEffort: String? = DEFAULT_REASONING_EFFORT,
    ): JsonObject = buildJsonObject {
      put("model", model)
      put("temperature", 0)
      put("max_tokens", PreviewGuidelineRequests.replyTokenLimit(request, reasoningEffort))
      if (reasoningEffort != null) {
        // OpenRouter's unified reasoning control (openrouter.ai/docs/use-cases/reasoning-tokens).
        putJsonObject("reasoning") {
          put("effort", reasoningEffort)
          // Unread either way, and billed either way: `exclude` only keeps the text out of the
          // reply. A stream keeps it, because its deltas are what tells a model that is thinking
          // from a stalled one — with them excluded, a long think sends only keep-alive comments
          // and the idle timeout cancels it. [StreamAssembler] drops them.
          if (!stream && reasoningEffort != REASONING_OFF) put("exclude", true)
        }
      }
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
     * An HTTP client whose calls give up after [requestTimeout], start to end. The read timeout is
     * the same, since OpenRouter keeps slow connections alive with whitespace and only the whole
     * call can be bounded.
     */
    public fun httpClient(requestTimeout: Duration = DEFAULT_REQUEST_TIMEOUT): OkHttpClient =
      OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(requestTimeout)
        .callTimeout(requestTimeout)
        .hostnameVerifier(ForbiddenCertificatesHostnameVerifier())
        .build()

    private val DEFAULT_HTTP: OkHttpClient by lazy { httpClient() }

    /**
     * [e] as a request that got no answer, saying which limit of [http] gave up — OkHttp reports
     * both its call timeout and read/connect timeouts as just `timeout`.
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
 * A streamed chat completion (server-sent events) assembled into the non-streamed shape
 * [GuidelineResponse] reads: joined content deltas, the last `finish_reason`, the final `usage`,
 * any mid-stream `error`, and other top-level fields as last seen.
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
