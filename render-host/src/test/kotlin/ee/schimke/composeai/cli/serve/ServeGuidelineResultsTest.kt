package ee.schimke.composeai.cli.serve

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServeGuidelineResultsTest {
  private val report =
    """
    {
      "module": ":catalog", "catalog": "wear-m3", "model": "deepseek/deepseek-v4.1-flash",
      "results": [
        {"previewId": "demo.WearList_192dp", "renderHash": "abc",
         "record": {"schema": "compose-ui-builder/guidelines-result/v1", "revision": 0,
           "model": "deepseek/deepseek-v4.1-flash", "rulesVersion": 1,
           "asked": ["wear.touch-target-48dp", "wear.type.theme-styles"],
           "servedModel": "deepseek/deepseek-v4.1-flash", "costUsd": 0.0034,
           "verdicts": [
             {"ruleId": "wear.touch-target-48dp", "verdict": "fail", "confidence": 1.7,
              "nodeIds": ["n1", "n1"], "reason": "36dp",
              "regions": [{"x": 0.8, "y": -0.2, "width": 0.9, "height": 0.5}]},
             {"ruleId": "wear.type.theme-styles", "verdict": "made-up", "confidence": 0.9}
           ]}},
        {"previewId": "demo.Other", "record": {"schema": "something-else", "revision": 0,
           "model": "m", "rulesVersion": 1, "asked": [], "verdicts": []}},
        {"previewId": "demo.Broken", "record": 42}
      ]
    }
    """
      .trimIndent()

  @Test
  fun `a bundle's results load per preview, cleaned`() {
    val dir = Files.createTempDirectory("guideline-results").toFile()
    dir.resolve(ServeGuidelineResultsStore.FILE).writeText(report)
    val results = assertNotNull(ServeGuidelineResultsStore.load(dir))
    assertEquals("wear-m3", results.catalog)
    assertEquals(setOf("demo.WearList_192dp"), results.records.keys)
    val record = assertNotNull(results.forPreview("demo.WearList_192dp"))
    assertEquals("deepseek/deepseek-v4.1-flash", record.servedModel)
    val verdict = record.verdicts.single()
    assertEquals("wear.touch-target-48dp", verdict.ruleId)
    assertEquals(1.0, verdict.confidence)
    assertEquals(listOf("n1"), verdict.nodeIds)
    val region = verdict.regions.single()
    assertEquals(0.0, region.y)
    assertTrue(region.x + region.width <= 1.0)
    assertNull(results.forPreview("demo.Other"))
  }

  @Test
  fun `a missing, unreadable or oversized file is no results`() {
    val dir = Files.createTempDirectory("guideline-results-bad").toFile()
    assertNull(ServeGuidelineResultsStore.load(dir))
    dir.resolve(ServeGuidelineResultsStore.FILE).writeText("{not json")
    assertNull(ServeGuidelineResultsStore.load(dir))
    assertNull(ServeGuidelineResultsStore.parse("""{"results": "nope"}"""))
    val many =
      (0..ServeGuidelineResultsStore.MAX_PREVIEWS).joinToString(",", "[", "]") {
        """{"previewId":"p$it"}"""
      }
    assertNull(ServeGuidelineResultsStore.parse("""{"results": $many}"""))
  }
}
