package ee.schimke.composeai.guidelines

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.guidelines.protocol.CatalogGuidelinesV1
import ee.schimke.composeai.guidelines.protocol.GuidelineRequestV1
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

/**
 * Several requests in flight at once: faster, never past the cost cap together, paused together by
 * a rate limit, and the same result whichever request finishes first.
 */
class GuidelineConcurrencyTest {
  private val rules = (1..24).map { "r$it" }

  /** Twenty-four rules a screen, as wear-m3-catalog's screen catalog asks. */
  private val guidelines: CatalogGuidelinesV1 =
    CatalogGuidelinesLoader.parse(
        """
        {"schema": "compose-ui-builder/catalog-guidelines/v1", "catalog": "wear-m3",
         "platform": "wear", "version": 1, "rules": [
        """ +
          rules.joinToString(",") {
            """{"id": "$it", "kind": "visual", "severity": "warning", "guidance": "g",
                "check": "ok?", "source": "https://developer.android.com/$it",
                "surfaces": ["screen"]}"""
          } +
          "]}"
      )
      .guidelines!!

  private fun screen(id: String) =
    PreviewSubject(
      previewId = id,
      label = id,
      surface = GuidelineSurfaces.SCREEN,
      renderHash = "h-$id",
      pictures = listOf(SubjectPicture("device", byteArrayOf(1, 2, 3), 40, 40)),
    )

  /** A reply failing `r1` for the first subject and passing everything else. */
  private fun answer(request: GuidelineRequestV1, cost: Double = 0.001): ModelResponse {
    val content = buildJsonObject {
      put(
        "verdicts",
        buildJsonArray {
          add(
            buildJsonObject {
              put("subjectId", "s1")
              put("ruleId", "r1")
              put("verdict", "fail")
              put("confidence", 0.9)
              put("nodeIds", buildJsonArray {})
              put("reason", "Clipped.")
              put("needs", buildJsonArray {})
              put("regions", buildJsonArray {})
            }
          )
        },
      )
      put(
        "others",
        buildJsonArray {
          request.subjects.indices.forEach {
            add(
              buildJsonObject {
                put("subjectId", "s${it + 1}")
                put("verdict", "pass")
                put("confidence", 0.9)
              }
            )
          }
        },
      )
    }
      .toString()
    return ModelResponse(
      200,
      buildJsonObject {
        put("id", "gen")
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

  /** A model answering after [latency], counting how many requests it holds at once. */
  private class SlowModel(
    val latency: (GuidelineRequestV1) -> Long,
    val reply: (GuidelineRequestV1) -> ModelResponse,
  ) : GuidelineModel {
    val inFlight = AtomicInteger()
    val mostInFlight = AtomicInteger()
    val calls = AtomicInteger()

    override fun complete(request: GuidelineRequestV1, model: String): ModelResponse {
      calls.incrementAndGet()
      val now = inFlight.incrementAndGet()
      mostInFlight.accumulateAndGet(now, ::maxOf)
      try {
        Thread.sleep(latency(request))
        return reply(request)
      } finally {
        inFlight.decrementAndGet()
      }
    }
  }

  private fun options(concurrency: Int, maxCost: Double? = null) =
    GuidelineRunOptions(triage = false, maxCostUsd = maxCost).withConcurrency(concurrency)

  /**
   * The wall-time measurement: 24 Wear screens, four batches of six, each request taking 400 ms on
   * a fake model. One at a time is four latencies; four at once is about one.
   */
  @Test
  fun `bench - four requests at once take about the time of one`() {
    val screens = (1..24).map { screen("screen$it") }
    fun timed(concurrency: Int): Pair<Long, GuidelineRunResult> {
      val model = SlowModel({ 400 }, { answer(it) })
      val start = System.nanoTime()
      val run = GuidelineEngine(model, options = options(concurrency)).run(guidelines, screens)
      return (System.nanoTime() - start) / 1_000_000 to run
    }
    val (sequential, one) = timed(1)
    val (parallel, four) = timed(4)
    println(
      "bench: 24 screens in 4 batches, 400 ms a request: concurrency 1 took $sequential ms, " +
        "concurrency 4 took $parallel ms"
    )
    assertThat(parallel).isLessThan(sequential / 2)
    assertThat(four.results.map { it.record.verdicts })
      .isEqualTo(one.results.map { it.record.verdicts })
    assertThat(four.results.map { it.previewId }).isEqualTo(screens.map { it.previewId })
  }

  /**
   * wear-m3-catalog#760's twelve screens end to end, on a fake model whose latency is the length of
   * what it writes: one token per four characters at 70 tokens a second (a flash model's pace),
   * scaled 1:100 so the test takes a moment. Before: batches of five (#5800's 120-verdict cap),
   * every verdict written out, one request at a time. After: batches of six, findings only (three
   * per screen) and an `others` statement each, four at a time.
   */
  @Test
  fun `bench - the twelve-screen check, before and after`() {
    val screens = (1..12).map { screen("screen$it") }
    val verdict = { s: Int, rule: String, v: String ->
      """{"subjectId":"s$s","ruleId":"$rule","verdict":"$v","confidence":0.9,"nodeIds":[],""" +
        """"reason":"The bottom chip is cut by the screen's curve.","needs":[],""" +
        """"regions":[{"picture":$s,"x":0.1,"y":0.8,"width":0.8,"height":0.15,"label":null}]}"""
    }
    fun everyVerdict(request: GuidelineRequestV1) =
      """{"verdicts":[""" +
        request.subjects.indices.joinToString(",") { i ->
          rules
            .mapIndexed { r, rule -> verdict(i + 1, rule, if (r < 3) "fail" else "pass") }
            .joinToString(",")
        } +
        "]}"
    fun findingsOnly(request: GuidelineRequestV1) =
      """{"verdicts":[""" +
        request.subjects.indices.joinToString(",") { i ->
          rules.take(3).joinToString(",") { verdict(i + 1, it, "fail") }
        } +
        """],"others":[""" +
        request.subjects.indices.joinToString(",") {
          """{"subjectId":"s${it + 1}","verdict":"pass","confidence":0.9}"""
        } +
        "]}"
    val tokens = java.util.concurrent.atomic.AtomicLong()
    fun model(write: (GuidelineRequestV1) -> String) =
      object : GuidelineModel {
        override fun complete(request: GuidelineRequestV1, model: String): ModelResponse {
          val content = write(request)
          val written = content.length / 4L
          tokens.addAndGet(written)
          // 70 tokens a second, a hundred times faster.
          Thread.sleep(written * 1000 / 70 / 100)
          return ModelResponse(
            200,
            buildJsonObject {
              put("model", "m")
              put("usage", buildJsonObject { put("completion_tokens", written) })
              put(
                "choices",
                buildJsonArray {
                  add(
                    buildJsonObject { put("message", buildJsonObject { put("content", content) }) }
                  )
                },
              )
            }
              .toString(),
          )
        }
      }
    fun timed(options: GuidelineRunOptions, write: (GuidelineRequestV1) -> String): Long {
      tokens.set(0)
      val start = System.nanoTime()
      val run = GuidelineEngine(model(write), options = options).run(guidelines, screens)
      assertThat(run.results.flatMap { it.unchecked }).isEmpty()
      return (System.nanoTime() - start) / 1_000_000
    }
    val before =
      timed(
        GuidelineRunOptions(
            triage = false,
            budget = GuidelineBudget().withMaxVerdicts(120).withMaxReplyTokens(Int.MAX_VALUE),
          )
          .withConcurrency(1),
        ::everyVerdict,
      )
    val beforeTokens = tokens.get()
    val after = timed(GuidelineRunOptions(triage = false), ::findingsOnly)
    val afterTokens = tokens.get()
    println(
      "bench: 12 screens x 24 rules end to end at 70 tokens/s: before $beforeTokens tokens " +
        "written, ~${before / 10} s; after $afterTokens tokens, ~${after / 10} s"
    )
    assertThat(afterTokens).isLessThan(beforeTokens / 4)
    assertThat(after).isLessThan(before / 4)
  }

  @Test
  fun `results and problems do not depend on which request finished first`() {
    val screens = (1..30).map { screen("screen$it") }
    // Later batches answer first; one batch's request always fails.
    fun model() =
      SlowModel(
        { request -> 400L - request.subjects.first().id.removePrefix("screen").toLong() * 10 },
        { request ->
          if (request.subjects.any { it.id == "screen13" })
            ModelResponse(400, """{"error":{"code":400,"message":"bad"}}""")
          else answer(request)
        },
      )
    val one = GuidelineEngine(model(), options = options(1)).run(guidelines, screens)
    val four = GuidelineEngine(model(), options = options(4)).run(guidelines, screens)
    assertThat(four.results.map { it.previewId }).isEqualTo(one.results.map { it.previewId })
    assertThat(four.results.map { it.unchecked }).isEqualTo(one.results.map { it.unchecked })
    assertThat(four.results.map { it.record.verdicts })
      .isEqualTo(one.results.map { it.record.verdicts })
    assertThat(four.problems).isEqualTo(one.problems)
    assertThat(four.failedRequests).isEqualTo(1)
  }

  @Test
  fun `concurrent requests never jointly cross the cap`() {
    val screens = (1..48).map { screen("screen$it") }
    val model = SlowModel({ 100 }, { answer(it, cost = 0.001) })
    val run =
      GuidelineEngine(model, options = options(4, maxCost = 0.0035)).run(guidelines, screens)
    // Three requests of $0.001 fit; a fourth, expected to cost as much, would not. Before the
    // first came back nothing said what one costs, so it went alone.
    assertThat(model.calls.get()).isEqualTo(3)
    assertThat(run.costUsd).isAtMost(0.0035)
    assertThat(run.results.count { it.pending }).isEqualTo(48 - 18)
    // Capped previews are a suffix of the queue, as one request at a time would leave them.
    assertThat(run.results.takeLast(30).all { it.pending }).isTrue()
    assertThat(run.problems.single { "cost cap" in it }).contains("30 previews were not checked")
  }

  @Test
  fun `without a cap, up to the concurrency is in flight`() {
    val screens = (1..48).map { screen("screen$it") }
    val model = SlowModel({ 150 }, { answer(it) })
    GuidelineEngine(model, options = options(3)).run(guidelines, screens)
    assertThat(model.mostInFlight.get()).isEqualTo(3)
    val single = SlowModel({ 20 }, { answer(it) })
    GuidelineEngine(single, options = options(1)).run(guidelines, screens)
    assertThat(single.mostInFlight.get()).isEqualTo(1)
  }

  @Test
  fun `a rate limit pauses every request, not only the one that met it`() {
    val screens = (1..48).map { screen("screen$it") }
    val starts = java.util.Collections.synchronizedList(mutableListOf<Long>())
    val limitedAt = java.util.concurrent.atomic.AtomicLong()
    val first = AtomicInteger()
    val model =
      object : GuidelineModel {
        override fun complete(request: GuidelineRequestV1, model: String): ModelResponse {
          if (first.getAndIncrement() == 0) {
            // Answered between the others' second and third requests (at 50 and 100 ms).
            Thread.sleep(75)
            limitedAt.set(System.nanoTime())
            return ModelResponse(429, """{"error":{"code":429,"message":"slow down"}}""").also {
              it.retryAfterMillis = 600
            }
          }
          starts += System.nanoTime()
          Thread.sleep(50)
          return answer(request)
        }
      }
    val run = GuidelineEngine(model, options = options(4)).run(guidelines, screens)
    assertThat(run.failedRequests).isEqualTo(0)
    // Other workers may have started before the 429 came back; none started in the pause.
    val inPause = starts.count { (it - limitedAt.get()) / 1_000_000 in 0 until 550 }
    assertThat(inPause).isEqualTo(0)
    assertThat(run.results.flatMap { it.unchecked }).isEmpty()
  }
}
