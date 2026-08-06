package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the playground editor's **runtime catalog selector** in [ServeWeb.playgroundPage].
 *
 * The selector is what turns "compile against the one bundle the operator pinned at startup" into
 * "compile against any catalog this host serves", and the load-bearing detail is that a catalog's
 * bundle backend *decides its modes* — so the page must ship each entry's mode list, not one global
 * one. These tests hold the shape the editor script reads; the full rendered page is pinned as a
 * golden fixture by `ServeWebFixtureTest`.
 */
class ServeWebPlaygroundCatalogTest {

  private fun page(catalogs: List<PlaygroundCatalogInfo>, catalogSelectorEnabled: Boolean = false) =
    ServeWeb.playgroundPage(
      token = "t",
      isPublic = false,
      catalogs = catalogs,
      catalogSelectorEnabled = catalogSelectorEnabled,
    )

  private val pinnedDefault =
    PlaygroundCatalogInfo(
      id = "",
      label = "Server default",
      modes = listOf(PlaygroundMode.CMP),
      resolved = true,
    )

  private val m3 =
    PlaygroundCatalogInfo(
      id = "compose-m3",
      label = "compose-m3 (desktop)",
      backend = "desktop",
      modes = listOf(PlaygroundMode.CMP),
      resolved = true,
    )

  private val wear =
    PlaygroundCatalogInfo(
      id = "compose-wear",
      label = "compose-wear (android)",
      backend = "android",
      modes = listOf(PlaygroundMode.ANDROID, PlaygroundMode.REMOTE_COMPOSE),
      resolved = false,
    )

  @Test
  fun `a host that pins its bundles renders the bar it always did`() {
    // One choice, and it's the pinned default: a "Catalog" control here would be a dropdown that
    // decides nothing, so it is omitted entirely rather than rendered disabled.
    val html = page(listOf(pinnedDefault))
    assertFalse(html.contains("""id="pg-catalog""""), "no catalog control for a pinned-only host")
    assertTrue(html.contains("""id="pg-mode""""), "the mode selector is still there")
    assertTrue(html.contains("""<option value="compose-cmp" selected>"""))
  }

  @Test
  fun `served catalogs render a selector with the default preselected`() {
    val html = page(listOf(pinnedDefault, m3, wear))
    assertTrue(html.contains("""id="pg-catalog""""))
    assertTrue(html.contains("""<option value="" selected>Server default</option>"""))
    assertTrue(html.contains("""<option value="compose-m3">compose-m3 (desktop)</option>"""))
    assertTrue(html.contains("""<option value="compose-wear">compose-wear (android)</option>"""))
  }

  @Test
  fun `a host with nothing pinned starts on its first served catalog`() {
    val html = page(listOf(m3, wear))
    assertTrue(html.contains("""<option value="compose-m3" selected>"""))
    // The Mode control is seeded from the SELECTED catalog, not from every mode the host knows —
    // a desktop catalog must not open on an Android mode it would immediately refuse.
    assertTrue(html.contains("""<option value="compose-cmp" selected>Compose (Desktop)</option>"""))
    assertFalse(
      html.contains("""<option value="compose-android">Compose (Android)</option>"""),
      "the desktop catalog's mode list must not leak the android catalog's modes",
    )
  }

  @Test
  fun `an android catalog seeds the mode control with its own modes`() {
    val html = page(listOf(wear))
    assertTrue(
      html.contains("""<option value="compose-android" selected>Compose (Android)</option>""")
    )
    assertTrue(html.contains("""<option value="remote-compose">Remote Compose</option>"""))
    assertFalse(html.contains("""value="compose-cmp""""), "an android catalog offers no CMP mode")
  }

  @Test
  fun `a startup-time page with no catalogs yet says so instead of rendering an empty control`() {
    // `--playground` with no pin: catalogs are fetched in the background after the server is up, so
    // this is a normal transient state, not a misconfiguration.
    val html = page(emptyList())
    assertTrue(html.contains("No catalogs available yet"))
    assertTrue(html.contains("""id="pg-catalog""""))
    // …and it names BOTH causes, because they look identical from the page: still loading
    // (transient) versus no catalog that verifies trusted and carries a live bundle (a config
    // problem that will never clear on its own).
    assertTrue(html.contains("""id="pg-empty""""))
    assertTrue(html.contains("fetched in the background"))
    assertTrue(html.contains("<strong>trusted</strong>"))
  }

  @Test
  fun `a selector host renders the control even while only the pin has loaded`() {
    // `--playground` plus a pinned LOCAL bundle: during the startup window the pin is the only
    // entry
    // this host can offer, because no served catalog has loaded yet. Deciding on the count alone
    // would omit the control from this page — and the script can only repopulate a control that
    // exists, so the visitor would stay pinned until they reloaded by hand.
    val html = page(listOf(pinnedDefault), catalogSelectorEnabled = true)
    assertTrue(
      html.contains("""id="pg-catalog""""),
      "the control tracks the host's configuration, not how many entries happen to have loaded",
    )
    // …and the same list WITHOUT the selector configured still renders the old bar.
    assertFalse(page(listOf(pinnedDefault)).contains("""id="pg-catalog""""))
  }

  @Test
  fun `the catalog list is re-asked until it stops coming back empty`() {
    // One fetch on load is not enough on a host with nothing pinned: the editor routinely loads
    // before the initial catalog loader has published anything, so the single reply is empty too
    // and nothing would ever ask again — a permanently disabled Run on a host that came up fine.
    val html = page(emptyList(), catalogSelectorEnabled = true)
    assertTrue(html.contains("setTimeout(refreshCatalogs"), "an empty answer schedules another ask")
    assertTrue(html.contains("MAX_EMPTY_POLLS"), "…and the polling is bounded, not forever")
    assertTrue(
      html.contains("""catalog.addEventListener("focus", refreshCatalogs)"""),
      "opening the dropdown re-asks, for catalogs that landed after the poll gave up",
    )
  }

  @Test
  fun `the empty-state note is absent once a catalog can back a compile`() {
    assertFalse(page(listOf(m3)).contains("""id="pg-empty""""))
    assertFalse(page(listOf(pinnedDefault)).contains("""id="pg-empty""""))
  }

  @Test
  fun `every offered catalog ships its own mode list to the editor script`() {
    val html = page(listOf(pinnedDefault, m3, wear))
    // The script reads this to repopulate Mode when the catalog changes; without the per-entry
    // modes it would have to round-trip on every selection.
    assertTrue(html.contains("""compose-wear"""))
    assertTrue(
      html.contains("""JSON.parse(\"{\\\"catalogs\\\"""") ||
        html.contains("""JSON.parse("{\"catalogs\""""),
      "the catalog list is embedded as a parseable JS string literal",
    )
    assertTrue(html.contains("var catalogs ="), "the script binds the embedded list")
  }

  @Test
  fun `the run request carries the selected catalog`() {
    val html = page(listOf(pinnedDefault, m3))
    assertTrue(
      html.contains("""catalog: catalog ? catalog.value : ""}""") ||
        html.contains("catalog: catalog ? catalog.value :"),
      "the POST body sends the selection; an empty value means the host's default",
    )
  }

  @Test
  fun `the catalog labels and ids are HTML-escaped`() {
    val html =
      page(
        listOf(
          PlaygroundCatalogInfo(
            id = "a\"><script>x</script>",
            label = "b<script>y</script>",
            backend = "desktop",
            modes = listOf(PlaygroundMode.CMP),
          )
        )
      )
    assertFalse(html.contains("<script>x</script>"))
    assertFalse(html.contains("<script>y</script>"))
    assertEquals(
      1,
      Regex("""<option value="a&quot;&gt;""").findAll(html).count(),
      "the id is escaped in the option value",
    )
  }
}
