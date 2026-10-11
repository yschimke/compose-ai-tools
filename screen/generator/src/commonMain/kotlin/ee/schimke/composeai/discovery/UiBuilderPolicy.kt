package ee.schimke.composeai.discovery

// The `ui-builder.policy.json` wire types live in compose-preview-contracts'
// `component-catalog-protocol`; this holds the closed sets `UiBuilderCatalogs` validates against.

/**
 * The JSON types a `@BuilderComponent.stateCallbacks` entry may give its state; the export prints
 * the hoisted `remember`'s initial value from it.
 */
val STATE_TYPES: Set<String> = setOf("boolean", "string", "number")

/**
 * The Kotlin classifiers each [STATE_TYPES] word may stand for (simple names, as
 * `TargetParameter.type` holds; `?` is stripped), so `checked:string` over a `Boolean` is caught.
 */
val STATE_TYPE_CLASSIFIERS: Map<String, Set<String>> =
  mapOf(
    "boolean" to setOf("Boolean"),
    "string" to setOf("String", "CharSequence"),
    "number" to setOf("Int", "Long", "Short", "Byte", "Float", "Double", "Number"),
  )

/**
 * The structural roles the template engine knows; a builtin's `role` must be one. Closed so the
 * builder can validate what a catalog asks for — a new role needs two catalogs that use it.
 *
 * `container` (box, column, row) holds children in a fixed arrangement with no repetition; without
 * it those had to masquerade as `list`.
 */
val UI_BUILDER_STRUCTURAL_ROLES: Set<String> =
  setOf("screen-root", "list", "list-item", "container", "overlay", "controlled", "decoration")

/**
 * How a catalog's designs are written as source. Closed: a word outside it selects no exporter.
 */
val UI_BUILDER_CODE_STRATEGIES: Set<String> = setOf("record", "templates")

/**
 * The named holes each template role may use, so templates are checked when the catalog is
 * published: a `${'$'}{contnet}` typo is a valid name and only this list catches it.
 * `${'$'}{call(...)}` is a hole kind, legal in every role, and not listed.
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
    // Not `list`'s holes: a container writes no repetition, so no list state or padding to thread.
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
 * The values [UiBuilderBuiltin.shelfRole] may take (the UI builder's vocabulary); anything else
 * files the component nowhere.
 */
val UI_BUILDER_SHELF_ROLES: Set<String> = setOf("Scaffold", "Container", "Leaf")

/**
 * The values [UiBuilderBuiltinWasm.adapterStatus] may take, mirroring the consumer's
 * `WasmAdapterStatusV1`.
 */
val UI_BUILDER_WASM_ADAPTER_STATUSES: Set<String> = setOf("supported", "planned", "unsupported")
