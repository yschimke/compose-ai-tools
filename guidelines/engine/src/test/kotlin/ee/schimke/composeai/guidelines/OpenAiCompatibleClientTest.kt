package ee.schimke.composeai.guidelines

import com.google.common.truth.Truth.assertThat
import com.sun.net.httpserver.HttpServer
import ee.schimke.composeai.guidelines.protocol.CatalogGuidelinesV1
import java.net.InetSocketAddress
import java.util.Collections
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Test

/** An OpenAI-style endpoint, served locally: the request it gets and the reply the engine reads. */
class OpenAiCompatibleClientTest {
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
  private val reply =
    """{"verdicts":[],"others":[{"subjectId":"s1","verdict":"pass","confidence":0.9}]}"""

  private val completion =
    """{"id":"chatcmpl-1","model":"llava:13b","choices":[{"index":0,"finish_reason":"stop",""" +
      """"message":{"role":"assistant","content":${JsonPrimitive(reply)}}}],""" +
      """"usage":{"prompt_tokens":900,"completion_tokens":30}}"""

  private val servers = mutableListOf<HttpServer>()

  @After fun stop() = servers.forEach { it.stop(0) }

  private class Seen(val path: String, val auth: String?, val body: JsonObject)

  /** A server answering each POST with [answers] in turn, recording what it was sent. */
  private fun serve(vararg answers: Pair<Int, String>): Pair<String, List<Seen>> {
    val seen = Collections.synchronizedList(mutableListOf<Seen>())
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange ->
      val body = exchange.requestBody.readBytes().decodeToString()
      seen +=
        Seen(
          exchange.requestURI.path,
          exchange.requestHeaders.getFirst("Authorization"),
          GUIDELINES_JSON.parseToJsonElement(body).jsonObject,
        )
      val (status, text) = answers[(seen.size - 1).coerceAtMost(answers.size - 1)]
      val bytes = text.toByteArray()
      exchange.sendResponseHeaders(status, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
    }
    server.start()
    servers += server
    return "http://127.0.0.1:${server.address.port}/v1" to seen
  }

  @Test
  fun `the engine reads a plain chat completion, sent without OpenRouter's extras`() {
    val (url, seen) = serve(200 to completion)
    val run =
      GuidelineEngine(
          OpenAiCompatibleClient(url, apiKey = "k-local"),
          options = GuidelineRunOptions(model = "llava:13b", triage = false),
        )
        .run(guidelines, listOf(subject))

    val result = run.results.single()
    assertThat(result.unchecked).isEmpty()
    assertThat(result.record.servedModel).isEqualTo("llava:13b")
    assertThat(run.costUsd).isEqualTo(0.0)
    val sent = seen.single()
    assertThat(sent.path).isEqualTo("/v1/chat/completions")
    assertThat(sent.auth).isEqualTo("Bearer k-local")
    assertThat(sent.body.keys).containsNoneOf("provider", "reasoning", "stream")
    assertThat(sent.body.keys).containsAtLeast("model", "messages", "max_tokens", "response_format")
    assertThat(sent.body["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
      .isEqualTo("json_schema")
  }

  @Test
  fun `a server refusing the strict schema is asked again with json_object, and remembered`() {
    val (url, seen) =
      serve(
        400 to """{"error":{"message":"response_format json_schema is not supported"}}""",
        200 to completion,
      )
    val client = OpenAiCompatibleClient(url, apiKey = null)
    val request =
      PreviewGuidelineRequests.request(
        guidelines,
        GuidelineBatch("component", listOf(subject)),
        "src",
        emptyList(),
      )
    assertThat(client.complete(request, "m").status).isEqualTo(200)
    assertThat(client.schemaRefused).isTrue()
    client.complete(request, "m")

    assertThat(seen.map { it.body["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content })
      .containsExactly("json_schema", "json_object", "json_object")
      .inOrder()
    assertThat(seen.map { it.auth }).containsExactly(null, null, null)
  }

  @Test
  fun `OpenAI itself is sent max_completion_tokens`() {
    val client = OpenAiCompatibleClient("https://api.openai.com/v1", apiKey = "k")
    val request =
      PreviewGuidelineRequests.request(
        guidelines,
        GuidelineBatch("component", listOf(subject)),
        "src",
        emptyList(),
      )
    val body = GUIDELINES_JSON.parseToJsonElement(client.body(request, "gpt", true)).jsonObject
    assertThat(body.keys).contains("max_completion_tokens")
    assertThat(body.keys).doesNotContain("max_tokens")
  }

  @Test
  fun `loopback endpoints are recognised`() {
    assertThat(OpenAiCompatibleClient.isLoopback("http://localhost:11434/v1")).isTrue()
    assertThat(OpenAiCompatibleClient.isLoopback("http://127.0.0.1:1234/v1")).isTrue()
    assertThat(OpenAiCompatibleClient.isLoopback("https://api.openai.com/v1")).isFalse()
    assertThat(OpenAiCompatibleClient.isLoopback("not a url")).isFalse()
  }

  @Test
  fun `the judge namespaces cached verdicts and survives the other option builders`() {
    val base = GuidelineRunOptions(model = "llava:13b")
    assertThat(base.cacheModel).isEqualTo("llava:13b")
    val local =
      base.withJudge("openai:http://localhost:11434").withConcurrency(2).withRetry(GuidelineRetry())
    val remote = base.withJudge("openai:https://api.example.com")
    assertThat(local.cacheModel).isEqualTo("openai:http://localhost:11434/llava:13b")
    assertThat(local.cacheModel).isNotEqualTo(remote.cacheModel)
    assertThat(local.cacheModel).isNotEqualTo(base.cacheModel)
  }
}
