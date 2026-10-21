package ee.schimke.composeai.viewer

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.engine.okhttp.OkHttpConfig
import okhttp3.Dispatcher
import okhttp3.OkHttpClient

/**
 * This module's one [OkHttpClient]. Callers needing other timeouts or redirect rules derive a
 * variant with [OkHttpClient.newBuilder], which shares its connection pool rather than opening its
 * own. Carries [ForbiddenCertificatesHostnameVerifier].
 */
internal object SharedOkHttpClient {
  val client: OkHttpClient by lazy {
    OkHttpClient.Builder().hostnameVerifier(ForbiddenCertificatesHostnameVerifier()).build()
  }

  /**
   * [client] for a Ktor engine's `preconfigured`, with a dispatcher of its own: closing a Ktor
   * client shuts down its engine's dispatcher, which would otherwise stop [client] for every other
   * caller.
   */
  fun forKtor(): OkHttpClient = client.newBuilder().dispatcher(Dispatcher()).build()
}

/** A Ktor client on [SharedOkHttpClient]. */
internal fun ktorOkHttpClient(block: HttpClientConfig<OkHttpConfig>.() -> Unit = {}): HttpClient =
  HttpClient(OkHttp) {
    engine { preconfigured = SharedOkHttpClient.forKtor() }
    block()
  }
