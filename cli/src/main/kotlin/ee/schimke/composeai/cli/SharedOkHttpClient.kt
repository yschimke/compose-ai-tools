package ee.schimke.composeai.cli

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import java.util.concurrent.ConcurrentHashMap
import okhttp3.OkHttpClient

/**
 * This module's one [OkHttpClient]. Callers needing other timeouts or redirect rules derive a
 * variant with [OkHttpClient.newBuilder], which shares its connection pool and dispatcher rather
 * than opening its own. Carries [ForbiddenCertificatesHostnameVerifier].
 */
internal object SharedOkHttpClient {
  val client: OkHttpClient by lazy {
    OkHttpClient.Builder().hostnameVerifier(ForbiddenCertificatesHostnameVerifier()).build()
  }

  private val ktorEngines = ConcurrentHashMap<Boolean, HttpClientEngine>()

  /**
   * A Ktor engine over [client], one per redirect policy. Never closed: closing an OkHttp engine
   * shuts down its client's dispatcher and evicts its connection pool, which here are [client]'s.
   * An [HttpClient] built on an engine instance doesn't own it, so closing that client is safe.
   */
  fun ktorEngine(followRedirects: Boolean): HttpClientEngine =
    ktorEngines.computeIfAbsent(followRedirects) { follow ->
      OkHttp.create {
        preconfigured =
          client.newBuilder().followRedirects(follow).followSslRedirects(follow).build()
      }
    }
}

/** A Ktor client on [SharedOkHttpClient]; closing it leaves the shared engine running. */
internal fun ktorOkHttpClient(
  followRedirects: Boolean = true,
  block: HttpClientConfig<*>.() -> Unit = {},
): HttpClient =
  HttpClient(SharedOkHttpClient.ktorEngine(followRedirects)) {
    this.followRedirects = followRedirects
    block()
  }
