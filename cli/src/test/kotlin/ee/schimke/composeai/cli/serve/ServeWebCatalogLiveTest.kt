package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The grid's **long-press live lane**: holding a catalog card starts a live daemon session inside
 * the card, instead of navigating to the viewer.
 *
 * What is pinned here is the contract between the two halves — the server emits each card's
 * streamable preview ids as an object literal in the grid's document order, and `catalog-live.js`
 * reads that (never the DOM) when it builds a socket URL. Plus the fail-closed half: a session with
 * nothing to stream emits no configuration and no script at all.
 */
class ServeWebCatalogLiveTest {

  private val previews =
    listOf(
      ServePreview(id = "filled-button__ideal__default__light", label = "Filled", theme = "light"),
      ServePreview(id = "filled-button__ideal__default__dark", label = "Filled", theme = "dark"),
      ServePreview(id = "chip__ideal__default__compact", label = "Chip"),
    )

  private fun page(
    canStreamLiveFor: (String) -> Boolean = { false },
    liveSignInHref: String? = null,
    isPublic: Boolean = true,
    token: String = "t",
  ) =
    ServeWeb.landingPage(
      "compose-m3",
      previews,
      token = token,
      isPublic = isPublic,
      basePath = "/compose-m3",
      canStreamLiveFor = canStreamLiveFor,
      liveSignInHref = liveSignInHref,
    )

  @Test
  fun `a session with no live lane emits no long-press machinery`() {
    val html = page()
    assertFalse(html.contains("cpCatalogLive"), "no configuration")
    assertFalse(html.contains("catalog-live.js"), "and no script to read it")
    assertFalse(html.contains("hold a card for a live session"), "and nothing offering the gesture")
  }

  @Test
  fun `a streamable catalog carries its cards' ids in document order`() {
    val html = page(canStreamLiveFor = { true })
    // Card one is a light/dark pair folded into ONE card, so it carries both ids: the gesture
    // follows whichever render the visitor has the grid swapped to.
    assertTrue(
      html.contains(
        "cards:[{l:\"filled-button__ideal__default__light\",d:\"filled-button__ideal__default__dark\"}," +
          "{l:\"chip__ideal__default__compact\",d:\"chip__ideal__default__compact\"}]"
      ),
      html,
    )
    assertTrue(
      html.contains("<script src=\"${ServeWebAssets.href("catalog-live.js")}\"></script>"),
      "the lane's script is loaded",
    )
    assertTrue(
      html.contains("hold a card for a live session"),
      "and the header says the lane is there",
    )
  }

  @Test
  fun `a card the session cannot stream keeps an empty entry`() {
    // An Android-only variant with no daemon twin: the socket would only ever replay its baked
    // pixels, so the card stays an ordinary link while its neighbours take the gesture.
    val html = page(canStreamLiveFor = { it.startsWith("chip") })
    assertTrue(html.contains("cards:[{l:\"\",d:\"\"},{l:\"chip__ideal__default__compact\""), html)
  }

  @Test
  fun `the socket query carries the session token on a gated box`() {
    val html = page(canStreamLiveFor = { true }, isPublic = false, token = "s3cret")
    assertTrue(html.contains("query:\"token=s3cret\""), html)
    assertTrue(html.contains("base:\"/compose-m3\""), html)
    assertTrue(html.contains("holdMs:${ServeWeb.LONG_PRESS_HOLD_MS}"), html)
  }

  @Test
  fun `an unauthenticated visitor is offered the sign-in rather than a socket`() {
    val html = page(canStreamLiveFor = { true }, liveSignInHref = "/auth/github/login")
    assertTrue(html.contains("signInHref:\"/auth/github/login\""), html)
  }

  @Test
  fun `the script reads its ids from the emitted config, never from the DOM`() {
    val js = ServeWebAssets.load("catalog-live.js")!!.bytes.decodeToString()
    assertTrue(js.contains("window.cpCatalogLive"), "config is the only id source")
    assertTrue(js.contains("encodeURIComponent(previewId)"), "ids are encoded into the socket URL")
    // The frame lane, the gesture, and the exits — the pieces a regression would quietly drop.
    assertTrue(js.contains("\"/ws/\""), "it opens the session's stream lane")
    assertTrue(js.contains("codec=webp"), "asking for WebP frames like the viewer does")
    assertTrue(js.contains("cfg.holdMs"), "the hold threshold comes from the server")
    assertTrue(js.contains("kind: \"click\""), "a tap on a live card reaches the composition")
    assertEquals(
      1,
      Regex("function stopLive").findAll(js).count(),
      "one teardown path for Escape, an outside press, a close and pagehide",
    )
  }

  @Test
  fun `input is mapped against the frame's contained rect, not the element box`() {
    // The canvas is `object-fit: contain`, so a frame whose aspect differs from the thumbnail slot
    // is letterboxed inside it. Scaling against the bounding rect would offset every coordinate by
    // the margin — a press would reach a different widget than the one under the finger.
    val js = ServeWebAssets.load("catalog-live.js")!!.bytes.decodeToString()
    assertTrue(
      js.contains("Math.min(rect.width / canvas.width, rect.height / canvas.height)"),
      "the contained scale factor is what maps a pointer to image pixels",
    )
    assertTrue(
      js.contains("(rect.width - paintedW) / 2") && js.contains("(rect.height - paintedH) / 2"),
      "and the frame's centring offset is subtracted",
    )
    assertTrue(
      js.contains("if (x < 0 || y < 0 || x > canvas.width || y > canvas.height) return null;"),
      "a press in the letterbox margin is no press, rather than one clamped onto the edge",
    )
  }

  @Test
  fun `the sign-in press follows the login rather than reporting a dead condition`() {
    // The card is itself an <a>, so a nested sign-in link isn't available (as it is in the viewer)
    // — the press follows the login instead, and the href is origin-checked before it's navigated
    // to so a mis-set config can't become a `javascript:` navigation.
    val js = ServeWebAssets.load("catalog-live.js")!!.bytes.decodeToString()
    assertTrue(js.contains("var href = sameOriginHref(cfg.signInHref);"), js)
    assertTrue(js.contains("if (href) location.href = href;"), js)
    assertTrue(js.contains("u.origin === location.origin ? u.href : \"\""), "same-origin only")
  }
}
