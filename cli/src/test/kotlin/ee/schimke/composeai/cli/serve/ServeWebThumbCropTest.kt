package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the HTML wiring of the server-side thumbnail crop: when a card's [ContentCrop] is present
 * the render `<img>` is wrapped in a `.cp-crop` clip window sized by aspect-ratio (so a Wear
 * sticker shows the component, not its watch canvas) and framed in PERCENTAGES so it shrinks with a
 * narrow grid card instead of overflowing it; when absent the card keeps the plain fit-to-box
 * `<img>` — so a phone/desktop catalog and the plain-module landing are untouched.
 */
class ServeWebThumbCropTest {

  private val previews =
    listOf(ServePreview(id = "filled-button__ideal__default__compact", label = "Filled"))
  private val crop =
    ContentCrop(boxW = 120, boxH = 48, imgW = 454, imgH = 454, left = -167, top = -203)

  @Test
  fun `a catalog card with a crop wraps the image in an aspect-sized clip window`() {
    val html =
      ServeWeb.landingPage(
        "wear-m3",
        previews,
        token = "t",
        basePath = "/wear-m3",
        thumbCrop = { crop },
      )
    // The window's natural width is the box, but it sizes by aspect-ratio (so `max-width: 100%` can
    // shrink it on a narrow card) rather than a fixed height.
    assertTrue(
      html.contains("class=\"cp-crop\" style=\"width:120px;aspect-ratio:120/48\""),
      "clip window sized to the box by aspect-ratio",
    )
    // Render img framed in percentages of the box (454/120, -167/120, -203/48), so the whole frame
    // scales as one when the window shrinks.
    assertTrue(
      html.contains("style=\"width:378.3333%;left:-139.1667%;top:-422.9167%\""),
      "render img sized + offset in box-percentages to show only the component",
    )
  }

  @Test
  fun `a catalog card with no crop keeps the plain image (no clip window)`() {
    val html = ServeWeb.landingPage("compose-m3", previews, token = "t", basePath = "/compose-m3")
    // The `.cp-crop` CSS rule ships on every page; assert the absence of the *wrapper element*.
    assertFalse(html.contains("class=\"cp-crop\""), "uncropped cards carry no clip window")
    assertTrue(html.contains("<img loading=\"lazy\" alt=\"Filled\""), "plain fit-to-box image")
  }

  @Test
  fun `the home hero card is framed when the system carries a hero crop`() {
    val system =
      ServeWeb.HomeSystem(
        system = "wear-m3",
        title = "Wear Compose Material 3",
        subtitle = null,
        previewCount = 34,
        trust = null,
        heroPreviewId = "filled-button__ideal__default__compact",
        heroCrop = crop,
      )
    val html = ServeWeb.homeIndexPage(listOf(system), token = "t", isPublic = true)
    assertTrue(
      html.contains("class=\"cp-crop\" style=\"width:120px;aspect-ratio:120/48\""),
      "hero framed to its box",
    )
  }

  @Test
  fun `the crop CSS is present so the clip window actually clips`() {
    val html = ServeWeb.landingPage("wear-m3", previews, token = "t", thumbCrop = { crop })
    assertTrue(
      html.contains(".cp-crop { position: relative; overflow: hidden;"),
      "clip style shipped",
    )
    assertTrue(
      html.contains(".cp-imgwrap .cp-crop img { position: absolute; max-width: none;"),
      "img escapes the fit-to-box cap",
    )
  }

  /** The section an operator's `catalogs.json` declares for Android's samples. */
  private val androidSamples =
    ServeWeb.HomeGroup(
      heading = "android/compose-samples",
      noun = "sample(s)",
      // The preview branches currently live in the fork; both spellings are Android's samples.
      repos = setOf("android/compose-samples", "yschimke/compose-samples"),
    )

  private val designSystems =
    ServeWeb.HomeGroup(
      heading = "Design Systems",
      noun = "design system(s)",
      repos = setOf("yschimke/compose-ai-tools"),
    )

  @Test
  fun `all compose sample catalogs are attributed to android and shown on the homepage`() {
    val sampleIds =
      listOf("jetnews", "jetcaster", "jetcaster-wear", "jetchat", "jetsnack", "jetlagged", "reply")
    val systems = sampleIds.map { id ->
      ServeWeb.HomeSystem(
        system = id,
        title = id,
        subtitle = null,
        previewCount = 1,
        // The preview branches currently live in this fork. That fetch/trust origin must not
        // make the public homepage attribute Android's samples to the fork owner.
        trust = "branch:yschimke/compose-samples@design-artifacts/$id",
        sourceRepo = "yschimke/compose-samples",
        heroPreviewId = null,
        group = androidSamples,
      )
    }

    val html = ServeWeb.homeIndexPage(systems, token = "t", isPublic = true)

    assertTrue(html.contains("<h1 class=\"cp-head\">android/compose-samples</h1>"))
    assertFalse(html.contains("<h1 class=\"cp-head\">yschimke org</h1>"))
    sampleIds.forEach { id ->
      assertTrue(html.contains("href=\"/$id/\""), "$id is linked from the homepage")
    }
  }

  @Test
  fun `a reused sample id is attributed to its actual catalog repository`() {
    // The config declares the samples section, but these bytes came from an unrelated repo — the
    // claim doesn't hold, so the card falls back to its own publisher rather than Android's.
    val system =
      ServeWeb.HomeSystem(
        system = "jetnews",
        title = "Unrelated Jetnews",
        subtitle = null,
        previewCount = 1,
        trust = "branch:someorg/unrelated@design-artifacts/jetnews",
        sourceRepo = "someorg/unrelated",
        heroPreviewId = null,
        group = androidSamples,
      )

    val html = ServeWeb.homeIndexPage(listOf(system), token = "t", isPublic = true)

    assertFalse(html.contains("<h1 class=\"cp-head\">android/compose-samples</h1>"))
    assertTrue(html.contains("<h1 class=\"cp-head\">someorg org</h1>"))
    assertTrue(html.contains("href=\"/jetnews/\""))
  }

  @Test
  fun `a reused design-system id is attributed to its actual catalog repository`() {
    // The same spoof the sample ids are already guarded against: a catalog id is claimed by
    // whoever publishes it, so `compose-m3` from someone else must not read as the official
    // design system on the public front door.
    val impostor =
      ServeWeb.HomeSystem(
        system = "compose-m3",
        title = "Definitely Material 3",
        subtitle = null,
        previewCount = 1,
        trust = "branch:someorg/unrelated@design-artifacts/compose-m3",
        sourceRepo = "someorg/unrelated",
        heroPreviewId = null,
        group = designSystems,
      )

    val sections = ServeWeb.homeSections(listOf(impostor))

    assertEquals(listOf("someorg org"), sections.map { it.heading })
  }

  @Test
  fun `the real design systems are grouped by their source repository`() {
    val real =
      listOf("compose-m3", "wear-m3", "remote-m3").map { id ->
        ServeWeb.HomeSystem(
          system = id,
          title = id,
          subtitle = null,
          previewCount = 1,
          trust = "branch:yschimke/compose-ai-tools@design-artifacts/$id",
          sourceRepo = "yschimke/compose-ai-tools",
          heroPreviewId = null,
          group = designSystems,
        )
      }

    val sections = ServeWeb.homeSections(real)

    assertEquals(listOf("Design Systems"), sections.map { it.heading })
    assertEquals(3, sections.single().systems.size)
    assertEquals("design system(s)", sections.single().noun)
  }

  @Test
  fun `a catalog with no provenance is never promoted into a curated section`() {
    // Unattributed bytes: an old catalog with no provenance carries no publisher claim at all, so
    // it lands in Other rather than inheriting a curated section from its config entry.
    val unattributed =
      ServeWeb.HomeSystem(
        system = "wear-m3",
        title = "Wear Compose Material 3",
        subtitle = null,
        previewCount = 1,
        trust = null,
        sourceRepo = null,
        heroPreviewId = null,
        group = designSystems,
      )

    assertEquals(listOf("Other"), ServeWeb.homeSections(listOf(unattributed)).map { it.heading })
  }

  @Test
  fun `an ungrouped catalog is sectioned by its repo owner, and Other reads last`() {
    // Nothing here is hardcoded per catalog: a server publishing catalogs this build has never
    // heard of still gets one section per publisher, with the unattributed bucket pinned last.
    fun system(id: String, repo: String?) =
      ServeWeb.HomeSystem(
        system = id,
        title = id,
        subtitle = null,
        previewCount = 1,
        trust = null,
        sourceRepo = repo,
        heroPreviewId = null,
      )

    val sections =
      ServeWeb.homeSections(
        listOf(
          system("mystery", null),
          system("confetti-wear", "joreilly/Confetti"),
          system("cadence", "yschimke/cadence"),
          system("confetti-mobile", "joreilly/Confetti"),
        )
      )

    assertEquals(listOf("joreilly org", "yschimke org", "Other"), sections.map { it.heading })
    assertEquals(2, sections.first().systems.size)
    assertEquals("catalog(s)", sections.first().noun)
  }

  @Test
  fun `a section heading from config is escaped, never injected into the page`() {
    val system =
      ServeWeb.HomeSystem(
        system = "somecat",
        title = "Some Catalog",
        subtitle = null,
        previewCount = 1,
        trust = null,
        sourceRepo = "someorg/somecat",
        heroPreviewId = null,
        group = ServeWeb.HomeGroup(heading = "<script>x</script>", repos = setOf("someorg/somecat")),
      )

    val html = ServeWeb.homeIndexPage(listOf(system), token = "t", isPublic = true)

    assertFalse(html.contains("<script>x</script>"), "config text is data, not markup")
    assertTrue(html.contains("&lt;script&gt;x&lt;/script&gt;"))
  }
}
