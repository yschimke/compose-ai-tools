package ee.schimke.composeai.designparity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * Pins the hand-written [PageBackdropManifest] mirror to what the producer actually emits.
 *
 * [ee.schimke.composeai.xr.SpatialSceneTest] can read its counterpart's fixture straight out of
 * this repo, because both ends of that contract live here. This one cannot: the producer is
 * `@design-parity/page-backdrop`, in another repository on its own release cadence. So its fixture
 * is vendored as a **test resource** — test data, not a second copy of the schema, which stays
 * solely upstream.
 *
 * Refresh it by re-copying `fixtures/page-backdrop/pages.json` from
 * https://github.com/yschimke/design-parity. That is a manual step and the honest limitation of
 * this gate: it catches our mirror drifting from the sample we hold, not the sample drifting from
 * upstream. What it does guarantee is that a shape change upstream cannot land here silently — the
 * refresh is what makes it fail.
 */
class PageBackdropParseTest {

  private fun fixture(): String =
    checkNotNull(javaClass.getResourceAsStream("/design-parity/page-backdrop-pages.json")) {
        "vendored design-parity fixture is missing"
      }
      .bufferedReader()
      .readText()

  private fun parse(text: String): PageBackdropManifest =
    PageBackdropJson.decodeFromString(PageBackdropManifest.serializer(), text)

  @Test
  fun `parses the producer's committed fixture`() {
    val manifest = parse(fixture())

    assertEquals(1, manifest.version)
    assertEquals("figma", manifest.source)
    assertTrue(manifest.isSupported)
    assertEquals(1, manifest.pages.size)

    val page = manifest.pages.single()
    assertEquals("now-playing", page.id)
    assertEquals(360.0, page.frame.width)
    assertEquals(720.0, page.frame.height)
    assertEquals(9, page.placements.size)
  }

  @Test
  fun `every placement carries a ref, linked or not`() {
    val page = parse(fixture()).pages.single()
    assertTrue(
      page.placements.all { it.ref.startsWith("figma:") },
      "a bare ref is what makes an unlinked hotspot still clickable",
    )
  }

  @Test
  fun `all four link methods round-trip, with the confidence the producer stated`() {
    val page = parse(fixture()).pages.single()
    assertEquals(
      setOf(
        PlacementLink.CODE_CONNECT,
        PlacementLink.MANIFEST,
        PlacementLink.CONVENTION,
        PlacementLink.UNLINKED,
      ),
      page.placements.map { it.link }.toSet(),
    )

    // Every linked placement states a confidence; a name match is always the weak one.
    for (p in page.linked) assertNotNull(
      p.confidence,
      "${p.name} is linked but states no confidence",
    )
    for (p in page.placements.filter { it.link == PlacementLink.CONVENTION }) {
      assertEquals(PlacementConfidence.LOW, p.confidence)
    }
    for (p in page.placements.filter { it.link == PlacementLink.CODE_CONNECT }) {
      assertEquals(PlacementConfidence.HIGH, p.confidence)
    }
  }

  @Test
  fun `an unlinked placement has no code and no confidence`() {
    val gap = parse(fixture()).pages.single().unlinked.single()
    assertEquals("Album art", gap.name)
    assertNull(gap.code)
    assertNull(gap.confidence)
    assertFalse(gap.isRenderable)
  }

  @Test
  fun `linked placements name a preview, which is what lets us render them ourselves`() {
    val page = parse(fixture()).pages.single()
    val renderable = page.placements.filter { it.isRenderable }
    assertEquals(8, renderable.size)
    assertTrue(renderable.all { it.previewId!!.isNotBlank() })
    // One preview backs three chip instances — a placement is a rectangle, not a unique component.
    assertEquals(3, page.placements.count { it.previewId == "app.ChipsKt.FilterChip_Light" })
  }

  @Test
  fun `unknown fields from a newer producer do not break parsing`() {
    // The contract requires this: the producer ships additive fields without a version bump, so a
    // consumer that refused unknown keys would treat every routine upstream release as an outage.
    val text =
      fixture()
        .replaceFirst(
          "\"version\": 1,",
          "\"version\": 1,\n  \"somethingAddedLater\": {\"nested\": true},",
        )
    val manifest = parse(text)
    assertEquals(9, manifest.pages.single().placements.size)
  }

  @Test
  fun `version support is a range, not an equality check`() {
    assertTrue(supportsPageBackdropVersion(1))
    assertFalse(supportsPageBackdropVersion(0))
    // A future breaking version is refused rather than mis-parsed.
    assertFalse(supportsPageBackdropVersion(PAGE_BACKDROP_VERSION + 1))

    val newer = fixture().replaceFirst("\"version\": 1,", "\"version\": 99,")
    assertFalse(parse(newer).isSupported, "a breaking version must be reported, not silently read")
  }

  @Test
  fun `a strict decoder would reject the fixture, which is why the contract mandates a lenient one`() {
    // Guards the reasoning behind PageBackdropJson: if someone swaps in a default Json, this shows
    // what breaks the moment upstream adds a field.
    val strict = Json { ignoreUnknownKeys = false }
    val text = fixture().replaceFirst("\"version\": 1,", "\"version\": 1,\n  \"futureField\": 1,")
    val threw =
      try {
        strict.decodeFromString(PageBackdropManifest.serializer(), text)
        false
      } catch (_: Exception) {
        true
      }
    assertTrue(threw, "a strict decoder is expected to reject an additive field")
  }
}
