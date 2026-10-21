package ee.schimke.composeai.bundle.coordinates

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.OkHttpClient

/**
 * This module's one [OkHttpClient], and the one Ktor engine over it. Carries
 * [ForbiddenCertificatesHostnameVerifier].
 */
internal object SharedOkHttpClient {
  val client: OkHttpClient by lazy {
    OkHttpClient.Builder().hostnameVerifier(ForbiddenCertificatesHostnameVerifier()).build()
  }

  /**
   * Never closed: closing an OkHttp engine shuts down its client's dispatcher and evicts its
   * connection pool, which here are [client]'s. An [HttpClient] built on an engine instance doesn't
   * own it, so closing that client is safe.
   */
  val ktorEngine: HttpClientEngine by lazy { OkHttp.create { preconfigured = client } }
}

/** A Ktor client on [SharedOkHttpClient]; closing it leaves the shared engine running. */
internal fun ktorOkHttpClient(): HttpClient = HttpClient(SharedOkHttpClient.ktorEngine)
