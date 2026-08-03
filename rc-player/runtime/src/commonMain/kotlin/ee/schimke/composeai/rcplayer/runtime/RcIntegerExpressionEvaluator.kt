package ee.schimke.composeai.rcplayer.runtime

import ee.schimke.composeai.rcplayer.protocol.RcIntegerExpression
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Platform-neutral implementation of AndroidX alpha16's integer RPN evaluator. */
public object RcIntegerExpressionEvaluator {
  public fun evaluate(expression: RcIntegerExpression, resolveId: (Int) -> Int): Int {
    val stack = IntArray(expression.values.size)
    var top = -1
    expression.values.forEachIndexed { index, token ->
      if (!expression.isMarked(index) || token < RcIntegerExpression.OFFSET) {
        stack[++top] =
          if (expression.isMarked(index) && token < RcIntegerExpression.OFFSET) resolveId(token)
          else token
        return@forEachIndexed
      }

      fun unary(transform: (Int) -> Int) {
        require(top >= 0) { "Integer expression underflow at $index" }
        stack[top] = transform(stack[top])
      }

      fun binary(transform: (Int, Int) -> Int) {
        require(top >= 1) { "Integer expression underflow at $index" }
        stack[top - 1] = transform(stack[top - 1], stack[top])
        top--
      }

      when (token) {
        RcIntegerExpression.ADD -> binary(Int::plus)
        RcIntegerExpression.SUB -> binary(Int::minus)
        RcIntegerExpression.MUL -> binary(Int::times)
        RcIntegerExpression.DIV -> binary { left, right -> if (right == 0) 0 else left / right }
        RcIntegerExpression.MOD -> binary { left, right -> if (right == 0) 0 else left % right }
        RcIntegerExpression.SHL -> binary { left, right -> left shl right }
        RcIntegerExpression.SHR -> binary { left, right -> left shr right }
        RcIntegerExpression.USHR -> binary { left, right -> left ushr right }
        RcIntegerExpression.OR -> binary { left, right -> left or right }
        RcIntegerExpression.AND -> binary { left, right -> left and right }
        RcIntegerExpression.XOR -> binary { left, right -> left xor right }
        RcIntegerExpression.COPY_SIGN ->
          binary { magnitude, sign -> (magnitude xor (sign shr 31)) - (sign shr 31) }
        RcIntegerExpression.MIN -> binary(::min)
        RcIntegerExpression.MAX -> binary(::max)
        RcIntegerExpression.NEG -> unary(Int::unaryMinus)
        RcIntegerExpression.ABS -> unary(::abs)
        RcIntegerExpression.INCR -> unary { it + 1 }
        RcIntegerExpression.DECR -> unary { it - 1 }
        RcIntegerExpression.NOT -> unary(Int::inv)
        RcIntegerExpression.SIGN -> unary { (it shr 31) or ((-it) ushr 31) }
        RcIntegerExpression.CLAMP -> {
          require(top >= 2) { "Integer expression underflow at $index" }
          stack[top - 2] = min(max(stack[top - 2], stack[top]), stack[top - 1])
          top -= 2
        }
        RcIntegerExpression.IFELSE -> {
          require(top >= 2) { "Integer expression underflow at $index" }
          stack[top - 2] = if (stack[top] > 0) stack[top - 1] else stack[top - 2]
          top -= 2
        }
        RcIntegerExpression.MAD -> {
          require(top >= 2) { "Integer expression underflow at $index" }
          stack[top - 2] = stack[top] + stack[top - 1] * stack[top - 2]
          top -= 2
        }
        in RcIntegerExpression.VAR1..RcIntegerExpression.VAR3 ->
          error(
            "IntegerExpression variable token ${token - RcIntegerExpression.OFFSET} has no arguments"
          )
        else -> error("Unknown AndroidX integer-expression token $token")
      }
    }
    require(top >= 0) { "Integer expression produced no value" }
    return stack[top]
  }

  public fun validationError(expression: RcIntegerExpression): String? {
    var depth = 0
    expression.values.forEachIndexed { index, token ->
      if (!expression.isMarked(index) || token < RcIntegerExpression.OFFSET) {
        depth++
        return@forEachIndexed
      }
      val arity =
        when (token) {
          in RcIntegerExpression.ADD..RcIntegerExpression.MAX -> 2
          in RcIntegerExpression.NEG..RcIntegerExpression.SIGN -> 1
          in RcIntegerExpression.CLAMP..RcIntegerExpression.MAD -> 3
          in RcIntegerExpression.VAR1..RcIntegerExpression.VAR3 ->
            return "variable token ${token - RcIntegerExpression.OFFSET} has no standalone arguments"
          else -> return "unknown token $token"
        }
      if (depth < arity) return "stack underflow at value $index"
      depth = depth - arity + 1
    }
    return if (depth == 0) "expression produces no value" else null
  }
}
