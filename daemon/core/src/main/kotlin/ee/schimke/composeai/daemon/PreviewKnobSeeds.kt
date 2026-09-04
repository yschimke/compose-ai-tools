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
