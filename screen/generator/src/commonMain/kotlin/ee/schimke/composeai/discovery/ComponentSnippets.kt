package ee.schimke.composeai.discovery

/**
 * Prints a Kotlin call site for a component in `components.json` — or refuses to.
 *
 * ## Why printing, and why refusing
 *
 * Generating Compose source has so far meant transcribing a call site by hand into a catalog table
 * and hoping it still compiles. The component record already carries the three things a call site
 * needs — the source-level callable, the value parameters with their defaults, and the receiver the
 * function is declared on — so the call site can be *printed* from the record instead. Printing is
 * only worth anything if the output compiles, which is why the interesting half of this object is
 * [ComponentSnippet.Refused].
 *
 * The rule is that every emitted snippet must be one this object can **prove** compiles from the
 * record alone. `Button(onClick = {}, content = {})` qualifies: `onClick` is `() -> Unit`,
 * `content` is a slot, every other parameter has a default and omitting a defaulted parameter is
 * always legal. `Icon(imageVector = ???)` does not: `ImageVector` has no literal this object can
 * write down. Guessing there produces code that looks right and does not build, which is worse than
 * admitting the gap — a consumer that gets a [ComponentSnippet.Refused] can ask a human or a model
 * for the one missing value, while a consumer handed broken source has to discover the breakage
 * itself.
 *
 * ## What is deliberately not attempted
 * - **No value invention.** A placeholder is written only for a type whose literal is unambiguous
 *   (see [placeholderFor]). Everything else refuses and names the parameter.
 * - **Every emitted import is one this object resolved.** The rule used to be "no import beyond the
 *   callable", because every placeholder was a literal or an empty lambda. Constructing a
 *   no-arg-constructible type (issue #5067) breaks that on purpose — `TextField(state =
 *   TextFieldState())` needs `TextFieldState` imported — so the invariant moved rather than
 *   loosened: an import is emitted only for a type DISCOVERY resolved on the classpath and marked
 *   [TargetParameter.noArgConstructible], or for a factory callable it resolved into
 *   [TargetParameter.noArgFactory] — never for a name read off the rendered spelling. The compile
 *   gate is what checks it.
 * - **No convention applied from its own name.** Compose's `rememberT` pairing is used, but only as
 *   something discovery LOOKED UP: `rememberTextFieldState()` is printed because the scan found
 *   that function beside `TextFieldState`, returning it, `@Composable` and fully defaulted. Nothing
 *   here spells a factory name from a type name, which would be the authored table
 *   `docs/design/COMPONENT_RECORD.md` keeps deleting.
 * - **No scope synthesis.** A composable declared on a receiver is refused rather than wrapped in a
 *   guessed `Column { … }`, because the wrapper is a rendering decision this object has no basis to
 *   make.
 */
object ComponentSnippets {

  /**
   * A call site for [record], or the reason there isn't one.
   *
   * [ComponentSnippet.Emitted.code] is an **expression**, not a file: it calls a `@Composable`, so
   * it only compiles inside a `@Composable` body. The caller supplies that wrapper.
   */
  fun callSite(record: ComponentRecord): ComponentSnippet {
    // "No parameters" and "we could not read the parameters" are the same empty list, and only the
    // first is safe to print. See `ComponentRecord.signatureKnown`.
    if (!record.signatureKnown) {
      return ComponentSnippet.Refused("signature was not recovered from @kotlin.Metadata")
    }
    // Overloads share a canonical id, so `ComponentRecords` merged them and kept one signature.
    // Even when that signature prints, the call is ambiguous: two fully defaulted overloads both
    // accept `Chip()`, and Kotlin resolves neither.
    if (record.overloadsCollided) {
      return ComponentSnippet.Refused(
        "overloads collided under this canonical id, so no single call site identifies one"
      )
    }
    // A generator writes its wrapper into a new file. `private` and `protected` declarations are
    // not reachable from there however correct the call text is.
    if (!record.callableFromAnotherFile) {
      return ComponentSnippet.Refused("not public or internal, so a generated file cannot call it")
    }
    // Every defaulted argument is omitted, which leaves a generic function with nothing to infer
    // its type parameters from — `fun <T> Picker(items: List<T> = emptyList())` has no `Picker()`.
    if (record.hasTypeParameters) {
      return ComponentSnippet.Refused(
        "declares type parameters that a call omitting defaulted arguments cannot infer"
      )
    }
    // The same reason as an extension receiver, one the parameter list cannot show: a context is
    // not a value parameter, so nothing in the printed call would hint that a `Theme` has to be in
    // scope around it.
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
    // A top-level function compiles into a `…Kt` facade, and `callableFqn` unwraps it — so an
    // unwrapped callable is exactly the evidence that this is top-level and therefore importable
    // and callable on its own. When nothing was unwrapped the symbol is a member, whose call site
    // needs an instance this object cannot conjure.
    if (record.symbol.callable == "${record.symbol.jvmOwner}.${record.symbol.name}") {
      return ComponentSnippet.Refused(
        "a member of ${record.symbol.jvmOwner}, so a call site needs an instance of it"
      )
    }

    val arguments = mutableListOf<String>()
    // What a constructed or remembered placeholder names, in the order they were needed — a
    // `Type()` or `rememberType()` placeholder is the one thing this object writes that does not
    // resolve on its own, so its import is collected here rather than left to the caller to notice
    // (see the object KDoc).
    val placeholderImports = mutableListOf<String>()
    // Defaulted parameters are omitted rather than filled: omitting one is legal by definition,
    // whereas restating a default means guessing at an expression the metadata does not carry.
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
   * [callSite] as the wire shape `components.json` carries, so the record answers "how do I call
   * this?" without a consumer linking this library.
   *
   * Deliberately a projection of [callSite] rather than a second implementation: the two cannot
   * disagree about what is emittable, because there is only one decision.
   */
  fun codeFor(record: ComponentRecord): ComponentCode =
    when (val snippet = callSite(record)) {
      is ComponentSnippet.Emitted ->
        ComponentCode(
          call = snippet.code,
          imports = snippet.imports,
          requiredOptIns = snippet.requiredOptIns,
          androidxOptIns = snippet.androidxOptIns,
        )
      is ComponentSnippet.Refused -> ComponentCode(refusedReason = snippet.reason)
    }

  /**
   * A literal for [parameter] that is guaranteed to type-check, or null to refuse.
   *
   * The nullable test comes first and needs no per-type knowledge, because `null` satisfies every
   * nullable type. It reads [TargetParameter.nullable] rather than looking for a trailing `?` in
   * the rendered type, because the spelling does not distinguish a nullable parameter (`String?`)
   * from a non-null function whose *return* is nullable (`(Int) -> String?`) — and `null`
   * type-checks for the first but not the second. Testing the spelling would emit uncompilable
   * source for every callback returning a nullable value.
   *
   * That test is what lets `Checkbox`, `RadioButton` and `Switch` through: material3 declares their
   * `onCheckedChange` / `onClick` as `((Boolean) -> Unit)?`, which no lambda-shaped rule accepts.
   */
  /**
   * Kotlin's **hard** keywords — the ones that are never valid as a plain identifier and so must be
   * backtick-escaped wherever they appear as a name.
   *
   * Soft and modifier keywords (`by`, `data`, `value`, `where`, …) are deliberately absent: they
   * are legal identifiers, and escaping them would be noise. `` fun `when`(`is`: String) `` is a
   * legal declaration whose metadata hands back the bare names `when` and `is`, which pass the
   * import-identifier filter and would otherwise be printed as `when(is = "")`.
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
   * The parameter's qualified type, falling back to the rendered spelling under `kotlin.` when the
   * record predates [TargetParameter.typeFqn].
   *
   * Shared with [ScreenGenerator] rather than duplicated: both have to decide "is this actually
   * `kotlin.String`?", and two implementations of that question is how one of them starts emitting
   * `""` for `com.example.String` again.
   */
  internal fun qualifiedTypeOf(parameter: TargetParameter): String =
    parameter.typeFqn ?: "kotlin.${parameter.type}"

  internal fun isHardKeyword(name: String): Boolean = name in HARD_KEYWORDS

  /**
   * Kotlin's own identifier rule: `(Letter | '_') (Letter | '_' | UnicodeDigit)*`.
   *
   * Not `[A-Za-z_][A-Za-z0-9_]*` — `Übersicht` and `画面` are identifiers Kotlin accepts without
   * backticks. Kept here, next to the escaping it decides, so there is one answer to "can this be
   * written bare?" rather than one per generator.
   */
  internal fun isIdentifier(name: String): Boolean =
    name.isNotEmpty() &&
      (name[0].isLetter() || name[0] == '_') &&
      name.all { it.isLetter() || it.isDigit() || it == '_' }

  /**
   * Backticks [name] unless it can be written bare.
   *
   * Two reasons it cannot, and a keyword is only the obvious one: a declaration's *source* name can
   * hold characters no bare identifier may, because it was written in backticks to begin with —
   * ``annotation class `Api${'$'}Experimental` ``. Printing that unquoted is as broken as printing
   * `when` unquoted, and the second case is the one that survived a round of review by looking
   * unremarkable.
   */
  internal fun escapeIfKeyword(name: String): String =
    if (name in HARD_KEYWORDS || !isIdentifier(name)) "`$name`" else name

  /** Escapes each segment of an import path, since any one of them may need it. */
  internal fun escapeCallableIfKeyword(callable: String): String =
    callable.split('.').joinToString(".") { escapeIfKeyword(it) }

  /**
   * The qualified type a `Type()` placeholder for [parameter] names, or null when its placeholder
   * needs no import.
   *
   * Reads the same two fields [placeholderFor] does, in the same order, so the import can never
   * disagree with the expression it exists for — a snippet importing what it did not print, or
   * printing what it did not import, is the failure this shares one source of truth to avoid.
   */
  internal fun constructedTypeOf(parameter: TargetParameter): String? =
    if (constructsItsType(parameter)) parameter.typeFqn else null

  /**
   * The qualified callable a `rememberT()` placeholder for [parameter] names, or null when its
   * placeholder is not a factory call.
   *
   * The same contract [constructedTypeOf] has, for the other of the two placeholders that need an
   * import — and the two are mutually exclusive by construction, so a parameter never imports both
   * a type it does not print and a factory it does.
   */
  internal fun factoryCallableOf(parameter: TargetParameter): String? =
    if (remembersItsValue(parameter)) parameter.noArgFactory else null

  /**
   * Whether [parameter] is answered by calling the `remember…` factory discovery found beside its
   * type.
   *
   * Preferred over [constructsItsType] wherever both are available, because the two do not compile
   * to the same thing: a raw `TextFieldState()` in a composable body is rebuilt on every
   * recomposition and silently loses what the user typed, while `rememberTextFieldState()` is the
   * expression a human would write. The gate is otherwise identical — a nullable parameter still
   * takes `null` and a slot still takes `{}`, both shorter answers than any call.
   */
  private fun remembersItsValue(parameter: TargetParameter): Boolean =
    parameter.noArgFactory != null &&
      !parameter.nullable &&
      !parameter.composableSlot &&
      !parameter.type.contains("->")

  /**
   * Whether [parameter] is answered by constructing its type rather than by a literal.
   *
   * The nullable and slot tests come first for the same reason they do in [placeholderFor]: a
   * nullable parameter takes `null` and a slot takes `{}` whatever its type can do, and both are
   * shorter answers than a constructor call. `typeFqn` is required rather than defaulted, because a
   * record written before [TargetParameter.noArgConstructible] existed carries `false` anyway, and
   * one carrying the flag without a qualified name could not be imported.
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
   * `{ 0f }` for a required `() -> Float`, or null when the return type has no literal.
   *
   * The classifier decides, not the spelling: `kotlin.Function0` is the only function type with no
   * value parameters and no receiver, so this never writes a body for a lambda that was handed
   * something. Anything whose return type has no literal — an `ImageVector`, a domain type — still
   * refuses its call site, which is the honest answer and the one `m3/icon` documents downstream.
   */
  private fun valueReturningLambda(parameter: TargetParameter): String? {
    if (parameter.typeFqn != "kotlin.Function0") return null
    return literalFor(parameter.lambdaReturnTypeFqn)?.let { "{ $it }" }
  }

  internal fun placeholderFor(parameter: TargetParameter): String? {
    if (parameter.nullable) return "null"
    val type = parameter.type
    // A record written before `nullable` existed defaults it to `false`, so a persisted v1
    // `components.json` would lose every `String?` it used to answer for. For a type with no
    // arrow the spelling is unambiguous — type arguments close with `>`, so `List<String?>` does
    // not reach here looking nullable — and this reads it as the fallback it is. Function types
    // deliberately get no fallback: `(Int) -> String?` ends in `?` and is *not* nullable.
    if (!type.contains("->") && type.endsWith("?")) return "null"
    // `emptyLambda` answers for a `-> Unit` slot or callback. A lambda that must RETURN something
    // has no empty form, and until `ScreenValue.Lambda` existed there was nothing to write there
    // either, so a component with a required `() -> Float` got no call site at all. Now the
    // placeholder is the same thing a document would supply: a lambda returning that type's own
    // literal.
    if (parameter.composableSlot || type.contains("->"))
      return emptyLambda(type) ?: valueReturningLambda(parameter)
    // Matched on the QUALIFIED type, never the rendered spelling: `com.example.String` renders as
    // `String` exactly like `kotlin.String`, and answering `""` for the first emits source that
    // does not compile. A record written before `typeFqn` existed has null here and falls back to
    // the spelling, which is what it always did — no worse, and no silent retraction.
    return when (val qualified = qualifiedTypeOf(parameter)) {
      "kotlin.String",
      "kotlin.Boolean",
      "kotlin.Int",
      "kotlin.Long",
      "kotlin.Float",
      "kotlin.Double" -> literalFor(qualified)
      // A type that constructs itself with no arguments is answered by doing exactly that —
      // `TextFieldState()` — which is not "inventing a value" but writing down the one the type
      // already defines. Discovery proved it on the classpath (`ComposableSignature
      // .isNoArgConstructible`): public, non-generic, non-value, non-inner, plain class, with a
      // public constructor whose parameters all default. The simple name is safe to print here
      // because the import above is the qualified one it resolves through.
      // A type whose package ships a `remember…` factory is answered by calling it. Discovery
      // resolved the callable on the classpath (`ComposableSignature.noArgFactoryFor`) rather than
      // spelling it from the type's name, and it wins over the constructor below because raw state
      // construction in a composable body does not survive recomposition.
      else ->
        if (remembersItsValue(parameter))
          "${escapeIfKeyword(parameter.noArgFactory!!.substringAfterLast('.'))}()"
        else if (constructsItsType(parameter))
          "${escapeIfKeyword(parameter.typeFqn!!.substringAfterLast('.'))}()"
        // Everything else — `Modifier`, `ImageVector`, an enum, a domain type — has no literal
        // that is correct without knowing the type, and inventing one is how generated code stops
        // compiling.
        else null
    }
  }

  /**
   * Whether a bare `{ … }` satisfies the function type [type] — the question a slot has to answer
   * before children can be dropped into it.
   *
   * Shared with [ScreenGenerator] because a slot whose type is `(Int, Int) -> Unit` or `() ->
   * String` accepts children in neither generator, and two answers to that would let one of them
   * emit a lambda the compiler rejects.
   */
  /**
   * Whether [type] is a function type taking **no** parameters — the question a generated *handler*
   * has to answer, which is not the question a slot asks.
   *
   * [acceptsBareLambda] is right for a slot: `content: (RowScope) -> Unit` really does take a bare
   * `{ … }`, because the receiver or argument is simply not used by the children placed in it. A
   * handler is different. `onValueChange: (String) -> Unit` exists to deliver the new value, and a
   * generated `{ expanded.value = !expanded.value }` compiles there only by accident of the
   * argument being ignored — shipping a control that silently drops what it was asked to report.
   */
  internal fun acceptsZeroArgLambda(type: String): Boolean {
    val bare = unwrapNullable(type)
    if (!bare.endsWith(" -> Unit")) return false
    val head = bare.removeSuffix(" -> Unit")
    val open = head.indexOf('(')
    if (open == -1 || !head.endsWith(")")) return false
    // Neither value parameters nor a receiver. `DrawScope.() -> Unit` has empty parentheses and is
    // still not an event callback: Compose runs it while drawing, so a generated body that writes
    // state invalidates what it just drew and the screen redraws forever. The receiver sits before
    // the parentheses, which is the only thing distinguishing it from `() -> Unit`.
    if (head.substring(0, open).isNotBlank()) return false
    return head.substring(open + 1, head.length - 1).isBlank()
  }

  internal fun acceptsBareLambda(type: String): Boolean =
    // A nullable slot renders as `(() -> Unit)?` — parenthesised so the `?` cannot read as part of
    // the return type. Kotlin accepts a non-null `{ … }` for a nullable function type, so the
    // outer nullability is stripped before asking about the lambda's shape. Only
    // `acceptsBareLambda`
    // unwraps: `emptyLambda` still answers null for a nullable parameter, because `placeholderFor`
    // reaches `null` first and that is the better answer when there are no children to place.
    emptyLambda(unwrapNullable(type)) != null

  /** `(() -> Unit)?` to `() -> Unit`; anything else unchanged. */
  private fun unwrapNullable(type: String): String {
    if (!type.startsWith("(") || !type.endsWith(")?")) return type
    val inner = type.substring(1, type.length - 2)
    // Only strip when the opening paren really is the one the `?` closes, so `(A) -> B` — whose
    // first paren belongs to the parameter list — is left alone.
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
   * `"{}"` when a bare empty-lambda literal satisfies the function type [type], else null.
   *
   * `{}` infers against zero or one parameter (the single one becomes an unused `it`) and against
   * any receiver, so `() -> Unit`, `(Boolean) -> Unit` and `RowScope.() -> Unit` all accept it. Two
   * or more parameters need explicit `_, _ ->` placeholders, and a non-`Unit` return needs a value
   * — both refuse rather than emit something that does not build.
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
   * Commas separating this parameter list's own entries, ignoring those nested inside type
   * arguments or a nested function type — `Map<String, Int>` is one parameter, not two, and reading
   * it as two would refuse a call site that is perfectly emittable.
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
   * @property imports the FQNs the snippet needs, which is always exactly the callable: every
   *   placeholder is a literal or an empty lambda, so nothing else has to resolve.
   * @property code the call expression, for a caller to place inside a `@Composable` body.
   */
  data class Emitted(
    val imports: List<String>,
    val code: String,
    /** Markers the caller's `@Composable` wrapper must `@OptIn` to — see `ComponentCode`. */
    val requiredOptIns: List<String> = emptyList(),
    val androidxOptIns: List<String> = emptyList(),
  ) : ComponentSnippet

  /**
   * No call site, and why — phrased for a human or a model to act on, since supplying the missing
   * value is exactly what a consumer would escalate.
   */
  data class Refused(val reason: String) : ComponentSnippet
}
