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

  /** The same catalog, sectioned, so the grid carries tabs and an **All** row. */
  private fun tabbedPage() =
    ServeWeb.landingPage(
      "compose-m3",
      listOf(
        ServePreview(id = "a", label = "A", section = "Actions"),
        ServePreview(id = "b", label = "B", section = "Containment"),
      ),
      token = "t",
      isPublic = true,
      basePath = "/compose-m3",
      declaredThemes = listOf(ServeTheme(name = "Brand", providerFqn = "com.example.BrandTheme")),
      canRenderThemeFor = { true },
    )

  @Test
  fun `on the All tab the grid is partitioned by geometry alone`() {
    // The bug this pins: on **All** every section is showing, so no card's section equals the
    // current tab and the section test rejected the entire grid. The visible batch came out empty,
    // its burst claim was handed straight back, and every deferred card then presented a token the
    // server had already reaped — a themed All tab rendered nothing at all.
    val html = tabbedPage()
    assertTrue(
      html.contains(
        "themeVisible = current === \"all\" || " +
          "themeSection.getAttribute(\"data-section\") === current"
      ),
      "All shows every section, so only the viewport decides",
    )
  }

  @Test
  fun `a deferred batch renders under a claim of its own, not the visible batch's`() {
    // The visible batch releases its claim the moment its last card settles — immediately, when no
    // card was on screen. Deferred cards stamped with THAT token were then refused for the life of
    // the page, which is what left a themed grid spinning on the previous theme's pixels.
    val html = page()
    val visibleStamp = html.indexOf("if (lease) stampThemeLease(themeQueue, lease);")
    assertTrue(visibleStamp >= 0, "the page claim is stamped onto the on-screen batch alone")
    assertFalse(
      html.contains("themeQueue.concat(themeDeferredQueue)"),
      "deferred jobs no longer inherit the visible batch's token",
    )
    assertTrue(
      html.contains("function runDeferredThemeBatch(jobs, gen)") &&
        html.contains("acquireThemeLease(gen, function (lease) {") &&
        html.contains("if (lease) stampThemeLease(jobs, lease);"),
      "each deferred batch asks for its own claim when the viewport reaches it",
    )
    assertTrue(
      html.contains("job.baseSrc = job.baseSrc.replace(/&_themeLease=[^&]*/, \"\")"),
      "re-stamping replaces the previous token rather than appending a second one",
    )
  }

  @Test
  fun `every claim the page holds is handed back, not just the last one acquired`() {
    // Several claims are live at once now (the visible batch, plus a deferred batch per scroll), so
    // abandoning a generation or leaving the page has to release all of them. A single slot left
    // the rest tying up the catalog's burst width until their TTL expired.
    val html = page()
    assertTrue(html.contains("var themeLeases = [];"), "claims are tracked as a set")
    assertTrue(
      html.contains("function releaseAllThemeLeases(beacon)") &&
        html.contains("releaseAllThemeLeases(false);") &&
        html.contains(
          "window.addEventListener(\"pagehide\", function () { releaseAllThemeLeases(true); });"
        ),
      "a theme change and a page exit both hand back every claim",
    )
  }

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
      html.contains("runDeferredThemeBatch(due, gen)"),
      "a trickle behind the scroll — its own claim, one card at a time",
    )
    assertTrue(
      html.contains("runThemeQueue(jobs, gen, lease, 1);"),
      "one worker whatever width the claim was granted",
    )
  }

  @Test
  fun `visible queued cards are busy while deferred cards stay settled`() {
    val html = page()
    val worker = html.indexOf("function runThemeWorker(queue, gen, batch)")
    val startsBusy = html.indexOf("job.card.classList.add(\"cp-reloading\")")
    val visibleBranch = html.indexOf("if (themeVisible) {")
    val queuesVisible = html.indexOf("themeQueue.push(job)", visibleBranch)
    val deferredBranch = html.indexOf("themeDeferredQueue.push(job)", queuesVisible)

    assertTrue(worker >= 0 && startsBusy > worker, "the worker owns the loading state")
    assertTrue(
      visibleBranch >= 0 &&
        html.contains("c.classList.add(\"cp-reloading\")") &&
        queuesVisible > visibleBranch &&
        deferredBranch > queuesVisible,
      "every visible queued card is busy, while deferred cards take the settled branch",
    )
  }

  @Test
  fun `a terminal render failure leaves an explicit error over the old theme`() {
    val html = page()
    assertTrue(html.contains("if (!ok) showThemeError(job.card, terminal);"), "failure is surfaced")
    assertTrue(
      html.contains("? \"This preview can't render live\"") &&
        html.contains(": \"Theme preview unavailable\";"),
      "the old pixels cannot look like a successful theme update, and a preview the server has " +
        "permanently given up on says so rather than implying another attempt might work",
    )
    // A 409 is terminal: retrying it only occupies a worker the rest of the grid needs.
    assertTrue(html.contains("if (response.status === 409)"), "a terminal status is recognised")
    assertTrue(
      html.contains("if (!ok && !terminal && job.retries < themeRenderRetries)"),
      "a terminal failure skips the retry ladder",
    )
    assertTrue(html.contains("error.setAttribute(\"role\", \"status\")"), "announced accessibly")
    val clearsError = html.indexOf("clearThemeError(job.card);")
    val showsSpinner = html.indexOf("job.card.classList.add(\"cp-reloading\")")
    assertTrue(
      clearsError >= 0 && showsSpinner > clearsError,
      "a retry or later theme request clears the prior error before showing its spinner",
    )
  }

  @Test
  fun `a browser without IntersectionObserver still renders every card`() {
    // Otherwise those cards would sit on the wrong theme forever.
    assertTrue(
      page()
        .contains("if (!window.IntersectionObserver) { runDeferredThemeBatch(jobs, gen); return; }")
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

  @Test
  fun `a themed card keeps its old pixels until the new render has arrived`() {
    // Assigning `src` on the live <img> drops what it is showing the moment the request starts, so
    // the card went blank and showed a broken-image glyph under the spinner for the whole ~1s
    // daemon round trip. The bytes are fetched first and only then handed to the element.
    val html = page()
    assertTrue(html.contains("fetch(job.src, { credentials: \"same-origin\" })"), "bytes first")
    assertTrue(html.contains("URL.createObjectURL(blob)"), "swapped from the fetched blob")
    assertFalse(html.contains("img.src = job.src"), "never assigned straight to the visible image")
  }

  @Test
  fun `each theme switch releases the blob the card was holding`() {
    // One object URL per card per switch would otherwise be stranded for the life of the page.
    val html = page()
    assertTrue(html.contains("img.getAttribute(\"data-cp-blob\")"))
    assertTrue(html.contains("URL.revokeObjectURL(previous)"))
  }

  @Test
  fun `leaving a declared theme releases the blob too, not just switching between them`() {
    // Revoking only on the next successful themed fetch left every card's full-resolution PNG
    // retained when the visitor went back to Light / Dark / Default — on an 80+ card catalog that
    // is a lot of blobs held until the page unloads.
    val html = page()
    assertTrue(html.contains("function setCardSrc(img, url)"), "one setter owns the release")
    assertTrue(html.contains("if (withSrc) setCardSrc(img, src);"), "swap cards restore through it")
    assertTrue(html.contains("if (img && base) setCardSrc(img, base);"), "so do non-swap cards")
  }
}
