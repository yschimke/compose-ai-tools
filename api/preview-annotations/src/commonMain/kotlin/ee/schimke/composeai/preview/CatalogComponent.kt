package ee.schimke.composeai.preview

/**
 * Declares a `@Preview` function as the **primary sticker** for one component in a published design
 * catalog — the code-side home for the metadata `catalog.spec.json` used to restate by hand
 * (`componentId`, `group`, `caption`). Where the spec joined a component to its render by a fragile
 * exact-function-name string, this annotation carries the catalog identity *next to* the
 * composable, so a rename can't silently break the sticker sheet.
 *
 * Everything is defaulted, so the common case needs no arguments — the "good defaults, override
 * with annotations" model:
 * * [id] defaults to the annotated function's name (the same join key the spec used), so an
 *   un-argumented `@CatalogComponent` reproduces today's behaviour. Override it with the stable
 *   public component id (e.g. `"Button/Filled"`) when you want the slashed inventory id.
 * * [group] defaults to the file-level [CatalogGroup] name, else `"Components"`. Override per
 *   component to move a single sticker into another group without splitting the file.
 * * [caption] defaults to empty (the catalog shows the component with no sub-line). Override with
 *   the one-line description shown under the sticker.
 * * [reference] defaults to empty. Override with a seed-kit handle (Figma node etc.) for the
 *   one-off import; the render stays authoritative.
 * * [parallel] defaults to empty. Override with the component id of the counterpart in the sibling
 *   system named by the catalog's `compareWith` setting.
 * * [perBreakpoint] defaults to false — every render of the function folds onto ONE component. Set
 *   it when the component should be a card *per* breakpoint instead; see below.
 * * [referenceSet] defaults to empty. Override with the handle of the component *family*
 *   [reference] is one variant of — see below.
 * * [noReference] defaults to empty. Override with the REASON there is no [reference], when the
 *   absence is a finding rather than a gap — the kit retired the pattern, never published it, or
 *   publishes something close enough to mislead. An empty [reference] otherwise means only "nobody
 *   has looked yet", and a consumer cannot tell the two apart.
 * * [referenceContentsOnly] defaults to true. Set it to false only when the referenced Figma node
 *   intentionally relies on overlapping sheet content, such as an authored backdrop. Keeping this
 *   next to [reference] avoids changing unrelated previews on the same component sheet.
 * * [kitAxis] defaults to empty. Set it when every override variant on this component uses the same
 *   design-kit property, so individual [OverrideVariant] cells only need to name exceptional axes
 *   or values.
 *
 * ```kotlin
 * @file:CatalogGroup("Buttons")
 *
 * @CatalogComponent(id = "Button/Filled", caption = "Highest emphasis; the primary action.")
 * @CatalogModes @Composable fun FilledButton() = Sticker("button-filled")
 * ```
 *
 * ### Variant vs family: [reference] and [referenceSet]
 *
 * [reference] names ONE concrete node — the frame a design-parity run diffs this sticker's render
 * against. It has to be one variant: point it at a Figma component *set* and the comparison is
 * against a grid of every variant at once, which is meaningless.
 *
 * [referenceSet] names the family that variant belongs to (the component set), and exists for the
 * other direction — matching a whole *screen* back to code. An instance placed on a screen reports
 * its own variant and its set, and a screen almost never uses the exact variant a catalog chose to
 * picture, so matching on [reference] alone misses. Measured on the Material 3 kit: per-variant
 * handles alone linked 3 of 11 instances on a real screen; the misses were a list item and a
 * carousel whose screens used *sibling* variants of components the catalog already maps.
 *
 * ```kotlin
 * @CatalogComponent(
 *   id = "Lists/ListItem",
 *   reference = "figma:AbCdEf/51964:64241",     // the one variant this sticker renders
 *   referenceSet = "figma:AbCdEf/51964:63037",  // the family every sibling variant shares
 * )
 * ```
 *
 * Two fields rather than one because the two readers want incompatible things — a parity diff needs
 * the narrowest renderable node, screen matching needs the widest. Both travel into `previews.json`
 * and out to the design-map, where design-parity indexes them side by side.
 *
 * ### Breakpoints: [perBreakpoint]
 *
 * A multipreview annotation (`@WearPreviewDevices`, a local `@CatalogWearBreakpoints`) renders one
 * function at several device sizes, and the export's candidate join keys on function name — so all
 * of them fold onto this one component. [perBreakpoint] splits that fan-out into a component **per
 * breakpoint**, each with its own id and its own sticker, without the module having to split the
 * `@Preview` function into per-device siblings (which would also drop the multipreview's other
 * axes, `@WearPreviewFontScales` among them):
 * ```kotlin
 * @CatalogComponent(id = "Layout/List", perBreakpoint = true)
 * @CatalogWearBreakpoints @Composable fun ListLayout() = FullScreenWear { … }
 * ```
 *
 * yields `Layout/List/smallRound`, `Layout/List/largeRound`, … — **one per breakpoint the function
 * actually rendered**, named by the catalog's `breakpoints` table in `catalog.spec.json` (a Wear
 * catalog that declares none inherits the standard round table).
 *
 * Deliberately a flag rather than a list of breakpoint names: the multipreview annotation directly
 * below already declares which devices this function renders at, so naming them here too would
 * restate — and could contradict — what the render itself knows. There is nothing to keep in sync
 * and nothing to misspell, and adding a device to the multipreview adds its card automatically. A
 * function that renders at only ONE breakpoint keeps its plain [id]: one breakpoint is one card,
 * and suffixing it would move a published sticker's URL to say what the id already says.
 *
 * To document a *subset* of the breakpoints a function renders, or to override any of this, use a
 * `catalog.spec.json` entry's `select` — the spec always wins over the annotation.
 *
 * Discovered by FQN off the annotated **function** — hence `@Target(FUNCTION)` plus `BINARY`
 * retention, so the annotation survives into the compiled `.class` files the plugin scans with
 * ClassGraph (the same policy as [ColorCatalog] / [ThemeCatalog], never a `SOURCE`-only KSP scan).
 * Discovery attaches the resolved identity to the preview's manifest entry; the design-artifacts
 * export reads it as the catalog inventory, with any matching `catalog.spec.json` entry layered on
 * top as an override. Consumers depend on `ee.schimke.composeai:preview-annotations` to use it.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
@MustBeDocumented
annotation class CatalogComponent(
  val id: String = "",
  val group: String = "",
  val caption: String = "",
  val reference: String = "",
  val parallel: String = "",
  val perBreakpoint: Boolean = false,
  // Appended, NOT slotted in beside `reference` where they read best: a parameter inserted ahead of
  // an existing one silently re-points a positional call. A five-string `@CatalogComponent(...)`
  // whose fifth argument meant `parallel` would still compile and quietly mean something else.
  // Declaration order is source API here; the grouping is documented above instead.
  val referenceSet: String = "",
  val noReference: String = "",
  val referenceContentsOnly: Boolean = true,
  /** Default design-kit variant property for this component's override-variant cells. */
  val kitAxis: String = "",
)

/**
 * Folds a secondary render onto a parent [CatalogComponent]'s sticker — the annotation form of a
 * `catalog.spec.json` `variants[]` entry. A variant is a distinct `@Preview` (a pressed state, a
 * disabled state, an RTL layout, a large-font axis) that the catalog surfaces *under* its parent
 * rather than as a top-level component.
 *
 * [of] is the one required field: the parent component's [CatalogComponent.id]. Tag what the
 * variant shows with either [state] (an interaction/state name — `"pressed"`, `"disabled"`,
 * `"off"`, …) or [props] (named content/i18n/a11y axes as `"key=value"` strings, e.g.
 * `["content=icon+label"]` or `["locale=ar-XB"]`, `["fontScale=2.0"]`) — annotations can't hold a
 * `Map`, so the axes travel as `key=value` pairs the export splits back apart. Give at least one so
 * the variant is distinguishable from the parent's default render.
 *
 * ```kotlin
 * @CatalogVariant(of = "Button/Filled", state = "pressed",
 *                 caption = "Held PressInteraction -> pressed state layer.")
 * @CatalogModes @Composable fun FilledButtonPressed() = Sticker("button-filled-pressed")
 *
 * @CatalogVariant(of = "Button/Filled", props = ["content=icon+label"],
 *                 caption = "Leading icon + label, vs the label-only default.")
 * @CatalogModes @Composable fun FilledButtonIconLabel() = Sticker("button-filled-icon-label")
 * ```
 *
 * ### Design-kit correspondence: [kitAxis] and [kitValue]
 *
 * [props] is the Compose side — `type=range` is what the API calls it, and it is what a reader of
 * this catalog greps for. A design map resolver translates that to the kit's own axis through
 * published alias tables, and some spellings no table reaches: the Material 3 kit files that
 * variant as `Type=Full-screen (range)`, parentheses and all. Naming the kit's spelling in [props]
 * to make the join work puts a kit string in code that has no business holding one, and it rots the
 * next time the kit renames a value.
 *
 * So name both sides instead — the code keeps its own word, the declaration carries the kit's:
 * ```kotlin
 * @CatalogVariant(
 *   of = "DatePicker/Modal",
 *   props = ["type=range"],
 *   kitAxis = "Type",
 *   kitValue = "Full-screen (range)",
 * )
 * ```
 *
 * Either alone is meaningful: [kitAxis] when only the axis *name* differs, [kitValue] when only the
 * value's spelling does. Both are **authoritative** downstream — a resolver that honours them stops
 * consulting its alias tables for that knob, so a misspelt declaration resolves to nothing rather
 * than quietly falling back to a guess. They apply to a variant declaring exactly ONE [props] entry
 * (or [state]); with several there is nothing to say which knob the axis names, and the projection
 * reports the declaration rather than picking one.
 *
 * Same discovery policy as [CatalogComponent] (`@Target(FUNCTION)`, `BINARY` retention, FQN match).
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
@MustBeDocumented
annotation class CatalogVariant(
  val of: String,
  val state: String = "",
  val caption: String = "",
  val props: Array<String> = [],
  /**
   * Design-kit variant property this variant's prop names; empty keeps downstream name matching.
   */
  val kitAxis: String = "",
  /** Design-kit value this variant maps to; empty keeps downstream value matching. */
  val kitValue: String = "",
)

/**
 * File-level default [group][CatalogComponent.group] (and optional [section] tab) for every
 * [CatalogComponent] / [CatalogVariant] declared in the file — so a file of related stickers names
 * its group once at the top instead of on every function. A per-component `group` argument still
 * wins over this file default (most-specific override), and a `catalog.spec.json` entry still wins
 * over the annotation.
 *
 * [section] is the optional top-level tab the preview server buckets this file's group under
 * (`"Themes"` / `"Components"` / `"Screens"` / …), one level above the group name; absent leaves
 * the catalog flat/untabbed.
 *
 * ```kotlin
 * @file:CatalogGroup("Buttons")
 * package com.example.designcatalog
 * ```
 *
 * Placed on the **file** — hence `@Target(FILE)`, which the Kotlin compiler emits onto the file's
 * synthetic `…Kt` facade class, so ClassGraph reads it as a class annotation (`BINARY` retention,
 * same FQN-match policy as the rest of the family). It supplies a default only; it does not by
 * itself turn every `@Preview` in the file into a catalog component — a preview joins the inventory
 * only via its own [CatalogComponent] / [CatalogVariant].
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FILE)
@MustBeDocumented
annotation class CatalogGroup(val name: String, val section: String = "")
