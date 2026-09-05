package ee.schimke.composeai.buildhost

import kotlinx.serialization.json.Json

/**
 * Reads and writes the framed form, so neither end hand-rolls it.
 *
 * A shared codec rather than a documented convention because there are two implementations in two
 * repositories on two release cadences, and a framing they each re-derive is a framing that
 * eventually disagrees.
 */
public object BuildHostCodec {

  /**
   * The one configuration both ends use.
   *
   * `ignoreUnknownKeys` so a host from a newer release can add a field without breaking an older
   * server *within* a protocol version — the version bump is for changes that alter meaning, and
   * making every additive field a breaking change would mean nobody ever adds one.
   *
   * `encodeDefaults` because a defaulted field that is simply absent reads as "unset" to anything
   * inspecting the wire — a person debugging the pipe, or a future non-Kotlin implementation with
   * different defaults. Writing it costs a few bytes on a channel that carries build output.
   *
   * `explicitNulls = false` so an absent envelope slot is omitted rather than written as `null`:
   * three of the four fields are null in every message, and this is a line-per-message protocol a
   * human is expected to be able to read.
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
