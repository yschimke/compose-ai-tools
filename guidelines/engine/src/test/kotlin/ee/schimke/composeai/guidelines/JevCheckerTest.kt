package ee.schimke.composeai.guidelines

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.guidelines.protocol.CatalogGuidelinesV1
import ee.schimke.composeai.guidelines.protocol.GuidelineEvidenceNeedV1
import ee.schimke.composeai.guidelines.protocol.GuidelineRequestV1
import ee.schimke.composeai.guidelines.protocol.GuidelineVerdictV1
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.Collections
import javax.imageio.ImageIO
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/** The experimental text-only checker, over a fake decisions API: no network. */
class JevCheckerTest {
  private val guidelines: CatalogGuidelinesV1 =
    CatalogGuidelinesLoader.parse(
        """
        {
          "schema": "compose-ui-builder/catalog-guidelines/v1",
          "catalog": "wear-m3", "platform": "wear", "version": 6,
          "rules": [
            {"id": "described", "kind": "structure", "severity": "warning", "guidance": "label icons",
             "check": "Does every icon-only control carry a content description?",
             "source": "https://developer.android.com/a"},
            {"id": "styles", "kind": "structure", "severity": "info", "guidance": "theme type",
             "check": "Do text nodes set a style?", "source": "https://developer.android.com/b"},
            {"id": "clipping", "kind": "visual", "severity": "warning", "guidance": "whole",
             "check": "Is every glyph drawn whole?", "source": "https://developer.android.com/c"},
            {"id": "consistent", "kind": "visual", "severity": "info", "guidance": "g",
             "check": "one primary?", "source": "https://developer.android.com/e", "scope": "set"}
          ]
        }
        """
      )
      .guidelines!!

  private val png: ByteArray =
    ByteArrayOutputStream()
      .also { ImageIO.write(BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB), "png", it) }
      .toByteArray()

  private fun subject(id: String) =
    PreviewSubject(
      previewId = id,
      label = id,
      renderHash = "h-$id",
      pictures = listOf(SubjectPicture("device", png, 40, 40)),
      source =
        "@Preview @Composable fun $id() = IconButton(onClick = {}) { Icon(Icons.Add, null) }",
    )

  /** A decisions API answering each question key with [answers]' choice for it, else `pass`. */
  private class FakeDecisions(
    val answers: (subject: String, key: String) -> Pair<String, Double>? = { _, _ -> null },
    val status: (call: Int) -> Int = { 200 },
  ) : GuidelineModel {
    val bodies: MutableList<JsonObject> = Collections.synchronizedList(mutableListOf())
    val completions = mutableListOf<GuidelineRequestV1>()

    override fun complete(request: GuidelineRequestV1, model: String): ModelResponse {
      completions += request
      error("the jev checker must not ask the vision model")
    }

    override fun decide(body: JsonObject): ModelResponse {
      bodies += body
      val call = bodies.size
      val code = status(call)
      if (code !in 200..299) return ModelResponse(code, """{"error":{"message":"busy"}}""")
      val name = body["state"]!!.jsonObject["preview"]!!.jsonObject["name"]!!.jsonPrimitive.content
      val questions = body["questions"]!!.jsonObject
      val answers =
        questions.keys.joinToString(",") { key ->
          val (choice, p) =
            answers(name, key)
              ?: if (key.endsWith("_node")) JevRuleRequests.NO_NODE to 0.9 else "pass" to 0.9
          """"$key":{"type":"choice","choice":"$choice","confidence":$p,
             "probabilities":{"$choice":$p}}"""
        }
      return ModelResponse(
        200,
        """{"id":"gen-dec-$call","model":"typesafe/jev-1.13-20260917","provider":"TypeSafe",
           "answers":{$answers},"usage":{"input_tokens":400,"output_tokens":20,"cost":0.00002}}""",
      )
    }
  }

  private fun jev(
    model: GuidelineModel,
    host: GuidelineEvidenceHost = GuidelineEvidenceHost.None,
    cache: GuidelineResultCache? = null,
    options: GuidelineRunOptions = GuidelineRunOptions(triage = false),
  ) = GuidelineEngine(model, host, cache, options.withChecker(GuidelineChecker.JEV))

  @Test
  fun `the checker defaults to vision and is parsed by name`() {
    val options = GuidelineRunOptions()
    assertThat(options.checker).isEqualTo(GuidelineChecker.VISION)
    assertThat(options.cacheModel).isEqualTo(OpenRouterClient.DEFAULT_MODEL)
    assertThat(GuidelineChecker.parse("jev")).isEqualTo(GuidelineChecker.JEV)
    assertThat(GuidelineChecker.parse(" Vision ")).isEqualTo(GuidelineChecker.VISION)
    assertThat(GuidelineChecker.parse("gpt")).isNull()
    // Neither builder drops what the other set.
    val both = options.withChecker(GuidelineChecker.JEV).withRetry(GuidelineRetry(maxAttempts = 1))
    assertThat(both.checker).isEqualTo(GuidelineChecker.JEV)
    assertThat(both.retry.maxAttempts).isEqualTo(1)
    assertThat(both.withChecker(GuidelineChecker.JEV).retry.maxAttempts).isEqualTo(1)
    assertThat(both.answeringModel).isEqualTo(JevTriage.MODEL)
  }

  @Test
  fun `structural rules are decided by Jev, one choice per rule, from text only`() {
    val model = FakeDecisions(answers = { _, key -> if (key == "r1") "fail" to 0.82 else null })
    val run = jev(model).run(guidelines, listOf(subject("a")))

    val body = model.bodies.single()
    assertThat(body["model"]!!.jsonPrimitive.content).isEqualTo(JevTriage.MODEL)
    val questions = body["questions"]!!.jsonObject
    assertThat(questions.keys).containsExactly("r1", "r2")
    val r1 = questions["r1"]!!.jsonObject
    assertThat(r1["type"]!!.jsonPrimitive.content).isEqualTo("choice")
    assertThat(r1["criteria"]!!.jsonObject.keys)
      .containsExactly("cannot_tell", "not_applicable", "fail", "pass")
      .inOrder()
    assertThat(r1.toString()).contains("content description")
    assertThat(body["state"].toString()).contains("IconButton(onClick")
    assertThat(model.completions).isEmpty()

    val result = run.results.single()
    val verdicts = result.record.verdicts.associateBy { it.ruleId }
    assertThat(verdicts.getValue("described").verdict).isEqualTo(GuidelineVerdictV1.FAIL)
    assertThat(verdicts.getValue("described").confidence).isWithin(1e-9).of(0.82)
    assertThat(verdicts.getValue("styles").verdict).isEqualTo(GuidelineVerdictV1.PASS)
    assertThat(result.record.model).isEqualTo(JevTriage.MODEL)
    assertThat(result.record.servedModel).isEqualTo("typesafe/jev-1.13-20260917")
    assertThat(result.record.provider).isEqualTo("TypeSafe")
    assertThat(result.record.costUsd).isWithin(1e-12).of(0.00002)
    assertThat(run.requests).isEqualTo(1)
    assertThat(run.failedRequests).isEqualTo(0)
  }

  @Test
  fun `visual rules are never asked and are unchecked with the text-only reason`() {
    val model = FakeDecisions()
    val run = jev(model).run(guidelines, listOf(subject("a")))

    val question = model.bodies.single()["questions"].toString()
    assertThat(question).doesNotContain("glyph")
    val result = run.results.single()
    assertThat(result.unchecked).containsExactly("clipping")
    val clipping = result.record.verdicts.single { it.ruleId == "clipping" }
    assertThat(clipping.verdict).isEqualTo(GuidelineVerdictV1.NEEDS_EVIDENCE)
    assertThat(clipping.reason).isEqualTo(JevChecker.TEXT_ONLY_REASON)
    assertThat(result.failures()).isEmpty()
    assertThat(run.problems.joinToString("\n")).contains("1 rule verdict(s) on 1 preview(s)")
    // A set rule is judged across a batch, which this checker never builds.
    assertThat(run.problems.joinToString("\n")).contains("set-scoped rule(s) were not asked")
  }

  @Test
  fun `cannot_tell and a weak answer are unchecked, never passed`() {
    val model =
      FakeDecisions(
        answers = { _, key ->
          when (key) {
            "r1" -> "cannot_tell" to 0.7
            "r2" -> "pass" to 0.4
            else -> null
          }
        }
      )
    val result = jev(model).run(guidelines, listOf(subject("a"))).results.single()
    assertThat(result.unchecked).containsExactly("described", "styles", "clipping")
    result.record.verdicts.forEach {
      assertThat(it.verdict).isEqualTo(GuidelineVerdictV1.NEEDS_EVIDENCE)
      assertThat(it.reason).startsWith(JevChecker.TEXT_ONLY_REASON)
    }
  }

  @Test
  fun `a11y data is fetched up front for the subjects asked, and a fail cites its node`() {
    val host = A11yHost()
    val model =
      FakeDecisions(
        answers = { _, key ->
          when (key) {
            "r1" -> "fail" to 0.9
            "r1_node" -> "add" to 0.8
            else -> null
          }
        }
      )
    val dir = Files.createTempDirectory("jev-a11y").toFile()
    try {
      val cache = GuidelineResultCache(dir)
      // `b` is answered from the cache: it is not fetched for.
      val warm = jev(FakeDecisions(), cache = cache).run(guidelines, listOf(subject("b")))
      assertThat(warm.results.single().fromCache).isFalse()
      host.prefetched.clear()

      val run = jev(model, host, cache).run(guidelines, listOf(subject("a"), subject("b")))
      assertThat(host.prefetched).containsExactly(setOf("a"))
      val body = model.bodies.single()
      assertThat(body["state"].toString()).contains("add | Button | Add")
      assertThat(body["state"].toString()).contains("TouchTargetSizeCheck ERROR")
      assertThat(
          body["questions"]!!.jsonObject["r1_node"]!!.jsonObject["criteria"]!!.jsonObject.keys
        )
        .containsExactly(JevRuleRequests.NO_NODE, "add")
      val fail = run.results.single { it.previewId == "a" }.failures().single()
      assertThat(fail.nodeIds).containsExactly("add")
      assertThat(fail.reason).contains("on add (Button 'Add')")
      assertThat(run.results.single { it.previewId == "b" }.fromCache).isTrue()
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `vision and jev results never answer for each other`() {
    val dir = Files.createTempDirectory("jev-cache").toFile()
    try {
      val cache = GuidelineResultCache(dir)
      jev(FakeDecisions(), cache = cache).run(guidelines, listOf(subject("a")))
      val options = GuidelineRunOptions(triage = false)
      assertThat(cache.get(subject("a"), guidelines, options.cacheModel)).isNull()
      assertThat(
          cache.get(
            subject("a"),
            guidelines,
            options.withChecker(GuidelineChecker.JEV).cacheModel,
          )
        )
        .isNotNull()
      assertThat(options.withChecker(GuidelineChecker.JEV).cacheModel)
        .isNotEqualTo(options.cacheModel)

      // A second jev run is answered from the cache without a request.
      val again = FakeDecisions()
      val run = jev(again, cache = cache).run(guidelines, listOf(subject("a")))
      assertThat(again.bodies).isEmpty()
      assertThat(run.results.single().fromCache).isTrue()
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `answers to questions nobody asked are dropped and counted`() {
    val model =
      object : GuidelineModel {
        override fun complete(request: GuidelineRequestV1, model: String) = error("unused")

        override fun decide(body: JsonObject) =
          ModelResponse(
            200,
            """{"model":"typesafe/jev-1.13","answers":{
              "r1":{"type":"choice","choice":"pass","probabilities":{"pass":0.9}},
              "r2":{"type":"choice","choice":"pass","probabilities":{"pass":0.9}},
              "clipping":{"type":"choice","choice":"fail","probabilities":{"fail":0.9}}},
              "usage":{"input_tokens":1,"output_tokens":1}}""",
          )
      }
    val run = jev(model).run(guidelines, listOf(subject("a")))
    assertThat(run.results.single().record.verdicts.map { it.ruleId })
      .containsExactly("described", "styles", "clipping")
    assertThat(run.results.single().failures()).isEmpty()
    assertThat(run.problems.joinToString("\n")).contains("1 answer(s) named a question")
  }

  @Test
  fun `a failed request is retried, then reported pending rather than passed`() {
    val flaky = FakeDecisions(status = { call -> if (call == 1) 503 else 200 })
    val engine = jev(flaky).also { it.sleep = {} }
    val ok = engine.run(guidelines, listOf(subject("a")))
    assertThat(flaky.bodies).hasSize(2)
    assertThat(ok.failedRequests).isEqualTo(0)

    val down = FakeDecisions(status = { 401 })
    val run = jev(down).run(guidelines, listOf(subject("a")))
    assertThat(down.bodies).hasSize(1)
    assertThat(run.failedRequests).isEqualTo(1)
    assertThat(run.results.single().pending).isTrue()
    assertThat(run.results.single().unchecked).containsExactly("described", "styles", "clipping")
  }

  @Test
  fun `subjects are asked in parallel and each gets its own answers`() {
    val model =
      FakeDecisions(
        answers = { name, key -> if (name == "c3" && key == "r2") "fail" to 0.9 else null }
      )
    val subjects = (1..9).map { subject("c$it") }
    val run = jev(model).run(guidelines, subjects)
    assertThat(model.bodies).hasSize(9)
    assertThat(run.results.map { it.previewId })
      .containsExactlyElementsIn(subjects.map { it.previewId })
    assertThat(run.results.filter { it.failures().isNotEmpty() }.map { it.previewId })
      .containsExactly("c3")
    assertThat(run.costUsd).isWithin(1e-12).of(9 * 0.00002)
  }

  @Test
  fun `the cost cap stops asking`() {
    val model = FakeDecisions()
    val checker =
      JevChecker(
        model,
        GuidelineEvidenceHost.None,
        null,
        GuidelineRunOptions(triage = false, maxCostUsd = 0.00003).withChecker(GuidelineChecker.JEV),
        System::currentTimeMillis,
      )
    checker.parallelism = 1
    val run = checker.run(guidelines, (1..4).map { subject("c$it") })
    // The first costs 0.00002; a second at that price would cross 0.00003.
    assertThat(model.bodies).hasSize(1)
    assertThat(run.results.count { it.pending }).isEqualTo(3)
    assertThat(run.problems.joinToString("\n")).contains("the cost cap")
  }

  private class A11yHost : GuidelineEvidenceHost {
    val prefetched = mutableListOf<Set<String>>()

    override val available = listOf(PreviewGuidelineRequests.KIND_A11Y)

    override fun prefetch(needs: Map<String, List<GuidelineEvidenceNeedV1>>) {
      prefetched += needs.keys
    }

    override fun nodes(previewId: String) =
      listOf(PreviewNode("add", "Button", "Add", 1, 1, 10, 10, listOf("clickable")))

    override fun checks(previewId: String) =
      listOf(PreviewCheck("TouchTargetSizeCheck", "ERROR", "24dp tall", "Add", "1,1,10,10"))
  }
}
