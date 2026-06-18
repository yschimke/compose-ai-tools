package ee.schimke.composeai.daemon.history

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the `history/diff mode=data` data-product diff (issue #1873) at the
 * [HistoryDataDiff] object level — no wire round-trip. Covers the three sections (semantics / a11y
 * / theme), a11y finding ref-keying via the captured hierarchy, and the "present only when both
 * entries carry the product" rule.
 */
class HistoryDataDiffTest {

  private val json = Json { ignoreUnknownKeys = true }

  private fun parse(text: String): JsonElement = json.parseToJsonElement(text)

  private fun entry(
    id: String,
    semantics: JsonElement? = null,
    a11yAtf: JsonElement? = null,
    a11yHierarchy: JsonElement? = null,
    theme: JsonElement? = null,
  ): HistoryEntry =
    HistoryEntry(
      id = id,
      previewId = "preview-A",
      module = ":t",
      timestamp = "2026-06-17T10:00:00Z",
      pngHash = "hash-$id",
      pngSize = 1,
      pngPath = "$id.png",
      producer = "daemon",
      trigger = "renderNow",
      source = HistorySourceInfo(kind = "fs", id = "fs:/tmp"),
      renderTookMs = 1,
      semantics = semantics,
      a11yAtf = a11yAtf,
      a11yHierarchy = a11yHierarchy,
      theme = theme,
    )

  @Test
  fun semantics_section_reports_field_change() {
    val from = entry("e1", semantics = semanticsPayload("greeting", "Hello"))
    val to = entry("e2", semantics = semanticsPayload("greeting", "World"))
    val delta = HistoryDataDiff.diff(from, to)
    val semantics = delta.semantics!!
    assertEquals(1, semantics.changed.size)
    val change = semantics.changed.single().changes.single()
    assertEquals("text", change.field)
    assertEquals("Hello", change.from)
    assertEquals("World", change.to)
    // The other products weren't captured, so their sections stay null.
    assertNull(delta.a11y)
    assertNull(delta.theme)
  }

  @Test
  fun a11y_finding_change_keyed_by_stable_ref() {
    // Same node bounds → same hierarchy ref → the contrast finding's level/message change reports
    // as a change on the same ref, not a remove + add.
    val hierarchy = hierarchyPayload(ref = "a/role:Button[0]", bounds = "0,0,48,48")
    val from =
      entry(
        "e1",
        a11yAtf =
          findingsPayload(type = "TextContrastCheck", level = "WARNING", bounds = "0,0,48,48"),
        a11yHierarchy = hierarchy,
      )
    val to =
      entry(
        "e2",
        a11yAtf =
          findingsPayload(type = "TextContrastCheck", level = "ERROR", bounds = "0,0,48,48"),
        a11yHierarchy = hierarchy,
      )
    val a11y = HistoryDataDiff.diff(from, to).a11y!!
    assertTrue(a11y.added.isEmpty())
    assertTrue(a11y.removed.isEmpty())
    assertEquals(1, a11y.changed.size)
    val change = a11y.changed.single()
    assertEquals("a/role:Button[0]", change.ref)
    assertEquals("TextContrastCheck", change.type)
    val levelChange = change.changes.single { it.field == "level" }
    assertEquals("WARNING", levelChange.from)
    assertEquals("ERROR", levelChange.to)
  }

  @Test
  fun a11y_finding_added_and_removed() {
    val from =
      entry("e1", a11yAtf = findingsPayload(type = "SpeakableTextPresentCheck", bounds = "0,0,1,1"))
    val to =
      entry("e2", a11yAtf = findingsPayload(type = "TouchTargetSizeCheck", bounds = "0,0,2,2"))
    val a11y = HistoryDataDiff.diff(from, to).a11y!!
    assertEquals(listOf("TouchTargetSizeCheck"), a11y.added.map { it.type })
    assertEquals(listOf("SpeakableTextPresentCheck"), a11y.removed.map { it.type })
    assertTrue(a11y.changed.isEmpty())
  }

  @Test
  fun theme_section_reports_color_token_change() {
    val from = entry("e1", theme = themePayload(primary = "0xFF6750A4"))
    val to = entry("e2", theme = themePayload(primary = "0xFFB3261E"))
    val theme = HistoryDataDiff.diff(from, to).theme!!
    assertEquals(1, theme.colorScheme.size)
    assertEquals("0xFFB3261E", theme.colorScheme.single().to)
  }

  @Test
  fun section_is_null_when_product_only_on_one_side() {
    val from = entry("e1", theme = themePayload(primary = "0xFF6750A4"))
    val to = entry("e2") // no theme captured
    val delta = HistoryDataDiff.diff(from, to)
    assertNull("theme not compared when only one side has it", delta.theme)
    assertTrue(delta.isEmpty)
  }

  @Test
  fun combined_delta_carries_all_three_sections() {
    val from =
      entry(
        "e1",
        semantics = semanticsPayload("greeting", "Hello"),
        a11yAtf =
          findingsPayload(type = "TouchTargetSizeCheck", level = "ERROR", bounds = "0,0,1,1"),
        theme = themePayload(primary = "0xFF6750A4"),
      )
    val to =
      entry(
        "e2",
        semantics = semanticsPayload("greeting", "World"),
        a11yAtf =
          findingsPayload(type = "TouchTargetSizeCheck", level = "ERROR", bounds = "0,0,1,1"),
        theme = themePayload(primary = "0xFFB3261E"),
      )
    val delta = HistoryDataDiff.diff(from, to)
    assertEquals(HistoryDataDiffProduct.SCHEMA, delta.schema)
    assertTrue("semantics changed", delta.semantics!!.changed.isNotEmpty())
    assertTrue("a11y identical → empty section", delta.a11y!!.isEmpty)
    assertTrue("theme changed", delta.theme!!.colorScheme.isNotEmpty())
    assertTrue("delta overall non-empty", !delta.isEmpty)
  }

  // --- payload builders ------------------------------------------------------

  private fun semanticsPayload(testTag: String, text: String): JsonElement =
    parse(
      """{"root":{"nodeId":"1","boundsInRoot":"0,0,100,50","testTag":"$testTag","text":"$text"}}"""
    )

  private fun findingsPayload(
    type: String,
    level: String = "WARNING",
    message: String = "msg",
    bounds: String,
  ): JsonElement =
    parse(
      """{"findings":[{"level":"$level","type":"$type","message":"$message","boundsInScreen":"$bounds"}]}"""
    )

  private fun hierarchyPayload(ref: String, bounds: String): JsonElement =
    parse("""{"nodes":[{"label":"x","ref":"$ref","boundsInScreen":"$bounds"}]}""")

  private fun themePayload(primary: String): JsonElement =
    parse(
      """{"resolvedTokens":{"colorScheme":{"primary":"$primary"},"typography":{},"shapes":{}},"consumers":[]}"""
    )
}
