package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * The dashboard's catalog-wide acceptance panel — the *payload* half, which is all the server owns.
 *
 * The verdicts are the browser's, and `<cp-acceptance-audit>`'s own suite covers them. What can go
 * wrong here is narrower and quieter: a catalog that accepts nothing being charged for the engine's
 * bundle, and a payload whose URLs have lost the session credential — which serves a 401 the walk
 * reports as "could not be fetched" on a catalog whose document is perfectly readable.
 */
class ServeWebAcceptanceAuditTest {

  private fun dashboard() =
    ServeParityDashboard.Dashboard(
      coverage =
        ServeParityDashboard.Coverage(
          components = 1,
          mapped = 1,
          unmapped = emptyList(),
          unmappedOverflow = 0,
        ),
      feed = emptyList(),
      components = emptyList(),
      gaps = emptyList(),
    )

  private fun page(
    audit: List<KnownDifferenceCatalogPreview>?,
    issues: List<ParityIssue> = emptyList(),
  ): String =
    ServeWeb.parityPage(
      moduleLabel = "m3-catalog",
      dashboard = dashboard(),
      token = "secret",
      basePath = "/m3",
      parityIssues = issues,
      acceptanceAudit = audit,
    )

  private val preview =
    KnownDifferenceCatalogPreview(
      system = "m3",
      id = "iconbutton-tonal__ideal__default__light",
      component = "IconButton/Tonal",
      variant = "ideal/default/light",
      referenceIds = listOf("iconbutton-tonal-ideal-light"),
    )

  private fun payloadOf(html: String): KnownDifferenceAuditContext {
    val json =
      html
        .substringAfter("id=\"cp-known-difference-audit\">")
        .substringBefore("</script>")
        .replace("\\u003c", "<")
    return Json.decodeFromString(KnownDifferenceAuditContext.serializer(), json)
  }

  @Test
  fun `a catalog that accepts nothing carries neither the panel nor the engine`() {
    val html = page(null)
    assertFalse("cp-acceptance-audit" in html, html)
    // The bundle is the contract's whole reference implementation. A dashboard that loads it to
    // evaluate a document that does not exist is paying for every gate and a PNG reader for
    // nothing.
    assertFalse("known-differences.js" in html, html)
  }

  @Test
  fun `the panel starts hidden, and the walk's inventory is the locator's spelling`() {
    val html = page(listOf(preview))
    assertTrue("<div class=\"cp-acceptance-audit\" id=\"cp-acceptance-audit\"" in html, html)
    assertTrue("hidden></div>" in html, html)
    assertTrue("known-differences.js" in html, html)
    val payload = payloadOf(html)
    assertEquals(listOf(preview), payload.previews)
  }

  @Test
  fun `every URL in the payload carries the session credential`() {
    val payload = payloadOf(page(listOf(preview)))
    assertTrue(
      payload.documentUrl.startsWith("/m3/parity/known-differences.json?"),
      payload.documentUrl,
    )
    assertTrue("token=secret" in payload.documentUrl, payload.documentUrl)
    assertEquals("/m3/parity/known-differences/", payload.artifactBase)
    assertTrue("token=secret" in payload.artifactQuery, payload.artifactQuery)
  }

  @Test
  fun `issue rows are positive evidence only, and deduped`() {
    // An umbrella issue contributes a row per component it names. The lifecycle join asks one
    // question of each issue, so three identical rows are one piece of evidence, not three.
    val rows =
      listOf("Button/Elevated", "Card/Elevated").map { component ->
        ParityIssue(
          repository = "yschimke/m3-catalog",
          number = 40,
          title = "Tonal glyph colour",
          url = "https://github.com/yschimke/m3-catalog/issues/40",
          state = "open",
          component = component,
        )
      }
    val payload = payloadOf(page(listOf(preview), rows))
    assertEquals(1, payload.issues.size, payload.issues.toString())
    assertEquals(40, payload.issues.single().number)
    assertEquals("open", payload.issues.single().state)
  }
}
