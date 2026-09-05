package ee.schimke.composeai.renderer

import ee.schimke.composeai.daemon.protocol.PreviewOverrideValue
import ee.schimke.composeai.data.overrides.PreviewOverrideDeclaration
import ee.schimke.composeai.data.overrides.PreviewOverrideOption
import ee.schimke.composeai.data.overrides.PreviewOverrideType

/**
 * Makes a preview's **parameter knobs** count during an offline render — the half of the knob
 * format the bake lane was missing.
 *
 * ### What was missing, and why it mattered off the daemon
 *
 * `previewOverride*` publishes a declaration as a by-product of *reading* a knob inside the
 * composable body, so a bake picks it up for free: [RobolectricRenderTestBase] clears the
 * controller before composing and drains it into `renders/<stem>.overrides.json` afterwards. A
 * parameter knob is declared by the function signature and read by ordinary argument passing, so
 * nothing in the composition ever announces it and that drain came back empty.
 *
 * That sidecar is not a nicety. `compose-preview serve` reads its override list from the bundled
 * sidecar — for a daemon-backed host too, since the daemon supplies renders and not declarations —
 * so a preview migrated to parameter knobs served an empty control list and any consumer selecting
 * on a knob key found nothing. Recording the declarations here, on the same controller channel the
 * other format uses, puts both formats in the sidecar and leaves every downstream reader unchanged.
 *
 * ### Why this is a renderer-local mirror
 *
 * The daemon has the same two mappings (`PreviewKnobSeeds`, `PreviewKnobDeclarations` in
 * `:daemon:core`). This renderer cannot reach them: it is published as `renderer-android` and
 * injected into a consumer's Robolectric test classpath, and `daemon-core` would drag the JSON-RPC
 * server and the in-process compile service along with it. Same trade the manifest DTOs already
 * make — see [RenderPreviewKnob] and its siblings, each "a renderer-side mirror of the plugin's …".
 */
internal object PreviewKnobBake {

  /**
   * The argument array to invoke a preview declaring [knobs] with, given this render's
   * `@OverrideVariant` seed bag — `null` at every position no seed bound, which is how a partial
   * seed says "leave this parameter alone" to [PreviewParameterSupport.invokeWithDefaultMask].
   *
   * Empty when nothing binds, so a preview nobody seeded keeps the zero-argument invoke it has
   * always used rather than an all-null array meaning the same thing.
   *
   * Sized by the highest knob index plus one rather than by [knobs].size: an index is a position in
   * the *full* value-parameter list, which may include defaulted parameters that are not knobs
   * (`modifier: Modifier = Modifier`). Those positions stay null and take their compiled defaults.
   */
  fun seedArgs(
    knobs: List<RenderPreviewKnob>,
    seeds: Map<String, PreviewOverrideValue>?,
  ): List<Any?> {
    if (knobs.isEmpty() || seeds.isNullOrEmpty()) return emptyList()
    val byName = knobs.associateBy { it.name }
    val bound = seeds.mapNotNull { (name, value) ->
      val knob = byName[name] ?: return@mapNotNull null
      val text = seedText(value) ?: return@mapNotNull null
      parse(knob, text)?.let { knob.index to it }
    }
    if (bound.isEmpty()) return emptyList()
    val size = knobs.maxOf { it.index } + 1
    val args = arrayOfNulls<Any?>(size)
    bound.forEach { (index, value) -> if (index in 0 until size) args[index] = value }
    return args.toList()
  }

  /**
   * The declarations for [knobs] under this render's [seeds], skipping the knobs that cannot be
   * declared honestly. Empty when nothing can be — the overwhelmingly common case, since most
   * previews declare no knobs at all.
   *
   * **A knob whose default discovery could not recover is left out.**
   * `PreviewOverrideDeclaration.default` is not nullable, so declaring one would mean inventing a
   * value — an empty string, a zero — and presenting it as what the author wrote, giving a viewer a
   * wrong default and a "reset" that resets to something the preview never said.
   *
   * `current` is the value actually in force: the seed where one bound, the author default
   * otherwise, resolved through [seedArgs] so the control cannot disagree with the pixels beside
   * it.
   */
  fun declarations(
    knobs: List<RenderPreviewKnob>,
    seeds: Map<String, PreviewOverrideValue>?,
  ): List<PreviewOverrideDeclaration> {
    if (knobs.isEmpty()) return emptyList()
    val bound = seedArgs(knobs, seeds)
    return knobs.mapNotNull { knob ->
      val default = knob.default ?: return@mapNotNull null
      val type = declaredTypeOf(knob.type) ?: return@mapNotNull null
      val seeded = bound.getOrNull(knob.index)
      PreviewOverrideDeclaration(
        key = knob.name,
        type = type,
        // No author-supplied label exists: a parameter has a name and nothing else. The
        // `previewOverride*` host does the same, so a viewer renders both formats identically.
        label = knob.name,
        default = valueOf(type, default),
        current = valueOf(type, seeded?.toString() ?: default),
        // A parameter list is fixed-arity, so there is no per-row value to address — always the
        // un-indexed seed key.
        index = null,
        // A closed set, and closed *exhaustively*: an enum parameter cannot hold anything but one
        // of its constants. Empty for every open kind, leaving the control a plain field.
        options = knob.options.map { PreviewOverrideOption(it) },
        optionsExhaustive = knob.options.isNotEmpty(),
      )
    }
  }

  /**
   * [args] with every enum knob's constant **name** replaced by the constant itself, read off
   * [parameterTypes] — the preview's own value-parameter types, in order.
   *
   * This is the seam an enum knob cannot be bound without: a name is all a seed carries and all
   * [seedArgs] can produce, because neither this renderer nor the daemon that sends the seed holds
   * the enum `Class` at that point. The invoke path does, so the conversion happens there, once.
   *
   * A position that is not an enum, is not a `String`, or names no constant of its type is left
   * exactly as it was — a seed that cannot become a constant falls back to the author default
   * rather than failing the render. The same list comes back when nothing moved, so a caller can
   * tell "no enum here" from "an enum was converted" by identity.
   */
  fun coerceToParameterTypes(args: List<Any?>, parameterTypes: Array<Class<*>>): List<Any?> {
    if (args.isEmpty() || args.none { it is String }) return args
    val coerced = args.mapIndexed { index, value ->
      val type = parameterTypes.getOrNull(index) ?: return@mapIndexed value
      if (value !is String || !type.isEnum) return@mapIndexed value
      type.enumConstants?.firstOrNull { (it as? Enum<*>)?.name == value } ?: value
    }
    return if (coerced == args) args else coerced
  }

  /**
   * The seed text for [value], or null when its kind has no parameter-knob equivalent.
   *
   * `ColorValue` is deliberately absent: `Color` is not a seedable parameter kind, so its
   * `#AARRGGBB` text could only ever fail to parse as one of the numeric kinds. A preview wanting
   * an editable colour as a parameter takes an ARGB `Long` and gets an ordinary numeric seed.
   */
  private fun seedText(value: PreviewOverrideValue): String? =
    when (value) {
      is PreviewOverrideValue.StringValue -> value.value
      is PreviewOverrideValue.IntValue -> value.value.toString()
      is PreviewOverrideValue.BooleanValue -> value.value.toString()
      is PreviewOverrideValue.FloatValue -> value.value.toString()
      is PreviewOverrideValue.ColorValue -> null
    }

  /**
   * The typed value for [raw] under the kind [type] names, or null when this renderer cannot build
   * that kind or [raw] is not a valid value of it.
   *
   * `toBooleanStrictOrNull` rather than `toBoolean`: the lenient form maps every non-`"true"`
   * string to `false`, so a malformed seed would render the opposite of a `true` default instead of
   * the default itself. A seed dropped here falls back to the author default, which is always a
   * value the preview actually names — unlike a coerced one.
   */
  private fun parse(knob: RenderPreviewKnob, raw: String): Any? =
    when (knob.type) {
      "STRING" -> raw
      "BOOLEAN" -> raw.toBooleanStrictOrNull()
      "INT" -> raw.toIntOrNull()
      "LONG" -> raw.toLongOrNull()
      "FLOAT" -> raw.toFloatOrNull()
      "DOUBLE" -> raw.toDoubleOrNull()
      // An enum knob binds by constant NAME and stays a name here — the manifest carries no
      // `Class`, and the invoke seam coerces it against the parameter's own type. A name that is
      // not one of the declared constants is dropped like any other unparseable seed.
      "ENUM" -> raw.takeIf { it in knob.options }
      else -> null
    }

  /**
   * The declaration type for a knob kind, or null when there is none.
   *
   * `LONG` and `DOUBLE` map to `STRING` because [PreviewOverrideType] has no wider numerics. That
   * costs the control's *shape*, not its reach: a text seed reaches the parameter through the same
   * path and parses against the knob's own kind, so a `Long` seeded as `"4281558783"` binds exactly
   * as it would have. A kind this renderer does not know is dropped rather than guessed at.
   */
  private fun declaredTypeOf(knobType: String): String? =
    when (knobType) {
      "STRING",
      "LONG",
      "DOUBLE",
      // An enum is declared as text whose accepted values are enumerated alongside it: the picker
      // comes from `options` + `optionsExhaustive`, not from a distinct declaration type.
      "ENUM" -> PreviewOverrideType.STRING
      "BOOLEAN" -> PreviewOverrideType.BOOL
      "INT" -> PreviewOverrideType.INT
      "FLOAT" -> PreviewOverrideType.FLOAT
      else -> null
    }

  /**
   * [text] as the declaration value of [type], falling back to a string value when it does not
   * parse — reachable only if discovery recovered a constant whose text doesn't round-trip through
   * its own declared type, where the raw text at least reports what the class file said.
   */
  private fun valueOf(type: String, text: String): PreviewOverrideValue =
    when (type) {
      PreviewOverrideType.INT ->
        text.toIntOrNull()?.let { PreviewOverrideValue.IntValue(it) }
          ?: PreviewOverrideValue.StringValue(text)
      PreviewOverrideType.FLOAT ->
        text.toFloatOrNull()?.let { PreviewOverrideValue.FloatValue(it) }
          ?: PreviewOverrideValue.StringValue(text)
      PreviewOverrideType.BOOL ->
        text.toBooleanStrictOrNull()?.let { PreviewOverrideValue.BooleanValue(it) }
          ?: PreviewOverrideValue.StringValue(text)
      else -> PreviewOverrideValue.StringValue(text)
    }
}
