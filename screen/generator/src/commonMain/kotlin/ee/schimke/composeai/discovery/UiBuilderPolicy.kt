package ee.schimke.composeai.discovery

// The `ui-builder.policy.json` wire types (`UiBuilderPolicyFile` and the shapes it holds) are in
// compose-preview-contracts' `component-catalog-protocol`. What stays here is the validator's
// vocabulary: the closed sets `UiBuilderCatalogs` checks a policy against.

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
