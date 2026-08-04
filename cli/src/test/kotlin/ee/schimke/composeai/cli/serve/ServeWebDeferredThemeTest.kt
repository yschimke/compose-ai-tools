package ee.schimke.composeai.cli.serve

import kotlin.test.Test
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
    // Asserted on what actually runs, not on the absence of a string: the two queues ARE joined
    // once, to stamp the shared page lease onto both, and a negative match can't tell that apart
    // from joining them to render them.
    val html = page()
    assertTrue(
      html.contains("runThemeQueue(themeQueue, themeQueueGen, lease, concurrency);"),
      "the leased batch is the visible queue alone",
    )
    assertTrue(html.contains("deferTheme(themeDeferredQueue, themeQueueGen);"), "the rest is held")
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
  fun `the initial batch is the cards near the viewport, not merely the unfiltered ones`() {
    // `hidden` means "filtered out by search or another tab", NOT "off screen". On a flat catalog
    // with no search nothing is hidden, so partitioning on it alone would put every card in the
    // leased batch and defer nothing — the exact case this change exists to fix.
    val html = page()
    assertTrue(
      html.contains("var themeVisible = !c.hidden && nearViewport(c);"),
      "geometry decides the initial batch, not just the filter state",
    )
    assertTrue(html.contains("c.getBoundingClientRect()"), "measured against the viewport")
    assertTrue(
      html.contains("if (!r.width && !r.height) return false;"),
      "a display:none card (a non-current tab panel) is never near the viewport",
    )
  }

  @Test
  fun `a stale callback retires itself without touching the live observer`() {
    // An observer callback already queued when the visitor picks another theme must not disconnect
    // the NEW observer or drain its worklist — that would strand every not-yet-scrolled card on the
    // previous theme's pixels.
    val html = page()
    assertTrue(
      html.contains("if (gen !== themeGen) { observer.disconnect(); return; }"),
      "the stale callback disconnects its own observer, not whatever is current",
    )
    assertTrue(html.contains("var pending = jobs.slice();"), "each generation owns its worklist")
  }

  @Test
  fun `changing theme again abandons the pending observer`() {
    // Each theme choice bumps themeGen; a stale observer must not paint the previous theme's pixels
    // over the new one as the visitor scrolls.
    assertTrue(page().contains("stopDeferredTheme();"), "torn down on a theme change")
  }
}
