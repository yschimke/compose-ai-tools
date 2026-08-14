package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the two **entry points** into the playground from the browsing surfaces: a per-preview
 * handoff in the viewer's provenance row, and a per-catalog one on the landing's summary line.
 *
 * Both are deliberately subtle — they join a run of links that already exists rather than becoming
 * controls of their own — and both are absent rather than dead when the host cannot honour them,
 * which is the property these tests hold.
 */
class ServeWebPlaygroundLinksTest {

  private val preview =
    ServePreview(
      id = "buttons.FilledButton",
      label = "Filled button",
      sourceFile = "src/main/kotlin/buttons/FilledButton.kt",
    )

  private fun viewer(playgroundHref: String?) =
    ServeWeb.viewerPage(
      preview,
      token = "t",
      basePath = "/compose-m3",
      siblings = listOf(preview),
      sourceHref = "https://github.com/o/r/blob/main/FilledButton.kt",
      playgroundHref = playgroundHref,
    )

  private fun landing(playgroundHref: String?) =
    ServeWeb.landingPage(
      moduleLabel = "compose-m3",
      previews = listOf(preview),
      token = "t",
      basePath = "/compose-m3",
      playgroundHref = playgroundHref,
    )

  @Test
  fun `the viewer offers a playground handoff beside the source link`() {
    val html = viewer("/playground?from=compose-m3/buttons.FilledButton&token=t")
    assertTrue(html.contains("cp-preview-links"), "it rides the existing provenance row")
    assertTrue(html.contains("/playground?from=compose-m3/buttons.FilledButton&amp;token=t"))
    assertTrue(html.contains("playground</a>"))
    // The row still carries what it carried before — this is an addition, not a replacement.
    assertTrue(html.contains("source</a>"))
  }

  @Test
  fun `the viewer omits the handoff entirely when the host cannot honour it`() {
    // No playground lane, or a preview whose catalog never recorded a source path. A dead entry in
    // a provenance row is worse than no entry: it reads as an offer the server then refuses.
    val html = viewer(null)
    assertFalse(html.contains("playground"), "no lane, no link")
    assertTrue(html.contains("source</a>"), "…and the rest of the row is untouched")
  }

  @Test
  fun `the catalog landing offers a try-in-playground action above the catalog`() {
    val html = landing("/playground?catalog=compose-m3&token=t")
    assertTrue(html.contains("try in playground</a>"))
    assertTrue(html.contains("/playground?catalog=compose-m3&amp;token=t"))
    assertTrue(
      html.indexOf("try in playground") < html.indexOf("id=\"cp-grid\""),
      "the playground handoff remains in the primary action row",
    )
    assertTrue(
      html.indexOf("download all (.zip)") > html.indexOf("id=\"cp-grid\""),
      "the bundle download follows the catalog content",
    )
  }

  @Test
  fun `the catalog landing reads exactly as before without a playground lane`() {
    val html = landing(null)
    assertFalse(html.contains("try in playground"))
    assertTrue(html.contains("download all (.zip)"))
  }

  @Test
  fun `both links are attribute-escaped`() {
    assertFalse(viewer("/playground?from=a\"><script>x</script>").contains("<script>x</script>"))
    assertFalse(
      landing("/playground?catalog=a\"><script>y</script>").contains("<script>y</script>")
    )
  }
}
