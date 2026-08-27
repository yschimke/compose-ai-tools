package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.overrides.PreviewOverrideValue

/**
 * Wire codec for the `previewOverride*` knob seeds a held (interactive / live) Android session
 * carries across the Robolectric sandbox boundary — `PreviewOverrides.namedOverrides` in, the same
 * map out, with only `java.lang.String`s in between.
 *
 * **Why strings.** The bridge command
 * ([ee.schimke.composeai.daemon.bridge.InteractiveCommand.Start]) may reference only `java.*` types
 * (see `DaemonHostBridge`'s file KDoc), and [PreviewOverrideValue] is in the instrumented
 * `ee.schimke.composeai` namespace: the sandbox re-loads it, so a host-side instance that did cross
 * would not satisfy the sandbox controller's `as? IntValue` reads. [encode] runs host-side,
 * [decode] runs inside the sandbox, and the values the controller compares against are minted by
 * the classloader that reads them.
 *
 * The encoding is the same `<kind>:<raw>` shape the rest of the stack already speaks — the wire
 * spellings are [PreviewOverrideValue]'s own `@SerialName`s (`string` / `int` / `float` / `bool` /
 * `color`), and the raw half is the value's `toString`-equivalent text. A value whose raw half no
 * longer parses to its kind is dropped rather than guessed, which leaves the author default in
 * place — the same answer the type-strict host gives a mismatched seed.
 */
internal object HeldNamedOverrides {

  /** Host-side: `seedKey → "<kind>:<raw>"`, ready to ride `InteractiveCommand.Start`. */
  fun encode(seeds: Map<String, PreviewOverrideValue>?): Map<String, String> {
    if (seeds.isNullOrEmpty()) return emptyMap()
    val out = LinkedHashMap<String, String>(seeds.size)
    for ((key, value) in seeds) {
      out[key] =
        when (value) {
          is PreviewOverrideValue.StringValue -> "string:${value.value}"
          is PreviewOverrideValue.IntValue -> "int:${value.value}"
          is PreviewOverrideValue.FloatValue -> "float:${value.value}"
          is PreviewOverrideValue.BooleanValue -> "bool:${value.value}"
          is PreviewOverrideValue.ColorValue -> "color:${value.argb}"
        }
    }
    return out
  }

  /** Sandbox-side: back to the typed bag the `previewOverride*` reads resolve against. */
  fun decode(encoded: Map<String, String>?): Map<String, PreviewOverrideValue>? {
    if (encoded.isNullOrEmpty()) return null
    val out = LinkedHashMap<String, PreviewOverrideValue>(encoded.size)
    for ((key, wire) in encoded) {
      val separator = wire.indexOf(':')
      if (separator <= 0) continue
      val raw = wire.substring(separator + 1)
      val value =
        when (wire.substring(0, separator)) {
          // An empty string is a real value here (a cleared label, a variant seeded to ""), which
          // is why only the non-string kinds parse-or-drop.
          "string" -> PreviewOverrideValue.StringValue(raw)
          "int" -> raw.toIntOrNull()?.let { PreviewOverrideValue.IntValue(it) }
          "float" -> raw.toFloatOrNull()?.let { PreviewOverrideValue.FloatValue(it) }
          "bool" -> PreviewOverrideValue.BooleanValue(raw.equals("true", ignoreCase = true))
          "color" -> PreviewOverrideValue.ColorValue(raw)
          else -> null
        } ?: continue
      out[key] = value
    }
    return out.ifEmpty { null }
  }
}
