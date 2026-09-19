# The UI builder catalog policy — this repository's half

Status: **in progress** (2026-09). What a catalog repository authors so that a UI builder can serve
it, and what this repository does with it. The contract itself, its phasing and its acceptance test
live in the preview server, which is the consumer:
[`UI_BUILDER_CATALOG_CONTRACT.md`](https://github.com/yschimke/compose-preview-server/blob/main/docs/design/UI_BUILDER_CATALOG_CONTRACT.md).
Read that first if you want the *why*; this document is the *where*.

## The problem this repository is asked to solve

The preview server's UI builder serves three catalogs, and two of them are written in Kotlin **in
the server** — synthesised at startup, exported by hand-written emitters, drawn from geometry copied
out of a test in wear-m3-catalog, chosen from an enum. A fourth catalog costs a release of a
repository that has never seen its components.

The fix is to make a builder catalog a **published artifact of the catalog repository**, read over
the same route as `components.json`. That makes it this repository's problem, because this
repository is where a catalog is discovered, built and published — layer 1 of
[`REPOSITORY_LAYERS.md`](REPOSITORY_LAYERS.md), between the catalog repositories and the server.

## The three inputs and the one output

Nothing is typed twice. A builder catalog is generated from what the catalog repository already
owns, plus one authored file for the residue:

| Input | Owner | What it carries |
| --- | --- | --- |
| the component record (`components.json`) | derived, this repository | every parameter, slot, call site and opt-in marker — read from `@kotlin.Metadata` ([`COMPONENT_RECORD.md`](COMPONENT_RECORD.md)) |
| `catalog.spec.json` | the catalog repository | the cover sheet: `system`, `title`, `library[]`, `modes[]`, `breakpoints[]` |
| `@BuilderComponent` | the catalog repository, beside `@CatalogComponent` | per-component policy no signature holds |
| `ui-builder.policy.json` | the catalog repository, beside `catalog.spec.json` | catalog-level policy no component owns |

→ **`ui-builder.json`**, generated, never edited.

### Why the split between the annotation and the file

Both catalog repositories are annotation-first by rule: the inventory is `@CatalogGroup` /
`@CatalogComponent` / `@CatalogVariant` beside the `@Preview`s, and `catalog.spec.json` is a cover
sheet. A second per-component inventory in JSON would be a second place to rename a component —
exactly the failure `@CatalogComponent` was introduced to end, one level down. So per-component
policy is an annotation, and the file carries only what belongs to no component: the platform word,
the frame, the structural code templates, the template designs.

The one exception is `builtins`, and it is the exception that proves the rule: a screen scaffold the
catalog structures around, or a shape that is not a composable at all, has **no call site**, so it
cannot be in the record and there is no sticker to annotate. A builtin must name a structural role,
and a component that has a call site belongs in the record.

## Where it is generated, and why not in the pipeline

`ui-builder.json` is written by the **discovery task**, into `build/compose-previews/` beside
`components.json`, from the same scan. The design-artifacts pipeline then copies it to the delivery
branch root and stamps `uiBuilderFile` on `catalog.json` — the job
[`catalog-component-record.mjs`](../../scripts/design-artifacts/catalog-component-record.mjs)
already does for the record.

The obvious alternative was a `generate-ui-builder-catalog.mjs` in the design-artifacts workflow.
That lane exists only in CI, and only for a catalog with a delivery branch, which quietly assumes
the deployed server is the only consumer of the file. It is not: `compose-preview-server ui` points
a builder at a local Gradle project with no branch anywhere, and it is the consumer that most wants
one — today it copies `build/compose-previews/components.json` and keeps a packaged Material 3
palette, so a person sitting in wear-m3-catalog is drawing with someone else's design system.

Generating in discovery serves both lanes from one implementation. The alternative was two, and this
repository's own `serve-wasm` fork is the standing evidence for what a mirror costs.

## `@BuilderComponent`

[`api/preview-annotations/…/BuilderComponent.kt`](../../api/preview-annotations/src/commonMain/kotlin/ee/schimke/composeai/preview/BuilderComponent.kt),
discovered by the same ClassGraph scan that reads `@CatalogComponent` (`@Target(FUNCTION)`, `BINARY`
retention, matched by FQN, never loaded), resolved into
[`BuilderPolicy`](../../screen/generator/src/commonMain/kotlin/ee/schimke/composeai/discovery/BuilderPolicy.kt)
and attached to `PreviewInfo.builder`, then carried onto `ComponentRecord.builder`.

```kotlin
@CatalogComponent(id = "Toggles/CheckboxButton", reference = "figma:…")
@BuilderComponent(
  id = "wear-m3/checkbox-button",
  canvas = "placeholder",
  stateCallbacks = ["onCheckedChange=checked:boolean"],
  starter = ["label=Checkbox"],
)
@CatalogModes @Composable fun CheckboxButtonSticker() = Sticker { CheckboxButton(…) }
```

Three properties worth stating, because each is a decision rather than an accident:

- **It never makes a component appear.** A catalog that annotates nothing still publishes a builder
  catalog: every record component the pack rules admit is on the shelf, grouped by its
  `@CatalogGroup`, drawn as a placeholder. The annotation is how a catalog *disagrees* with that
  default for one component, so a catalog with no disagreements writes none of them.
- **Nothing is defaulted on the catalog's behalf.** A blank argument records `null`, not the value a
  generator would pick — "the catalog did not say" and "the catalog said `placeholder`" are
  different facts, and a report of which components nobody has looked at is only true if the record
  can tell them apart. An all-defaults annotation is therefore still recorded rather than folded to
  `null`: writing it is a statement that somebody considered this component.
- **A disagreement is recorded, not resolved.** Several previews can render one component and any of
  them may carry the annotation. Where they agree, `declaredBy` names every preview that said it;
  where they disagree the **lowest preview id** wins and the rest are named in `conflicting` — by
  id rather than by manifest order, because manifest order is not a fact anybody controls and a
  record that changes which policy it publishes when a preview is renamed cannot be reviewed.

## `ui-builder.policy.json`

Schema: [`scripts/design-artifacts/ui-builder.policy.schema.json`](../../scripts/design-artifacts/ui-builder.policy.schema.json),
which carries the field-by-field documentation, and a build-free pre-flight beside
`validate-catalog-spec.mjs`:

```
node scripts/design-artifacts/validate-ui-builder-policy.mjs --policy ui-builder.policy.json
```

Not a JSON Schema validator — the schema is the contract and the thing an editor autocompletes
against, and a second hand-rolled implementation of it would drift. It checks the subset a schema
states poorly or not at all: that a builtin names a role the engine knows, that templates and the
declared strategy agree, that padding rows ascend (a reader interpolates between adjacent ones), and
that prose has not been written inside `builtins`, where it is a *parse* failure rather than a
schema one because those values are typed. The shape, in brief:

```jsonc
{
  "schema": "compose-ui-builder-policy/v1",
  "platform": "wear",                  // a word; equality is compatibility
  "platformLabel": "Wear",
  "previewSurfaces": { "wasm": {…}, "native": {…} },
  "frame": { "adapter": "frame/round-screen", "seedDevice": "id:wearos_small_round",
             "geometry": { … } },      // written by a test, never by hand
  "builtins": { "wear-m3/screen-scaffold": { "role": "screen-root", "slots": {…} } },
  "menu": { "groupOrder": ["Screens", "Layout", …] },
  "code": { "strategy": "templates", "imports": […], "templates": { "screen-root": "…", … } },
  "templates": ["ui-builder/designs/wear-list.json"]
}
```

Two rules the schema enforces and this document explains:

**Geometry is written by a test.** `frame.geometry` — the content-padding table, the corner radius,
the sampled colours, a widget host's two sizes — is emitted by the catalog repository's own
Robolectric probe, which then asserts the committed file equals its measurement. The server carries
copies of these numbers today, transcribed out of `ScreenScaffoldContentPaddingTest` in
wear-m3-catalog; a Wear Compose bump moves the number where it is measured and nowhere else. A
hand-edited block is a failing test, which is the whole reason the block lives here.

**The structural roles are a closed set.** `screen-root`, `list`, `list-item`, `container`,
`overlay`, `controlled`, `decoration`. Six of the seven are the roles the Wear screen emitter's
1,406 lines decompose into; `container` is the one that is not about a screen's decomposition at
all, and it is the worked example of how the set grows. A catalog that needs an eighth means the
template engine grows one, once, with a test; it does not mean every catalog ships a compiler. The
test that a new role is general is that two catalogs use it.

### Why `container` exists

A box, a column and a row hold children in a fixed arrangement and write no repetition. Before
this word the only role that admitted children in sequence was `list`, whose template is handed
`${listState}`, `${contentPadding}` and `${items}` — so declaring `layout/box` as a `list` says a
box is a scrolling list, and it is not. `container`'s template gets `${children}` and nothing else,
because there is no shared list state to thread and no measured padding to pass.

The generality bar was met from two directions at once: `compose-foundation`
([yschimke/m3-catalog](https://github.com/yschimke/m3-catalog/blob/main/docs/design/FOUNDATION_CATALOG.md))
declares the three, and the packaged builder vocabulary it was copied from carries the same three
for every platform that borrows them.

### What a builtin may state about itself

A builtin has no call site, so its declaration is the only source there is — and five things a
consumer otherwise **derives** were unstateable, which is not the same as unstated: the derived
answer was published as though the catalog had agreed with it.

| field | what it says | what the absence meant |
| --- | --- | --- |
| `shelfRole` | `Scaffold` / `Container` / `Leaf`, the shape the editor names and slots accept | derived from whether there were slots at all, so a design root arrived as an ordinary container |
| `wasm` | the canvas lane: `platformSupported`, `adapterStatus`, `notes` | derived from the adapter id, which can say supported or unsupported and never `planned` |
| `code` | the callable a design exports as, and its imports | a builtin with no record entry published no code capability at all |
| `svg` | what a structured-SVG export makes of it | every republished component claimed nothing, which reads as unverified rather than as the `verified` most are |
| `slots.*.ordered` | whether the order of a slot's children is meaningful | every slot composed as ordered; six of the packaged vocabulary's fifteen are not |

`shelfRole` and `role` are two vocabularies in one declaration, and the capability document a
consumer serves publishes `shelfRole` under the name `role`. `role` says which template **writes**
the component; `shelfRole` says what **shape** it is. Each refuses the other's words, because
crossing them is the mistake this design makes easy.

`code` and `implementation` answer the same question, and `implementation` is the better answer
when there is one: it points at a record entry, and the record is discovered, so it cannot drift
from the source. `code` is for the component that has no call site anywhere in the catalog and
still exports as a known callable — `layout/box` writes `Box`, and nothing in a foundation
catalog's record can say so, because inference scopes library components to
`material3`/`material`/`wear` ([`COMPONENT_RECORD.md`](COMPONENT_RECORD.md)).

### The editing canvas's mock

Every other field in this file describes what a consumer **serves**. `unrolled` describes what the
editor **draws**, and those are not the same picture: a lazy column rendered as itself shows the
rows that fit the frame, so the ninth row is not on the canvas and cannot be edited. The editor has
always drawn that split — constrained in the preview pane and in every device frame, unrolled while
an author is inside the container — but *which* container unrolls, and *how*, was hardcoded in the
builder. A catalog knows its own components, so it states it, beside `canvas`, on a record component
and on a builtin alike:

```jsonc
"m3/lazy-column": {
  "record": "…LazyColumnKt.LazyColumn",
  "canvas": "material3/LazyColumn",
  "unrolled": { "layout": "stack" }        // every row, laid out as a column while editing
}
```

`layout` is the builder's vocabulary, exactly as `canvas` is: `stack` (a `Column`), `row` (a `Row`),
`wrap` (a `FlowRow`) or `panes` (every pane a pane scaffold declares), with `cellWidthDp` and
`spacingDp` for the layouts that tile. A name the consuming build does not know is inert — the
component draws as itself — so stating one does not pin the builder's vintage, and a catalog that
states nothing keeps today's behaviour on every component.

## What a consumer may assume

The file is published once and read by builders of several vintages that the publisher cannot
upgrade — a deployed server, a `serve` on a laptop, a locally installed `compose-preview-server ui`,
and the VS Code extension reading through one of those. So:

- **Unknown fields never fail a load.** `schema` refuses a future *major*, not a minor.
- **Adapters and template roles are negotiated, never required.** A catalog naming a canvas or frame
  adapter this build does not ship gets the placeholder and one log line; a template naming an
  unknown role costs that one export. Neither costs the catalog its palette, because the operator of
  that builder cannot fix it.
- **A locally generated builder catalog is unpinned**, and says so. A design pins a catalog
  revision; a delivery branch has one and a `build/` directory does not.

## Status

- [x] `@BuilderComponent` in `preview-annotations`, read by `PreviewDiscovery` into `BuilderPolicy`
      and carried onto `ComponentRecord.builder`.
- [x] `ui-builder.policy.schema.json` — what the catalog repositories author against, plus
      `validate-ui-builder-policy.mjs`, the pre-flight that reads one before a 90-minute render does.
- [x] The generator (record + cover sheet + policy → `ui-builder.json`) in the discovery task and
      in the bundle, written to `build/compose-previews/ui-builder.json`.
- [x] `catalog-ui-builder.mjs`: publish it to the branch root, stamp `uiBuilderFile`.
- [x] The structural template engine (`StructuralTemplate`) in `screen/generator`, beside
      `ScreenGenerator`: `${name}` substitution and `${call(...)}` record call sites, no
      conditionals, and indentation of a multi-line value to the column of the hole that starts its
      line. `UiBuilderCatalogs` reads each declared template through it, so a malformed one is
      reported against the role that declares it when the catalog is published.
- [ ] Wiring the engine to a design: resolving a node's role, its slot children and its record call
      site, so a `code.strategy: "templates"` catalog exports through its own structure. The engine
      fills holes; nothing yet decides what goes in them.
- [ ] Typed fields on `CatalogCapabilityV1` in compose-preview-contracts (not on the critical path;
      `statusSemantics` carries them until then).
- [x] The editing canvas's mock in the policy: stated beside `canvas`, carried into `ui-builder.json`
      for a record component and a builtin, and covered by the schema, the pre-flight and the
      generator's tests. The wire type (`WasmCapabilityV1.unrolled`) is
      [compose-preview-contracts#84](https://github.com/yschimke/compose-preview-contracts/pull/84);
      the builder's registry and its constrained/unrolled switch follow it.
