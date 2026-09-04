package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.PreviewOverrideValue

/**
 * Flattens `renderNow.overrides.namedOverrides` — the typed seed bag both override formats share —
 * into the `name -> verbatim text` map a **parameter knob** binder takes.
 *
 * ### Why one seed map serves both formats
 *
 * `previewOverride*` declares a knob by executing a lookup inside the composable body, so it is
 * seeded by writing typed values into a process-static controller. A parameter knob is declared by
 * the function signature, so it is seeded by ordinary argument passing. A client shouldn't have to
 * know which of the two a given preview used, so both read the same `namedOverrides` map and each
 * side takes only the keys it recognises: the controller ignores a key no `previewOverride*` call
 * asked for, and the binder ignores a key that names no declared parameter.
 *
 * ### Why the text, rather than the typed value
 *
 * The binder parses against the knob's *declared* type, which is the only type that can be bound —
 * a client that sent `IntValue(3)` for a `Long` parameter meant 3, and one that sent
 * `StringValue("3")` for an `Int` parameter meant 3 as well. Reducing both to `"3"` and parsing
 * once, at the knob, keeps that decision in a single place and keeps an unparseable seed falling
 * back to the author default rather than being coerced into a value nobody asked for.
 *
 * [ColorValue][PreviewOverrideValue.ColorValue] is deliberately **not** flattened: `Color` is not a
 * seedable parameter-knob kind, so its `#AARRGGBB` text would only ever fail to parse as one of the
 * numeric kinds, and returning it would make a colour seed look like a dropped number rather than
 * an unsupported kind. A preview that wants an editable colour as a parameter takes it as an ARGB
 * `Long` and gets an ordinary numeric seed.
 */
public object PreviewKnobSeeds {

  /** The seed text for [value], or null when its kind has no parameter-knob equivalent. */
  public fun text(value: PreviewOverrideValue): String? =
    when (value) {
      is PreviewOverrideValue.StringValue -> value.value
      is PreviewOverrideValue.IntValue -> value.value.toString()
      is PreviewOverrideValue.BooleanValue -> value.value.toString()
      is PreviewOverrideValue.FloatValue -> value.value.toString()
      is PreviewOverrideValue.ColorValue -> null
    }

  /**
   * The argument array to invoke a preview declaring [knobs] with, given this render's typed seed
   * bag — `null` at every position no seed bound, which is how a partial seed says "leave this
   * parameter alone".
   *
   * Returns an empty list when nothing binds — no knobs, no seed that names one, nothing that
   * parses — so a caller keeps the zero-argument invoke a plain preview has always used rather than
   * constructing an all-null array that means the same thing.
   *
   * The array is sized by the highest knob index plus one rather than by [knobs].size: a knob's
   * index is its position in the *full* value-parameter list, which may include parameters that are
   * defaulted but not seedable (`modifier: Modifier = Modifier`). Those positions stay null and
   * take their defaults.
   *
   * A knob whose declared [PreviewKnobDto.type] this daemon does not know is dropped rather than
   * guessed at: a newer plugin may name a kind an older daemon cannot build, and degrading that one
   * parameter to its author default while the rest of the preview still seeds beats failing the
   * render. A seed whose text is not a valid value for its knob's type is dropped for the same
   * reason — coercing `"yes"` to `true`, or truncating `"1.5"` to an `Int`, would publish a capture
   * that silently disagrees with what the client asked for.
   */
  public fun bind(
    knobs: List<PreviewKnobDto>,
    values: Map<String, PreviewOverrideValue>?,
  ): List<Any?> {
    if (knobs.isEmpty()) return emptyList()
    val seeds = texts(values)
    if (seeds.isEmpty()) return emptyList()
    val byName = knobs.associateBy { it.name }
    val bound = seeds.mapNotNull { (name, raw) ->
      val knob = byName[name] ?: return@mapNotNull null
      parse(knob.type, raw)?.let { knob.index to it }
    }
    if (bound.isEmpty()) return emptyList()
    val size = knobs.maxOf { it.index } + 1
    val args = arrayOfNulls<Any?>(size)
    bound.forEach { (index, value) -> if (index in 0 until size) args[index] = value }
    return args.toList()
  }

  /**
   * The typed value for [raw] under the knob kind [type] names, or null when [type] is a kind this
   * daemon cannot build or [raw] is not a valid value of it.
   *
   * `toBooleanStrictOrNull` rather than `toBoolean`: the lenient form maps every non-`"true"`
   * string to `false`, so a malformed seed would silently render the opposite of a `true` default
   * instead of the default itself.
   */
  private fun parse(type: String, raw: String): Any? =
    when (type) {
      "STRING" -> raw
      "BOOLEAN" -> raw.toBooleanStrictOrNull()
      "INT" -> raw.toIntOrNull()
      "LONG" -> raw.toLongOrNull()
      "FLOAT" -> raw.toFloatOrNull()
      "DOUBLE" -> raw.toDoubleOrNull()
      else -> null
    }

  /** [text] applied across a whole seed bag, dropping the entries with no equivalent. */
  public fun texts(values: Map<String, PreviewOverrideValue>?): Map<String, String> {
    if (values.isNullOrEmpty()) return emptyMap()
    return buildMap {
      for ((key, value) in values) {
        text(value)?.let { put(key, it) }
      }
    }
  }
}
