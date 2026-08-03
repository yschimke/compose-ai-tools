package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The presence heartbeat: an open catalog page tells the server a visitor is still there, so its
 * session — and the daemon behind it — isn't reaped out from under them.
 *
 * This matters more the better the page caches. A grid of prebaked thumbnails repaints from cache
 * and makes no requests at all, so "no requests for ten minutes" stopped meaning "nobody is here".
 */
class ServeWebPresenceTest {

  private val previews = listOf(ServePreview(id = "filled-button", label = "Filled"))

  private fun page(presenceUrl: String = "") =
    ServeWeb.landingPage(
      "compose-m3",
      previews,
      token = "t",
      isPublic = true,
      basePath = "/compose-m3",
      presenceUrl = presenceUrl,
    )

  @Test
  fun `a catalog page pings the server well inside the idle window`() {
    val html = page("/compose-m3/api/presence")
    assertTrue(
      html.contains("fetch(presenceUrl, { method: \"POST\", credentials: \"same-origin\""),
      "the page posts a heartbeat",
    )
    assertTrue(
      html.contains("setInterval(ping, ${ServeWeb.PRESENCE_INTERVAL_SECONDS} * 1000)"),
      "on the presence interval",
    )
    // The reaper's window is ten minutes and the heartbeat has to survive a dropped ping — a
    // sleeping laptop, a flaky connection — so the interval must leave room for at least two
    // before the session would lapse.
    assertTrue(
      ServeWeb.PRESENCE_INTERVAL_SECONDS * 2 * 1000L <
        ServeSessionRegistry.DEFAULT_IDLE_TIMEOUT_MILLIS,
      "two heartbeats fit inside the idle window",
    )
  }

  @Test
  fun `a backgrounded tab is not a visitor`() {
    // Holding a daemon resident for a tab nobody is looking at is precisely the waste the reaper
    // exists to prevent — so the ping is skipped while hidden, and fires on the way back.
    val html = page("/compose-m3/api/presence")
    assertTrue(
      html.contains("if (document.visibilityState !== \"visible\") return;"),
      "hidden tabs don't ping",
    )
    assertTrue(
      html.contains("document.addEventListener(\"visibilitychange\", ping)"),
      "and a tab returned to says so at once, rather than waiting out an interval",
    )
  }

  @Test
  fun `a page with no presence URL emits no heartbeat at all`() {
    val html = page()
    assertFalse(html.contains("presenceUrl"), "no heartbeat for a plain-module landing")
    assertFalse(html.contains("visibilitychange"), "and none of its wiring")
  }
}
