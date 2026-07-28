package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The SSRF policy every client-supplied `?url=` fetch runs through — `POST /bundles?url=` and `POST
 * /docs?url=` alike.
 *
 * The load-bearing property is that the allowlist is consulted **per hop**, not once: an
 * allowlisted host that answers with a redirect must not be able to walk the server anywhere its
 * `Location` points. [ServeUrlFetch.followingRedirects] is pure over its transport, so that's
 * asserted here without a network.
 */
class ServeUrlFetchTest {

  private val body = "a document".toByteArray()

  /** A canned transport: an exact-URL → [ServeUrlFetch.Hop] table, anything else fails. */
  private fun transport(
    vararg chain: Pair<String, ServeUrlFetch.Hop>
  ): (String) -> ServeUrlFetch.Hop = { url ->
    chain.firstOrNull { it.first == url }?.second ?: ServeUrlFetch.Hop.Failed
  }

  private val onGoodHost = { url: String -> url.startsWith("https://good.example/") }

  @Test
  fun `a redirect off the allowlist is refused before the request is made`() {
    val sent = mutableListOf<String>()
    val send =
      transport(
        "https://good.example/a.zip" to
          ServeUrlFetch.Hop.Redirect("http://169.254.169.254/latest/meta-data")
      )

    val bytes =
      ServeUrlFetch.followingRedirects("https://good.example/a.zip", onGoodHost) { url ->
        sent += url
        send(url)
      }

    assertNull(bytes)
    // The point of the fix: the internal address is never contacted at all.
    assertEquals(listOf("https://good.example/a.zip"), sent)
  }

  @Test
  fun `a redirect that stays on the allowlist is followed`() {
    val bytes =
      ServeUrlFetch.followingRedirects(
        "https://good.example/a.zip",
        onGoodHost,
        transport(
          "https://good.example/a.zip" to
            ServeUrlFetch.Hop.Redirect("https://good.example/real.zip"),
          "https://good.example/real.zip" to ServeUrlFetch.Hop.Body(body),
        ),
      )

    assertEquals(body.toList(), bytes?.toList())
  }

  @Test
  fun `a redirect chain is bounded, so a loop terminates`() {
    var hops = 0

    val bytes =
      ServeUrlFetch.followingRedirects("https://good.example/a.zip", onGoodHost) {
        hops++
        ServeUrlFetch.Hop.Redirect("https://good.example/a.zip")
      }

    assertNull(bytes)
    assertEquals(ServeUrlFetch.MAX_REDIRECTS + 1, hops, "the walk stops at the hop ceiling")
  }

  @Test
  fun `a transport failure ends the walk`() {
    assertNull(
      ServeUrlFetch.followingRedirects("https://good.example/a.zip", onGoodHost) {
        ServeUrlFetch.Hop.Failed
      }
    )
  }

  @Test
  fun `the allowlist is exact-host, http-or-https, and empty means nothing`() {
    val hosts = listOf("ci.example.com")

    assertTrue(ServeUrlFetch.isAllowedUrl("https://ci.example.com/a.zip", hosts))
    assertTrue(ServeUrlFetch.isAllowedUrl("http://CI.EXAMPLE.COM/a.zip", hosts))
    // A look-alike host, a subdomain, a non-HTTP scheme and an unparseable value all fail closed.
    assertFalse(ServeUrlFetch.isAllowedUrl("https://ci.example.com.evil.test/a.zip", hosts))
    assertFalse(ServeUrlFetch.isAllowedUrl("https://sub.ci.example.com/a.zip", hosts))
    assertFalse(ServeUrlFetch.isAllowedUrl("file:///etc/passwd", hosts))
    assertFalse(ServeUrlFetch.isAllowedUrl("not a url", hosts))
    assertFalse(ServeUrlFetch.isAllowedUrl("https://ci.example.com/a.zip", emptyList()))
  }
}
