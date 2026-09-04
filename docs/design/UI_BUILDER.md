# UI builder — assembling screens from the component catalog

Status: **partly built** (revised 2026-09; original proposal 2026-07). The
original said "no code yet"; that is no longer true, and two things learned since
change the plan rather than just its progress — read
[§0](#0-what-changed-since-the-proposal) before the phases.

> **Thesis.** ~70% of a catalog-driven UI builder is already built and running
> across `compose-ai-tools` and the `design-parity` `figma-plugin`. The Compose
> code is already the source of truth, and `compose/figma-svg` already exposes
> each component to Figma *identified* (a named layer per composable), not as a
> flattened screenshot. The work is to (1) make **scaffolds slot-fillable**, (2)
> add a **persisted screen-composition document**, (3) give each exported layer a
> **stable per-instance identity**, and (4) eventually **generate real Compose**
> from a composition. Everything else is wiring seams that already exist.

---

## 0. What changed since the proposal

Three findings, in order of how much they move the plan.

### 0.1 The target is a composition *tree*, not a filled scaffold

The interaction this is for, stated by the person who wants it:

> I want to be able to add a component, say `LazyColumn`, then add a `ListHeader`,
> edit the text. Then add a `Card`, select the style. Then generate the code from
> it, or run in the playground.

That is a **tree with per-instance props** — add a container, nest a child in it,
edit *that child's* values, pick *that child's* variant. [Gap 1](#31-gap-1--scaffolds-arent-slot-fillable-yet-highest-leverage-smallest-change)
gives fixed **named** slots (`topBar`, `fab`, `content`), which is necessary
substrate and is not the same thing: a `LazyColumn` takes N arbitrary children,
not a named region. Slot-filling is a step on the way, not a smaller version of
the destination.

Two consequences worth stating before more code is written:

* **Per-instance editing needs an indexed knob.** Editing the text of the third
  `ListHeader` in a list is `previewOverrideString(key, default, index = i)` — the
  `index` parameter the `previewOverride*` format has and the parameter-knob
  format structurally cannot (a parameter list is fixed-arity; see
  [PARAMETER_KNOB_MIGRATION.md](PARAMETER_KNOB_MIGRATION.md) gap 3). Whatever else
  the two formats do, the builder rides the older one.
* **"Select the style" is variant selection**, which already exists as
  `@CatalogVariant` / `@OverrideVariant` cells — a picker over declared variants,
  not a new mechanism.

### 0.2 The "run it" half is already built — which moves codegen from last to load-bearing

[PLAYGROUND.md](PLAYGROUND.md) is **not** a proposal any more: phases 1–3 (CMP,
Android, Remote Compose) and the phase-4 per-session sandbox are shipped. It
compiles Kotlin without Gradle (`BtaCompileSession`), renders it headlessly, and
streams a **live, clickable** composition over the serve `input` protocol.

So "generate the code from it, **or** run in the playground" is not two futures.
It is one pipeline:

```
composition tree  ->  Compose source  ->  BTA compile  ->  live interactive preview
```

That reorders this document's own plan. Codegen was phase 4, "the dream". It is
actually the **bridge**, because the thing it feeds already runs in production —
and it means the tree never needs a runtime interpreter, since its output is just
Kotlin.

### 0.3 Half of this lands in the other repository

The preview-server split moved the *reader* side out. Checked, not assumed:

* `PreviewSlot` (the writer) is here, in `runtimes/slots/`.
* The `dp-slot:` **reader** and `/render/<id>.slots` are in
  [`compose-preview-server`](https://github.com/yschimke/compose-preview-server)
  (`render-host/.../ServeRenderHost.kt`). `data/layoutinspector/core/PreviewSlots.kt`,
  which §2 still cites, does not exist in this repository.
* The **playground** is there too (`server/.../Playground*`).

So phase 2 (`screen.json` + endpoints) and the codegen→playground handoff are
mostly *that* repository's work, with this one supplying the catalog, the slots
and the knobs. The §2 table predates the split and still points several rows at
`cli/.../serve/…` paths that moved with it.

### 0.4 Wear M3 cannot run in the browser, so composition has to stay server-side

Asked directly — does this work for Wear M3, and does it run in wasm? The two
halves have different answers, and together they constrain the architecture.

**Wear M3 does not compile to `wasmJs`.** `androidx.wear.compose` is Android-only,
and `:samples:cmp-wasm-catalog` says so in its own build file:

> The Android design-catalog modules can't compile to `wasmJs` (Android-only
> `@Preview` / `Configuration` / `wear.compose`), so the shared module exposes the
> same components through a plain id→composable registry instead.

So a builder that composes the catalog **in the browser** is a mobile-M3-only
builder by construction. That rules out one plausible design outright.

**Wear M3 does run live, server-side.** `wear-m3` is already a shipped live
streaming lane on `preview.coo.ee` — the Robolectric daemon composes it and the
serve `input` protocol streams pixels out and events back
([PLAYGROUND.md](PLAYGROUND.md) §2, phase 2).

The conclusion is a constraint, not a blocker: **the wasm UI is a client, not the
compositor.** The tree is composed where the catalog can actually run — desktop
for CMP, Robolectric for Android and Wear — and the browser shows the result and
sends edits. That is the same shape [§0.2](#02-the-run-it-half-is-already-built--which-moves-codegen-from-last-to-load-bearing)
already implies, and it is why the codegen→playground path works for Wear while an
in-browser interpreter never could.

> **A drift risk this turned up.** `slot-preview-runtime` is **forked** into the
> server repo, and the two copies are byte-identical today — but unlike the
> `serve-wasm` fork there is no CI gate holding them so
> (`.github/ci/check_serve_wasm_fork.py` has no sibling for this one). The slot
> tag grammar is a wire contract between a writer here and a reader there; if the
> builder is going to lean on it, that fork wants either a gate or a published
> coordinate.

---

## 1. Product analysis

### 1.1 The user story

A designer wants to:

1. Pick a **scaffold** — a phone screen with an OS status bar, or a Wear app with
   `TimeText` and maybe a page indicator.
2. Select a **theme**, and customise details like the device dimensions.
3. Drop **catalog components** into the scaffold's **slots** / layout guides —
   customising each component's content, or leaving a placeholder.
4. Keep every placed component + its customisations as **structured data**, not an
   opaque SVG/PNG — so each component can be **refreshed** as the code is fixed, or
   **replaced** later.
5. See **variants** (theme, display size) that stay **connected and synced** to the
   one underlying structure.

…delivered through one of three surfaces: **(a)** native Figma, **(b)** a Figma
plugin dialog, or **(c)** `preview.coo.ee`, saved and referenceable, insertable
into Figma as SVG/PNG. Dream feature: **convert the composition to Compose code**
using the real composable slots.

### 1.2 Prior art — does this exist? (short answer: not as one tool, and the closest died)

| Tool | What it is | Why it isn't the fit |
|---|---|---|
| **Google Relay** | Figma frames → Jetpack Compose composables with slots/parameters. The *exact* Figma↔Compose slot-mapping idea, from Google. | **Sunset 2025‑04‑30.** Figma→Compose only (never the reverse). Relied on manual designer annotation — the fragility that likely killed it. Leaves this space open. |
| **UXPin Merge** | Assemble screens from *real code components* (via Storybook/npm), structured tree, live-synced to code. Closest philosophy overall. | React/web only, no Compose, **no identity-preserving Figma export**, closed SaaS. |
| **Builder.io + Visual Copilot** | `registerComponent` + structured JSON tree + Mitosis codegen; AI maps Figma comps → code comps. | Web frameworks only (Mitosis has no Compose target); Figma is import-direction. |
| **Figma Code Connect** (MIT) | Maps a Figma component → its production code snippet + prop mapping; surfaces in Dev Mode + MCP. | **One-directional metadata.** Presumes the Figma component already exists; won't create it from a catalog. The standard to *emit into*, not a builder. |
| **Supernova** | Design-system platform; already consumes **DTCG tokens** and **exports Android Compose**. | Token/docs oriented, not a slot assembler. Good token-interop target. |
| Tempo, Framer, Anima/Locofy, Modulz (defunct), Storybook, Ladle/Histoire, Chromatic, Zeroheight, Backlight | code-backed editors / catalogs / doc syncers | All web-framework centric; none ingest a Compose catalog; none preserve component identity through an SVG round-trip. |

**The genuinely unclaimed part is the SVG-with-component-identity round-trip.**
Figma's SVG *import* always flattens to static vector groups — `id`/`class`
survive only as inert names; component identity, variants, props, and slots are
lost. So identity cannot ride *in* through Figma's SVG importer; it has to be
reconstructed by driving Figma via its **Plugin API / MCP** from a side-car
identity manifest — which is exactly what the `design-parity` `figma-plugin`
already does (native component sets + provenance stamps), *not* via SVG import.

**Market verdict.** The feature is a union of UXPin's structured canvas + Relay's
Compose-slot mapping + Code Connect's identity link + a novel SVG-identity path.
No incumbent combines them, and the *Compose* end is entirely unserved. Building
fresh is justified; hook into **DTCG tokens** and **Figma Code Connect** at the
edges so output lands in the existing design-system ecosystem instead of a silo.

### 1.3 Strategic risk

"Design tool that emits real components" is a graveyard (Modulz acquired-and-shut;
Relay sunset). The survivors (UXPin, Framer) narrowed scope. The durable design
choice — which this repo already made — is that **identity flows *from* the Compose
code, not from hand-annotation** (Relay's fatal weakness). Keep the catalog as the
single source of truth; never ask a designer to hand-tag what the renderer can
derive.

### 1.4 Surface choice (a / b / c)

Recommendation: **don't build a standalone web drag-drop canvas.** Two reasons:
the assembly canvas already exists — **Figma, via the plugin** (surfaces a/b),
where designers already live — and a bespoke canvas re-scopes into the graveyard
above. `preview.coo.ee` (surface c) earns its keep not as a *canvas* but as the
**persistence + sharing + live-render backend** the plugin composes against, and
as a lightweight read-only viewer of a saved composition. So:

- **Authoring:** the Figma plugin (a/b) — reuse its slot flow + live editor + reconcile.
- **Persistence / reference / SVG-PNG export:** `preview.coo.ee` serves a saved
  **screen-composition document** (§3.2) and renders it as a composed whole.

---

## 2. What already exists (the substrate)

Every row below is a shipped seam. **Paths predate the preview-server split** and
several `cli/.../serve/…` ones moved to
[`compose-preview-server`](https://github.com/yschimke/compose-preview-server) with
it — including the `dp-slot:` reader this table places in
`data/layoutinspector/core`, which is not in this repository. See
[§0.3](#03-half-of-this-lands-in-the-other-repository).

| Capability | Seam |
|---|---|
| **Pick a scaffold** (phone w/ status bar; Wear w/ `TimeText`, page indicator, edge button) | `samples/design-catalog-m3/.../CatalogPreviews.kt` (`AppScaffoldTemplate` inside `FullScreenM3`, renders OS bars via `SystemBarsFrame`/`SYSTEM_BAR_INSET`); `samples/design-catalog-wear-m3/.../CatalogPreviews.kt` (`TimeText`/`PageIndicator`/`EdgeButton` scaffold templates). Registered under a "Scaffold templates" group in `catalog.spec.json`. |
| **Select a theme** | `themeProvider` override (app-declared `@ThemeCatalog`); `tokens.dtcg.json` (W3C DTCG); `figma-variables.json` (light/dark = Figma variable modes). |
| **Customise device dimensions** | `PreviewOverrides`: `device`, `widthPx`/`heightPx`, `fontScale`, `density`, `orientation`, breakpoints (`cli/.../serve/ServeOverrides.kt`). |
| **Slots + placeholders** | `runtimes/slots/.../PreviewSlot.kt` — `PreviewSlot(name){}` → `testTag="dp-slot:<name>"`; `LocalSlotMode`/`slotMode=true` draws labelled empty placeholders. `data/layoutinspector/core/.../PreviewSlots.kt` → `/render/<id>.slots` returns `{previewId, slots:[{name, bounds}]}`. |
| **Customise content per component** | `compose/overrides`: `PreviewOverrideDeclaration{key,type(string/int/float/bool/color),default,current,index}` → `previews/<id>.overrides.json` (`data/preview-overrides/core/.../PreviewOverrideModels.kt`). The property panel is already modelled. |
| **Remember each component as data (not opaque SVG/PNG)** | (1) the enumerable override knobs above; (2) **RemoteCompose** `RemoteDocument` byte stream `ir/<id>.rc` — code-free, replayable, reseedable via `namedValues` (`data/remotecompose/core/.../RemoteComposeModels.kt`, `compose/remotecompose`). |
| **Refresh as code is fixed** | daemon live re-render + WebSocket stream; figma-plugin **Refresh selected** rebuilds `/render/<id>` from provenance stamps; RemoteCompose reseeds `namedValues` *without rebuilding the document*. |
| **Replace a component** | figma-plugin provenance stamps (`RenderSource`) + non-destructive **reconcile** (match by `componentId`, not position). |
| **Variants synced to one structure** | native Figma **component sets** (`state=…, theme=…, size=…`) + **variable collections**; reconcile refreshes each variant in place from the code render. |
| **Export to Figma, components identified** | `compose/figma-svg` (`data/layoutinspector/core/.../FigmaLayeredSvg.kt`): every composable → a named `<g id="…">`, nested per the composable tree, with real fills/strokes/corner-radii/**editable text** + `data-token` variable bindings — not a flattened screenshot. |
| **In-Figma builder (a/b)** | `design-parity` `packages/figma-plugin/` — single-component picker, whole-catalog import, **`Place with slots`** flow, **live override editor + Refresh**, **spec → GitHub issue**. |
| **preview.coo.ee (c)** | `compose-preview serve --public` (`cli/.../serve/ServeWeb.kt`): upload bundle → `?session=<name>` shareable link; catalogs served read-only; live customisable renders; catalog SVG export; `livePreview` deep links in `catalog.json`. |
| **Catalog format** | `@design-parity/catalog-export` → `catalog.json` (schema `design-parity-catalog/v1`): components, variants (`ideal`/`layout`), tokens, greenlines/redlines, wireframe SVGs, a **`screens[]` graph**, per-image `livePreview` deep links. |
| **Convert to Compose code with real slots** | **Only partial** — design→code exists as "propose spec → GitHub issue" (`figma-plugin/src/spec.ts`): a Markdown spec, *not* `Scaffold(topBar=…, content=…)` codegen. |
| **Compile and run generated Compose, live** | `compose-preview-server`'s **playground** — `BtaCompileSession` compiles Kotlin with no Gradle, the daemon renders it headlessly, and the serve `input` protocol streams a clickable composition. Phases 1–3 + the per-session sandbox are shipped ([PLAYGROUND.md](PLAYGROUND.md)). This is the far side of codegen, and it already exists. |

### 2.1 The existing in-Figma slot flow (what a builder inherits)

`figma-plugin` already implements the assemble loop, on the Figma canvas:

1. Place a container render (`src/scene.ts`, `src/insert.ts`).
2. Fetch `/render/<id>.slots` from a `compose-preview serve` host (`src/slots.ts`).
3. `placeSlots` stamps each declared slot region as an empty frame at the slot's
   bounds (`src/structure.ts`).
4. Per-slot picker + **Fill** → `fillSlot` renders the chosen child at the slot's
   exact box (`?widthPx&heightPx`) and drops it in.
5. **Live override editor** (`src/editor.ts`, `src/previews.ts`, `src/live.ts`) —
   edit declared knobs + display axes; **Refresh selected** (`planRefresh`)
   re-renders selected nodes against current code.
6. **Provenance** (`src/provenance.ts`) stamps each node with its full
   `RenderSource`; **reconcile** (`src/reconcile.ts`) makes re-import
   non-destructive; **`design-map.json`** (`src/designMap.ts`) records the
   code↔Figma-node correspondence.

The builder described in the request *is this flow*, made scaffold-first and
persistent.

---

## 3. Design — the four gaps

### 3.1 Gap 1 — scaffolds weren't slot-fillable — **done**

`AppScaffoldTemplate` (M3: `topBar`, `fab`, `content`) and
`TimeTextScaffoldTemplate` (Wear: `header`) now wrap their fillable regions in
`PreviewSlot`, so they surface through `/render/<id>.slots` as drop targets with
measured boxes and swap to labelled placeholders under `slotMode`. The knobs
stayed: a slot's default child is a live example, not an either/or with being
editable.

Two things the doing of it taught, which the sketch below did not anticipate:

* **A `Scaffold` slot lambda has no layout-scope receiver**, so the scope has to
  be declared explicitly (`PreviewSlotScope.Box` for `topBar`/`fab`,
  `PreviewSlotScope.Column` for `content`) rather than inferred by the
  scope-receiver overload the runtime prefers. Same for a Wear
  `TransformingLazyColumn` item body, which is not `LazyItemScope`:
  `PreviewSlotScope.Lazy` says so explicitly, which is what tells a builder its
  child lands in a scrolling container.
* **Sizing is per-slot and not obvious.** `topBar` fills horizontally and hugs
  vertically; `fab` hugs both, because filling would stretch it across the screen;
  `content` fills both. Getting this wrong is invisible in a normal render and
  only shows up when something is dropped in.

Remember [§0.1](#01-the-target-is-a-composition-tree-not-a-filled-scaffold): this
gives **fixed named** slots. It is the substrate for the tree, not the tree.

The original sketch, kept for the shape:

```kotlin
// samples/design-catalog-m3/.../CatalogPreviews.kt — AppScaffoldTemplate
topBar = {
  TopAppBar(title = { Text(previewOverrideString("title", …)) })     // knob, not a slot
},
floatingActionButton = {
  FloatingActionButton(onClick = {}) { Text(previewOverrideString("fab", "+")) }
},
```

**Change:** wrap the scaffold's fillable regions in `PreviewSlot(name){}` so they
surface through `/render/<id>.slots` and become drop targets in the existing
plugin flow:

```kotlin
topBar = {
  PreviewSlot("topBar", Modifier.fillMaxWidth()) {
    TopAppBar(title = { Text(previewOverrideString("title", …)) })   // default fill, still knob-editable
  }
},
floatingActionButton = {
  PreviewSlot("fab") { FloatingActionButton(onClick = {}) { Text(previewOverrideString("fab", "+")) } }
},
content = { padding ->
  PreviewSlot("content", Modifier.padding(padding).fillMaxSize()) { /* default list */ }
},
```

A slot with a default child is both a **placeholder** (empty under `slotMode`) and
a **live example** (renders its default otherwise) — no either/or. Mechanical, no
harness change, and it turns the plugin's slot flow into the "pick a scaffold →
fill its slots" experience.

### 3.2 Gap 2 — no persisted screen-composition document (the real new build; surface c)

Today an assembled screen lives only as Figma-canvas provenance stamps. There is
no server-side artifact that says *"scaffold X + slot A ← component P{overrides} +
slot B ← component Q{…}"* as one referenceable, re-renderable unit.

**Proposal:** a `screen.json` — a small, self-contained composition document,
modelled as an extension of `catalog.json`'s existing `screens[]` graph. Draft
shape (schema `design-parity-screen/v1`):

```jsonc
{
  "schema": "design-parity-screen/v1",
  "id": "messages-home",
  "title": "Messages — home",
  "system": "compose-m3",              // which catalog / design system
  "scaffold": {
    "previewId": "AppScaffoldTemplate",
    "overrides": { "device": "pixel_7", "fontScale": 1.0, "themeProvider": "…", "title": "Inbox" }
  },
  "slots": [
    { "name": "topBar",  "fill": { "previewId": "SearchBar",    "overrides": { "label": "Search mail" } } },
    { "name": "content", "fill": { "previewId": "MessageList",  "overrides": { "sender[0]": "Ada", "preview[0]": "Re: lunch" } } },
    { "name": "fab",     "fill": null }                          // left as placeholder
  ],
  "variants": ["light", "dark"],       // rendered from the SAME structure, kept in sync
  "provenance": { "serverBase": "https://preview.coo.ee", "generatedAt": "…" }
}
```

Properties that matter:

- **Not flattened.** Every slot references a `previewId` + its `overrides` bag, so
  the composition remembers *which component* and *which customisations* — each is
  independently refreshable/replaceable.
- **Variants are projections of one structure.** `light`/`dark` (or size) render
  the same `screen.json`; they can't drift because there is one source.
- **`preview.coo.ee` renders it as a composed whole.** New serve route
  `POST /screens/<name>` (mirrors the existing `POST /bundles/<name>`) saves it;
  `GET /screens/<name>` returns a live, customisable composed render + a
  `?session=` share link. The daemon composes the scaffold, fills each slot's
  child at its `bounds`, applies overrides — reusing `ServeCatalogLiveHost` /
  `ServePerPreviewLiveHost` machinery, not a new renderer.
- **RemoteCompose is the "flatten to one replayable doc" companion.** When a
  designer wants the whole composed screen as a single portable, code-free
  artifact (for embedding, or a stable reference render), emit an `ir/<id>.rc`
  of the composed result — replayable with no source code.
- **The figma-plugin consumes `screen.json` directly**: it already has
  `placeSlots` + `fillSlot`; feeding it a saved composition just replays the fills
  instead of re-picking them by hand.

### 3.3 Gap 3 — figma-svg layers are named by composable *type*, not per-instance

`compose/figma-svg` emits `<g id="Button">` — two buttons produce two identical
ids; identity is positional. The stable `ComposeSemanticsNode.ref` (assigned by
`SemanticsRefs`, survives content edits) exists on the node but isn't threaded onto
the SVG group.

**Change:** in `FigmaLayeredSvg`, stamp `data-ref` (and `data-node-id`) onto each
`<g>` from the paired semantics node. Then "this exact layer = this exact placed
instance" survives re-export and reconcile — the figma-plugin can match a filled
slot's child to its Figma node losslessly instead of by tree position. All the
identity data already exists; it's a threading change in one emitter. (Related:
issue #2357 already tracks figma-svg fidelity gaps.)

### 3.4 Gap 4 — real Compose codegen from a composition (**the bridge**, not the dream)

The inverse (Figma → spec → GitHub issue) exists; the ingredients for the forward
path (slot names, override values, component IDs, Code Connect) all exist. A
`screen.json` → Compose generator is a well-defined transform on top of Gap 2:

```kotlin
// generated from messages-home/screen.json
@Composable
fun MessagesHome() = AppScaffoldTemplate(
  topBar = { SearchBar(label = "Search mail") },
  content = { MessageList(/* seeded rows */) },
  // fab slot left empty
)
```

This requires the scaffold templates to be authored as **real slot-parameter
composables** (`AppScaffoldTemplate(topBar = …, content = …, fab = …)`), which
Gap 1 already nudges toward. Emitting **Figma Code Connect** entries for each
catalog composable makes the generated screens light up in Figma Dev Mode + the
Figma MCP server — real Compose, mapped to the assembled nodes.

---

## 4. Phased plan

**Revised** — see [§0](#0-what-changed-since-the-proposal). Codegen moved from
last to second, because the surface that *runs* generated code already exists, and
phase 1 is done.

**Two builders now exist, and combining them is designed** in
[UI_BUILDER_COMBINED.md](UI_BUILDER_COMBINED.md): the `design-parity` Figma plugin
and the wasm builder edit the same `Screen` document, and the preview pane is a
two-implementation seam because Android and Wear cannot compose in the browser.
That document also carries the two open questions blocking the projection.

**Phase 2 has started**, and phase 3 with it: `:screen-model` (the composition
document, its edits and its codegen) and `ScreenBuilderApp` (the browser builder
over the M3 catalog) are in. The two things they are missing — syntax highlighting
and a real compile check — are specced ready-to-start in
[UI_BUILDER_LIVE_CODE.md](UI_BUILDER_LIVE_CODE.md), including the shipped
`/api/{version}/compiler/run` contract the compile check rides.

| Phase | Scope | Effort | Unlocks |
|---|---|---|---|
| ~~**1**~~ | ~~`PreviewSlot`-ify the scaffold templates.~~ **Done** ([§3.1](#31-gap-1--scaffolds-werent-slot-fillable--done)). | — | "Pick a scaffold, fill its slots" through the existing plugin flow. Fixed named slots only. |
| **2** | `screen.json` — a tree of `{componentId, variant, knobs{}, children[]}` — plus `POST/GET /screens/<name>`. Mostly [`compose-preview-server`](https://github.com/yschimke/compose-preview-server)'s work ([§0.3](#03-half-of-this-lands-in-the-other-repository)). | 1–2 wks | Compositions persist, are shared, referenced, rendered whole, inserted into Figma. The document everything else reads. |
| **3** | `screen.json` → **Compose source** → the playground's `BtaCompileSession` → live interactive preview. | 1–2 wks | The loop in [§0.1](#01-the-target-is-a-composition-tree-not-a-filled-scaffold), end to end. Was phase 4; it is the bridge, and the far side of it is already built. |
| **4** | Stamp stable per-instance `ref` onto figma-svg groups (Gap 3). ~~Code Connect~~ **done** — the catalog export writes `code-connect.json` per component ([scripts/design-artifacts/docs/code-connect.md](../../scripts/design-artifacts/docs/code-connect.md)); `publish-code-connect.mjs` resolves node ids + emits the `send_code_connect_mappings` payload. | ~1 wk | Lossless composition ↔ Figma-node round-trip; screens surface in Dev Mode / MCP. |

Phase 3 does not need phase 4: generated Kotlin is the interchange format, so the
loop closes without any Figma-node identity at all. Phase 4 is what makes the
round-trip *back* lossless.

**Interop, not reinvention, at the edges:** keep emitting **DTCG tokens**
(Supernova / Tokens Studio / Style Dictionary consume them) and adopt **Figma Code
Connect** (MIT) as the code↔node link — so assembled screens land in the existing
ecosystem rather than a proprietary silo.

**Wire it into the preview pipeline (repo convention):** a saved `screen.json`
should itself become a rendered, diff-on-PR surface — register the composed render
with the preview-harness so every catalog/renderer change re-renders and diffs the
example compositions automatically, the same way per-component stickers are
diffed today.

---

## 5. Open questions

- **Slot defaults vs. placeholders.** A `PreviewSlot` with a default child renders
  the default when not overridden and an empty placeholder under `slotMode`. Is
  that the right dual behaviour, or should scaffolds ship *two* variants (populated
  demo + empty skeleton)? Leaning: one slot, dual behaviour (§3.1).
- **Where does `screen.json` live? — RESOLVED (2026-09).** Three tiers, not one:
  1. **Server-only artifact** for a scratch composition — uploaded at runtime,
     never committed. The default, and what most compositions are.
  2. **Committed exemplars** in the catalog module, which the preview-harness
     renders and diffs on every PR, so a renderer or catalog change that breaks a
     composed screen is caught the same way a broken sticker is.
  3. **On the `design-artifacts/<system>` delivery branch** for a screen that
     should ship *editable* — i.e. one a consumer of the published catalog is
     meant to open and change, rather than only look at.
- **Nested slots.** Cards already have slots; a card dropped into a scaffold slot
  yields nested `dp-slot:` regions. Does the plugin recurse, or is one level enough
  for v1? Leaning: one level for v1, recurse later.
- **RemoteCompose dependency.** Using `.rc` as the "whole composed screen as one
  data document" inherits an *alpha* androidx runtime (`compileSdk 37`, opaque
  player canvas, single colour mode). Acceptable for an opt-in export; not the
  default persistence format (that's `screen.json`).

## 6. Related issues

- #2137 — generic interactive preview browser (Showkase-equivalent) — **closed as
  completed**; a composition browser is a sibling surface.
- #2357 — figma-svg export fidelity (shadow elevation); Gap 3 rides alongside.
- #2358 — props-only catalog variants not yet supported by catalog-export.
- #2365 — live-stream knob edits + catalog SVG export — **closed as completed**;
  the live-fill path depends on knob overrides reaching the running preview, which
  is the [§0.1](#01-the-target-is-a-composition-tree-not-a-filled-scaffold)
  per-instance editing story.
