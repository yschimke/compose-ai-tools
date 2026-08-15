package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The grid's **long-press live lane**: holding a catalog card starts a live daemon session inside
 * the card, instead of navigating to the viewer.
 *
 * What is pinned here is the SERVER's half of the contract — each card's streamable preview ids as
 * an object literal in the grid's document order, which `<cp-catalog-live>` reads (never the DOM)
 * when it builds a socket URL. Plus the fail-closed half: a session with nothing to stream emits no
 * configuration and no element at all.
 *
 * The browser's half moved to `cli/serve-web` with the port and is tested there, as behaviour
 * rather than as source text: `liveSession.test.ts` (which preview, which socket URL, what a
 * refused lane says), `livePointerMap.test.ts` (a press mapped against the frame's contained rect,
 * and a press in the letterbox margin refused rather than clamped), `sameOrigin.test.ts` (the
 * sign-in href checked before it is navigated to) and `catalogLiveElement.test.ts` (the gesture,
 * and the one-session-at-a-time rule). Three tests here used to grep the asset for those very
 * lines; greps like that cannot survive minification, and each named a rule the replacement now
 * exercises directly.
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
    assertFalse(html.contains("cp-catalog-live"), "and no element to read it")
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
      html.contains("<cp-catalog-live></cp-catalog-live>"),
      "the lane's element is emitted",
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
}
