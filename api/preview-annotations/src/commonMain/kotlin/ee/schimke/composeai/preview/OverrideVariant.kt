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
 * Android/Wear (Robolectric) render path only today — the desktop (Skiko) backend is fed by
 * positional args and doesn't yet carry the seed, so a CMP-desktop catalog keeps hand-written
 * variant functions until desktop parity lands.
 */
@Repeatable
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
@MustBeDocumented
annotation class OverrideVariant(
  /**
   * Short tag distinguishing this variant, e.g. `"off"`, `"disabled"`, `"unchecked"`. Used as the
   * `_VARIANT_<name>` suffix on the rendered file and as the variant's `state` in the catalog fold,
   * so keep it slug-friendly (lowercase, no spaces) and unique across the function's variants.
   */
  val name: String,
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
)
