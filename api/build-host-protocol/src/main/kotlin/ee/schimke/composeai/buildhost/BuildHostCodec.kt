package ee.schimke.composeai.buildhost

import kotlinx.serialization.json.Json

/**
 * Reads and writes the framed form, shared so the two implementations (separate repositories and
 * release cadences) can't drift.
 */
public object BuildHostCodec {

  /**
   * The one configuration both ends use: `ignoreUnknownKeys` so additive fields don't break older
   * peers within a protocol version; `encodeDefaults` so the wire is explicit to readers and other
   * implementations; `explicitNulls = false` so absent envelope slots are omitted, keeping lines
   * readable.
   */
  public val json: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    classDiscriminator = "kind"
  }

  /** One line, no trailing newline — the writer supplies the framing. */
  public fun encode(envelope: BuildHostEnvelope): String = json.encodeToString(envelope)

  /** Parses one framed line. Throws on anything malformed; the caller answers with a failure. */
  public fun decode(line: String): BuildHostEnvelope = json.decodeFromString(line)
}
