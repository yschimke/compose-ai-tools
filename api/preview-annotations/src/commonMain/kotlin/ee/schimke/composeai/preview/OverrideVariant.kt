package ee.schimke.composeai.preview

/**
 * Emits an **extra baked capture** of a `@Preview` composable with named `previewOverride*` values
 * seeded, so a state/content variant that differs *only* by an override knob — a toggled `checked`,
 * a disabled `enabled`, a different `label` — rides on the SAME preview function instead of a
 * duplicated `@Composable` wrapper. Repeatable: stack one per variant.
 *
 * The point is to stop authoring a second one-line `@Composable` (`fun SwitchOff() = … checked =
 * false …`) whose only difference from the primary is one flag. The primary already exposes that
 * flag as a `previewOverrideBoolean("checked", true)` knob; this annotation renders the primary a
 * second time with `checked = false` seeded, producing a distinct `<id>_VARIANT_<name>.png`. The
 * design-catalog fold then surfaces it as a secondary sticker under the primary — the same place a
 * hand-written `variants` entry used to land, minus the wrapper function and the spec entry.
 *
 * The compose-preview Gradle plugin's discovery task picks this up by FQN (mirroring
 * [AmbientPreview] / [GestureHintPreview] / [ScrollingPreview]); consumers that want to use the
 * annotation in their own code depend on `ee.schimke.composeai:preview-annotations`. The renderer
 * seeds the values through the same `PreviewOverrideController` the daemon's
 * `renderNow.overrides.namedOverrides` lane uses, so the baked variant and a live re-render land at
 * the same composable seam.
 *
 * The seed is **typed** — the value's Kotlin type must match what the composable reads, or the read
 * falls back to its author default (a `previewOverrideBoolean` only honours a boolean seed). Each
 * entry is `"key=value"`, or `"key#index=value"` for an indexed knob (`previewOverrideString("row",
 * …, index = 2)` → `"row#2=…"`). The array a value lives in picks its type: [booleans], [strings],
 * [ints], [floats], [colors] (ARGB hex, `#AARRGGBB` or `#RRGGBB`).
 *
 * Applies to every `@Preview` expansion on the function (each light/dark multipreview member gets
 * its own variant capture), the same "one annotation, applies to every expansion" policy
 * [ScrollingPreview] / [AmbientPreview] follow.
 *
 * Example — the on switch, its off state folded on without a second function:
 * ```
 * @CatalogWearModes
 * @OverrideVariant(name = "off", booleans = ["checked=false"])
 * @Composable
 * fun SwitchButtonOn() =
 *   WearSticker {
 *     SwitchButton(checked = previewOverrideBoolean("checked", true), onCheckedChange = {}, …)
 *   }
 * ```
 *
 * Renders on **both** backends: the Robolectric (Android/Wear) path and the desktop (Skiko) one.
 * `design-catalog-m3` is a CMP-desktop catalog and publishes `CheckboxChecked_*_VARIANT_unchecked`,
 * `SwitchOn_*_VARIANT_off` and the rest from these annotations, with matching `figma/…` vectors —
 * so a desktop catalog has no reason to keep a hand-written wrapper for a variant that differs only
 * by a knob.
 *
 * Interaction states are addressable variants too. They use real harness input rather than a
 * preview-only `MutableInteractionSource`:
 * ```
 * @OverrideVariant(name = "hovered", interaction = VariantInteraction.Hovered)
 * @OverrideVariant(name = "focused", interaction = VariantInteraction.Focused)
 * @OverrideVariant(name = "pressed", interaction = VariantInteraction.Pressed)
 * @Composable
 * fun FilledButton() = Button(onClick = {}) { Text("Button") }
 * ```
 *
 * Each produces its own `_VARIANT_<name>` preview id. [interactionIndex] selects among multiple
 * interactive nodes; it defaults to the first.
 *
 * ## Hoisting a whole matrix onto one annotation
 *
 * `@Target` includes `ANNOTATION_CLASS`, so a set of variants that several components share can be
 * declared **once** on an annotation class and applied with one line each — the same "declare the
 * fan-out once, tag the functions" move a multi-preview annotation makes for `@Preview`:
 * ```
 * @OverrideVariant(name = "xs", strings = ["size=xs"])
 * @OverrideVariant(name = "xs-square", strings = ["size=xs", "shape=square"])
 * // … one per cell …
 * annotation class SizeShapeMatrix
 *
 * @CatalogModes @SizeShapeMatrix @Composable fun FilledButton() = …
 * ```
 *
 * That is what the M3 catalogs need: five sizes by two shapes is nine cells, and writing them out
 * per component put 237 near-identical annotations across thirteen blocks, which drifted.
 *
 * Two properties worth knowing before reaching for it:
 * * **Stacking is a union, not a product.** A function tagged with a five-cell size annotation and
 *   a two-cell shape annotation gets **seven** variants, not ten — each annotation contributes its
 *   own cells, and nothing crosses them. Declare the cross product on one annotation if that is
 *   what you want.
 * * **Names must be unique across everything the function ends up carrying**, direct and hoisted
 *   alike, because the name is what distinguishes the rendered `_VARIANT_<name>` output. Discovery
 *   keeps the first of a repeated name and warns, rather than emitting two captures that overwrite
 *   each other's file.
 *
 * Resolution walks the whole meta-annotation closure, so an annotation class that is itself tagged
 * with another one contributes both sets.
 *
 * ## Design-kit correspondence
 *
 * [kitAxis] names the design-kit variant property that the seeded knob represents when its name is
 * different or ambiguous. [kitValue] optionally names this cell's kit-side value too. Neither
 * changes the preview override seed — the render is identical with or without them; what they
 * change is the *join*. They travel through discovery into the design-map variant sidecar, where a
 * resolver prefers them over its own alias tables, which is how a cell reaches a kit spelling no
 * table has: `type=range` finds nothing against the Material 3 kit's `Type=Full-screen (range)`,
 * and declaring the kit's words is the alternative to writing them into the seed itself. A
 * component whose cells all use the same property can declare the default once with
 * [CatalogComponent.kitAxis].
 *
 * ```
 * @OverrideVariant(
 *   name = "avatar",
 *   strings = ["content=avatar"],
 *   kitAxis = "Show avatar",
 *   kitValue = "True",
 * )
 * ```
 *
 * A declaration is **authoritative** downstream: a resolver that honours it stops guessing for that
 * knob, so a misspelt axis resolves to nothing rather than falling back to a translation that would
 * make the typo indistinguishable from a correct declaration. The pair applies to a cell seeding
 * exactly ONE knob — with several there is nothing to say which of them the axis names, and the
 * design-map projection reports the declaration rather than picking one.
 *
 * A cell that turns several knobs at once declares the kit's whole assignment with [kitProps]
 * instead. Kits couple their axes — turning `Icon` on can drag `Icon size` and `Alignment` with it
 * — so the cell that lands on a node the kit actually drew is often a multi-knob one, and it is
 * [kitProps] that lets it say so. See there.
 */
@Repeatable
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@MustBeDocumented
annotation class OverrideVariant(
  /**
   * Short tag distinguishing this variant, e.g. `"off"`, `"disabled"`, `"unchecked"`. Used as the
   * `_VARIANT_<name>` suffix on the rendered file and as the variant's `state` in the catalog fold,
   * so keep it slug-friendly (lowercase, no spaces) and unique across the function's variants.
   */
  val name: String,
  /**
   * Optional real input state to drive before this variant is captured. Unlike a knob seed, this
   * goes through Compose's focus / pointer input paths, so the component's own interaction source
   * and indication produce the pixels. [Focused] and [Pressed] use the same focus walk as
   * [FocusedPreview]; [Hovered] targets the component without focusing it.
   */
  val interaction: VariantInteraction = VariantInteraction.None,
  /**
   * Zero-based target: tab order for [VariantInteraction.Focused]/[VariantInteraction.Pressed],
   * interactive-semantics order for [VariantInteraction.Hovered].
   */
  val interactionIndex: Int = 0,
  /** Boolean knob seeds, each `"key=true"` / `"key=false"` (or `"key#index=…"`). */
  val booleans: Array<String> = [],
  /** String knob seeds, each `"key=value"` (or `"key#index=value"`). */
  val strings: Array<String> = [],
  /** Int knob seeds, each `"key=42"` (or `"key#index=42"`). */
  val ints: Array<String> = [],
  /** Float knob seeds, each `"key=0.25"` (or `"key#index=0.25"`). */
  val floats: Array<String> = [],
  /** Colour knob seeds, each `"key=#AARRGGBB"` (or `#RRGGBB`; or `"key#index=…"`). */
  val colors: Array<String> = [],
  /** Design-kit variant property this cell maps to; empty keeps downstream name matching. */
  val kitAxis: String = "",
  /** Design-kit value this cell maps to; empty keeps downstream value matching. */
  val kitValue: String = "",
  /**
   * The design kit's **whole** assignment for this cell, one `"Axis=Value"` per entry — for a cell
   * that turns more than one knob, which [kitAxis] / [kitValue] cannot describe.
   *
   * ```
   * @OverrideVariant(
   *   name = "icon-large",
   *   booleans = ["icon=true"],
   *   strings = ["iconSize=lrg-32", "alignment=left"],
   *   kitProps = ["Icon=Yes", "Icon size=Lrg 32", "Alignment=Left"],
   * )
   * ```
   *
   * ## Why a cell needs this
   *
   * A kit's axes are often **coupled**: the Wear kit's `Button` set has no `Icon=Yes, Icon
   * size=n/a, Alignment=Center` node, because turning `Icon` on drags `Icon size` and `Alignment`
   * with it. A cell that names one axis therefore resolves to nothing — it asks for a node between
   * the cells the kit actually drew — and a cell that seeds the three knobs it takes to land on a
   * real node has, with [kitAxis] alone, no way to say which of them the axis names. It is reported
   * and dropped rather than guessed at, since guessing pins the wrong axis and resolves to a
   * confidently wrong node.
   *
   * ## What it declares
   *
   * The **kit's** vector, not the code's. Each entry is matched exactly (up to case and
   * punctuation) against the set's own property names and values, so a name the kit does not
   * publish resolves to nothing rather than to something adjacent — the same authoritative posture
   * [kitAxis] already has, for the same reason. Values with `=` in them keep everything after the
   * first `=`, so `"Style=Variant (Highlighted)"` needs no escaping.
   *
   * This says nothing about how the render was produced. The knob seeds above still decide the
   * pixels; `kitProps` decides only what the render is compared *against*. Keeping the two separate
   * is deliberate: it is what lets a catalog draw a one-line button by default and still compare
   * that component against the kit's two-line cell, rather than editing the default until the diff
   * goes green.
   *
   * Mutually exclusive with [kitAxis] / [kitValue] on one annotation — two spellings of one fact,
   * with no rule for which wins, is exactly the ambiguity this removes. Declaring both is a
   * discovery warning and the cell keeps `kitProps`.
   */
  val kitProps: Array<String> = [],
)

/** Harness-driven state for an addressable [OverrideVariant]. */
enum class VariantInteraction {
  None,
  Hovered,
  Focused,
  Pressed,
}
