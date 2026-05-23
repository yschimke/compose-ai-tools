package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.pseudolocale.Pseudolocale
import ee.schimke.composeai.data.pseudolocale.Pseudolocalizer
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.ResourceReader

/**
 * [ResourceReader] decorator that pseudolocalises every string-shaped record on its way out of the
 * binary resource store.
 *
 * **Why we wrap the reader and not the env.** Compose Multiplatform Resources 1.10.x keeps
 * `ComposeEnvironment` and `LocalComposeEnvironment` `internal`, so the swap-friendly path the
 * `androidx.compose.ui.res.stringResource` Android trick uses is not reachable from outside the
 * library module. The `ResourceEnvironment` itself only chooses which qualifier-keyed resource
 * bytes to load — it doesn't transform output. The byte-level interceptor `LocalResourceReader`
 * (public, marked `@ExperimentalResourceApi`) is the one swap point that actually changes what
 * `stringResource(...)` returns.
 *
 * **Record format.** `getStringItem` in `StringResourcesUtils.kt` reads bytes from `readPart(...)`,
 * decodes UTF-8, splits on `|`, takes `recordItems.first()` as the type (`string`, `string-array`,
 * `plurals`) and `recordItems.last()` as the data. Data layouts:
 * - `string` — single Base64-encoded UTF-8 payload.
 * - `string-array` — comma-separated Base64 payloads.
 * - `plurals` — comma-separated `<category>:<Base64>` pairs.
 *
 * We decode each Base64 chunk, run [Pseudolocalizer.transform] over the UTF-8 text, and re-encode
 * with the original `<type>|...|` prefix preserved. Any record that isn't recognised as one of the
 * three string-shaped types — fonts, drawables, raw bytes — passes through untouched.
 *
 * **Cache caveat.** Compose Resources keeps a process-wide `stringItemsCache` keyed by
 * `path/offset-size`, populated by the *first* reader to answer a given key. To stop a prior
 * non-pseudo render from silently no-opping the next pseudo render via stale cache hits,
 * `PseudolocaleOverrideExtensionDesktop.AroundComposable` reflectively clears the cache on every
 * composition entry (see [PseudolocaleResourceCache]). That clear is best-effort: it relies on
 * private CMP internals (`StringResourcesUtilsKt.stringItemsCache` + `AsyncCache.cache`) and will
 * degrade to "first mode wins" if a future CMP version reshapes those internals. Callers that hit
 * the degraded path can still restart the JVM between modes — same fallback that lives here for
 * defence in depth.
 */
@OptIn(ExperimentalResourceApi::class, ExperimentalEncodingApi::class)
internal class PseudolocalizingResourceReader(
  private val delegate: ResourceReader,
  private val mode: Pseudolocale,
) : ResourceReader {

  override suspend fun read(path: String): ByteArray = delegate.read(path)

  override suspend fun readPart(path: String, offset: Long, size: Long): ByteArray {
    val raw = delegate.readPart(path, offset, size)
    val record = raw.decodeToString()
    val pipe = record.indexOf('|')
    // No `|` means this isn't a string record (e.g. raw image bytes). Pass through unchanged.
    if (pipe < 0) return raw
    val type = record.substring(0, pipe)
    val lastPipe = record.lastIndexOf('|')
    val prefix = record.substring(0, lastPipe + 1)
    val data = record.substring(lastPipe + 1)
    val transformedData =
      when (type) {
        "plurals" -> transformPlurals(data)
        "string-array" -> transformArray(data)
        // Any other type — `string` or unrecognised — is treated as a single Base64 chunk.
        else -> transformSingle(data)
      } ?: return raw
    return (prefix + transformedData).encodeToByteArray()
  }

  override fun getUri(path: String): String = delegate.getUri(path)

  private fun transformSingle(data: String): String? {
    val decoded = decodeOrNull(data) ?: return null
    return Base64.encode(Pseudolocalizer.transform(decoded, mode).encodeToByteArray())
  }

  private fun transformArray(data: String): String? {
    val parts = data.split(",")
    val encoded = parts.map { item ->
      val decoded = decodeOrNull(item) ?: return null
      Base64.encode(Pseudolocalizer.transform(decoded, mode).encodeToByteArray())
    }
    return encoded.joinToString(",")
  }

  private fun transformPlurals(data: String): String? {
    val parts = data.split(",")
    val encoded = parts.map { item ->
      val colon = item.indexOf(':')
      if (colon < 0) return null
      val category = item.substring(0, colon)
      val payload = item.substring(colon + 1)
      val decoded = decodeOrNull(payload) ?: return null
      val pseudo = Pseudolocalizer.transform(decoded, mode)
      "$category:${Base64.encode(pseudo.encodeToByteArray())}"
    }
    return encoded.joinToString(",")
  }

  private fun decodeOrNull(base64: String): String? =
    try {
      Base64.decode(base64).decodeToString()
    } catch (_: IllegalArgumentException) {
      null
    }
}
