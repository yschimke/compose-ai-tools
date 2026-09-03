package ee.schimke.composeai.discovery

/**
 * Generates a compilable `@Composable` screen from a [ScreenDocument] and the components a build
 * discovered.
 *
 * ## What this adds over [ComponentSnippets]
 *
 * [ComponentSnippets] prints one component's call site with **placeholders** — `Text(text = "")` —
 * which proves a component is reachable but renders nothing anyone designed. This binds the values
 * a builder actually set, and nests components into each other's slots, so the output is the screen
 * rather than a specimen of its parts.
 *
 * ## What it inherits, and why that matters
 *
 * A node is generated only when its record carries an emitted [ComponentCode]. That single check
 * carries every protection the call-site generator learned the hard way: the component is public,
 * has no uninferable type parameters, did not collide with an overload, is a top-level function
 * with an importable callable, and has a signature that was actually read rather than defaulted
 * away. None of that is re-derived here — a second implementation of those rules is how two halves
 * of a contract start disagreeing.
 *
 * What is *not* inherited is the argument list: `code.call` fills required parameters with
 * placeholders, and this replaces them with the document's values. So the emitted call is built
 * here from [ComponentRecord.parameters], with `code.call` used as the licence to call at all.
 *
 * ## Refusing, again
 *
 * The discipline is the same one that makes the call-site generator worth anything: emit only what
 * can be proven, and say why otherwise. A builder pinned to a catalog it no longer has, a property
 * the component never declared, a string handed to a `Boolean` — each is a refusal naming the node,
 * because a screen that compiles and is not the one designed is worse than an error message.
 */
object ScreenGenerator {

  /** The generated file, or the reasons it could not be generated. */
  sealed interface Result {
    data class Emitted(
      /** A complete Kotlin file: package, imports, opt-ins and the screen composable. */
      val source: String,
      /**
       * Every `@RequiresOptIn` marker the screen's components need, already applied to [source].
       */
      val requiredOptIns: List<String>,
    ) : Result

    /** Every problem found, not just the first — a builder wants the whole list to act on. */
    data class Refused(val reasons: List<String>) : Result
  }

  private const val INDENT = "    "

  fun generate(
    document: ScreenDocument,
    components: ComponentRecordFile,
    packageName: String = "generated.screen",
  ): Result {
    if (!KOTLIN_IDENTIFIER.matches(document.name)) {
      return Result.Refused(listOf("screen name `${document.name}` is not a Kotlin identifier"))
    }
    val byId = components.components.associateBy { it.canonicalId }
    val context = Emission(byId)
    val body = context.node(document.root, depth = 1)
    if (context.reasons.isNotEmpty()) return Result.Refused(context.reasons.toList())

    val optIns = context.optIns.toSortedSet().toList()
    val imports = (context.imports + optIns + "androidx.compose.runtime.Composable").toSortedSet()
    val source = buildString {
      appendLine("package $packageName")
      appendLine()
      imports.forEach { appendLine("import $it") }
      appendLine()
      if (optIns.isNotEmpty()) {
        appendLine(
          optIns.joinToString(", ", "@OptIn(", ")") { "${it.substringAfterLast('.')}::class" }
        )
      }
      appendLine("@Composable")
      appendLine("fun ${document.name}() {")
      appendLine(body)
      appendLine("}")
    }
    return Result.Emitted(source = source, requiredOptIns = optIns)
  }

  /**
   * Accumulates one generation pass: the text, the imports it needs, and every reason it failed.
   */
  private class Emission(val byId: Map<String, ComponentRecord>) {
    val imports = mutableSetOf<String>()
    val optIns = mutableSetOf<String>()
    val reasons = mutableListOf<String>()

    fun node(node: ScreenNode, depth: Int): String {
      val pad = INDENT.repeat(depth)
      val record = byId[node.componentId]
      if (record == null) {
        reasons += "no component `${node.componentId}` in this catalog"
        return "$pad// unresolved: ${node.componentId}"
      }
      // The licence to call at all. Everything a refusal protects against — private, generic,
      // collided, unreadable, not importable — is already decided here, once, by the producer.
      val code = record.code
      if (code?.call == null) {
        reasons +=
          "`${node.componentId}` has no call site: ${code?.refusedReason ?: "no code was recorded"}"
        return "$pad// unusable: ${node.componentId}"
      }
      imports += ComponentSnippets.escapeCallableIfKeyword(record.symbol.callable)
      optIns += code.requiredOptIns

      val byName = record.parameters.associateBy { it.name }
      node.arguments.keys.filterNot(byName::containsKey).forEach {
        reasons += "`${record.symbol.name}` has no parameter `$it`"
      }
      node.slots.keys.forEach { slot ->
        val parameter = byName[slot]
        when {
          parameter == null -> reasons += "`${record.symbol.name}` has no slot `$slot`"
          !parameter.composableSlot ->
            reasons += "`${record.symbol.name}`.`$slot` is a parameter, not a @Composable slot"
        }
      }

      val arguments = mutableListOf<String>()
      for (parameter in record.parameters) {
        val supplied = node.arguments[parameter.name]
        val children = node.slots[parameter.name]
        when {
          supplied != null ->
            literal(supplied, parameter, record.symbol.name)?.let {
              arguments += "${ComponentSnippets.escapeIfKeyword(parameter.name)} = $it"
            }
          children != null && parameter.composableSlot -> {
            val nested = children.joinToString("\n") { node(it, depth + 1) }
            arguments += "${ComponentSnippets.escapeIfKeyword(parameter.name)} = {\n$nested\n$pad}"
          }
          // Untouched by the document. A default may be omitted; anything else still has to be
          // filled, and the placeholder table is the same one the call-site generator uses.
          parameter.hasDefault -> Unit
          else -> {
            val placeholder = ComponentSnippets.placeholderFor(parameter)
            if (placeholder == null) {
              reasons +=
                "`${record.symbol.name}` needs `${parameter.name}: ${parameter.type}` and the " +
                  "document does not set it"
            } else {
              arguments += "${ComponentSnippets.escapeIfKeyword(parameter.name)} = $placeholder"
            }
          }
        }
      }
      val name = ComponentSnippets.escapeIfKeyword(record.symbol.name)
      return "$pad$name(${arguments.joinToString(", ")})"
    }

    /**
     * The Kotlin literal for [value], or null having recorded why it does not fit [parameter].
     *
     * Checked against the **qualified** type, so a `com.example.String` property is rejected rather
     * than handed a string literal — the trap the call-site generator was caught by twice.
     */
    fun literal(value: ScreenValue, parameter: TargetParameter, owner: String): String? {
      val type = ComponentSnippets.qualifiedTypeOf(parameter)
      val literal =
        when (value) {
          is ScreenValue.Text -> if (type == "kotlin.String") quote(value.value) else null
          is ScreenValue.Bool -> if (type == "kotlin.Boolean") value.value.toString() else null
          is ScreenValue.Whole ->
            when (type) {
              "kotlin.Int" -> value.value.toInt().toString()
              "kotlin.Long" -> "${value.value}L"
              else -> null
            }
          is ScreenValue.Fractional ->
            when (type) {
              "kotlin.Float" -> "${value.value}f"
              "kotlin.Double" -> value.value.toString()
              else -> null
            }
        }
      if (literal == null) {
        reasons += "`$owner`.`${parameter.name}` is $type, which ${value::class.simpleName} is not"
      }
      return literal
    }
  }

  /**
   * A Kotlin string literal for [value].
   *
   * `$` needs escaping as much as `"` does: a user typing `$name` into a label would otherwise
   * generate a template referring to a variable that does not exist, which is a compile error
   * produced by ordinary text.
   */
  private fun quote(value: String): String =
    value
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("$", "\\$")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
      .replace("\t", "\\t")
      .let { "\"$it\"" }

  private val KOTLIN_IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
}
