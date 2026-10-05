package ee.schimke.composeai.remotecompose.json

import androidx.compose.remote.core.operations.Header
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What a `.rc` declares about itself in its [Header] operation: the authored size, not the measured
 * one (an unmeasured `CoreDocument` reports `0 x 0`).
 *
 * Optional wire fields are nullable and default to null: null means "not declared", and the
 * defaults let [toJsonObject]'s output (which omits undeclared keys) decode back.
 */
@Serializable
public data class RemoteComposeDocumentHeader(
  /** Wire-format version the document was written at, as `major.minor.patch`. */
  public val version: String,
  public val width: Int? = null,
  public val height: Int? = null,
  public val contentDescription: String? = null,
  /**
   * Capability profile bitmask (`512` = `ANDROIDX`, `513` = `EXPERIMENTAL`). A player refuses a
   * profile it doesn't implement, so check this first when a document plays blank in one host.
   */
  public val profiles: Int? = null,
  /** `desiredFPS` on the wire, matching [toJsonObject] and `rc dump`. */
  @SerialName("desiredFPS") public val desiredFps: Int? = null,
  /**
   * Screen density at authoring time (not playback). [NonFiniteFloatSerializer] keeps a NaN here
   * from failing the whole header.
   */
  @Serializable(with = NonFiniteFloatSerializer::class)
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
    // Through the shared float encoder: a bare NaN / Infinity would make `Json.encodeToString`
    // throw and take the whole dump down.
    densityAtGeneration?.let { put("densityAtGeneration", JsonMapSerializer.float(it)) }
    put("byteLength", byteLength)
  }

  public companion object {
    /** Build from a decoded [Header], which reads even when later operations can't be parsed. */
    internal operator fun invoke(header: Header, byteLength: Int): RemoteComposeDocumentHeader =
      RemoteComposeDocumentHeader(
        // The version is only exposed through `deepToString` (`HEADER v1.1.0`); a format change
        // yields "unknown" rather than failing the dump.
        version = VERSION.find(header.deepToString(""))?.groupValues?.get(1) ?: "unknown",
        width = header.int(Header.DOC_WIDTH),
        height = header.int(Header.DOC_HEIGHT),
        contentDescription = header.get(Header.DOC_CONTENT_DESCRIPTION) as? String,
        profiles = header.int(Header.DOC_PROFILES),
        desiredFps = header.int(Header.DOC_DESIRED_FPS),
        densityAtGeneration = (header.get(Header.DOC_DENSITY_AT_GENERATION) as? Number)?.toFloat(),
        byteLength = byteLength,
      )

    /** Read through [Number]: the writer boxes some fields as `Integer` or `Float` by path. */
    private fun Header.int(tag: Short): Int? = (get(tag) as? Number)?.toInt()

    private val VERSION = Regex("""HEADER v(\d+\.\d+\.\d+)""")
  }
}
