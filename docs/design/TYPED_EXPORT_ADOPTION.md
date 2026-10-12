# Adopting typed exports in a catalog

How a catalog moves a component's Compose export from the builder's hand-written exporter onto
**typed adapters on the real library composables**. Why this approach exists is covered in
[`TYPED_REAL_RECORD_ADAPTERS.md`](TYPED_REAL_RECORD_ADAPTERS.md), and how screen structure is
expressed is in [`TYPED_STRUCTURAL_TEMPLATES.md`](TYPED_STRUCTURAL_TEMPLATES.md). This page is the
checklist. The worked example is wear-m3-catalog's `catalog-typed-adapters` module:
`RealWearAdapters.kt` holds the definitions, and `RealWearExportTest` exports the fixture designs
through them.

The end state for a component: the catalog publishes, beside its record, an `exportProjection`
that says how a saved node becomes a call to the real composable. compose-ui-builder exports any
catalog that selects the `compose-component-records` export by reading those projections. It holds
no per-catalog code. Anything a projection does not cover is **refused by name**, never
approximated.

## What a catalog writes

### 1. One definition per component, on the real record

```kotlin
class RealIconAdapter(record: ComponentRecord) :
  TypedComponentAdapter<RealIconProperties>("wear-m3/icon", record) {
  val iconKey = wireProperty(RealIconProperties::iconKey, AdapterValueCodecs.materialIcon(), "filled/add")
  val sizeDp = wireProperty(RealIconProperties::sizeDp, AdapterValueCodecs.unit("dp"), 0f)
  val color = wireProperty(RealIconProperties::color, COLOR, WireColor.Unset)

  init {
    exportAs(
      target(
        record,
        mapOf("imageVector" to from(iconKey), "tint" to from(color)),
        modifier = listOf(ExportProjection.ModifierLink(SIZE, listOf(from(sizeDp)))),
      )
    )
  }
}
```

- **The record is discovered, never written by hand.** Take it from the catalog's discovery of
  the library (`TypedAdapterDiscovery`), and take every `…Defaults` factory a projection calls
  from `TypedAdapterDiscovery.discoverFactories`. Generation refuses an adapter built against a
  stale record or factory.
- **Properties are the builder's, not the composable's.** A `wireProperty` keeps the name and
  JSON shape that saved designs already use. Its codec both decodes the value for the renderer and
  lowers it for export.

| The property holds | Codec |
| --- | --- |
| text, flags, whole numbers | `AdapterValueCodecs.String` / `Boolean` / `Int` |
| a `Dp` / `Sp` | `unit("dp")`, `unit("sp")` |
| a theme colour or literal | `color(ExportProjection.Codec.Color(theme))` with `WireColor` |
| a theme token (`typography.titleMedium`) | `token(ExportProjection.Codec.Token(…))` |
| a Kotlin enum member | `enumOf(typeFqn, members)` |
| a Material icon key (`filled/add`) | `materialIcon()`; the host writes `Icons.Filled.Add` from its icon table |

### 2. How each property reaches the call

All of these are arguments to `target(…)`:

| The library wants | Write |
| --- | --- |
| a parameter from a property | `"tint" to from(color)`, or with `omitWhen` to keep the short call |
| a factory call (`ButtonDefaults.filledTonalButtonColors(containerColor = …)`) | `call(factories.named(fqn), "containerColor" to from(containerColor))` |
| a different composable per value (`FilledTonalButton`) | one `target` per record, with `variants = setOf(…)`, and `exportAs(…, variant = variant)` |
| a modifier from a property (`Modifier.size(…)`) | `modifier = listOf(ModifierLink(fqn, listOf(from(sizeDp))))` |
| a builder slot or event under the library's name | `slots = mapOf("content" to "label")`, `events = mapOf("click" to "onClick")` |
| children recognised as particular parameters (icon, label, secondaryLabel) | `slotPatterns` |
| a property drawn as a child (a header's text as a `Text`) | `synthesize` |
| more arguments when placed in a role (a list row's `transformation`, `Modifier.transformedHeight`) | `contexts = mapOf("list-item" to Context(…))` |
| a DSL whose children are each wrapped (`item { … }`) | `items = mapOf("content" to "item")`, plus `itemRoles` for the role that opens |
| a line of text into each slot (`title`, then the rest as `content`) | a `Lines` matcher; a `rest` the minimum needs counts as always written |

When a property genuinely cannot be written, say so with `exportRefuses(property)`. It stays on
the shelf, and the builder refuses any node that sets it. Validation reports every declared
property that a projection neither writes nor refuses.

### 3. Prove each definition against its record

`adapter.validateAgainst(adapter.component)` must be empty in the catalog's tests. It resolves
every target, factory call and modifier link the way the Kotlin compiler and the screen generator
would: overloads, required parameters, argument types and return supertypes. A mismatch is a test
failure, not a broken export at runtime.

### 4. Generate the published pair

```kotlin
TypedAdapterCatalog.generate(
  discovered, cover, policy,
  adapters = shelfAdapters,
  factories = discoveredFactories,
  implementations = listOf(listAdapter),
)
```

- `adapters` are the shelf components. Each one publishes its `exportProjection`, and its variant
  records ship off the shelf as export targets.
- `implementations` are adapters for the policy's **builtins**, such as the list. The builtin stays
  what the editor places. Its record ships off the shelf carrying the projection, and the builtin's
  `implementation` names it.
- The policy carries the rest:
  - `code`: the `screen-root` template around the generated root, the `locals` that frame
    declares (`listState`, `spec`, `contentPadding`), and the `scopes` a role's lambda runs in;
  - `composeSourceExport = compose-component-records` (version 1), which selects the generic
    export.

### The screen frame and its other slots

The `screen-root` template's `${content}` hole holds the generated root. A scaffold slot that a
design fills besides `content` goes into the hole of the same name: `${edgeButton}` inside the
scaffold's argument, or `${overlays}` after it for a dialog. Templates have no conditionals, so
publish one frame per set of filled slots, keyed `screen-root+<slot>[+<slot>]` in sorted order,
for example `screen-root+edgeButton` and `screen-root+edgeButton+overlays`. A design that fills a
set with no published frame is refused by name.

### The shared layouts

The builder's own `layout/column`, `layout/row` and `layout/box` export as Compose's
`Column`, `Row` and `Box` when the catalog's record carries those calls under the shared ids.
Discover them like any library composable, set each record's `componentIds` to its `layout/…` id,
and publish them off the shelf. A plain column of texts inside a slot with a `lines` pattern is
folded into the pattern's parameters instead, so it isn't written as a `Column`.

### 5. Export every committed design in a test

Run each fixture design through compose-ui-builder's `ScreenExportGate` with the generated pair,
as `RealWearExportTest` does. Assert two things: what emits, and what is still refused. The
refusals are the remaining port work. A definition that lands moves its fixture from "refused" to
"emitted".

## Cutting over

The steps from [`TYPED_REAL_RECORD_ADAPTERS.md`](TYPED_REAL_RECORD_ADAPTERS.md) still apply. Do
them in one change per catalog, once every committed design either emits or is refused for a
reason someone has reviewed:

1. Switch the production policy, the runtime and `composeSourceExport` in the same change.
2. Afterwards, delete that catalog's hand-written branches in the builder.

## Versions

Typed exports need the compose-ai-tools release after 2.51.0 (screen-model and the
preview-discovery API). They also need the compose-ui-builder release that reads builtin
implementations, the `compose-component-records` export and `itemRoles`.
