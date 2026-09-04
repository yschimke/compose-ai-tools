package ee.schimke.composeai.renderer

import ee.schimke.composeai.daemon.protocol.PreviewOverrideValue
import ee.schimke.composeai.data.overrides.PreviewOverrideDeclaration
import ee.schimke.composeai.data.overrides.PreviewOverrideType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * One editable value parameter of a preview, as the render subprocess receives it.
 *
 * Desktop mirror of the plugin's `PreviewKnob`. [type] is carried as the plain enum **name** rather
 * than an enum of its own: a newer plugin naming a kind this renderer cannot build must cost that
 * one knob, not fail the whole payload and take the preview's other knobs with it.
 *
 * @property name the Kotlin parameter name, and the seed key that addresses it.
 * @property index the parameter's zero-based position in the function's **full** value-parameter
 *   list — which may include defaulted parameters that are not knobs (`modifier: Modifier =
 *   Modifier`), so it is not an index into a knob list.
 * @property default the parameter's literal default as seed text, or null when discovery could not
 *   recover one (an expression default — `stringResource(...)`, `itemCount + 1`).
 */
public data class PreviewKnobSpec(
  val name: String,
  val index: Int,
  val type: String,
  val default: String? = null,
)

/**
 * Makes a preview's **parameter knobs** count during an offline desktop render — the half of the
 * knob format the CMP bake lane was missing.
 *
 * ### What was missing, and why it mattered off the daemon
 *
 * `previewOverride*` publishes a declaration as a by-product of *reading* a knob inside the
 * composable body, so a bake picks it up for free: [renderPreview] clears the controller before
 * composing and [writePreviewOverridesSidecar] drains it into `<stem>.overrides.json` afterwards. A
 * parameter knob is declared by the function signature and read by ordinary argument passing, so
 * nothing in the composition ever announces it and that drain came back empty.
 *
 * That sidecar is not a nicety. `compose-preview serve` reads its override list from the bundled
 * sidecar — for a daemon-backed host too, since the daemon supplies renders and not declarations —
 * so a preview migrated to parameter knobs served an empty control list and any consumer selecting
 * on a knob key found nothing. Recording the declarations here, on the same controller channel the
 * other format uses, puts both formats in the sidecar and leaves every downstream reader unchanged.
 *
 * ### How the knobs get here
 *
 * Unlike the Android backend, this renderer has no manifest to read: it takes positional CLI args
 * plus per-capture system properties. The plugin therefore hands the knobs over as
 * [KNOBS_PROPERTY], a JSON array beside the `composeai.overrides.seed` it already sets, and the
 * pooled lane carries the same payload on its request frame — a warm worker outlives one capture,
 * so a knob list left in the environment would be read by whatever it drew next.
 *
 * ### Why this is a renderer-local mirror
 *
 * The daemon has the same two mappings (`PreviewKnobSeeds`, `PreviewKnobDeclarations` in
 * `:daemon:core`), and the Android renderer has its own copy for the same reason this one exists:
 * each renderer artefact is resolved into a consumer's graph on its own and must stay independently
 * buildable, which is the trade `PreviewParameterSupport` and `findComposableMethodWithArgs`
 * already make.
 */
internal object PreviewKnobBake {

  /**
   * The per-capture system property carrying this preview's knobs, as a JSON array of
   * [PreviewKnobSpec]. Absent or blank on every preview that declares none — which is nearly all of
   * them.
   */
  const val KNOBS_PROPERTY: String = "composeai.preview.knobs"

  /**
   * The knobs named by [KNOBS_PROPERTY], or empty when it is absent, blank or unreadable.
   *
   * Best-effort by design: a malformed payload costs this preview its knob controls and renders
   * every parameter at its author default, which is what the renderer did before the property
   * existed. Failing the capture instead would turn a wiring bug into a broken build.
   */
  fun fromSystemProperty(): List<PreviewKnobSpec> =
    System.getProperty(KNOBS_PROPERTY)?.takeIf { it.isNotBlank() }?.let(::parse) ?: emptyList()

  /**
   * [text] decoded as a knob array, or empty when it does not parse.
   *
   * Read off the JSON tree rather than through a generated serializer: this module does not apply
   * the serialization compiler plugin (nothing here was a wire type before), and adding it to a
   * published renderer artefact to decode four fields is the larger change. The shape is exactly
   * `previews.json`'s own `knobs` array, so the plugin serializes its `PreviewKnob` list unchanged.
   *
   * An entry missing `name`, `index` or `type` is dropped rather than defaulted: a knob with an
   * invented name or position would bind a seed to the wrong parameter, which is worse than the
   * knob not existing.
   */
  fun parse(text: String): List<PreviewKnobSpec> = runCatching {
    Json.parseToJsonElement(text).jsonArray.mapNotNull { element ->
      val fields = element.jsonObject
      val name = (fields["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
      val index = (fields["index"] as? JsonPrimitive)?.content?.toIntOrNull()
      val type = (fields["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
      if (name == null || index == null || type == null) null
      else
        PreviewKnobSpec(
          name = name,
          index = index,
          type = type,
          default = (fields["default"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
        )
    }
  }
    .getOrDefault(emptyList())

  /**
   * The argument array to invoke a preview declaring [knobs] with, given this render's
   * `@OverrideVariant` seed bag — `null` at every position no seed bound, which is how a partial
   * seed says "leave this parameter alone" to the defaults mask.
   *
   * The binding itself is [PreviewKnobArguments.bind], which already owns the per-kind parsing, the
   * drop-rather-than-coerce rule and the sizing-by-highest-index rule for this renderer. All this
   * adds is the two conversions that object cannot do: a typed [PreviewOverrideValue] down to the
   * verbatim text it parses, and a knob kind carried as a *name* down to the enum — dropping a kind
   * this renderer does not know rather than guessing at it, so a newer plugin costs one knob and
   * not the render.
   */
  /**
   * Record this render's parameter-knob declarations onto the override controller, reading both the
   * knob list and the seed from the ambient per-capture channels this subprocess was handed.
   *
   * `previewOverride*` records a declaration as a by-product of *reading* each knob during
   * composition; a parameter knob is read by argument passing and announces nothing, so a lane that
   * does not do this ships a `<stem>.overrides.json` with the knob missing and `serve` offers no
   * control for it. [renderPreview] has always done this with the knobs and seed already in hand;
   * the focus, motion and scroll lanes have neither, which is why they need this ambient form — and
   * why, before it, an interaction-state capture of a migrated preview served fewer controls than
   * its own resting capture.
   *
   * Call directly after `clearDeclarations()`, which is what makes the set this adds to clean.
   */
  fun recordAmbientDeclarations() {
    val seeds = ee.schimke.composeai.overrides.PreviewOverrideController.seededValues.value
    declarations(fromSystemProperty(), seeds).forEach {
      ee.schimke.composeai.overrides.PreviewOverrideController.record(it)
    }
  }

  fun seedArgs(
    knobs: List<PreviewKnobSpec>,
    seeds: Map<String, PreviewOverrideValue>?,
  ): List<Any?> {
    if (knobs.isEmpty() || seeds.isNullOrEmpty()) return emptyList()
    val bindable = knobs.mapNotNull { knob -> knob.toArgumentKnob() }
    if (bindable.isEmpty()) return emptyList()
    val texts = buildMap {
      for ((key, value) in seeds) {
        seedText(value)?.let { put(key, it) }
      }
    }
    return PreviewKnobArguments.bind(bindable, texts)
  }

  /**
   * This knob as the binder's own shape, or null when its [PreviewKnobSpec.type] names a kind this
   * renderer cannot build.
   */
  private fun PreviewKnobSpec.toArgumentKnob(): PreviewKnobArguments.Knob? = runCatching {
    PreviewKnobArguments.Type.valueOf(type)
  }
    .getOrNull()
    ?.let { PreviewKnobArguments.Knob(name, index, it) }

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
    knobs: List<PreviewKnobSpec>,
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
      )
    }
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
      "DOUBLE" -> PreviewOverrideType.STRING
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
