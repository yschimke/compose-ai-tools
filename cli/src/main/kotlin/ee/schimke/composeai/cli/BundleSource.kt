package ee.schimke.composeai.cli

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files

/**
 * Resolves a bundle argument — a local path **or** a URL — to a local [File] every bundle-open
 * command can operate on. A bundle is a PNG+ZIP polyglot, so once it's on disk the rest of the
 * pipeline ([BundleReader], [BundleRenderer], `bundle daemon`, the viewer) is unchanged.
 *
 * - `http(s)://…` — downloaded to a temp file (follows redirects; fails loudly on a non-2xx).
 * - `file://…` — the referenced local file.
 * - anything else — treated as a local filesystem path.
 *
 * Downloaded temp files are marked delete-on-exit; callers that want eager cleanup can delete the
 * returned file themselves once done. The `.png` suffix is preserved so downstream name-derivation
 * (`<name>-render`, `<name>-extracted`, the daemon's `bundleSource` tag) stays sensible.
 */
object BundleSource {

  /**
   * True when [arg] is an http/https/file URL we should resolve as a URL rather than a raw path.
   * Matches a `<scheme>:` prefix (so `file:/x`, `file:///x`, and `https://…` all count) while
   * leaving Windows drive letters (`C:\…`) and bare paths alone.
   */
  fun looksLikeUrl(arg: String): Boolean {
    val scheme = arg.substringBefore(':', missingDelimiterValue = "").lowercase()
    return scheme == "http" || scheme == "https" || scheme == "file"
  }

  /**
   * Resolve [arg] to a readable local file, downloading if it's a URL. Throws
   * [IllegalArgumentException] when a path doesn't exist or a download fails — callers map that to
   * their usual "not a file" exit.
   */
  fun resolveToFile(arg: String): File {
    if (!looksLikeUrl(arg)) {
      val f = File(arg)
      require(f.isFile) { "not a file: $arg" }
      return f
    }
    val uri =
      try {
        URI(arg)
      } catch (e: Exception) {
        throw IllegalArgumentException("not a valid URL: $arg (${e.message})")
      }
    if (uri.scheme.equals("file", ignoreCase = true)) {
      val f = File(uri)
      require(f.isFile) { "not a file: $arg" }
      return f
    }
    return download(uri, arg)
  }

  private fun download(uri: URI, arg: String): File {
    // Bundles are always PNG+ZIP polyglots, so a `.png` temp suffix keeps downstream
    // name-derivation (`<name>-render`, the daemon's bundleSource tag) sensible.
    val temp = Files.createTempFile("compose-preview-bundle-", ".png").toFile()
    temp.deleteOnExit()
    val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
    val request = HttpRequest.newBuilder(uri).GET().build()
    val response =
      try {
        client.send(request, HttpResponse.BodyHandlers.ofFile(temp.toPath()))
      } catch (e: Exception) {
        temp.delete()
        throw IllegalArgumentException("could not download bundle from $arg: ${e.message}")
      }
    if (response.statusCode() !in 200..299) {
      temp.delete()
      throw IllegalArgumentException(
        "could not download bundle from $arg: HTTP ${response.statusCode()}"
      )
    }
    require(temp.length() > 0) { "downloaded an empty bundle from $arg" }
    return temp
  }
}
