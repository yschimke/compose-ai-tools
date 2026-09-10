package ee.schimke.composeai.remotecompose.json

import androidx.compose.remote.core.operations.Header
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What a `.rc` declares about itself, before anything measures it.
 *
 * Deliberately not the inflated document's own geometry. `CoreDocument.getWidth()` / `getHeight()`
 * are the *measured* size and a document that has never been through a layout pass reports `0 x 0`
 * for both — which is what a dump produced outside a player always is. The numbers here come from
 * the [Header] operation, so they are the size the document was authored at, which is the only size
 * available without a player and the one a caller sizing a canvas actually wants.
 *
 * Every field is nullable because every header field except the version triple is optional in the
 * wire format. A `null` here means "the document did not say", not "the document said zero", and
 * those differ: a sticker with no declared `desiredFPS` is not a sticker asking for 0 fps.
 *
 * Each of those also defaults to `null`, which is what makes this type's own JSON round-trip.
 * [toJsonObject] omits a key the document did not declare, and without the defaults the generated
 * decoder treats every nullable parameter as required — so a consumer handed a header with no
 * `width` got a `MissingFieldException` reading back the very shape this codec emits.
 */
@Serializable
public data class RemoteComposeDocumentHeader(
  /** Wire-format version the document was written at, as `major.minor.patch`. */
  public val version: String,
  public val width: Int? = null,
  public val height: Int? = null,
  public val contentDescription: String? = null,
  /**
   * Capability profile bitmask — `512` = `ANDROIDX`, `513` = `EXPERIMENTAL`. A player refuses a
   * document whose profile it does not implement, so this is the first thing to check when a
   * document that plays in one host is blank in another.
   */
  public val profiles: Int? = null,
  /**
   * Named `desiredFPS` on the wire, matching what [toJsonObject] and `rc dump`'s `header` block
   * emit. Without the annotation a consumer serializing this class directly got `desiredFps` while
   * every JSON surface of this codec said `desiredFPS`, so a `jq` query written against a dump
   * missed silently on the other.
   */
  @SerialName("desiredFPS") public val desiredFps: Int? = null,
  /**
   * Screen density at authoring time. Not the playback density — the document is
   * resolution-independent.
   */
  public val densityAtGeneration: Float? = null,
  /** Byte length of the document these fields were read from. */
  public val byteLength: Int,
) {
  public fun toJsonObject(): JsonObject = buildJsonObject {
    put("version", version)
    width?.let { put("width", it) }
    height?.let { put("height", it) }
    contentDescription?.let { put("contentDescription", it) }
    profiles?.let { put("profiles", it) }
    desiredFps?.let { put("desiredFPS", it) }
    // Through the shared float encoder, not `put(String, Float)`. A non-finite density would
    // otherwise reach the JSON tree as a bare `NaN` / `Infinity` token, and `Json.encodeToString`
    // rejects those outright — so a document with a broken density would take the whole dump down
    // with a `JsonEncodingException` from outside the codec's own exception type, i.e. as a stack
    // trace, for a field nothing else in the dump depends on. Same encoding as every other float
    // in the projection, so `densityAtGeneration` reads the same way in `rc dump` and
    // `rc header --json`.
    densityAtGeneration?.let { put("densityAtGeneration", JsonMapSerializer.float(it)) }
    put("byteLength", byteLength)
  }

  public companion object {
    /**
     * Build from an already-decoded [Header] — the one operation a document can be read from
     * without inflating the rest, which is what makes this cheap and what makes it work on a
     * document whose later operations this `remote-core` cannot parse.
     */
    internal operator fun invoke(header: Header, byteLength: Int): RemoteComposeDocumentHeader =
      RemoteComposeDocumentHeader(
        // The version triple is the only thing `Header` exposes solely through `deepToString`, so
        // it is parsed from there. `HEADER v1.1.0` is the shape; anything else means upstream
        // changed the rendering and the version simply goes missing rather than the dump failing.
        version = VERSION.find(header.deepToString(""))?.groupValues?.get(1) ?: "unknown",
        width = header.int(Header.DOC_WIDTH),
        height = header.int(Header.DOC_HEIGHT),
        contentDescription = header.get(Header.DOC_CONTENT_DESCRIPTION) as? String,
        profiles = header.int(Header.DOC_PROFILES),
        desiredFps = header.int(Header.DOC_DESIRED_FPS),
        densityAtGeneration = (header.get(Header.DOC_DENSITY_AT_GENERATION) as? Number)?.toFloat(),
        byteLength = byteLength,
      )

    /**
     * `Header.get` returns the boxed value the writer put in, and the writer's choice of box is not
     * stable across fields — `DOC_WIDTH` arrives as an `Integer` from one path and a `Float` from
     * another (a document authored at a fractional density). Reading through [Number] rather than
     * casting to `Int` is what keeps a `ClassCastException` out of the dump path.
     */
    private fun Header.int(tag: Short): Int? = (get(tag) as? Number)?.toInt()

    private val VERSION = Regex("""HEADER v(\d+\.\d+\.\d+)""")
  }
}
