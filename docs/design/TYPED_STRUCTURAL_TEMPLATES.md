# Structure for typed real-record exports

Status: **proposed**. Follows [`TYPED_REAL_RECORD_ADAPTERS.md`](TYPED_REAL_RECORD_ADAPTERS.md), whose
`ExportProjection` writes **one call**: it maps a node's properties to the real record's parameters
through codecs and factory calls. This document covers everything a real catalog's export needs
*besides* that one call, so a catalog can replace its builder's hand-written exporter instead of
running beside it.

## What the hand-written Wear exporter does that a projection cannot

An inventory of compose-ui-builder's `WearContentEmitter` / `WearScreenCodeExporter` (≈2,000 lines,
the production `wear-compose-screen` path) sorts what it does into five kinds. Only the first is a
projection today.

1. **Per-call argument mapping** — symbol by variant, `ButtonDefaults.…Colors(…)`, enum and colour
   tokens, `enabled` written only when false. *Covered by `ExportProjection`.*
2. **Child recognition.** The same children become different parameters depending on their shape:
   - `wear-m3/button`: an optional leading `wear-m3/icon` becomes `icon`; then exactly one `Text`
     (label), or a column of 1–2 texts (`label` + `secondaryLabel`), uses the slot overload;
     anything else uses the content overload.
   - `wear-m3/card` `app`: the single child must be a column of ≥ 2 texts — `appName`, `title`, the
     rest the trailing content — or the export refuses. `TitleCard`: first line is `title`, the rest
     content; no column at all puts everything in `title`.
   - The "column of texts" test is precise: a `layout/column` with no modifiers, default
     arrangement and alignment, and only `wear-m3/text` children. The column itself disappears.
3. **Properties that become slots.** `ListHeader`'s `text` property is written as
   `{ Text(text = …) }`; selection buttons' `label`/`secondaryLabel` likewise; dialogs'
   `title`/`text`, and `curvedText = { confirmationDialogCurvedText("…") }`.
4. **Arguments the parent decides.** A direct child of the screen's `TransformingLazyColumn` gets
   `Modifier.transformedHeight(this, spec)`, a per-component
   `minimumVerticalContentPadding(XDefaults.minimum…ListContentPadding)` (Text, ListHeader, Card,
   Button, ButtonGroup, IconButton, TextButton — each from its own `…Defaults`), and
   `transformation = SurfaceTransformation(spec)` only for components that declare it. One level
   deep only.
5. **Screen structure.** `AppScaffold` / `ScreenScaffold(scrollState, scrollIndicator, edgeButton)`
   / `TransformingLazyColumn(state, contentPadding)` / `item(key) { }`, hoisted `listState` and
   `spec`, `remember`ed state for uncontrolled controls and overlay visibility, theme wrappers,
   previews.

## The pieces, and which already exist

| Kind | Mechanism | State |
| --- | --- | --- |
| 1 per-call | `ExportProjection` (targets, codecs, factory `call`s) | #5828, #5832 merged; #5835, #5840 in review |
| 5 screen structure | catalog `code.templates` by structural role, rendered by `StructuralTemplate` | vocabulary, hole contract (`UI_BUILDER_TEMPLATE_HOLES`) and publish-time validation exist here; **no builder reads them** |
| 4 parent-driven arguments | **new:** a target's `contexts` | this document |
| 2 child recognition | **new:** a target's `slotPatterns` | this document |
| 3 property → slot | **new:** a slot `synthesize` binding | this document |

### Screen structure: real records inside, one frame around them

**Decided (2026-10-11): everything a record can print is a record; only the frame is text.**

An earlier draft kept a text template per role (`screen-root`, `list`, `list-item`) and asked the
generator for each item's call as a fragment to splice into them. That splits import, name and
scope resolution between two systems, and every check the generator makes (a component called by
simple name, a local that would shadow it, a scoped link under the wrong receiver) would have to be
remade for the text around it. Most of the structure does not need text:

- **The list is a record.** Wear's `TransformingLazyColumn(state = listState, contentPadding =
  contentPadding)` is the design root's projection, its arguments read through
  `ScreenValue.LocalRead`.
- **Each item is a `SlotItem`.** The list's DSL slot wraps every child in `item { … }`, and the item
  scope the catalog declares (`code.scopes["list-item"]`) is that wrapper's attested `bodyScopeFqn`,
  so `Modifier.transformedHeight(this, spec)` inside it is checked like any other scoped read.
- **The frame is the `screen-root` template**, written by `ScreenGenerator.generate(…, frame)`
  around the root, with one hole: `${content}`. It declares what the records read and no record
  declares: `val listState = rememberTransformingLazyColumnState()`, `val spec = …`, and
  `ScreenScaffold(scrollState = listState) { contentPadding -> … }`. Those names are the catalog's
  `code.locals`, and the frame's own imports are `code.imports`. The generator refuses a frame that
  writes `content` other than once, names any other hole, or carries a `call(…)` hole.

`list` and `list-item` templates are not read by this path; they remain in the vocabulary for the
catalogs that publish them until the hand-written exporters are retired.

Two things stay open, each refused by name rather than approximated until done:

- **Per-item keys.** The hand-written exporter writes `item(key = "<stableKey>")` when a node has a
  stable key. A `SlotItem` is per slot, so the key needs a per-child argument on the wrapper (a
  screen-document addition, through a Builder). The `list-item` `key` hole added for the text
  draft is unused here.
- **The frame's other slots.** `edgeButton` and overlays are children of the screen, not the list.
  They become further frame holes rendered from generator nodes, the same way `content` is, once
  the first screens export.

### Parent-driven arguments: `contexts`

A projection target may say what it writes **when it is placed in a structural role**:

```jsonc
{ "record": "…ButtonKt.Button",
  "contexts": {
    "list-item": {
      "parameters": { "transformation": { "fixed": <SurfaceTransformation(spec)> } },
      "modifier":   [ <transformedHeight(this, spec)>,
                      <minimumVerticalContentPadding(ButtonDefaults.minimumVerticalListContentPadding)> ] } } }
```

- Values are the projection's existing `Value`s, so a context argument is type-checked against the
  record exactly as an ordinary one (`transformation` must exist and take `SurfaceTransformation`).
- `modifier` links **append** after the node's authored chain, in the order the hand-written
  exporter uses, and are `ChainLink`s the generator already prints.
- **The item scope is an explicit argument, not a receiver.** Wear's `transformedHeight` is a
  top-level `Modifier` extension that takes the `TransformingLazyColumnItemScope` as a parameter:
  `.transformedHeight(this, spec)`. `ChainLink.receiverScopeFqn` means something else, the implicit
  receiver of a *member* extension such as `RowScope.weight`. So a context value reads the scope
  through a typed **role scope** reference instead. The `list-item` role declares that it opens a
  scope of type `…TransformingLazyColumnItemScope` (`code.scopes`, beside `code.locals`), the
  renderer threads it to the item's direct child, and the generator prints it as `this`. A value that
  reads a scope its role does not open is refused, as is one placed outside that role. The existing
  item-wrapper path deliberately does not attest its trailing lambda's receiver; this is where that
  attestation comes from.
- `spec` and `listState` are **template locals**: names a catalog's templates declare (new
  `code.locals: { "spec": "…TransformationSpec", "listState": "…TransformingLazyColumnState" }`).
  A context value reads one as a typed reference, and validation refuses a read of an undeclared
  local, so a projection cannot print a name its templates never define.
- Only the *direct* child of a role gets the role's context — the inventory's "one level deep".

### Child recognition: `slotPatterns`

A target may replace "slot `content` → parameter `content`" with an ordered list of patterns over the
slot's children. The first pattern that matches decides which child goes to which parameter. The parameters it writes
then pick the **overload**, the way Kotlin resolves named arguments. If no pattern matches, the
target says whether to refuse or where the children go instead:

```jsonc
"slotPatterns": {
  "content": {
    "patterns": [
      { "match": [ { "component": "wear-m3/icon", "optional": true, "to": "icon" },
                   { "lines": { "max": 2 }, "to": ["label", "secondaryLabel"] } ] } ],
    "otherwise": { "to": "content" } } }
```

The matcher vocabulary is closed, like the codecs, so it stays data that can be validated:

- `component` — one child of that catalog id.
- `lines` — a single `wear-m3/text`, **or** a column that passes the inventory's exact column-of-texts
  test, flattened to its texts; `min`/`max` bound the count; extra lines may go to a `rest` parameter
  (the card body).
- `any` — the remaining children, in order.

Validation checks that each pattern's parameters, together with the target's ordinary ones, resolve
to exactly one overload, and that every `to` is a composable slot of it. `otherwise` must be `refuse`
or resolve likewise.

Overloads that differ only in their slots, such as Wear's `Button(…, label)` and `Button(…, content)`,
need the record to describe **every** overload. Today discovery keeps one record per callable, with
the single overload `OverloadSelection` chose against the names a policy authors (`known` holds the
rest but does not publish them). Publishing all overloads' parameters is the first prerequisite
below.

### Properties as slots: `synthesize`

A slot parameter may be written from a property by **synthesizing a child node of another catalog
component**, which then exports through its own projection:

```jsonc
"slots": { "label": { "synthesize": { "component": "wear-m3/text", "properties": { "text": "label" } } } }
```

So `ListHeader`'s header is the catalog's own `Text` projection, with the same style rules. It is not
a string template that drifts from it. The synthesized node inherits nothing from the parent except
what the binding names.

## What stays in the builder

The **matcher semantics** (what "a column of texts" is), the **template renderer** (`StructuralTemplate`,
shared via `screen-model`), and hoisting `remember` declarations through the `controlled` template. A
catalog states which pieces apply; it never ships code. A design using something no projection,
pattern or template covers is **refused with the reason**, never approximated. That is the current
exporter's behaviour, and the equivalence gate below holds the new path to it.

## Proof before cutover

For every committed design and template in the catalog, export it through the hand-written path and
the projection path and compare their structure: the calls, in order, with their arguments,
ignoring imports, whitespace and how a name is qualified. The generator writes some names
qualified where the hand-written exporter imports them, so byte-identical source is not the bar. Where the
difference is deliberate (the new path refusing what the old silently dropped), the gate lists it by
design and reason, and each listed difference is reviewed. The rendered-parity and
compile-the-export gates from `TYPED_REAL_RECORD_ADAPTERS.md` step 7 run beside it.

## Order of work

1. **Discovery: publish every overload's parameters** on a record (an additive `overloads` field,
   contracts first), so patterns resolve an overload the way Kotlin does.
2. **Tools:**
   - `contexts`, `slotPatterns`, `synthesize`, `code.locals` and `code.scopes` in `ExportProjection`
     / the policy, and the `list-item` `key` hole;
   - a role-scope reference in the screen document, printed as `this` inside the role's lambda;
   - JSON spelling;
   - validation against records;
   - a generator-side `lower` that returns the chosen record and child assignment.
3. **Tools, for the frame:** `ScreenGenerator.generate(…, frame)` (the `screen-root` template
   around the root); then a projection target's DSL slot naming its item wrapper (`item`), with the
   wrapper's body scope taken from `code.scopes["list-item"]`.
4. **Builder:** export a projection catalog's screen through those (projected slots and synthesized
   children landed in compose-ui-builder#712), selected by `composeSourceExport` (a new adapter
   id), with role contexts applied to the list's direct children. Also the structural equivalence
   harness against `WearScreenCodeExporter`.
5. **Wear:**
   - real-record definitions with projections, patterns and templates;
   - green on all three gates;
   - switch `composeSourceExport`, then delete `WearContentEmitter`'s per-component branches.
