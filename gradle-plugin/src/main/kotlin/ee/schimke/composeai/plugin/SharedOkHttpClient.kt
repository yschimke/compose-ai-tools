package ee.schimke.composeai.plugin

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
}
