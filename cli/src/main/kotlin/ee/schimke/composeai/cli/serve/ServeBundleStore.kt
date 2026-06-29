package ee.schimke.composeai.cli.serve

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Runtime ingestion of **client-provided** portable bundles for the shared/public mode: a client
 * uploads a bundle zip (or points at a URL of one — a CI "build results" artifact), and the store
 * unpacks it and registers a read-only [ServeBundleHost] session via [register]. This is what makes
 * a deployed public server useful without it building anything: clients contribute pre-rendered
 * results and get a shareable `?session=<name>` link back.
 *
 * Safety: only `previews/<id>.png` entries are extracted (everything else in the zip is ignored),
 * each written under a per-bundle directory with a zip-slip containment check, and the total
 * extracted size is capped. The URL case ([addFromUrl]) is an **SSRF** surface — a public server
 * fetching arbitrary URLs could be steered at internal metadata/services — so it is gated by
 * [allowedHosts]: only a URL whose host is on that operator-supplied allowlist is fetched, and an
 * empty allowlist refuses every URL (fail closed). [fetch] is injected so it can be stubbed in
 * tests; the host gate runs in [addFromUrl] regardless of which fetcher is wired.
 */
class ServeBundleStore(
  private val root: File,
  private val register: (name: String, host: ServeBundleHost) -> Unit,
  private val fetch: (String) -> ByteArray? = ::httpFetch,
  private val maxBytes: Long = DEFAULT_MAX_BYTES,
  /**
   * SSRF allowlist for [addFromUrl]: hostnames (case-insensitive, exact match) a `?url=` fetch is
   * permitted to reach. Empty = no URL fetch is allowed (fail closed), so enabling
   * `--accept-bundles` alone never lets a client make the server fetch an arbitrary address.
   */
  private val allowedHosts: List<String> = emptyList(),
) {

  sealed interface Result {
    data class Ok(val name: String, val previewCount: Int) : Result

    data class Failed(val reason: String) : Result
  }

  /**
   * Unpack [zipBytes] under [name] and register it as a bundle session.
   *
   * [isSecurityChecked] is a required, greppable audit marker (no runtime enforcement): the caller
   * passes `true` only once the request has cleared policy (here: token-gated `POST /bundles`). The
   * unpack itself is defended in depth — name sanitisation, zip-slip containment, size cap — but
   * the marker records that the *entry point* was authorised before risky bytes were processed.
   */
  fun add(name: String, zipBytes: ByteArray, isSecurityChecked: Boolean): Result {
    val safe = sanitizeName(name) ?: return Result.Failed("invalid bundle name: '$name'")
    val dir = File(root, safe)
    dir.deleteRecursively()
    val count =
      try {
        extractPreviews(zipBytes, dir)
      } catch (e: Exception) {
        dir.deleteRecursively()
        return Result.Failed("could not unpack bundle: ${e.message}")
      }
    if (count == 0) {
      dir.deleteRecursively()
      return Result.Failed("bundle had no previews/*.png entries")
    }
    val host = ServeBundleHost(dir, safe)
    register(safe, host)
    return Result.Ok(safe, host.previews.size)
  }

  /**
   * Fetch a bundle zip from [url] (the "link to build results" case), then [add] it.
   *
   * SSRF gate: the URL must be http/https and its host must be on [allowedHosts] (empty = refuse
   * everything), checked here before [fetch] runs, so a client can't steer the server at an
   * internal address. [isSecurityChecked] is the same documented audit marker as [add] — the caller
   * asserts the entry point was authorised (token-gated). The host allowlist is the actual SSRF
   * enforcement.
   */
  fun addFromUrl(name: String, url: String, isSecurityChecked: Boolean): Result {
    if (!isAllowedUrl(url)) {
      return Result.Failed(
        "refusing to fetch $url: host is not on the --accept-bundles-from allowlist"
      )
    }
    val bytes =
      try {
        fetch(url)
      } catch (e: Exception) {
        return Result.Failed("could not fetch $url: ${e.message}")
      } ?: return Result.Failed("could not fetch $url")
    return add(name, bytes, isSecurityChecked = isSecurityChecked)
  }

  /** True when [url] is http/https and its host is on the [allowedHosts] SSRF allowlist. */
  private fun isAllowedUrl(url: String): Boolean {
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

  /** Extract only `previews/<id>.png` entries into [dir] (zip-slip safe, size-capped). */
  private fun extractPreviews(zipBytes: ByteArray, dir: File): Int {
    val rootPath = dir.canonicalFile.toPath()
    var count = 0
    var total = 0L
    ZipInputStream(ByteArrayInputStream(zipBytes)).use { zin ->
      var entry = zin.nextEntry
      while (entry != null) {
        val name = entry.name.replace('\\', '/')
        val segments = name.split("/")
        // Keep the baked PNGs (the servable images) and, since v8, the per-preview override sidecars
        // (`previews/<id>.overrides.json`) so a served bundle can present its declared editable knobs.
        val underPreviews = name.startsWith("$PREVIEWS_SUBDIR/") && ".." !in segments
        val isPng = underPreviews && name.endsWith(PNG_SUFFIX)
        val isOverrides = underPreviews && name.endsWith(OVERRIDES_SUFFIX)
        if (!entry.isDirectory && (isPng || isOverrides)) {
          val target = File(dir, name)
          // Zip-slip guard: the resolved path must stay under the bundle dir.
          if (target.canonicalFile.toPath().startsWith(rootPath)) {
            target.parentFile?.mkdirs()
            // Copy in bounded chunks so a huge / zip-bomb entry can't be fully allocated before the
            // cap rejects it — abort the moment the running total crosses maxBytes.
            total += copyCapped(zin, target, remaining = maxBytes - total)
            // Only PNGs count toward "does this bundle have any servable previews?" — an override
            // sidecar without a PNG isn't a renderable preview.
            if (isPng) count++
          }
        }
        zin.closeEntry()
        entry = zin.nextEntry
      }
    }
    return count
  }

  /** Stream [input] into [target], throwing once more than [remaining] bytes have been written. */
  private fun copyCapped(input: InputStream, target: File, remaining: Long): Long {
    var written = 0L
    val buffer = ByteArray(64 * 1024)
    target.outputStream().use { out ->
      while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        written += n
        check(written <= remaining) { "bundle exceeds ${maxBytes / (1024 * 1024)}MB" }
        out.write(buffer, 0, n)
      }
    }
    return written
  }

  companion object {
    private const val PREVIEWS_SUBDIR = "previews"
    private const val PNG_SUFFIX = ".png"
    private const val OVERRIDES_SUFFIX = ".overrides.json"
    private const val DEFAULT_MAX_BYTES = 100L * 1024 * 1024 // 100 MB

    /** A session name safe to use as a path segment + URL value; null if it can't be made safe. */
    fun sanitizeName(name: String): String? {
      val trimmed = name.trim()
      // Reject empty and dot-only names ('.', '..', '...') even though they match the char class:
      // File(root, ".")/File(root, "..") resolve to the upload root or its parent, and add() calls
      // deleteRecursively() on that path before unpacking — which would wipe the wrong directory.
      if (trimmed.isEmpty() || trimmed.all { it == '.' }) return null
      return trimmed.takeIf { it.matches(Regex("[A-Za-z0-9._@-]{1,128}")) }
    }

    private val httpClient: OkHttpClient by lazy {
      OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    }

    /** Default URL fetcher: http/https only, capped + time-bounded. SSRF is the operator's call. */
    private fun httpFetch(url: String): ByteArray? {
      if (URI(url).scheme?.lowercase() !in setOf("http", "https")) return null
      httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
        if (!response.isSuccessful) return null
        val body = response.body ?: return null
        return readCapped(body.byteStream(), DEFAULT_MAX_BYTES)
      }
    }

    /**
     * Read at most [max] bytes into memory, aborting (not buffering further) once it's exceeded.
     */
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
}
