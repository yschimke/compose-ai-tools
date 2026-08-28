package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.BundleVerifier
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Parses and fetches the **operator-supplied** preview bundles a public/shared server is started
 * with (`--bundle <url|path>` / `--bundle <name>=<url|path>`, repeatable). Unlike the client `POST
 * /bundles?url=` upload path ([ServeBundleStore]), these URLs come from the operator's own command
 * line (the same trust level as `--catalogs`), so there is **no SSRF allowlist** here — the
 * operator chose the address. Whether a fetched bundle is *live-rendered* is still gated exactly
 * like a catalog's `liveBundle`: it must verify `Trusted` (signature or trusted branch origin) and
 * the operator must pass `--allow-render-trusted`; otherwise it is served read-only as baked PNGs.
 *
 * This class only turns a spec into (name, bytes, origin) — the live-vs-baked decision and the
 * registry wiring live in [ServeCommand] alongside the catalog machinery it mirrors.
 */
public object ServeStartupBundles {

  /**
   * One `--bundle` entry: the [source] (an `http(s)://…` URL or a local filesystem path) and the
   * session [name] it registers under (`/<name>/`, `?session=<name>`).
   */
  data class Spec(val name: String, val source: String)

  /**
   * Parse each raw `--bundle` value into a [Spec]. Two forms:
   * - `<url|path>` — the session name is derived from the file's basename (extension stripped,
   *   sanitised to the same charset [ServeBundleStore.sanitizeName] accepts).
   * - `<name>=<url|path>` — an explicit name, used only when the part before the first `=` is a
   *   safe bare name (so `https://…` URLs, whose scheme carries no `=`, are never mis-split).
   *
   * A spec whose name can't be sanitised is dropped with a warning via [onWarn] rather than failing
   * the whole server.
   */
  fun parse(
    raw: List<String>,
    onWarn: (String) -> Unit = { System.err.println("serve: $it") },
  ): List<Spec> = raw.mapNotNull { entry ->
    val trimmed = entry.trim()
    if (trimmed.isEmpty()) return@mapNotNull null
    val eq = trimmed.indexOf('=')
    val explicit =
      if (eq > 0) {
        val candidate = trimmed.substring(0, eq)
        // Only treat `foo=…` as name=source when `foo` is a bare safe name (no scheme / slash),
        // so a `?a=b` query or a `key=val` in a URL never hijacks the name.
        if (ServeBundleStore.sanitizeName(candidate) != null && "://" !in candidate) candidate
        else null
      } else null
    val source = if (explicit != null) trimmed.substring(eq + 1).trim() else trimmed
    if (source.isEmpty()) {
      onWarn("--bundle '$entry' has no source — skipping")
      return@mapNotNull null
    }
    val rawName = explicit ?: deriveName(source)
    val name = ServeBundleStore.sanitizeName(rawName)
    if (name == null) {
      onWarn("--bundle '$entry' has no usable name (from '$rawName') — skipping")
      return@mapNotNull null
    }
    Spec(name, source)
  }

  /**
   * The session name a bare `--bundle <source>` uses: the basename with a known extension stripped.
   */
  private fun deriveName(source: String): String {
    val last = source.trimEnd('/').substringAfterLast('/').substringBefore('?').substringBefore('#')
    return last.removeSuffix(".bundle").removeSuffix(".png").removeSuffix(".zip").ifBlank {
      "bundle"
    }
  }

  /** True when [source] is an `http`/`https` URL (vs. a local filesystem path). */
  fun isUrl(source: String): Boolean = runCatching {
    URI(source).scheme?.lowercase() in setOf("http", "https")
  }
    .getOrDefault(false)

  /**
   * Candidate branch [BundleVerifier.Origin]s for a
   * `raw.githubusercontent.com/<owner>/<repo>/<ref>/…` URL — the same host `--catalogs` fetches
   * from — so a bundle pulled from a trusted branch earns `Trusted(Branch)` without a signature.
   *
   * A raw URL is `.../<owner>/<repo>/<ref>/<path…>`, but `<ref>` may itself contain slashes (a
   * branch like `design-artifacts/compose-m3`), and the boundary between the ref and the file path
   * isn't recoverable from the string alone (GitHub resolves it server-side). So this enumerates
   * every split that leaves at least one path segment — `design-artifacts`, then
   * `design-artifacts/compose-m3`, … — shortest ref first. The caller picks the one the trust store
   * actually trusts (a `design-artifacts/<slug>` branch glob matches the two-segment branch, not
   * the bare `design-artifacts`), which is what disambiguates the split. A non-github host / local
   * path / too-short URL yields an empty list, so trust then rests solely on a pinned Ed25519
   * signature.
   */
  fun candidateOrigins(source: String): List<BundleVerifier.Origin> {
    val uri = runCatching { URI(source) }.getOrNull() ?: return emptyList()
    if (uri.host?.lowercase() != RAW_GITHUB_HOST) return emptyList()
    val segments = (uri.path ?: "").trim('/').split('/').filter { it.isNotEmpty() }
    if (segments.size < 4) return emptyList()
    val owner = segments[0]
    val repo = segments[1]
    if (owner.isBlank() || repo.isBlank()) return emptyList()
    // ref spans segments[2..refEnd]; leave ≥1 trailing path segment (refEnd ≤ size-2).
    return (2..(segments.size - 2)).map { refEnd ->
      BundleVerifier.Origin(
        repo = "$owner/$repo",
        branch = segments.subList(2, refEnd + 1).joinToString("/"),
      )
    }
  }

  /** Fetch [url] into memory, http/https only, capped + time-bounded; null on any failure. */
  fun fetch(url: String, maxBytes: Long = DEFAULT_MAX_BYTES): ByteArray? {
    if (!isUrl(url)) return null
    return runCatching {
      httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
        if (!response.isSuccessful) return@use null
        val body = response.body
        readCapped(body.byteStream(), maxBytes)
      }
    }
      .getOrNull()
  }

  private const val RAW_GITHUB_HOST = "raw.githubusercontent.com"
  private const val DEFAULT_MAX_BYTES = 100L * 1024 * 1024 // 100 MB — matches ServeBundleStore.

  private val httpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
      .connectTimeout(10, TimeUnit.SECONDS)
      .readTimeout(60, TimeUnit.SECONDS)
      .build()
  }

  private fun readCapped(input: InputStream, max: Long): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
      val n = input.read(buffer)
      if (n < 0) break
      total += n
      require(total <= max) { "remote bundle exceeds ${max / (1024 * 1024)}MB" }
      out.write(buffer, 0, n)
    }
    return out.toByteArray()
  }
}
