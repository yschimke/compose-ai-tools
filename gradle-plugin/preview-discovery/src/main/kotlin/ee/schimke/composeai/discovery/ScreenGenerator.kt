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
    if (components.schemaVersion > COMPONENT_RECORD_SCHEMA_VERSION) {
      // A record from a newer producer may mean things by fields this build has never seen.
      // Reading it as the current schema is exactly the guess the version exists to prevent.
      return Result.Refused(
        listOf(
          "components.json is schema ${components.schemaVersion}, newer than the " +
            "$COMPONENT_RECORD_SCHEMA_VERSION this generator understands"
        )
      )
    }
    val badSegment = packageName.split('.').firstOrNull { !isUsableIdentifier(it) }
    if (badSegment != null) {
      return Result.Refused(
        listOf("package segment `$badSegment` is not a usable Kotlin identifier")
      )
    }
    if (!isUsableIdentifier(document.name)) {
      return Result.Refused(
        listOf("screen name `${document.name}` is not a usable Kotlin function name")
      )
    }
    // **Schema 1 is refused outright**, rather than read with a growing list of exceptions.
    //
    // The first attempt kept reading it and refused only the one thing it could name — markers
    // whose opt-in mechanism it could not classify. That was too clever twice over: it scanned the
    // whole catalog, so one gated component nobody placed refused a screen built entirely from
    // stable ones; and it protected only the field it happened to be about, while a schema-1 record
    // also cannot say whether a component needs a context receiver, so those still slipped through
    // with a persisted `code.call` and produced a call the compiler rejects.
    //
    // Both are the same shape: this generator's guarantee rests on fields schema 1 does not have,
    // and every one of them would need its own exception here. One rule instead of a table of them
    // — a producer emits schema 2, and a catalog older than that is regenerated rather than
    // squinted at.
    if (components.schemaVersion < COMPONENT_RECORD_OPT_IN_MECHANISM_SCHEMA) {
      return Result.Refused(
        listOf(
          "components.json is schema ${components.schemaVersion}; this generator needs at least " +
            "$COMPONENT_RECORD_OPT_IN_MECHANISM_SCHEMA, which is the first to record whether a " +
            "component needs a context receiver and which opt-in mechanism each marker uses. " +
            "Re-run discovery to regenerate the catalog."
        )
      )
    }
    val byId = components.components.associateBy { it.canonicalId }
    // Two components can share a simple name (`com.a.Badge`, `com.b.Badge`), and a screen can share
    // one with a component it calls — `fun HomeScreen()` calling a `HomeScreen` component would
    // shadow the import and recurse into itself. Neither is exotic once a catalog spans libraries.
    // A simple name is only used when exactly one component wants it and the screen does not; the
    // rest are called fully qualified, which is always unambiguous and needs no import.
    val claimants = components.components.groupBy { it.symbol.name }
    val simplyImportable =
      components.components
        .filter {
          claimants.getValue(it.symbol.name).size == 1 &&
            it.symbol.name != document.name &&
            it.symbol.name !in RESERVED_BY_THE_WRAPPER
        }
        .map { it.canonicalId }
        .toSet()
    val context = Emission(byId, simplyImportable)
    val body = context.node(document.root, depth = 1)
    if (context.reasons.isNotEmpty()) return Result.Refused(context.reasons.toList())

    // An AndroidX-mechanism marker is reported by both scans, so it is subtracted here rather than
    // written twice under two annotations that would each reject the other's markers.
    val androidxOptIns = context.androidxOptIns.toSortedSet().toList()
    val optIns = (context.optIns - context.androidxOptIns).toSortedSet().toList()
    val imports = (context.imports + "androidx.compose.runtime.Composable").toSortedSet()
    val source = buildString {
      appendLine("package $packageName")
      appendLine()
      imports.forEach { appendLine("import $it") }
      appendLine()
      if (optIns.isNotEmpty()) {
        appendLine(
          // Both halves qualified. The markers because two can share a simple name from different
          // packages, and `@OptIn(ExperimentalApi::class, ExperimentalApi::class)` is ambiguous
          // rather than merely ugly; the annotation itself because the generated file sits in a
          // package the caller chose, and a package declaring its own `OptIn` would capture the
          // bare name — the AndroidX branch below was already written qualified.
          optIns.joinToString(", ", "@kotlin.OptIn(", ")") { "${markerReference(it)}::class" }
        )
      }
      if (androidxOptIns.isNotEmpty()) {
        // A different annotation, not a stylistic variant: `kotlin.OptIn` rejects a marker declared
        // with `androidx.annotation.RequiresOptIn` ("this class is not an opt-in requirement
        // marker"), and the AndroidX one takes an array under a named `markerClass`.
        appendLine(
          androidxOptIns.joinToString(
            ", ",
            "@androidx.annotation.OptIn(markerClass = [",
            "])",
          ) {
            "${markerReference(it)}::class"
          }
        )
      }
      appendLine("@Composable")
      appendLine("fun ${document.name}() {")
      appendLine(body)
      appendLine("}")
    }
    return Result.Emitted(source = source, requiredOptIns = optIns + androidxOptIns)
  }

  /**
   * Accumulates one generation pass: the text, the imports it needs, and every reason it failed.
   */
  private class Emission(
    val byId: Map<String, ComponentRecord>,
    val simplyImportable: Set<String>,
  ) {
    val imports = mutableSetOf<String>()
    val optIns = mutableSetOf<String>()
    val androidxOptIns = mutableSetOf<String>()
    val reasons = mutableListOf<String>()

    fun node(node: ScreenNode, depth: Int, inReceiverScope: Boolean = false): String {
      val pad = INDENT.repeat(depth)
      val record = byId[node.componentId]
      if (record == null) {
        reasons += "no component `${node.componentId}` in this catalog"
        // Keep walking its children: a catalog that dropped a whole subtree should name every node
        // it can no longer place, not just the outermost one. `reasons` is what a caller acts on,
        // and the text returned here is discarded the moment anything has failed.
        node.slots.values.flatten().forEach { node(it, depth + 1, inReceiverScope) }
        return "$pad// unresolved: ${node.componentId}"
      }
      // The licence to call at all. Everything a refusal protects against — private, generic,
      // collided, unreadable, not importable — is already decided here, once, by the producer.
      val code = record.code
      if (code?.call == null) {
        reasons +=
          "`${node.componentId}` has no call site: ${code?.refusedReason ?: "no code was recorded"}"
        node.slots.values.flatten().forEach { node(it, depth + 1, inReceiverScope) }
        return "$pad// unusable: ${node.componentId}"
      }
      val qualified = ComponentSnippets.escapeCallableIfKeyword(record.symbol.callable)
      if (record.canonicalId in simplyImportable && !inReceiverScope) imports += qualified
      optIns += code.requiredOptIns
      androidxOptIns += code.androidxOptIns

      val byName = record.parameters.associateBy { it.name }
      node.arguments.keys.filterNot(byName::containsKey).forEach {
        reasons += "`${record.symbol.name}` has no parameter `$it`"
      }
      node.slots.forEach { (slot, children) ->
        val parameter = byName[slot]
        val rejected =
          when {
            parameter == null -> "`${record.symbol.name}` has no slot `$slot`"
            !parameter.composableSlot ->
              "`${record.symbol.name}`.`$slot` is a parameter, not a @Composable slot"
            else -> null
          }
        if (rejected != null) {
          reasons += rejected
          // The loop below walks `record.parameters`, so a slot the component never declared is
          // never reached and its subtree would go unreported — the same gap as an unresolved
          // node's children, one level in. A renamed slot is exactly when a document is most
          // likely to be stale further down, so those children are the ones worth naming.
          children.forEach { node(it, depth + 1, inReceiverScope) }
        }
      }

      val arguments = mutableListOf<String>()
      for (parameter in record.parameters) {
        val supplied = node.arguments[parameter.name]
        val children = node.slots[parameter.name]
        when {
          supplied != null -> {
            literal(supplied, parameter, record.symbol.name)?.let {
              arguments += "${ComponentSnippets.escapeIfKeyword(parameter.name)} = $it"
            }
            // A conflicted document can set both an argument and a slot for one parameter. The
            // scalar loses (a literal cannot be a function type, so `literal` refuses it), but the
            // slot's children would never be visited otherwise — the fourth branch that rejects a
            // node and would drop its subtree.
            //
            // Only when the slot loop above did not already walk them. It walks the children of
            // any slot it rejected, so for a parameter that is not a composable slot both paths
            // fire, every reason below is duplicated, and a document conflicted at each level
            // doubles the work per level.
            if (children != null) {
              reasons +=
                "`${record.symbol.name}`.`${parameter.name}` is set as both a value and a slot"
              if (parameter.composableSlot) {
                children.forEach { node(it, depth + 1, inReceiverScope || hasReceiver(parameter)) }
              }
            }
          }
          children != null &&
            parameter.composableSlot &&
            !ComponentSnippets.acceptsBareLambda(parameter.type) -> {
            // `code.call` may have been emittable only because this slot was defaulted away. A
            // `(Int, Int) -> Unit` or `() -> String` slot cannot be satisfied by `{ children }`.
            reasons +=
              "`${record.symbol.name}`.`${parameter.name}` is `${parameter.type}`, which children " +
                "in a bare lambda cannot satisfy"
            // The third branch that rejects a node and would otherwise drop its subtree, after an
            // unresolved id and a slot the component never declared. All three now walk on.
            children.forEach { node(it, depth + 1, inReceiverScope) }
          }
          children != null && parameter.composableSlot -> {
            val nested =
              children.joinToString("\n") {
                node(it, depth + 1, inReceiverScope || hasReceiver(parameter))
              }
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
      val name =
        if (record.canonicalId in simplyImportable && !inReceiverScope)
          ComponentSnippets.escapeIfKeyword(record.symbol.name)
        else qualified
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
          is ScreenValue.Text ->
            when {
              type != "kotlin.String" -> null
              // A JVM constant-pool string is length-prefixed with an unsigned short, so a value
              // over 65535 modified-UTF-8 bytes cannot be a literal at all — the backend fails
              // late, on a file this generator has already called compilable. Nothing bounds a
              // pasted document value, so it is measured and refused rather than assumed small.
              modifiedUtf8Length(value.value) > MAX_CONSTANT_POOL_STRING -> {
                reasons +=
                  "`$owner`.`${parameter.name}` is ${modifiedUtf8Length(value.value)} bytes, past " +
                    "the $MAX_CONSTANT_POOL_STRING a JVM string constant can hold"
                return null
              }
              else -> quote(value.value)
            }
          is ScreenValue.Bool -> if (type == "kotlin.Boolean") value.value.toString() else null
          is ScreenValue.Whole ->
            when (type) {
              "kotlin.Int" ->
                // `toInt()` wraps silently: 2147483648 would be emitted as -2147483648, which
                // compiles and is not the number anyone entered.
                if (value.value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
                  value.value.toString()
                else null
              // `-9223372036854775808L` does not compile: Kotlin reads the positive token first
              // and rejects it as out of range, then applies unary minus. Verified with the
              // compiler, which is also why `Int.MIN_VALUE` is left as a plain literal — the same
              // spelling one type down *is* accepted.
              "kotlin.Long" ->
                if (value.value == Long.MIN_VALUE) "Long.MIN_VALUE" else "${value.value}L"
              else -> null
            }
          is ScreenValue.Fractional ->
            when {
              // Neither `NaN` nor `Infinity` is a Kotlin literal, so both would emit source the
              // compiler rejects.
              !value.value.isFinite() -> null
              type == "kotlin.Float" -> {
                // The same narrowing rule as `Int`, which the first pass missed one type down: a
                // `Double` past `Float`'s range becomes `Infinity`, and one below it collapses to
                // zero. The float's own rendering is emitted, so the literal is exactly the value
                // the parameter will hold rather than a `Double` spelling with an `f` stapled on.
                val narrowed = value.value.toFloat()
                val lost = !narrowed.isFinite() || (narrowed == 0.0f && value.value != 0.0)
                if (lost) null else "${narrowed}f"
              }
              type == "kotlin.Double" -> value.value.toString()
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

  /**
   * Whether [name] can be written into generated source as a bare declaration name.
   *
   * Three ways it cannot, and all three produce source the compiler rejects rather than a warning:
   * it is not an identifier at all (`my screen`), it is a hard keyword (`when`), or it is
   * all-underscore. The last is the least obvious — `_`, `__` and friends match every identifier
   * regex ever written and Kotlin reserves them, so `fun _()` fails with "Names _, __, ___, … are
   * reserved in Kotlin".
   */
  private fun isUsableIdentifier(name: String): Boolean =
    ComponentSnippets.isIdentifier(name) &&
      !ComponentSnippets.isHardKeyword(name) &&
      name.any { it != '_' }

  /**
   * An opt-in marker, spelled for source.
   *
   * The name arrives already in source notation — the producer rebuilds a nested marker's name from
   * its nesting chain, because `$` is a nesting separator in a binary name and an ordinary
   * character inside a backticked one, and only the chain tells them apart. All that is left here
   * is keyword escaping, since a marker under `com.`when`` is spelled without backticks anywhere it
   * is recorded.
   */
  private fun markerReference(marker: String): String =
    ComponentSnippets.escapeCallableIfKeyword(marker)

  /**
   * The bytes [value] occupies as a JVM constant-pool string.
   *
   * Modified UTF-8, not UTF-8: `NUL` is two bytes rather than one, and a supplementary character is
   * six (both halves of the surrogate pair encoded separately) rather than four.
   */
  private fun modifiedUtf8Length(value: String): Int = value.sumOf { c ->
    when {
      c.code in 1..0x7F -> 1
      c.code <= 0x7FF -> 2
      else -> 3
    }
  }

  /**
   * Whether children placed in [parameter] execute inside an implicit receiver.
   *
   * The **recorded** receiver is the answer, because the rendered type is a lossy spelling: a
   * nullable extension slot renders as `(ColumnScope.() -> Unit)?`, which has nothing at all before
   * its first `(`, so reading the receiver off the text answered "none" for exactly the slots most
   * likely to have one. The text is kept only as a fallback for a record written before
   * `composableSlotReceiver` existed, where null means "not recorded" rather than "no receiver" —
   * and there it is unwrapped first, so the same nullable case does not slip through twice.
   */
  private fun hasReceiver(parameter: TargetParameter): Boolean =
    parameter.composableSlotReceiver != null ||
      parameter.type.removePrefix("(").removeSuffix(")?").substringBefore('(').contains('.')

  private const val MAX_CONSTANT_POOL_STRING = 65535

  /**
   * Simple names the generated file has already spent on its own scaffolding.
   *
   * The wrapper always imports `androidx.compose.runtime.Composable`, so a catalog component that
   * happens to be called `Composable` would be imported alongside it and `Composable()` would be
   * ambiguous between the two. Such a component is called fully qualified instead — the same answer
   * the screen's own name and a two-package collision already get.
   */
  private val RESERVED_BY_THE_WRAPPER = setOf("Composable")

  /**
   * Kotlin's own identifier rule: `(Letter | '_') (Letter | '_' | UnicodeDigit)*`.
   *
   * Not `[A-Za-z_][A-Za-z0-9_]*`. `Übersicht`, `画面` and `généré` are identifiers Kotlin accepts
   * without backticks, and an ASCII-only rule refused documents that were never wrong — a refusal
   * costs nothing to the compiler and everything to whoever named their screen in their own
   * language.
   */
  private fun isIdentifier(name: String): Boolean =
    name.isNotEmpty() &&
      (name[0].isLetter() || name[0] == '_') &&
      name.all { it.isLetter() || it.isDigit() || it == '_' }
}
