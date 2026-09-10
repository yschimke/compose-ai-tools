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

val UI_BUILDER_STRUCTURAL_ROLES: Set<String> =
  setOf("screen-root", "list", "list-item", "overlay", "controlled", "decoration")

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
 * [previewSurfaces], [frame] and [colorTokens] are carried through to the generated file
 * **verbatim** rather than parsed into Kotlin. They are read by the preview server, whose types own
 * their shape; re-declaring them here would put a second definition of somebody else's contract in
 * the middle of the pipeline, where it would be the thing that has to be updated for a field this
 * generator never looks at. What this generator validates about them, it validates structurally.
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
  val frame: JsonElement? = null,
  val builtins: Map<String, UiBuilderBuiltin> = emptyMap(),
  val menu: UiBuilderMenu? = null,
  val code: UiBuilderCode? = null,
  /** Branch-relative paths of the template designs offered in the New design chooser. */
  val templates: List<String> = emptyList(),
  val colorTokens: JsonElement? = null,
  val assetRegistry: JsonElement? = null,
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
   * Merged onto the annotation-derived entry for the same id, so the two can be used together and
   * neither has to carry the other's concerns. An entry naming an id no component derives is
   * reported rather than dropped — see `Diagnostics.POLICY_ORPHANED`'s sibling for the annotation
   * case, and the same argument: a policy naming nothing is a rename that got away.
   */
  val components: Map<String, UiBuilderAuthoredComponent> = emptyMap(),
)

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
  val nativeOnly: Boolean? = null,
  val traits: List<String>? = null,
  /** Kept off the shelf, with the stated reason. */
  val excluded: String? = null,
  val propertyCapabilities: List<JsonElement>? = null,
  val slotCapabilities: List<JsonElement>? = null,
  val modifierCapabilities: List<String>? = null,
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
)

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
