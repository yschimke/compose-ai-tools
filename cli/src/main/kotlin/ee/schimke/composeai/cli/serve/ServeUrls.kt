package ee.schimke.composeai.cli.serve

import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Pure helpers for the `compose-preview serve` link surface: minting the session token, assembling
 * shareable URLs (with preview ids percent-encoded), constant-time token comparison, and LAN IPv4
 * discovery for the startup banner. Kept free of ktor / IO types so the URL + token logic is
 * unit-testable; the only environment touch is [siteLocalIpv4Addresses], isolated in its own fn.
 */
object ServeUrls {

  /** A host bound to all interfaces — the value we treat as "exposed to the LAN". */
  const val ALL_INTERFACES: String = "0.0.0.0"

  /** Loopback host; the safe default bind. */
  const val LOOPBACK: String = "127.0.0.1"

  /**
   * Mint a URL-safe, unguessable session token: 32 bytes from [SecureRandom], base64url-encoded
   * without padding. This is the only gate on the served endpoints, so it must be high-entropy and
   * never derived from anything predictable.
   */
  fun generateToken(random: SecureRandom = SecureRandom()): String {
    val bytes = ByteArray(32)
    random.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
  }

  /** True when [host] means "bound to every interface", i.e. reachable from other machines. */
  fun isExposed(host: String): Boolean = host == ALL_INTERFACES || host == "::"

  /**
   * Base origin (`http://host:port`) a browser uses. When [host] is the wildcard bind, callers
   * substitute a concrete reachable address (loopback for the Local line, a
   * [siteLocalIpv4Addresses] entry for the Network line) — the wildcard itself is not a usable URL
   * host.
   */
  fun origin(host: String, port: Int): String = "http://$host:$port"

  /** Landing-page URL (preview list) carrying the token. */
  fun landingUrl(origin: String, token: String): String =
    "$origin/?token=${WebEscaping.urlEncodeSegment(token)}"

  /** Viewer-page URL for one preview, id percent-encoded as a path segment, token in the query. */
  fun viewerUrl(origin: String, previewId: String, token: String): String =
    "$origin/p/${WebEscaping.urlEncodeSegment(previewId)}?token=${WebEscaping.urlEncodeSegment(token)}"

  /**
   * Relative src for the in-browser Wasm app backing a catalog [previewId] in [system]
   * (`/wasm/<system>/?id=<component>[&uiMode=<theme>]`). The catalog preview id is
   * `<component-slug>__<axis>…` and the Wasm app keys its component registry by the slug, so the
   * variant is stripped for `id`. The variant's M3 theme **is** forwarded as `uiMode`, though: the
   * app defaults to light, so without it a deep link to a `…__dark` snapshot would flip to light
   * the moment the viewer hands the render to the in-browser tier (e.g. on a font-scale change).
   * The theme axis surfaces as a `light`/`dark` segment; absent one, no `uiMode` is forced and the
   * app uses its own default. The viewer's Theme control still overrides this when set.
   */
  fun wasmAppSrc(system: String, previewId: String): String {
    return buildWasmAppSrc("/wasm/${WebEscaping.urlEncodeSegment(system)}/", previewId)
  }

  /**
   * Token-in-path twin of [wasmAppSrc] for automatically discovered local applications. Keeping the
   * credential in the directory prefix means the app's relative JavaScript and `.wasm` requests
   * inherit it; a query token on `index.html` would be lost by those sub-resource URLs.
   */
  fun privateWasmAppSrc(system: String, previewId: String, token: String): String =
    buildWasmAppSrc(
      "/wasm-private/${WebEscaping.urlEncodeSegment(token)}/${WebEscaping.urlEncodeSegment(system)}/",
      previewId,
    )

  private fun buildWasmAppSrc(base: String, previewId: String): String {
    val component = previewId.substringBefore("__")
    val theme = previewId.split("__").drop(1).lastOrNull { it == "light" || it == "dark" }
    return buildString {
      append(base).append("?id=")
      append(WebEscaping.urlEncodeSegment(component))
      if (theme != null) append("&uiMode=").append(theme)
    }
  }

  /**
   * Render (PNG) URL for one preview at the given overrides. [overrides] is an already-validated
   * map of `ServeOverrides.SUPPORTED_KEYS` → value; the token and each value are percent-encoded.
   */
  fun renderUrl(
    origin: String,
    previewId: String,
    token: String,
    overrides: Map<String, String> = emptyMap(),
  ): String {
    val query = buildString {
      append("token=").append(WebEscaping.urlEncodeSegment(token))
      for ((k, v) in overrides) {
        if (v.isBlank()) continue
        append('&').append(k).append('=').append(WebEscaping.urlEncodeSegment(v))
      }
    }
    return "$origin/render/${WebEscaping.urlEncodeSegment(previewId)}.png?$query"
  }

  /**
   * GitHub blob URL for a preview's source file:
   * `https://github.com/<repo>/blob/<ref>/<module>/<sourceFile>`. [repo] is `owner/name`; [ref] is
   * the **source** branch/tag/sha the catalog was built from (its `catalog.json` `source.ref`, NOT
   * the `design-artifacts/<system>` delivery branch, which carries generated assets rather than
   * Kotlin); [module] is the source module's Gradle project path (`source.module`, e.g.
   * `:samples:design-catalog-compose-m3`) or repository-relative subdirectory, joined ahead of the
   * module-relative [sourceFile] that discovery recorded. Gradle project separators are converted
   * to repository path separators, so `:previews` links under `previews/` rather than the literal
   * (and invalid) `%3Apreviews/` directory.
   *
   * Returns null when [repo], [ref], or [sourceFile] is missing/blank, so a caller can `?.let` the
   * link into existence only when it resolves. [module] is optional (a source with no module
   * subdirectory links `blob/<ref>/<sourceFile>` directly). The joined `<module>/<sourceFile>` path
   * is percent-encoded per segment so `/` separators survive while spaces and other unsafe
   * characters are escaped; [repo] and [ref] are passed through verbatim (a `/`-bearing ref is a
   * valid blob path, matching the existing provenance links).
   */
  fun githubBlobUrl(repo: String?, ref: String?, module: String?, sourceFile: String?): String? {
    val r = repo?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val f = ref?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val rel =
      sourceFile?.replace('\\', '/')?.trim()?.trim('/')?.takeIf { it.isNotEmpty() } ?: return null
    val rawModule = module?.replace('\\', '/')?.trim()?.trim('/')?.takeIf { it.isNotEmpty() }
    val mod =
      rawModule
        ?.let { if (it.startsWith(':')) it.trim(':').replace(':', '/') else it }
        ?.takeIf { it.isNotEmpty() }
    val path = if (mod != null) "$mod/$rel" else rel
    val encoded = path.split('/').joinToString("/") { WebEscaping.urlEncodeSegment(it) }
    return "https://github.com/$r/blob/$f/$encoded"
  }

  /**
   * The **raw file** twin of [githubBlobUrl]: `https://raw.githubusercontent.com/<repo>/<ref>/…`,
   * resolving the same `<module>/<sourceFile>` path by the same rules. Where the blob URL is for a
   * human to click, this is what the server reads a preview's Kotlin from to seed the playground
   * editor (`/playground?from=…`).
   *
   * Every input comes from the catalog's own trusted metadata — `catalog.json`'s `source.{repo,
   * ref, module}` and the `sourceFile` recorded per preview — never from a request, so the host
   * cannot be steered at an arbitrary URL by a visitor naming a preview.
   */
  fun githubRawUrl(repo: String?, ref: String?, module: String?, sourceFile: String?): String? {
    val blob = githubBlobUrl(repo, ref, module, sourceFile) ?: return null
    return blob
      .replaceFirst("https://github.com/", "https://raw.githubusercontent.com/")
      .replaceFirst("/blob/", "/")
  }

  /**
   * `history.json` on a delivery branch — the precomputed render timeline the viewer reads instead
   * of walking git. Null when there is no delivery provenance (an uploaded bundle, a local
   * project), which is also the signal for the viewer to leave the timeline out entirely.
   */
  fun historyManifestUrl(repo: String?, branch: String?): String? {
    val r = repo?.trim()?.trim('/')?.takeIf { it.isNotEmpty() && it.count { c -> c == '/' } == 1 }
    val b = branch?.trim()?.trim('/')?.takeIf { it.isNotEmpty() }
    if (r == null || b == null) return null
    val encodedBranch = b.split('/').joinToString("/") { WebEscaping.urlEncodeSegment(it) }
    return "https://raw.githubusercontent.com/$r/$encodedBranch/${PreviewHistoryManifest.FILE_NAME}"
  }

  /**
   * A render as it existed at [commit] — `raw.githubusercontent.com/<repo>/<sha>/<path>`.
   *
   * This is what makes a timeline viewable at all. The delivery branch only carries the *current*
   * bytes at its tip, but the raw host serves any commit, so pairing the manifest's per-version
   * `commit` with its `path` addresses every historical render directly — no server round-trip and
   * nothing to unpack. Verified against real published renders: two versions of the same preview
   * fetch as different bytes.
   *
   * [commit] must be a full or abbreviated hex sha, not a ref: the manifest records shas, and
   * refusing anything else keeps a malformed manifest from steering fetches at an attacker-chosen
   * branch. [path] is likewise rejected unless it stays inside the renders tree.
   */
  fun historicalRenderUrl(repo: String?, commit: String?, path: String?): String? {
    val r = repo?.trim()?.trim('/')?.takeIf { it.isNotEmpty() && it.count { c -> c == '/' } == 1 }
    val c = commit?.trim()?.takeIf { it.matches(Regex("[0-9a-fA-F]{7,40}")) }
    val p = path?.trim()?.takeIf { it.startsWith("renders/") && !it.contains("..") }
    if (r == null || c == null || p == null) return null
    val encoded = p.split('/').joinToString("/") { WebEscaping.urlEncodeSegment(it) }
    return "https://raw.githubusercontent.com/$r/$c/$encoded"
  }

  /**
   * Constant-time token comparison — avoids leaking how many leading characters matched via timing.
   * Both sides are compared as UTF-8 bytes; length mismatches short-circuit safely inside
   * [MessageDigest.isEqual] (which is itself constant-time for equal-length inputs).
   */
  fun tokensMatch(expected: String, provided: String?): Boolean {
    if (provided == null) return false
    return MessageDigest.isEqual(
      expected.toByteArray(Charsets.UTF_8),
      provided.toByteArray(Charsets.UTF_8),
    )
  }

  /**
   * Site-local IPv4 addresses on up, non-loopback interfaces, for the "Network:" banner line. Best
   * effort: returns empty when enumeration fails or nothing qualifies (e.g. loopback-only host).
   */
  fun siteLocalIpv4Addresses(): List<String> =
    try {
      NetworkInterface.getNetworkInterfaces()
        .asSequence()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual }
        .flatMap { it.inetAddresses.asSequence() }
        .filterIsInstance<Inet4Address>()
        .filter { it.isSiteLocalAddress }
        .map { it.hostAddress }
        .distinct()
        .toList()
    } catch (_: Exception) {
      emptyList()
    }
}
