package ee.schimke.composeai.discovery

/**
 * Prints a Kotlin call site for a component in `components.json` — or refuses to.
 *
 * Every emitted snippet must be provably compilable from the record alone: `Button(onClick = {},
 * content = {})` qualifies; `Icon(imageVector = ???)` does not, since `ImageVector` has no literal.
 * A [ComponentSnippet.Refused] lets a consumer ask for the one missing value, which beats broken
 * source.
 *
 * Deliberately not attempted:
 * - **No value invention.** Placeholders only for types with an unambiguous literal
 *   ([placeholderFor]); everything else refuses and names the parameter.
 * - **Only resolved imports.** Beyond the callable, an import is emitted only for a type discovery
 *   marked [TargetParameter.noArgConstructible] or a factory it resolved into
 *   [TargetParameter.noArgFactory] — never a name read off a rendered spelling.
 * - **No naming conventions.** A `rememberT()` factory is used only when discovery found it, never
 *   spelled from a type name.
 * - **No scope synthesis.** A composable declared on a receiver is refused rather than wrapped in a
 *   guessed `Column { … }`.
 */
object ComponentSnippets {

  /**
   * A call site for [record], or the reason there isn't one. [ComponentSnippet.Emitted.code] is an
   * expression that only compiles inside a `@Composable` body the caller supplies.
   */
  fun callSite(record: ComponentRecord): ComponentSnippet {
    // "No parameters" and "we could not read the parameters" are the same empty list, and only the
    // first is safe to print. See `ComponentRecord.signatureKnown`.
    if (!record.signatureKnown) {
      return ComponentSnippet.Refused("signature was not recovered from @kotlin.Metadata")
    }
    // Merged overloads share a canonical id; even a printable signature is ambiguous (`Chip()`).
    if (record.overloadsCollided) {
      return ComponentSnippet.Refused(
        "overloads collided under this canonical id, so no single call site identifies one"
      )
    }
    // The wrapper lives in a new file, where `private`/`protected` declarations aren't reachable.
    if (!record.callableFromAnotherFile) {
      return ComponentSnippet.Refused("not public or internal, so a generated file cannot call it")
    }
    // With defaults omitted, a generic function has nothing to infer its type parameters from.
    if (record.hasTypeParameters) {
      return ComponentSnippet.Refused(
        "declares type parameters that a call omitting defaulted arguments cannot infer"
      )
    }
    // Like an extension receiver, a context isn't visible in the printed call but must be in scope.
    if (record.hasContextReceivers) {
      return ComponentSnippet.Refused(
        "declares a context receiver or parameter, which a generated wrapper cannot supply"
      )
    }
    record.symbol.receiver?.let { receiver ->
      return ComponentSnippet.Refused(
        "declared on $receiver, so a call site needs that scope around it"
      )
    }
    // `callableFqn` unwraps a top-level `…Kt` facade, so an unwrapped callable proves it is
    // top-level and importable; otherwise it's a member needing an instance.
    if (record.symbol.callable == "${record.symbol.jvmOwner}.${record.symbol.name}") {
      return ComponentSnippet.Refused(
        "a member of ${record.symbol.jvmOwner}, so a call site needs an instance of it"
      )
    }

    val arguments = mutableListOf<String>()
    // Imports a constructed or remembered placeholder needs, in order (see the object KDoc).
    val placeholderImports = mutableListOf<String>()
    // Defaulted parameters are omitted: always legal, whereas restating a default means guessing.
    for (parameter in record.parameters.filterNot { it.hasDefault }) {
      val placeholder =
        placeholderFor(parameter)
          ?: return ComponentSnippet.Refused(
            "no placeholder can be written for required parameter " +
              "`${parameter.name}: ${parameter.type}`"
          )
      constructedTypeOf(parameter)?.let(placeholderImports::add)
      factoryCallableOf(parameter)?.let(placeholderImports::add)
      arguments += "${escapeIfKeyword(parameter.name)} = $placeholder"
    }
    return ComponentSnippet.Emitted(
      imports =
        (listOf(record.symbol.callable) + placeholderImports).distinct().map {
          escapeCallableIfKeyword(it)
        },
      code = "${escapeIfKeyword(record.symbol.name)}(${arguments.joinToString(", ")})",
      requiredOptIns = record.requiredOptIns,
      androidxOptIns = record.androidxOptIns,
    )
  }

  /**
   * Why [record] cannot be called by a generator that supplies [supplied] itself, or null when it
   * can. Same decision as [callSite] with supplied parameters treated as omittable: every other
   * refusal still stands.
   */
  fun refusalWith(record: ComponentRecord, supplied: Set<String>): String? {
    val filled =
      record
        .newBuilder()
        .also { builder ->
          builder.parameters =
            record.parameters.map {
              if (it.name in supplied) it.newBuilder().also { b -> b.hasDefault = true }.build()
              else it
            }
        }
        .build()
    return (callSite(filled) as? ComponentSnippet.Refused)?.reason
  }

  /**
   * [callSite] as the wire shape `components.json` carries. A projection rather than a second
   * implementation, so the two cannot disagree.
   */
  fun codeFor(record: ComponentRecord): ComponentCode =
    when (val snippet = callSite(record)) {
      is ComponentSnippet.Emitted ->
        ComponentCode.Builder()
          .also { b ->
            b.call = snippet.code
            b.imports = snippet.imports
            b.requiredOptIns = snippet.requiredOptIns
            b.androidxOptIns = snippet.androidxOptIns
          }
          .build()
      is ComponentSnippet.Refused ->
        ComponentCode.Builder().also { b -> b.refusedReason = snippet.reason }.build()
    }

  /**
   * Kotlin's hard keywords, which must be backtick-escaped wherever they appear as a name. Soft and
   * modifier keywords are legal identifiers and left alone.
   */
  private val HARD_KEYWORDS =
    setOf(
      "as",
      "break",
      "class",
      "continue",
      "do",
      "else",
      "false",
      "for",
      "fun",
      "if",
      "in",
      "interface",
      "is",
      "null",
      "object",
      "package",
      "return",
      "super",
      "this",
      "throw",
      "true",
      "try",
      "typealias",
      "typeof",
      "val",
      "var",
      "when",
      "while",
    )

  /**
   * The parameter's qualified type, falling back to the rendered spelling under `kotlin.` for
   * records predating [TargetParameter.typeFqn]. Shared with [ScreenGenerator] so neither emits
   * `""` for a `com.example.String`.
   */
  internal fun qualifiedTypeOf(parameter: TargetParameter): String =
    parameter.typeFqn ?: "kotlin.${parameter.type}"

  internal fun isHardKeyword(name: String): Boolean = name in HARD_KEYWORDS

  /**
   * Kotlin's own identifier rule: `(Letter | '_') (Letter | '_' | UnicodeDigit)*`, so `Übersicht`
   * is bare-writable.
   */
  internal fun isIdentifier(name: String): Boolean =
    name.isNotEmpty() &&
      (name[0].isLetter() || name[0] == '_') &&
      name.all { it.isLetter() || it.isDigit() || it == '_' }

  /**
   * Backticks [name] unless it can be written bare: keywords, and source names that were already
   * backticked (``annotation class `Api${'$'}Experimental` ``).
   */
  internal fun escapeIfKeyword(name: String): String =
    if (name in HARD_KEYWORDS || !isIdentifier(name)) "`$name`" else name

  /** Escapes each segment of an import path, since any one of them may need it. */
  internal fun escapeCallableIfKeyword(callable: String): String =
    callable.split('.').joinToString(".") { escapeIfKeyword(it) }

  /**
   * The qualified type a `Type()` placeholder for [parameter] names, or null when it needs no
   * import. Reads the same fields as [placeholderFor] so import and expression cannot disagree.
   */
  internal fun constructedTypeOf(parameter: TargetParameter): String? =
    if (constructsItsType(parameter)) parameter.typeFqn else null

  /**
   * The qualified callable a `rememberT()` placeholder for [parameter] names, or null. Mutually
   * exclusive with [constructedTypeOf].
   */
  internal fun factoryCallableOf(parameter: TargetParameter): String? =
    if (remembersItsValue(parameter)) parameter.noArgFactory else null

  /**
   * Whether [parameter] is answered by the `remember…` factory discovery found beside its type.
   * Preferred over [constructsItsType]: a raw `TextFieldState()` is rebuilt on every recomposition.
   * Nullable and slot parameters still take the shorter `null` / `{}`.
   */
  private fun remembersItsValue(parameter: TargetParameter): Boolean =
    parameter.noArgFactory != null &&
      !parameter.nullable &&
      !parameter.composableSlot &&
      !parameter.type.contains("->")

  /**
   * Whether [parameter] is answered by constructing its type. Nullable and slot tests come first,
   * as in [placeholderFor]; `typeFqn` is required since the import needs it.
   */
  private fun constructsItsType(parameter: TargetParameter): Boolean =
    !remembersItsValue(parameter) &&
      parameter.noArgConstructible &&
      parameter.typeFqn != null &&
      !parameter.nullable &&
      !parameter.composableSlot &&
      !parameter.type.contains("->")

  /** The literal a built-in type answers with, or null for everything that has none. */
  private fun literalFor(typeFqn: String?): String? =
    when (typeFqn) {
      "kotlin.String" -> "\"\""
      "kotlin.Boolean" -> "false"
      "kotlin.Int" -> "0"
      "kotlin.Long" -> "0L"
      "kotlin.Float" -> "0f"
      "kotlin.Double" -> "0.0"
      else -> null
    }

  /**
   * `{ 0f }` for a required `() -> Float`, or null when the return type has no literal. Only
   * `kotlin.Function0` qualifies, so this never writes a body for a lambda that takes arguments.
   */
  private fun valueReturningLambda(parameter: TargetParameter): String? {
    if (parameter.typeFqn != "kotlin.Function0") return null
    return literalFor(parameter.lambdaReturnTypeFqn)?.let { "{ $it }" }
  }

  /**
   * A literal for [parameter] that is guaranteed to type-check, or null to refuse.
   *
   * Nullability comes first (`null` satisfies any nullable type) and reads
   * [TargetParameter.nullable] rather than a trailing `?`, which can't tell `String?` from `(Int)
   * -> String?`. That is what lets `Checkbox` etc. through, whose callbacks are `((Boolean) ->
   * Unit)?`.
   */
  internal fun placeholderFor(parameter: TargetParameter): String? {
    if (parameter.nullable) return "null"
    val type = parameter.type
    // Fallback for records predating `nullable` (which default it to `false`). Safe only for
    // non-function types: `(Int) -> String?` ends in `?` but isn't nullable.
    if (!type.contains("->") && type.endsWith("?")) return "null"
    // A lambda that must return something has no empty form; write one returning the type's
    // literal.
    if (parameter.composableSlot || type.contains("->"))
      return emptyLambda(type) ?: valueReturningLambda(parameter)
    // Matched on the qualified type: `com.example.String` renders as `String` too. Records
    // predating `typeFqn` fall back to the spelling.
    return when (val qualified = qualifiedTypeOf(parameter)) {
      "kotlin.String",
      "kotlin.Boolean",
      "kotlin.Int",
      "kotlin.Long",
      "kotlin.Float",
      "kotlin.Double" -> literalFor(qualified)
      // A `remember…` factory discovery resolved (`ComposableSignature.noArgFactoryFor`) wins,
      // since raw state doesn't survive recomposition; otherwise a type discovery proved no-arg
      // constructible (`ComposableSignature.isNoArgConstructible`). The simple name resolves
      // through the import above.
      else ->
        if (remembersItsValue(parameter))
          "${escapeIfKeyword(parameter.noArgFactory!!.substringAfterLast('.'))}()"
        else if (constructsItsType(parameter))
          "${escapeIfKeyword(parameter.typeFqn!!.substringAfterLast('.'))}()"
        // Everything else (`Modifier`, `ImageVector`, enums, domain types) has no safe literal.
        else null
    }
  }

  /**
   * Whether [type] is a function type taking no parameters — the question a generated handler asks.
   * Unlike a slot ([acceptsBareLambda]), a handler that ignores `onValueChange`'s value would
   * silently drop what the control reports.
   */
  internal fun acceptsZeroArgLambda(type: String): Boolean {
    val bare = unwrapNullable(type)
    if (!bare.endsWith(" -> Unit")) return false
    val head = bare.removeSuffix(" -> Unit")
    val open = head.indexOf('(')
    if (open == -1 || !head.endsWith(")")) return false
    // No receiver either: `DrawScope.() -> Unit` runs while drawing, so writing state there would
    // redraw forever.
    if (head.substring(0, open).isNotBlank()) return false
    return head.substring(open + 1, head.length - 1).isBlank()
  }

  /**
   * Whether a bare `{ … }` satisfies the function type [type] — whether a slot can take children.
   * Shared with [ScreenGenerator] so the two can't disagree.
   */
  internal fun acceptsBareLambda(type: String): Boolean =
    // A nullable slot renders as `(() -> Unit)?`; Kotlin accepts a non-null lambda for it, so
    // unwrap before asking about the shape. `emptyLambda` itself doesn't unwrap, since
    // `placeholderFor` answers `null` first.
    emptyLambda(unwrapNullable(type)) != null

  /**
   * The one parameter a `(T) -> Unit` slot's lambda takes, as the record spells it, or null when it
   * takes none or several. `@Composable (PaddingValues) -> Unit` answers `PaddingValues`.
   */
  internal fun singleLambdaParameterType(type: String): String? {
    val lambda =
      unwrapNullable(type.removePrefix("@Composable ").trim()).removePrefix("@Composable ")
    if (!lambda.endsWith(" -> Unit")) return null
    val head = lambda.removeSuffix(" -> Unit").trim()
    // A receiver (`RowScope.(T) -> Unit`) is not a parameter the lambda names.
    if (!head.startsWith("(") || !head.endsWith(")")) return null
    val parameters = head.substring(1, head.length - 1).trim()
    if (parameters.isEmpty() || topLevelCommaCount(parameters) != 0) return null
    return parameters
  }

  /** `(() -> Unit)?` to `() -> Unit`; anything else unchanged. */
  private fun unwrapNullable(type: String): String {
    if (!type.startsWith("(") || !type.endsWith(")?")) return type
    val inner = type.substring(1, type.length - 2)
    // Only strip when the opening paren is the one the `?` closes, not a parameter list.
    return if (topLevelCommaCount(inner) >= 0 && balanced(inner)) inner else type
  }

  private fun balanced(text: String): Boolean {
    var depth = 0
    for (c in text) {
      if (c == '(') depth++
      if (c == ')') depth--
      if (depth < 0) return false
    }
    return depth == 0
  }

  /**
   * `"{}"` when a bare empty lambda satisfies [type]: zero or one parameter, any receiver, `Unit`
   * return. Otherwise null.
   */
  private fun emptyLambda(type: String): String? {
    if (!type.endsWith(" -> Unit")) return null
    val head = type.removeSuffix(" -> Unit")
    val open = head.indexOf('(')
    if (open == -1 || !head.endsWith(")")) return null
    val parameters = head.substring(open + 1, head.length - 1)
    if (parameters.isBlank()) return "{}"
    return if (topLevelCommaCount(parameters) == 0) "{}" else null
  }

  /**
   * Commas separating this parameter list's own entries, ignoring those nested in type arguments or
   * function types (`Map<String, Int>` is one parameter).
   */
  private fun topLevelCommaCount(parameters: String): Int {
    var depth = 0
    var count = 0
    for (character in parameters) {
      when (character) {
        '<',
        '(' -> depth++
        '>',
        ')' -> depth--
        ',' -> if (depth == 0) count++
      }
    }
    return count
  }
}

/** The outcome of printing a call site for one component. */
sealed interface ComponentSnippet {

  /**
   * A call site this object proved compiles.
   *
   * @property imports the FQNs the snippet needs: the callable plus any resolved placeholder types.
   * @property code the call expression, for a caller to place inside a `@Composable` body.
   */
  data class Emitted(
    val imports: List<String>,
    val code: String,
    /** Markers the caller's `@Composable` wrapper must `@OptIn` to — see `ComponentCode`. */
    val requiredOptIns: List<String> = emptyList(),
    val androidxOptIns: List<String> = emptyList(),
  ) : ComponentSnippet

  /** No call site, and why — phrased for a human or model to supply the missing value. */
  data class Refused(val reason: String) : ComponentSnippet
}
