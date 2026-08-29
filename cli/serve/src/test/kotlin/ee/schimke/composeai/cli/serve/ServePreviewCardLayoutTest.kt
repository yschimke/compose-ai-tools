package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertTrue

/** Pins the height chain that keeps mixed-size catalog previews aligned across family groups. */
class ServePreviewCardLayoutTest {

  private val css = ServeWebAssets.load("serve.css")!!.bytes.decodeToString()

  @Test
  fun `preview cards fill the tallest family and center short content`() {
    assertTrue(
      css.contains(
        ".cp-section, .cp-grid-groups { display: flex; flex-wrap: wrap; align-items: stretch;"
      ),
      "family groups stretch to the tallest group on their flex line",
    )
    assertTrue(
      css.contains(".cp-subgroup {\n  display: flex; flex-direction: column;"),
      "each family carries the line height down to its cards",
    )
    assertTrue(
      css.contains(
        ".cp-cards { display: flex; flex: 1 1 auto; flex-wrap: wrap; align-items: stretch;"
      ),
      "the card row fills the family and stretches every card",
    )
    assertTrue(
      css.contains(
        "display: flex; flex-direction: column; }\n.cp-cards > .cp-card > .cp-imgwrap { flex: 1 1 auto; }"
      ),
      "the image well fills the card so its existing centering centers short previews vertically",
    )
    assertTrue(
      css.contains(
        ".cp-imgwrap { position: relative; display: flex; align-items: center; justify-content: center;"
      ),
      "expanded image wells center their preview content",
    )
  }
}
