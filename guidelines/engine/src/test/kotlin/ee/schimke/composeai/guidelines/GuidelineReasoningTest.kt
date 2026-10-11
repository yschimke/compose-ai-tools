package ee.schimke.composeai.guidelines

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.guidelines.protocol.CatalogGuidelinesV1
import ee.schimke.composeai.guidelines.protocol.GuidelineRequestV1
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Test

/**
 * A reasoning model's token budget: the reasoning effort the request carries, `max_tokens` sized
 * for it, and a reply that spent the whole budget reasoning — told apart, asked once more with
 * reasoning off, never split, and never paid from the run's retry allowance.
 */
class GuidelineReasoningTest {
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
             "source": "https://developer.android.com/b", "surfaces": ["component", "screen"]}
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

  private fun request(vararg ids: String): GuidelineRequestV1 =
    PreviewGuidelineRequests.request(
      guidelines,
      GuidelineBatch(GuidelineSurfaces.COMPONENT, ids.map { subject(it) }),
      "src",
      emptyList(),
    )

  /** A completion with [content], ending [finishReason], billed [completion] output tokens. */
  private fun completion(
    content: String,
    finishReason: String = "stop",
    completion: Int = 200,
    reasoning: Int = 0,
  ): ModelResponse =
    ModelResponse(
      200,
      buildJsonObject {
        put("id", "gen-1")
        put("model", "deepseek/deepseek-v4.1-flash")
        put(
          "usage",
          buildJsonObject {
            put("cost", 0.001)
            put("completion_tokens", completion)
            put("completion_tokens_details", buildJsonObject { put("reasoning_tokens", reasoning) })
          },
        )
        put(
          "choices",
          buildJsonArray {
            add(
              buildJsonObject {
                put("message", buildJsonObject { put("content", content) })
                put("finish_reason", finishReason)
              }
            )
          },
        )
      }
        .toString(),
    )

  /** Every subject of [request] passing: no finding, and an `others: pass` statement each. */
  private fun passing(request: GuidelineRequestV1): ModelResponse =
    completion(
      buildJsonObject {
        put("verdicts", buildJsonArray {})
        put(
          "others",
          buildJsonArray {
            request.subjects.indices.forEach { index ->
              add(
                buildJsonObject {
                  put("subjectId", "s${index + 1}")
                  put("verdict", "pass")
                  put("confidence", 0.9)
                }
              )
            }
          },
        )
      }
        .toString()
    )

  /** What the run log showed: cut at max_tokens with nothing written, every token reasoning. */
  private val exhausted: ModelResponse
    get() = completion("", finishReason = "length", completion = 24_576, reasoning = 24_576)

  /** A model that answers [complete] and [completeWithoutReasoning] as told, recording each. */
  private class ReasoningModel(
    val reasoning: (GuidelineRequestV1, Int) -> ModelResponse,
    val withoutReasoning: (GuidelineRequestV1) -> ModelResponse,
  ) : GuidelineModel {
    val asked = mutableListOf<String>()

    override fun complete(request: GuidelineRequestV1, model: String): ModelResponse {
      asked += "reasoning:" + request.subjects.joinToString(",") { it.id }
      return reasoning(request, asked.size)
    }

    override fun completeWithoutReasoning(
      request: GuidelineRequestV1,
      model: String,
    ): ModelResponse {
      asked += "off:" + request.subjects.joinToString(",") { it.id }
      return withoutReasoning(request)
    }
  }

  private fun engine(model: GuidelineModel, options: GuidelineRunOptions) =
    GuidelineEngine(model, options = options.withConcurrency(1)).apply { sleep = {} }

  @Test
  fun `the request carries the reasoning effort, and max_tokens leaves the reply its room`() {
    val request = request("a", "b")
    val reply = PreviewGuidelineRequests.replyTokenLimit(request, OpenRouterClient.REASONING_OFF)
    val body = OpenRouterClient.chatBody(request, "m")

    assertThat(body["reasoning"].toString()).isEqualTo("""{"effort":"low","exclude":true}""")
    val maxTokens = body["max_tokens"].toString().toInt()
    assertThat(maxTokens).isEqualTo(PreviewGuidelineRequests.replyTokenLimit(request, "low"))
    // The reply's worst case, plus the thinking allowance at `low`.
    assertThat(maxTokens - reply)
      .isEqualTo(PreviewGuidelineRequests.reasoningTokenAllowance("low") - 1_024)
    // Where OpenRouter budgets `low` as a share of max_tokens (about 20%), the rest still fits.
    assertThat((maxTokens * 0.8).toInt()).isAtLeast(reply)
  }

  @Test
  fun `a stream keeps its reasoning deltas, and no effort sends no reasoning object`() {
    val request = request("a")
    val streamed = OpenRouterClient.chatBody(request, "m", requireParameters = true, stream = true)
    // Streamed: the deltas are what tells a thinking model from a stalled one.
    assertThat(streamed["reasoning"].toString()).isEqualTo("""{"effort":"low"}""")

    val modelDefault = OpenRouterClient.chatBody(request, "m", reasoningEffort = null)
    assertThat(modelDefault.containsKey("reasoning")).isFalse()
    assertThat(modelDefault["max_tokens"].toString().toInt())
      .isEqualTo(
        PreviewGuidelineRequests.replyTokenLimit(request, OpenRouterClient.REASONING_OFF) - 1_024 +
          PreviewGuidelineRequests.REPLY_HEADROOM_TOKENS
      )

    val off = OpenRouterClient.chatBody(request, "m", OpenRouterClient.REASONING_OFF)
    assertThat((off["reasoning"] as JsonObject).toString()).isEqualTo("""{"effort":"none"}""")
  }

  @Test
  fun `a reply that spent its whole budget reasoning says so, with its reasoning tokens`() {
    val failure = GuidelineResponse.reasoningExhausted(exhausted.body)!!
    assertThat(failure.kind).isEqualTo(FailureKind.REASONING_EXHAUSTED)
    assertThat(failure.problem)
      .isEqualTo(
        "the model spent its whole token budget reasoning (24576 reasoning tokens, billed) and " +
          "wrote no reply"
      )
    assertThat(failure.splittable).isFalse()
    // A long reply cut at max_tokens is not that: it is a size problem, split as before.
    val long = completion("""{"verdicts":[{"ruleId"""", "length", 9_000, reasoning = 300)
    assertThat(GuidelineResponse.reasoningExhausted(long.body)).isNull()
    // Nor is a reply cut with no reasoning reported.
    assertThat(GuidelineResponse.reasoningExhausted(completion("", "length", 9_000).body)).isNull()
  }

  @Test
  fun `a reply out of reasoning budget is asked once more with reasoning off, not split`() {
    val model = ReasoningModel(reasoning = { _, _ -> exhausted }, withoutReasoning = ::passing)
    val run =
      engine(model, GuidelineRunOptions(triage = false))
        .run(guidelines, listOf(subject("a"), subject("b"), subject("c")))

    assertThat(model.asked).containsExactly("reasoning:a,b,c", "off:a,b,c").inOrder()
    assertThat(run.failedRequests).isEqualTo(0)
    assertThat(run.results.map { it.unchecked })
      .containsExactly(emptyList<String>(), emptyList<String>(), emptyList<String>())
    assertThat(run.reasoningTokens).isEqualTo(24_576)
    assertThat(run.completionTokens).isEqualTo(24_576 + 200)
    assertThat(run.reasoningRetries).isEqualTo(1)
    assertThat(run.problems.single())
      .isEqualTo(
        "1 reply(ies) spent the whole token budget reasoning and wrote no usable reply (24576 " +
          "reasoning token(s) billed in all); 1 request(s) were asked again with reasoning off, " +
          "and 1 answered. A lower reasoning effort leaves more of max_tokens to the reply"
      )
  }

  @Test
  fun `a reply out of budget even with reasoning off fails its batch without splitting it`() {
    val model = ReasoningModel(reasoning = { _, _ -> exhausted }, withoutReasoning = { exhausted })
    val run =
      engine(model, GuidelineRunOptions(triage = false))
        .run(guidelines, listOf(subject("a"), subject("b"), subject("c")))

    assertThat(model.asked).containsExactly("reasoning:a,b,c", "off:a,b,c").inOrder()
    assertThat(run.failedRequests).isEqualTo(1)
    assertThat(run.results.all { it.pending }).isTrue()
    assertThat(run.problems)
      .contains(
        "the model spent its whole token budget reasoning (24576 reasoning tokens, billed) and " +
          "wrote no reply, even with reasoning off"
      )
    assertThat(run.problems.none { "split" in it }).isTrue()
  }

  @Test
  fun `running out of reasoning budget does not use up the retries other batches need`() {
    // Room for one failed try to be followed up across the whole run.
    val options =
      GuidelineRunOptions(triage = false).withRetry(GuidelineRetry(maxFailedAttempts = 1))
    val timeout =
      ModelResponse.noAnswer("no bytes arrived for the 300 s read timeout", timedOut = true)
    var screenTries = 0
    val model =
      ReasoningModel(
        reasoning = { request, _ ->
          // The component batch (asked first) runs out reasoning; the screen batch's first try
          // times out, and its second is answered.
          if (request.subjects.single().id == "s1") {
            if (++screenTries == 1) timeout else passing(request)
          } else exhausted
        },
        withoutReasoning = { exhausted },
      )
    val run =
      engine(model, options)
        .run(guidelines, listOf(subject("c1"), subject("s1", GuidelineSurfaces.SCREEN)))

    assertThat(model.asked)
      .containsExactly("reasoning:c1", "off:c1", "reasoning:s1", "reasoning:s1")
      .inOrder()
    assertThat(run.results.single { it.previewId == "s1" }.pending).isFalse()
    assertThat(run.results.single { it.previewId == "c1" }.pending).isTrue()
    assertThat(run.problems.none { "used up its retries" in it }).isTrue()
  }

  @Test
  fun `a reply cut at max_tokens never passes the rules it left out`() {
    // Parseable, but cut: its `others: pass` may stand in for findings it never got to write.
    val cut =
      completion(
        """{"verdicts":[{"subjectId":"s1","ruleId":"touch","verdict":"fail","confidence":0.9,""" +
          """"nodeIds":[],"reason":"small","needs":[],"regions":[]}],""" +
          """"others":[{"subjectId":"s1","verdict":"pass","confidence":0.9}]}""",
        finishReason = "length",
        completion = 2_000,
        reasoning = 400,
      )
    val parsed =
      GuidelineResponse.parse(
          cut.body,
          GuidelineBatch(GuidelineSurfaces.COMPONENT, listOf(subject("a"))),
          emptyList(),
        )
        .getOrThrow()
    assertThat(parsed.truncated).isTrue()
    assertThat(parsed.othersPass).isEmpty()

    val model = ReasoningModel(reasoning = { _, _ -> cut }, withoutReasoning = { cut })
    val run =
      engine(model, GuidelineRunOptions(triage = false)).run(guidelines, listOf(subject("a")))
    val result = run.results.single()
    assertThat(result.implicitPasses).isEmpty()
    assertThat(result.unchecked).containsExactly("any")
    assertThat(result.failures().map { it.ruleId }).containsExactly("touch")
    assertThat(run.problems)
      .contains(
        "1 reply(ies) were cut at max_tokens: their findings were kept, but not their " +
          "statement that every other rule passes, so those rules are reported unchecked"
      )
  }

  @Test
  fun `a verdict reached at one reasoning effort is not reused at another`() {
    val dir = kotlin.io.path.createTempDirectory("reasoning-cache").toFile()
    try {
      val model =
        ReasoningModel(reasoning = { request, _ -> passing(request) }, withoutReasoning = ::passing)
      fun run(options: GuidelineRunOptions) =
        GuidelineEngine(
            model,
            cache = GuidelineResultCache(dir),
            options = options.withConcurrency(1),
          )
          .run(guidelines, listOf(subject("a")))
      val low = GuidelineRunOptions(triage = false)
      assertThat(low.cacheModel).isEqualTo(OpenRouterClient.DEFAULT_MODEL)

      run(low)
      assertThat(run(low).results.single().fromCache).isTrue()
      val high = low.withReasoningEffort("high")
      assertThat(high.withConcurrency(2).cacheModel).isEqualTo(high.cacheModel)
      assertThat(run(high).results.single().fromCache).isFalse()
      assertThat(run(low.withReasoningEffort(null)).results.single().fromCache).isFalse()
      assertThat(model.asked).hasSize(3)
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `the request body's usage is read for reasoning tokens`() {
    val tokens = GuidelineResponse.tokens(exhausted.body)!!
    assertThat(tokens).isEqualTo(CompletionTokens(24_576, 24_576))
    assertThat(GuidelineResponse.tokens("""{"usage":{"cost":0.1}}""")).isNull()
    assertThat(GUIDELINES_JSON.parseToJsonElement(exhausted.body).jsonObject["usage"].toString())
      .contains("reasoning_tokens")
  }
}
