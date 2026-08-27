package ee.schimke.composeai.cli.serve

import java.io.InputStream
import java.net.URI
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The one place a serve host fetches a **client-supplied** URL — `POST /bundles?url=` and `POST
 * /docs?url=`.
 *
 * Both are SSRF surfaces: a public server that fetches whatever a client names could be steered at
 * cloud metadata (`169.254.169.254`) or an internal service. The operator's allowlist
 * (`--accept-bundles-from` / `--accept-docs-from`) is the gate, and the subtlety this object exists
 * for is that **checking the client's URL is not enough**: an HTTP client that follows redirects on
 * its own will happily walk from an allowlisted host to anywhere its `Location` header points, with
 * the allowlist never consulted again. So redirects are turned OFF on the client here, and
 * [followingRedirects] walks them itself, re-checking the allowlist before every single request.
 *
 * [followingRedirects] is pure over its transport so the hop policy is unit-testable with no
 * network; [sendOnce] is the real one-request transport it's normally given.
 *
 * Not for **operator-supplied** URLs (`--bundle <url>`, `--catalogs`): those addresses come from
 * the command line, so they're trusted by definition and aren't allowlist-gated at all.
 */
object ServeUrlFetch {

  /** Redirect hops a client-supplied fetch may take before it's treated as a failure. */
  const val MAX_REDIRECTS = 4

  /** What one HTTP request produced: a body, a redirect to follow, or nothing usable. */
  sealed interface Hop {
    class Body(val bytes: ByteArray) : Hop

    /** `Location`, already resolved against the request URL. */
    class Redirect(val location: String) : Hop

    data object Failed : Hop
  }

  /**
   * Walk a client-supplied fetch to its bytes, **checking [isAllowed] before every request** — the
   * starting URL and each redirect target alike. Null when a hop is off the allowlist, the
   * transport fails, or the chain exceeds [MAX_REDIRECTS] (which also stops a redirect loop).
   */
  fun followingRedirects(
    start: String,
    isAllowed: (String) -> Boolean,
    send: (String) -> Hop,
  ): ByteArray? {
    var current = start
    repeat(MAX_REDIRECTS + 1) {
      if (!isAllowed(current)) return null
      when (val hop = send(current)) {
        is Hop.Body -> return hop.bytes
        is Hop.Redirect -> current = hop.location
        Hop.Failed -> return null
      }
    }
    return null
  }

  /**
   * True when [url] is http/https and its host is on [allowedHosts] (case-insensitive, exact
   * match). An empty allowlist matches nothing — fetching a client-supplied URL is fail-closed
   * everywhere.
   */
  fun isAllowedUrl(url: String, allowedHosts: List<String>): Boolean {
    val uri =
      try {
        URI(url)
      } catch (e: Exception) {
        return false
      }
    if (uri.scheme?.lowercase() !in setOf("http", "https")) return false
    val host = uri.host?.lowercase() ?: return false
    return allowedHosts.any { it.equals(host, ignoreCase = true) }
  }

  /**
   * One request, **no redirect following**: http/https only, body capped at [maxBytes]. A 3xx comes
   * back as [Hop.Redirect] for [followingRedirects] to re-check rather than being chased here.
   */
  fun sendOnce(url: String, maxBytes: Long): Hop {
    if (URI(url).scheme?.lowercase() !in setOf("http", "https")) return Hop.Failed
    httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
      if (response.isRedirect) {
        val location = response.header("Location") ?: return Hop.Failed
        // Resolve a relative Location against the request URL; the result is re-checked against the
        // allowlist by the caller before anything is sent to it.
        val resolved = response.request.url.resolve(location) ?: return Hop.Failed
        return Hop.Redirect(resolved.toString())
      }
      if (!response.isSuccessful) return Hop.Failed
      val body = response.body
      val bytes = readCapped(body.byteStream(), maxBytes) ?: return Hop.Failed
      return Hop.Body(bytes)
    }
  }

  /**
   * Read at most [max] bytes, or null once that's exceeded — stopping there rather than buffering
   * an unbounded (or deliberately huge) response into the server's heap.
   */
  private fun readCapped(input: InputStream, max: Long): ByteArray? {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
      val n = input.read(buffer)
      if (n < 0) break
      total += n
      if (total > max) return null
      out.write(buffer, 0, n)
    }
    return out.toByteArray()
  }

  private val httpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
      .connectTimeout(10, TimeUnit.SECONDS)
      .readTimeout(30, TimeUnit.SECONDS)
      // The allowlist has to see every hop, so redirects are followed by [followingRedirects].
      .followRedirects(false)
      .followSslRedirects(false)
      .build()
  }
}
