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
 *
 * ```kotlin
 * @file:CatalogGroup("Buttons")
 *
 * @CatalogComponent(id = "Button/Filled", caption = "Highest emphasis; the primary action.")
 * @CatalogModes @Composable fun FilledButton() = Sticker("button-filled")
 * ```
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
