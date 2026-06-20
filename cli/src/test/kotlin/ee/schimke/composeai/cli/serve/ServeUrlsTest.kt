package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ServeUrlsTest {

  @Test
  fun `generated tokens are url-safe and unique`() {
    val a = ServeUrls.generateToken()
    val b = ServeUrls.generateToken()
    assertNotEquals(a, b)
    assertTrue(a.isNotEmpty())
    // base64url alphabet only, no padding.
    assertTrue(a.all { it.isLetterOrDigit() || it == '-' || it == '_' }, "unexpected char in '$a'")
  }

  @Test
  fun `token match is exact and rejects null or wrong`() {
    val token = "s3cr3t-token_value"
    assertTrue(ServeUrls.tokensMatch(token, token))
    assertFalse(ServeUrls.tokensMatch(token, null))
    assertFalse(ServeUrls.tokensMatch(token, ""))
    assertFalse(ServeUrls.tokensMatch(token, "s3cr3t-token_valu"))
    assertFalse(ServeUrls.tokensMatch(token, "$token-extra"))
  }

  @Test
  fun `isExposed only for wildcard binds`() {
    assertTrue(ServeUrls.isExposed("0.0.0.0"))
    assertTrue(ServeUrls.isExposed("::"))
    assertFalse(ServeUrls.isExposed("127.0.0.1"))
    assertFalse(ServeUrls.isExposed("192.168.1.5"))
  }

  @Test
  fun `urls carry the token and percent-encode the preview id`() {
    val origin = ServeUrls.origin("127.0.0.1", 8723)
    assertEquals("http://127.0.0.1:8723", origin)

    val landing = ServeUrls.landingUrl(origin, "tok en")
    assertTrue(landing.startsWith("http://127.0.0.1:8723/?token="))
    assertTrue("tok%20en" in landing, "token should be percent-encoded: $landing")

    // A preview id with characters that must not survive raw in a URL.
    val viewer = ServeUrls.viewerUrl(origin, "com.x.Foo#bar baz", "tok")
    assertTrue("/p/com.x.Foo%23bar%20baz?token=tok" in viewer, viewer)

    val render =
      ServeUrls.renderUrl(origin, "com.x.Foo", "tok", mapOf("uiMode" to "dark", "device" to ""))
    assertTrue(render.startsWith("http://127.0.0.1:8723/render/com.x.Foo.png?token=tok"), render)
    assertTrue("uiMode=dark" in render, render)
    // Blank override values are dropped.
    assertFalse("device=" in render, render)
  }
}
