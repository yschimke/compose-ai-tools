package ee.schimke.composeai.discovery

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** The `schema` value a `ui-builder.policy.json` this generator understands must carry. */
const val UI_BUILDER_POLICY_SCHEMA: String = "compose-ui-builder-policy/v1"

/**
 * The structural roles the template engine knows, and the only values a builtin's `role` may take.
 *
 * A closed set on purpose. These are what the preview server's 1,406-line Wear screen emitter
 * decomposes into, and the point of templates-as-data over an emitter-as-a-jar is that the builder
 * can *validate* what a catalog asks for. A catalog that needs a seventh role means the engine
 * grows one, once, with a test; the test that a new role is general is that two catalogs use it.
 */
/**
 * The JSON types a `@BuilderComponent.stateCallbacks` entry may give its state.
 *
 * The export prints the hoisted `remember`'s initial value from this, so an entry without one — or
 * with `bool` for `boolean` — publishes a hoist nothing downstream can complete.
 */
val STATE_TYPES: Set<String> = setOf("boolean", "string", "number")

/**
 * The Kotlin classifiers each [STATE_TYPES] word may stand for, as the record renders them.
 *
 * A supported word is not enough on its own: `checked:string` names a real type and a real
 * parameter, and produces an export that initialises a `String` state and threads it into a
 * `Boolean` parameter — source that does not compile, from a policy where every other check passed.
 * Simple names because that is what `TargetParameter.type` holds; a nullable `Boolean?` is the same
 * classifier and is compared with the `?` stripped.
 */
val STATE_TYPE_CLASSIFIERS: Map<String, Set<String>> =
  mapOf(
    "boolean" to setOf("Boolean"),
    "string" to setOf("String", "CharSequence"),
    "number" to setOf("Int", "Long", "Short", "Byte", "Float", "Double", "Number"),
  )

/**
 * The structural roles the template engine knows, and the whole set of them.
 *
 * `container` is the one that is not about a screen's decomposition. The other six say how a node
 * is WRITTEN as part of a screen — a scrolling list, one of its items, an overlay hoisted beside
 * it. A box, a column and a row are none of those: they hold children in a fixed arrangement and
 * write no repetition at all. Without this word they had to publish as `list`, which is the role
 * whose template is handed `${'$'}{listState}`, `${'$'}{contentPadding}` and `${'$'}{items}` — so
 * the honest reading of a `layout/box` declared as a `list` is that a box is a scrolling list, and
 * it is not.
 *
 * The bar for a seventh role is that two catalogs need it, and it is met: `compose-foundation`
 * (yschimke/m3-catalog) declares `layout/box`, `layout/column` and `layout/row`, and the packaged
 * vocabulary those were copied from carries the same three for every platform that borrows them.
 */
val UI_BUILDER_STRUCTURAL_ROLES: Set<String> =
  setOf("screen-root", "list", "list-item", "container", "overlay", "controlled", "decoration")

/**
 * How a catalog's designs are written as source. Closed, and stated here beside the role set for
 * the same reason: a word outside it selects no exporter, and the failure surfaces at export rather
 * than where somebody typed it.
 */
val UI_BUILDER_CODE_STRATEGIES: Set<String> = setOf("record", "templates")

/**
 * The named holes each template role may use, and the whole reason a template can be checked when
 * the catalog is published rather than when somebody exports through it.
 *
 * A `${'$'}{contnet}` typo parses perfectly well — it is a valid name — so nothing about the
 * template's *syntax* catches it. What catches it is knowing which names the engine will have
 * values for in each role, and that is a contract rather than an implementation detail: it is
 * exactly the list of things the surrounding structure can hand a template, and a catalog author
 * needs it written down before they write a template rather than discovered from a refused export.
 *
 * Closed for the same reason the role set is. A template asking for a name no role supplies would
 * be refused at render, weeks from the person who typed it, and the refusal would name a hole
 * rather than the mistake.
 *
 * `${'$'}{call(...)}` is not here: it is a hole *kind* rather than a name, legal in every role, and
 * it is what puts a record call site into the structure.
 */
val UI_BUILDER_TEMPLATE_HOLES: Map<String, Set<String>> =
  mapOf(
    // The screen's own frame: the list state it shares with the list inside it (the reason this
    // role exists at all), the slot bodies, and an edge-anchored action.
    "screen-root" to setOf("listState", "content", "edgeButton", "overlays", "contentPadding"),
    // A scrolling list: the same state object its scaffold holds, the padding the frame measured,
    // and the items.
    "list" to setOf("listState", "contentPadding", "items"),
    // One item, wrapped in whatever scope its parent requires. `${'$'}{call(...)}` does the work.
    "list-item" to setOf("index", "children"),
    // Children in a fixed arrangement, and nothing else. Deliberately NOT `list`'s holes: a
    // container writes no repetition, so there is no list state to share and no measured padding
    // to thread. A template asking for one is asking for a value this role will never have.
    "container" to setOf("children"),
    // A sibling of the screen rather than a child, shown by a hoisted state.
    "overlay" to setOf("state", "children"),
    // The hoisted `remember` a state callback needs; see BuilderComponent.stateCallbacks.
    "controlled" to setOf("name", "type", "initial"),
    "decoration" to setOf("children"),
    // Whole-file templates rather than node roles.
    "previews" to setOf("name", "widthDp", "heightDp", "device"),
    "file" to setOf("packageName", "imports", "name", "body"),
  )

/**
 * `ui-builder.policy.json` — the **catalog-level** half of what a catalog tells a UI builder,
 * authored beside `catalog.spec.json`.
 *
 * Per-component policy is [BuilderPolicy], an annotation on the sticker, so a component is never
 * renamed in two places. What is here is what belongs to no component: the platform word, the
 * screen frame and its measured geometry, the structural code templates, the template designs. The
 * one exception is [builtins], and it is the exception that proves the rule — a screen scaffold or
 * a shape that is not a composable at all has no call site, so it cannot be in the record and there
 * is no sticker to annotate.
 *
 * Field-by-field documentation, including the shapes this class deliberately keeps as raw
 * [JsonElement], is in `scripts/design-artifacts/ui-builder.policy.schema.json`; the contract is
 * [UI_BUILDER_CATALOG_CONTRACT.md](https://github.com/yschimke/compose-preview-server/blob/main/docs/design/UI_BUILDER_CATALOG_CONTRACT.md).
 *
 * ### Why several fields are `JsonElement`
 *
 * [previewSurfaces], [browserPreview], [frame] and [colorTokens] are carried through to the
 * generated file **verbatim** rather than parsed into Kotlin. They are read by the preview server,
 * whose types own their shape; re-declaring them here would put a second definition of somebody
 * else's contract in the middle of the pipeline, where it would be the thing that has to be updated
 * for a field this generator never looks at. What this generator validates about them, it validates
 * structurally.
 *
 * `frame.geometry` in particular is written by the catalog's own Robolectric probe and asserted
 * against the committed file by that same test. Parsing it here would add a second opinion about
 * numbers that are measured, and the whole reason the block lives in the catalog repository is that
 * there be only one.
 */
@Serializable
data class UiBuilderPolicyFile(
  @SerialName("\$schema") val jsonSchema: String? = null,
  @SerialName("\$comment") val comment: String? = null,
  val schema: String,
  /** The id the builder authors against; defaults to `catalog.spec.json`'s `system`. */
  val catalogId: String? = null,
  /**
   * What a derived builder id is prefixed with, when it is not `<catalogId>/`.
   *
   * A component's builder id is the string a saved design stores in every node, so it is derived
   * rather than authored per component — but the derivation has to be able to produce the ids
   * designs *already* store. m3-catalog's are `m3/button` and `m3/card`, not `m3-catalog/button`:
   * the catalog is named for the repository and the components for the library, and no rename can
   * reconcile that without invalidating every saved design. An explicit `@BuilderComponent(id = …)`
   * still wins over this.
   */
  val componentIdPrefix: String? = null,
  /** The platform word. Equality is compatibility; there is no enum. */
  val platform: String,
  /** What the New design chooser prints over the group; defaults to [platform] title-cased. */
  val platformLabel: String? = null,
  val previewSurfaces: JsonElement? = null,
  /** Typed by the consuming UI-builder protocol; carried without a duplicate definition here. */
  val browserPreview: JsonElement? = null,
  val frame: JsonElement? = null,
  val builtins: Map<String, UiBuilderBuiltin> = emptyMap(),
  val menu: UiBuilderMenu? = null,
  val code: UiBuilderCode? = null,
  /**
   * The safe, versioned Compose source adapter this catalog selects, if it exports Compose source.
   *
   * This is a declaration, not source code. The consumer resolves the id/version only against
   * adapters it ships; an unknown declaration refuses export rather than executing catalog data.
   */
  val composeSourceExport: UiBuilderComposeSourceExport? = null,
  /** Branch-relative paths of the template designs offered in the New design chooser. */
  val templates: List<String> = emptyList(),
  val colorTokens: JsonElement? = null,
  val assetRegistry: JsonElement? = null,
  /** Component/property/slot successor rules, carried verbatim for catalog-upgrade previews. */
  val supersedes: JsonElement? = null,
  /**
   * Per-component policy the catalog states here rather than on a sticker, keyed by builder id.
   *
   * `@BuilderComponent` is the right place for what belongs to one sticker — its group, its variant
   * property, whether to exclude it. It is the wrong place for a component's **vocabulary**: the
   * properties a design may set, the slots it may fill and the modifiers it accepts are editorial
   * decisions about the catalog's shelf, they run to dozens of entries per component, and a catalog
   * can hold them without annotating anything. m3-catalog has 104 record components and **zero**
   * `@BuilderComponent` annotations, and the vocabulary it needs to publish is the one its frozen
   * capability document already states.
   *
   * Joined by [UiBuilderAuthoredComponent.record] when present, which lets the map key preserve the
   * published builder id across a source rename. Otherwise it is merged onto the annotation-derived
   * entry for the same id, so the two can be used together and neither has to carry the other's
   * concerns. An entry joining no record component is reported rather than dropped — see
   * `Diagnostics.POLICY_ORPHANED`'s sibling for the annotation case, and the same argument: a
   * policy naming nothing is a rename that got away.
   */
  val components: Map<String, UiBuilderAuthoredComponent> = emptyMap(),
)

/** A catalog-owned selection of a shipped Compose source-export adapter. */
@Serializable data class UiBuilderComposeSourceExport(val adapter: String, val version: Int)

/**
 * One component's authored policy.
 *
 * The three capability blocks are carried as raw JSON on purpose. Their shape is the UI builder's —
 * `PropertyCapabilityV1`, `SlotCapabilityV1` — and re-declaring it here would be a second
 * definition of a contract this repository does not own, which is exactly the drift the published
 * `ui-builder.json` exists to avoid. The preview server validates them when it composes the shelf
 * and refuses a catalog whose declarations it cannot serve; this carries them faithfully.
 *
 * Every field is nullable so that "not stated" and "stated as empty" stay different questions: a
 * catalog declaring `modifierCapabilities: []` means the component accepts none, and one omitting
 * it means the consumer should fall back.
 */
@Serializable
data class UiBuilderAuthoredComponent(
  /** The record's `canonicalId`, joining this policy to the inventory. */
  val record: String? = null,
  /** The shelf this component appears on, as `@BuilderComponent(group = …)` would say it. */
  val group: String? = null,
  val displayName: String? = null,
  val canvas: String? = null,
  /** Canvas-only property/slot projection, owned by the consuming UI-builder protocol. */
  val canvasMapping: JsonElement? = null,
  /** The editing canvas's mock for this component — see [UiBuilderUnrolledMock]. */
  val unrolled: UiBuilderUnrolledMock? = null,
  val nativeOnly: Boolean? = null,
  val traits: List<String>? = null,
  /** Kept off the shelf, with the stated reason. */
  val excluded: String? = null,
  val propertyCapabilities: List<JsonElement>? = null,
  val slotCapabilities: List<JsonElement>? = null,
  val modifierCapabilities: List<String>? = null,
)

/**
 * The layout a catalog asks the editing canvas to draw for a component while it is being edited.
 *
 * A scrollable container drawn as itself cannot show a child past the frame's edge — the ninth row
 * of a lazy column, the fifth tab of a scrollable row, the pane a phone frame hides — so a catalog
 * says how its children should be laid out while an author is inside it. The **constrained**
 * surfaces never see this: the preview pane, each device frame, the native lane and every export
 * draw the component itself.
 *
 * [layout] is the builder's vocabulary, not this file's, exactly as
 * [UiBuilderAuthoredComponent.canvas] is: the builder resolves the name against its own registry,
 * and a name it does not know is inert — the component draws as itself rather than as a broken
 * mock. The names in use are `stack` (a `Column`), `row` (a `Row`), `wrap` (a `FlowRow`) and
 * `panes` (every pane a pane scaffold declares).
 *
 * The wire carries it nested under the component's `wasm` block — `WasmCapabilityV1.unrolled` —
 * because that is the canvas-lane block there. Here it sits beside `canvas`, which is the
 * declaration it belongs with: it is the same declaration for a record component and for a builtin,
 * and those two do not share a `wasm` block.
 */
@Serializable
data class UiBuilderUnrolledMock(
  val layout: String,
  /** The width `wrap` and `row` give one cell; absent leaves it to the layout. */
  val cellWidthDp: JsonElement? = null,
  /** The gap between cells; absent leaves it to the layout. */
  val spacingDp: JsonElement? = null,
)

/**
 * A component the policy file may declare because the record cannot carry it: it has no call site,
 * so there is nothing to discover and no sticker to annotate.
 *
 * A builtin must name a structural [role], which is what tells the template engine which template
 * writes it. A component that *has* a call site belongs in the record; declaring one here would be
 * the second inventory this contract exists to avoid.
 */
@Serializable
data class UiBuilderBuiltin(
  val role: String,
  val displayName: String? = null,
  val group: String? = null,
  val canvas: String? = null,
  /** The editing canvas's mock for this builtin — see [UiBuilderUnrolledMock]. */
  val unrolled: UiBuilderUnrolledMock? = null,
  /**
   * What this builtin IS, for the slot-acceptance rules — a component whose slot accepts
   * `AnyContent` cannot admit one that claims no traits at all.
   *
   * The consumer already reads it; without it here there was no way to write it, so every builtin a
   * catalog could publish arrived on the shelf with an empty list. The same was true of
   * [modifierCapabilities].
   */
  val traits: List<String> = emptyList(),
  val slots: Map<String, JsonElement> = emptyMap(),
  val properties: List<JsonElement> = emptyList(),
  /** The modifiers this builtin accepts, or null for the consumer's structural default. */
  val modifierCapabilities: List<String>? = null,
  /** Canonical id of the wrapper call site this catalog ships in its component record. */
  val implementation: String? = null,
  /**
   * What this builtin IS on the shelf — `Scaffold`, `Container` or `Leaf` — or null to let the
   * consumer derive it.
   *
   * Two different words are spelled `role` in this contract and they are not the same vocabulary.
   * [role] above is the STRUCTURAL one: which template writes this component. This is the shelf's,
   * which decides what the editor calls it and which slots will take it, and it is the one a
   * capability document publishes as `role`.
   *
   * There was no way to state it, so a consumer derived it from whether the builtin had slots at
   * all — and a design ROOT with slots arrives as an ordinary `Container` rather than a `Scaffold`.
   * `screen-root` is the only structural role that implies the answer; `list` and `container` say
   * how a thing is WRITTEN and not what shape it is. Null keeps the derivation, which is right for
   * everything the derivation gets right.
   */
  val shelfRole: String? = null,
  /**
   * What the canvas lane says about this builtin, overriding what [canvas] implies.
   *
   * A consumer computes the block from the adapter id, which can only produce "supported" or
   * "unsupported" with a sentence about the adapter. Two things a catalog knows and that cannot
   * say: an adapter that is `planned` rather than absent, and WHY a component draws the way it
   * does. The packaged vocabulary this contract is measured against carries both — `layout/box` is
   * `planned`, and `asset/image`'s note is four sentences about where the bytes come from — and a
   * catalog republishing those declarations had to drop them.
   *
   * It also matters per platform: a Wear palette rewrites every borrowed foundation component's
   * note to say that `androidx.compose.foundation` publishes one of these and not two, which says
   * the opposite of what a borrowed Material component's note said.
   */
  val wasm: UiBuilderBuiltinWasm? = null,
  /**
   * The call this builtin exports as, when the catalog states it outright.
   *
   * [implementation] answers the same question by pointing at a record entry, and is the better
   * answer when there IS one: the record is discovered, so it cannot drift from the source. This is
   * for the component that has no call site anywhere in this catalog and still exports as a known
   * callable — `layout/box` writes `Box` and imports `androidx.compose.foundation.layout.Box`, and
   * nothing in a foundation catalog's record can say so, because discovery does not scope to
   * foundation symbols. Without it such a builtin published with no code capability at all.
   */
  val code: UiBuilderBuiltinCode? = null,
  /**
   * Whether a structured-SVG export can draw this builtin, and what happens when it cannot.
   *
   * Not derivable from anything else in the declaration: whether the recorder has a vector for a
   * radial gradient is a fact about the recorder, and whether falling back to a raster is
   * acceptable is editorial. The packaged vocabulary states it for all seventeen components and a
   * catalog could not, so every republished declaration claimed nothing — which a consumer reads as
   * unverified rather than as the `verified` most of them are.
   */
  val svg: UiBuilderBuiltinSvg? = null,
)

/**
 * The canvas-lane block, mirroring the consumer's `WasmCapabilityV1`.
 *
 * Every field nullable so "not stated" and "stated" stay different questions: a catalog overriding
 * only [notes] keeps the platform support and adapter status the consumer derived from `canvas`.
 */
@Serializable
data class UiBuilderBuiltinWasm(
  val platformSupported: JsonElement? = null,
  /** `supported`, `planned` or `unsupported`. */
  val adapterStatus: String? = null,
  val notes: String? = null,
)

/**
 * The export call for a builtin with no record entry. Mirrors the consumer's `CodeCapabilityV1`.
 */
@Serializable
data class UiBuilderBuiltinCode(val symbol: String, val imports: List<String> = emptyList())

/** What a structured-SVG export makes of this builtin. Mirrors the consumer's `SvgCapabilityV1`. */
@Serializable
data class UiBuilderBuiltinSvg(
  val status: String,
  val fallback: String,
  val blocksExport: Boolean = false,
  val notes: String? = null,
)

/**
 * The values [UiBuilderBuiltin.shelfRole] may take.
 *
 * The UI builder's own vocabulary, not this generator's, and closed there: a word outside it names
 * no shelf, so the component is filed nowhere and the editor has no name for it.
 */
val UI_BUILDER_SHELF_ROLES: Set<String> = setOf("Scaffold", "Container", "Leaf")

/**
 * The values [UiBuilderBuiltinWasm.adapterStatus] may take, mirroring the consumer's
 * `WasmAdapterStatusV1`.
 */
val UI_BUILDER_WASM_ADAPTER_STATUSES: Set<String> = setOf("supported", "planned", "unsupported")

/**
 * How the builder shelves this catalog. Only the group ORDER is authored: the shelves themselves
 * come from `@CatalogGroup`, and no annotation on one component can state a total order over all of
 * them.
 */
@Serializable data class UiBuilderMenu(val groupOrder: List<String> = emptyList())

/** How this catalog's designs are written as source. */
@Serializable
data class UiBuilderCode(
  /**
   * `record` (the default): every node is a call site printed from the component record — all a
   * catalog of leaf components has to say. `templates`: the design has structure no record can
   * print, and the catalog supplies it in [templates].
   */
  val strategy: String = "record",
  /** What the export produces, for the label a person reads (`kotlin`, `kotlin-remote-compose`). */
  val language: String? = null,
  /** Imports every generated file needs. Per-component imports come from the record. */
  val imports: List<String> = emptyList(),
  /** Structural Kotlin with named holes, keyed by role. A hole-filler, not a language. */
  val templates: Map<String, String> = emptyMap(),
)
