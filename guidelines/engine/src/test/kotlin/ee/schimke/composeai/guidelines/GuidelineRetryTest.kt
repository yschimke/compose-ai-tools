package ee.schimke.composeai.guidelines

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.guidelines.protocol.CatalogGuidelinesV1
import ee.schimke.composeai.guidelines.protocol.GuidelineRequestV1
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.time.Duration
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

/** A request that fails: retried, split, held to the cost cap, and reported once. */
class GuidelineRetryTest {
  private val guidelines: CatalogGuidelinesV1 =
    CatalogGuidelinesLoader.parse(
        """
        {
          "schema": "compose-ui-builder/catalog-guidelines/v1",
          "catalog": "wear-m3", "platform": "wear", "version": 1,
          "rules": [
            {"id": "any", "kind": "structure", "severity": "info", "guidance": "g", "check": "ok?",
             "source": "https://developer.android.com/a"},
            {"id": "touch", "kind": "visual", "severity": "warning", "guidance": "48dp", "check": "big?",
             "source": "https://developer.android.com/b", "surfaces": ["component", "screen"]},
            {"id": "screen-only", "kind": "structure", "severity": "info", "guidance": "g",
             "check": "time text?", "source": "https://developer.android.com/c", "surfaces": ["screen"]}
          ]
        }
        """
      )
      .guidelines!!

  private fun subject(id: String, surface: String = GuidelineSurfaces.COMPONENT) =
    PreviewSubject(
      previewId = id,
      label = id,
      surface = surface,
      renderHash = "h-$id",
      pictures = listOf(SubjectPicture("device", byteArrayOf(1, 2, 3), 40, 40)),
    )

  /** A completion passing `any` and `touch` for every subject of [request]. */
  private fun passing(request: GuidelineRequestV1, cost: Double = 0.001): ModelResponse {
    val verdicts = buildJsonArray {
      request.subjects.indices.forEach { index ->
        listOf("any", "touch").forEach { rule ->
          add(
            buildJsonObject {
              put("subjectId", "s${index + 1}")
              put("ruleId", rule)
              put("verdict", "pass")
              put("confidence", 0.9)
              put("nodeIds", buildJsonArray {})
              put("reason", "")
              put("needs", buildJsonArray {})
              put("regions", buildJsonArray {})
            }
          )
        }
      }
    }
    val content = buildJsonObject { put("verdicts", verdicts) }.toString()
    return ModelResponse(
      200,
      buildJsonObject {
        put("id", "gen-1")
        put("model", "m")
        put("usage", buildJsonObject { put("cost", cost) })
        put(
          "choices",
          buildJsonArray {
            add(buildJsonObject { put("message", buildJsonObject { put("content", content) }) })
          },
        )
      }
        .toString(),
    )
  }

  private val timeout: ModelResponse
    get() =
      ModelResponse.noAnswer(
        "no complete answer within the 300 s request timeout (the request was abandoned)",
        timedOut = true,
      )

  /** A model answering each request with [answer], given the request and its number (from 1). */
  private class ScriptedModel(val answer: (GuidelineRequestV1, Int) -> ModelResponse) :
    GuidelineModel {
    val asked = mutableListOf<List<String>>()

    override fun complete(request: GuidelineRequestV1, model: String): ModelResponse {
      asked += request.subjects.map { it.id }
      return answer(request, asked.size)
    }
  }

  private fun engine(
    model: GuidelineModel,
    options: GuidelineRunOptions,
    sleeps: MutableList<Long>,
  ) = GuidelineEngine(model, options = options).apply { sleep = { sleeps += it } }

  @Test
  fun `a request that timed out is asked again and its answer used`() {
    val model = ScriptedModel { request, n -> if (n == 1) timeout else passing(request) }
    val sleeps = mutableListOf<Long>()
    val run =
      engine(model, GuidelineRunOptions(triage = false), sleeps)
        .run(guidelines, listOf(subject("a")))

    assertThat(model.asked).containsExactly(listOf("a"), listOf("a")).inOrder()
    assertThat(sleeps).containsExactly(2_000L)
    assertThat(run.failedRequests).isEqualTo(0)
    assertThat(run.results.single().unchecked).isEmpty()
    assertThat(run.results.single().pending).isFalse()
    // The retry is said once; the timeout itself is not a problem, since it was recovered.
    assertThat(run.problems)
      .containsExactly(
        "1 request(s) were asked again after a failure that may pass (no answer, a timeout, " +
          "408, 429, 5xx or a provider error)"
      )
  }

  @Test
  fun `a server's Retry-After is waited for, and one asking too long is not`() {
    val model = ScriptedModel { request, n ->
      if (n == 1) ModelResponse(429, "{}").also { it.retryAfterMillis = 7_000 }
      else passing(request)
    }
    val sleeps = mutableListOf<Long>()
    val run =
      engine(model, GuidelineRunOptions(triage = false), sleeps)
        .run(guidelines, listOf(subject("a")))
    assertThat(sleeps).containsExactly(7_000L)
    assertThat(run.failedRequests).isEqualTo(0)

    val patient = ScriptedModel { _, _ ->
      ModelResponse(503, "{}").also { it.retryAfterMillis = 600_000 }
    }
    val waited = mutableListOf<Long>()
    val failed =
      engine(patient, GuidelineRunOptions(triage = false), waited)
        .run(guidelines, listOf(subject("a")))
    assertThat(waited).isEmpty()
    assertThat(patient.asked).hasSize(1)
    assertThat(failed.failedRequests).isEqualTo(1)
    assertThat(failed.problems.single()).contains("the server asked for a 600 s wait")
  }

  @Test
  fun `a batch that keeps timing out is split down to answers for each preview`() {
    // The provider answers one subject at a time and times out on anything bigger.
    val model = ScriptedModel { request, _ ->
      if (request.subjects.size > 1) timeout else passing(request)
    }
    val sleeps = mutableListOf<Long>()
    val subjects = (1..4).map { subject("p$it") }
    val run = engine(model, GuidelineRunOptions(triage = false), sleeps).run(guidelines, subjects)

    // A timeout on several subjects is split at once: the same request would take as long again.
    assertThat(model.asked)
      .containsExactly(
        listOf("p1", "p2", "p3", "p4"),
        listOf("p1", "p2"),
        listOf("p1"),
        listOf("p2"),
        listOf("p3", "p4"),
        listOf("p3"),
        listOf("p4"),
      )
      .inOrder()
    assertThat(sleeps).isEmpty()
    assertThat(run.failedRequests).isEqualTo(0)
    assertThat(run.results.map { it.previewId }).containsExactly("p1", "p2", "p3", "p4")
    run.results.forEach { result ->
      assertThat(result.unchecked).isEmpty()
      assertThat(result.record.verdicts.map { it.ruleId }).containsExactly("any", "touch")
    }
    // The three splits are one problem line, not one per failed try.
    assertThat(run.problems).hasSize(1)
    assertThat(run.problems.single())
      .startsWith("3 request(s) kept failing and were split into smaller ones: 4 previews (")
  }

  @Test
  fun `a failure that persists is recorded once per request that finally failed`() {
    val model = ScriptedModel { _, _ -> timeout }
    val sleeps = mutableListOf<Long>()
    val run =
      engine(model, GuidelineRunOptions(triage = false), sleeps)
        .run(guidelines, listOf(subject("a"), subject("b")))

    // Split once, then each single subject is tried twice.
    assertThat(model.asked)
      .containsExactly(listOf("a", "b"), listOf("a"), listOf("a"), listOf("b"), listOf("b"))
      .inOrder()
    assertThat(sleeps).containsExactly(2_000L, 2_000L)
    assertThat(run.failedRequests).isEqualTo(2)
    assertThat(run.results.map { it.pending }).containsExactly(true, true)
    val failures = run.problems.filter { it.startsWith("the request got no answer") }
    assertThat(failures)
      .containsExactly(
        "the request got no answer: no complete answer within the 300 s request timeout (the " +
          "request was abandoned) (after 2 tries)",
        "the request got no answer: no complete answer within the 300 s request timeout (the " +
          "request was abandoned) (after 2 tries)",
      )
    assertThat(run.problems.count { "split" in it }).isEqualTo(1)
  }

  @Test
  fun `the run stops retrying and splitting once its failures use up the allowance`() {
    val model = ScriptedModel { _, _ -> ModelResponse(502, "{}") }
    val sleeps = mutableListOf<Long>()
    val run =
      engine(
          model,
          GuidelineRunOptions(triage = false).withRetry(GuidelineRetry(maxFailedAttempts = 2)),
          sleeps,
        )
        .run(guidelines, (1..4).map { subject("p$it") })
    // Two failed tries of the batch, which is then split; the first half's first failure is the
    // third, past the allowance, so it is final, and so is every request after it.
    assertThat(model.asked)
      .containsExactly(
        listOf("p1", "p2", "p3", "p4"),
        listOf("p1", "p2", "p3", "p4"),
        listOf("p1", "p2"),
        listOf("p3", "p4"),
      )
      .inOrder()
    assertThat(run.failedRequests).isEqualTo(2)
    assertThat(run.results.all { it.pending }).isTrue()
  }

  @Test
  fun `a retry the cost cap cannot afford is not started, and the rest stay pending`() {
    // p1 answers at $0.001; p2 gets no answer, which may still be billed, so it counts as $0.001
    // against the cap; a retry expected to cost $0.001 more would cross $0.0025.
    val model = ScriptedModel { request, _ ->
      if (request.subjects.single().id == "p2") timeout else passing(request)
    }
    val sleeps = mutableListOf<Long>()
    val run =
      engine(
          model,
          GuidelineRunOptions(
            triage = false,
            budget = GuidelineBudget(maxSubjects = 1),
            maxCostUsd = 0.0025,
          ),
          sleeps,
        )
        .run(guidelines, (1..4).map { subject("p$it") })

    assertThat(model.asked).containsExactly(listOf("p1"), listOf("p2")).inOrder()
    assertThat(sleeps).isEmpty()
    assertThat(run.costUsd).isWithin(1e-9).of(0.001)
    assertThat(run.results.filter { it.pending }.map { it.previewId })
      .containsExactly("p2", "p3", "p4")
    assertThat(run.failedRequests).isEqualTo(1)
    assertThat(run.problems.single { it.startsWith("the request got no answer") })
      .endsWith("; not asked again: the cost cap left no room")
    assertThat(run.problems.single { "cost cap (" in it }).contains("2 previews were not checked")
    assertThat(run.problems.single { "may still have been billed" in it }).contains("\$0.0010")
  }

  @Test
  fun `what a failed reply cost counts against the cap, and its halves wait for room`() {
    // An error OpenRouter sent in place of a completion, after the answer began: 200, billed.
    val providerError =
      """{"error":{"code":502,"message":"Provider returned error","metadata":{"provider_name":"X"}},"usage":{"cost":0.004}}"""
    val model = ScriptedModel { _, _ -> ModelResponse(200, providerError) }
    val sleeps = mutableListOf<Long>()
    val run =
      engine(model, GuidelineRunOptions(triage = false, maxCostUsd = 0.005), sleeps)
        .run(guidelines, listOf(subject("a"), subject("b")))

    // $0.004 spent; another $0.004 would cross $0.005: no retry, and the halves are capped.
    assertThat(model.asked).hasSize(1)
    assertThat(run.costUsd).isWithin(1e-9).of(0.004)
    assertThat(run.results.map { it.pending }).containsExactly(true, true)
    assertThat(run.problems.single { "cost cap (" in it }).contains("2 previews were not checked")
    assertThat(run.problems.single { "split" in it }).contains("code 502, from X")
  }

  @Test
  fun `an error in place of a completion is told apart from an unreadable reply`() {
    val provider =
      GuidelineResponse.failure(
        """{"error":{"code":502,"message":"Provider returned error","metadata":{"provider_name":"X"}}}"""
      )!!
    assertThat(provider.kind).isEqualTo(FailureKind.TRANSIENT)
    assertThat(provider.problem)
      .isEqualTo(
        "the model answered with an error in place of a completion (code 502, from X): " +
          "Provider returned error"
      )
    val midway =
      GuidelineResponse.failure(
        """{"choices":[{"finish_reason":"error","error":{"code":"504","message":"timeout"}}]}"""
      )!!
    assertThat(midway.kind).isEqualTo(FailureKind.TRANSIENT)
    assertThat(GuidelineResponse.failure("""{"error":{"code":401,"message":"No auth"}}""")!!.kind)
      .isEqualTo(FailureKind.FATAL)
    assertThat(GuidelineResponse.failure("""{"error":{"code":429,"message":"slow down"}}""")!!.kind)
      .isEqualTo(FailureKind.RATE_LIMITED)
    assertThat(GuidelineResponse.failure("""{"choices":[{"message":{"content":"{}"}}]}""")).isNull()
    assertThat(GuidelineResponse.failure("not json")).isNull()
  }

  @Test
  fun `statuses are sorted into what a retry or a split can fix`() {
    fun kind(status: Int) = FailedRequest.of(ModelResponse(status, "{}")).kind
    assertThat(kind(408)).isEqualTo(FailureKind.TRANSIENT)
    assertThat(kind(500)).isEqualTo(FailureKind.TRANSIENT)
    assertThat(kind(524)).isEqualTo(FailureKind.TRANSIENT)
    assertThat(kind(429)).isEqualTo(FailureKind.RATE_LIMITED)
    assertThat(kind(413)).isEqualTo(FailureKind.TOO_LARGE)
    assertThat(kind(400)).isEqualTo(FailureKind.FATAL)
    assertThat(kind(402)).isEqualTo(FailureKind.FATAL)
    assertThat(FailedRequest.of(timeout).kind).isEqualTo(FailureKind.TIMEOUT)
    assertThat(FailedRequest.of(ModelResponse.noAnswer("reset")).kind)
      .isEqualTo(FailureKind.TRANSIENT)
  }

  @Test
  fun `a request OkHttp gave up on says which timeout passed`() {
    val http = OpenRouterClient.httpClient(Duration.ofSeconds(90))
    // OkHttp's call timeout and its socket timeouts both say only "timeout".
    val call = OpenRouterClient.noAnswer(InterruptedIOException("timeout"), http)
    assertThat(call.status).isEqualTo(ModelResponse.NO_ANSWER)
    assertThat(call.timedOut).isTrue()
    assertThat(call.transportError)
      .isEqualTo("no complete answer within the 90 s request timeout (the request was abandoned)")
    assertThat(call.body).contains("90 s request timeout")
    val read = OpenRouterClient.noAnswer(SocketTimeoutException("timeout"), http)
    assertThat(read.transportError).isEqualTo("no bytes arrived for the 90 s read timeout")
    assertThat(read.timedOut).isTrue()
    val connect = OpenRouterClient.noAnswer(SocketTimeoutException("connect timed out"), http)
    assertThat(connect.transportError).contains("connect timeout")
    assertThat(connect.timedOut).isFalse()
    val reset = OpenRouterClient.noAnswer(IOException("Connection reset"), http)
    assertThat(reset.timedOut).isFalse()
    assertThat(reset.transportError)
      .isEqualTo("the connection failed (IOException: Connection reset)")
  }

  @Test
  fun `the request timeout bounds the whole call, and Retry-After reads both forms`() {
    val http = OpenRouterClient.httpClient(Duration.ofSeconds(120))
    assertThat(http.callTimeoutMillis).isEqualTo(120_000)
    assertThat(http.readTimeoutMillis).isEqualTo(120_000)
    assertThat(OpenRouterClient.httpClient().callTimeoutMillis).isEqualTo(300_000)
    assertThat(OpenRouterClient.retryAfterMillis("12")).isEqualTo(12_000)
    assertThat(OpenRouterClient.retryAfterMillis(null)).isNull()
    assertThat(OpenRouterClient.retryAfterMillis("soon")).isNull()
    val now = 1_760_000_000_000L
    val date =
      java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(
        java.time.Instant.ofEpochMilli(now + 30_000).atZone(java.time.ZoneOffset.UTC)
      )
    assertThat(OpenRouterClient.retryAfterMillis(date, now)).isEqualTo(30_000)
  }

  @Test
  fun `a picture is counted by its pixels once it costs more than the floor`() {
    fun png(width: Int, height: Int): ByteArray =
      java.io
        .ByteArrayOutputStream()
        .also {
          javax.imageio.ImageIO.write(
            java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB),
            "png",
            it,
          )
        }
        .toByteArray()
    // A Wear screen, and its scroll capture, cost the floor; a 1080x4000 capture costs its pixels.
    assertThat(
        PreviewGuidelineRequests.pictureTokens(SubjectPicture("device", png(454, 454), 227, 227))
      )
      .isEqualTo(PreviewGuidelineRequests.PICTURE_TOKENS)
    assertThat(
        PreviewGuidelineRequests.pictureTokens(SubjectPicture("scroll", png(1080, 4000), 411, 1523))
      )
      .isEqualTo(1080 * 4000 / 750)
    assertThat(PreviewGuidelineRequests.pictureTokens(SubjectPicture("x", byteArrayOf(1), 1, 1)))
      .isEqualTo(PreviewGuidelineRequests.PICTURE_TOKENS)
  }

  @Test
  fun `a batch holds no more verdicts than the budget allows`() {
    // A screen is asked three rules here: two screens fill six, the third starts a new batch.
    val screens = (1..5).map { subject("s$it", GuidelineSurfaces.SCREEN) }
    val batches =
      PreviewGuidelineRequests.batches(guidelines, screens, GuidelineBudget().withMaxVerdicts(6))
    assertThat(batches.map { it.subjects.size }).containsExactly(2, 2, 1).inOrder()
    // At the default, twelve screens asked 24 rules each go in batches of six, not one of twelve:
    // the worst case (every rule listed) and the expected reply agree on six.
    assertThat(GuidelineBudget.DEFAULT_MAX_VERDICTS / 24).isEqualTo(6)
    assertThat(
        GuidelineBudget.DEFAULT_MAX_REPLY_TOKENS / PreviewGuidelineRequests.expectedReplyTokens(24)
      )
      .isEqualTo(6)
  }
}
