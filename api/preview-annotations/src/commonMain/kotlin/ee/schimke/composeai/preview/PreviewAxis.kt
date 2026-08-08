package ee.schimke.composeai.preview

/**
 * Declares **one dimension** a preview varies along, so discovery can expand the cross product of
 * every declared axis into one seeded capture per cell — the declarative form of a stack of
 * [OverrideVariant]s.
 *
 * ## Why
 *
 * A variant matrix is a cross product, and writing it out cell by cell scales badly in the two ways
 * that matter. m3-catalog's buttons are five sizes by two shapes, its icon buttons add a third
 * width axis, and its toggle buttons a selected axis: 237 `@OverrideVariant` annotations across
 * thirteen blocks, each cell spelled twice — once as a seed (`strings =
 * ["size=xs", "shape=square"]`) and once as a name (`"xs-square"`) — with nothing checking that the
 * two agree. They had already drifted. [OverrideVariant] hoisted onto an annotation class fixes the
 * *repetition* across components, but a hoisted matrix is still a hand-enumerated product, and
 * stacking two hoisted annotations is a union rather than a product.
 *
 * ```
 * @PreviewAxis(key = "size",  values = ["xs", "s", "m", "l", "xl"], default = "s")
 * @PreviewAxis(key = "shape", values = ["round", "square"])
 * @CatalogModes
 * @Composable
 * fun FilledButton() = Sticker { … }   // 9 cells, none of them typed out
 * ```
 *
 * The sticker reads its knobs exactly as before (`previewOverrideString("size", "s")`), and each
 * cell is rendered with that cell's values seeded.
 *
 * ## What it emits that a hand-written variant cannot
 *
 * **Typed props.** An `@OverrideVariant` contributes one opaque string to the published catalog —
 * `state: "xs-square"` — because the name is all the export has to go on. An axis cell knows what
 * it *is*, so it publishes `props: {size: "xs", shape: "square"}`. That matters beyond tidiness:
 * design-parity pairs a rendered candidate against a reference by its variant properties, and a
 * design kit's component set carries exactly these as named properties. A hand-typed name has to
 * coincidentally match the kit's naming for a cell to pair; a prop map matches by construction.
 *
 * Every cell carries its **full** assignment, defaults included — `s` and `round` are as much part
 * of what a cell is as `xs` and `square` — even though only the non-default values are seeded,
 * since seeding a knob with the value it already resolves to is a no-op.
 *
 * ## Naming
 *
 * A cell is named by its non-default values, in axis-declaration order, joined by `-`: `xs-square`,
 * `xs`, `square`. Set [namesEveryValue] on an axis that should appear in every name even at its
 * default — m3-catalog does this for `size`, where a cell called `square` with no size in it reads
 * as a shape variant of nothing, giving `s-square` instead.
 *
 * The all-defaults cell is **not** a variant: it is the base render the `@Preview` already
 * produces, so it is skipped and an unseeded sticker stays byte-identical to what it published
 * before its axes were declared.
 *
 * ## Combining
 *
 * Axes stacked on one function **multiply**, which is the whole point and the opposite of how
 * stacked [OverrideVariant]s combine. Axes may also be hoisted onto an annotation class (same
 * `ANNOTATION_CLASS` target, same meta-annotation walk), and a function's direct axes multiply with
 * every hoisted one — so a shared `@SizeAndShape` can be combined with a per-component extra axis
 * without either knowing about the other. Two axes with the same [key] would square that key
 * against itself, so the later one is dropped with a warning.
 *
 * Mixing `@PreviewAxis` with `@OverrideVariant` on one function is allowed: the axis cells and the
 * hand-written variants are unioned, which is how a component that is a clean product *except* for
 * one odd extra state stays expressible. Names must not collide across the two.
 *
 * A product grows fast — five sizes by three widths by two shapes is thirty cells, times every
 * `@Preview` expansion — so discovery warns past [MAX_CELLS_WARN] cells on one function, and
 * refuses past [MAX_CELLS] rather than minting thousands of captures from a typo.
 */
@Repeatable
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@MustBeDocumented
annotation class PreviewAxis(
  /**
   * The `previewOverride*` knob this axis seeds, and the prop name each cell publishes — `"size"`,
   * `"shape"`, `"width"`.
   */
  val key: String,
  /**
   * The values this axis takes, in the order their cells should be emitted. At least two; a
   * one-value axis multiplies nothing and is dropped with a warning.
   */
  val values: Array<String>,
  /**
   * The value an **unseeded** render resolves to — the author default the composable passes to
   * `previewOverrideString(key, default)`. Cells never seed it, and by default never name it.
   *
   * Empty means `values[0]`. A default that is not in [values] is a mistake discovery warns about
   * and treats as `values[0]`, since it would otherwise make every cell non-default and emit a
   * duplicate of the base render.
   */
  val default: String = "",
  /**
   * Which typed array an [OverrideVariant] would have put these values in — the knob's Kotlin type.
   *
   * A `previewOverrideBoolean` knob only honours a boolean seed, so an axis over a boolean knob has
   * to say so. Its [values] are still written as strings (`["true", "false"]`); this decides how
   * they are seeded.
   */
  val kind: PreviewAxisKind = PreviewAxisKind.STRING,
  /**
   * Per-value name fragments, when a value's own spelling is not what a cell should be called — a
   * `selected` axis over `["true", "false"]` naming itself `["on", "off"]`.
   *
   * Positional against [values]. Empty (the default) means each value names itself; a non-empty
   * array of the wrong length is ignored with a warning rather than silently misnaming half the
   * cells.
   */
  val slugs: Array<String> = [],
  /**
   * Whether every cell's name carries this axis, or only the cells that are off [default].
   *
   * False everywhere by default. True is for the axis a reader needs in order to make sense of the
   * others: m3-catalog sets it on `size`, so the shape variant of the default size is `s-square`
   * rather than a bare `square`. It affects the name only — a default value is still never seeded.
   */
  val namesEveryValue: Boolean = false,
  /**
   * Where this axis sits in a cell's name and props, low to high — `size` at 1 and `shape` at 2
   * give `xs-square`, not `square-xs`.
   *
   * Explicit because **the order repeated annotations are emitted in is not a contract**. It is a
   * detail of the compiler, and Kotlin's does not match the order they were written in: a
   * cross-check against ClassGraph (which preserves whatever it is handed, verified on
   * javac-produced classes) showed a two-axis function coming back with its axes swapped, which
   * silently renames every cell. Sorting on a declared number makes the naming a property of the
   * source rather than of the toolchain.
   *
   * Axes that share an order keep their emitted order relative to each other, so a single-axis
   * function — and any matrix whose naming you don't care about — needs nothing here.
   */
  val order: Int = 0,
) {
  companion object {
    /** Past this many cells on one function, discovery warns; the render bill is real. */
    const val MAX_CELLS_WARN = 64

    /** Past this many, discovery refuses the expansion — a product this size is a typo. */
    const val MAX_CELLS = 512
  }
}

/** The Kotlin type of the knob a [PreviewAxis] seeds — which typed seed array its values go in. */
enum class PreviewAxisKind {
  STRING,
  BOOLEAN,
  INT,
  FLOAT,
}
