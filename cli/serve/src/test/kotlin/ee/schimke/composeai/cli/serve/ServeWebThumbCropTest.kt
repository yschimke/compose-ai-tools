package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.imagecrop.ContentCrop
import ee.schimke.composeai.imagecrop.computeGutterCrop
import ee.schimke.composeai.imagecrop.computeThumbCrop
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
    ContentCrop(
      boxW = 120,
      boxH = 48,
      imgW = 454,
      imgH = 454,
      left = -167,
      top = -203,
      natBoxW = 120,
      natCapAxis = 120,
    )

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
      html.contains(
        "class=\"cp-crop\" style=\"--cp-crop-w-per-cap:1;--cp-crop-w-per-h:2.5;--cp-crop-max-w:120px;aspect-ratio:120/48\""
      ),
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
      html.contains(
        "class=\"cp-crop\" style=\"--cp-crop-w-per-cap:1;--cp-crop-w-per-h:2.5;--cp-crop-max-w:120px;aspect-ratio:120/48\""
      ),
      "hero framed to its box",
    )
  }

  @Test
  fun `a gutter window is marked so its overflow is not hidden`() {
    // The pixels outside a capture gutter's box are the component's own shadow — the reason the
    // gutter was captured. The window lines the box up with its neighbours; it must not crop.
    val html =
      ServeWeb.landingPage(
        "compose-m3",
        previews,
        token = "t",
        basePath = "/compose-m3",
        thumbCrop = { crop.copy(clip = false) },
      )
    assertTrue(html.contains("class=\"cp-crop cp-crop--bleed\""), "bleeding window marked")
    val css = ServeWebAssets.load("serve.css")!!.bytes.decodeToString()
    assertTrue(css.contains(".cp-crop--bleed { overflow: visible; }"), "and allowed to overflow")
    // …through the card too, which otherwise clips its own content to its rounded edge.
    assertTrue(
      css.contains(".cp-card:has(.cp-crop--bleed) { overflow: visible; }"),
      "the card lets a bleeding window's shadow reach the grid gap",
    )
  }

  @Test
  fun `a capped window publishes its width relative to the display cap, not as frozen px`() {
    // A 300x100 component on a 600x600 render: the largest edge is capped 240/300, so the window
    // draws 240x80. Freezing that 240px in the HTML is what made a cropped card 20% larger than
    // its plain neighbour under the narrow-viewport `max-height: 200px` (#4544) — the plain image
    // shrank and the window did not. Publishing the RELATIONSHIP instead (box width per 1px of
    // cap, plus the 1x ceiling) lets the stylesheet re-derive it for whatever cap is in force.
    val capped = computeThumbCrop(svg("0 0 300 100", "translate(-150, -250)"), 600, 600)!!
    val html =
      ServeWeb.landingPage(
        "wear-m3",
        previews,
        token = "t",
        basePath = "/wear-m3",
        thumbCrop = { capped },
      )
    // 300 wide per 300 of cap = 1, ceiling 300px → min(300px, 1 * 240px) = 240px at the desktop
    // cap and min(300px, 1 * 200px) = 200px at the narrow one: the same ratio the plain <img>'s
    // 240 -> 200 `max-height` drop applies.
    //
    // The two ratios DIFFER here — 1 against the largest edge, 3 against the height — because this
    // is a content crop, whose cap axis is `max(box.w, box.h)`. That gap is why the front door's
    // fixed-height hero well sizes on `--cp-crop-w-per-h`: reading the cap ratio there would have
    // shrunk this landscape window to 196x65 in a well it already fits.
    assertTrue(
      html.contains(
        "class=\"cp-crop\" style=\"--cp-crop-w-per-cap:1;--cp-crop-w-per-h:3;--cp-crop-max-w:300px;aspect-ratio:240/80\""
      ),
      "window width published as a ratio of the cap",
    )
  }

  @Test
  fun `a gutter window is capped on its height, so it matches a plain image's max-height`() {
    // A 249x126 button in a 271x150 render with an 11/11/11/13 gutter. The cap acts on HEIGHT here
    // (a plain <img> beside it is bounded by `max-height`), so the ratio published is width-per-
    // cap = 249/126 and the window's height lands on the cap exactly.
    val gutter = computeGutterCrop(11, 11, 11, 13, 271, 150)!!
    val html =
      ServeWeb.landingPage(
        "wear-m3",
        previews,
        token = "t",
        basePath = "/wear-m3",
        thumbCrop = { gutter },
      )
    // Both ratios, and here they are the SAME number — which is the invariant worth pinning: for a
    // gutter crop the cap axis IS the height, so `--cp-crop-w-per-h` adds nothing. It is the
    // content-crop case above (`--cp-crop-w-per-cap:1` against `--cp-crop-w-per-h:3`) where they
    // part company, and a well that sizes on its own height has to read the second one.
    assertTrue(
      html.contains("--cp-crop-w-per-cap:1.9762;--cp-crop-w-per-h:1.9762;--cp-crop-max-w:249px"),
      "gutter window width published per cap PIXEL of height (249/126), the cap axis being height",
    )
  }

  @Test
  fun `a crop with no native size keeps the fixed-px window`() {
    val handmade =
      ContentCrop(boxW = 120, boxH = 48, imgW = 454, imgH = 454, left = -167, top = -203)
    val html =
      ServeWeb.landingPage(
        "wear-m3",
        previews,
        token = "t",
        basePath = "/wear-m3",
        thumbCrop = { handmade },
      )
    assertTrue(
      html.contains("class=\"cp-crop\" style=\"width:120px;aspect-ratio:120/48\""),
      "no native size to re-derive from, so the window stays at its computed px",
    )
  }

  /** A minimal figma-svg carrying a content [viewBox] and placing [translate]. */
  private fun svg(viewBox: String, translate: String) =
    """<svg viewBox="$viewBox"><g transform="$translate"></g></svg>"""

  @Test
  fun `the crop CSS is present so the clip window actually clips`() {
    val css = ServeWebAssets.load("serve.css")!!.bytes.decodeToString()
    assertTrue(
      css.contains(".cp-crop { position: relative; overflow: hidden;"),
      "clip style shipped",
    )
    assertTrue(
      css.contains(".cp-imgwrap .cp-crop img { position: absolute; max-width: none;"),
      "img escapes the fit-to-box cap",
    )
    // And deliberately NO `max-height` on the window: it carries an inline `aspect-ratio`, so
    // constraining its height in CSS squashes the box rather than scaling it, and the render
    // inside would sit at the wrong scale. The display cap belongs to the crop geometry, where
    // `computeThumbCrop` and `computeGutterCrop` both apply it (m3-catalog#179).
    assertFalse(
      css.contains(
        ".cp-crop { position: relative; overflow: hidden; display: block; max-width: 100%; max-height"
      ),
      "no CSS height cap on an aspect-ratio window",
    )
    // The cap the window resolves its published ratio against, and the narrow-viewport drop that
    // keeps a cropped card the same size as the plain card beside it (#4544).
    assertTrue(
      css.contains(
        "width: min(var(--cp-crop-max-w, 100%), calc(var(--cp-crop-w-per-cap, 9999) * var(--cp-thumb-cap, 240px)));"
      ),
      "window width derived from the cap, defaulting to the desktop 240px",
    )
    assertTrue(css.contains(".cp-crop { --cp-thumb-cap: 200px; }"), "narrow-viewport cap")
    // ...and it must drop in lockstep with the plain image's cap, or the mismatch just moves.
    assertTrue(css.contains(".cp-imgwrap img { max-height: 200px; }"), "plain image's narrow cap")
    // A front-door system card's hero is a different well: `.cp-syslist .cp-imgwrap` is a fixed
    // 220px row at every width, and the plain hero in it is exempted from the grid's image cap
    // (`max-height: none`) for exactly that reason. A CROPPED hero takes its cap through the
    // variable instead, so the exemption has to be spelled the other way or it draws to the grid's
    // number in a row that is not the grid's — 240px at desktop, overflowing, and 200px in the
    // narrow block, visibly short beside the prebaked hero beside it. Pinned here, next to the two
    // caps it has to agree with, because these three drifting apart is the whole bug.
    //
    // 196px rather than 220px: the row's CONTENT height, since `.cp-imgwrap` carries 12px of
    // padding under the border-box `*` rule. The row height itself would overflow the well by 12px
    // each way and, in the narrow block, would push the window UP from 200px.
    assertTrue(
      css.contains(
        "width: min(var(--cp-crop-max-w, 100%), calc(var(--cp-crop-w-per-h, var(--cp-crop-w-per-cap, 9999)) * 196px)); }"
      ),
      "a system-card window sizes against the box's own height, capped at the well's 196px",
    )
    // NOT `--bleed`, and not `--cp-thumb-cap`. `natCapAxis` is the height for a gutter crop and
    // the largest edge for a content one, and `clip` does not separate them either —
    // `ServeBundleHost` clears it on a vector crop over a guttered render. So a landscape 300x100
    // window carries `--bleed` with a largest-edge ratio, and sizing it off the well's height
    // shrank it 240x80 -> 196x65 for nothing.
    assertFalse(
      css.contains(".cp-syslist .cp-crop--bleed { --cp-thumb-cap:"),
      "--bleed is not a proxy for a height-capped crop",
    )
    // The padding that makes it 196 rather than 220 — if this moves, so must the number above.
    assertTrue(
      css.contains("background: var(--cp-surface); padding: 12px; }"),
      "the image well's padding, which the hero cap is derived from",
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
    assertTrue(html.contains("<h1 class=\"cp-head\">someorg repositories</h1>"))
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

    assertEquals(listOf("someorg repositories"), sections.map { it.heading })
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

    assertEquals(
      listOf("joreilly repositories", "yschimke repositories", "Other"),
      sections.map { it.heading },
    )
    assertEquals(2, sections.first().systems.size)
    assertEquals("catalog(s)", sections.first().noun)
  }

  @Test
  fun `a group priority lifts its section above the ones the catalog list reaches first`() {
    // The ordering the front page had before #4601: sections came out in first-appearance order,
    // so a design system registered after the samples (an admin publish is appended) read last.
    fun system(id: String, repo: String, group: ServeWeb.HomeGroup?) =
      ServeWeb.HomeSystem(
        system = id,
        title = id,
        subtitle = null,
        previewCount = 1,
        trust = null,
        sourceRepo = repo,
        heroPreviewId = null,
        group = group,
      )

    val systems =
      listOf(
        system("jetnews", "yschimke/compose-samples", androidSamples),
        system("confetti-wear", "joreilly/Confetti", null),
        system("m3-catalog", "yschimke/compose-ai-tools", designSystems),
      )

    assertEquals(
      listOf("android/compose-samples", "joreilly repositories", "Design Systems"),
      ServeWeb.homeSections(systems).map { it.heading },
      "with no priority declared, order is still where the catalog list first reaches a section",
    )

    val lifted = systems.map {
      if (it.group == designSystems) it.copy(group = designSystems.copy(priority = 100)) else it
    }

    assertEquals(
      listOf("Design Systems", "android/compose-samples", "joreilly repositories"),
      ServeWeb.homeSections(lifted).map { it.heading },
      "a declared priority orders the sections; the rest keep first-appearance order",
    )
  }

  @Test
  fun `a section that two claims spell the same way takes the highest priority declared`() {
    // Headings are operator text, not unique keys: two groups (or a group and the owner fallback)
    // can spell one. Recording only the first claim's priority would strand a lifted group under a
    // heading-mate that registered earlier with none.
    fun system(id: String, repo: String, group: ServeWeb.HomeGroup?) =
      ServeWeb.HomeSystem(
        system = id,
        title = id,
        subtitle = null,
        previewCount = 1,
        trust = null,
        sourceRepo = repo,
        heroPreviewId = null,
        group = group,
      )

    val unlifted = ServeWeb.HomeGroup(heading = "Design Systems", repos = setOf("someorg/legacy"))
    val lifted =
      ServeWeb.HomeGroup(
        heading = "Design Systems",
        repos = setOf("yschimke/m3-catalog"),
        priority = 100,
      )

    val sections =
      ServeWeb.homeSections(
        listOf(
          system("legacy", "someorg/legacy", unlifted),
          system("jetnews", "yschimke/compose-samples", androidSamples),
          system("m3-catalog", "yschimke/m3-catalog", lifted),
        )
      )

    assertEquals(listOf("Design Systems", "android/compose-samples"), sections.map { it.heading })
    assertEquals(listOf("legacy", "m3-catalog"), sections.first().systems.map { it.system })
  }

  @Test
  fun `Other stays pinned last however it is claimed, and cards keep list order`() {
    fun system(id: String, repo: String?, group: ServeWeb.HomeGroup?) =
      ServeWeb.HomeSystem(
        system = id,
        title = id,
        subtitle = null,
        previewCount = 1,
        trust = null,
        sourceRepo = repo,
        heroPreviewId = null,
        group = group,
      )

    val sections =
      ServeWeb.homeSections(
        listOf(
          // No provenance: falls into Other, and a priority on the claim it can't satisfy must
          // not promote it out of the unattributed bucket.
          system("mystery", null, designSystems.copy(priority = 500)),
          system(
            "wear-m3-catalog",
            "yschimke/compose-ai-tools",
            designSystems.copy(priority = 100),
          ),
          system("m3-catalog", "yschimke/compose-ai-tools", designSystems.copy(priority = 100)),
        )
      )

    assertEquals(listOf("Design Systems", "Other"), sections.map { it.heading })
    assertEquals(
      listOf("wear-m3-catalog", "m3-catalog"),
      sections.first().systems.map { it.system },
      "priority orders sections only — inside one, the configured catalog order still decides",
    )
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
        group =
          ServeWeb.HomeGroup(heading = "<script>x</script>", repos = setOf("someorg/somecat")),
      )

    val html = ServeWeb.homeIndexPage(listOf(system), token = "t", isPublic = true)

    assertFalse(html.contains("<script>x</script>"), "config text is data, not markup")
    assertTrue(html.contains("&lt;script&gt;x&lt;/script&gt;"))
  }
}
