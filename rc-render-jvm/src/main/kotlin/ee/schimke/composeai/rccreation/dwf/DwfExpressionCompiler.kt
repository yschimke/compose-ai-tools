package ee.schimke.composeai.rccreation.dwf

import ee.schimke.composeai.rccreation.common.RemoteContext
import ee.schimke.composeai.rccreation.common.TextFromFloat
import ee.schimke.composeai.rccreation.compose.state.RemoteBoolean
import ee.schimke.composeai.rccreation.compose.state.RemoteColor
import ee.schimke.composeai.rccreation.compose.state.RemoteFloat
import ee.schimke.composeai.rccreation.compose.state.RemoteInt
import ee.schimke.composeai.rccreation.compose.state.RemoteString
import ee.schimke.composeai.rccreation.compose.state.RemoteStringArray
import ee.schimke.composeai.rccreation.compose.state.abs
import ee.schimke.composeai.rccreation.compose.state.acos
import ee.schimke.composeai.rccreation.compose.state.asin
import ee.schimke.composeai.rccreation.compose.state.atan
import ee.schimke.composeai.rccreation.compose.state.atan2
import ee.schimke.composeai.rccreation.compose.state.cbrt
import ee.schimke.composeai.rccreation.compose.state.ceil
import ee.schimke.composeai.rccreation.compose.state.clamp
import ee.schimke.composeai.rccreation.compose.state.cos
import ee.schimke.composeai.rccreation.compose.state.exp
import ee.schimke.composeai.rccreation.compose.state.floor
import ee.schimke.composeai.rccreation.compose.state.lerp
import ee.schimke.composeai.rccreation.compose.state.ln
import ee.schimke.composeai.rccreation.compose.state.log
import ee.schimke.composeai.rccreation.compose.state.max
import ee.schimke.composeai.rccreation.compose.state.min
import ee.schimke.composeai.rccreation.compose.state.pow
import ee.schimke.composeai.rccreation.compose.state.rf
import ee.schimke.composeai.rccreation.compose.state.round
import ee.schimke.composeai.rccreation.compose.state.rs
import ee.schimke.composeai.rccreation.compose.state.sign
import ee.schimke.composeai.rccreation.compose.state.sin
import ee.schimke.composeai.rccreation.compose.state.sqrt
import ee.schimke.composeai.rccreation.compose.state.tan
import ee.schimke.composeai.rccreation.compose.state.toDeg
import ee.schimke.composeai.rccreation.compose.state.toRad

/**
 * Compiles Watch Face Format (DWF) arithmetic, boolean, string, color, ICU `<TimeText>`, and
 * `<Template>` expressions into live `RemoteFloat` / `RemoteBoolean` / `RemoteString` /
 * `RemoteColor` state graphs.
 */
internal class DwfExpressionCompiler(
  val document: DwfDocument,
  val resources: DwfResourceResolver,
  val options: DwfRenderOptions,
  private val complicationSlotId: Int? = null,
  private val localExpressions: Map<String, String> = emptyMap(),
  private val sharedVariables: DwfSharedVariables =
    DwfSharedVariables(document, resources, options),
) {
  fun withScope(
    complicationSlotId: Int? = this.complicationSlotId,
    localExpressions: Map<String, String> = this.localExpressions,
  ): DwfExpressionCompiler =
    if (
      complicationSlotId == this.complicationSlotId && localExpressions === this.localExpressions
    ) {
      this
    } else {
      DwfExpressionCompiler(
        document = document,
        resources = resources,
        options = options,
        complicationSlotId = complicationSlotId,
        localExpressions =
          if (localExpressions.isEmpty()) this.localExpressions
          else this.localExpressions + localExpressions,
        sharedVariables = sharedVariables,
      )
    }

  val isAmbient: RemoteBoolean
    get() = sharedVariables.isAmbient

  val is24Hour: RemoteBoolean
    get() = sharedVariables.is24Hour

  val accelAngleX: RemoteFloat
    get() = sharedVariables.accelAngleX

  val accelAngleY: RemoteFloat
    get() = sharedVariables.accelAngleY

  internal data class ResolvedWeightedStroke(
    val colors: List<RemoteColor>,
    val weights: List<RemoteFloat>,
    val interpolate: Boolean,
  )

  fun listConfigurationState(configId: String): RemoteInt =
    sharedVariables.listConfigurationState(configId)

  fun listOptionIndex(configId: String, optionId: String): Int =
    sharedVariables.listOptionIndex(configId, optionId)

  fun booleanConfigurationState(configId: String): RemoteBoolean =
    sharedVariables.booleanConfigurationState(configId)

  fun complicationTypeState(slotId: Int): RemoteInt = sharedVariables.complicationTypeState(slotId)

  fun resolveComplicationImageResource(rawResource: String): Pair<String, RemoteBoolean>? =
    sharedVariables.resolveComplicationImageResource(complicationSlotId ?: 0, rawResource)

  fun resolveWeightedStroke(ws: DwfWeightedStroke): ResolvedWeightedStroke =
    sharedVariables.resolveWeightedStroke(complicationSlotId ?: 0, ws, this)

  fun compileFloat(raw: String, fallback: Float = 0f): RemoteFloat {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return fallback.rf
    trimmed.toFloatOrNull()?.let {
      return it.rf
    }
    return runCatching { evaluate(trimmed, mutableSetOf()).toFloat() }.getOrElse { fallback.rf }
  }

  fun compileBoolean(raw: String, fallback: Boolean = false): RemoteBoolean {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return RemoteBoolean(fallback)
    if (trimmed.equals("true", ignoreCase = true)) return RemoteBoolean(true)
    if (trimmed.equals("false", ignoreCase = true)) return RemoteBoolean(false)
    localExpressions[trimmed]?.let { inline ->
      return runCatching { evaluate(inline, mutableSetOf(trimmed)).toBool() }
        .getOrElse { RemoteBoolean(fallback) }
    }
    return runCatching { evaluate(trimmed, mutableSetOf()).toBool() }
      .getOrElse { RemoteBoolean(fallback) }
  }

  fun compileString(raw: String): RemoteString {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return "".rs
    if (trimmed.startsWith("@string/")) {
      return (resources.resolveString(trimmed) ?: trimmed.substringAfter('/')).rs
    }
    return runCatching { evaluate(trimmed, mutableSetOf()).toRemoteString() }
      .getOrElse { trimmed.rs }
  }

  fun compileColor(raw: String, fallbackArgb: Int = 0xFFFFFFFF.toInt()): RemoteColor {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return RemoteColor(fallbackArgb)
    parseDwfColorArgb(trimmed, resources)?.let {
      return RemoteColor(it)
    }
    return runCatching { evaluate(trimmed, mutableSetOf()).toColor(fallbackArgb) }
      .getOrElse { RemoteColor(fallbackArgb) }
  }

  /** Compiles a list of `<Text>` / `<Font>` / `<BitmapFont>` rich text elements into one string. */
  fun compileTextElements(elements: List<DwfTextElement>): RemoteString {
    if (elements.isEmpty()) return "".rs
    val parts = elements.map(::compileTextElement)
    return parts.reduce { acc, next -> acc + next }
  }

  private fun compileTextElement(element: DwfTextElement): RemoteString =
    when (element) {
      is DwfTextElement.Literal -> resolveLiteralString(element.text).rs
      is DwfTextElement.Parameter -> compileString(element.expression)
      is DwfTextElement.Template -> compileTemplate(element.format, element.parameters)
      is DwfTextElement.Upper -> compileTextElements(element.elements).uppercase()
      is DwfTextElement.Lower -> compileTextElements(element.elements).lowercase()
      is DwfTextElement.Span -> compileTextElements(element.elements)
    }

  private fun resolveLiteralString(raw: String): String =
    if (raw.startsWith("@string/")) {
      resources.resolveString(raw) ?: raw.substringAfter('/')
    } else {
      raw
    }

  /**
   * Compiles a `<Template>` printf-style format string (`%s`, `%d`, `%02d`, `%.1f`, `%1$s`, `%%`)
   * with `<Parameter>` expressions.
   */
  fun compileTemplate(rawFormat: String, parameterExpressions: List<String>): RemoteString {
    val format = resolveLiteralString(rawFormat)
    if (parameterExpressions.isEmpty()) return format.replace("%%", "%").rs
    val compiledParams = parameterExpressions.map { expr ->
      runCatching { evaluate(expr.trim(), mutableSetOf()) }
        .getOrElse { EvalValue.Text(expr.rs, expr) }
    }
    var result: RemoteString? = null
    fun appendPart(part: RemoteString) {
      result = result?.let { it + part } ?: part
    }

    var i = 0
    var sequentialIndex = 0
    val literalBuf = StringBuilder()
    fun flushLiteral() {
      if (literalBuf.isNotEmpty()) {
        appendPart(literalBuf.toString().rs)
        literalBuf.clear()
      }
    }

    while (i < format.length) {
      val ch = format[i]
      if (ch != '%' || i + 1 >= format.length) {
        literalBuf.append(ch)
        i++
        continue
      }
      if (format[i + 1] == '%') {
        literalBuf.append('%')
        i += 2
        continue
      }
      flushLiteral()
      i++ // consume '%'

      // Optional positional argument index: e.g. 1$
      var paramIndex: Int? = null
      val posStart = i
      while (i < format.length && format[i].isDigit()) i++
      if (i > posStart && i < format.length && format[i] == '$') {
        paramIndex = (format.substring(posStart, i).toIntOrNull() ?: 1) - 1
        i++
      } else {
        i = posStart
      }

      var zeroPad = false
      var spacePad = false
      while (i < format.length && (format[i] == '0' || format[i] == ' ' || format[i] == '+')) {
        if (format[i] == '0') zeroPad = true
        if (format[i] == ' ') spacePad = true
        i++
      }

      val widthStart = i
      while (i < format.length && format[i].isDigit()) i++
      val width = if (i > widthStart) format.substring(widthStart, i).toIntOrNull() ?: 0 else 0

      var precision: Int? = null
      if (i < format.length && format[i] == '.') {
        i++
        val precStart = i
        while (i < format.length && format[i].isDigit()) i++
        precision = if (i > precStart) format.substring(precStart, i).toIntOrNull() ?: 0 else 0
      }

      val conv = if (i < format.length) format[i++] else 's'
      val chosenIdx = paramIndex ?: sequentialIndex++
      val arg = compiledParams.getOrNull(chosenIdx)
      if (arg == null) continue

      val formatted =
        when (conv) {
          'd',
          'i' -> {
            val padFlag =
              when {
                zeroPad -> TextFromFloat.PAD_PRE_ZERO
                spacePad -> TextFromFloat.PAD_PRE_SPACE
                else -> TextFromFloat.PAD_PRE_NONE
              }
            val before = if (width > 0) width else 10
            floor(arg.toFloat()).toRemoteStringOptions(before, 0, padFlag)
          }
          'f' -> {
            val after = precision ?: 2
            val padFlag =
              when {
                zeroPad -> TextFromFloat.PAD_PRE_ZERO or TextFromFloat.PAD_AFTER_ZERO
                spacePad -> TextFromFloat.PAD_PRE_SPACE or TextFromFloat.PAD_AFTER_ZERO
                else -> TextFromFloat.PAD_PRE_NONE or TextFromFloat.PAD_AFTER_ZERO
              }
            val before = if (width > after + 1) width - after - 1 else 10
            arg.toFloat().toRemoteStringOptions(before, after, padFlag)
          }
          else -> arg.toRemoteString()
        }
      appendPart(formatted)
    }
    flushLiteral()
    return result ?: "".rs
  }

  /**
   * Compiles an ICU `<TimeText format="..." hourFormat="...">` pattern (`HH:mm`, `hh:mm:ss a`,
   * `EEE, MMM d`, etc.) into a live `RemoteString` driven by `RemoteContext.FLOAT_*` clock tokens.
   */
  fun compileTimeTextFormat(format: String, hourFormat: DwfHourFormat): RemoteString {
    if (format.isEmpty()) return "".rs
    var result: RemoteString? = null
    fun appendPart(part: RemoteString) {
      result = result?.let { it + part } ?: part
    }

    val literalBuf = StringBuilder()
    fun flushLiteral() {
      if (literalBuf.isNotEmpty()) {
        appendPart(literalBuf.toString().rs)
        literalBuf.clear()
      }
    }

    var i = 0
    while (i < format.length) {
      val ch = format[i]
      if (ch == '\'') {
        if (i + 1 < format.length && format[i + 1] == '\'') {
          literalBuf.append('\'')
          i += 2
        } else {
          i++
          while (i < format.length) {
            if (format[i] == '\'') {
              if (i + 1 < format.length && format[i + 1] == '\'') {
                literalBuf.append('\'')
                i += 2
              } else {
                i++
                break
              }
            } else {
              literalBuf.append(format[i++])
            }
          }
        }
        continue
      }

      if (ch in TIME_PATTERN_CHARS) {
        flushLiteral()
        val start = i
        while (i < format.length && format[i] == ch) i++
        val count = i - start
        appendPart(compileTimeToken(ch, count, hourFormat))
      } else {
        literalBuf.append(ch)
        i++
      }
    }
    flushLiteral()
    return result ?: "".rs
  }

  private fun compileTimeToken(
    token: Char,
    count: Int,
    hourFormat: DwfHourFormat,
  ): RemoteString {
    val v = sharedVariables
    fun pad(expr: RemoteFloat, minDigits: Int): RemoteString =
      if (minDigits >= 2) {
        expr.toRemoteStringOptions(minDigits, 0, TextFromFloat.PAD_PRE_ZERO)
      } else {
        expr.toRemoteStringOptions(10, 0, TextFromFloat.PAD_PRE_NONE)
      }

    return when (token) {
      'H',
      'h' -> {
        val twentyFour = pad(v.hour0To23, count)
        val twelve = pad(v.hour1To12, count)
        when (hourFormat) {
          DwfHourFormat.TWENTY_FOUR -> twentyFour
          DwfHourFormat.TWELVE -> twelve
          DwfHourFormat.SYNC_TO_DEVICE ->
            if (token == 'H') {
              v.is24Hour.select(twentyFour, twelve)
            } else {
              twelve
            }
        }
      }
      'k' -> pad(v.hour1To24, count)
      'K' -> pad(v.hour0To11, count)
      'm' -> pad(v.minute, count)
      's' -> pad(v.second, count)
      'S' -> {
        val digits = count.coerceIn(1, 3)
        val scaled =
          when (digits) {
            1 -> floor(v.millisecond / 100f)
            2 -> floor(v.millisecond / 10f)
            else -> v.millisecond
          }
        pad(scaled, digits)
      }
      'a' -> v.amPmString
      'y',
      'Y' ->
        if (count == 2) {
          v.yearShort.toRemoteStringOptions(2, 0, TextFromFloat.PAD_PRE_ZERO)
        } else {
          v.year.toRemoteStringOptions(count.coerceAtLeast(4), 0, TextFromFloat.PAD_PRE_ZERO)
        }
      'M',
      'L' ->
        when {
          count >= 4 -> v.monthFull
          count == 3 -> v.monthShort
          else -> pad(v.month, count)
        }
      'd' -> pad(v.day, count)
      'D' -> pad(v.dayOfYear, count)
      'E',
      'c',
      'e' ->
        when {
          count >= 4 -> v.dayOfWeekFull
          else -> v.dayOfWeekShort
        }
      else -> token.toString().repeat(count).rs
    }
  }

  /**
   * Resolves a base property plus its `<Transform>` (`TO` / `BY`) and `<Variant mode="AMBIENT">`
   * overrides into a single `RemoteFloat`.
   */
  fun resolveTransformedFloat(
    base: Float,
    targetName: String,
    transforms: List<DwfTransform>,
    variants: List<DwfVariant>,
  ): RemoteFloat {
    var current: RemoteFloat = base.rf
    for (t in transforms) {
      if (t.target.equals(targetName, ignoreCase = true)) {
        val expr = compileFloat(t.value, 0f)
        current =
          if (t.mode.equals("BY", ignoreCase = true)) {
            current + expr
          } else {
            expr
          }
      }
    }
    for (v in variants) {
      if (
        v.mode.equals("AMBIENT", ignoreCase = true) &&
          v.target.equals(targetName, ignoreCase = true)
      ) {
        val ambientVal = compileFloat(v.value, base)
        current = isAmbient.select(ambientVal, current)
      }
    }
    return current
  }

  /**
   * Resolves a base color plus its `<Transform target="color">` and `<Variant mode="AMBIENT"
   * target="color">` overrides into a single `RemoteColor`.
   */
  fun resolveTransformedColor(
    baseColorRaw: String,
    targetName: String = "color",
    transforms: List<DwfTransform> = emptyList(),
    variants: List<DwfVariant> = emptyList(),
    fallbackArgb: Int = 0xFFFFFFFF.toInt(),
  ): RemoteColor {
    var current = compileColor(baseColorRaw, fallbackArgb)
    for (t in transforms) {
      if (t.target.equals(targetName, ignoreCase = true)) {
        current = compileColor(t.value, fallbackArgb)
      }
    }
    for (v in variants) {
      if (
        v.mode.equals("AMBIENT", ignoreCase = true) &&
          v.target.equals(targetName, ignoreCase = true)
      ) {
        val ambientColor = compileColor(v.value, fallbackArgb)
        current = isAmbient.select(ambientColor, current)
      }
    }
    return current
  }

  private fun evaluate(expression: String, visited: MutableSet<String>): EvalValue {
    localExpressions[expression]?.let { mapped ->
      if (visited.add(expression)) {
        return evaluate(mapped, visited)
      }
    }
    val tokens = tokenize(expression)
    val parser = ExprParser(tokens, visited)
    return parser.parseTernary()
  }

  internal sealed interface EvalValue {
    fun toFloat(): RemoteFloat

    fun toBool(): RemoteBoolean

    fun toRemoteString(): RemoteString

    fun toColor(fallbackArgb: Int): RemoteColor

    data class Num(
      val value: RemoteFloat,
      val isInteger: Boolean = false,
      val enumMap: Map<String, Int>? = null,
      val nullableBelowZero: Boolean = false,
      val sourceTag: String? = null,
    ) : EvalValue {
      override fun toFloat(): RemoteFloat = value

      override fun toBool(): RemoteBoolean = value.isNotEqualTo(0f.rf)

      override fun toRemoteString(): RemoteString {
        value.constantValueOrNull?.let { c ->
          return if (isInteger || c == kotlin.math.floor(c)) {
            c.toInt().toString().rs
          } else {
            c.toString().rs
          }
        }
        return if (isInteger) {
          round(value).toRemoteStringOptions(10, 0, TextFromFloat.PAD_PRE_NONE)
        } else {
          value.toRemoteStringOptions(10, 2, TextFromFloat.PAD_PRE_NONE)
        }
      }

      override fun toColor(fallbackArgb: Int): RemoteColor {
        val constInt = value.constantValueOrNull?.toLong()?.toInt()
        return RemoteColor(constInt ?: fallbackArgb)
      }
    }

    data class Bool(val value: RemoteBoolean) : EvalValue {
      override fun toFloat(): RemoteFloat = value.select(1f.rf, 0f.rf)

      override fun toBool(): RemoteBoolean = value

      override fun toRemoteString(): RemoteString = value.select("true".rs, "false".rs)

      override fun toColor(fallbackArgb: Int): RemoteColor = RemoteColor(fallbackArgb)
    }

    data class Text(val value: RemoteString, val literal: String? = null) : EvalValue {
      override fun toFloat(): RemoteFloat = literal?.toFloatOrNull()?.rf ?: 0f.rf

      override fun toBool(): RemoteBoolean =
        if (literal != null) {
          RemoteBoolean(literal.equals("true", ignoreCase = true) || literal == "1")
        } else {
          value.isNotEmpty
        }

      override fun toRemoteString(): RemoteString = value

      override fun toColor(fallbackArgb: Int): RemoteColor =
        literal?.let { parseDwfColorArgb(it) }?.let(::RemoteColor) ?: RemoteColor(fallbackArgb)
    }

    data class ColorVal(val value: RemoteColor) : EvalValue {
      override fun toFloat(): RemoteFloat = (value.constantValueOrNull ?: -1).toFloat().rf

      override fun toBool(): RemoteBoolean = RemoteBoolean(true)

      override fun toRemoteString(): RemoteString = "".rs

      override fun toColor(fallbackArgb: Int): RemoteColor = value
    }

    data class NullableRef(
      val isPresent: RemoteBoolean,
      val resourceRef: String? = null,
    ) : EvalValue {
      override fun toFloat(): RemoteFloat = isPresent.select(1f.rf, 0f.rf)

      override fun toBool(): RemoteBoolean = isPresent

      override fun toRemoteString(): RemoteString = (resourceRef ?: "").rs

      override fun toColor(fallbackArgb: Int): RemoteColor = RemoteColor(fallbackArgb)
    }

    data object NullVal : EvalValue {
      override fun toFloat(): RemoteFloat = 0f.rf

      override fun toBool(): RemoteBoolean = RemoteBoolean(false)

      override fun toRemoteString(): RemoteString = "".rs

      override fun toColor(fallbackArgb: Int): RemoteColor = RemoteColor(fallbackArgb)
    }
  }

  private sealed interface Token {
    data class NumberLit(val value: Float, val isInt: Boolean) : Token

    data class StringLit(val value: String) : Token

    data class ColorLit(val argb: Int) : Token

    data class SourceRef(val name: String) : Token

    data class Ident(val name: String) : Token

    data class Op(val symbol: String) : Token

    data object LParen : Token

    data object RParen : Token

    data object Comma : Token

    data object Question : Token

    data object Colon : Token

    data object Eof : Token
  }

  private fun tokenize(src: String): List<Token> {
    val out = ArrayList<Token>()
    var i = 0
    val n = src.length
    while (i < n) {
      val ch = src[i]
      when {
        ch.isWhitespace() -> i++
        ch == '[' -> {
          val end = src.indexOf(']', i + 1)
          if (end == -1) {
            out += Token.SourceRef(src.substring(i + 1).trim())
            i = n
          } else {
            out += Token.SourceRef(src.substring(i + 1, end).trim())
            i = end + 1
          }
        }
        ch == '"' || ch == '\'' -> {
          val quote = ch
          i++
          val sb = StringBuilder()
          while (i < n && src[i] != quote) {
            if (src[i] == '\\' && i + 1 < n) {
              i++
              sb.append(src[i++])
            } else {
              sb.append(src[i++])
            }
          }
          if (i < n && src[i] == quote) i++
          out += Token.StringLit(sb.toString())
        }
        ch == '#' -> {
          val start = i
          i++
          while (i < n && (src[i].isDigit() || src[i] in 'a'..'f' || src[i] in 'A'..'F')) i++
          val rawHex = src.substring(start, i)
          val argb = parseDwfColorArgb(rawHex, resources) ?: 0xFFFFFFFF.toInt()
          out += Token.ColorLit(argb)
        }
        ch == '0' && i + 1 < n && (src[i + 1] == 'x' || src[i + 1] == 'X') -> {
          val start = i
          i += 2
          while (i < n && (src[i].isDigit() || src[i] in 'a'..'f' || src[i] in 'A'..'F')) i++
          val rawHex = src.substring(start, i)
          val hexDigits = rawHex.substring(2)
          if (hexDigits.length == 8 || hexDigits.length == 6) {
            out += Token.ColorLit(parseDwfColorArgb(rawHex, resources) ?: 0)
          } else {
            val v = hexDigits.toLongOrNull(16)?.toFloat() ?: 0f
            out += Token.NumberLit(v, isInt = true)
          }
        }
        ch.isDigit() || (ch == '.' && i + 1 < n && src[i + 1].isDigit()) -> {
          val start = i
          var hasDot = false
          while (
            i < n &&
              (src[i].isDigit() || src[i] == '.' || ((src[i] == 'e' || src[i] == 'E') && i > start))
          ) {
            if (src[i] == '.') hasDot = true
            if (
              (src[i] == 'e' || src[i] == 'E') &&
                i + 1 < n &&
                (src[i + 1] == '+' || src[i + 1] == '-')
            ) {
              i++
            }
            i++
          }
          if (i < n && (src[i] == 'f' || src[i] == 'F')) {
            hasDot = true
            i++
          }
          val text = src.substring(start, i).trimEnd('f', 'F')
          out += Token.NumberLit(text.toFloatOrNull() ?: 0f, isInt = !hasDot)
        }
        ch == '(' -> {
          out += Token.LParen
          i++
        }
        ch == ')' -> {
          out += Token.RParen
          i++
        }
        ch == ',' -> {
          out += Token.Comma
          i++
        }
        ch == '?' -> {
          out += Token.Question
          i++
        }
        ch == ':' -> {
          out += Token.Colon
          i++
        }
        i + 1 < n && src.substring(i, i + 2) in TWO_CHAR_OPS -> {
          out += Token.Op(src.substring(i, i + 2))
          i += 2
        }
        ch in ONE_CHAR_OPS -> {
          out += Token.Op(ch.toString())
          i++
        }
        ch == '@' -> {
          val start = i
          i++
          while (i < n && (src[i].isLetterOrDigit() || src[i] in "_/:.")) i++
          val ref = src.substring(start, i)
          if (ref.startsWith("@color/") || ref.startsWith("@android:color/")) {
            out += Token.ColorLit(parseDwfColorArgb(ref, resources) ?: 0xFFFFFFFF.toInt())
          } else if (ref.startsWith("@string/")) {
            out += Token.StringLit(resources.resolveString(ref) ?: ref.substringAfter('/'))
          } else {
            out += Token.StringLit(ref)
          }
        }
        else -> {
          val start = i
          while (i < n && (src[i].isLetterOrDigit() || src[i] == '_' || src[i] == '.')) i++
          if (i == start) i++ else out += Token.Ident(src.substring(start, i))
        }
      }
    }
    out += Token.Eof
    return out
  }

  private inner class ExprParser(
    private val tokens: List<Token>,
    private val visited: MutableSet<String>,
  ) {
    private var pos = 0

    private fun peek(): Token = tokens.getOrElse(pos) { Token.Eof }

    private fun consume(): Token = tokens.getOrElse(pos++) { Token.Eof }

    fun parseTernary(): EvalValue {
      val cond = parseLogicalOr()
      if (peek() is Token.Question) {
        consume()
        val ifTrue = parseTernary()
        if (peek() is Token.Colon) consume()
        val ifFalse = parseTernary()
        val b = cond.toBool()
        return when {
          ifTrue is EvalValue.ColorVal || ifFalse is EvalValue.ColorVal ->
            EvalValue.ColorVal(
              b.select(ifTrue.toColor(0xFFFFFFFF.toInt()), ifFalse.toColor(0xFFFFFFFF.toInt())),
            )
          ifTrue is EvalValue.Text || ifFalse is EvalValue.Text ->
            EvalValue.Text(b.select(ifTrue.toRemoteString(), ifFalse.toRemoteString()))
          ifTrue is EvalValue.Bool && ifFalse is EvalValue.Bool ->
            EvalValue.Bool(b.select(ifTrue.toBool(), ifFalse.toBool()))
          else ->
            EvalValue.Num(
              b.select(ifTrue.toFloat(), ifFalse.toFloat()),
              isInteger =
                (ifTrue as? EvalValue.Num)?.isInteger == true &&
                  (ifFalse as? EvalValue.Num)?.isInteger == true,
            )
        }
      }
      return cond
    }

    private fun parseLogicalOr(): EvalValue {
      var left = parseLogicalAnd()
      while ((peek() as? Token.Op)?.symbol == "||") {
        consume()
        val right = parseLogicalAnd()
        left = EvalValue.Bool(left.toBool().or(right.toBool()))
      }
      return left
    }

    private fun parseLogicalAnd(): EvalValue {
      var left = parseBitwiseOr()
      while ((peek() as? Token.Op)?.symbol == "&&") {
        consume()
        val right = parseBitwiseOr()
        left = EvalValue.Bool(left.toBool().and(right.toBool()))
      }
      return left
    }

    private fun parseBitwiseOr(): EvalValue {
      var left = parseBitwiseXor()
      while ((peek() as? Token.Op)?.symbol == "|") {
        consume()
        val right = parseBitwiseXor()
        val li = left.toFloat().toRemoteInt()
        val ri = right.toFloat().toRemoteInt()
        left = EvalValue.Num((li or ri).toRemoteFloat(), isInteger = true)
      }
      return left
    }

    private fun parseBitwiseXor(): EvalValue {
      var left = parseBitwiseAnd()
      while ((peek() as? Token.Op)?.symbol == "^") {
        consume()
        val right = parseBitwiseAnd()
        val li = left.toFloat().toRemoteInt()
        val ri = right.toFloat().toRemoteInt()
        left = EvalValue.Num((li xor ri).toRemoteFloat(), isInteger = true)
      }
      return left
    }

    private fun parseBitwiseAnd(): EvalValue {
      var left = parseEquality()
      while ((peek() as? Token.Op)?.symbol == "&") {
        consume()
        val right = parseEquality()
        val li = left.toFloat().toRemoteInt()
        val ri = right.toFloat().toRemoteInt()
        left = EvalValue.Num((li and ri).toRemoteFloat(), isInteger = true)
      }
      return left
    }

    private fun parseEquality(): EvalValue {
      var left = parseRelational()
      while (true) {
        val op = (peek() as? Token.Op)?.symbol
        if (op != "==" && op != "!=") break
        consume()
        val right = parseRelational()
        val eq = compareEqual(left, right)
        left = EvalValue.Bool(if (op == "==") eq else eq.not())
      }
      return left
    }

    private fun compareEqual(left: EvalValue, right: EvalValue): RemoteBoolean {
      if (left is EvalValue.NullVal && right is EvalValue.NullVal) {
        return RemoteBoolean(true)
      }
      if (left is EvalValue.NullVal || right is EvalValue.NullVal) {
        val other = if (left is EvalValue.NullVal) right else left
        return when (other) {
          is EvalValue.Text ->
            if (other.literal != null) RemoteBoolean(other.literal.isEmpty())
            else other.value.isEmpty
          is EvalValue.Num ->
            if (other.nullableBelowZero) other.value.isLessThan(0f.rf) else RemoteBoolean(false)
          is EvalValue.NullableRef -> other.isPresent.not()
          else -> RemoteBoolean(false)
        }
      }
      if (
        left is EvalValue.Num &&
          left.enumMap != null &&
          right is EvalValue.Text &&
          right.literal != null
      ) {
        val code = left.enumMap[right.literal] ?: left.enumMap[right.literal.uppercase()] ?: -1
        return left.value.isEqualTo(code.toFloat().rf)
      }
      if (
        right is EvalValue.Num &&
          right.enumMap != null &&
          left is EvalValue.Text &&
          left.literal != null
      ) {
        val code = right.enumMap[left.literal] ?: right.enumMap[left.literal.uppercase()] ?: -1
        return right.value.isEqualTo(code.toFloat().rf)
      }
      if (left is EvalValue.Text && right is EvalValue.Text) {
        if (left.literal != null && right.literal != null) {
          return RemoteBoolean(left.literal == right.literal)
        }
        if (right.literal == "") return left.value.isEmpty
        if (left.literal == "") return right.value.isEmpty
        return left.value.length.isEqualTo(right.value.length)
      }
      if (left is EvalValue.Bool && right is EvalValue.Bool) {
        return left.value.isEqualTo(right.value)
      }
      return left.toFloat().isEqualTo(right.toFloat())
    }

    private fun parseRelational(): EvalValue {
      var left = parseAdditive()
      while (true) {
        val op = (peek() as? Token.Op)?.symbol
        if (op != "<" && op != "<=" && op != ">" && op != ">=") break
        consume()
        val right = parseAdditive()
        val lf = left.toFloat()
        val rfVal = right.toFloat()
        val cmp =
          when (op) {
            "<" -> lf.isLessThan(rfVal)
            "<=" -> lf.isLessThanOrEqualTo(rfVal)
            ">" -> lf.isGreaterThan(rfVal)
            else -> lf.isGreaterThanOrEqualTo(rfVal)
          }
        left = EvalValue.Bool(cmp)
      }
      return left
    }

    private fun parseAdditive(): EvalValue {
      var left = parseMultiplicative()
      while (true) {
        val op = (peek() as? Token.Op)?.symbol
        if (op != "+" && op != "-") break
        consume()
        val right = parseMultiplicative()
        if (op == "+" && (left is EvalValue.Text || right is EvalValue.Text)) {
          left = EvalValue.Text(left.toRemoteString() + right.toRemoteString())
        } else {
          val isInt =
            (left as? EvalValue.Num)?.isInteger == true &&
              (right as? EvalValue.Num)?.isInteger == true
          left =
            EvalValue.Num(
              if (op == "+") left.toFloat() + right.toFloat() else left.toFloat() - right.toFloat(),
              isInteger = isInt,
            )
        }
      }
      return left
    }

    private fun parseMultiplicative(): EvalValue {
      var left = parseUnary()
      while (true) {
        val op = (peek() as? Token.Op)?.symbol
        if (op != "*" && op != "/" && op != "%") break
        consume()
        val right = parseUnary()
        val lf = left.toFloat()
        val rfVal = right.toFloat()
        val isInt =
          op != "/" &&
            (left as? EvalValue.Num)?.isInteger == true &&
            (right as? EvalValue.Num)?.isInteger == true
        left =
          EvalValue.Num(
            when (op) {
              "*" -> lf * rfVal
              "/" -> lf / rfVal
              else -> lf % rfVal
            },
            isInteger = isInt,
          )
      }
      return left
    }

    private fun parseUnary(): EvalValue {
      val op = (peek() as? Token.Op)?.symbol
      if (op == "-") {
        consume()
        val v = parseUnary()
        return EvalValue.Num(-v.toFloat(), isInteger = (v as? EvalValue.Num)?.isInteger == true)
      }
      if (op == "+") {
        consume()
        return parseUnary()
      }
      if (op == "!") {
        consume()
        return EvalValue.Bool(parseUnary().toBool().not())
      }
      return parsePrimary()
    }

    private fun parsePrimary(): EvalValue =
      when (val tok = consume()) {
        is Token.NumberLit -> EvalValue.Num(tok.value.rf, isInteger = tok.isInt)
        is Token.StringLit -> EvalValue.Text(tok.value.rs, tok.value)
        is Token.ColorLit -> EvalValue.ColorVal(RemoteColor(tok.argb))
        is Token.SourceRef -> resolveSourceToken(tok.name)
        is Token.LParen -> {
          val inner = parseTernary()
          if (peek() is Token.RParen) consume()
          inner
        }
        is Token.Ident -> {
          if (peek() is Token.LParen) {
            consume()
            val args = ArrayList<EvalValue>()
            if (peek() !is Token.RParen && peek() !is Token.Eof) {
              args += parseTernary()
              while (peek() is Token.Comma) {
                consume()
                args += parseTernary()
              }
            }
            if (peek() is Token.RParen) consume()
            invokeFunction(tok.name, args)
          } else {
            resolveIdentifier(tok.name)
          }
        }
        else -> EvalValue.Num(0f.rf)
      }

    private fun resolveIdentifier(ident: String): EvalValue {
      when {
        ident.equals("true", ignoreCase = true) -> return EvalValue.Bool(RemoteBoolean(true))
        ident.equals("false", ignoreCase = true) -> return EvalValue.Bool(RemoteBoolean(false))
        ident.equals("null", ignoreCase = true) -> return EvalValue.NullVal
        ident.equals("PI", ignoreCase = true) -> return EvalValue.Num(kotlin.math.PI.toFloat().rf)
        ident.equals("E", ignoreCase = true) -> return EvalValue.Num(kotlin.math.E.toFloat().rf)
        ident.startsWith("COMPLICATION_TYPE_", ignoreCase = true) ->
          return EvalValue.Num(
            DwfVariables.complicationTypeCode(ident).toFloat().rf,
            isInteger = true,
          )
      }
      localExpressions[ident]?.let { expr ->
        if (visited.add(ident)) {
          return evaluate(expr, visited)
        }
      }
      resources.resolveString(ident)?.let { resolved ->
        return EvalValue.Text(resolved.rs, resolved)
      }
      parseDwfColorArgb(ident, resources)?.let {
        return EvalValue.ColorVal(RemoteColor(it))
      }
      return EvalValue.Text(ident.rs, ident)
    }

    private fun invokeFunction(name: String, args: List<EvalValue>): EvalValue {
      fun f(idx: Int, default: Float = 0f): RemoteFloat =
        args.getOrNull(idx)?.toFloat() ?: default.rf
      return when (name) {
        "round" -> EvalValue.Num(round(f(0)), isInteger = true)
        "floor" -> EvalValue.Num(floor(f(0)), isInteger = true)
        "ceil" -> EvalValue.Num(ceil(f(0)), isInteger = true)
        "fract" -> {
          val x = f(0)
          EvalValue.Num(x - floor(x))
        }
        "sin" -> EvalValue.Num(sin(f(0)))
        "cos" -> EvalValue.Num(cos(f(0)))
        "tan" -> EvalValue.Num(tan(f(0)))
        "asin" -> EvalValue.Num(asin(f(0)))
        "acos" -> EvalValue.Num(acos(f(0)))
        "atan" -> EvalValue.Num(atan(f(0)))
        "atan2" -> EvalValue.Num(atan2(f(0), f(1)))
        "torad",
        "degToRad" -> EvalValue.Num(toRad(f(0)))
        "todeg",
        "radToDeg" -> EvalValue.Num(toDeg(f(0)))
        "sqrt" -> EvalValue.Num(sqrt(f(0)))
        "cbrt" -> EvalValue.Num(cbrt(f(0)))
        "pow" -> EvalValue.Num(pow(f(0), f(1, 1f)))
        "exp" -> EvalValue.Num(exp(f(0)))
        "expm1" -> EvalValue.Num(exp(f(0)) - 1f.rf)
        "log" -> EvalValue.Num(ln(f(0)))
        "log10" -> EvalValue.Num(log(f(0)))
        "log2" -> EvalValue.Num(ln(f(0)) / kotlin.math.ln(2.0).toFloat().rf)
        "abs" ->
          EvalValue.Num(
            abs(f(0)),
            isInteger = (args.getOrNull(0) as? EvalValue.Num)?.isInteger == true,
          )
        "sign" -> EvalValue.Num(sign(f(0)), isInteger = true)
        "clamp" -> EvalValue.Num(clamp(f(0), f(1, 0f), f(2, 1f)))
        "min" -> EvalValue.Num(min(f(0), f(1)))
        "max" -> EvalValue.Num(max(f(0), f(1)))
        "lerp" -> EvalValue.Num(lerp(f(0), f(1), f(2)))
        "colorRgb" ->
          EvalValue.ColorVal(
            RemoteColor.rgb(
              red = clamp(f(0) / 255f.rf, 0f.rf, 1f.rf),
              green = clamp(f(1) / 255f.rf, 0f.rf, 1f.rf),
              blue = clamp(f(2) / 255f.rf, 0f.rf, 1f.rf),
              alpha = 1f.rf,
            ),
          )
        "colorArgb" ->
          EvalValue.ColorVal(
            RemoteColor.rgb(
              red = clamp(f(1) / 255f.rf, 0f.rf, 1f.rf),
              green = clamp(f(2) / 255f.rf, 0f.rf, 1f.rf),
              blue = clamp(f(3) / 255f.rf, 0f.rf, 1f.rf),
              alpha = clamp(f(0) / 255f.rf, 0f.rf, 1f.rf),
            ),
          )
        "extractColorFromColors" -> {
          if (args.isEmpty()) return EvalValue.ColorVal(RemoteColor(0xFFFFFFFF.toInt()))
          val colorCount = (args.size - 1).coerceAtLeast(1)
          if (colorCount == 1) return EvalValue.ColorVal(args[0].toColor(0xFFFFFFFF.toInt()))
          val t = clamp(args.last().toFloat(), 0f.rf, 1f.rf)
          val c0 = args[0].toColor(0xFFFFFFFF.toInt())
          val c1 = args[1].toColor(0xFFFFFFFF.toInt())
          EvalValue.ColorVal(lerp(c0, c1, t))
        }
        "numberFormat" -> {
          val pattern = (args.getOrNull(0) as? EvalValue.Text)?.literal ?: "#"
          val num = f(1)
          EvalValue.Text(compileDecimalPattern(pattern, num))
        }
        "icuText",
        "icuBestText" -> {
          val pattern = (args.getOrNull(0) as? EvalValue.Text)?.literal ?: "HH:mm"
          val formattedClock = compileTimeTextFormat(pattern, DwfHourFormat.SYNC_TO_DEVICE)
          val secondTag = (args.getOrNull(1) as? EvalValue.Num)?.sourceTag
          if (secondTag == "WEATHER.LAST_UPDATED") {
            val customLastUpdated = sharedVariables.weatherLastUpdatedText
            EvalValue.Text(customLastUpdated.isNotEmpty.select(customLastUpdated, formattedClock))
          } else {
            EvalValue.Text(formattedClock)
          }
        }
        "subText" -> {
          val str = args.getOrNull(0)?.toRemoteString() ?: "".rs
          val start = f(1, 0f).toRemoteInt()
          val end = args.getOrNull(2)?.toFloat()?.toRemoteInt()
          EvalValue.Text(if (end != null) str.substring(start, end) else str.substring(start))
        }
        "textLength" -> {
          val str = args.getOrNull(0)?.toRemoteString() ?: "".rs
          EvalValue.Num(str.length.toRemoteFloat(), isInteger = true)
        }
        else -> args.firstOrNull() ?: EvalValue.Num(0f.rf)
      }
    }
  }

  private fun compileDecimalPattern(pattern: String, num: RemoteFloat): RemoteString {
    val dotIdx = pattern.indexOf('.')
    val intPart = if (dotIdx >= 0) pattern.substring(0, dotIdx) else pattern
    val fracPart = if (dotIdx >= 0) pattern.substring(dotIdx + 1) else ""
    val grouping = if (',' in intPart) (1 shl 4) else 0
    val minIntZeros = intPart.count { it == '0' }
    val maxFrac = fracPart.count { it == '0' || it == '#' }
    val minFracZeros = fracPart.count { it == '0' }
    val preFlag = if (minIntZeros >= 2) TextFromFloat.PAD_PRE_ZERO else TextFromFloat.PAD_PRE_NONE
    val postFlag =
      if (minFracZeros > 0) TextFromFloat.PAD_AFTER_ZERO else TextFromFloat.PAD_AFTER_NONE
    val before = if (minIntZeros >= 2) minIntZeros else 12
    val value = if (maxFrac == 0) round(num) else num
    return value.toRemoteStringOptions(before, maxFrac, preFlag or postFlag or grouping)
  }

  private fun resolveSourceToken(rawName: String): EvalValue {
    val v = sharedVariables
    val name = rawName.trim()
    if (name.startsWith("CONFIGURATION.", ignoreCase = true)) {
      return v.resolveConfigurationReference(name.substring("CONFIGURATION.".length))
    }
    if (name.startsWith("COMPLICATION.", ignoreCase = true)) {
      val prop = name.substring("COMPLICATION.".length).uppercase()
      val slotId = complicationSlotId ?: 0
      return v.resolveComplicationProperty(slotId, prop)
    }
    if (name.startsWith("WEATHER.", ignoreCase = true)) {
      return v.resolveWeatherProperty(name.substring("WEATHER.".length).uppercase())
    }
    return when (name.uppercase()) {
      "MILLISECOND" -> EvalValue.Num(v.millisecond, isInteger = true)
      "SECOND" -> EvalValue.Num(v.second, isInteger = true)
      "SECOND_Z" -> EvalValue.Text(v.second.toRemoteStringOptions(2, 0, TextFromFloat.PAD_PRE_ZERO))
      "SECOND_MILLISECOND" -> EvalValue.Num(v.secondMillisecond)
      "SECONDS_IN_DAY" -> EvalValue.Num(v.secondsInDay, isInteger = true)
      "MINUTE" -> EvalValue.Num(v.minute, isInteger = true)
      "MINUTE_Z" -> EvalValue.Text(v.minute.toRemoteStringOptions(2, 0, TextFromFloat.PAD_PRE_ZERO))
      "MINUTE_SECOND" -> EvalValue.Num(v.minuteSecond)
      "HOUR_0_11" -> EvalValue.Num(v.hour0To11, isInteger = true)
      "HOUR_0_11_Z" ->
        EvalValue.Text(v.hour0To11.toRemoteStringOptions(2, 0, TextFromFloat.PAD_PRE_ZERO))
      "HOUR_0_11_MINUTE" -> EvalValue.Num(v.hour0To11Minute)
      "HOUR_1_12" -> EvalValue.Num(v.hour1To12, isInteger = true)
      "HOUR_1_12_Z" ->
        EvalValue.Text(v.hour1To12.toRemoteStringOptions(2, 0, TextFromFloat.PAD_PRE_ZERO))
      "HOUR_1_12_MINUTE" -> EvalValue.Num(v.hour1To12 + (v.minute / 60f))
      "HOUR_0_23" -> EvalValue.Num(v.hour0To23, isInteger = true)
      "HOUR_0_23_Z" ->
        EvalValue.Text(v.hour0To23.toRemoteStringOptions(2, 0, TextFromFloat.PAD_PRE_ZERO))
      "HOUR_0_23_MINUTE" -> EvalValue.Num(v.hour0To23Minute)
      "HOUR_1_24" -> EvalValue.Num(v.hour1To24, isInteger = true)
      "HOUR_1_24_Z" ->
        EvalValue.Text(v.hour1To24.toRemoteStringOptions(2, 0, TextFromFloat.PAD_PRE_ZERO))
      "HOUR_1_24_MINUTE" -> EvalValue.Num(v.hour1To24 + (v.minute / 60f))
      "DAY",
      "DAY_1_31" -> EvalValue.Num(v.day, isInteger = true)
      "DAY_Z" -> EvalValue.Text(v.day.toRemoteStringOptions(2, 0, TextFromFloat.PAD_PRE_ZERO))
      "DAY_0_30" -> EvalValue.Num(v.day0To30, isInteger = true)
      "DAYS_IN_MONTH" -> EvalValue.Num(v.daysInMonth, isInteger = true)
      "DAY_OF_YEAR" -> EvalValue.Num(v.dayOfYear, isInteger = true)
      "DAY_OF_WEEK" -> EvalValue.Num(v.dayOfWeek, isInteger = true)
      "DAY_OF_WEEK_F" -> EvalValue.Text(v.dayOfWeekFull)
      "DAY_OF_WEEK_S" -> EvalValue.Text(v.dayOfWeekShort)
      "MONTH",
      "MONTH_1_12" -> EvalValue.Num(v.month, isInteger = true)
      "MONTH_Z" -> EvalValue.Text(v.month.toRemoteStringOptions(2, 0, TextFromFloat.PAD_PRE_ZERO))
      "MONTH_0_11" -> EvalValue.Num(v.month0To11, isInteger = true)
      "MONTH_F" -> EvalValue.Text(v.monthFull)
      "MONTH_S" -> EvalValue.Text(v.monthShort)
      "YEAR" -> EvalValue.Num(v.year, isInteger = true)
      "YEAR_S" ->
        EvalValue.Text(v.yearShort.toRemoteStringOptions(2, 0, TextFromFloat.PAD_PRE_ZERO))
      "AMPM_POSITION" -> EvalValue.Num(v.amPmPosition, isInteger = true)
      "AMPM_STRING",
      "AMPM_STRING_ENG",
      "AMPM_STRING_SHORT" -> EvalValue.Text(v.amPmString)
      "IS_24_HOUR_MODE" -> EvalValue.Bool(v.is24Hour)
      "IS_DST_ON" -> EvalValue.Bool(v.isDst)
      "STATE.IS_AMBIENT",
      "IS_AMBIENT" -> EvalValue.Bool(v.isAmbient)
      "BATTERY_PERCENT" -> EvalValue.Num(v.batteryPercent, isInteger = true)
      "BATTERY_CHARGING_STATUS" -> EvalValue.Bool(v.batteryCharging)
      "BATTERY_IS_LOW" -> EvalValue.Bool(v.batteryIsLow)
      "BATTERY_TEMPERATURE_CELSIUS" -> EvalValue.Num(28f.rf)
      "BATTERY_TEMPERATURE_FAHRENHEIT" -> EvalValue.Num(82.4f.rf)
      "STEP_COUNT" -> EvalValue.Num(v.stepCount.toRemoteFloat(), isInteger = true)
      "STEP_GOAL" -> EvalValue.Num(v.stepGoal, isInteger = true)
      "STEP_PERCENT" -> EvalValue.Num(v.stepPercent, isInteger = true)
      "HEART_RATE",
      "HR" -> EvalValue.Num(v.heartRate, isInteger = true, nullableBelowZero = true)
      "HEART_RATE_Z" ->
        EvalValue.Text(v.heartRate.toRemoteStringOptions(3, 0, TextFromFloat.PAD_PRE_ZERO))
      "CALORIES" -> EvalValue.Num(v.calories, isInteger = true)
      "DISTANCE" -> EvalValue.Num(v.distance)
      "FLOORS" -> EvalValue.Num(v.floors, isInteger = true)
      "UNREAD_NOTIFICATION_COUNT" ->
        EvalValue.Num(v.unreadNotifications.toRemoteFloat(), isInteger = true)
      "ACCELEROMETER_IS_SUPPORTED" -> EvalValue.Bool(RemoteBoolean(true))
      "ACCELEROMETER_ANGLE_X" -> EvalValue.Num(v.accelAngleX)
      "ACCELEROMETER_ANGLE_Y" -> EvalValue.Num(v.accelAngleY)
      "ACCELEROMETER_ANGLE_Z" -> EvalValue.Num(0f.rf)
      "ACCELEROMETER_X" -> EvalValue.Num(sin(toRad(v.accelAngleX)) * 9.81f.rf)
      "ACCELEROMETER_Y" -> EvalValue.Num(sin(toRad(v.accelAngleY)) * 9.81f.rf)
      "ACCELEROMETER_Z" -> EvalValue.Num(9.81f.rf)
      "MOON_PHASE_POSITION" -> EvalValue.Num(((v.day - 1f) / 29.53f) * 28f)
      "MOON_PHASE_TYPE" -> EvalValue.Num(floor(((v.day - 1f) / 29.53f) * 8f) % 8f, isInteger = true)
      "MOON_PHASE_TYPE_STRING" -> EvalValue.Text("Waxing Gibbous".rs, "Waxing Gibbous")
      "LANGUAGE_CODE",
      "LANGUAGE_LOCALE_NAME" -> EvalValue.Text("en_US".rs, "en_US")
      else -> EvalValue.Num(0f.rf)
    }
  }

  internal class DwfSharedVariables(
    private val document: DwfDocument,
    private val resources: DwfResourceResolver,
    private val options: DwfRenderOptions,
  ) {
    val contSec: RemoteFloat by lazy { RemoteFloat(RemoteContext.FLOAT_CONTINUOUS_SEC) }
    val timeInSec: RemoteFloat by lazy { RemoteFloat(RemoteContext.FLOAT_TIME_IN_SEC) }
    val timeInMin: RemoteFloat by lazy { RemoteFloat(RemoteContext.FLOAT_TIME_IN_MIN) }
    val timeInHr: RemoteFloat by lazy { RemoteFloat(RemoteContext.FLOAT_TIME_IN_HR) }

    val second: RemoteFloat by lazy { floor(timeInSec % 60f) }
    val secondMillisecond: RemoteFloat by lazy { contSec % 60f }
    val millisecond: RemoteFloat by lazy { floor((contSec % 1f) * 1000f) }
    val minute: RemoteFloat by lazy { floor(timeInMin % 60f) }
    val minuteSecond: RemoteFloat by lazy { minute + (secondMillisecond / 60f) }
    val hour0To23: RemoteFloat by lazy { floor(timeInHr % 24f) }
    val hour0To11: RemoteFloat by lazy { hour0To23 % 12f }
    val hour1To12: RemoteFloat by lazy { hour0To11.isEqualTo(0f.rf).select(12f.rf, hour0To11) }
    val hour1To24: RemoteFloat by lazy { hour0To23.isEqualTo(0f.rf).select(24f.rf, hour0To23) }
    val hour0To11Minute: RemoteFloat by lazy { hour0To11 + (minute / 60f) }
    val hour0To23Minute: RemoteFloat by lazy { hour0To23 + (minute / 60f) }
    val secondsInDay: RemoteFloat by lazy { (hour0To23 * 3600f) + (minute * 60f) + second }
    val amPmPosition: RemoteFloat by lazy {
      hour0To23.isGreaterThanOrEqualTo(12f.rf).select(1f.rf, 0f.rf)
    }
    val amPmString: RemoteString by lazy {
      hour0To23.isGreaterThanOrEqualTo(12f.rf).select("PM".rs, "AM".rs)
    }

    val day: RemoteFloat by lazy { RemoteFloat(RemoteContext.FLOAT_DAY_OF_MONTH) }
    val day0To30: RemoteFloat by lazy { day - 1f }
    val dayOfYear: RemoteFloat by lazy { RemoteFloat(RemoteContext.FLOAT_DAY_OF_YEAR) }
    val month: RemoteFloat by lazy { RemoteFloat(RemoteContext.FLOAT_CALENDAR_MONTH) }
    val month0To11: RemoteFloat by lazy { month - 1f }
    val year: RemoteFloat by lazy { RemoteFloat(RemoteContext.FLOAT_YEAR) }
    val yearShort: RemoteFloat by lazy { year % 100f }
    val daysInMonth: RemoteFloat by lazy {
      val isFeb = month.isEqualTo(2f.rf)
      val is30 =
        month
          .isEqualTo(4f.rf)
          .or(month.isEqualTo(6f.rf))
          .or(month.isEqualTo(9f.rf))
          .or(month.isEqualTo(11f.rf))
      val isLeap = (year % 4f).isEqualTo(0f.rf)
      isFeb.select(isLeap.select(29f.rf, 28f.rf), is30.select(30f.rf, 31f.rf))
    }
    val dayOfWeek: RemoteFloat by lazy { (RemoteFloat(RemoteContext.FLOAT_WEEK_DAY) % 7f) + 1f }

    val dayOfWeekShort: RemoteString by lazy {
      val arr =
        RemoteStringArray(
          listOf("Sun".rs, "Mon".rs, "Tue".rs, "Wed".rs, "Thu".rs, "Fri".rs, "Sat".rs),
        )
      arr[clamp(dayOfWeek - 1f, 0f, 6f).toRemoteInt()]
    }

    val dayOfWeekFull: RemoteString by lazy {
      val arr =
        RemoteStringArray(
          listOf(
            "Sunday".rs,
            "Monday".rs,
            "Tuesday".rs,
            "Wednesday".rs,
            "Thursday".rs,
            "Friday".rs,
            "Saturday".rs,
          ),
        )
      arr[clamp(dayOfWeek - 1f, 0f, 6f).toRemoteInt()]
    }

    val monthShort: RemoteString by lazy {
      val arr =
        RemoteStringArray(
          listOf(
            "Jan".rs,
            "Feb".rs,
            "Mar".rs,
            "Apr".rs,
            "May".rs,
            "Jun".rs,
            "Jul".rs,
            "Aug".rs,
            "Sep".rs,
            "Oct".rs,
            "Nov".rs,
            "Dec".rs,
          ),
        )
      arr[clamp(month - 1f, 0f, 11f).toRemoteInt()]
    }

    val monthFull: RemoteString by lazy {
      val arr =
        RemoteStringArray(
          listOf(
            "January".rs,
            "February".rs,
            "March".rs,
            "April".rs,
            "May".rs,
            "June".rs,
            "July".rs,
            "August".rs,
            "September".rs,
            "October".rs,
            "November".rs,
            "December".rs,
          ),
        )
      arr[clamp(month - 1f, 0f, 11f).toRemoteInt()]
    }

    val isAmbient: RemoteBoolean by lazy {
      RemoteBoolean.createNamedRemoteBoolean(DwfVariables.IS_AMBIENT, options.isAmbientDefault)
    }
    val is24Hour: RemoteBoolean by lazy {
      RemoteBoolean.createNamedRemoteBoolean(DwfVariables.IS_24_HOUR, options.is24HourDefault)
    }
    val isDst: RemoteBoolean by lazy {
      RemoteBoolean.createNamedRemoteBoolean(DwfVariables.IS_DST, options.isDstDefault)
    }
    val batteryPercent: RemoteFloat by lazy {
      RemoteFloat.createNamedRemoteFloat(
        DwfVariables.BATTERY_PERCENT,
        options.batteryPercentDefault,
      )
    }
    val batteryCharging: RemoteBoolean by lazy {
      RemoteBoolean.createNamedRemoteBoolean(
        DwfVariables.BATTERY_CHARGING,
        options.batteryChargingDefault,
      )
    }
    val batteryIsLow: RemoteBoolean by lazy {
      RemoteBoolean.createNamedRemoteBoolean(
          DwfVariables.BATTERY_IS_LOW,
          options.batteryPercentDefault <= 15f,
        )
        .or(batteryPercent.isLessThanOrEqualTo(15f.rf))
    }
    val unreadNotifications: RemoteInt by lazy {
      RemoteInt.createNamedRemoteInt(
        DwfVariables.UNREAD_NOTIFICATIONS,
        options.unreadNotificationsDefault,
      )
    }
    val stepCount: RemoteInt by lazy {
      RemoteInt.createNamedRemoteInt(DwfVariables.STEP_COUNT, options.stepCountDefault)
    }
    val stepGoal: RemoteFloat by lazy {
      RemoteFloat.createNamedRemoteFloat(DwfVariables.STEP_GOAL, options.stepGoalDefault)
    }
    val stepPercent: RemoteFloat by lazy {
      clamp((stepCount.toRemoteFloat() / max(stepGoal, 1f.rf)) * 100f.rf, 0f.rf, 100f.rf)
    }
    val heartRate: RemoteFloat by lazy {
      RemoteFloat.createNamedRemoteFloat(DwfVariables.HEART_RATE, options.heartRateDefault)
    }
    val calories: RemoteFloat by lazy {
      RemoteFloat.createNamedRemoteFloat(DwfVariables.CALORIES, options.caloriesDefault)
    }
    val distance: RemoteFloat by lazy {
      RemoteFloat.createNamedRemoteFloat(DwfVariables.DISTANCE, options.distanceDefault)
    }
    val floors: RemoteFloat by lazy {
      RemoteFloat.createNamedRemoteFloat(DwfVariables.FLOORS, options.floorsDefault)
    }
    val accelAngleX: RemoteFloat by lazy {
      RemoteFloat.createNamedRemoteFloat(DwfVariables.ACCEL_ANGLE_X, 0f)
    }
    val accelAngleY: RemoteFloat by lazy {
      RemoteFloat.createNamedRemoteFloat(DwfVariables.ACCEL_ANGLE_Y, 0f)
    }
    val weatherIsAvailable: RemoteBoolean by lazy {
      RemoteBoolean.createNamedRemoteBoolean(
        DwfVariables.WEATHER_IS_AVAILABLE,
        options.weatherDefault.isAvailable,
      )
    }
    val weatherIsDay: RemoteBoolean by lazy {
      RemoteBoolean.createNamedRemoteBoolean(
        DwfVariables.WEATHER_IS_DAY,
        options.weatherDefault.isDay,
      )
    }
    val weatherCondition: RemoteInt by lazy {
      RemoteInt.createNamedRemoteInt(
        DwfVariables.WEATHER_CONDITION,
        options.weatherDefault.condition,
      )
    }
    val weatherConditionName: RemoteString by lazy {
      RemoteString.createNamedRemoteString(
        DwfVariables.WEATHER_CONDITION_NAME,
        options.weatherDefault.conditionName,
      )
    }
    val weatherTemperature: RemoteFloat by lazy {
      RemoteFloat.createNamedRemoteFloat(
        DwfVariables.WEATHER_TEMPERATURE,
        options.weatherDefault.temperature,
      )
    }
    val weatherTempUnit: RemoteInt by lazy {
      RemoteInt.createNamedRemoteInt(
        DwfVariables.WEATHER_TEMPERATURE_UNIT,
        options.weatherDefault.temperatureUnit,
      )
    }
    val weatherLastUpdatedText: RemoteString by lazy {
      RemoteString.createNamedRemoteString(
        DwfVariables.WEATHER_LAST_UPDATED_TEXT,
        options.weatherDefault.lastUpdatedText,
      )
    }

    private val activeFlavor: DwfFlavor? =
      options.flavorDefault?.let { fid -> document.flavors.firstOrNull { it.id == fid } }

    private val effectiveConfigOverrides: Map<String, String> =
      if (activeFlavor != null) {
        activeFlavor.configurations + options.configurationDefaults
      } else {
        options.configurationDefaults
      }

    private val listConfigCache = HashMap<String, RemoteInt>()
    private val boolConfigCache = HashMap<String, RemoteBoolean>()
    private val colorThemeCache = HashMap<String, RemoteColor>()
    private val complicationTextCache = HashMap<Int, RemoteString>()
    private val complicationTitleCache = HashMap<Int, RemoteString>()
    private val complicationValueCache = HashMap<Int, RemoteFloat>()
    private val complicationMinCache = HashMap<Int, RemoteFloat>()
    private val complicationMaxCache = HashMap<Int, RemoteFloat>()
    private val complicationTypeCache = HashMap<Int, RemoteInt>()
    private val complicationMonoImgCache = HashMap<Int, RemoteBoolean>()
    private val complicationSmallImgCache = HashMap<Int, RemoteBoolean>()
    private val complicationPhotoImgCache = HashMap<Int, RemoteBoolean>()
    private val weatherBoolCache = HashMap<String, RemoteBoolean>()
    private val weatherIntCache = HashMap<String, RemoteInt>()
    private val weatherFloatCache = HashMap<String, RemoteFloat>()

    private val configsById: Map<String, DwfUserConfiguration> =
      document.userConfigurations.associateBy { it.id }

    private val complicationSlotsById: Map<Int, DwfSceneNode.ComplicationSlot> = buildMap {
      fun scan(nodes: List<DwfSceneNode>) {
        for (node in nodes) {
          when (node) {
            is DwfSceneNode.ComplicationSlot -> put(node.slotId, node)
            is DwfSceneNode.Group -> scan(node.children)
            is DwfSceneNode.Condition -> node.branches.forEach { scan(it.children) }
            is DwfSceneNode.ListConfigurationSwitch -> node.options.forEach { scan(it.children) }
            is DwfSceneNode.BooleanConfigurationSwitch -> {
              scan(node.trueChildren)
              scan(node.falseChildren)
            }
            else -> {}
          }
        }
      }
      scan(document.scene.children)
    }

    fun listOptionIndex(configId: String, optionId: String): Int {
      val cfg = configsById[configId] as? DwfUserConfiguration.ListConfiguration
      val idx = cfg?.options?.indexOfFirst { it.id == optionId } ?: -1
      return if (idx >= 0) idx else optionId.toIntOrNull() ?: 0
    }

    fun listConfigurationState(configId: String): RemoteInt =
      listConfigCache.getOrPut(configId) {
        val cfg = configsById[configId] as? DwfUserConfiguration.ListConfiguration
        val desired = effectiveConfigOverrides[configId] ?: cfg?.defaultValue
        val defaultIdx =
          when {
            desired == null -> 0
            cfg != null ->
              cfg.options.indexOfFirst { it.id == desired }.takeIf { it >= 0 }
                ?: desired.toIntOrNull()
                ?: 0
            else -> desired.toIntOrNull() ?: 0
          }
        RemoteInt.createNamedRemoteInt(DwfVariables.configKey(configId), defaultIdx)
      }

    fun booleanConfigurationState(configId: String): RemoteBoolean =
      boolConfigCache.getOrPut(configId) {
        val cfg = configsById[configId] as? DwfUserConfiguration.BooleanConfiguration
        val overrideRaw = effectiveConfigOverrides[configId]
        val defaultVal =
          overrideRaw?.let { it.equals("true", ignoreCase = true) || it == "1" }
            ?: cfg?.defaultValue
            ?: false
        RemoteBoolean.createNamedRemoteBoolean(DwfVariables.configKey(configId), defaultVal)
      }

    fun resolveConfigurationReference(rest: String): EvalValue {
      val dot = rest.lastIndexOf('.')
      val candidateId = if (dot > 0) rest.substring(0, dot) else rest
      val candidateIdx = if (dot > 0) rest.substring(dot + 1).toIntOrNull() else null
      val cfg = configsById[rest] ?: configsById[candidateId]
      return when (cfg) {
        is DwfUserConfiguration.ColorConfiguration -> {
          val colorIdx = candidateIdx ?: 0
          val chosenOptId = effectiveConfigOverrides[cfg.id] ?: cfg.defaultValue
          val chosenOpt =
            cfg.options.firstOrNull { it.id == chosenOptId } ?: cfg.options.firstOrNull()
          val defaultArgb =
            chosenOpt?.colors?.getOrNull(colorIdx)
              ?: chosenOpt?.colors?.firstOrNull()
              ?: 0xFFFFFFFF.toInt()
          val key = DwfVariables.colorThemeKey(cfg.id, colorIdx)
          val rcColor =
            colorThemeCache.getOrPut(key) { RemoteColor.createNamedRemoteColor(key, defaultArgb) }
          EvalValue.ColorVal(rcColor)
        }
        is DwfUserConfiguration.BooleanConfiguration ->
          EvalValue.Bool(booleanConfigurationState(cfg.id))
        is DwfUserConfiguration.ListConfiguration -> {
          val optionMap = cfg.options.mapIndexed { idx, opt -> opt.id to idx }.toMap()
          EvalValue.Num(
            listConfigurationState(cfg.id).toRemoteFloat(),
            isInteger = true,
            enumMap = optionMap,
          )
        }
        else -> {
          if (candidateIdx != null) {
            val key = DwfVariables.colorThemeKey(candidateId, candidateIdx)
            val rcColor =
              colorThemeCache.getOrPut(key) {
                RemoteColor.createNamedRemoteColor(key, 0xFFFFFFFF.toInt())
              }
            EvalValue.ColorVal(rcColor)
          } else {
            EvalValue.Num(listConfigurationState(rest).toRemoteFloat(), isInteger = true)
          }
        }
      }
    }

    fun resolveWeatherProperty(prop: String): EvalValue {
      val wd = options.weatherDefault
      if (prop.startsWith("HOURS.")) {
        val parts = prop.split('.')
        val hourIdx = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 24) ?: 1
        val sub = parts.drop(2).joinToString(".")
        val hourSeed = wd.hours[hourIdx]
        val hourlyConditions = intArrayOf(14, 8, 8, 2, 6, 14, 1, 1)
        val fallbackCond = hourlyConditions[(hourIdx - 1) % hourlyConditions.size]
        val tempOffset = intArrayOf(0, 1, 2, 1, 0, -1, -2, -3)[(hourIdx - 1) % 8]
        return when (sub) {
          "IS_AVAILABLE" -> {
            val key = DwfVariables.weatherHourAvailableKey(hourIdx)
            val state =
              weatherBoolCache.getOrPut(key) {
                RemoteBoolean.createNamedRemoteBoolean(
                  key,
                  hourSeed?.isAvailable ?: wd.isAvailable,
                )
              }
            EvalValue.Bool(weatherIsAvailable.and(state))
          }
          "IS_DAY" -> {
            val key = DwfVariables.weatherHourIsDayKey(hourIdx)
            val state =
              weatherBoolCache.getOrPut(key) {
                RemoteBoolean.createNamedRemoteBoolean(key, hourSeed?.isDay ?: (hourIdx <= 6))
              }
            EvalValue.Bool(state)
          }
          "CONDITION" -> {
            val key = DwfVariables.weatherHourConditionKey(hourIdx)
            val state =
              weatherIntCache.getOrPut(key) {
                RemoteInt.createNamedRemoteInt(key, hourSeed?.condition ?: fallbackCond)
              }
            EvalValue.Num(state.toRemoteFloat(), isInteger = true)
          }
          "TEMPERATURE" -> {
            if (hourSeed != null) {
              val key = DwfVariables.weatherHourTemperatureKey(hourIdx)
              val state =
                weatherFloatCache.getOrPut(key) {
                  RemoteFloat.createNamedRemoteFloat(key, hourSeed.temperature)
                }
              EvalValue.Num(round(state), isInteger = true)
            } else {
              EvalValue.Num(round(weatherTemperature + tempOffset.toFloat().rf), isInteger = true)
            }
          }
          "UV_INDEX" ->
            EvalValue.Num(
              (hourSeed?.uvIndex?.toFloat() ?: kotlin.math.max(0, 6 - hourIdx).toFloat()).rf,
              isInteger = true,
            )
          "CHANCE_OF_PRECIPITATION" -> {
            val cond = hourSeed?.condition ?: fallbackCond
            val pct = hourSeed?.chanceOfPrecipitation ?: if (cond == 6) 60 else 10
            EvalValue.Num(pct.toFloat().rf, isInteger = true)
          }
          else -> EvalValue.Num(0f.rf, isInteger = true)
        }
      }
      if (prop.startsWith("DAYS.")) {
        val parts = prop.split('.')
        val dayIdx = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 7) ?: 1
        val sub = parts.drop(2).joinToString(".")
        val daySeed = wd.days[dayIdx]
        val dayConditions = intArrayOf(8, 14, 2, 6, 8)
        val fallbackCondDay = dayConditions[(dayIdx - 1) % dayConditions.size]
        return when (sub) {
          "IS_AVAILABLE" -> {
            val key = DwfVariables.weatherDayAvailableKey(dayIdx)
            val state =
              weatherBoolCache.getOrPut(key) {
                RemoteBoolean.createNamedRemoteBoolean(key, daySeed?.isAvailable ?: wd.isAvailable)
              }
            EvalValue.Bool(weatherIsAvailable.and(state))
          }
          "CONDITION_DAY" -> {
            val key = DwfVariables.weatherDayConditionKey(dayIdx)
            val state =
              weatherIntCache.getOrPut(key) {
                RemoteInt.createNamedRemoteInt(key, daySeed?.conditionDay ?: fallbackCondDay)
              }
            EvalValue.Num(state.toRemoteFloat(), isInteger = true)
          }
          "CONDITION_NIGHT" -> {
            val key = DwfVariables.weatherDayNightConditionKey(dayIdx)
            val state =
              weatherIntCache.getOrPut(key) {
                RemoteInt.createNamedRemoteInt(key, daySeed?.conditionNight ?: 1)
              }
            EvalValue.Num(state.toRemoteFloat(), isInteger = true)
          }
          "TEMPERATURE_HIGH" -> {
            if (daySeed != null) {
              val key = DwfVariables.weatherDayTempHighKey(dayIdx)
              val state =
                weatherFloatCache.getOrPut(key) {
                  RemoteFloat.createNamedRemoteFloat(key, daySeed.temperatureHigh)
                }
              EvalValue.Num(round(state), isInteger = true)
            } else {
              EvalValue.Num(
                round(weatherTemperature + (3 - (dayIdx % 2)).toFloat().rf),
                isInteger = true,
              )
            }
          }
          "TEMPERATURE_LOW" -> {
            if (daySeed != null) {
              val key = DwfVariables.weatherDayTempLowKey(dayIdx)
              val state =
                weatherFloatCache.getOrPut(key) {
                  RemoteFloat.createNamedRemoteFloat(key, daySeed.temperatureLow)
                }
              EvalValue.Num(round(state), isInteger = true)
            } else {
              EvalValue.Num(
                round(weatherTemperature - (5 + (dayIdx % 2)).toFloat().rf),
                isInteger = true,
              )
            }
          }
          "CHANCE_OF_PRECIPITATION" -> {
            val key = DwfVariables.weatherDayPrecipKey(dayIdx)
            val defaultPct = daySeed?.chanceOfPrecipitation ?: if (fallbackCondDay == 6) 65 else 15
            val state =
              weatherIntCache.getOrPut(key) { RemoteInt.createNamedRemoteInt(key, defaultPct) }
            EvalValue.Num(state.toRemoteFloat(), isInteger = true)
          }
          "CHANCE_OF_PRECIPITATION_NIGHT" -> {
            val key = DwfVariables.weatherDayNightPrecipKey(dayIdx)
            val defaultPct = daySeed?.chanceOfPrecipitationNight ?: 10
            val state =
              weatherIntCache.getOrPut(key) { RemoteInt.createNamedRemoteInt(key, defaultPct) }
            EvalValue.Num(state.toRemoteFloat(), isInteger = true)
          }
          else -> EvalValue.Num(0f.rf, isInteger = true)
        }
      }
      return when (prop) {
        "IS_AVAILABLE" -> EvalValue.Bool(weatherIsAvailable)
        "IS_DAY" -> EvalValue.Bool(weatherIsDay)
        "CONDITION" -> EvalValue.Num(weatherCondition.toRemoteFloat(), isInteger = true)
        "CONDITION_NAME" -> EvalValue.Text(weatherConditionName)
        "TEMPERATURE" -> EvalValue.Num(round(weatherTemperature), isInteger = true)
        "TEMPERATURE_LOW" ->
          EvalValue.Num(
            wd.temperatureLow?.let { round(it.rf) } ?: round(weatherTemperature - 6f.rf),
            isInteger = true,
          )
        "TEMPERATURE_HIGH" ->
          EvalValue.Num(
            wd.temperatureHigh?.let { round(it.rf) } ?: round(weatherTemperature + 3f.rf),
            isInteger = true,
          )
        "TEMPERATURE_UNIT" -> EvalValue.Num(weatherTempUnit.toRemoteFloat(), isInteger = true)
        "CHANCE_OF_PRECIPITATION" ->
          EvalValue.Num(wd.chanceOfPrecipitation.toFloat().rf, isInteger = true)
        "UV_INDEX" -> EvalValue.Num(wd.uvIndex.toFloat().rf, isInteger = true)
        "LAST_UPDATED" -> EvalValue.Num(0f.rf, isInteger = true, sourceTag = "WEATHER.LAST_UPDATED")
        else -> EvalValue.Num(0f.rf, isInteger = true)
      }
    }

    private fun seedForSlot(slotId: Int): DwfComplicationSeed {
      options.complicationDefaults[slotId]?.let {
        return it
      }
      val slot = complicationSlotsById[slotId]
      val flavorPolicy = activeFlavor?.complications?.get(slotId)
      val effectiveProvider = flavorPolicy?.defaultSystemProvider ?: slot?.defaultSystemProvider
      val effectiveProviderType =
        flavorPolicy?.defaultSystemProviderType ?: slot?.defaultSystemProviderType
      if (effectiveProviderType?.equals("EMPTY", ignoreCase = true) == true) {
        return DwfComplicationSeed(type = "EMPTY", text = "", title = "")
      }
      val imageOnlyTypes = setOf("PHOTO_IMAGE", "SMALL_IMAGE", "MONOCHROMATIC_IMAGE", "EMPTY")
      val allImageOnly =
        slot != null &&
          effectiveProvider == null &&
          slot.supportedTypes.isNotEmpty() &&
          slot.supportedTypes.all { it.uppercase() in imageOnlyTypes }
      if (allImageOnly) {
        return DwfComplicationSeed(type = "EMPTY", text = "", title = "")
      }
      val preferredType =
        effectiveProviderType?.takeIf { dt ->
          slot == null ||
            slot.complications.isEmpty() ||
            slot.complications.any { it.type.equals(dt, ignoreCase = true) }
        }
          ?: slot
            ?.complications
            ?.firstOrNull {
              val u = it.type.uppercase()
              u != "EMPTY" && u !in imageOnlyTypes
            }
            ?.type
          ?: slot?.complications?.firstOrNull { !it.type.equals("EMPTY", ignoreCase = true) }?.type
          ?: slot?.complications?.firstOrNull()?.type
          ?: slot?.supportedTypes?.firstOrNull()
          ?: "SHORT_TEXT"
      val provider = effectiveProvider?.uppercase().orEmpty()
      val safeHr = if (options.heartRateDefault >= 0f) options.heartRateDefault else 72f
      return when {
        "STEP" in provider ->
          DwfComplicationSeed(
            type = preferredType,
            text = options.stepCountDefault.toString(),
            title = "STEPS",
            value = options.stepCountDefault.toFloat(),
            min = 0f,
            max = options.stepGoalDefault,
          )
        "BATTERY" in provider ->
          DwfComplicationSeed(
            type = preferredType,
            text = "${options.batteryPercentDefault.toInt()}%",
            title = "BATT",
            value = options.batteryPercentDefault,
            min = 0f,
            max = 100f,
          )
        "DATE" in provider || "DAY" in provider ->
          DwfComplicationSeed(
            type = preferredType,
            text = "18",
            title = "WED",
            value = 18f,
            min = 1f,
            max = 31f,
          )
        "SUNRISE" in provider || "SUNSET" in provider ->
          DwfComplicationSeed(
            type = preferredType,
            text = "06:42",
            title = "SUNRISE",
            value = 6.7f,
            min = 0f,
            max = 24f,
          )
        "NOTIFICATION" in provider ->
          DwfComplicationSeed(
            type = preferredType,
            text = "${options.unreadNotificationsDefault}",
            title = "ALERTS",
            value = options.unreadNotificationsDefault.toFloat(),
            min = 0f,
            max = 10f,
          )
        preferredType.equals("LONG_TEXT", ignoreCase = true) ->
          DwfComplicationSeed(
            type = preferredType,
            text = "Design Sync 10:30",
            title = "Calendar",
            value = 50f,
            min = 0f,
            max = 100f,
          )
        slotId % 3 == 1 ->
          DwfComplicationSeed(
            type = preferredType,
            text = options.stepCountDefault.toString(),
            title = "STEPS",
            value = options.stepCountDefault.toFloat(),
            min = 0f,
            max = options.stepGoalDefault,
          )
        slotId % 3 == 2 ->
          DwfComplicationSeed(
            type = preferredType,
            text = "${options.batteryPercentDefault.toInt()}%",
            title = "BATT",
            value = options.batteryPercentDefault,
            min = 0f,
            max = 100f,
          )
        else ->
          DwfComplicationSeed(
            type = preferredType,
            text = "${safeHr.toInt()}",
            title = "BPM",
            value = safeHr,
            min = 0f,
            max = 200f,
          )
      }
    }

    fun complicationTypeState(slotId: Int): RemoteInt =
      complicationTypeCache.getOrPut(slotId) {
        val seed = seedForSlot(slotId)
        RemoteInt.createNamedRemoteInt(
          DwfVariables.complicationTypeKey(slotId),
          DwfVariables.complicationTypeCode(seed.type),
        )
      }

    private fun hasMonoImageState(slotId: Int, seed: DwfComplicationSeed): RemoteBoolean =
      complicationMonoImgCache.getOrPut(slotId) {
        RemoteBoolean.createNamedRemoteBoolean(
          DwfVariables.complicationHasMonoImageKey(slotId),
          !seed.monochromaticImage.isNullOrBlank(),
        )
      }

    private fun hasSmallImageState(slotId: Int, seed: DwfComplicationSeed): RemoteBoolean =
      complicationSmallImgCache.getOrPut(slotId) {
        RemoteBoolean.createNamedRemoteBoolean(
          DwfVariables.complicationHasSmallImageKey(slotId),
          !seed.smallImage.isNullOrBlank(),
        )
      }

    private fun hasPhotoImageState(slotId: Int, seed: DwfComplicationSeed): RemoteBoolean =
      complicationPhotoImgCache.getOrPut(slotId) {
        RemoteBoolean.createNamedRemoteBoolean(
          DwfVariables.complicationHasPhotoImageKey(slotId),
          !seed.photoImage.isNullOrBlank(),
        )
      }

    fun resolveComplicationImageResource(
      slotId: Int,
      rawResource: String,
    ): Pair<String, RemoteBoolean>? {
      val trimmed = rawResource.trim()
      if (!trimmed.startsWith("[COMPLICATION.", ignoreCase = true) || !trimmed.endsWith("]")) {
        return null
      }
      val prop = trimmed.substring("[COMPLICATION.".length, trimmed.length - 1).uppercase()
      val seed = seedForSlot(slotId)
      return when (prop) {
        "MONOCHROMATIC_IMAGE",
        "MONOCHROMATIC_IMAGE_AMBIENT" -> {
          val ref = seed.monochromaticImage ?: return null
          ref to hasMonoImageState(slotId, seed)
        }
        "SMALL_IMAGE",
        "SMALL_IMAGE_AMBIENT" -> {
          val ref = seed.smallImage ?: return null
          ref to hasSmallImageState(slotId, seed)
        }
        "PHOTO_IMAGE",
        "PHOTO_IMAGE_AMBIENT" -> {
          val ref = seed.photoImage ?: return null
          ref to hasPhotoImageState(slotId, seed)
        }
        else -> null
      }
    }

    fun resolveWeightedStroke(
      slotId: Int,
      ws: DwfWeightedStroke,
      compiler: DwfExpressionCompiler,
    ): ResolvedWeightedStroke {
      val seed = seedForSlot(slotId)
      val colorsTrimmed = ws.colors.trim()
      val colors: List<RemoteColor> =
        when (colorsTrimmed.uppercase()) {
          "[COMPLICATION.RANGED_VALUE_COLORS]" -> seed.rangedValueColors.map(::RemoteColor)
          "[COMPLICATION.GOAL_PROGRESS_COLORS]" -> seed.goalProgressColors.map(::RemoteColor)
          "[COMPLICATION.WEIGHTED_ELEMENTS_COLORS]" ->
            seed.weightedElementsColors.map(::RemoteColor)
          else ->
            colorsTrimmed
              .split(Regex("\\s+"))
              .filter { it.isNotEmpty() }
              .map { compiler.compileColor(it) }
        }
      val weightsTrimmed = ws.weights?.trim().orEmpty()
      val weights: List<RemoteFloat> =
        when {
          weightsTrimmed.isEmpty() -> List(colors.size) { 1f.rf }
          weightsTrimmed.equals("[COMPLICATION.WEIGHTED_ELEMENTS_WEIGHTS]", ignoreCase = true) ->
            if (seed.weightedElementsWeights.isNotEmpty()) {
              seed.weightedElementsWeights.map { it.rf }
            } else {
              List(colors.size) { 1f.rf }
            }
          else ->
            weightsTrimmed
              .split(Regex("\\s+"))
              .filter { it.isNotEmpty() }
              .map { compiler.compileFloat(it, 1f) }
        }
      val interpTrimmed = ws.interpolate?.trim().orEmpty()
      val interpolate =
        when (interpTrimmed.uppercase()) {
          "[COMPLICATION.RANGED_VALUE_COLORS_INTERPOLATE]" -> seed.rangedValueColorsInterpolate
          "[COMPLICATION.GOAL_PROGRESS_COLORS_INTERPOLATE]" -> seed.goalProgressColorsInterpolate
          "TRUE",
          "1" -> true
          else -> false
        }
      return ResolvedWeightedStroke(colors = colors, weights = weights, interpolate = interpolate)
    }

    fun resolveComplicationProperty(slotId: Int, prop: String): EvalValue {
      val seed = seedForSlot(slotId)
      return when (prop) {
        "TEXT" ->
          EvalValue.Text(
            complicationTextCache.getOrPut(slotId) {
              RemoteString.createNamedRemoteString(
                DwfVariables.complicationTextKey(slotId),
                seed.text,
              )
            },
          )
        "TITLE" ->
          EvalValue.Text(
            complicationTitleCache.getOrPut(slotId) {
              RemoteString.createNamedRemoteString(
                DwfVariables.complicationTitleKey(slotId),
                seed.title,
              )
            },
          )
        "RANGED_VALUE_VALUE",
        "GOAL_PROGRESS_VALUE" ->
          EvalValue.Num(
            complicationValueCache.getOrPut(slotId) {
              RemoteFloat.createNamedRemoteFloat(
                DwfVariables.complicationValueKey(slotId),
                seed.value,
              )
            },
          )
        "RANGED_VALUE_MIN" ->
          EvalValue.Num(
            complicationMinCache.getOrPut(slotId) {
              RemoteFloat.createNamedRemoteFloat(DwfVariables.complicationMinKey(slotId), seed.min)
            },
          )
        "RANGED_VALUE_MAX",
        "GOAL_PROGRESS_TARGET_VALUE" ->
          EvalValue.Num(
            complicationMaxCache.getOrPut(slotId) {
              RemoteFloat.createNamedRemoteFloat(DwfVariables.complicationMaxKey(slotId), seed.max)
            },
          )
        "TYPE" ->
          EvalValue.Num(
            complicationTypeState(slotId).toRemoteFloat(),
            isInteger = true,
            enumMap = COMPLICATION_TYPE_MAP,
          )
        "MONOCHROMATIC_IMAGE",
        "MONOCHROMATIC_IMAGE_AMBIENT" ->
          EvalValue.NullableRef(hasMonoImageState(slotId, seed), seed.monochromaticImage)
        "SMALL_IMAGE",
        "SMALL_IMAGE_AMBIENT" ->
          EvalValue.NullableRef(hasSmallImageState(slotId, seed), seed.smallImage)
        "PHOTO_IMAGE",
        "PHOTO_IMAGE_AMBIENT" ->
          EvalValue.NullableRef(hasPhotoImageState(slotId, seed), seed.photoImage)
        "RANGED_VALUE_COLORS" ->
          EvalValue.NullableRef(RemoteBoolean(seed.rangedValueColors.isNotEmpty()))
        "RANGED_VALUE_COLORS_INTERPOLATE" ->
          EvalValue.Bool(RemoteBoolean(seed.rangedValueColorsInterpolate))
        "GOAL_PROGRESS_COLORS" ->
          EvalValue.NullableRef(RemoteBoolean(seed.goalProgressColors.isNotEmpty()))
        "GOAL_PROGRESS_COLORS_INTERPOLATE" ->
          EvalValue.Bool(RemoteBoolean(seed.goalProgressColorsInterpolate))
        "WEIGHTED_ELEMENTS_COLORS" ->
          EvalValue.NullableRef(RemoteBoolean(seed.weightedElementsColors.isNotEmpty()))
        "WEIGHTED_ELEMENTS_WEIGHTS" ->
          EvalValue.NullableRef(RemoteBoolean(seed.weightedElementsWeights.isNotEmpty()))
        else -> EvalValue.NullVal
      }
    }
  }

  private companion object {
    val TIME_PATTERN_CHARS: Set<Char> =
      setOf('H', 'h', 'k', 'K', 'm', 's', 'S', 'a', 'y', 'Y', 'M', 'L', 'd', 'D', 'E', 'c', 'e')
    val TWO_CHAR_OPS: Set<String> = setOf("==", "!=", "<=", ">=", "&&", "||")
    val ONE_CHAR_OPS: Set<Char> = setOf('+', '-', '*', '/', '%', '<', '>', '!', '&', '|', '^')
    val COMPLICATION_TYPE_MAP: Map<String, Int> =
      mapOf(
        "EMPTY" to DwfVariables.COMPLICATION_TYPE_EMPTY,
        "NO_DATA" to DwfVariables.COMPLICATION_TYPE_EMPTY,
        "NOT_CONFIGURED" to DwfVariables.COMPLICATION_TYPE_EMPTY,
        "SHORT_TEXT" to DwfVariables.COMPLICATION_TYPE_SHORT_TEXT,
        "LONG_TEXT" to DwfVariables.COMPLICATION_TYPE_LONG_TEXT,
        "RANGED_VALUE" to DwfVariables.COMPLICATION_TYPE_RANGED_VALUE,
        "MONOCHROMATIC_IMAGE" to DwfVariables.COMPLICATION_TYPE_MONOCHROMATIC_IMAGE,
        "SMALL_IMAGE" to DwfVariables.COMPLICATION_TYPE_SMALL_IMAGE,
        "PHOTO_IMAGE" to DwfVariables.COMPLICATION_TYPE_PHOTO_IMAGE,
        "GOAL_PROGRESS" to DwfVariables.COMPLICATION_TYPE_GOAL_PROGRESS,
        "WEIGHTED_ELEMENTS" to DwfVariables.COMPLICATION_TYPE_WEIGHTED_ELEMENTS,
      )
  }
}
