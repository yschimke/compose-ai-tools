package ee.schimke.composeai.preview

/**
 * The **seed text** an enum constant answers to, when it differs from the constant's own name.
 *
 * An `enum class` parameter is the parameter-knob format's closed value set: its constants are the
 * options a viewer offers, and a seed names one of them. By default that name *is* the constant's —
 * `Emphasis.Tonal` is seeded as `"Tonal"` — which is fine for a knob written from scratch.
 *
 * It is not fine for a migration. A catalog that has been seeding
 * `previewOverrideChoice("iconSize", "default", listOf("default", "large", "extra-large"))` has its
 * vocabulary written down in every `@OverrideVariant(strings = ["iconSize=extra-large"])` it has
 * accumulated — and, more importantly, in the **kit's own spelling**, which is what a variant's
 * props are matched against when the render is compared to its design-system reference. Rename the
 * value to `ExtraLarge` and every one of those seeds stops binding: the render silently falls back
 * to its author default and the node drops out of the comparison with no diagnostic anywhere.
 *
 * Several of those values cannot be Kotlin identifiers at all — `12-sided cookie`, `4-leaf clover`,
 * `0.0`, `24s` — so "just name the constant after the value" is not available even in principle.
 *
 * So the constant declares the text instead of being renamed to it:
 * ```
 * enum class IconSize {
 *   @KnobValue("default") Default,
 *   @KnobValue("large") Large,
 *   @KnobValue("extra-large") ExtraLarge,
 * }
 * ```
 *
 * Everything downstream then speaks one vocabulary — the declared one. Discovery publishes it as
 * the knob's `options`, a viewer's picker offers it, an `@OverrideVariant` seed carries it, and the
 * renderer maps it back to the constant at the invoke seam. The constant name is what Kotlin sees;
 * this is what everyone else does.
 *
 * A constant without the annotation answers to its own name, so the two forms mix freely and a knob
 * written from scratch needs nothing.
 *
 * **Values must be distinct within an enum.** Two constants claiming one seed text make the seed
 * ambiguous; discovery drops such an enum's options rather than binding to whichever it saw first.
 */
/**
 * `RUNTIME`, unlike most annotations here, because two different readers need it: discovery reads
 * it from the class file to publish the knob's options, and the **renderer** reads it reflectively
 * off the loaded enum to map a seed back to a constant. The renderer resolves it by name rather
 * than by type, so no renderer artefact gains a dependency on this module.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FIELD)
@MustBeDocumented
annotation class KnobValue(val value: String)
