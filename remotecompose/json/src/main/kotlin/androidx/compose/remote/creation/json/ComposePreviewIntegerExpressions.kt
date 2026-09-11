package androidx.compose.remote.creation.json

import androidx.compose.remote.core.operations.Utils
import androidx.compose.remote.core.operations.utilities.IntegerExpressionEvaluator
import org.json.JSONException

/**
 * Adapter for AndroidX's component-parser registration seam. The package is necessary because the
 * expression compiler and symbol tables exposed by that seam have Java package visibility. This
 * does not replace parser classes or patch wire bytes. Keep it internal and verify against the
 * pinned AndroidX parser whenever that dependency changes.
 */
internal object ComposePreviewIntegerExpressions {
  private val identifier = Regex("[A-Za-z_][A-Za-z0-9_]*")
  private val token = Regex("@[A-Za-z_][A-Za-z0-9_]*|[A-Za-z_][A-Za-z0-9_]*|[0-9]+|[-+*/%(),]")
  private val functions = setOf("abs", "min", "max", "clamp")

  fun install(parser: RemoteComposeJsonParser) {
    parser.registerComponentParser("integerExpression") { component, _, writer, current ->
      val name = component.getString("name")
      try {
        require(identifier.matches(name)) { "name must be an identifier" }
        require(component.keySet().all { it in setOf("type", "name", "value") }) {
          "only type, name and value are supported"
        }
        require(
          name !in current.mIntegerVariables &&
            name !in current.mVariables &&
            name !in current.mDeferredVariables
        ) {
          "duplicate name '$name'"
        }
        val source = component.getString("value")
        validateTokens(source, current)
        val rpn = current.expressionParser.infixToIntegerRpn(source)
        require(rpn.size in 1..32) {
          "expression needs 1–32 operands/operators; split larger expressions into declarations"
        }
        // The integer wire operation carries a 32-bit operand mask. Do not let the upstream writer
        // silently truncate it, or emit a stack program the player cannot evaluate.
        val operands = rpn.map { (it as Number).toLong() }.toLongArray()
        var depth = 0
        for (operand in operands) {
          val arity =
            when (operand) {
              encoded(IntegerExpressionEvaluator.I_NEG),
              encoded(IntegerExpressionEvaluator.I_ABS) -> 1
              encoded(IntegerExpressionEvaluator.I_ADD),
              encoded(IntegerExpressionEvaluator.I_SUB),
              encoded(IntegerExpressionEvaluator.I_MUL),
              encoded(IntegerExpressionEvaluator.I_DIV),
              encoded(IntegerExpressionEvaluator.I_MOD),
              encoded(IntegerExpressionEvaluator.I_MIN),
              encoded(IntegerExpressionEvaluator.I_MAX) -> 2
              encoded(IntegerExpressionEvaluator.I_CLAMP) -> 3
              else -> 0
            }
          require(depth >= arity) { "expression has too few arguments" }
          depth += 1 - arity
        }
        require(depth == 1) { "expression must produce exactly one integer" }
        val encoded = writer.integerExpression(*operands)
        val id = encoded.toInt()
        current.mIntegerVariables[name] = encoded
        current.mVariables[name] = Utils.asNan(id)
        current.recordVariable(name, id)
      } catch (e: RuntimeException) {
        throw JSONException(
          "${current.contextPathString}: integerExpression '$name': ${e.message}",
          e,
        )
      }
    }
  }

  private fun encoded(operation: Int): Long = 0x100000000L + operation

  private class Parentheses(val arity: Int?, var arguments: Int = 1)

  private fun validateTokens(source: String, parser: RemoteComposeJsonParser) {
    val tokens = token.findAll(source).map { it.value }.toList()
    require(tokens.joinToString("") == source.filterNot { it.isWhitespace() }) {
      "unsupported syntax; use integer literals, @integer references, + - * / %, abs, min, max or clamp"
    }
    val parentheses = mutableListOf<Parentheses>()
    var expectOperand = true
    for ((index, part) in tokens.withIndex()) {
      when {
        part == "(" -> {
          require(expectOperand) { "expected an operator before (" }
          val arity =
            when (tokens.getOrNull(index - 1)) {
              "abs" -> 1
              "min",
              "max" -> 2
              "clamp" -> 3
              else -> null
            }
          parentheses += Parentheses(arity)
        }
        part == ")" -> {
          require(parentheses.isNotEmpty()) { "mismatched parentheses" }
          require(!expectOperand) { "expected an operand before )" }
          val group = parentheses.removeAt(parentheses.lastIndex)
          require(group.arity == null || group.arity == group.arguments) {
            "function requires ${group.arity} arguments, got ${group.arguments}"
          }
        }
        part == "," -> {
          require(!expectOperand && parentheses.lastOrNull()?.arity != null) { "unexpected comma" }
          parentheses.last().arguments++
          expectOperand = true
        }
        part in setOf("+", "-", "*", "/", "%") -> {
          require(!expectOperand || part == "-") { "expected an operand before $part" }
          expectOperand = true
        }
        part.startsWith("@") -> {
          require(expectOperand) { "expected an operator before $part" }
          require(part.drop(1) in parser.mIntegerVariables) {
            "unknown integer reference '$part'; declare integers before using them"
          }
          expectOperand = false
        }
        identifier.matches(part) -> {
          require(expectOperand) { "expected an operator before $part" }
          require(part in functions) {
            "unsupported function '$part'; integer references must start with @"
          }
          require(tokens.getOrNull(index + 1) == "(") { "function $part requires parentheses" }
        }
        else -> {
          require(expectOperand) { "expected an operator before $part" }
          expectOperand = false
        }
      }
    }
    require(parentheses.isEmpty()) { "mismatched parentheses" }
    require(!expectOperand) { "expected an operand" }
  }
}
