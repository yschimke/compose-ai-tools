package ee.schimke.composeai.cli

import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.io.TemporaryDirectory
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.copyTo
import java.io.File
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okio.buffer

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
    val tempPath = TemporaryDirectory / "compose-preview-bundle-${UUID.randomUUID()}.png"
    val temp = tempPath.toFile().apply { deleteOnExit() }
    // Ktor client over the OkHttp engine. OkHttp follows redirects by default; we stream the body
    // to disk (via an Okio sink) and only keep the file on a 2xx. `runBlocking` is fine here — this
    // is a one-shot CLI / startup call, not a hot path.
    try {
      HttpClient(OkHttp).use { client ->
        runBlocking {
          client.prepareGet(uri.toString()).execute { response ->
            if (!response.status.isSuccess()) {
              throw IllegalArgumentException(
                "could not download bundle from $arg: HTTP ${response.status.value}"
              )
            }
            SystemFileSystem.sink(tempPath).buffer().use { sink ->
              response.bodyAsChannel().copyTo(sink.outputStream())
            }
          }
        }
      }
    } catch (e: IllegalArgumentException) {
      SystemFileSystem.delete(tempPath, mustExist = false)
      throw e
    } catch (e: Exception) {
      SystemFileSystem.delete(tempPath, mustExist = false)
      throw IllegalArgumentException("could not download bundle from $arg: ${e.message}")
    }
    require((SystemFileSystem.metadataOrNull(tempPath)?.size ?: 0L) > 0L) {
      "downloaded an empty bundle from $arg"
    }
    return temp
  }
}
