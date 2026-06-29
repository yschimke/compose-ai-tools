package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The token gate ([ServeHttpServer.isAuthorized]). In normal mode the provided token must match; in
 * `--public` mode every request is authorized (the deployed public server, where browsing the
 * catalogs / bundles is the point and safety is structural, not token-based).
 */
class ServeAuthTest {

  private val token = "s3cret-token"

  @Test
  fun `non-public mode requires the matching token`() {
    assertTrue(ServeHttpServer.isAuthorized(token, token, isPublic = false))
    assertFalse(ServeHttpServer.isAuthorized(token, "wrong", isPublic = false))
    assertFalse(ServeHttpServer.isAuthorized(token, null, isPublic = false))
  }

  @Test
  fun `public mode authorizes every request regardless of token`() {
    assertTrue(ServeHttpServer.isAuthorized(token, null, isPublic = true))
    assertTrue(ServeHttpServer.isAuthorized(token, "wrong", isPublic = true))
    assertTrue(ServeHttpServer.isAuthorized(token, token, isPublic = true))
  }
}
