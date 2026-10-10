package ee.schimke.composeai.guidelines

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.guidelines.protocol.CatalogGuidelinesV1
import ee.schimke.composeai.guidelines.protocol.GuidelineRequestV1
import ee.schimke.composeai.guidelines.protocol.GuidelineVerdictV1
import java.nio.file.Files
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test

/**
 * Replies list only what does not pass, plus an `others` statement per subject: expanded into
 * passes for exactly the rules asked and not listed, and never into a pass for a subject the reply
 * said nothing about.
 */
class GuidelineFailuresOnlyTest {
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
             "source": "https://developer.android.com/b"},
            {"id": "text", "kind": "visual", "severity": "warning", "guidance": "g", "check": "legible?",
             "source": "https://developer.android.com/c"},
            {"id": "consistent", "kind": "visual", "severity": "info", "guidance": "g",
             "check": "one primary?", "source": "https://developer.android.com/e", "scope": "set"}
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

  private fun completion(content: String, completionTokens: Int? = null): ModelResponse =
    ModelResponse(
      200,
      buildJsonObject {
        put("id", "gen-1")
        put("model", "m")
        put(
          "usage",
          buildJsonObject {
            put("cost", 0.001)
            completionTokens?.let { put("completion_tokens", it) }
          },
        )
        put(
          "choices",
          buildJsonArray {
            add(buildJsonObject { put("message", buildJsonObject { put("content", content) }) })
          },
        )
      }
        .toString(),
    )

  private class Model(val answer: (GuidelineRequestV1) -> ModelResponse) : GuidelineModel {
    val requests = mutableListOf<GuidelineRequestV1>()

    override fun complete(request: GuidelineRequestV1, model: String): ModelResponse {
      requests += request
      return answer(request)
    }
  }

  private fun verdict(subject: String?, rule: String, verdict: String) = buildJsonObject {
    put("subjectId", subject)
    put("ruleId", rule)
    put("verdict", verdict)
    put("confidence", 0.8)
    put("nodeIds", buildJsonArray {})
    put("reason", "Too small.")
    put("needs", buildJsonArray {})
    put("regions", buildJsonArray {})
  }

  private fun others(subject: String?, verdict: String = "pass") = buildJsonObject {
    put("subjectId", subject)
    put("verdict", verdict)
    put("confidence", 0.9)
  }

  private fun reply(verdicts: List<JsonObject>, others: List<JsonObject>) = buildJsonObject {
    put("verdicts", JsonArray(verdicts))
    put("others", JsonArray(others))
  }
    .toString()

  @Test
  fun `an others statement passes every rule asked and not listed, and says so`() {
    val model = Model {
      completion(
        reply(
          listOf(verdict("s1", "touch", "fail")),
          listOf(others("s1"), others("s2"), others(null)),
        )
      )
    }
    val run =
      GuidelineEngine(model, options = GuidelineRunOptions(triage = false))
        .run(guidelines, listOf(subject("a"), subject("b")))

    val a = run.results.single { it.previewId == "a" }
    val b = run.results.single { it.previewId == "b" }
    assertThat(a.unchecked).isEmpty()
    assertThat(a.failures().map { it.ruleId }).containsExactly("touch")
    assertThat(a.implicitPasses).containsExactly("any", "text", "consistent")
    assertThat(b.implicitPasses).containsExactly("any", "touch", "text", "consistent")
    // The record still carries a verdict per rule asked, each implicit pass saying so.
    val passes = b.record.verdicts.filter { it.verdict == GuidelineVerdictV1.PASS }
    assertThat(passes.map { it.ruleId }).containsExactly("any", "touch", "text", "consistent")
    assertThat(passes.map { it.reason }.distinct()).containsExactly(IMPLICIT_PASS_REASON)
    assertThat(passes.first().confidence).isEqualTo(0.9)
    assertThat(run.problems).isEmpty()
  }

  @Test
  fun `a subject the reply said nothing about is unchecked, not passed, and not cached`() {
    val dir = Files.createTempDirectory("guidelines").toFile()
    val cache = GuidelineResultCache(dir)
    val model = Model {
      completion(
        reply(
          listOf(verdict("s2", "any", "fail")),
          listOf(
            others("s1"),
            others("s3", PreviewGuidelineRequests.OTHERS_UNCHECKED),
            others(null),
          ),
        )
      )
    }
    val run =
      GuidelineEngine(model, cache = cache, options = GuidelineRunOptions(triage = false))
        .run(guidelines, listOf(subject("a"), subject("b"), subject("c")))

    val byId = run.results.associateBy { it.previewId }
    assertThat(byId.getValue("a").unchecked).isEmpty()
    // No statement for s2: what it did not list stays unchecked.
    assertThat(byId.getValue("b").unchecked).containsExactly("touch", "text")
    // Only the set-wide rule, which the statement with `subjectId` null passed for the batch.
    assertThat(byId.getValue("b").implicitPasses).containsExactly("consistent")
    // `unchecked` is a statement, but not a pass.
    assertThat(byId.getValue("c").unchecked).containsExactly("any", "touch", "text")
    assertThat(run.problems.joinToString()).contains("2 preview(s) got a reply that neither")
    // Kept with the record, so a result read back from the cache still says which passes were
    // implicit.
    assertThat(cache.get(subject("a"), guidelines, OpenRouterClient.DEFAULT_MODEL)?.implicitPasses)
      .containsExactly("any", "touch", "text", "consistent")
    assertThat(cache.get(subject("b"), guidelines, OpenRouterClient.DEFAULT_MODEL)).isNull()
    assertThat(cache.get(subject("c"), guidelines, OpenRouterClient.DEFAULT_MODEL)).isNull()
  }

  @Test
  fun `set rules no statement covers keep every subject of the batch out of the cache`() {
    val dir = Files.createTempDirectory("guidelines").toFile()
    val cache = GuidelineResultCache(dir)
    // Every subject's own rules pass; the set-wide rule gets no verdict and no statement.
    val model = Model { completion(reply(emptyList(), listOf(others("s1"), others("s2")))) }
    val run =
      GuidelineEngine(model, cache = cache, options = GuidelineRunOptions(triage = false))
        .run(guidelines, listOf(subject("a"), subject("b")))
    run.results.forEach { result ->
      assertThat(result.record.verdicts.map { it.ruleId }).doesNotContain("consistent")
    }
    assertThat(cache.get(subject("a"), guidelines, OpenRouterClient.DEFAULT_MODEL)).isNull()
    assertThat(cache.get(subject("b"), guidelines, OpenRouterClient.DEFAULT_MODEL)).isNull()
    assertThat(run.problems.joinToString()).contains("1 batch(es) got a reply that decided none")
  }

  @Test
  fun `a statement never passes a rule the request did not ask, and invented ids are dropped`() {
    val model = Model {
      completion(reply(listOf(verdict("s1", "made-up", "fail")), listOf(others("s1"))))
    }
    val run =
      GuidelineEngine(model, options = GuidelineRunOptions(triage = false))
        .run(guidelines, listOf(subject("a")))
    val result = run.results.single()
    assertThat(result.record.verdicts.map { it.ruleId }).containsExactly("any", "touch", "text")
    assertThat(result.failures()).isEmpty()
    assertThat(run.problems.joinToString()).contains("made-up")
  }

  @Test
  fun `the schema requires the others statements and the prompt asks for findings only`() {
    val schema = PreviewGuidelineRequests.RESPONSE_SCHEMA
    assertThat(schema.toString()).contains("\"required\":[\"verdicts\",\"others\"]")
    val request =
      PreviewGuidelineRequests.request(
        guidelines,
        GuidelineBatch("component", listOf(subject("a"), subject("b"))),
        "src",
        emptyList(),
      )
    assertThat(request.systemPrompt).contains("WRITE DOWN ONLY WHAT DOES NOT PASS")
    assertThat(request.userText).contains("one `others` entry for each of s1, s2")
    assertThat(request.userText).contains("and one with `subjectId` null")
    val body = OpenRouterClient.chatBody(request, "m")
    assertThat(body["max_tokens"]!!.jsonPrimitive.int)
      .isEqualTo(
        2 * 4 * PreviewGuidelineRequests.VERDICT_TOKENS +
          3 * PreviewGuidelineRequests.OTHERS_TOKENS +
          PreviewGuidelineRequests.REPLY_HEADROOM_TOKENS
      )
    assertThat(body["provider"]!!.jsonObject["require_parameters"].toString()).isEqualTo("true")
  }

  @Test
  fun `a reply cut at max_tokens says so`() {
    val cut =
      ModelResponse(
        200,
        buildJsonObject {
          put(
            "choices",
            buildJsonArray {
              add(
                buildJsonObject {
                  put("finish_reason", "length")
                  put("message", buildJsonObject { put("content", """{"verdicts":[{"sub""") })
                }
              )
            },
          )
        }
          .toString(),
      )
    val parsed =
      GuidelineResponse.parse(cut.body, GuidelineBatch("component", listOf(subject("a"))), listOf())
    assertThat(parsed.exceptionOrNull()?.message).contains("max_tokens")
  }

  /**
   * The reply-size measurement: twelve Wear screens asked 24 rules each, the request that timed out
   * on wear-m3-catalog#760, answered by a fake model that bills a token per four characters of what
   * it writes. Before, every one of the 288 verdicts was written out; now only the findings (here
   * three per screen, the expected quarter rounded down) and one statement per screen.
   */
  @Test
  fun `bench - a failures-only reply is a fraction of the every-verdict one`() {
    val rules = (1..24).map { "r$it" }
    val screens =
      CatalogGuidelinesLoader.parse(
          """
          {"schema": "compose-ui-builder/catalog-guidelines/v1", "catalog": "wear-m3",
           "platform": "wear", "version": 1, "rules": [
          """ +
            rules.joinToString(",") {
              """{"id": "$it", "kind": "visual", "severity": "warning",
                  "guidance": "Keep it legible at a glance.", "check": "legible?",
                  "source": "https://developer.android.com/$it", "surfaces": ["screen"]}"""
            } +
            "]}"
        )
        .guidelines!!
    val subjects = (1..12).map { subject("screen$it", GuidelineSurfaces.SCREEN) }
    fun tokens(content: String) = content.length / 4

    val before =
      reply(
        subjects.indices.flatMap { i ->
          rules.mapIndexed { r, rule -> verdict("s${i + 1}", rule, if (r < 3) "fail" else "pass") }
        },
        emptyList(),
      )
    val after =
      reply(
        subjects.indices.flatMap { i -> rules.take(3).map { verdict("s${i + 1}", it, "fail") } },
        subjects.indices.map { others("s${it + 1}") },
      )
    val beforeTokens = tokens(before)
    val afterTokens = tokens(after)
    println(
      "bench: 12 screens x 24 rules, one request: every-verdict reply $beforeTokens tokens, " +
        "failures-only reply $afterTokens tokens (${afterTokens * 100 / beforeTokens}%)"
    )
    assertThat(afterTokens).isLessThan(beforeTokens / 5)

    // Batched as the engine batches it now: two requests of six, each reply a few hundred tokens.
    val batches = PreviewGuidelineRequests.batches(screens, subjects)
    assertThat(batches.map { it.subjects.size }).containsExactly(6, 6).inOrder()
    val model = Model { request ->
      val content =
        reply(
          request.subjects.indices.flatMap { i ->
            rules.take(3).map { verdict("s${i + 1}", it, "fail") }
          },
          request.subjects.indices.map { others("s${it + 1}") },
        )
      completion(content, completionTokens = tokens(content))
    }
    val run =
      GuidelineEngine(model, options = GuidelineRunOptions(triage = false)).run(screens, subjects)
    val perRequest =
      model.requests.map { request -> PreviewGuidelineRequests.replyTokenLimit(request) }
    println(
      "bench: batched, ${model.requests.size} requests of " +
        "${model.requests.map { it.subjects.size }}; max_tokens per request $perRequest"
    )
    assertThat(run.results.flatMap { it.unchecked }).isEmpty()
    assertThat(run.results.sumOf { it.implicitPasses.size }).isEqualTo(12 * 21)
  }
}
