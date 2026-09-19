package ee.schimke.composeai.remotecompose.json

import androidx.compose.remote.core.operations.Utils
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * A float that may not be finite, encoded the way the rest of this codec encodes one.
 *
 * `Json` rejects a bare `NaN` or `Infinity` token — they are not JSON — so a `Float` property
 * holding one cannot be serialized by the generated serializer at all. That is a real shape here
 * rather than a hypothetical: a non-finite float in a Remote Compose document is usually an
 * *encoded id*, and a document carrying one in its header is readable in every other respect.
 *
 * So a non-finite value travels as a string, exactly as [JsonMapSerializer.float] writes it into
 * the projection: `"Infinity"`, `"-Infinity"`, `"NaN"`, or `"@42"` for an id-bearing NaN. Finite
 * values stay numbers, so the common shape is unchanged and a `jq` query written against a dump
 * reads the same field the same way here.
 *
 * The point of it being a *serializer* rather than a conversion at the call site: `toJsonObject()`
 * already emitted this representation, while the generated serializer for the same public type
 * could not represent the value at all. A consumer handed a `RemoteComposeDocumentHeader` should
 * not have to know which of the two paths produced their JSON.
 */
internal object NonFiniteFloatSerializer : KSerializer<Float> {

  override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor("ee.schimke.composeai.NonFiniteFloat", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: Float) {
    val json =
      encoder as? JsonEncoder
        ?: // Outside JSON there is no string/number ambiguity to resolve and no way to write one:
        // formats that carry a float natively carry a non-finite one too.
        return encoder.encodeFloat(value)
    json.encodeJsonElement(JsonMapSerializer.float(value))
  }

  override fun deserialize(decoder: Decoder): Float {
    val json = decoder as? JsonDecoder ?: return decoder.decodeFloat()
    val primitive = json.decodeJsonElement().jsonPrimitive
    return if (primitive.isString) parse(primitive) else primitive.content.toFloat()
  }

  /** The inverse of [JsonMapSerializer.float]'s string forms. */
  private fun parse(primitive: JsonPrimitive): Float =
    when (val text = primitive.content) {
      "NaN" -> Float.NaN
      "Infinity" -> Float.POSITIVE_INFINITY
      "-Infinity" -> Float.NEGATIVE_INFINITY
      else ->
        if (text.startsWith("@")) {
          val id =
            text.drop(1).toIntOrNull()
              ?: throw SerializationException("not an encoded id: \"$text\"")
          Utils.asNan(id)
        } else {
          text.toFloatOrNull() ?: throw SerializationException("not a float: \"$text\"")
        }
    }
}
