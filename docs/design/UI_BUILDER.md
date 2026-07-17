# UI builder — assembling screens from the component catalog

Status: **design proposal** (2026-07). Product analysis + architecture for a
WYSIWYG "assemble a screen from catalog components" surface, built on the seams
this repo already ships. No code yet; this doc scopes what exists, what's
missing, and a phased plan.

> **Thesis.** ~70% of a catalog-driven UI builder is already built and running
> across `compose-ai-tools` and the `design-parity` `figma-plugin`. The Compose
> code is already the source of truth, and `compose/figma-svg` already exposes
> each component to Figma *identified* (a named layer per composable), not as a
> flattened screenshot. The work is to (1) make **scaffolds slot-fillable**, (2)
> add a **persisted screen-composition document**, (3) give each exported layer a
> **stable per-instance identity**, and (4) eventually **generate real Compose**
> from a composition. Everything else is wiring seams that already exist.

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

Every row below is a shipped seam. Paths are repo-relative.

| Capability | Seam |
|---|---|
| **Pick a scaffold** (phone w/ status bar; Wear w/ `TimeText`, page indicator, edge button) | `samples/design-catalog-m3/.../CatalogPreviews.kt` (`AppScaffoldTemplate` inside `FullScreenM3`, renders OS bars via `SystemBarsFrame`/`SYSTEM_BAR_INSET`); `samples/design-catalog-wear-m3/.../CatalogPreviews.kt` (`TimeText`/`PageIndicator`/`EdgeButton` scaffold templates). Registered under a "Scaffold templates" group in `catalog.spec.json`. |
| **Select a theme** | `themeProvider` override (app-declared `@ThemeCatalog`); `tokens.dtcg.json` (W3C DTCG); `figma-variables.json` (light/dark = Figma variable modes). |
| **Customise device dimensions** | `PreviewOverrides`: `device`, `widthPx`/`heightPx`, `fontScale`, `density`, `orientation`, breakpoints (`cli/.../serve/ServeOverrides.kt`). |
| **Slots + placeholders** | `runtimes/slots/.../PreviewSlot.kt` — `PreviewSlot(name){}` → `testTag="dp-slot:<name>"`; `LocalSlotMode`/`slotMode=true` draws labelled empty placeholders. `data/layoutinspector/core/.../PreviewSlots.kt` → `/render/<id>.slots` returns `{previewId, slots:[{name, bounds}]}`. |
| **Customise content per component** | `compose/overrides`: `PreviewOverrideDeclaration{key,type(string/int/float/bool/color),default,current,index}` → `previews/<id>.overrides.json` (`data/preview-overrides/core/.../PreviewOverrideModels.kt`). The property panel is already modelled. |
| **Remember each component as data (not opaque SVG/PNG)** | (1) the enumerable override knobs above; (2) **RemoteCompose** `RemoteDocument` byte stream `ir/<id>.rcdoc` — code-free, replayable, reseedable via `namedValues` (`data/remotecompose/core/.../RemoteComposeModels.kt`, `compose/remotecompose`). |
| **Refresh as code is fixed** | daemon live re-render + WebSocket stream; figma-plugin **Refresh selected** rebuilds `/render/<id>` from provenance stamps; RemoteCompose reseeds `namedValues` *without rebuilding the document*. |
| **Replace a component** | figma-plugin provenance stamps (`RenderSource`) + non-destructive **reconcile** (match by `componentId`, not position). |
| **Variants synced to one structure** | native Figma **component sets** (`state=…, theme=…, size=…`) + **variable collections**; reconcile refreshes each variant in place from the code render. |
| **Export to Figma, components identified** | `compose/figma-svg` (`data/layoutinspector/core/.../FigmaLayeredSvg.kt`): every composable → a named `<g id="…">`, nested per the composable tree, with real fills/strokes/corner-radii/**editable text** + `data-token` variable bindings — not a flattened screenshot. |
| **In-Figma builder (a/b)** | `design-parity` `packages/figma-plugin/` — single-component picker, whole-catalog import, **`Place with slots`** flow, **live override editor + Refresh**, **spec → GitHub issue**. |
| **preview.coo.ee (c)** | `compose-preview serve --public` (`cli/.../serve/ServeWeb.kt`): upload bundle → `?session=<name>` shareable link; catalogs served read-only; live customisable renders; catalog SVG export; `livePreview` deep links in `catalog.json`. |
| **Catalog format** | `@design-parity/catalog-export` → `catalog.json` (schema `design-parity-catalog/v1`): components, variants (`ideal`/`layout`), tokens, greenlines/redlines, wireframe SVGs, a **`screens[]` graph**, per-image `livePreview` deep links. |
| **Convert to Compose code with real slots** | **Only partial** — design→code exists as "propose spec → GitHub issue" (`figma-plugin/src/spec.ts`): a Markdown spec, *not* `Scaffold(topBar=…, content=…)` codegen. |

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

### 3.1 Gap 1 — scaffolds aren't slot-fillable yet (highest leverage, smallest change)

The M3/Wear scaffold templates expose their regions as **knob overrides**
(`title`, `fab`, `sender[i]`, `edgeLabel`) — you can retext them but can't "drop a
Chip into the top-bar slot." Cards already use `PreviewSlot`. Example, today:

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
a **live example** (renders its default otherwise) — no either/or. Same for Wear
`timeText` / `edgeButton` / `content`. Mechanical, no harness change, and it turns
the plugin's slot flow into the "pick a scaffold → fill its slots" experience.

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
  artifact (for embedding, or a stable reference render), emit an `ir/<id>.rcdoc`
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

### 3.4 Gap 4 — real Compose codegen from a composition (the dream)

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

| Phase | Scope | Effort | Unlocks |
|---|---|---|---|
| **1** | `PreviewSlot`-ify the scaffold templates (Gap 1). | days | "Pick a scaffold, fill its slots" through the existing plugin flow — no new UI. |
| **2** | `screen.json` schema + `POST/GET /screens/<name>` on `compose-preview serve`; figma-plugin reads a saved composition (Gap 2). | 1–2 wks | Surface (c): compositions are saved, shared, referenced, rendered as a whole, inserted into Figma. Durable beyond one canvas. |
| **3** | Stamp stable `ref` onto figma-svg groups (Gap 3); ~~emit **Code Connect** mappings~~ **done** — the catalog export writes `code-connect.json` per component ([scripts/design-artifacts/docs/code-connect.md](../../scripts/design-artifacts/docs/code-connect.md)); `publish-code-connect.mjs` resolves node ids + emits the `send_code_connect_mappings` payload. | ~1 wk | Lossless composition ↔ Figma-node round-trip; screens surface in Dev Mode / MCP. |
| **4** | `screen.json` → real `Scaffold(...)` Compose codegen (Gap 4). | 2–3 wks | The dream: assembled screen → idiomatic Compose using real slots. |

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
- **Where does `screen.json` live?** A new `design-artifacts/<system>` companion
  file, an uploaded-only server artifact, or committed example compositions in the
  catalog module? Leaning: uploadable at runtime + a few committed exemplars that
  the preview-harness diffs.
- **Nested slots.** Cards already have slots; a card dropped into a scaffold slot
  yields nested `dp-slot:` regions. Does the plugin recurse, or is one level enough
  for v1? Leaning: one level for v1, recurse later.
- **RemoteCompose dependency.** Using `.rcdoc` as the "whole composed screen as one
  data document" inherits an *alpha* androidx runtime (`compileSdk 37`, opaque
  player canvas, single colour mode). Acceptable for an opt-in export; not the
  default persistence format (that's `screen.json`).

## 6. Related issues

- #2137 — generic interactive preview browser (Showkase-equivalent); a composition
  browser is a sibling surface.
- #2357 — figma-svg export fidelity (shadow elevation); Gap 3 rides alongside.
- #2358 — props-only catalog variants not yet supported by catalog-export.
- #2365 — live-stream knob edits + catalog SVG export; the live-fill path depends
  on knob overrides reaching the running preview.
