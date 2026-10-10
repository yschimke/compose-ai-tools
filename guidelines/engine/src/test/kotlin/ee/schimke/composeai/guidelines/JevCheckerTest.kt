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
import kotlinx.serialization.json.JsonPrimitive
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
            {"id": "touch", "kind": "structure", "severity": "warning", "guidance": "48dp",
             "check": "Is the tap area of every tappable control at least 48dp?",
             "source": "https://developer.android.com/t"},
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
      // 40px at 40dp: one pixel a dp, so node sizes read straight off their bounds.
      pictures = listOf(SubjectPicture("device", png, 40, 40)),
      source =
        "@Preview @Composable fun $id() = Frame { IconButton(onClick = {}) { Icon(Icons.Add, " +
          "null) } }\n\n// Frame, which the preview calls (Frame.kt:3):\n" +
          "@Composable\nfun Frame(content: @Composable () -> Unit) {\n  Box { content() }\n}",
    )

  private fun questions(body: JsonObject): JsonObject = body["questions"]!!.jsonObject

  private fun name(body: JsonObject): String =
    body["state"]!!.jsonObject["preview"]!!.jsonObject["name"]!!.jsonPrimitive.content

  /** The rule a question key asks, read back from its instructions. */
  private fun ruleOf(body: JsonObject, key: String): String? =
    (questions(body)[key]?.jsonObject?.get("instructions") as? JsonObject)
      ?.get("rule")
      ?.jsonPrimitive
      ?.content

  private fun isNode(key: String) = key.endsWith("_node")

  /** A decisions API answering each question with [answer]'s choice for it, else `pass`. */
  private inner class FakeDecisions(
    val answer: (body: JsonObject, rule: String?, key: String) -> Pair<String, Double>? =
      { _, _, _ ->
        null
      },
    val status: (call: Int) -> Int = { 200 },
  ) : GuidelineModel {
    val bodies: MutableList<JsonObject> = Collections.synchronizedList(mutableListOf())

    override fun complete(request: GuidelineRequestV1, model: String): ModelResponse =
      error("the jev checker must not ask the vision model")

    override fun decide(body: JsonObject): ModelResponse {
      bodies += body
      val call = bodies.size
      val code = status(call)
      if (code !in 200..299) return ModelResponse(code, """{"error":{"message":"busy"}}""")
      val answers =
        questions(body).keys.joinToString(",") { key ->
          val rule = ruleOf(body, key.removeSuffix("_node"))
          val (choice, p) =
            answer(body, rule, key)
              ?: if (isNode(key)) JevRuleRequests.NO_NODE to 0.9 else "pass" to 0.9
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

  /** A host holding accessibility data: [inHand] without fetching, as a staged handoff does. */
  private class A11yHost(val inHand: Boolean = true) : GuidelineEvidenceHost {
    val prefetched = mutableListOf<Set<String>>()
    private val fetched = mutableSetOf<String>()

    override val available = listOf(PreviewGuidelineRequests.KIND_A11Y)

    override fun prefetch(needs: Map<String, List<GuidelineEvidenceNeedV1>>) {
      prefetched += needs.keys
      fetched += needs.keys
    }

    override fun summary(previewId: String): String? =
      if (inHand || previewId in fetched)
        PreviewGuidelineRequests.a11ySummary(nodes(previewId), checks(previewId))
      else null

    // A 24dp icon button: 24px at one pixel a dp.
    override fun nodes(previewId: String) =
      listOf(PreviewNode("add", "Button", "Add", 1, 1, 25, 25, listOf("clickable")))

    override fun checks(previewId: String) =
      listOf(
        PreviewCheck(
          "TouchTargetSizeCheck",
          "ERROR",
          "This item's height is 24dp. Consider making the height of this touch target 48dp or " +
            "larger.",
          "Add",
          "1,1,25,25",
        )
      )
  }

  private fun jev(
    model: GuidelineModel,
    host: GuidelineEvidenceHost = GuidelineEvidenceHost.None,
    cache: GuidelineResultCache? = null,
    rounds: Int = 3,
  ) =
    GuidelineEngine(
        model,
        host,
        cache,
        GuidelineRunOptions(triage = false, maxRounds = rounds).withChecker(GuidelineChecker.JEV),
      )
      .also { it.sleep = {} }

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
    assertThat(both.cacheModel).startsWith("checker:jev@")
  }

  @Test
  fun `round 0 is minimal - an excerpt, the summary line and the rule's own facts`() {
    val model = FakeDecisions()
    jev(model, A11yHost()).run(guidelines, listOf(subject("a")))

    val body = model.bodies.single()
    val state = body["state"]!!.jsonObject
    // The wrapper's signature, not its body.
    val excerpt = state["source_excerpt"]!!.jsonPrimitive.content
    assertThat(excerpt).contains("fun Frame(content: @Composable () -> Unit) {")
    assertThat(excerpt).doesNotContain("Box { content() }")
    // The summary line, not the node list.
    assertThat(state["accessibility"]).isInstanceOf(JsonPrimitive::class.java)
    assertThat(state["accessibility"]!!.jsonPrimitive.content)
      .contains("1 node(s), none scrollable; ATF: 1 ERROR TouchTargetSizeCheck")
    assertThat(state["facts"]!!.jsonPrimitive.content).contains("touch-target 2 (2 flagged)")
    // The touch rule is handed its facts, decisive first.
    val touch = questions(body).entries.single { ruleOf(body, it.key) == "touch" }.value.jsonObject
    val facts = touch["instructions"]!!.jsonObject["relevant_facts"].toString()
    assertThat(facts).contains("24dp short of the 48dp minimum touch target")
    assertThat(facts).contains("ATF TouchTargetSizeCheck ERROR on 'Add': measured 24dp, needs 48dp")
    assertThat(touch["criteria"]!!.jsonObject.keys.toList())
      .containsExactly(
        "cannot_tell",
        "needs:a11y",
        "needs:source",
        "needs:facts",
        "not_applicable",
        "fail",
        "pass",
      )
      .inOrder()
    assertThat(touch["criteria"]!!.jsonObject["fail"].toString()).contains("smaller than 48dp")
    // Visual rules are never asked.
    assertThat(questions(body).toString()).doesNotContain("glyph")
  }

  @Test
  fun `a fail carries the decisive fact as its reason and the fact's node`() {
    val model =
      FakeDecisions(
        answer = { _, rule, key -> if (rule == "touch" && !isNode(key)) "fail" to 0.88 else null }
      )
    val run = jev(model, A11yHost()).run(guidelines, listOf(subject("a")))
    val fail = run.results.single().failures().single()
    assertThat(fail.ruleId).isEqualTo("touch")
    assertThat(fail.nodeIds).containsExactly("add")
    assertThat(fail.reason).startsWith("Jev (text-only, round 1): ")
    assertThat(fail.reason).contains("48dp")
    val record = run.results.single().record
    assertThat(record.model).isEqualTo(JevTriage.MODEL)
    assertThat(record.servedModel).isEqualTo("typesafe/jev-1.13-20260917")
    assertThat(record.provider).isEqualTo("TypeSafe")
    assertThat(record.costUsd).isWithin(1e-12).of(0.00002)
    val trace = run.results.single().jev!!
    assertThat(trace.rounds).isEqualTo(1)
    assertThat(trace.rules.single { it.ruleId == "touch" }.facts)
      .contains(GuidelineFacts.TOUCH_TARGET)
  }

  @Test
  fun `a rule that asks for a11y is re-asked alone with the full nodes, fetched for it only`() {
    val host = A11yHost(inHand = false)
    val model =
      FakeDecisions(
        answer = { body, rule, key ->
          val full = body["state"]!!.jsonObject["accessibility"] is JsonObject
          when {
            isNode(key) -> if (full && name(body) == "a") "add" to 0.8 else null
            rule == "described" && name(body) == "a" && !full -> "needs:a11y" to 0.7
            rule == "described" && name(body) == "a" -> "fail" to 0.8
            else -> null
          }
        }
      )
    val run = jev(model, host).run(guidelines, listOf(subject("a"), subject("b")))

    // Round 0 had nothing in hand: no fetch, and the state says so.
    val first = model.bodies.first { name(it) == "a" }
    assertThat(first["state"]!!.jsonObject["accessibility"]!!.jsonPrimitive.content)
      .isEqualTo("not fetched")
    // One prefetch, for the subject that asked only.
    assertThat(host.prefetched).containsExactly(setOf("a"))
    val followUp = model.bodies.filter { name(it) == "a" }[1]
    assertThat(followUp["state"].toString()).contains("add | Button | Add")
    assertThat(questions(followUp).keys.mapNotNull { ruleOf(followUp, it) })
      .containsExactly("described")
    assertThat(model.bodies.count { name(it) == "b" }).isEqualTo(1)

    val a = run.results.single { it.previewId == "a" }
    val fail = a.failures().single()
    assertThat(fail.ruleId).isEqualTo("described")
    assertThat(fail.nodeIds).containsExactly("add")
    assertThat(fail.reason).startsWith("Jev (text-only, round 2)")
    assertThat(a.jev!!.rules.single { it.ruleId == "described" }.requested).containsExactly("a11y")
    assertThat(run.problems.joinToString("\n")).contains("Jev asked for more evidence: a11y ×1")
  }

  @Test
  fun `cannot_tell asks for everything still offered, and is unchecked only after the last round`() {
    val model =
      FakeDecisions(
        answer = { _, rule, key ->
          if (rule == "described" && !isNode(key)) "cannot_tell" to 0.7 else null
        }
      )
    val run = jev(model, A11yHost(), rounds = 1).run(guidelines, listOf(subject("a")))

    assertThat(model.bodies).hasSize(2)
    val last = model.bodies[1]
    assertThat(last["state"].toString()).contains("add | Button | Add")
    assertThat(last["state"]!!.jsonObject.keys).contains("source")
    // The last round offers nothing more: decide, or cannot_tell.
    val options = questions(last).values.first().jsonObject["criteria"]!!.jsonObject.keys
    assertThat(options.none { it.startsWith("needs:") }).isTrue()

    val result = run.results.single()
    assertThat(result.unchecked).containsExactly("described", "clipping")
    val described = result.record.verdicts.single { it.ruleId == "described" }
    assertThat(described.verdict).isEqualTo(GuidelineVerdictV1.NEEDS_EVIDENCE)
    assertThat(described.reason).startsWith(JevChecker.TEXT_ONLY_REASON)
    assertThat(described.reason).contains("after 2 round(s)")
    assertThat(result.record.verdicts.single { it.ruleId == "clipping" }.reason)
      .isEqualTo(JevChecker.TEXT_ONLY_REASON)
    assertThat(result.failures()).isEmpty()
    val problems = run.problems.joinToString("\n")
    assertThat(problems).contains("2 rule verdict(s) on 1 preview(s)")
    assertThat(problems).contains("set-scoped rule(s) were not asked")
  }

  @Test
  fun `a weak answer is not taken as a verdict`() {
    val model =
      FakeDecisions(
        answer = { _, rule, key -> if (rule == "touch" && !isNode(key)) "pass" to 0.4 else null }
      )
    val result = jev(model, rounds = 0).run(guidelines, listOf(subject("a"))).results.single()
    assertThat(result.unchecked).contains("touch")
    assertThat(result.record.verdicts.single { it.ruleId == "touch" }.reason)
      .startsWith(JevChecker.TEXT_ONLY_REASON)
  }

  @Test
  fun `the full-a11y-up-front path is an internal option, off by default`() {
    val host = A11yHost(inHand = false)
    val model = FakeDecisions()
    val checker =
      JevChecker(
        model,
        host,
        null,
        GuidelineRunOptions(triage = false).withChecker(GuidelineChecker.JEV),
        System::currentTimeMillis,
      )
    assertThat(checker.a11yUpFront).isFalse()
    checker.a11yUpFront = true
    checker.run(guidelines, listOf(subject("a")))
    assertThat(host.prefetched).containsExactly(setOf("a"))
    assertThat(model.bodies.single()["state"].toString()).contains("add | Button | Add")
  }

  @Test
  fun `vision and jev results never answer for each other, and the trace survives the cache`() {
    val dir = Files.createTempDirectory("jev-cache").toFile()
    try {
      val cache = GuidelineResultCache(dir)
      jev(FakeDecisions(), cache = cache).run(guidelines, listOf(subject("a")))
      val options = GuidelineRunOptions(triage = false)
      assertThat(cache.get(subject("a"), guidelines, options.cacheModel)).isNull()
      val jevOptions = options.withChecker(GuidelineChecker.JEV)
      assertThat(cache.get(subject("a"), guidelines, jevOptions.cacheModel)).isNotNull()
      assertThat(jevOptions.cacheModel).isNotEqualTo(options.cacheModel)

      val again = FakeDecisions()
      val run = jev(again, cache = cache).run(guidelines, listOf(subject("a")))
      assertThat(again.bodies).isEmpty()
      assertThat(run.results.single().fromCache).isTrue()
      assertThat(run.results.single().jev).isNotNull()
      // A run answered wholly from the cache still says what it left unchecked.
      val problems = run.problems.joinToString("\n")
      assertThat(problems).contains("1 rule verdict(s) on 1 preview(s)")
      assertThat(problems).contains("set-scoped rule(s) were not asked")
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
    val run = jev(model, rounds = 0).run(guidelines, listOf(subject("a")))
    assertThat(run.results.single().record.verdicts.map { it.ruleId })
      .containsExactly("described", "touch", "clipping")
    assertThat(run.results.single().failures()).isEmpty()
    assertThat(run.problems.joinToString("\n")).contains("1 answer(s) named a question")
  }

  @Test
  fun `a failed request is retried, then reported pending rather than passed`() {
    val flaky = FakeDecisions(status = { call -> if (call == 1) 503 else 200 })
    val ok = jev(flaky).run(guidelines, listOf(subject("a")))
    assertThat(flaky.bodies).hasSize(2)
    assertThat(ok.failedRequests).isEqualTo(0)

    val down = FakeDecisions(status = { 401 })
    val run = jev(down).run(guidelines, listOf(subject("a")))
    assertThat(down.bodies).hasSize(1)
    assertThat(run.failedRequests).isEqualTo(1)
    assertThat(run.results.single().pending).isTrue()
    assertThat(run.results.single().unchecked).containsExactly("described", "touch", "clipping")
  }

  @Test
  fun `a failure with no cost does not price the pool for the requests waiting on it`() {
    // The first reply is a 503 with no usage: the next requests must still go one at a time
    // until a priced reply, so the cap is not crossed by requests reserved at zero.
    val model = FakeDecisions(status = { call -> if (call == 1) 503 else 200 })
    val checker =
      JevChecker(
        model,
        GuidelineEvidenceHost.None,
        null,
        GuidelineRunOptions(triage = false, maxCostUsd = 0.00003).withChecker(GuidelineChecker.JEV),
        System::currentTimeMillis,
      )
    checker.sleep = {}
    checker.parallelism = 4
    val run = checker.run(guidelines, (1..8).map { subject("c$it") })
    // The failed one, its retry (priced at 0.00002), and nothing more under a 0.00003 cap.
    assertThat(model.bodies).hasSize(2)
    assertThat(run.costUsd).isAtMost(0.00003)
  }

  @Test
  fun `a subject only set rules apply to is not reported as checked`() {
    val setOnly =
      CatalogGuidelinesLoader.parse(
          """
          {"schema": "compose-ui-builder/catalog-guidelines/v1", "catalog": "wear-m3",
           "platform": "wear", "version": 1, "rules": [
            {"id": "consistent", "kind": "structure", "severity": "info", "guidance": "g",
             "check": "one primary?", "source": "https://developer.android.com/e", "scope": "set"}
          ]}
          """
        )
        .guidelines!!
    val model = FakeDecisions()
    val run = jev(model).run(setOnly, listOf(subject("a")))
    assertThat(model.bodies).isEmpty()
    assertThat(run.results.single().noRules).contains("only set-scoped rules")
  }

  @Test
  fun `under a cap, never-checked subjects are asked before stale ones`() {
    val dir = Files.createTempDirectory("jev-order").toFile()
    try {
      val cache = GuidelineResultCache(dir)
      // `old` was checked once, under another render: stale now.
      jev(FakeDecisions(), cache = cache).run(guidelines, listOf(subject("old")))
      val changed = subject("old").copy(renderHash = "h-old-2")
      val model = FakeDecisions()
      val checker =
        JevChecker(
          model,
          GuidelineEvidenceHost.None,
          cache,
          GuidelineRunOptions(triage = false, maxCostUsd = 0.00003)
            .withChecker(GuidelineChecker.JEV),
          System::currentTimeMillis,
        )
      checker.parallelism = 4
      val run = checker.run(guidelines, listOf(changed, subject("new")))
      assertThat(model.bodies.map { name(it) }).containsExactly("new")
      assertThat(run.results.single { it.previewId == "old" }.pending).isTrue()
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `a subject split into chunks keeps the verdicts of a chunk asked before the cap`() {
    // Thirty rules with long guidance: more than one request's worth of questions.
    val long = "x".repeat(8_000)
    val many =
      CatalogGuidelinesLoader.parse(
          """
          {"schema": "compose-ui-builder/catalog-guidelines/v1", "catalog": "wear-m3",
           "platform": "wear", "version": 1, "rules": [""" +
            (1..30).joinToString(",") { i ->
              """{"id": "rule$i", "kind": "structure", "severity": "info", "guidance": "$long",
                  "check": "check $i?", "source": "https://developer.android.com/r$i"}"""
            } +
            "]}"
        )
        .guidelines!!
    val model = FakeDecisions()
    val checker =
      JevChecker(
        model,
        GuidelineEvidenceHost.None,
        null,
        GuidelineRunOptions(triage = false, maxCostUsd = 0.00003).withChecker(GuidelineChecker.JEV),
        System::currentTimeMillis,
      )
    val result = checker.run(many, listOf(subject("a"))).results.single()
    assertThat(model.bodies).hasSize(1)
    val asked = questions(model.bodies.single()).keys.count { !isNode(it) }
    assertThat(asked).isLessThan(30)
    val passed = result.record.verdicts.count { it.verdict == GuidelineVerdictV1.PASS }
    assertThat(passed).isEqualTo(asked)
    assertThat(result.unchecked).hasSize(30 - asked)
    assertThat(result.pending).isFalse()
  }

  @Test
  fun `a rate limit pauses every worker for the wait the server asked`() {
    val waits = Collections.synchronizedList(mutableListOf<Long>())
    var calls = 0
    val inner = FakeDecisions()
    val limited =
      object : GuidelineModel by inner {
        override fun decide(body: JsonObject): ModelResponse {
          if (synchronized(this) { calls++ } == 0) {
            return ModelResponse(429, """{"error":{"message":"rate limited"}}""").also {
              it.retryAfterMillis = 40
            }
          }
          return inner.decide(body)
        }
      }
    val engine = jev(limited)
    engine.sleep = {
      waits += it
      Thread.sleep(it)
    }
    val run = engine.run(guidelines, (1..3).map { subject("c$it") })
    assertThat(run.failedRequests).isEqualTo(0)
    assertThat(inner.bodies).hasSize(3)
    // The pause was waited out, not a per-request backoff.
    assertThat(waits).isNotEmpty()
    assertThat(waits.all { it <= 40 }).isTrue()
  }

  @Test
  fun `a Retry-After past the retry ceiling pauses no one`() {
    val inner = FakeDecisions()
    var calls = 0
    val limited =
      object : GuidelineModel by inner {
        override fun decide(body: JsonObject): ModelResponse {
          if (synchronized(this) { calls++ } == 0) {
            return ModelResponse(429, """{"error":{"message":"rate limited"}}""").also {
              it.retryAfterMillis = 3_600_000
            }
          }
          return inner.decide(body)
        }
      }
    val waits = Collections.synchronizedList(mutableListOf<Long>())
    val engine = jev(limited)
    engine.sleep = { waits += it }
    val run = engine.run(guidelines, (1..3).map { subject("c$it") })
    // The hour is not waited, by the request that met it or by any other: that one fails, the
    // rest are asked.
    assertThat(waits.none { it > 60_000 }).isTrue()
    assertThat(run.failedRequests).isEqualTo(1)
    assertThat(inner.bodies).hasSize(2)
  }

  @Test
  fun `a host that throws for one preview fails that preview alone`() {
    val host =
      object : GuidelineEvidenceHost {
        override val available = listOf(PreviewGuidelineRequests.KIND_A11Y)

        override fun available(previewId: String): List<String> =
          if (previewId == "bad") error("no data for $previewId") else available
      }
    val model = FakeDecisions()
    val run = jev(model, host).run(guidelines, listOf(subject("good"), subject("bad")))
    assertThat(run.results.single { it.previewId == "good" }.pending).isFalse()
    assertThat(run.results.single { it.previewId == "bad" }.pending).isTrue()
    assertThat(run.failedRequests).isEqualTo(1)
    assertThat(model.bodies.map { name(it) }).containsExactly("good")
  }

  @Test
  fun `subjects are asked in parallel and each gets its own answers`() {
    val model =
      FakeDecisions(
        answer = { body, rule, key ->
          if (name(body) == "c3" && rule == "touch" && !isNode(key)) "fail" to 0.9 else null
        }
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
  fun `the cost cap stops asking, even with requests in parallel`() {
    val model = FakeDecisions()
    val checker =
      JevChecker(
        model,
        GuidelineEvidenceHost.None,
        null,
        GuidelineRunOptions(triage = false, maxCostUsd = 0.00003).withChecker(GuidelineChecker.JEV),
        System::currentTimeMillis,
      )
    // Four at once: before the first answer the price is unknown, so the others wait for it
    // rather than all reserving at zero and crossing the cap together.
    checker.parallelism = 4
    val run = checker.run(guidelines, (1..8).map { subject("c$it") })
    assertThat(model.bodies).hasSize(1)
    assertThat(run.results.count { it.pending }).isEqualTo(7)
    assertThat(run.problems.joinToString("\n")).contains("the cost cap")
  }
}
