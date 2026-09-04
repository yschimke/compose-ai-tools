package ee.schimke.composeai.screen

import kotlinx.serialization.Serializable

/** The Kotlin type a knob's value is written as, which decides how its literal is spelled. */
@Serializable
public enum class KnobKind {
  STRING,
  INT,
  FLOAT,
  BOOLEAN,
  /** An ARGB colour, spelled `Color(0xAARRGGBB)`. */
  COLOR,
  /** A density-independent size, spelled `<n>.dp`. */
  DP,
}

/**
 * How one knob of a component maps onto the generated call — the argument name it is passed as, and
 * how to spell its literal.
 *
 * @property parameter the Kotlin parameter name. Often the knob key, but not always: a catalog's
 *   `label` knob may be the composable's `text`.
 */
@Serializable
public data class KnobSpec(val parameter: String, val kind: KnobKind = KnobKind.STRING)

/**
 * How one catalog component id becomes Compose source.
 *
 * This table is **the catalog's** to supply — the model does not know that `button-filled` is a
 * `Button`, and should not. That keeps codegen honest about its own limits: a component with no
 * spec cannot be generated, and says so, instead of emitting something that looks like Kotlin and
 * does not compile.
 *
 * @property call the composable to call.
 * @property imports the fully-qualified imports the call needs. Emitted sorted and de-duplicated
 *   across the whole file.
 * @property knobs the per-knob argument mapping. A knob the spec does not name is **dropped with a
 *   comment** rather than guessed at as a parameter name.
 * @property container whether the call takes a trailing content lambda. A node with children whose
 *   spec is not a container is a document the builder should not have produced; codegen reports it
 *   rather than silently discarding the children.
 * @property slots the named slots this component accepts, each mapped to the parameter that
 *   receives it (`topBar` -> `topBar`). A child naming a slot absent here is reported.
 */
@Serializable
public data class ComponentSpec(
  val call: String,
  val imports: List<String> = emptyList(),
  val knobs: Map<String, KnobSpec> = emptyMap(),
  val container: Boolean = false,
  val slots: Map<String, String> = emptyMap(),
  /**
   * Arguments emitted verbatim on every call, ahead of the knobs.
   *
   * Real composables have required parameters a screen document has no opinion about — `Button`
   * needs an `onClick`. Without these the generated file would be missing an argument the compiler
   * demands, which is exactly the "looks like Kotlin, does not compile" outcome this table exists
   * to avoid.
   */
  val requiredArgs: List<String> = emptyList(),
  /**
   * A knob that becomes the call's **content** rather than an argument — `Button(onClick = {}) {
   * Text("Open") }`.
   *
   * The catalog's `label` knob is a button's child, not a parameter, and there is no `label =` to
   * pass it to. [contentCall] wraps its value, so the generated line is the one a developer writes.
   * Ignored on a [container], whose content is its children.
   */
  val contentKnob: String? = null,
  val contentCall: String = "Text",
)

/** The generated file, plus everything codegen could not express. */
public data class GeneratedScreen(
  val source: String,
  /**
   * What the document asked for and the spec table could not deliver — an unknown component, an
   * unmapped knob, children on a non-container.
   *
   * Returned rather than thrown, and rendered into the source as comments, because a builder's job
   * is to keep working while one node is unrepresentable. A silent drop would be the worst of the
   * three outcomes: the user sees code that is missing something with no clue what.
   */
  val problems: List<String> = emptyList(),
  /**
   * Spans describing what every character of [source] is, for a highlighter.
   *
   * Recorded by codegen as it writes, never re-derived: the builder highlights source *it
   * generated*, and re-lexing that output would be a second parser that can disagree with the thing
   * it parses. The list **tiles [source] exactly** — sorted, non-overlapping, every offset covered
   * once — so a renderer walks it in order with no gap handling.
   */
  val tokens: List<SourceToken> = emptyList(),
)

/**
 * Turns a [Screen] into Compose source.
 *
 * ### Why the output is source and not a runtime tree
 *
 * The surface that *runs* generated code already exists — the playground compiles Kotlin with no
 * Gradle and streams a live composition. So Kotlin is the interchange format between the builder
 * and everything downstream, and the tree never needs an interpreter. It also means the generated
 * screen is the same artefact a developer would have written by hand, which is the point of
 * building on a code-first catalog at all.
 *
 * ### What it will not do
 *
 * It will not invent. An id with no [ComponentSpec], a knob the spec does not map, children on a
 * component that takes none: each is recorded in [GeneratedScreen.problems] and marked in the
 * source with a `// TODO` comment naming exactly what was dropped. Emitting a plausible guess would
 * produce a file that fails to compile somewhere else, which is a much worse debugging experience
 * than a comment at the point of loss.
 */
public object ScreenCodegen {

  public fun generate(screen: Screen, specs: Map<String, ComponentSpec>): GeneratedScreen {
    val problems = ArrayList<String>()
    val imports = mutableSetOf("androidx.compose.runtime.Composable")
    val body = SpanBuilder()

    screen.roots.forEach { node -> emit(node, specs, imports, problems, body, depth = 2) }

    val out = SpanBuilder()
    // Sorted here rather than kept in a sorted set: `sortedSetOf` is JVM-only and this module
    // compiles to wasmJs, where the builder runs.
    imports.sorted().forEachIndexed { index, fqName ->
      if (index > 0) out.plain("\n")
      out.token("import", SourceTokenKind.KEYWORD)
      out.plain(" ")
      out.plain(fqName)
    }
    out.plain("\n\n")
    out.token("@Composable", SourceTokenKind.ANNOTATION)
    out.plain("\n")
    out.token("fun", SourceTokenKind.KEYWORD)
    out.plain(" ")
    out.token(functionNameFor(screen.name), SourceTokenKind.CALL)
    out.plain("() {\n")
    if (body.isEmpty()) {
      out.plain("  ")
      out.token("// (empty screen)", SourceTokenKind.COMMENT)
      out.plain("\n")
    } else {
      out.splice(body)
    }
    out.plain("}\n")

    return GeneratedScreen(out.build(), problems, out.tile())
  }

  private fun emit(
    node: ScreenNode,
    specs: Map<String, ComponentSpec>,
    imports: MutableSet<String>,
    problems: MutableList<String>,
    out: SpanBuilder,
    depth: Int,
  ) {
    val pad = " ".repeat(depth)
    val spec = specs[node.componentId]
    if (spec == null) {
      problems += "no spec for component '${node.componentId}'"
      out.comment(pad, "// TODO unknown component '${node.componentId}'")
      // Its children are still the user's work, so they are emitted at this level rather than
      // dropped with the parent — losing a subtree because its container is unknown would throw
      // away far more than it reports.
      node.children.forEach { emit(it, specs, imports, problems, out, depth) }
      return
    }
    imports += spec.imports

    val args = ArrayList<SpanBuilder>()
    spec.requiredArgs.forEach { arg -> args += SpanBuilder().also { it.plain(arg) } }
    var content: SpanBuilder? = null
    // Sorted by key so a document generates the same source every time, whatever order a builder
    // happened to write its knobs in. `toSortedMap` is JVM-only; this is the multiplatform
    // spelling.
    node.knobs.entries
      .sortedBy { it.key }
      .forEach { (key, value) ->
        val knob = spec.knobs[key]
        if (key == spec.contentKnob && !spec.container) {
          // The catalog's `label` is a button's child, not a parameter — there is no `label =` to
          // pass it to, so it becomes the trailing content the developer would have written.
          content =
            SpanBuilder().apply {
              token(spec.contentCall, SourceTokenKind.CALL)
              plain("(")
              token(quote(value), SourceTokenKind.STRING)
              plain(")")
            }
        } else if (knob == null) {
          problems += "component '${node.componentId}' has no parameter for knob '$key'"
          out.comment(pad, "// TODO knob '$key' has no parameter on ${spec.call}")
        } else {
          if (knob.kind == KnobKind.COLOR) imports += "androidx.compose.ui.graphics.Color"
          if (knob.kind == KnobKind.DP) imports += "androidx.compose.ui.unit.dp"
          args +=
            SpanBuilder().apply {
              plain(knob.parameter)
              plain(" = ")
              splice(literal(knob.kind, value))
            }
        }
      }

    val slotted = node.children.filter { it.slot != null }
    val ordered = node.children.filter { it.slot == null }

    slotted.forEach { child ->
      val parameter = spec.slots[child.slot]
      if (parameter == null) {
        problems += "component '${node.componentId}' has no slot '${child.slot}'"
        out.comment(pad, "// TODO no slot '${child.slot}' on ${spec.call}")
      } else {
        val nested = SpanBuilder()
        emit(child, specs, imports, problems, nested, depth + 2)
        args +=
          SpanBuilder().apply {
            plain(parameter)
            plain(" = {\n")
            splice(nested)
            plain(pad)
            plain("}")
          }
      }
    }

    if (ordered.isNotEmpty() && !spec.container) {
      problems += "component '${node.componentId}' takes no children but has ${ordered.size}"
      out.comment(pad, "// TODO ${spec.call} takes no children; ${ordered.size} dropped")
    }

    out.plain(pad)
    out.token(spec.call, SourceTokenKind.CALL)
    out.plain('(')
    args.forEachIndexed { index, arg ->
      if (index > 0) out.plain(", ")
      out.splice(arg)
    }
    out.plain(')')
    val contentSpans = content
    if (contentSpans != null) {
      out.plain(" { ")
      out.splice(contentSpans)
      out.plain(" }")
    } else if (spec.container && ordered.isNotEmpty()) {
      out.plain(" {\n")
      ordered.forEach { emit(it, specs, imports, problems, out, depth + 2) }
      out.plain(pad)
      out.plain("}")
    }
    out.plain('\n')
  }

  /** An indented whole-line `// TODO …`, the one shape codegen uses to mark what it dropped. */
  private fun SpanBuilder.comment(pad: String, text: String) {
    plain(pad)
    token(text, SourceTokenKind.COMMENT)
    plain("\n")
  }

  /**
   * A knob's value as a Kotlin literal, with its spans.
   *
   * A value that does not parse as its declared kind falls back to a **quoted string plus a
   * comment**, not to a zero: a `0` where the user typed `abc` compiles and is wrong, which is the
   * one outcome worse than not compiling.
   */
  private fun literal(kind: KnobKind, value: String): SpanBuilder =
    when (kind) {
      KnobKind.STRING -> SpanBuilder().apply { token(quote(value), SourceTokenKind.STRING) }
      KnobKind.INT -> value.trim().toIntOrNull()?.let { number(it.toString()) } ?: badLiteral(value)
      KnobKind.FLOAT -> value.trim().toFloatOrNull()?.let { number("${it}f") } ?: badLiteral(value)
      KnobKind.BOOLEAN ->
        value.trim().toBooleanStrictOrNull()?.let { boolean ->
          SpanBuilder().apply { token(boolean.toString(), SourceTokenKind.KEYWORD) }
        } ?: badLiteral(value)
      // `4.0.dp` is a number *and* a property read; the number stops at the dot, which is where an
      // IDE stops colouring it too.
      KnobKind.DP ->
        value.trim().toFloatOrNull()?.let { dp ->
          SpanBuilder().apply {
            token(dp.toString(), SourceTokenKind.NUMBER)
            plain(".dp")
          }
        } ?: badLiteral(value)
      KnobKind.COLOR -> colorLiteral(value) ?: badLiteral(value)
    }

  private fun number(text: String): SpanBuilder =
    SpanBuilder().apply { token(text, SourceTokenKind.NUMBER) }

  private fun badLiteral(value: String): SpanBuilder =
    SpanBuilder().apply {
      token(quote(value), SourceTokenKind.STRING)
      plain(" ")
      token("/* TODO not a valid value */", SourceTokenKind.COMMENT)
    }

  /** `#AARRGGBB` / `#RRGGBB`, with or without the `#`, as `Color(0xAARRGGBB)`. */
  private fun colorLiteral(value: String): SpanBuilder? {
    val raw = value.trim().removePrefix("#")
    raw.toLongOrNull(16) ?: return null
    val hex =
      when (raw.length) {
        8 -> "0x${raw.uppercase()}"
        6 -> "0xFF${raw.uppercase()}"
        else -> return null
      }
    return SpanBuilder().apply {
      token("Color", SourceTokenKind.CALL)
      plain("(")
      token(hex, SourceTokenKind.NUMBER)
      plain(")")
    }
  }

  private fun quote(value: String): String = buildString {
    append('"')
    value.forEach {
      when (it) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '$' -> append("\\$")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> append(it)
      }
    }
    append('"')
  }

  /**
   * A screen name as a Kotlin function name: the alphanumeric runs upper-camelled, prefixed if it
   * would start with a digit. `"my screen 2"` becomes `MyScreen2`; a name with nothing usable in it
   * becomes `GeneratedScreen`, because a generated file that does not compile helps nobody.
   */
  internal fun functionNameFor(name: String): String {
    val parts = name.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
    if (parts.isEmpty()) return "GeneratedScreen"
    val camel = parts.joinToString("") { it.replaceFirstChar { c -> c.uppercaseChar() } }
    return if (camel.first().isDigit()) "Screen$camel" else camel
  }
}
