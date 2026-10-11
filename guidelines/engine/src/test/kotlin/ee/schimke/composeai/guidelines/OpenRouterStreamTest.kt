package ee.schimke.composeai.guidelines

import com.google.common.truth.Truth.assertThat
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import ee.schimke.composeai.guidelines.protocol.CatalogGuidelinesV1
import java.net.InetSocketAddress
import java.time.Duration
import java.util.Collections
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/** Streamed completions over a local server speaking OpenRouter's server-sent events. */
class OpenRouterStreamTest {
  private val guidelines: CatalogGuidelinesV1 =
    CatalogGuidelinesLoader.parse(
        """
        {"schema": "compose-ui-builder/catalog-guidelines/v1", "catalog": "wear-m3",
         "platform": "wear", "version": 1, "rules": [
          {"id": "any", "kind": "structure", "severity": "info", "guidance": "g", "check": "ok?",
           "source": "https://developer.android.com/a"}]}
        """
      )
      .guidelines!!

  private val subject =
    PreviewSubject(
      "a",
      pictures = listOf(SubjectPicture("device", byteArrayOf(1, 2, 3), 40, 40)),
      renderHash = "h",
    )
  private val batch = GuidelineBatch("component", listOf(subject))
  private val request = PreviewGuidelineRequests.request(guidelines, batch, "src", emptyList())

  private fun chunk(content: String) =
    """{"id":"gen-1","model":"deepseek/deepseek-v4.1-flash","provider":"DeepInfra",""" +
      """"choices":[{"index":0,"delta":{"content":${kotlinx.serialization.json.JsonPrimitive(content)}},""" +
      """"finish_reason":null}]}"""

  private val reply =
    """{"verdicts":[],"others":[{"subjectId":"s1","verdict":"pass","confidence":0.9}]}"""

  /** The stream OpenRouter sends: keep-alives, deltas, a final chunk with usage, `[DONE]`. */
  private val events: List<String> =
    listOf(
      ": OPENROUTER PROCESSING",
      "",
      "data: " + chunk(reply.take(20)),
      "",
      ": OPENROUTER PROCESSING",
      "",
      "data: " + chunk(reply.drop(20)),
      "",
      "data: " +
        """{"id":"gen-1","model":"deepseek/deepseek-v4.1-flash","provider":"DeepInfra",""" +
        """"choices":[{"index":0,"delta":{"content":""},"finish_reason":"stop"}],""" +
        """"usage":{"prompt_tokens":900,"completion_tokens":30,"cost":0.0007}}""",
      "",
      "data: [DONE]",
      "",
    )

  @Test
  fun `a stream is assembled into the completion it streamed`() {
    val stream = StreamAssembler()
    val progress = events.map { stream.line(it) }
    // Keep-alive comments and blank lines are not progress; data events are.
    assertThat(progress.count { it }).isEqualTo(4)
    assertThat(stream.done).isTrue()
    val body = stream.completion()!!
    val parsed = GuidelineResponse.parse(body, batch, emptyList()).getOrThrow()
    assertThat(parsed.othersPass).containsKey("a")
    assertThat(parsed.served.provider).isEqualTo("DeepInfra")
    assertThat(GuidelineResponse.cost(body)).isEqualTo(0.0007)
    assertThat(GuidelineResponse.failure(body)).isNull()
  }

  @Test
  fun `an error mid-stream is a failure that may pass`() {
    val stream = StreamAssembler()
    stream.line("data: " + chunk("""{"verd"""))
    stream.line(
      "data: " +
        """{"id":"cmpl-abc123","object":"chat.completion.chunk","model":"openai/gpt-4o",""" +
        """"provider":"openai","error":{"code":"server_error","message":"Provider """ +
        """disconnected unexpectedly"},"choices":[{"index":0,"delta":{"content":""},""" +
        """"finish_reason":"error"}]}"""
    )
    val failure = GuidelineResponse.failure(stream.completion()!!)!!
    assertThat(failure.kind).isEqualTo(FailureKind.TRANSIENT)
    assertThat(failure.problem).contains("Provider disconnected unexpectedly")
  }

  @Test
  fun `a stream cut before it finished is no answer`() {
    val stream = StreamAssembler()
    stream.line("data: " + chunk("""{"verd"""))
    assertThat(stream.completion()).isNull()
  }

  /** A local server answering every POST with [handler], recording each request body. */
  private fun serve(handler: (HttpExchange, Int) -> Unit): Pair<HttpServer, MutableList<String>> {
    val bodies = Collections.synchronizedList(mutableListOf<String>())
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange ->
      bodies += exchange.requestBody.readBytes().decodeToString()
      try {
        handler(exchange, bodies.size)
      } catch (_: java.io.IOException) {
        // The client hung up: what a cancelled stream looks like from here.
      } finally {
        exchange.close()
      }
    }
    server.executor = java.util.concurrent.Executors.newCachedThreadPool()
    server.start()
    return server to bodies
  }

  private fun client(server: HttpServer) =
    OpenRouterClient(
      "test-key",
      http = OpenRouterClient.httpClient(Duration.ofSeconds(30)),
      baseUrl = "http://127.0.0.1:${server.address.port}",
    )

  private fun HttpExchange.sse(lines: List<String>, pauseMillis: Long = 0) {
    responseHeaders.add("Content-Type", "text/event-stream")
    sendResponseHeaders(200, 0)
    lines.forEach { line ->
      responseBody.write((line + "\n").toByteArray())
      responseBody.flush()
      if (pauseMillis > 0) Thread.sleep(pauseMillis)
    }
  }

  @Test
  fun `a streamed completion comes back whole, and the request asked for a stream`() {
    val (server, bodies) = serve { exchange, _ -> exchange.sse(events) }
    try {
      val response = client(server).complete(request, "m")
      assertThat(response.status).isEqualTo(200)
      assertThat(GuidelineResponse.parse(response.body, batch, emptyList()).isSuccess).isTrue()
      val sent = GUIDELINES_JSON.parseToJsonElement(bodies.single()).jsonObject
      assertThat(sent["stream"].toString()).isEqualTo("true")
      assertThat(sent["max_tokens"]).isNotNull()
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun `a stream sending only keep-alives is cancelled at the idle timeout`() {
    val (server, _) =
      serve { exchange, _ ->
        // Ten seconds of keep-alives, a comment every 100 ms, and never a token.
        exchange.sse(List(100) { ": OPENROUTER PROCESSING" }, pauseMillis = 100)
      }
    try {
      val client = client(server).apply { idleTimeout = Duration.ofSeconds(1) }
      val start = System.nanoTime()
      val response = client.complete(request, "m")
      val took = (System.nanoTime() - start) / 1_000_000
      assertThat(response.status).isEqualTo(ModelResponse.NO_ANSWER)
      assertThat(response.timedOut).isTrue()
      assertThat(response.transportError).contains("idle timeout")
      assertThat(took).isLessThan(5_000)
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun `an error status is answered as itself, with its Retry-After`() {
    val (server, _) =
      serve { exchange, _ ->
        val body =
          """{"error":{"code":402,"message":"in flight","metadata":""" +
            """{"limit_source":"openrouter_in_flight_budget"}}}"""
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.responseHeaders.add("Retry-After", "3")
        exchange.sendResponseHeaders(402, body.length.toLong())
        exchange.responseBody.write(body.toByteArray())
      }
    try {
      val response = client(server).complete(request, "m")
      assertThat(response.status).isEqualTo(402)
      assertThat(response.retryAfterMillis).isEqualTo(3_000)
      assertThat(FailedRequest.of(response).kind).isEqualTo(FailureKind.RATE_LIMITED)
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun `no provider honouring the parameters drops the requirement once, for good`() {
    val (server, bodies) =
      serve { exchange, n -> if (n <= 2) exchange.noEndpoints() else exchange.sse(events) }
    try {
      val client = client(server)
      assertThat(client.complete(request, "m").status).isEqualTo(200)
      assertThat(client.relaxedParameters).isTrue()
      client.complete(request, "m")
      assertThat(bodies).hasSize(4)
      // Strict with reasoning, strict without it (the reasoning may be what no provider takes),
      // then relaxed, for good.
      assertThat(bodies[0]).contains("require_parameters")
      assertThat(bodies[0]).contains("\"reasoning\"")
      assertThat(bodies[1]).contains("require_parameters")
      assertThat(bodies[1]).doesNotContain("\"reasoning\"")
      assertThat(bodies[2]).doesNotContain("require_parameters")
      assertThat(bodies[3]).doesNotContain("require_parameters")
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun `a model no provider takes reasoning for is asked without it, keeping the strict schema`() {
    val (server, bodies) =
      serve { exchange, n -> if (n == 1) exchange.noEndpoints() else exchange.sse(events) }
    try {
      val client = client(server)
      assertThat(client.complete(request, "m").status).isEqualTo(200)
      assertThat(client.reasoningUnsupported).isTrue()
      assertThat(client.relaxedParameters).isFalse()
      client.complete(request, "m")
      assertThat(bodies).hasSize(3)
      assertThat(bodies[2]).contains("require_parameters")
      assertThat(bodies[2]).doesNotContain("\"reasoning\"")
      // Sized as a request sending no effort is.
      assertThat(
          GUIDELINES_JSON.parseToJsonElement(bodies[2]).jsonObject["max_tokens"].toString().toInt()
        )
        .isEqualTo(PreviewGuidelineRequests.replyTokenLimit(request, null))
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun `the retry without reasoning asks for effort none`() {
    val (server, bodies) = serve { exchange, _ -> exchange.sse(events) }
    try {
      client(server).completeWithoutReasoning(request, "m")
      val sent = GUIDELINES_JSON.parseToJsonElement(bodies.single()).jsonObject
      assertThat(sent["reasoning"].toString()).isEqualTo("""{"effort":"none"}""")
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun `a streamed reasoning delta is progress, and never part of the reply`() {
    val stream = StreamAssembler()
    val thinking =
      """{"id":"gen-1","model":"m","choices":[{"index":0,"delta":{"content":"",""" +
        """"reasoning":"others pass? verdicts none {}"},"finish_reason":null}]}"""
    assertThat(stream.line("data: $thinking")).isTrue()
    events.forEach { stream.line(it) }
    val parsed = GuidelineResponse.parse(stream.completion()!!, batch, emptyList()).getOrThrow()
    assertThat(parsed.othersPass).containsKey("a")
    assertThat(stream.completion()).doesNotContain("others pass?")
  }

  @Test
  fun `a client that learned no provider honours the parameters asks relaxed from the start`() {
    val (server, bodies) = serve { exchange, _ -> exchange.sse(events) }
    try {
      val client =
        client(server).also {
          it.learned(relaxedAt = System.currentTimeMillis(), noReasoningAt = null)
        }
      assertThat(client.complete(request, "m").status).isEqualTo(200)
      assertThat(bodies.single()).doesNotContain("require_parameters")
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun `a client shared by runs starts each from its own record, not another run's stale one`() {
    val (server, bodies) = serve { exchange, _ -> exchange.sse(events) }
    try {
      val week = GuidelineModelRecord.RELEARN_AFTER_MILLIS
      val client = client(server).also { it.learned(System.currentTimeMillis() - 2 * week, null) }
      // A run whose model has no record: the client's stale refusal is dropped, strict is tried.
      val dir = java.nio.file.Files.createTempDirectory("guidelines-shared").toFile()
      GuidelineEngine(
          client,
          cache = GuidelineResultCache(dir),
          options = GuidelineRunOptions(triage = false),
        )
        .run(guidelines, listOf(subject))
      assertThat(bodies.first()).contains("require_parameters")
      assertThat(client.relaxedParameters).isFalse()
      assertThat(GuidelineResultCache(dir).ledger.get(OpenRouterClient.DEFAULT_MODEL)!!.relaxedAt)
        .isNull()
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun `the key's remaining limit is read from OpenRouter`() {
    val body =
      """{"data":{"label":"ci","limit":5,"usage":4.75,"limit_remaining":0.25,""" +
        """"limit_reset":"weekly","is_free_tier":false}}"""
    val (server, _) =
      serve { exchange, _ ->
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, body.length.toLong())
        exchange.responseBody.write(body.toByteArray())
      }
    try {
      val status = client(server).keyStatus()!!
      assertThat(status).isEqualTo(OpenRouterKeyStatus(5.0, 4.75, 0.25, "weekly"))
      assertThat(status.describe())
        .isEqualTo("the OpenRouter key has $0.25 left of its $5.00 weekly limit")
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun `a key status is read field by field`() {
    // No limit: nothing to cap at.
    assertThat(OpenRouterKeyStatus.parse("""{"data":{"limit":null,"usage":1.5}}"""))
      .isEqualTo(OpenRouterKeyStatus(null, 1.5, null, null))
    // A limit that never resets: what is left is the limit less the usage.
    assertThat(OpenRouterKeyStatus.parse("""{"data":{"limit":2,"usage":2.5}}"""))
      .isEqualTo(OpenRouterKeyStatus(2.0, 2.5, 0.0, null))
    // A resetting limit without `limit_remaining`: usage spans every period, so nothing is assumed.
    assertThat(
        OpenRouterKeyStatus.parse("""{"data":{"limit":2,"usage":9,"limit_reset":"daily"}}""")
      )
      .isEqualTo(OpenRouterKeyStatus(2.0, 9.0, null, "daily"))
    assertThat(OpenRouterKeyStatus.parse("""{"data":{"label":"x"}}""")).isNull()
    assertThat(OpenRouterKeyStatus.parse("not json")).isNull()
  }

  @Test
  fun `a key at its spending limit is told apart from a forbidden key and the in-flight budget`() {
    val limit = """{"error":{"code":403,"message":"Key limit exceeded (weekly limit)"}}"""
    assertThat(FailedRequest.of(ModelResponse(403, limit)).kind).isEqualTo(FailureKind.KEY_LIMIT)
    val credits = """{"error":{"code":402,"message":"Insufficient credits"}}"""
    assertThat(FailedRequest.of(ModelResponse(402, credits)).kind).isEqualTo(FailureKind.KEY_LIMIT)
    val forbidden = """{"error":{"code":403,"message":"Forbidden"}}"""
    assertThat(FailedRequest.of(ModelResponse(403, forbidden)).kind).isEqualTo(FailureKind.FATAL)
    // A stream's 200 sent before the limit was found: the same reading from the body.
    assertThat(GuidelineResponse.failure(limit)!!.kind).isEqualTo(FailureKind.KEY_LIMIT)
    assertThat(GuidelineResponse.failure(credits)!!.kind).isEqualTo(FailureKind.KEY_LIMIT)
    assertThat(GuidelineResponse.failure(forbidden)!!.kind).isEqualTo(FailureKind.FATAL)
  }

  private fun HttpExchange.noEndpoints() {
    val body =
      """{"error":{"code":404,"message":"No endpoints found that can handle the """ +
        """requested parameters."}}"""
    responseHeaders.add("Content-Type", "application/json")
    sendResponseHeaders(404, body.length.toLong())
    responseBody.write(body.toByteArray())
  }

  @Test
  fun `provider speed preferences are sent only when set`() {
    val plain = OpenRouterClient.chatBody(request, "m", requireParameters = true)
    assertThat(plain["provider"].toString()).isEqualTo("""{"require_parameters":true}""")
    val fast =
      OpenRouterClient.chatBody(
        request,
        "m",
        requireParameters = true,
        routing = ProviderRouting(sort = "throughput", maxLatencySeconds = 5.0),
      )
    assertThat(fast["provider"].toString())
      .isEqualTo("""{"require_parameters":true,"sort":"throughput","preferred_max_latency":5.0}""")
  }
}
