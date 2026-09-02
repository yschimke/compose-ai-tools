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
 * - **No import beyond the callable.** Every placeholder this object writes is a literal or an
 *   empty lambda, so the emitted snippet never needs a second import to resolve. That is a property
 *   of the placeholder table, not a coincidence: widening the table means widening the emitted
 *   imports too.
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
    // Defaulted parameters are omitted rather than filled: omitting one is legal by definition,
    // whereas restating a default means guessing at an expression the metadata does not carry.
    for (parameter in record.parameters.filterNot { it.hasDefault }) {
      val placeholder =
        placeholderFor(parameter)
          ?: return ComponentSnippet.Refused(
            "no placeholder can be written for required parameter " +
              "`${parameter.name}: ${parameter.type}`"
          )
      arguments += "${parameter.name} = $placeholder"
    }
    return ComponentSnippet.Emitted(
      imports = listOf(record.symbol.callable),
      code = "${record.symbol.name}(${arguments.joinToString(", ")})",
    )
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
  private fun placeholderFor(parameter: TargetParameter): String? {
    if (parameter.nullable) return "null"
    val type = parameter.type
    if (parameter.composableSlot || type.contains("->")) return emptyLambda(type)
    return when (type) {
      "String" -> "\"\""
      "Boolean" -> "false"
      "Int" -> "0"
      "Long" -> "0L"
      "Float" -> "0f"
      "Double" -> "0.0"
      // Everything else — `Modifier`, `ImageVector`, an enum, a domain type — has no literal that
      // is correct without knowing the type, and inventing one is how generated code stops
      // compiling.
      else -> null
    }
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
  data class Emitted(val imports: List<String>, val code: String) : ComponentSnippet

  /**
   * No call site, and why — phrased for a human or a model to act on, since supplying the missing
   * value is exactly what a consumer would escalate.
   */
  data class Refused(val reason: String) : ComponentSnippet
}
