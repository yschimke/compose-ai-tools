package ee.schimke.composeai.guidelines

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.guidelines.protocol.CatalogGuidelinesV1
import ee.schimke.composeai.guidelines.protocol.GuidelineEvidenceNeedV1
import ee.schimke.composeai.guidelines.protocol.GuidelineRegionV1
import ee.schimke.composeai.guidelines.protocol.GuidelineRequestV1
import ee.schimke.composeai.guidelines.protocol.GuidelineVerdictV1
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test

class GuidelineEngineTest {
  private val guidelines: CatalogGuidelinesV1 =
    CatalogGuidelinesLoader.parse(
        """
        {
          "schema": "compose-ui-builder/catalog-guidelines/v1",
          "catalog": "wear-m3", "platform": "wear", "version": 6,
          "rules": [
            {"id": "any", "kind": "structure", "severity": "info", "guidance": "g", "check": "ok?",
             "source": "https://developer.android.com/a"},
            {"id": "touch", "kind": "visual", "severity": "warning", "guidance": "48dp", "check": "big?",
             "source": "https://developer.android.com/b", "surfaces": ["component", "screen"]},
            {"id": "screen-only", "kind": "structure", "severity": "info", "guidance": "g",
             "check": "time text?", "source": "https://developer.android.com/c", "surfaces": ["screen"]},
            {"id": "v7", "kind": "structure", "severity": "info", "guidance": "g", "check": "v7?",
             "source": "https://developer.android.com/d", "profiles": ["launcher-widgets-v7"]},
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

  private fun subject(
    id: String,
    surface: String = GuidelineSurfaces.COMPONENT,
    hash: String? = "h-$id",
  ) =
    PreviewSubject(
      previewId = id,
      label = id,
      surface = surface,
      renderHash = hash,
      pictures = listOf(SubjectPicture("device", png, 40, 40)),
    )

  @Test
  fun `rules are filtered by surface, profile, scope and whether there is a picture`() {
    assertThat(guidelines.subjectRules("component").map { it.id }).containsExactly("any", "touch")
    assertThat(guidelines.subjectRules("screen").map { it.id })
      .containsExactly("any", "touch", "screen-only")
    assertThat(
        guidelines.subjectRules("component", "launcher-widgets-v7+experimental").map { it.id }
      )
      .contains("v7")
    assertThat(guidelines.subjectRules("component", hasPicture = false).map { it.id })
      .containsExactly("any")
    assertThat(guidelines.setRules().map { it.id }).containsExactly("consistent")
  }

  @Test
  fun `a catalog file for another catalog, or a broken one, is refused softly`() {
    assertThat(CatalogGuidelinesLoader.parse("{", null).guidelines).isNull()
    val other =
      CatalogGuidelinesLoader.parse(
        """{"schema":"compose-ui-builder/catalog-guidelines/v1","catalog":"x","platform":"wear","version":1}""",
        "wear-m3",
      )
    assertThat(other.guidelines).isNull()
    assertThat(other.problem).contains("written for catalog `x`")
  }

  @Test
  fun `a file that is not guidelines is refused without quoting it`() {
    val secret = "sk-or-v1-not-a-real-key"
    val dotenv = Files.createTempFile("guidelines", ".env").toFile()
    try {
      dotenv.writeText("COMPOSE_PREVIEW_OPENROUTER_KEY=$secret\n")
      val loaded = CatalogGuidelinesLoader.load(dotenv.path)
      assertThat(loaded.guidelines).isNull()
      assertThat(loaded.problem).doesNotContain(secret)
      assertThat(loaded.problem).doesNotContain("OPENROUTER")
      assertThat(loaded.problem).contains("malformed JSON at offset")
    } finally {
      dotenv.delete()
    }
    // Valid JSON, wrong shape: the reason names the schema's fields, not the file's values.
    val wrongShape = CatalogGuidelinesLoader.parse("""{"token": "$secret"}""")
    assertThat(wrongShape.problem).doesNotContain(secret)
    assertThat(wrongShape.problem).contains("missing")
  }

  @Test
  fun `batches respect the picture budget and never mix surfaces`() {
    val subjects = (1..5).map { subject("c$it") } + subject("screen1", GuidelineSurfaces.SCREEN)
    val batches =
      PreviewGuidelineRequests.batches(guidelines, subjects, GuidelineBudget(maxPictures = 2))
    assertThat(batches.map { it.surface to it.subjects.size })
      .containsExactly("component" to 2, "component" to 2, "component" to 1, "screen" to 1)
      .inOrder()
  }

  @Test
  fun `the prompt names every subject, its nodes and the instructions`() {
    val batch =
      GuidelineBatch(
        "component",
        listOf(
          subject("a").copy(nodes = listOf(PreviewNode("n1", "Button", "Stop", 1, 2, 30, 20))),
          subject("b"),
        ),
      )
    val request =
      PreviewGuidelineRequests.request(guidelines, batch, "src", listOf("a11y-hierarchy", "render"))
    assertThat(request.userText).contains("### s1: a")
    assertThat(request.userText).contains("- n1 | Button | Stop | 1,2,30,20")
    assertThat(request.userText).contains("Rules judged ONCE across all subjects")
    assertThat(request.userText).contains("a11y-hierarchy, render")
    assertThat(request.systemPrompt).contains("needs_evidence")
    assertThat(request.subjects.map { it.id }).containsExactly("a", "b")
    assertThat(request.pictures.map { it.subjectId }).containsExactly("a", "b")
    assertThat(request.evidence.single().subjectId).isEqualTo("a")
    val required =
      request.responseSchema["properties"]!!
        .jsonObject["verdicts"]!!
        .jsonObject["items"]!!
        .jsonObject["required"]!!
        .jsonArray
        .map { it.jsonPrimitive.content }
    assertThat(required).containsAtLeast("subjectId", "needs", "regions")
  }

  @Test
  fun `a reply maps subjects, needs, regions and who served it`() {
    val batch = GuidelineBatch("component", listOf(subject("a"), subject("b")))
    val body =
      completion(
        """{"verdicts":[
          {"subjectId":"s1","ruleId":"touch","verdict":"fail","confidence":0.9,"nodeIds":["n1"],
           "reason":"Small.","needs":[],"regions":[{"picture":2,"x":0.1,"y":0.2,"width":0.3,"height":0.4,"label":null}]},
          {"subjectId":"s2","ruleId":"any","verdict":"needs_evidence","confidence":0.4,"nodeIds":[],
           "reason":"Need nodes.","needs":[{"kind":"a11y-hierarchy","theme":null,"fontScale":null,"device":null,"reason":"labels"}],"regions":[]},
          {"subjectId":null,"ruleId":"consistent","verdict":"pass","confidence":0.8,"nodeIds":[],"reason":"","needs":[],"regions":[]}
        ]}"""
      )
    val reply =
      GuidelineResponse.parse(body, batch, listOf("a" to "device", "b" to "device")).getOrThrow()
    assertThat(reply.verdicts.map { it.subjectId to it.ruleId })
      .containsExactly("a" to "touch", "b" to "any", null to "consistent")
    assertThat(reply.verdicts[1].needs.single().kind).isEqualTo("a11y-hierarchy")
    assertThat(reply.verdicts.flatMap { it.regions }.single().subjectId).isEqualTo("b")
    assertThat(reply.served.model).isEqualTo("deepseek/deepseek-v4.1-flash")
  }

  @Test
  fun `a routed completion records the served model and the router's choice`() {
    val text = javaClass.getResource("/guidelines/router-completion.json")!!.readText()
    val served =
      GuidelineResponse.served(kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject)
    assertThat(served.model).isEqualTo("deepseek/deepseek-v4.1-flash")
    assertThat(served.provider).isEqualTo("CoreWeave")
    assertThat(served.routing!!.reason).isEqualTo("initial")
    assertThat(served.routing!!.probability!!).isWithin(0.001).of(0.895)
  }

  @Test
  fun `the evidence loop fetches only what was asked and re-asks only those rules`() {
    val model = FakeModel()
    model.replies +=
      """{"verdicts":[
        {"subjectId":"s1","ruleId":"touch","verdict":"needs_evidence","confidence":0.3,"nodeIds":[],
         "reason":"","needs":[{"kind":"a11y-hierarchy","theme":null,"fontScale":null,"device":null,"reason":"bounds"}],"regions":[]},
        {"subjectId":"s1","ruleId":"any","verdict":"pass","confidence":0.9,"nodeIds":[],"reason":"","needs":[],"regions":[]},
        {"subjectId":"s2","ruleId":"touch","verdict":"pass","confidence":0.9,"nodeIds":[],"reason":"","needs":[],"regions":[]},
        {"subjectId":"s2","ruleId":"any","verdict":"pass","confidence":0.9,"nodeIds":[],"reason":"","needs":[],"regions":[]}
      ]}"""
    model.replies +=
      """{"verdicts":[
        {"subjectId":"s1","ruleId":"touch","verdict":"fail","confidence":0.8,"nodeIds":["n1"],"reason":"28dp.","needs":[],"regions":[]}
      ]}"""
    val host = FakeHost()
    val engine =
      GuidelineEngine(model, host, options = GuidelineRunOptions(triage = false, maxRounds = 1))

    val run = engine.run(guidelines, listOf(subject("a"), subject("b")))

    assertThat(host.nodeRequests).containsExactly("a")
    assertThat(model.requests).hasSize(2)
    val followUp = model.requests[1]
    assertThat(followUp.round).isEqualTo(1)
    assertThat(followUp.subjects.map { it.id }).containsExactly("a")
    assertThat(followUp.rules.asked.map { it.id }).containsExactly("touch")
    val a = run.results.single { it.previewId == "a" }
    assertThat(a.failures().single().nodeIds).containsExactly("n1")
    assertThat(a.unchecked).isEmpty()
    assertThat(run.costUsd).isWithin(1e-9).of(0.002)
  }

  @Test
  fun `a staged scroll capture is offered only where it exists and served in round two`() {
    // The handoff shape: no build to render with, only a long screenshot the render job staged
    // for `a`. Round 0 cannot tell scrolled from clipped and asks; round 1 decides from it.
    val model = FakeModel()
    model.replies +=
      """{"verdicts":[
        {"subjectId":"s1","ruleId":"touch","verdict":"needs_evidence","confidence":0.4,"nodeIds":[],
         "reason":"","needs":[{"kind":"scroll-capture","theme":null,"fontScale":null,"device":null,"reason":"scrolled or clipped?"}],"regions":[]},
        {"subjectId":"s1","ruleId":"any","verdict":"pass","confidence":0.9,"nodeIds":[],"reason":"","needs":[],"regions":[]},
        {"subjectId":"s2","ruleId":"touch","verdict":"pass","confidence":0.9,"nodeIds":[],"reason":"","needs":[],"regions":[]},
        {"subjectId":"s2","ruleId":"any","verdict":"pass","confidence":0.9,"nodeIds":[],"reason":"","needs":[],"regions":[]}
      ]}"""
    model.replies +=
      """{"verdicts":[
        {"subjectId":"s1","ruleId":"touch","verdict":"fail","confidence":0.85,"nodeIds":[],"reason":"Cut across the list.","needs":[],
         "regions":[{"picture":1,"x":0.1,"y":0.7,"width":0.8,"height":0.2,"label":null}]}
      ]}"""
    model.costs += listOf(0.0031, 0.0014)
    val served = mutableListOf<String>()
    val host =
      object : GuidelineEvidenceHost {
        override val available = listOf(PreviewGuidelineRequests.KIND_SCROLL_CAPTURE)

        override fun available(previewId: String) = if (previewId == "a") available else emptyList()

        override fun render(previewId: String, need: GuidelineEvidenceNeedV1): SubjectPicture {
          served += previewId
          return SubjectPicture(PreviewGuidelineRequests.KIND_SCROLL_CAPTURE, png, 40, 40)
        }
      }
    val engine =
      GuidelineEngine(model, host, options = GuidelineRunOptions(triage = true, maxRounds = 2))

    val run = engine.run(guidelines, listOf(subject("a"), subject("b")))

    // Two requests, no triage call (it offers nothing this host serves), one capture served.
    assertThat(model.decisions).isEmpty()
    assertThat(model.requests.map { it.round }).containsExactly(0, 1).inOrder()
    assertThat(model.requests[0].evidenceAvailable).containsExactly("scroll-capture")
    assertThat(model.requests[0].userText).contains("Evidence that may be asked for s2: none")
    assertThat(model.requests[0].pictures).hasSize(2)
    assertThat(model.requests[1].pictures).hasSize(2)
    assertThat(served).containsExactly("a")
    val a = run.results.single { it.previewId == "a" }
    assertThat(a.unchecked).isEmpty()
    assertThat(a.failures().single().regions.single().pictureKind).isEqualTo("device")
    assertThat(run.requests).isEqualTo(2)
    assertThat(run.costUsd).isWithin(1e-9).of(0.0045)
  }

  @Test
  fun `a capture is never fetched for a preview the host has none for`() {
    val model = FakeModel()
    model.replies +=
      """{"verdicts":[
        {"subjectId":"s1","ruleId":"touch","verdict":"needs_evidence","confidence":0.4,"nodeIds":[],
         "reason":"","needs":[{"kind":"scroll-capture","theme":null,"fontScale":null,"device":null,"reason":"?"}],"regions":[]},
        {"subjectId":"s1","ruleId":"any","verdict":"pass","confidence":0.9,"nodeIds":[],"reason":"","needs":[],"regions":[]}
      ]}"""
    model.replies +=
      """{"verdicts":[{"subjectId":"s1","ruleId":"touch","verdict":"needs_evidence","confidence":0.4,"nodeIds":[],"reason":"","needs":[],"regions":[]}]}"""
    val served = mutableListOf<String>()
    val host =
      object : GuidelineEvidenceHost {
        override val available = listOf(PreviewGuidelineRequests.KIND_SCROLL_CAPTURE)

        override fun available(previewId: String) = emptyList<String>()

        override fun render(previewId: String, need: GuidelineEvidenceNeedV1): SubjectPicture? {
          served += previewId
          return null
        }
      }
    val run =
      GuidelineEngine(model, host, options = GuidelineRunOptions(triage = false, maxRounds = 2))
        .run(guidelines, listOf(subject("a")))
    assertThat(served).isEmpty()
    assertThat(model.requests[0].evidenceAvailable).isEmpty()
    assertThat(run.results.single().unchecked).containsExactly("touch")
  }

  @Test
  fun `a rule still undecided after the last round is unchecked, not passed`() {
    val model = FakeModel()
    model.replies +=
      """{"verdicts":[
        {"subjectId":"s1","ruleId":"touch","verdict":"needs_evidence","confidence":0.3,"nodeIds":[],"reason":"",
         "needs":[{"kind":"render","theme":"dark","fontScale":null,"device":null,"reason":"contrast"}],"regions":[]},
        {"subjectId":"s1","ruleId":"any","verdict":"pass","confidence":0.9,"nodeIds":[],"reason":"","needs":[],"regions":[]}
      ]}"""
    val run =
      GuidelineEngine(
          model,
          GuidelineEvidenceHost.None,
          options = GuidelineRunOptions(triage = false),
        )
        .run(guidelines, listOf(subject("a")))
    assertThat(model.requests).hasSize(1)
    assertThat(run.results.single().unchecked).containsExactly("touch")
  }

  @Test
  fun `Jev triage adds only the renders it wants, before the first round`() {
    val model = FakeModel()
    model.decision =
      """{"answers":{"s1__dark_theme":{"type":"noul","noul":0.8},"s1__large_font":{"type":"noul","noul":0.1},
          "s1__a11y":{"type":"noul","noul":0.2}}}"""
    model.replies +=
      """{"verdicts":[{"subjectId":"s1","ruleId":"any","verdict":"pass","confidence":0.9,"nodeIds":[],"reason":"","needs":[],"regions":[]}]}"""
    val host = FakeHost()
    GuidelineEngine(model, host, options = GuidelineRunOptions(maxRounds = 0))
      .run(guidelines, listOf(subject("a")))
    assertThat(host.renderRequests.map { it.theme }).containsExactly("dark")
    assertThat(model.requests.single().pictures.map { it.theme }).containsExactly(null, "dark")
  }

  @Test
  fun `an unchanged render is answered from the cache without asking the model`() {
    val dir = Files.createTempDirectory("guidelines-cache").toFile()
    val model = FakeModel()
    model.replies +=
      """{"verdicts":[{"subjectId":"s1","ruleId":"any","verdict":"pass","confidence":0.9,"nodeIds":[],"reason":"","needs":[],"regions":[]}]}"""
    val options = GuidelineRunOptions(triage = false)
    GuidelineEngine(model, cache = GuidelineResultCache(dir), options = options)
      .run(guidelines, listOf(subject("a")))
    val second =
      GuidelineEngine(model, cache = GuidelineResultCache(dir), options = options)
        .run(guidelines, listOf(subject("a")))
    assertThat(model.requests).hasSize(1)
    assertThat(second.results.single().fromCache).isTrue()
    dir.deleteRecursively()
  }

  @Test
  fun `annotating draws findings and regions without failing`() {
    val out =
      GuidelineAnnotator.annotate(
        png,
        listOf(PreviewNode("n1", null, "", 2, 2, 20, 20)),
        listOf(
          GuidelineVerdictV1.Builder("touch", GuidelineVerdictV1.FAIL)
            .apply {
              nodeIds = listOf("n1")
              regions = listOf(GuidelineRegionV1.Builder(0.5, 0.5, 0.4, 0.4).build())
            }
            .build()
        ),
      )
    val image = ImageIO.read(out.inputStream())
    assertThat(image.width).isEqualTo(40)
  }

  private fun completion(content: String, cost: Double = 0.001): String = buildJsonObject {
    put("id", "gen-1")
    put("model", "deepseek/deepseek-v4.1-flash")
    put("provider", "DeepInfra")
    put("usage", buildJsonObject { put("cost", cost) })
    put(
      "choices",
      buildJsonArray {
        add(buildJsonObject { put("message", buildJsonObject { put("content", content) }) })
      },
    )
  }
    .toString()

  @Test
  fun `a subject's source goes to the model as evidence and in the prompt`() {
    val withSource =
      subject("a").copy(source = "@Composable fun A() { Button(Modifier.size(36.dp)) {} }")
    val batch = PreviewGuidelineRequests.batches(guidelines, listOf(withSource, subject("b")))
    val request =
      PreviewGuidelineRequests.request(guidelines, batch.single(), "rules.json", listOf("source"))

    assertThat(request.sourceAttached).isTrue()
    val source = request.evidence.single { it.kind == GuidelineEvidenceNeedV1.KIND_SOURCE }
    assertThat(source.subjectId).isEqualTo("a")
    assertThat(source.mediaType).isEqualTo(PreviewGuidelineRequests.SOURCE_MEDIA_TYPE)
    assertThat(request.userText).contains("Modifier.size(36.dp)")
    assertThat(request.systemPrompt).contains("Use the source for rules about code")
  }

  @Test
  fun `source is given up before a picture when the batch's source allowance runs out`() {
    val long = "x".repeat(600)
    val subjects = listOf(subject("a").copy(source = long), subject("b").copy(source = long))
    val batches =
      PreviewGuidelineRequests.batches(
        guidelines,
        subjects,
        GuidelineBudget(maxSourceChars = 800),
      )

    val batch = batches.single()
    assertThat(batch.subjects.map { it.pictures.size }).containsExactly(1, 1)
    assertThat(batch.subjects[0].source).hasLength(600)
    assertThat(batch.subjects[1].source).hasLength(200)
  }

  @Test
  fun `a request that fails is counted, and its previews are unchecked rather than passed`() {
    val model = FakeModel().apply { status = 503 }
    val run =
      GuidelineEngine(model, options = GuidelineRunOptions(triage = false))
        .run(guidelines, listOf(subject("a"), subject("b")))
    assertThat(run.failedRequests).isEqualTo(1)
    assertThat(run.problems.single()).contains("503")
    assertThat(run.results.map { it.unchecked.isNotEmpty() }).containsExactly(true, true)
  }

  @Test
  fun `stopping at the cost cap is a limit, not a failed request`() {
    val run =
      GuidelineEngine(FakeModel(), options = GuidelineRunOptions(triage = false, maxCostUsd = 0.0))
        .run(guidelines, listOf(subject("a")))
    assertThat(run.failedRequests).isEqualTo(0)
    assertThat(run.problems.single()).contains("cost cap")
  }

  @Test
  fun `a verdict on a rule the request never asked is dropped and counted`() {
    val model = FakeModel()
    // "component" subjects are asked `any` and `touch`, and the set is asked `consistent`.
    model.replies +=
      """{"verdicts":[
        {"subjectId":"s1","ruleId":"touch","verdict":"fail","confidence":0.9,"nodeIds":[],"reason":"Small.","needs":[],"regions":[]},
        {"subjectId":"s1","ruleId":"clipping","verdict":"fail","confidence":0.9,"nodeIds":[],"reason":"Cut.","needs":[],"regions":[]},
        {"subjectId":"s1","ruleId":"screen-only","verdict":"fail","confidence":0.9,"nodeIds":[],"reason":"No time.","needs":[],"regions":[]},
        {"subjectId":"s2","ruleId":"clipping","verdict":"fail","confidence":0.9,"nodeIds":[],"reason":"Cut.","needs":[],"regions":[]},
        {"subjectId":"s9","ruleId":"touch","verdict":"fail","confidence":0.9,"nodeIds":[],"reason":"?","needs":[],"regions":[]},
        {"subjectId":null,"ruleId":"R5","verdict":"fail","confidence":0.9,"nodeIds":[],"reason":"?","needs":[],"regions":[]}
      ]}"""
    val run =
      GuidelineEngine(model, options = GuidelineRunOptions(triage = false))
        .run(guidelines, listOf(subject("a"), subject("b")))

    val a = run.results.single { it.previewId == "a" }
    assertThat(a.record.verdicts.map { it.ruleId }).containsExactly("touch")
    assertThat(a.unchecked).containsExactly("any")
    val b = run.results.single { it.previewId == "b" }
    assertThat(b.record.verdicts).isEmpty()
    assertThat(b.unchecked).containsExactly("any", "touch")
    assertThat(run.failedRequests).isEqualTo(0)
    assertThat(run.problems)
      .contains(
        "4 verdict(s) named a rule their request did not ask and were dropped: clipping ×2, " +
          "screen-only, R5"
      )
    assertThat(run.problems.single { "outside their request" in it }).contains("s9/touch")
  }

  @Test
  fun `a reply answering no rule it was asked is asked once more, then counted as failed`() {
    val invented =
      """{"verdicts":[{"subjectId":"s1","ruleId":"contrast","verdict":"fail","confidence":0.9,"nodeIds":[],"reason":"Low.","needs":[],"regions":[]}]}"""
    val good =
      """{"verdicts":[{"subjectId":"s1","ruleId":"any","verdict":"pass","confidence":0.9,"nodeIds":[],"reason":"","needs":[],"regions":[]},
        {"subjectId":"s1","ruleId":"touch","verdict":"pass","confidence":0.9,"nodeIds":[],"reason":"","needs":[],"regions":[]}]}"""
    val retried = FakeModel().apply { replies += listOf(invented, good) }
    val run =
      GuidelineEngine(retried, options = GuidelineRunOptions(triage = false))
        .run(guidelines, listOf(subject("a")))
    assertThat(run.requests).isEqualTo(2)
    assertThat(run.failedRequests).isEqualTo(0)
    assertThat(run.results.single().unchecked).isEmpty()
    assertThat(run.costUsd).isWithin(1e-9).of(0.002)
    // What the first, unusable reply cost is still the preview's.
    assertThat(run.results.single().record.costUsd!!).isWithin(1e-9).of(0.002)
    assertThat(run.problems)
      .contains("1 request(s) were asked again after a reply with no usable verdict")

    val twice = FakeModel().apply { replies += listOf(invented, """{"verdicts":[]}""") }
    val failed =
      GuidelineEngine(twice, options = GuidelineRunOptions(triage = false))
        .run(guidelines, listOf(subject("a")))
    assertThat(twice.requests).hasSize(2)
    assertThat(failed.failedRequests).isEqualTo(1)
    assertThat(failed.results.single().pending).isTrue()
    // Both replies were paid for, though neither could be used.
    assertThat(failed.costUsd).isWithin(1e-9).of(0.002)
    assertThat(failed.problems.single { it.startsWith("unreadable") }).contains("no verdicts")
  }

  @Test
  fun `the cap is not crossed by a request expected to cost more than is left`() {
    val pass =
      """{"verdicts":[{"subjectId":"s1","ruleId":"any","verdict":"pass","confidence":0.9,"nodeIds":[],"reason":"","needs":[],"regions":[]}]}"""
    val model = FakeModel().apply { repeat(4) { replies += pass } }
    val run =
      GuidelineEngine(
          model,
          options =
            GuidelineRunOptions(
              triage = false,
              budget = GuidelineBudget(maxSubjects = 1),
              maxCostUsd = 0.0025,
            ),
        )
        .run(guidelines, (1..4).map { subject("p$it") })
    // Two requests of 0.001 leave 0.0005: a third, expected to cost 0.001, would cross the cap.
    assertThat(model.requests).hasSize(2)
    assertThat(run.costUsd).isAtMost(0.0025)
    assertThat(run.results.filter { it.pending }.map { it.previewId }).containsExactly("p3", "p4")
    assertThat(run.problems.single()).contains("cost cap")
    assertThat(run.problems.single()).contains("2 previews were not checked")
  }

  @Test
  fun `the prompt lists each subject's rule ids and says no other id is valid`() {
    val batch =
      GuidelineBatch(
        "component",
        listOf(subject("a"), subject("w").copy(profile = "launcher-widgets-v7")),
      )
    val request = PreviewGuidelineRequests.request(guidelines, batch, "src", emptyList())
    assertThat(request.userText).contains("Rules for s1: any, touch\n")
    assertThat(request.userText).contains("Rules for s2: any, touch, v7\n")
    assertThat(request.userText)
      .contains(
        "The only valid ruleIds are the ones listed here, spelled exactly as listed: " +
          "any, touch, v7, consistent."
      )
    assertThat(request.systemPrompt).contains("never invent a rule id")
  }

  @Test
  fun `the prompt says which nodes scroll, which run past the viewport, and what ATF measured`() {
    val list = PreviewNode("list", null, "", 0, 0, 40, 90, states = listOf("scrollable"))
    val footer = PreviewNode("footer", "TextView", "Remove first", 2, 30, 55, 38)
    val batch =
      GuidelineBatch(
        "component",
        listOf(
          subject("a")
            .copy(
              nodes = listOf(list, footer),
              checks =
                listOf(
                  PreviewCheck(
                    "TouchTargetSizeCheck",
                    "ERROR",
                    "This item's height is 24dp.",
                    "Button",
                    "4,4,28,28",
                  )
                ),
            )
        ),
      )
    val request = PreviewGuidelineRequests.request(guidelines, batch, "src", emptyList())
    assertThat(request.userText).contains("Viewport (its first picture): 40×40 px")
    assertThat(request.userText).contains("- list | - |  | 0,0,40,90 | scrollable off:bottom")
    assertThat(request.userText)
      .contains("- footer | TextView | Remove first | 2,30,55,38 | off:right")
    assertThat(request.userText)
      .contains("- TouchTargetSizeCheck | ERROR | Button | 4,4,28,28 | This item's height is 24dp.")
    assertThat(request.systemPrompt).contains("Scrolling is not clipping")
    assertThat(request.systemPrompt)
      .contains("only while the container can still scroll towards it")
    assertThat(request.systemPrompt).contains("the last item cut at the end edge")
    assertThat(request.systemPrompt).contains("measured accessibility checks")
  }

  @Test
  fun `a measured check or a node's states change the cache key`() {
    val base = subject("a").copy(nodes = listOf(PreviewNode("n", null, "", 0, 0, 1, 1)))
    fun key(s: PreviewSubject) = GuidelineResultCache.inputsKey(s, guidelines, "m")
    val scrolling =
      base.copy(nodes = listOf(PreviewNode("n", null, "", 0, 0, 1, 1, listOf("scrollable"))))
    val measured = base.copy(checks = listOf(PreviewCheck("TextContrastCheck", "WARNING", "low")))
    assertThat(setOf(key(base), key(scrolling), key(measured))).hasSize(3)
  }

  @Test
  fun `a capped run checks previews never checked before ahead of stale ones`() {
    val dir = Files.createTempDirectory("guidelines-cache-order").toFile()
    val model = FakeModel()
    val pass =
      """{"verdicts":[{"subjectId":"s1","ruleId":"any","verdict":"pass","confidence":0.9,"nodeIds":[],"reason":"","needs":[],"regions":[]}]}"""
    repeat(2) { model.replies += pass }
    fun engine(cap: Double? = null) =
      GuidelineEngine(
        model,
        cache = GuidelineResultCache(dir),
        options =
          GuidelineRunOptions(
            triage = false,
            budget = GuidelineBudget(maxSubjects = 1),
            maxCostUsd = cap,
          ),
      )
    engine().run(guidelines, listOf(subject("a")))
    assertThat(GuidelineResultCache(dir).checked("a")).isTrue()
    assertThat(GuidelineResultCache(dir).checked("b")).isFalse()

    // "a" re-rendered, "b" is new; the cap admits one request.
    val capped =
      engine(cap = 0.0005).run(guidelines, listOf(subject("a", hash = "h-a2"), subject("b")))

    assertThat(model.requests).hasSize(2)
    assertThat(capped.results.single { !it.pending }.previewId).isEqualTo("b")
    assertThat(capped.results.single { it.pending }.previewId).isEqualTo("a")
    dir.deleteRecursively()
  }

  @Test
  fun `a result whose follow-up round was cut short is not cached`() {
    val dir = Files.createTempDirectory("guidelines-cache-followup").toFile()
    val model = FakeModel()
    val undecided =
      """{"verdicts":[
        {"subjectId":"s1","ruleId":"touch","verdict":"needs_evidence","confidence":0.3,"nodeIds":[],
         "reason":"","needs":[{"kind":"a11y-hierarchy","theme":null,"fontScale":null,"device":null,"reason":"bounds"}],"regions":[]},
        {"subjectId":"s2","ruleId":"any","verdict":"pass","confidence":0.9,"nodeIds":[],"reason":"","needs":[],"regions":[]}
      ]}"""
    model.replies += undecided
    fun engine(cap: Double?) =
      GuidelineEngine(
        model,
        FakeHost(),
        cache = GuidelineResultCache(dir),
        options = GuidelineRunOptions(triage = false, maxRounds = 1, maxCostUsd = cap),
      )

    // The first reply spends the cap, so "a"'s follow-up is never asked.
    val capped = engine(cap = 0.0005).run(guidelines, listOf(subject("a"), subject("b")))
    assertThat(model.requests).hasSize(1)
    assertThat(capped.results.single { it.previewId == "a" }.unchecked).contains("touch")

    val cache = GuidelineResultCache(dir)
    assertThat(cache.checked("a")).isFalse()
    assertThat(cache.checked("b")).isTrue()
    dir.deleteRecursively()
  }

  @Test
  fun `pruning keeps only the results a run read or wrote`() {
    val dir = Files.createTempDirectory("guidelines-cache-prune").toFile()
    val model = FakeModel()
    repeat(2) {
      model.replies +=
        """{"verdicts":[{"subjectId":"s1","ruleId":"any","verdict":"pass","confidence":0.9,"nodeIds":[],"reason":"","needs":[],"regions":[]}]}"""
    }
    val options = GuidelineRunOptions(triage = false)
    GuidelineEngine(model, cache = GuidelineResultCache(dir), options = options)
      .run(guidelines, listOf(subject("a"), subject("gone")))
    fun results() = dir.walk().filter { it.isFile && it.extension == "json" }.count()
    assertThat(results()).isEqualTo(2)

    val cache = GuidelineResultCache(dir)
    val second =
      GuidelineEngine(model, cache = cache, options = options).run(guidelines, listOf(subject("a")))
    assertThat(second.results.single().fromCache).isTrue()
    cache.prune(setOf("a"))

    assertThat(results()).isEqualTo(1)
    assertThat(GuidelineResultCache(dir).checked("a")).isTrue()
    assertThat(GuidelineResultCache(dir).checked("gone")).isFalse()
    assertThat(
        GuidelineEngine(model, cache = GuidelineResultCache(dir), options = options)
          .run(guidelines, listOf(subject("a")))
          .results
          .single()
          .fromCache
      )
      .isTrue()
    dir.deleteRecursively()
  }

  @Test
  fun `the cache misses when the source, the surface or the rules' text change`() {
    val dir = Files.createTempDirectory("guidelines-cache-inputs").toFile()
    val model = FakeModel()
    repeat(4) {
      model.replies +=
        """{"verdicts":[{"subjectId":"s1","ruleId":"any","verdict":"pass","confidence":0.9,"nodeIds":[],"reason":"","needs":[],"regions":[]}]}"""
    }
    val options = GuidelineRunOptions(triage = false)
    fun run(subject: PreviewSubject, rules: CatalogGuidelinesV1 = guidelines) =
      GuidelineEngine(model, cache = GuidelineResultCache(dir), options = options)
        .run(rules, listOf(subject))
    val base = subject("a").copy(source = "fun A() = Text(\"a\")")

    run(base)
    assertThat(run(base).results.single().fromCache).isTrue()
    assertThat(model.requests).hasSize(1)

    run(base.copy(source = "fun A() = Text(\"a\", color = Color(0xFF00FF00))"))
    assertThat(model.requests).hasSize(2)

    run(base.copy(surface = GuidelineSurfaces.SCREEN))
    assertThat(model.requests).hasSize(3)

    val reworded =
      guidelines
        .newBuilder()
        .apply {
          rules = guidelines.rules.map { it.newBuilder().apply { check = "really ok?" }.build() }
        }
        .build()
    run(base, reworded)
    assertThat(model.requests).hasSize(4)
    dir.deleteRecursively()
  }

  @Test
  fun `a host is never asked for evidence it did not advertise`() {
    val model = FakeModel()
    model.decision = """{"answers":{"s1__a11y":{"type":"noul","noul":0.1}}}"""
    model.replies +=
      """{"verdicts":[
        {"subjectId":"s1","ruleId":"touch","verdict":"needs_evidence","confidence":0.3,"nodeIds":[],"reason":"",
         "needs":[{"kind":"render","theme":"dark","fontScale":null,"device":null,"reason":"contrast"}],"regions":[]},
        {"subjectId":"s1","ruleId":"any","verdict":"pass","confidence":0.9,"nodeIds":[],"reason":"","needs":[],"regions":[]}
      ]}"""
    val host = FakeHost(available = listOf("a11y-hierarchy"))
    GuidelineEngine(model, host, options = GuidelineRunOptions(maxRounds = 1))
      .run(guidelines, listOf(subject("a")))
    assertThat(host.renderRequests).isEmpty()
    // Jev was offered only what the host can fetch.
    val questions = model.decisions.single()["questions"]!!.jsonObject.keys
    assertThat(questions.none { "dark_theme" in it || "large_font" in it }).isTrue()
  }

  @Test
  fun `a region on another preview's picture is kept for that preview`() {
    val model = FakeModel()
    model.replies +=
      """{"verdicts":[
        {"subjectId":"s1","ruleId":"touch","verdict":"fail","confidence":0.9,"nodeIds":[],"reason":"Clipped.",
         "needs":[],"regions":[{"picture":2,"x":0.1,"y":0.2,"width":0.3,"height":0.4,"label":null}]},
        {"subjectId":"s1","ruleId":"any","verdict":"pass","confidence":0.9,"nodeIds":[],"reason":"","needs":[],"regions":[]},
        {"subjectId":"s2","ruleId":"any","verdict":"pass","confidence":0.9,"nodeIds":[],"reason":"","needs":[],"regions":[]}
      ]}"""
    val run =
      GuidelineEngine(model, options = GuidelineRunOptions(triage = false))
        .run(guidelines, listOf(subject("a"), subject("b")))
    val b = run.results.single { it.previewId == "b" }
    val moved = b.failures().single { it.ruleId == "touch" }
    assertThat(moved.regions.single().subjectId).isEqualTo("b")
    assertThat(moved.reason).contains("judging a")
    val a = run.results.single { it.previewId == "a" }
    assertThat(a.failures().single { it.ruleId == "touch" }.regions).isEmpty()
  }

  @Test
  fun `a subject whose source was cut to fit the batch is still answered from the cache next run`() {
    val dir = Files.createTempDirectory("guidelines-cache-cut").toFile()
    val model = FakeModel()
    model.replies +=
      """{"verdicts":[{"subjectId":"s1","ruleId":"any","verdict":"pass","confidence":0.9,"nodeIds":[],"reason":"","needs":[],"regions":[]}]}"""
    val options =
      GuidelineRunOptions(triage = false, budget = GuidelineBudget(maxSourceChars = 100))
    val long = listOf(subject("a").copy(source = "x".repeat(600)))
    GuidelineEngine(model, cache = GuidelineResultCache(dir), options = options)
      .run(guidelines, long)
    val second =
      GuidelineEngine(model, cache = GuidelineResultCache(dir), options = options)
        .run(guidelines, long)
    assertThat(model.requests).hasSize(1)
    assertThat(second.results.single().fromCache).isTrue()
    dir.deleteRecursively()
  }

  @Test
  fun `a region does not become a finding on a preview the rule was never asked of`() {
    val model = FakeModel()
    model.replies +=
      """{"verdicts":[
        {"subjectId":"s1","ruleId":"v7","verdict":"fail","confidence":0.9,"nodeIds":[],"reason":"Not v7.",
         "needs":[],"regions":[{"picture":2,"x":0.1,"y":0.2,"width":0.3,"height":0.4,"label":null}]},
        {"subjectId":"s2","ruleId":"any","verdict":"pass","confidence":0.9,"nodeIds":[],"reason":"","needs":[],"regions":[]}
      ]}"""
    val run =
      GuidelineEngine(model, options = GuidelineRunOptions(triage = false))
        .run(
          guidelines,
          listOf(subject("a").copy(profile = "launcher-widgets-v7"), subject("b")),
        )
    val b = run.results.single { it.previewId == "b" }
    assertThat(b.failures().map { it.ruleId }).doesNotContain("v7")
    val a = run.results.single { it.previewId == "a" }
    assertThat(a.failures().single { it.ruleId == "v7" }.regions).hasSize(1)
  }

  /** remote-m3's shape: every rule is a Wear widget rule, and the catalog draws host frames. */
  private val widgetOnly: CatalogGuidelinesV1 =
    CatalogGuidelinesLoader.parse(
        """
        {
          "schema": "compose-ui-builder/catalog-guidelines/v1",
          "catalog": "remote-m3", "platform": "wear", "version": 1,
          "frames": [
            {"kind": "widget-host", "surface": "widget", "hostShape": "round", "label": "Samsung"},
            {"kind": "widget-host", "surface": "widget", "hostShape": "squircle",
             "label": "Pixel Watch"}
          ],
          "rules": [
            {"id": "wear.layout.no-clipping", "kind": "visual", "severity": "warning",
             "guidance": "g", "check": "whole?", "source": "https://developer.android.com/w",
             "surfaces": ["widget"], "profiles": ["wear-widgets"]}
          ]
        }
        """
      )
      .guidelines!!

  @Test
  fun `a subject no rule applies to costs no request and says why`() {
    // remote-m3-catalog#72: five previews judged as components against widget-only rules were
    // sent as one request asking nothing; the empty reply came back "unreadable" and the PR
    // comment read "No findings".
    val model = FakeModel()
    val run =
      GuidelineEngine(model, options = GuidelineRunOptions(triage = false))
        .run(widgetOnly, listOf(subject("a"), subject("b")))

    assertThat(model.requests).isEmpty()
    assertThat(run.requests).isEqualTo(0)
    assertThat(run.failedRequests).isEqualTo(0)
    assertThat(run.results.map { it.noRules }.distinct())
      .containsExactly(
        "no rule in the `remote-m3` guidelines applies to surface `component` with no profile"
      )
    assertThat(run.results.none { it.pending }).isTrue()
    assertThat(run.problems.single()).startsWith("2 preview(s) were not checked: no rule")
  }

  @Test
  fun `a widget targeting the rules' profile is asked them, and only it is`() {
    val model = FakeModel()
    model.replies +=
      """{"verdicts":[{"subjectId":"s1","ruleId":"wear.layout.no-clipping","verdict":"fail",
        "confidence":0.9,"nodeIds":[],"reason":"Text cut.","needs":[],"regions":[]}]}"""
    val widget =
      subject("w", GuidelineSurfaces.WIDGET).copy(profile = GuidelineSurfaces.WEAR_WIDGETS_PROFILE)
    val run =
      GuidelineEngine(model, options = GuidelineRunOptions(triage = false))
        .run(widgetOnly, listOf(widget, subject("c")))

    assertThat(model.requests).hasSize(1)
    assertThat(model.requests.single().subjects.map { it.id }).containsExactly("w")
    assertThat(run.results.single { it.previewId == "w" }.failures().single().ruleId)
      .isEqualTo("wear.layout.no-clipping")
    assertThat(run.results.single { it.previewId == "c" }.noRules).isNotNull()
  }

  @Test
  fun `host frames no subject has a picture of are named as not rendered`() {
    val widget =
      subject("w", GuidelineSurfaces.WIDGET).copy(profile = GuidelineSurfaces.WEAR_WIDGETS_PROFILE)
    val request =
      PreviewGuidelineRequests.request(
        widgetOnly,
        GuidelineBatch(GuidelineSurfaces.WIDGET, listOf(widget)),
        "rules.json",
        emptyList(),
      )
    assertThat(request.userText)
      .contains(
        "Not rendered here: the catalog's Samsung (round widget-host), Pixel Watch (squircle " +
          "widget-host) picture(s)."
      )
    assertThat(request.userText).contains("never cite a picture that is not attached")
    // A component batch is not told about widget frames.
    val component =
      PreviewGuidelineRequests.request(
        widgetOnly,
        GuidelineBatch(GuidelineSurfaces.COMPONENT, listOf(subject("c"))),
        "rules.json",
        emptyList(),
      )
    assertThat(component.userText).doesNotContain("Not rendered here")
  }

  @Test
  fun `a manifest entry says what surface and profile a preview is`() {
    fun of(json: String) =
      GuidelineSurfaces.of(kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject)

    // remote-m3-catalog#72's widget sticker, as discovery now records it.
    assertThat(
        of(
          """{"id":"w","params":{"device":null,"widthDp":216,"heightDp":124},
             "widget":{"host":"wear","profile":"wear-widgets"}}"""
        )
      )
      .isEqualTo(GuidelineSubjectKind(GuidelineSurfaces.WIDGET, "wear-widgets"))
    // The same sticker from a plugin that predates the field is, as far as anything can tell, a
    // component: that is what the override is for.
    assertThat(of("""{"id":"w","params":{"device":null,"widthDp":216,"heightDp":124}}"""))
      .isEqualTo(GuidelineSubjectKind(GuidelineSurfaces.COMPONENT))
    assertThat(of("""{"id":"s","params":{"device":"id:wearos_small_round"}}"""))
      .isEqualTo(GuidelineSubjectKind(GuidelineSurfaces.SCREEN))
    assertThat(of("""{"id":"g","params":{"kind":"GLANCE_APPWIDGET"}}"""))
      .isEqualTo(GuidelineSubjectKind(GuidelineSurfaces.WIDGET))
    assertThat(
        of("""{"id":"l","params":{},"captures":[{"launcherWidget":{"width":2,"height":1}}]}""")
      )
      .isEqualTo(GuidelineSubjectKind(GuidelineSurfaces.WIDGET))
    assertThat(
        of(
          """{"id":"p","params":{"previewParameterProviderClassName":
             "androidx.glance.wear.tooling.preview.SquircleAllWidgetPreviewParams"}}"""
        )
      )
      .isEqualTo(GuidelineSubjectKind(GuidelineSurfaces.WIDGET, "wear-widgets"))
  }

  private inner class FakeModel : GuidelineModel {
    val replies = ArrayDeque<String>()
    /** Each reply's cost, in order; 0.001 once these run out. */
    val costs = ArrayDeque<Double>()
    val requests = mutableListOf<GuidelineRequestV1>()
    val decisions = mutableListOf<JsonObject>()
    var decision: String? = null
    var status: Int = 200

    override fun complete(request: GuidelineRequestV1, model: String): ModelResponse {
      requests += request
      if (status !in 200..299) return ModelResponse(status, """{"error":{"message":"busy"}}""")
      return ModelResponse(
        200,
        completion(replies.removeFirst(), costs.removeFirstOrNull() ?: 0.001),
      )
    }

    override fun decide(body: JsonObject): ModelResponse {
      decisions += body
      return decision?.let { ModelResponse(200, it) } ?: ModelResponse(500, "{}")
    }
  }

  /** A host holding accessibility data for some previews, as a CI publish job does. */
  private inner class A11yHost(private val with: Set<String>) : GuidelineEvidenceHost {
    val prefetched = mutableListOf<Set<String>>()
    val served = mutableListOf<String>()

    override val available = listOf(PreviewGuidelineRequests.KIND_A11Y)

    override fun available(previewId: String) = if (previewId in with) available else emptyList()

    override fun nodes(previewId: String): List<PreviewNode> {
      served += previewId
      return listOf(PreviewNode("stop", "Button", "Stop", 1, 1, 10, 10, listOf("clickable")))
    }

    override fun checks(previewId: String) =
      listOf(PreviewCheck("TouchTargetSizeCheck", "ERROR", "24dp tall", "Stop", "1,1,10,10"))

    override fun summary(previewId: String) =
      if (previewId in with)
        PreviewGuidelineRequests.a11ySummary(nodes(previewId), checks(previewId))
      else null

    override fun prefetch(needs: Map<String, List<GuidelineEvidenceNeedV1>>) {
      prefetched += needs.keys
    }
  }

  @Test
  fun `accessibility data is offered with a summary and served, nodes and checks, when asked`() {
    val model = FakeModel()
    model.replies +=
      """{"verdicts":[
        {"subjectId":"s1","ruleId":"touch","verdict":"needs_evidence","confidence":0.4,"nodeIds":[],
         "reason":"","needs":[{"kind":"a11y","theme":null,"fontScale":null,"device":null,"reason":"tap targets"}],"regions":[]},
        {"subjectId":"s1","ruleId":"any","verdict":"pass","confidence":0.9,"nodeIds":[],"reason":"","needs":[],"regions":[]},
        {"subjectId":"s2","ruleId":"touch","verdict":"pass","confidence":0.9,"nodeIds":[],"reason":"","needs":[],"regions":[]},
        {"subjectId":"s2","ruleId":"any","verdict":"pass","confidence":0.9,"nodeIds":[],"reason":"","needs":[],"regions":[]}
      ]}"""
    model.replies +=
      """{"verdicts":[
        {"subjectId":"s1","ruleId":"touch","verdict":"fail","confidence":0.9,"nodeIds":["stop"],"reason":"24dp.","needs":[],"regions":[]}
      ]}"""
    val host = A11yHost(with = setOf("a"))
    val run =
      GuidelineEngine(model, host, options = GuidelineRunOptions(triage = false, maxRounds = 1))
        .run(guidelines, listOf(subject("a"), subject("b")))

    val (first, second) = model.requests
    // Up front: the kind is offered and summarised, the data itself is not attached.
    assertThat(first.evidenceAvailable).containsExactly("a11y")
    assertThat(first.evidence).isEmpty()
    assertThat(first.userText)
      .contains("Accessibility: 1 node(s), none scrollable; ATF: 1 ERROR TouchTargetSizeCheck.")
    assertThat(first.userText).contains("Evidence that may be asked for s2: none")
    assertThat(first.systemPrompt).contains("asking for `a11y` when a rule turns on touch target")
    // Asked for: one prefetch for the round, then the nodes and the measured checks.
    assertThat(host.prefetched).containsExactly(setOf("a"))
    assertThat(second.userText).contains("- stop | Button | Stop")
    assertThat(second.userText).contains("TouchTargetSizeCheck | ERROR")
    assertThat(second.userText).doesNotContain("Accessibility: ")
    assertThat(run.results.single { it.previewId == "a" }.failures().single().nodeIds)
      .containsExactly("stop")
  }

  @Test
  fun `a need for the protocol's node kinds is served as a11y, and a11y as nodes`() {
    assertThat(servedKind("a11y-hierarchy", listOf("a11y"))).isEqualTo("a11y")
    assertThat(servedKind("semantics", listOf("a11y", "render"))).isEqualTo("a11y")
    assertThat(servedKind("a11y", listOf("a11y-hierarchy"))).isEqualTo("a11y-hierarchy")
    assertThat(servedKind("a11y", listOf("render"))).isNull()
    assertThat(servedKind("render", listOf("render"))).isEqualTo("render")
  }

  @Test
  fun `the a11y summary is a few dozen tokens whatever the data`() {
    val nodes = (1..80).map { PreviewNode("n$it", "Text", "label $it", 0, it, 10, it + 9) }
    val checks = (1..40).map { PreviewCheck("TextContrastCheck", "WARNING", "low contrast $it") }
    val summary = PreviewGuidelineRequests.a11ySummary(nodes, checks)
    assertThat(summary)
      .isEqualTo(
        "80 node(s), none scrollable; ATF: 40 WARNING TextContrastCheck. " +
          "Ask for `a11y` for the nodes and checks."
      )
    // Against ~20 tokens a node and ~60 a check for the data: 80 nodes and 40 checks is ~4,000.
    assertThat(summary.length / 4).isLessThan(40)
    assertThat(PreviewGuidelineRequests.a11ySummary(emptyList(), emptyList()))
      .isEqualTo(
        "0 node(s), none scrollable; ATF: no findings. Ask for `a11y` for the nodes and checks."
      )
  }

  @Test
  fun `a removed result is asked again`() {
    val dir = Files.createTempDirectory("guidelines-cache-remove").toFile()
    val cache = GuidelineResultCache(dir)
    val model = FakeModel()
    model.replies +=
      """{"verdicts":[{"subjectId":"s1","ruleId":"any","verdict":"pass","confidence":0.9,"nodeIds":[],"reason":"","needs":[],"regions":[]}]}"""
    GuidelineEngine(model, cache = cache, options = GuidelineRunOptions(triage = false))
      .run(guidelines, listOf(subject("a")))
    assertThat(cache.get(subject("a"), guidelines, OpenRouterClient.DEFAULT_MODEL)).isNotNull()
    cache.remove(subject("a"), guidelines, OpenRouterClient.DEFAULT_MODEL)
    assertThat(cache.get(subject("a"), guidelines, OpenRouterClient.DEFAULT_MODEL)).isNull()
    dir.deleteRecursively()
  }

  private inner class FakeHost(
    override val available: List<String> = listOf("a11y-hierarchy", "render")
  ) : GuidelineEvidenceHost {
    val nodeRequests = mutableListOf<String>()
    val renderRequests = mutableListOf<GuidelineEvidenceNeedV1>()

    override fun nodes(previewId: String): List<PreviewNode> {
      nodeRequests += previewId
      return listOf(PreviewNode("n1", "Button", "Stop", 1, 1, 10, 10))
    }

    override fun render(previewId: String, need: GuidelineEvidenceNeedV1): SubjectPicture {
      renderRequests += need
      return SubjectPicture("device", png, 40, 40, theme = need.theme, fontScale = need.fontScale)
    }
  }
}
