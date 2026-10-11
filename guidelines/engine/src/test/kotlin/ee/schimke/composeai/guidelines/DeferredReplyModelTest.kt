package ee.schimke.composeai.guidelines

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.guidelines.protocol.CatalogGuidelinesV1
import ee.schimke.composeai.guidelines.protocol.GuidelineEvidenceNeedV1
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import org.junit.After
import org.junit.Test

/** Prompt mode: requests written for an agent, its replies read back by the same engine. */
class DeferredReplyModelTest {
  private val dir: File = Files.createTempDirectory("review").toFile()
  private val requests = File(dir, "review")
  private val cacheDir = File(dir, "guidelines")

  @After fun cleanUp() = dir.deleteRecursively().let {}

  private val guidelines: CatalogGuidelinesV1 =
    CatalogGuidelinesLoader.parse(
        """
        {
          "schema": "compose-ui-builder/catalog-guidelines/v1",
          "catalog": "wear-m3", "platform": "wear", "version": 1,
          "rules": [
            {"id": "any", "kind": "structure", "severity": "info", "guidance": "g", "check": "ok?",
             "source": "https://developer.android.com/a"},
            {"id": "touch", "kind": "visual", "severity": "warning", "guidance": "48dp",
             "check": "big?", "source": "https://developer.android.com/b"}
          ]
        }
        """
      )
      .guidelines!!

  private val png: ByteArray =
    ByteArrayOutputStream()
      .also { ImageIO.write(BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB), "png", it) }
      .toByteArray()

  private val subject =
    PreviewSubject(
      previewId = "com.example.Stop",
      label = "Stop",
      surface = GuidelineSurfaces.COMPONENT,
      renderHash = "h",
      pictures = listOf(SubjectPicture("device", png, 40, 40)),
    )

  /** Holds accessibility data, served when a reply asks for `a11y`. */
  private class A11yHost : GuidelineEvidenceHost {
    val fetched = mutableListOf<String>()

    override val available = listOf(PreviewGuidelineRequests.KIND_A11Y)

    override fun nodes(previewId: String): List<PreviewNode> {
      fetched += previewId
      return listOf(PreviewNode("stop", "Button", "Stop", 1, 1, 10, 10, listOf("clickable")))
    }

    override fun checks(previewId: String) =
      listOf(PreviewCheck("TouchTargetSizeCheck", "ERROR", "24dp tall", "Stop", "1,1,10,10"))
  }

  private fun run(
    host: GuidelineEvidenceHost = A11yHost(),
    subjects: List<PreviewSubject> = listOf(subject),
  ): Pair<GuidelineRunResult, DeferredReplyModel> {
    val model = DeferredReplyModel(requests, rerun = "compose-preview review --filter Stop")
    val result =
      GuidelineEngine(
          model,
          host,
          cache = GuidelineResultCache(cacheDir),
          options =
            GuidelineRunOptions(
                model = DeferredReplyModel.DEFAULT_MODEL,
                triage = false,
              )
              .withRetry(GuidelineRetry(maxAttempts = 1, split = false)),
        )
        .run(guidelines, subjects)
    return result to model
  }

  private fun reply(request: File, json: String) =
    File(request.parentFile, DeferredReplyModel.REPLY).writeText(json)

  @Test
  fun `a request without a reply is written out and left pending, not failed`() {
    val (result, model) = run()

    assertThat(result.deferredRequests).isEqualTo(1)
    assertThat(result.failedRequests).isEqualTo(0)
    assertThat(result.requests).isEqualTo(0)
    assertThat(result.results.single().pending).isTrue()
    val request = model.waiting.single()
    val text = request.readText()
    assertThat(text).contains("compose-preview review --filter Stop")
    assertThat(text).contains(File(request.parentFile, "reply.json").path)
    assertThat(text).contains("touch")
    assertThat(File(request.parentFile, "picture-1.png").readBytes()).isEqualTo(png)
    // Asked again with nothing answered, the same request is named the same.
    assertThat(run().second.waiting).containsExactly(request)
  }

  @Test
  fun `a reply asking for accessibility data gets a follow-up request with it, then a verdict`() {
    val first = run().second.waiting.single()
    reply(
      first,
      """{"verdicts":[{"subjectId":"s1","ruleId":"touch","verdict":"needs_evidence",
        "confidence":0.3,"nodeIds":[],"reason":"","needs":[{"kind":"a11y","theme":null,
        "fontScale":null,"device":null,"reason":"tap target size"}],"regions":[]}],
        "others":[{"subjectId":"s1","verdict":"pass","confidence":0.9}]}""",
    )

    val host = A11yHost()
    val (second, secondModel) = run(host)
    assertThat(host.fetched).containsExactly("com.example.Stop")
    assertThat(second.deferredRequests).isEqualTo(1)
    val followUp = secondModel.waiting.single()
    assertThat(followUp).isNotEqualTo(first)
    assertThat(followUp.readText()).contains("TouchTargetSizeCheck")
    assertThat(second.results.single().unchecked).containsExactly("touch")
    assertThat(second.results.single().pending).isTrue()

    reply(
      followUp,
      """{"verdicts":[{"subjectId":"s1","ruleId":"touch","verdict":"fail","confidence":0.9,
        "nodeIds":["stop"],"reason":"24dp tall.","needs":[],"regions":[]}],"others":[]}""",
    )
    val (third, thirdModel) = run()
    assertThat(thirdModel.waiting).isEmpty()
    assertThat(third.deferredRequests).isEqualTo(0)
    val done = third.results.single()
    assertThat(done.unchecked).isEmpty()
    assertThat(done.failures().single().nodeIds).containsExactly("stop")
    assertThat(done.record.servedModel).isEqualTo(DeferredReplyModel.DEFAULT_MODEL)

    // Cached: a later run neither reads nor writes a request.
    val (fourth, fourthModel) = run()
    assertThat(fourth.results.single().fromCache).isTrue()
    assertThat(fourthModel.waiting).isEmpty()
    fourthModel.prune()
    assertThat(requests.listFiles().orEmpty().filter { it.isDirectory }).isEmpty()
  }

  @Test
  fun `an unreadable reply is a failed request that is not split or written again`() {
    val first = run().second.waiting.single()
    reply(first, "not json")
    val (result, model) = run()
    assertThat(result.failedRequests).isEqualTo(1)
    assertThat(result.deferredRequests).isEqualTo(0)
    assertThat(model.waiting).isEmpty()
    assertThat(result.problems.joinToString()).contains("unreadable reply")
  }

  @Test
  fun `the request id is stable and changes with what is asked`() {
    val batch = GuidelineBatch(GuidelineSurfaces.COMPONENT, listOf(subject))
    fun request(round: Int) =
      PreviewGuidelineRequests.request(guidelines, batch, "rules", emptyList(), round)
    assertThat(DeferredReplyModel.idOf(request(0))).isEqualTo(DeferredReplyModel.idOf(request(0)))
    assertThat(DeferredReplyModel.idOf(request(0)))
      .isNotEqualTo(DeferredReplyModel.idOf(request(1)))
    assertThat(DeferredReplyModel.idOf(request(0))).matches("[0-9a-f]{16}")
  }

  @Test
  fun `a need the host cannot serve is never asked of it`() {
    // Kept here because prompt mode offers whatever the CLI host offers: nothing more.
    assertThat(servedKind(GuidelineEvidenceNeedV1.KIND_SEMANTICS, listOf("a11y"))).isEqualTo("a11y")
    assertThat(servedKind("render", emptyList())).isNull()
  }

  @Test
  fun `a batch waiting on a follow-up caches none of its previews, so the next run replays it`() {
    val other = subject.copy(previewId = "com.example.Go", label = "Go", renderHash = "g")
    val both = listOf(subject, other)
    val first = run(subjects = both).second.waiting.single()
    // s1 is decided at once; s2 needs its accessibility data.
    reply(
      first,
      """{"verdicts":[{"subjectId":"s2","ruleId":"touch","verdict":"needs_evidence",
        "confidence":0.3,"nodeIds":[],"reason":"","needs":[{"kind":"a11y","theme":null,
        "fontScale":null,"device":null,"reason":"size"}],"regions":[]}],
        "others":[{"subjectId":"s1","verdict":"pass","confidence":0.9},
                  {"subjectId":"s2","verdict":"pass","confidence":0.9}]}""",
    )
    val (second, secondModel) = run(subjects = both)
    // s1's verdicts are reported, but pending and not cached while its batch waits.
    val waitingOne = second.results.single { it.previewId == subject.previewId }
    assertThat(waitingOne.unchecked).isEmpty()
    assertThat(second.results.all { it.pending }).isTrue()
    assertThat(GuidelineResultCache(cacheDir).checked(subject.previewId)).isFalse()
    val followUp = secondModel.waiting.single()

    reply(
      followUp,
      """{"verdicts":[],"others":[{"subjectId":"s1","verdict":"pass","confidence":0.8}]}""",
    )
    val (third, thirdModel) = run(subjects = both)
    // Round 0 replayed from the first reply, round 1 from the second: nothing new to answer.
    assertThat(thirdModel.waiting).isEmpty()
    assertThat(third.results.all { it.unchecked.isEmpty() && !it.pending }).isTrue()
    assertThat(GuidelineResultCache(cacheDir).checked(other.previewId)).isTrue()
  }
}
