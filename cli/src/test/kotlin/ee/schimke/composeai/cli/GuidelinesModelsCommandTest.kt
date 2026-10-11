package ee.schimke.composeai.cli

import ee.schimke.composeai.guidelines.OpenRouterModels
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GuidelinesModelsCommandTest {
  private val models =
    listOf(
      OpenRouterModels.Model("dear/vision", "Dear", 1000, 5.0, 15.0, true, true),
      OpenRouterModels.Model("cheap/vision", "Cheap", 2000, 0.1, 0.4, true, true),
      OpenRouterModels.Model("text/only", "Text", 3000, 0.01, 0.02, false, true),
    )

  private fun out(args: List<String>, fetch: () -> List<OpenRouterModels.Model> = { models }) =
    ByteArrayOutputStream().let { bytes ->
      val original = System.out
      System.setOut(PrintStream(bytes, true))
      try {
        GuidelinesModelsCommand(args, fetch).list() to bytes.toString()
      } finally {
        System.setOut(original)
      }
    }

  @Test
  fun `only usable models are listed, cheapest first`() {
    val (code, text) = out(emptyList())
    assertEquals(0, code)
    assertTrue(text.indexOf("cheap/vision") < text.indexOf("dear/vision"))
    assertFalse("text/only" in text)
    assertTrue("--all lists the rest" in text)
  }

  @Test
  fun `--all lists everything and --json is machine-readable`() {
    assertTrue("text/only" in out(listOf("--all")).second)
    val json = out(listOf("--json")).second
    assertTrue("\"id\":\"cheap/vision\"" in json)
    assertTrue("\"promptUsdPerMillion\":0.1" in json)
  }

  @Test
  fun `an unreadable list exits 2`() {
    assertEquals(2, out(emptyList()) { throw IOException("offline") }.first)
  }
}
