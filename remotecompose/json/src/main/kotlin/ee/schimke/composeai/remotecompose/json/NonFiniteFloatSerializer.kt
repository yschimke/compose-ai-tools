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
 * Encodes a possibly non-finite float as [JsonMapSerializer.float] does: finite values as numbers,
 * others as strings (`"Infinity"`, `"-Infinity"`, `"NaN"`, or `"@42"` for an id-bearing NaN).
 * `Json` rejects bare `NaN`/`Infinity`, and non-finite floats are real here (usually encoded ids),
 * so the generated serializer and `toJsonObject()` must agree on this form.
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
