package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.PreviewOverrideValue
import ee.schimke.composeai.data.overrides.PreviewOverrideDeclaration
import ee.schimke.composeai.data.overrides.PreviewOverrideOption
import ee.schimke.composeai.data.overrides.PreviewOverrideType

/**
 * Turns a preview's **parameter knobs** into the `PreviewOverrideDeclaration`s a viewer draws its
 * controls from — the last seam between the two override formats.
 *
 * ### Why declarations, and why through this type
 *
 * `previewOverride*` publishes a declaration per knob as a by-product of *reading* it: the lookup
 * knows the key, the type, the author default and the value in force, and records all four. That is
 * what fills `previews/<id>.overrides.json` and `data/fetch?kind=compose/overrides`, and what a
 * viewer's control list, the serve `?knob.<key>=` UI and `serve-lanes.spec.mjs` all read.
 *
 * A parameter knob is declared by a signature and read by ordinary argument passing, so nothing in
 * the composition ever announces it. Building the same declarations here and recording them on the
 * render puts both formats on one channel: every consumer downstream keeps working unchanged, and
 * neither has to know which format a given preview used.
 *
 * ### What it will not declare
 *
 * **A knob whose default this could not recover.** `PreviewOverrideDeclaration.default` is not
 * nullable, so declaring one means inventing a value — an empty string, a zero — and presenting it
 * as what the author wrote. A viewer would then show a wrong default and offer a "reset" that
 * resets to something the preview never said. Leaving the knob undeclared costs its editability and
 * says nothing false; that is the better trade until the declaration contract can carry an absent
 * default. Discovery recovers a literal default (`PreviewKnobDefaults`), so this only bites a knob
 * defaulted to an *expression* — `stringResource(...)`, `Color(0xFF3366FF)`, `itemCount + 1`.
 *
 * ### Where `Long` and `Double` go
 *
 * [PreviewOverrideType] has `STRING` / `INT` / `FLOAT` / `BOOL` / `COLOR` and no wider numerics, so
 * a `Long` or `Double` knob is declared as **text**. That is not a downgrade in what can be edited:
 * a text seed reaches the parameter through the same `PreviewKnobSeeds` path and parses against the
 * knob's declared kind, so a `Long` seeded as `"4281558783"` binds exactly as it would have. The
 * viewer draws a text field instead of a number field, which is the whole of the difference — and
 * it is why an ARGB `Long` (the format's stand-in for an editable colour) gets a text box rather
 * than a colour picker.
 */
public object PreviewKnobDeclarations {

  /**
   * The declarations for [knobs] under this render's [seeds], skipping the knobs that cannot be
   * declared honestly. Empty when nothing can be — the overwhelmingly common case, since most
   * previews declare no knobs at all.
   *
   * `current` is the value actually in force: the seed when one bound, the author default
   * otherwise, resolved by the same rules the renderer binds arguments with. A viewer showing a
   * `current` that disagreed with the pixels beside it would be worse than showing nothing.
   */
  public fun of(
    knobs: List<PreviewKnobDto>,
    seeds: Map<String, PreviewOverrideValue>?,
  ): List<PreviewOverrideDeclaration> {
    if (knobs.isEmpty()) return emptyList()
    val bound = PreviewKnobSeeds.bind(knobs, seeds)
    return knobs.mapNotNull { knob ->
      val default = knob.default ?: return@mapNotNull null
      val type = declaredTypeOf(knob.type) ?: return@mapNotNull null
      // The bound argument for this knob's position, when a seed reached it. `bind` returns an
      // empty list when nothing bound at all, and null at every position no seed named — the same
      // "use the author default" signal the renderer acts on, so the two cannot disagree.
      val seeded = bound.getOrNull(knob.index)
      PreviewOverrideDeclaration(
        key = knob.name,
        type = type,
        // No author-supplied label exists: a parameter has a name and nothing else. The
        // `previewOverride*` host does the same, so a viewer renders both formats identically.
        label = knob.name,
        default = valueOf(type, default),
        current = valueOf(type, seeded?.toString() ?: default),
        // Parameter knobs have no indexed form — a parameter list is fixed-arity, so there is no
        // per-row value to address. Always the un-indexed seed key.
        index = null,
        // A closed set, and closed *exhaustively*: an enum parameter cannot hold a value that is
        // not one of its constants, so a viewer is right to refuse anything else. Empty for every
        // open kind, which leaves the control a plain field exactly as before.
        options = knob.options.map { PreviewOverrideOption(it) },
        optionsExhaustive = knob.options.isNotEmpty(),
      )
    }
  }

  /**
   * The declaration type for a discovery knob kind, or null when there is none.
   *
   * `LONG` and `DOUBLE` map to `STRING` — see the class KDoc for why that costs only the control's
   * shape, not its reach. A kind this doesn't know is dropped rather than guessed: a newer plugin
   * naming one must leave the knob undeclared, not declared as something it isn't.
   */
  private fun declaredTypeOf(knobType: String): String? =
    when (knobType) {
      "STRING",
      "LONG",
      "DOUBLE",
      // An enum is declared as text whose accepted values are enumerated alongside it. The picker
      // comes from `options` + `optionsExhaustive`, not from a distinct declaration type — which is
      // exactly how `previewOverrideChoice` has always described a closed set.
      "ENUM" -> PreviewOverrideType.STRING
      "BOOLEAN" -> PreviewOverrideType.BOOL
      "INT" -> PreviewOverrideType.INT
      "FLOAT" -> PreviewOverrideType.FLOAT
      else -> null
    }

  /**
   * [text] as the declaration value of [type], falling back to a string value when it does not
   * parse.
   *
   * The fallback is reachable only if discovery recovered a constant whose text doesn't round-trip
   * through its own declared type, which would be a bug upstream — but showing the raw text beats
   * substituting a zero, because the text is at least what the class file said.
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
