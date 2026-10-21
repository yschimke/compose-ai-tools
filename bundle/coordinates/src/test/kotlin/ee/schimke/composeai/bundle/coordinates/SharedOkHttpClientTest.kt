package ee.schimke.composeai.bundle.coordinates

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SharedOkHttpClientTest {
  @Test
  fun `closing a Ktor client leaves the shared client running`() {
    val shared = SharedOkHttpClient.client
    repeat(2) { ktorOkHttpClient().close() }
    assertFalse(shared.dispatcher.executorService.isShutdown)
  }

  @Test
  fun `a Ktor client shares the connection pool and certificate check`() {
    val forKtor = SharedOkHttpClient.forKtor()
    assertSame(SharedOkHttpClient.client.connectionPool, forKtor.connectionPool)
    assertTrue(forKtor.hostnameVerifier is ForbiddenCertificatesHostnameVerifier)
  }
}
