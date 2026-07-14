package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.overrides.RESOURCE_OVERRIDE_KEY_PREFIX
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.ResourceReader

/**
 * Resolves a resource-loaded string to its effective (possibly daemon-seeded) text and records it as
 * an editable knob. The production sink is backed by `PreviewOverrideController.resolveResourceString`;
 * tests supply a fake so [RecordingResourceReader] can be exercised without the controller.
 */
@FunctionalInterface
fun interface ResourceOverrideSink {
  /**
   * Record the resource string [default] as an editable knob under [key] and return the effective
   * value — the seeded replacement bound to [key], or [default] when none is bound.
   */
  fun resolve(key: String, default: String): String
}

/**
 * [ResourceReader] decorator that makes every string a preview loads from resources editable, the
 * counterpart to the pseudolocale connector's `PseudolocalizingResourceReader`. Where that one
 * rewrites the text to its pseudolocalised form, this one hands each decoded string to a
 * [ResourceOverrideSink] — which records it as a `compose/overrides` knob and returns the
 * daemon-seeded replacement (or the original when unseeded) — and re-encodes the effective value.
 *
 * **Why the reader, not an explicit call.** CMP resolves `stringResource(...)` through this byte-level
 * reader; intercepting here means the author doesn't have to wrap the lookup in
 * `previewOverrideString(...)`. A resource read already tells us the exact text that rendered, and
 * `(path, offset)` gives a stable key to seed a replacement against.
 *
 * **Record format** (same as `PseudolocalizingResourceReader`): UTF-8 bytes split on `|`; the last
 * segment is the data. `string` — one Base64 payload; `string-array` — comma-separated Base64;
 * `plurals` — comma-separated `<category>:<Base64>`. Any record without a `|` (fonts, drawables, raw
 * bytes) or of an unrecognised type passes through byte-for-byte.
 *
 * **Key.** `res:<path>#<offset>` for a scalar string; the array index or plural category is appended
 * (`…#<offset>[<i>]`, `…#<offset>:<category>`) so each editable slot round-trips independently. The
 * key is minted identically here at record time and at substitution time (both inside this
 * `readPart`), keeping the `namedOverrides` seed round-trip intact.
 */
@OptIn(ExperimentalResourceApi::class, ExperimentalEncodingApi::class)
class RecordingResourceReader(
  private val delegate: ResourceReader,
  private val sink: ResourceOverrideSink,
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
    val baseKey = "$RESOURCE_OVERRIDE_KEY_PREFIX$path#$offset"
    val transformedData =
      when (type) {
        "string" -> transformSingle(baseKey, data)
        "string-array" -> transformArray(baseKey, data)
        "plurals" -> transformPlurals(baseKey, data)
        // Unrecognised type — leave it untouched rather than risk mangling a non-string payload.
        else -> null
      } ?: return raw
    return (prefix + transformedData).encodeToByteArray()
  }

  override fun getUri(path: String): String = delegate.getUri(path)

  private fun transformSingle(key: String, data: String): String? {
    val decoded = decodeOrNull(data) ?: return null
    val effective = sink.resolve(key, decoded)
    return Base64.encode(effective.encodeToByteArray())
  }

  private fun transformArray(baseKey: String, data: String): String? {
    val parts = data.split(",")
    val encoded =
      parts.mapIndexed { index, item ->
        val decoded = decodeOrNull(item) ?: return null
        val effective = sink.resolve("$baseKey[$index]", decoded)
        Base64.encode(effective.encodeToByteArray())
      }
    return encoded.joinToString(",")
  }

  private fun transformPlurals(baseKey: String, data: String): String? {
    val parts = data.split(",")
    val encoded =
      parts.map { item ->
        val colon = item.indexOf(':')
        if (colon < 0) return null
        val category = item.substring(0, colon)
        val payload = item.substring(colon + 1)
        val decoded = decodeOrNull(payload) ?: return null
        val effective = sink.resolve("$baseKey:$category", decoded)
        "$category:${Base64.encode(effective.encodeToByteArray())}"
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
