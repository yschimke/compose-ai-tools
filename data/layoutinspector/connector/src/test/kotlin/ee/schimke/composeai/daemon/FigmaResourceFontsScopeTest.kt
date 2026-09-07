package ee.schimke.composeai.daemon

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Which file a FAMILY resolved to is a fact about one render, not about the process.
 *
 * `remote-m3` proves it within a single catalog: a typography specimen declaring `wght`/`wdth` axes
 * resolves Roboto Flex's variable file, while a sticker declaring no axes resolves a static
 * per-weight instance. Held process-wide the specimen's registration outlived it, and every later
 * preview embedded the variable file it never drew — 1.6 MB of `gvar` for a document that varies
 * nothing.
 *
 * A `res/font/<resId>` handle is the opposite case and must survive: it names one concrete face
 * whose bytes do not change for the life of the render JVM, and re-extracting it per preview would
 * cost a whole-catalog render the same work repeatedly.
 */
class FigmaResourceFontsScopeTest {

  @Before fun reset() = FigmaResourceFonts.clear()

  @After fun cleanup() = FigmaResourceFonts.clear()

  @Test
  fun `a family's file does not outlive the preview that resolved it`() {
    FigmaResourceFonts.register("Roboto Flex", 550, false, "/cache/roboto-flex-variable.ttf")
    assertEquals(
      "/cache/roboto-flex-variable.ttf",
      FigmaResourceFonts.pathFor("Roboto Flex", 550, false),
    )

    FigmaResourceFonts.beginPreview()

    assertNull(
      "the next preview must not inherit the previous one's file",
      FigmaResourceFonts.pathFor("Roboto Flex", 550, false),
    )
  }

  @Test
  fun `the preview that draws it wins over one that drew it before`() {
    FigmaResourceFonts.register("Roboto Flex", 550, false, "/cache/roboto-flex-variable.ttf")
    FigmaResourceFonts.beginPreview()
    FigmaResourceFonts.register("Roboto Flex", 550, false, "/cache/roboto-flex-550.ttf")

    assertEquals(
      "/cache/roboto-flex-550.ttf",
      FigmaResourceFonts.pathFor("Roboto Flex", 550, false),
    )
  }

  @Test
  fun `a resource face survives, because its bytes are a process fact`() {
    val identity = FigmaResourceFonts.identityFor(2131296256)
    FigmaResourceFonts.register(identity, "/extracted/montserrat_medium.ttf")

    FigmaResourceFonts.beginPreview()

    assertEquals("/extracted/montserrat_medium.ttf", FigmaResourceFonts.pathFor(identity))
    assertEquals(
      "and stays reachable through the weight-qualified lookup",
      "/extracted/montserrat_medium.ttf",
      FigmaResourceFonts.pathFor(identity, 500, false),
    )
  }
}
