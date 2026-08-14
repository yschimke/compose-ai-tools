package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the viewer's **Source** lane — the chip, its panel, and the mode radio that makes the lane
 * behave like every other one.
 *
 * The lane's *content* is drawn client-side (see `viewer.js`) and captured for the visual-diff bot
 * by the `serve-viewer-source` fixture's `source-panel` state. What is server-rendered, and what
 * these tests hold, is narrower and easy to get wrong: the chip must exist exactly when the host
 * can actually answer for it, the panel must be present-but-empty so the stage does not jump on
 * first open, and the radio must join the mode group or the lane drops out of the URL.
 */
class ServeViewerSourceLaneTest {

  private val token = "t"

  private fun page(usageHref: String? = null, playgroundHref: String? = null) =
    ServeWeb.viewerPage(
      ServePreview("com.example.ProfileCardPreview", "Profile card"),
      token,
      sessionId = "compose-m3",
      usageHref = usageHref,
      playgroundHref = playgroundHref,
    )

  @Test
  fun `a preview with usage source offers the chip, an empty panel and a mode radio`() {
    val html = page(usageHref = "/usage/com.example.ProfileCardPreview")
    assertTrue(html.contains("id=\"cp-source-chip\""), "no Source chip")
    assertTrue(
      html.contains("data-usage-src=\"/usage/com.example.ProfileCardPreview\""),
      "the chip must carry the URL it fetches from",
    )
    // Present, and empty: server-rendered so the panel has a stable place in the stage, filled only
    // once the chip is pressed.
    assertTrue(html.contains("id=\"cp-source-panel\""), "no Source panel")
    assertTrue(html.contains("id=\"cp-source-panel\" role=\"region\""), "panel is not a region")
    assertTrue(
      html.contains("aria-label=\"Usage source\" hidden></div>"),
      "the panel must be rendered empty and hidden, not pre-filled",
    )
    assertTrue(
      html.contains("name=\"cp-mode\" value=\"source\" id=\"cp-source-toggle\""),
      "the lane needs a mode radio or it drops out of ?mode= and Back/Forward",
    )
  }

  /**
   * The chip starts un-pressed and describes what pressing it does. `viewer.js` inverts the tooltip
   * on entry, and reads the resting one back off this attribute — so the attribute has to be here,
   * not only the `title`.
   */
  @Test
  fun `the chip rests un-pressed and carries its own tooltip`() {
    val html = page(usageHref = "/usage/x")
    assertTrue(html.contains("id=\"cp-source-chip\" class=\"cp-spec-chip cp-source-chip\""))
    assertTrue(html.contains("aria-pressed=\"false\" aria-controls=\"cp-source-panel\""))
    assertTrue(
      html.contains("data-source-chip-tip=\"Show the plain Compose that produces this render\"")
    )
  }

  /** No derivable source ⇒ no chip, no panel, no radio. Never a dead control. */
  @Test
  fun `a preview with no usage source offers nothing at all`() {
    val html = page(usageHref = null)
    for (marker in
      listOf("cp-source-chip", "cp-source-panel", "cp-source-toggle", "value=\"source\"")) {
      assertFalse(html.contains(marker), "$marker rendered without a usage source")
    }
  }

  /** Blank is the same as absent — a host that computed an empty href must not get a dead chip. */
  @Test
  fun `a blank usage href is treated as no source`() {
    assertFalse(page(usageHref = "  ").contains("cp-source-chip"))
  }

  /**
   * The two affordances are independent on purpose. Reading the code is useful wherever a catalog
   * can be browsed; running it needs a host that can compile that catalog, which most of the public
   * deployment's catalogs have none of. So Source without playground is the common case and must
   * work.
   */
  @Test
  fun `Source is offered without a playground, and the playground without Source`() {
    val sourceOnly = page(usageHref = "/usage/x")
    assertTrue(sourceOnly.contains("cp-source-chip"))
    assertFalse(sourceOnly.contains("▶ playground"))

    val playgroundOnly = page(playgroundHref = "/playground?from=compose-m3/x")
    assertFalse(playgroundOnly.contains("cp-source-chip"))
    assertTrue(playgroundOnly.contains("▶ playground"))
  }

  /**
   * The chip sits beside the design-spec chip on the control row, not inside the renderer combo.
   * That combo is headed "Switch renderer" and source is not a renderer — the same reasoning that
   * put the spec lane on the row in the first place.
   */
  @Test
  fun `the chip is a control of its own, not a renderer option`() {
    val html = page(usageHref = "/usage/x")
    val laneSelect = html.substringAfter("id=\"cp-lane-select\"", "").substringBefore("</select>")
    assertFalse(laneSelect.contains("source"), "Source must not be an option in the renderer combo")
    assertEquals(1, Regex("id=\"cp-source-chip\"").findAll(html).count())
  }
}
