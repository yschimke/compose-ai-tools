package ee.schimke.composeai.guidelines

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.guidelines.protocol.CatalogGuidelinesV1
import ee.schimke.composeai.guidelines.protocol.GuidelineEvidenceNeedV1
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
    assertThat(reply.regions.single().subjectId).isEqualTo("b")
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
            .apply { nodeIds = listOf("n1") }
            .build()
        ),
        listOf(GuidelineRegion(ruleId = "touch", x = 0.5, y = 0.5, width = 0.4, height = 0.4)),
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

  private inner class FakeModel : GuidelineModel {
    val replies = ArrayDeque<String>()
    val requests = mutableListOf<GuidelineRequestV1>()
    var decision: String? = null

    override fun complete(request: GuidelineRequestV1, model: String): ModelResponse {
      requests += request
      return ModelResponse(200, completion(replies.removeFirst()))
    }

    override fun decide(body: JsonObject): ModelResponse =
      decision?.let { ModelResponse(200, it) } ?: ModelResponse(500, "{}")
  }

  private inner class FakeHost : GuidelineEvidenceHost {
    val nodeRequests = mutableListOf<String>()
    val renderRequests = mutableListOf<GuidelineEvidenceNeedV1>()
    override val available = listOf("a11y-hierarchy", "render")

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
