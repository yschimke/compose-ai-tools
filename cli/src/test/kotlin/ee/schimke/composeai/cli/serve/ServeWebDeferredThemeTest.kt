package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Picking a declared theme re-renders the grid through the catalog's daemon. That daemon is shared
 * and renders one card at a time (~1s each), so *which* cards are queued decides whether the page
 * feels responsive: draining all 80+ of a large catalog costs a minute of daemon time, most of it
 * for cards the visitor never scrolls to, while the ones on screen wait behind them.
 *
 * Off-screen cards are therefore held against the viewport and rendered as they come into view.
 */
class ServeWebDeferredThemeTest {

  private fun page() =
    ServeWeb.landingPage(
      "compose-m3",
      listOf(ServePreview(id = "a", label = "A"), ServePreview(id = "b", label = "B")),
      token = "t",
      isPublic = true,
      basePath = "/compose-m3",
      declaredThemes = listOf(ServeTheme(name = "Brand", providerFqn = "com.example.BrandTheme")),
      canRenderThemeFor = { true },
    )

  @Test
  fun `off-screen cards are not queued up front with the visible ones`() {
    val html = page()
    assertFalse(
      html.contains("themeQueue.concat(themeDeferredQueue)"),
      "the deferred cards must not be appended onto the visible batch",
    )
    assertTrue(html.contains("deferTheme(themeDeferredQueue, themeQueueGen)"), "held instead")
  }

  @Test
  fun `deferred cards render as the viewport reaches them`() {
    val html = page()
    assertTrue(html.contains("new IntersectionObserver("), "queued against the viewport")
    assertTrue(
      html.contains("rootMargin: \"400px\""),
      "started a screenful early, so scrolling meets finished pixels not a spinner",
    )
    assertTrue(
      html.contains("runThemeQueue(due, gen, null, 1)"),
      "a trickle behind the scroll — no burst lease, one at a time",
    )
  }

  @Test
  fun `a browser without IntersectionObserver still renders every card`() {
    // Otherwise those cards would sit on the wrong theme forever.
    assertTrue(
      page()
        .contains(
          "if (!window.IntersectionObserver) { runThemeQueue(jobs, gen, null, 1); return; }"
        )
    )
  }

  @Test
  fun `changing theme again abandons the pending observer`() {
    // Each theme choice bumps themeGen; a stale observer must not paint the previous theme's pixels
    // over the new one as the visitor scrolls.
    val html = page()
    assertTrue(html.contains("stopDeferredTheme();"), "torn down on a theme change")
    assertTrue(
      html.contains("if (gen !== themeGen) { stopDeferredTheme(); return; }"),
      "and a late callback from the old generation does nothing",
    )
  }
}
