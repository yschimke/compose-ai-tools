package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The parity dashboard's issue bands, once an issue may occupy **several rows**.
 *
 * An umbrella report names one component per locator block and the index carries a row each, so
 * both bands have to say what they mean: the open band groups by component and counts components,
 * and the closed band is flat, carries no component on a row, and therefore counts issues.
 */
class ServeWebParityIssueBandTest {

  private fun issue(number: Int, component: String, state: String) =
    ParityIssue(
      repository = "yschimke/m3-catalog",
      number = number,
      title = "Elevated previews do not match the kit's Level 1 shadow",
      url = "https://github.com/yschimke/m3-catalog/issues/$number",
      state = state,
      area = "component",
      parity = "known-difference",
      system = "m3-catalog",
      component = component,
      previewIds = listOf("${component.lowercase().replace('/', '-')}__ideal__default__light"),
    )

  private fun page(issues: List<ParityIssue>): String =
    ServeWeb.parityPage(
      moduleLabel = "m3-catalog",
      dashboard =
        ServeParityDashboard.Dashboard(
          coverage =
            ServeParityDashboard.Coverage(
              components = 3,
              mapped = 3,
              unmapped = emptyList(),
              unmappedOverflow = 0,
            ),
          feed = emptyList(),
          components = emptyList(),
          gaps = emptyList(),
        ),
      token = "t",
      parityIssues = issues,
    )

  private val elevated = listOf("Button/Elevated", "Card/Elevated", "ToggleButton/Elevated")

  @Test
  fun `one open umbrella issue is one row per component, counted as components`() {
    val html = page(elevated.map { issue(42, it, "open") })
    // Three sections, one per component, each naming the issue once…
    for (component in elevated) assertTrue(component in html, component)
    assertEquals(3, Regex("#42 ").findAll(html).count(), "the issue appears under each component")
    // …and the heading counts the components, which is what it says.
    assertTrue("Components with open issues (3)" in html, html.substringAfter("cp-status-sec"))
  }

  @Test
  fun `one closed umbrella issue is listed once, not once per component`() {
    // The closed band is flat and its rows carry no component, so three rows would render as three
    // identical links under a count claiming three closed issues.
    val html = page(elevated.map { issue(42, it, "closed") })
    assertEquals(1, Regex("#42 ").findAll(html).count(), "one link, not one per row")
    assertTrue("Closed issues (1)" in html, html)
  }

  @Test
  fun `two closed issues on one component stay two`() {
    // The collapse is by issue identity, not by "anything that repeats": two genuinely different
    // issues that happen to share a component are two rows and two links.
    val html =
      page(listOf(issue(42, "Card/Elevated", "closed"), issue(85, "Card/Elevated", "closed")))
    assertTrue("Closed issues (2)" in html, html)
    assertEquals(1, Regex("#42 ").findAll(html).count())
    assertEquals(1, Regex("#85 ").findAll(html).count())
  }
}
