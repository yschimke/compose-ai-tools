package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The serve pages' **address-bar state**: what a visitor picked (a section tab, a theme, a filter,
 * a viewer override) is reflected into the page URL, so the page on screen is the page its URL
 * describes — bookmarkable, shareable, and reachable with Back.
 *
 * These are structural assertions on the emitted script; the behaviour itself (click a tab, read
 * `location.search`, go Back, see the previous tab) is driven in a real browser by
 * `preview-harness/serve-lanes.spec.mjs`. Both matter: this one fails fast in `:cli:test` when the
 * wiring is dropped, the browser one proves it actually navigates.
 */
class ServeUrlStateTest {

  private val sectioned =
    listOf(
      ServePreview("theme-light__ideal__default__light", "Theme light", section = "Themes"),
      ServePreview("theme-dark__ideal__default__dark", "Theme dark", section = "Themes"),
      ServePreview("button__ideal__default__light", "Button", section = "Components"),
      ServePreview("button__ideal__default__dark", "Button", section = "Components"),
    )

  private fun landing() =
    ServeWeb.landingPage("meshcore-mobile", sectioned, token = "t", basePath = "/meshcore-mobile")

  @Test
  fun `catalog landing loads the shared url-state helper`() {
    assertTrue(
      landing().contains("""<script src="${ServeWebAssets.href("url-state.js")}"></script>"""),
      "the landing page must load url-state.js before its filter script",
    )
  }

  @Test
  fun `section tab and theme pick each push a history entry`() {
    val html = landing()
    assertTrue(html.contains("pushUrl({ tab: current });"), "a tab click must push ?tab=")
    assertTrue(html.contains("pushUrl({ theme: theme });"), "a theme chip must push ?theme=")
    // The background toggle's own `?bg=` push is asserted against the real element in
    // `cli/serve-web/test/bgToggle.test.ts` ("pushes a history entry so the checkerboard view is
    // shareable"). It used to be a substring match on `bg-toggle.js`'s source here, which could
    // only prove the file *said* it — and says nothing at all once the source is a minified bundle.
  }

  @Test
  fun `typing in the filter replaces rather than pushes`() {
    val html = landing()
    assertTrue(
      html.contains("replaceUrl({ q: input.value.trim() });"),
      "the filter must replace the current entry — one entry per keystroke is unusable",
    )
    assertFalse(
      html.contains("pushUrl({ q:"),
      "the filter must never push, or Back would walk back through every keystroke",
    )
  }

  @Test
  fun `the url outranks the remembered tab and theme`() {
    val html = landing()
    assertTrue(html.contains("""var urlTab = urlParam("tab");"""), html)
    assertTrue(html.contains("""var urlTheme = urlParam("theme");"""), html)
    assertTrue(
      html.contains("if (urlTheme && chipOffered(urlTheme)) theme = urlTheme;"),
      "an explicit ?theme= is applied — including an app-declared theme, which the stored value " +
        "deliberately never replays",
    )
  }

  @Test
  fun `back and forward restore the whole selection without reloading`() {
    val html = landing()
    assertTrue(html.contains("urlState.onPop(function () {"), "the grid must handle popstate")
    // An entry that names no tab/theme falls back to what THIS load resolved to, not to whatever
    // localStorage was last written with — otherwise Back out of a theme lands on that theme.
    assertTrue(html.contains("""urlParam("tab") || initialTab"""), html)
    assertTrue(html.contains("""urlParam("theme") || initialTheme"""), html)
    assertFalse(
      html.contains("location.reload()"),
      "restoring state must re-point the grid in place, never reload the catalog",
    )
  }

  // "Back restores the background this load opened with, not the stored one" now lives in
  // `cli/serve-web/test/bgToggle.test.ts`, where it drives the real element through a real
  // popstate instead of asserting that a source file contains a particular line. A mutation of
  // the fallback back to `localStorage.getItem(…)` fails it, which the substring match here could
  // not have caught once the source became a bundle.

  @Test
  fun `both the grid and the viewer load the shared component bundle`() {
    val tag = """<script src="${ServeWebAssets.href("serve-components.js")}"></script>"""
    assertTrue(landing().contains(tag), "the grid must load serve-components.js")
    val preview = ServePreview("plain.Button", "button")
    assertTrue(
      ServeWeb.viewerPage(preview, token = "t", siblings = listOf(preview)).contains(tag),
      "the viewer carries the same Transparent toggle, so it loads the same bundle",
    )
  }

  @Test
  fun `the pre-paint background script honours the url before the sticky choice`() {
    assertTrue(
      landing().contains("""var b=new URLSearchParams(location.search).get("bg")"""),
      "?bg= must be applied before first paint, like the sticky value it outranks",
    )
  }

  @Test
  fun `a catalog with no previews emits no url wiring`() {
    val empty = ServeWeb.landingPage("empty", emptyList(), token = "t")
    assertFalse(empty.contains("url-state.js"), "nothing to select ⇒ no state to carry")
  }

  @Test
  fun `viewer loads the helper and lets the url outrank the remembered theme`() {
    val preview = ServePreview("plain.Button", "button")
    val html = ServeWeb.viewerPage(preview, token = "t", siblings = listOf(preview))
    assertTrue(
      html.contains("""<script src="${ServeWebAssets.href("url-state.js")}"></script>"""),
      html,
    )
    assertTrue(html.contains("""var provider = params.get("themeProvider");"""), html)
    assertTrue(html.contains("""var uiMode = params.get("uiMode");"""), html)
    assertTrue(
      html.contains("if (!urlOption && option && !option.disabled"),
      "the remembered theme applies only when the URL names none",
    )
  }

  @Test
  fun `viewer syncs its overrides into the page url and restores them on popstate`() {
    val script = ServeWebAssets.load("viewer.js")!!.bytes.decodeToString()
    assertTrue(script.contains("function ownsUrlParam(name)"), "the viewer must scope what it owns")
    assertTrue(
      script.contains("window.cpUrlState.sync(values, ownsUrlParam, !push);"),
      "a control returning to its default has to clear its param, not pin a redundant value",
    )
    assertTrue(
      script.contains("function hydrateFromUrl(popped)"),
      "one restore path serves both the first load and Back/Forward",
    )
    assertTrue(script.contains("window.cpUrlState.onPop("), "the viewer must handle Back/Forward")
    // The interactive lanes render themselves and never reach refreshLinks, so without this the
    // chosen lane never reaches the URL and the pending push lands on some later edit instead.
    assertTrue(
      script.contains("else syncUrl();"),
      "entering Live / Wasm / RC must write ?mode= at the moment of the transition",
    )
    // …and the other half of the round trip: a bookmarked lane has to open in that lane. The
    // param is read BEFORE the first sync, which would otherwise clear a mode no control is
    // holding yet — reading it at apply time restored nothing at all.
    assertTrue(
      script.contains(
        """var initialUrlMode = new URLSearchParams(location.search).get("mode") || "";"""
      ),
      "the viewer must capture a bookmarked ?mode= before the first URL sync",
    )
    assertTrue(script.contains("var wanted = initialUrlMode;"), "…and apply it on first load")
    // …after the first snapshot has LANDED. The stage's <img> has no server-rendered src, and
    // entering an interactive lane cancels the in-flight render, so switching immediately leaves a
    // cold bookmarked load with an empty stage behind a lane that may be slow — or that fails and
    // shows an error over nothing.
    assertTrue(
      script.contains("""img.addEventListener("load", enterBookmarkedMode);"""),
      "the bookmarked lane waits for the fallback frame",
    )
    assertTrue(
      script.contains("setTimeout(enterBookmarkedMode, 8000);"),
      "…but is bounded: a render that errors fires no event and must not strand the bookmark",
    )
    assertTrue(
      script.contains("if (!radio || radio.disabled) return;"),
      "a mode this session doesn't offer is ignored, not entered",
    )
    // token / session are the server's, and the viewer must never rewrite them.
    assertFalse(
      script.contains("values.token") || script.contains("values.session"),
      "the viewer owns only its own override params",
    )
  }

  @Test
  fun `format comparison pushes its format and theme picks`() {
    val script = ServeWebAssets.load("format-compare.js")!!.bytes.decodeToString()
    assertTrue(script.contains("pushUrl({ format: format });"), "format is a discrete pick")
    assertTrue(script.contains("pushUrl({ theme: theme });"), "so is the comparison theme")
    assertTrue(
      script.contains("replaceUrl({ q: search.value.trim() });"),
      "the filter replaces, like every other typed filter",
    )
    assertFalse(
      script.contains("history.replaceState"),
      "URL writes go through url-state.js, which preserves token/session and never navigates",
    )
  }
}
