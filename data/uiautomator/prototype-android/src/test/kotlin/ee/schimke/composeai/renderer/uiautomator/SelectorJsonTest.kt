package ee.schimke.composeai.renderer.uiautomator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage of the JSON wire format for [Selector]. Exercised standalone (no Robolectric
 * sandbox) because everything here is data-shape conversion.
 */
class SelectorJsonTest {

  @Test
  fun `simple selector round-trips`() {
    val original = By.text("Submit").enabled(true)
    val json = original.encodeJson()
    val parsed = decodeSelectorJson(json)
    assertEquals(original, parsed)
    // Wire shape is the documented flat object — no nulls, no defaults.
    assertEquals("""{"text":"Submit","enabled":true}""", json)
  }

  @Test
  fun `regex variant uses the parallel textMatches key on the wire`() {
    val original = By.textMatches("Item \\d+").clickable()
    val json = original.encodeJson()
    val parsed = decodeSelectorJson(json)
    assertEquals(original, parsed)
    assertTrue(json.contains("\"textMatches\":\"Item \\\\d+\""))
    assertTrue(json.contains("\"clickable\":true"))
  }

  @Test
  fun `tree predicates serialize as nested arrays`() {
    val original = By.desc("row-2").hasDescendant(By.text("Bob"))
    val json = original.encodeJson()
    val parsed = decodeSelectorJson(json)
    assertEquals(original, parsed)
    assertEquals(
      """{"desc":"row-2","hasDescendant":[{"text":"Bob"}]}""",
      json,
    )
  }

  @Test
  fun `unknown JSON keys are tolerated for forward-compat`() {
    val parsed = decodeSelectorJson("""{"text":"Submit","futureField":"ignored"}""")
    assertEquals(By.text("Submit"), parsed)
  }

  @Test
  fun `mutually exclusive text and textMatches are rejected`() {
    val ex =
      assertThrows(IllegalArgumentException::class.java) {
        decodeSelectorJson("""{"text":"a","textMatches":"b"}""")
      }
    assertTrue(ex.message!!, ex.message!!.contains("text"))
  }

  @Test
  fun `every BySelector chain we expose round-trips through JSON`() {
    val original =
      Selector()
        .text("Submit")
        .desc("button-row")
        .clazz("android.widget.Button")
        .res("submit-btn")
        .enabled(true)
        .clickable(true)
        .longClickable(false)
        .checkable(true)
        .checked(true)
        .selected(true)
        .focused(false)
        .scrollable(false)
        .hasChild(By.text("Inner"))
        .hasDescendant(By.desc("badge"))
    val parsed = decodeSelectorJson(original.encodeJson())
    assertEquals(original, parsed)
  }
}
