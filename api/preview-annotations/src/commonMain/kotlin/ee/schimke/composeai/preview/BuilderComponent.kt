package ee.schimke.composeai.preview

/**
 * Per-component **UI builder policy**, beside [CatalogComponent] on the same sticker — what a
 * catalog says about how its component behaves in a drawing tool, as opposed to what its signature
 * already says.
 *
 * The component record (`components.json`) is derived: every parameter, slot, call site and opt-in
 * marker comes from `@kotlin.Metadata` or from discovery's own inference, so nothing in it is typed
 * twice. What it cannot carry is the residue a builder needs and no signature holds — that
 * `onCheckedChange` updates a `checked` state rather than merely firing, that a new
 * `CheckboxButton` should arrive with the word "Checkbox" in it, that the canvas may draw this one
 * through the Material 3 `Text` adapter and must draw that one as a placeholder. Until now that
 * residue was written in Kotlin **in the server**, per catalog, by hand
 * ([UI_BUILDER_CATALOG_CONTRACT.md](https://github.com/yschimke/compose-preview-server/blob/main/docs/design/UI_BUILDER_CATALOG_CONTRACT.md)):
 * a fourth catalog cost a release of a repository that has never seen its components.
 *
 * This annotation is the per-component half of moving that residue home. The catalog-level half —
 * the platform word, the frame, the structural code templates, the template designs — is
 * `ui-builder.policy.json` beside `catalog.spec.json`, because none of it belongs to a component.
 *
 * ```kotlin
 * @CatalogComponent(id = "Toggles/CheckboxButton", reference = "figma:…")
 * @BuilderComponent(
 *   id = "wear-m3/checkbox-button",
 *   canvas = "placeholder",
 *   stateCallbacks = ["onCheckedChange=checked:boolean"],
 *   starter = ["label=Checkbox"],
 * )
 * @CatalogModes @Composable fun CheckboxButtonSticker() = Sticker { CheckboxButton(…) }
 * ```
 *
 * ### It widens or narrows a shelf that already exists
 *
 * A catalog that adds this annotation to nothing still publishes a builder catalog: every record
 * component the pack rules already admit is on the shelf, grouped by its [CatalogGroup], drawn as a
 * placeholder. So the annotation is never the thing that makes a component *appear* — it is how a
 * catalog disagrees with the default for one of them, and a catalog with no disagreements writes
 * none of these.
 *
 * Every `@BuilderComponent` must sit on a preview whose target the record resolved. One that does
 * not is reported by the generator rather than silently dropped: a policy attached to nothing is a
 * rename that got away, and the symptom otherwise is a component that quietly keeps the default
 * nobody meant it to have.
 *
 * ### Why an annotation rather than more JSON
 *
 * Both catalog repositories are annotation-first by rule: the inventory is [CatalogComponent] /
 * [CatalogVariant] beside the `@Preview`s, and `catalog.spec.json` is a cover sheet. A second
 * per-component inventory in JSON would be a second place to rename a component — the exact failure
 * [CatalogComponent] was introduced to end, one level down.
 *
 * Same discovery policy as the rest of the family: `@Target(FUNCTION)`, `BINARY` retention, matched
 * by FQN in the ClassGraph scan and never loaded. Discovery attaches the resolved policy to the
 * record entry the preview's target inference already binds the sticker to, and the generator reads
 * it from there.
 *
 * ### The `key=value` strings
 *
 * [starter], [stateCallbacks], [slots] and [variants] are `String[]` for the reason
 * [CatalogVariant.props] is: annotations cannot hold a `Map`. Each is split on the **first** `=`,
 * so a value may contain one. A malformed entry costs that entry rather than the build, and is
 * reported by the generator — the same bargain [CatalogComponent.breakpointKit] strikes, for the
 * same reason: a typo with no symptom is worse than a warning nobody reads.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
@MustBeDocumented
annotation class BuilderComponent(
  /**
   * The id this component is known by **in the builder** — the string a design document stores in a
   * node, and the key the canvas, the palette and the export all look it up by.
   *
   * Empty (the default) derives it from the catalog identity: the platform word declared in
   * `ui-builder.policy.json`, a slash, and a slug of [CatalogComponent.id] — `wear` +
   * `Toggles/CheckboxButton` becomes `wear-m3/checkbox-button` under a policy whose catalog id is
   * `wear-m3`. Deriving rather than requiring keeps the common case free, and the override exists
   * because a published design references this string: a component that has to be renamed in the
   * catalog can keep the id designs already store.
   *
   * It is deliberately not [CatalogComponent.id] itself. That id is a *catalog* identity, shaped
   * for a sticker sheet's URL and grouping (`Toggles/CheckboxButton`), and the two vocabularies
   * have already diverged in every catalog the builder serves.
   */
  val id: String = "",
  /**
   * Which component this policy is about, when the sticker renders more than one.
   *
   * A sticker is routinely `Button { Text(label) }`, and discovery records **both** calls as
   * components the preview renders. The policy is a singular claim — one builder id, one canvas
   * adapter, one variant property — so writing it onto both would give `Text` the button's identity
   * and callbacks. Naming the subject settles it: the callable's fully-qualified name
   * (`androidx.wear.compose.material3.CheckboxButton`) or its simple name (`CheckboxButton`).
   *
   * Empty is right for the ordinary sticker that renders one component, and for one whose extra
   * calls are scaffolding the record already drops. Where it is empty and several remain, discovery
   * binds the policy to the first component the preview renders and records the others, and the
   * generator reports the ambiguity by name — because the alternative, binding to none, is an
   * annotation that silently does nothing.
   */
  val component: String = "",
  /**
   * Insert-panel group, when the builder should shelve this component somewhere other than its
   * [CatalogGroup]. Empty keeps the catalog's own grouping, which is the answer nearly always.
   *
   * The catalog's group order is `menu.groupOrder` in `ui-builder.policy.json`; a group named here
   * that the order does not list sorts after the ones it does.
   */
  val group: String = "",
  /** Insert-panel label. Empty derives one from [id]'s last segment. */
  val displayName: String = "",
  /**
   * How the canvas may draw this component: the id of a **drawing adapter the builder ships**
   * (`material3/Text`, `foundation/Column`, …), or `"placeholder"`.
   *
   * Empty means placeholder, and placeholder is the honest default. The rule this field lives under
   * is not negotiable and is not new
   * ([UI_BUILDER_WEAR_SCREEN.md](https://github.com/yschimke/compose-preview-server/blob/main/docs/design/UI_BUILDER_WEAR_SCREEN.md)):
   * **no component is hand-assembled in the browser to stand in for a library the canvas cannot
   * link.** What this field carries is a claim the *catalog* makes about its own component — "our
   * text really is Material 3 `Text`, draw it with that adapter" — rather than a lookalike anybody
   * maintains. A wrong claim is visible in the render, and it is the catalog's to be wrong about.
   *
   * A builder that ships no adapter by this name draws the placeholder and logs the adapter and the
   * catalog once at startup. It never refuses the catalog: the file is published once and read by
   * builders of several vintages, several of which the reader cannot upgrade.
   */
  val canvas: String = "",
  /**
   * Callbacks that **update state** rather than merely firing, as `"<callback>=<state>:<type>"` —
   * `"onCheckedChange=checked:boolean"`.
   *
   * A `CheckboxButton` exported without a hoisted `remember` is a picture of a checkbox: it draws,
   * it compiles, and it does not tick. Which of a component's lambdas is the one that has to be
   * hoisted is not in the signature — `onClick` and `onCheckedChange` have indistinguishable types
   * — so the catalog says, and the export writes `var checked by remember { mutableStateOf(false)
   * }` above the call and threads it through.
   *
   * `<type>` is the state's JSON type (`boolean`, `string`, `number`), used to print the initial
   * value. The declared `<state>` must be a parameter of the component; one that is not is
   * reported.
   */
  val stateCallbacks: Array<String> = [],
  /**
   * What a freshly inserted instance arrives holding, as `"<parameter>=<value>"` —
   * `["label=Checkbox"]`.
   *
   * A component dragged onto the canvas with every parameter at its default is usually invisible:
   * an empty label, a zero-size image, a list of nothing. Starter content is the catalog's answer
   * to "what does one of these look like when you have just made it", and it is per component
   * because only the catalog knows.
   */
  val starter: Array<String> = [],
  /**
   * Slot policy, as `"<slot>=<traits>"` with `|`-separated traits — `["content=Content",
   * "edgeButton=Action", "overlays=Overlay"]`.
   *
   * The record already says which parameters *are* slots (a `@Composable` lambda is a slot); this
   * says what each will accept, which is a design decision rather than a type. A slot naming no
   * traits accepts anything the catalog admits, which is today's behaviour for every catalog.
   *
   * A slot named here that the record did not recover is reported: it is either a rename or a
   * signature discovery could not read, and both are worth a line.
   */
  val slots: Array<String> = [],
  /**
   * Traits this component *offers* to the slots of others — the other side of [slots], as free-form
   * words the catalog owns (`Action`, `Overlay`, `Content`).
   *
   * Deliberately not a fixed vocabulary. Slot acceptance is a within-catalog relation: a Wear
   * catalog's `Overlay` and a mobile one's mean whatever each catalog means, and no reader compares
   * them across catalogs.
   */
  val traits: Array<String> = [],
  /**
   * The parameter whose value distinguishes this component's variants in the builder's own variant
   * control (`"style"`, `"size"`), when it has one.
   *
   * Empty leaves the component with no variant control, which is the usual case: variants are a
   * property like any other, and promoting one to a control is a claim that it is *the* axis a
   * person reaches for first.
   */
  val variantProperty: String = "",
  /**
   * Variant control entries as `"<label>=<value>"` — `["Filled=filled", "Outlined=outlined"]`.
   *
   * Empty with a [variantProperty] set means "offer the property's own allowed values", which is
   * right whenever the parameter is an enum the record already recovered constants for. Naming them
   * here is for the case where the label a person should see is not the constant's name.
   */
  val variants: Array<String> = [],
  /**
   * True when this component renders **only in the native lane** — it draws on a device or under
   * Robolectric and cannot be drawn on the browser canvas at all, adapter or not.
   *
   * The builder still offers it, still exports it and still renders it natively; the canvas shows a
   * placeholder that says so rather than one that looks like a failure. Distinct from an empty
   * [canvas], which means "nobody has claimed an adapter for this yet".
   */
  val nativeOnly: Boolean = false,
  /**
   * Keep this component **off** the builder's shelf, with the reason.
   *
   * The generator reports every excluded component and its reason, so a shelf that has quietly
   * shrunk is legible. Empty (the default) leaves the pack rules to decide, which is what already
   * happens for every catalog and is the answer unless a component is genuinely unusable in a
   * drawing tool — a debug harness, a fixture, a composable whose only sensible caller is a test.
   */
  val exclude: String = "",
)
