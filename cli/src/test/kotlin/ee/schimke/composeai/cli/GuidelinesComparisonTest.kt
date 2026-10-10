package ee.schimke.composeai.cli

import ee.schimke.composeai.guidelines.JevTriage
import ee.schimke.composeai.guidelines.PreviewGuidelineResult
import ee.schimke.composeai.guidelines.protocol.GuidelineRecordV1
import ee.schimke.composeai.guidelines.protocol.GuidelineVerdictV1
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.serialization.builtins.ListSerializer

class GuidelinesComparisonTest {
  private fun result(
    id: String,
    verdicts: Map<String, Pair<String, Double>>,
    unchecked: List<String> = emptyList(),
    asked: List<String> = verdicts.keys.toList() + unchecked,
  ) =
    PreviewGuidelineResult(
      previewId = id,
      record =
        GuidelineRecordV1.Builder(
            revision = 0,
            model = "m",
            rulesVersion = 1,
            asked = asked,
            verdicts =
              verdicts.map { (rule, v) ->
                GuidelineVerdictV1.Builder(rule, v.first)
                  .apply {
                    subjectId = id
                    confidence = v.second
                  }
                  .build()
              },
          )
          .build(),
      unchecked = unchecked,
    )

  private val jev =
    listOf(
      result(
        "a",
        mapOf(
          "described" to (GuidelineVerdictV1.FAIL to 0.82),
          "styles" to (GuidelineVerdictV1.PASS to 0.9),
          "clipping" to (GuidelineVerdictV1.NEEDS_EVIDENCE to 0.0),
        ),
        unchecked = listOf("clipping"),
        asked = listOf("described", "styles", "clipping"),
      ),
      result("only-jev", mapOf("styles" to (GuidelineVerdictV1.PASS to 0.9))),
    )

  private val vision =
    listOf(
      result(
        "a",
        mapOf(
          "described" to (GuidelineVerdictV1.FAIL to 0.9),
          "styles" to (GuidelineVerdictV1.FAIL to 0.7),
          "clipping" to (GuidelineVerdictV1.PASS to 0.95),
        ),
      )
    )

  @Test
  fun `each preview and rule is set against the other run`() {
    val comparison = GuidelinesComparison.compare(jev, vision)
    val byRule = comparison.rows.associateBy { it.ruleId }
    assertEquals(GuidelinesComparison.Outcome.AGREE, byRule.getValue("described").outcome)
    assertEquals(GuidelinesComparison.Outcome.DISAGREE, byRule.getValue("styles").outcome)
    assertEquals(GuidelinesComparison.Outcome.ONLY_THERE, byRule.getValue("clipping").outcome)
    assertEquals(listOf("only-jev"), comparison.onlyHere)

    val text = GuidelinesComparison.render(comparison, "jev ${JevTriage.MODEL}", "vision m")
    assertContains(text, "previews in both: 1 (1 only here, 0 only there)")
    assertContains(
      text,
      "agree 1, disagree 1, decided here only 0, decided there only 1, unchecked in both 0",
    )
    assertContains(text, "agreement where both decided: 50.0% (2 verdicts)")
    assertContains(text, "findings there also found here: 1 of 2")
    assertContains(text, "clipping: 0 / 0 / 0 / 1 / 0")
    assertContains(text, "a styles: here pass (0.90), there fail (0.70)")
  }

  @Test
  fun `a report is read as one module or as the list --json prints, and the checker is kept`() {
    val dir = Files.createTempDirectory("guidelines-compare").toFile()
    try {
      val one = dir.resolve("one.json")
      val module = ModuleGuidelines("m", "wear-m3", JevTriage.MODEL, jev, checker = "jev")
      one.writeText(
        GuidelinesCommand.REPORT_JSON.encodeToString(ModuleGuidelines.serializer(), module)
      )
      assertEquals("jev", GuidelinesComparison.load(one)!!.single().checker)
      val list = dir.resolve("list.json")
      list.writeText(
        GuidelinesCommand.REPORT_JSON.encodeToString(
          ListSerializer(ModuleGuidelines.serializer()),
          listOf(module, module.copy(module = "n")),
        )
      )
      assertEquals(listOf("m", "n"), GuidelinesComparison.load(list)!!.map { it.module })
      // A vision report records no checker at all, so its file is what it was before.
      val visionText =
        GuidelinesCommand.REPORT_JSON.encodeToString(
          ModuleGuidelines.serializer(),
          ModuleGuidelines("m", "wear-m3", "m", vision),
        )
      assertFalse("checker" in visionText)
      assertEquals(null, GuidelinesComparison.load(dir.resolve("missing.json")))
    } finally {
      dir.deleteRecursively()
    }
  }
}
