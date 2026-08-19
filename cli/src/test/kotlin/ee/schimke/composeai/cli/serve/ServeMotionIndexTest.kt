package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The catalog-wide **motion browser** ([ServeWeb.motionIndexPage]) and the landing chip that is its
 * entry point.
 *
 * The page is markup-only — swapping an `<img src>` is its whole behaviour — so these assertions
 * are about the two things that decide whether it works at all: that a card points at the right
 * *two* URLs (its still and its recording), and that pressing it is the only thing that starts
 * anything. The committed fixture in [ServeWebFixtureTest] carries the pixels.
 */
class ServeMotionIndexTest {

  private fun preview(
    id: String,
    label: String,
    section: String? = null,
    order: Int? = null,
    motion: List<ServeMotion> = emptyList(),
  ) = ServePreview(id = id, label = label, section = section, catalogOrder = order, motion = motion)

  private val card =
    preview(
      "card__filled",
      "Card · Filled",
      section = "Components",
      order = 1,
      motion =
        listOf(
          ServeMotion(
            id = "card__filled__interaction",
            kind = "interaction",
            caption =
              "Press and hold the card. The container lifts to its pressed elevation through " +
                "the theme's spatial spring.",
            extension = ".apng",
          ),
          ServeMotion(
            id = "card__filled__anim",
            kind = "animation",
            caption = "Toggle repeatedly. The container morphs between its shapes.",
            extension = ".gif",
          ),
        ),
    )

  private val still = preview("badge__default", "Badge", section = "Components", order = 2)

  private fun page(previews: List<ServePreview> = listOf(card, still)) =
    ServeWeb.motionIndexPage(
      moduleLabel = "compose-m3",
      previews = previews,
      token = "t",
      sessionId = "compose-m3",
      basePath = "/compose-m3",
    )

  @Test
  fun `a card carries both the still it opens on and the recording it swaps to`() {
    val html = page()
    // The `src` the card ships with is the BAKED STILL, not the capture: nothing decodes an
    // animation until a reader asks for one.
    assertTrue(
      html.contains("data-motion-poster=\"/compose-m3/render/card__filled.png?token=t\""),
      "the card names its component's still as the image to return to",
    )
    assertTrue(
      Regex("<img class=\"cp-motion-card-img\"[^>]*src=\"/compose-m3/render/card__filled\\.png")
        .containsMatchIn(html),
      "and ships with that still as its `src`",
    )
    assertFalse(
      Regex("<img class=\"cp-motion-card-img\"[^>]*src=\"[^\"]*\\.(apng|gif)")
        .containsMatchIn(html),
      "no card starts on a recording — the page must not autoplay",
    )
    // …and the recording is the swap target, at the extension the manifest published. An APNG
    // served as a `.gif` renders one frame and stops (see [ServeMotion.extension]).
    assertTrue(
      html.contains(
        "data-motion-src=\"/compose-m3/motion/card__filled__interaction.apng?token=t\""
      ),
      "the interaction capture keeps its .apng",
    )
    assertTrue(
      html.contains("data-motion-src=\"/compose-m3/motion/card__filled__anim.gif?token=t\""),
      "and the animation capture keeps its .gif",
    )
  }

  @Test
  fun `each capture gets its own card, deep-linked to that recording in the viewer`() {
    val html = page()
    assertEquals(
      2,
      Regex("class=\"cp-motion-card-stage\"").findAll(html).count(),
      "one card per capture, not per component — two recordings are two things to compare",
    )
    // `?motion=<id>` is what the viewer reads to open its picker on the shared recording rather
    // than on the component's first one. `&amp;` because these are attribute values, not URLs in
    // the raw — an unescaped `&` in an href is the classic way a query param goes missing.
    assertTrue(
      html.contains(
        "/compose-m3/p/card__filled?token=t&amp;mode=motion&amp;motion=card__filled__anim"
      ),
      "the second capture's card opens the viewer on the second capture",
    )
    assertTrue(
      html.contains(
        "/compose-m3/p/card__filled?token=t&amp;mode=motion&amp;motion=card__filled__interaction"
      ),
      "and the first on the first",
    )
  }

  @Test
  fun `captures are named the way the viewer's picker names them`() {
    val html = page()
    // The same first-clause split [MotionCaptureLabels] gives the viewer, so one recording is
    // called one thing in both places.
    assertTrue(
      html.contains(">Press and hold the card<"),
      "the title is the caption's first clause",
    )
    assertTrue(
      html.contains("The container lifts to its pressed elevation"),
      "and the rest of the caption is printed under the card, not thrown away",
    )
  }

  @Test
  fun `a preview with no captures is absent, and the summary counts what is there`() {
    val html = page()
    assertFalse(html.contains("badge__default"), "a still-only component has nothing to show here")
    assertTrue(
      html.contains("2 recordings across 1 component"),
      "the summary counts captures and components separately",
    )
  }

  @Test
  fun `the landing offers the browser only when the catalog records something`() {
    val withMotion =
      ServeWeb.landingPage(
        "compose-m3",
        listOf(card, still),
        "t",
        basePath = "/compose-m3",
        motionCaptureCount = 2,
      )
    assertTrue(
      withMotion.contains("href=\"/compose-m3/motion?token=t\">2 motion captures</a>"),
      "the chip is the entry point, and its label says how much is behind it",
    )

    val none = ServeWeb.landingPage("compose-m3", listOf(still), "t", basePath = "/compose-m3")
    assertFalse(
      none.contains("/compose-m3/motion?"),
      "a catalog that records nothing is not offered an empty page",
    )
  }

  @Test
  fun `one capture is singular`() {
    val single =
      ServeWeb.landingPage(
        "compose-m3",
        listOf(card),
        "t",
        basePath = "/compose-m3",
        motionCaptureCount = 1,
      )
    assertTrue(single.contains(">1 motion capture</a>"), "one is a capture, not captures")
  }

  @Test
  fun `Catalog mode keeps its destinations off the page`() {
    val catalogMode =
      ServeWeb.landingPage(
        "compose-m3",
        listOf(card),
        "t",
        basePath = "/compose-m3",
        motionCaptureCount = 2,
        componentBrowser = true,
      )
    // Not because the browser is unsuitable there, but because it would be the only thing left in
    // the `⋯` menu — see the shadowing block in `landingPage` for the trade.
    assertFalse(catalogMode.contains("/compose-m3/motion"), "no destinations in Catalog mode")
  }

  @Test
  fun `cards are grouped under the catalog's own sections`() {
    val screens =
      preview(
        "home__screen",
        "Home",
        section = "Screens",
        order = 9,
        motion = listOf(ServeMotion(id = "home__scroll", kind = "interaction")),
      )
    val html = page(listOf(card, screens))
    val components = html.indexOf("<h2 class=\"cp-section-head\">Components</h2>")
    val screensHead = html.indexOf("<h2 class=\"cp-section-head\">Screens</h2>")
    assertTrue(components in 1..<screensHead, "sections read in the catalog's authored order")
    // A capture whose annotation declared no caption falls back to its kind rather than to a
    // blank label — the same honesty [MotionCaptureLabels] applies in the viewer's picker.
    assertTrue(html.contains(">Interaction<"), "an uncaptioned capture is still named")
  }
}
