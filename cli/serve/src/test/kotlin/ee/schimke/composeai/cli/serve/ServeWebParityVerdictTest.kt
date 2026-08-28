package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The parity verdict on the focused comparison — the half of a design-parity run that is prose.
 *
 * The comparison page could always show that two frames differ and, with a producer's redline, what
 * each side is. It could not say what a parity run CONCLUDED: that a label truncates when
 * localized, that padding is 24 where the spec says 12. These pin the two decisions that make that
 * readable rather than merely present — the panel is server-rendered HTML, and a row is only
 * interactive when it has somewhere to point.
 */
class ServeWebParityVerdictTest {

  private val reference =
    DesignReference(
      id = "design-button",
      previewId = "button__light",
      label = "Button",
      raster = DesignReferenceRaster(path = "references/button.png"),
    )

  private val preview = ServePreview(id = "button__light", label = "Button")

  private fun page(sets: List<ParityFindingSet>): String =
    ServeWeb.referenceComparisonPage(
      moduleLabel = "m3-catalog",
      preview = preview,
      reference = reference,
      token = "t",
      sessionId = "m3-catalog",
      parityFindings = sets,
    )

  private fun finding(
    kind: String,
    severity: String = "warn",
    message: String = "m",
    detail: Map<String, String> = emptyMap(),
    anchors: List<ParityAnchor> = emptyList(),
  ) = ParityFinding(kind, severity, message, detail, anchors)

  private fun anchor(side: String = "actual", label: String? = null) =
    ParityAnchor(side, AnnotationBounds(2, 3, 40, 12), label)

  @Test
  fun `a catalog with no verdict renders exactly as it did before`() {
    val html = page(emptyList())
    assertFalse("cp-parity-verdict" in html, html)
    assertFalse("cp-parity-anchors" in html, html)
  }

  @Test
  fun `the three categories a designer asks about each get their own group`() {
    val html =
      page(
        listOf(
          ParityFindingSet(
            findings =
              listOf(
                finding("i18n", message = "\"Label\" risks truncation when localized"),
                finding(
                  "token",
                  severity = "error",
                  message = "spacing.padding: 24 vs spec 12",
                  detail =
                    mapOf("token" to "spacing.padding", "expected" to "12", "actual" to "24"),
                ),
                finding("layout", message = "layout \"Label\": offset (1, -12)"),
              )
          )
        )
      )
    assertTrue("Accessibility &amp; i18n" in html, html)
    assertTrue("Token compliance" in html, html)
    assertTrue(">Layout<" in html, html)
    // The sentences are in the DOCUMENT, not behind a script bundle: a verdict a reader cannot
    // quote into a bug, or find with the browser's own search, is a verdict they cannot cite.
    assertTrue("spacing.padding: 24 vs spec 12" in html, html)
    assertTrue("expected 12" in html && "actual 24" in html, html)
  }

  @Test
  fun `the head states the run's own verdict rather than re-deriving one`() {
    val html =
      page(
        listOf(
          ParityFindingSet(
            status = "pass",
            findings = listOf(finding("token", severity = "error", message = "e")),
          )
        )
      )
    // A run that declared `pass` over a finding it accepted is not overruled by this page.
    assertTrue("cp-parity-status--pass" in html, html)
    assertTrue("1 error" in html, html)
  }

  @Test
  fun `a run that declares no status is read off its own findings`() {
    val warn = page(listOf(ParityFindingSet(findings = listOf(finding("i18n")))))
    assertTrue("cp-parity-status--warn" in warn, warn)
    val fail =
      page(listOf(ParityFindingSet(findings = listOf(finding("token", severity = "error")))))
    assertTrue("cp-parity-status--fail" in fail, fail)
  }

  @Test
  fun `only an anchored finding is offered as a control`() {
    val html =
      page(
        listOf(
          ParityFindingSet(
            findings =
              listOf(
                finding("layout", message = "anchored", anchors = listOf(anchor(label = "Label"))),
                finding("layout", message = "prose only"),
              )
          )
        )
      )
    // One row carries an anchor id…
    assertEquals(1, Regex("data-cp-parity-finding=").findAll(html).count(), html)
    assertTrue("1 region" in html, html)
    // …and the other is plain text.
    assertTrue("prose only" in html, html)
    // Neither is announced as a control by the SERVER. Script may be disabled, blocked or fail to
    // load, and on any of those no highlight can be drawn — a pressed-state button with a tab stop
    // and no handler is worse than prose on the one page whose no-script behaviour is the point.
    // `<cp-reference-compare>` promotes the anchored row once it has actually built the boxes.
    assertFalse("tabindex" in html, html)
    assertFalse("aria-pressed" in html, html)
    assertFalse("role=\"button\"" in html, html)
    // The hint is only claimed because something can respond to it.
    assertTrue("Hover a finding" in html, html)
  }

  @Test
  fun `a verdict with no geometry gets the panel without the invitation`() {
    val html = page(listOf(ParityFindingSet(findings = listOf(finding("a11y")))))
    assertTrue("cp-parity-verdict" in html, html)
    assertFalse("Hover a finding" in html, html)
    assertFalse("cp-parity-anchors" in html, html)
  }

  @Test
  fun `the anchor payload is keyed by the id its row carries`() {
    val html =
      page(
        listOf(
          ParityFindingSet(
            findings =
              listOf(
                finding("token", anchors = listOf(anchor(side = "reference"))),
                finding("layout", anchors = listOf(anchor(), anchor(side = "reference"))),
              )
          )
        )
      )
    assertTrue("data-cp-parity-finding=\"tokens-0\"" in html, html)
    assertTrue("data-cp-parity-finding=\"layout-0\"" in html, html)
    val payload = html.substringAfter("id=\"cp-parity-anchors\">").substringBefore("</script>")
    assertTrue("\"tokens-0\"" in payload && "\"layout-0\"" in payload, payload)
    assertTrue("2 regions" in html, html)
  }

  @Test
  fun `a producer's message cannot inject markup or close the payload script`() {
    val html =
      page(
        listOf(
          ParityFindingSet(
            findings =
              listOf(
                finding(
                  "a11y",
                  message = "<img src=x onerror=alert(1)>",
                  anchors = listOf(anchor(label = "</script><script>alert(1)</script>")),
                )
              )
          )
        )
      )
    assertFalse("<img src=x" in html, html)
    assertTrue("&lt;img src=x" in html, html)
    val payload = html.substringAfter("id=\"cp-parity-anchors\">").substringBefore("</script>")
    assertTrue("\\u003c/script" in payload, payload)
  }
}
