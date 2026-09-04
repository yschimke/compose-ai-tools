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
    val body = StringBuilder()

    screen.roots.forEach { node -> emit(node, specs, imports, problems, body, depth = 2) }

    val source = buildString {
      append("import ")
      // Sorted here rather than kept in a sorted set: `sortedSetOf` is JVM-only and this module
      // compiles to wasmJs, where the builder runs.
      append(imports.sorted().joinToString("\nimport "))
      append("\n\n@Composable\nfun ")
      append(functionNameFor(screen.name))
      append("() {\n")
      if (body.isEmpty()) append("  // (empty screen)\n") else append(body)
      append("}\n")
    }
    return GeneratedScreen(source, problems)
  }

  private fun emit(
    node: ScreenNode,
    specs: Map<String, ComponentSpec>,
    imports: MutableSet<String>,
    problems: MutableList<String>,
    out: StringBuilder,
    depth: Int,
  ) {
    val pad = " ".repeat(depth)
    val spec = specs[node.componentId]
    if (spec == null) {
      problems += "no spec for component '${node.componentId}'"
      out.append(pad).append("// TODO unknown component '").append(node.componentId).append("'\n")
      // Its children are still the user's work, so they are emitted at this level rather than
      // dropped with the parent — losing a subtree because its container is unknown would throw
      // away far more than it reports.
      node.children.forEach { emit(it, specs, imports, problems, out, depth) }
      return
    }
    imports += spec.imports

    val args = ArrayList<String>()
    // Sorted by key so a document generates the same source every time, whatever order a builder
    // happened to write its knobs in. `toSortedMap` is JVM-only; this is the multiplatform
    // spelling.
    node.knobs.entries
      .sortedBy { it.key }
      .forEach { (key, value) ->
        val knob = spec.knobs[key]
        if (knob == null) {
          problems += "component '${node.componentId}' has no parameter for knob '$key'"
          out
            .append(pad)
            .append("// TODO knob '")
            .append(key)
            .append("' has no parameter on ")
            .append(spec.call)
            .append("\n")
        } else {
          if (knob.kind == KnobKind.COLOR) imports += "androidx.compose.ui.graphics.Color"
          if (knob.kind == KnobKind.DP) imports += "androidx.compose.ui.unit.dp"
          args += "${knob.parameter} = ${literal(knob.kind, value)}"
        }
      }

    val slotted = node.children.filter { it.slot != null }
    val ordered = node.children.filter { it.slot == null }

    slotted.forEach { child ->
      val parameter = spec.slots[child.slot]
      if (parameter == null) {
        problems += "component '${node.componentId}' has no slot '${child.slot}'"
        out
          .append(pad)
          .append("// TODO no slot '")
          .append(child.slot)
          .append("' on ")
          .append(spec.call)
          .append("\n")
      } else {
        val nested = StringBuilder()
        emit(child, specs, imports, problems, nested, depth + 2)
        args += "$parameter = {\n$nested$pad}"
      }
    }

    if (ordered.isNotEmpty() && !spec.container) {
      problems += "component '${node.componentId}' takes no children but has ${ordered.size}"
      out
        .append(pad)
        .append("// TODO ")
        .append(spec.call)
        .append(" takes no children; ")
        .append(ordered.size)
        .append(" dropped\n")
    }

    out.append(pad).append(spec.call).append('(')
    if (args.isNotEmpty()) out.append(args.joinToString(", "))
    out.append(')')
    if (spec.container && ordered.isNotEmpty()) {
      out.append(" {\n")
      ordered.forEach { emit(it, specs, imports, problems, out, depth + 2) }
      out.append(pad).append("}")
    }
    out.append('\n')
  }

  /**
   * A knob's value as a Kotlin literal.
   *
   * A value that does not parse as its declared kind falls back to a **quoted string plus a
   * comment**, not to a zero: a `0` where the user typed `abc` compiles and is wrong, which is the
   * one outcome worse than not compiling.
   */
  private fun literal(kind: KnobKind, value: String): String =
    when (kind) {
      KnobKind.STRING -> quote(value)
      KnobKind.INT -> value.trim().toIntOrNull()?.toString() ?: badLiteral(value)
      KnobKind.FLOAT -> value.trim().toFloatOrNull()?.let { "${it}f" } ?: badLiteral(value)
      KnobKind.BOOLEAN -> value.trim().toBooleanStrictOrNull()?.toString() ?: badLiteral(value)
      KnobKind.DP -> value.trim().toFloatOrNull()?.let { "${it}.dp" } ?: badLiteral(value)
      KnobKind.COLOR -> colorLiteral(value) ?: badLiteral(value)
    }

  private fun badLiteral(value: String): String = "${quote(value)} /* TODO not a valid value */"

  /** `#AARRGGBB` / `#RRGGBB`, with or without the `#`, as `Color(0xAARRGGBB)`. */
  private fun colorLiteral(value: String): String? {
    val raw = value.trim().removePrefix("#")
    val parsed = raw.toLongOrNull(16) ?: return null
    return when (raw.length) {
      8 -> "Color(0x${raw.uppercase()})"
      6 -> "Color(0xFF${raw.uppercase()})"
      else -> null
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
