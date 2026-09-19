package androidx.compose.remote.creation.json

import androidx.compose.remote.core.operations.Utils
import androidx.compose.remote.core.operations.utilities.AnimatedFloatExpression
import org.json.JSONException

/** Exact finite Float equality, lowered identically to creation-compose's RemoteFloat.isEqualTo. */
internal object ComposePreviewFloatEquals {
  fun install(parser: RemoteComposeJsonParser) {
    parser.registerComponentParser("floatEquals") { component, _, writer, current ->
      val name = component.getString("name")
      try {
        require(Regex("[A-Za-z_][A-Za-z0-9_]*").matches(name)) { "name must be an identifier" }
        require(component.keySet().all { it in setOf("type", "name", "left", "right") }) {
          "only type, name, left and right are supported"
        }
        require(
          name !in current.mIntegerVariables &&
            name !in current.mVariables &&
            name !in current.mDeferredVariables
        ) {
          "duplicate name '$name'"
        }
        fun operand(key: String): Float {
          val value = component.get(key)
          if (value is Number)
            return value.toFloat().also {
              require(it.isFinite()) { "$key must fit a finite Float" }
            }
          require(value is String && Regex("@[A-Za-z_][A-Za-z0-9_]*").matches(value)) {
            "$key must be a finite number or @float reference"
          }
          val variable = value.drop(1)
          val encoded = current.mVariables[variable]
          require(
            variable !in current.mIntegerVariables &&
              encoded != null &&
              encoded.isNaN() &&
              Utils.idFromNan(encoded) in 1 until 0x10000
          ) {
            "$key: unknown scalar float reference '$value'"
          }
          return encoded
        }
        val left = operand("left")
        val right = operand("right")
        // Do not approximate equality with 1-min(1, abs(a-b)): that aliases adjacent Floats.
        val match =
          writer.floatExpression(
            1f,
            0f,
            right,
            left,
            AnimatedFloatExpression.SUB,
            AnimatedFloatExpression.ABS,
            AnimatedFloatExpression.IFELSE,
          )
        val encoded = writer.integerExpression(0x100000000L + Utils.idFromNan(match))
        current.mIntegerVariables[name] = encoded
        current.mVariables[name] = Utils.asNan(encoded.toInt())
        current.recordVariable(name, encoded.toInt())
      } catch (e: RuntimeException) {
        throw JSONException("${current.contextPathString}: floatEquals '$name': ${e.message}", e)
      }
    }
  }
}
