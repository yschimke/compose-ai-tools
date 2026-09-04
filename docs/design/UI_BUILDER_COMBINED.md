# Combining the two builders — one document, two editors, two preview hosts

Status: **corrected** (2026-09). The first draft of this document described
combining the Figma plugin with the wasm builder's `:screen-model`. That framing
was wrong, and the correction is the most useful thing here: **a more capable
screen document and generator already exist on `main`**, and `:screen-model`
duplicates them. See [§0](#0-correction-screen-model-duplicates-screengenerator)
first.

Parents: [UI_BUILDER.md](UI_BUILDER.md) (the plan), [PLAYGROUND.md](PLAYGROUND.md)
(the compile-and-stream pipeline this leans on),
[UI_BUILDER_LIVE_CODE.md](UI_BUILDER_LIVE_CODE.md) (highlighting + compile check).

---

## 0. Correction: `:screen-model` duplicates `ScreenGenerator`

Written after finding it, not before building it. `:screen-model` was added in
[#5109](https://github.com/yschimke/compose-ai-tools/pull/5109) without checking
what discovery already had. It should have been checked: the branch list is full
of `agent/screen-state`, `agent/screen-value-vocabulary`, `agent/components-json`,
`agent/component-call-sites` — the work that built the thing it duplicates.

What is already on `main`:

| | Lines | What it is |
|---|---|---|
| `discovery/ScreenGenerator.kt` | 1159 | Emits a complete Kotlin file — package, imports, opt-ins — or refuses with every reason |
| `discovery/ScreenGeneratorTest.kt` | 888 | Its tests |
| `discovery/ComponentRecord.kt` | 276 | The **discovered** component record: real call sites, types, nullability, opt-ins |
| `discovery/ScreenDocument.kt` | — | `ScreenDocument` / `ScreenNode` / `ScreenValue` / `ScreenState` / `ScreenAction` |
| `mcp/UiBuilderMcpAdapter.kt`, `UiBuilderStreamableHttp.kt` | — | The builder as an MCP surface |
| server `uibuilder.protocol` / `uibuilder.service`, `ScreenGeneratorComposeExportExecutor`, `UiBuilderGeneratedPreviewAdapter` | — | Export, compile and render lanes |

And `discovery.ScreenNode` is a strict superset of `screen.ScreenNode`:

| `discovery.ScreenNode` | `screen.ScreenNode` (mine) |
|---|---|
| `arguments: Map<String, ScreenValue>` — typed, with a `Reference`/`Construct`/`Chain` vocabulary and claimed types checked against the parameter | `knobs: Map<String, String>` — strings |
| `slots: Map<String, List<ScreenNode>>` — named slots, many children each | `children` + one `slot` string |
| `handlers: Map<String, List<ScreenAction>>` — event handlers by source parameter name | — |
| `ScreenDocument.state: List<ScreenState>` — `mutableStateOf` declarations | — |

The difference that matters most is not the shape. `ScreenGenerator` writes call
sites against **discovered component records**; `ScreenCodegen` writes them against
a **hand-maintained `ComponentSpec` table** with nine entries. The hand table is
the exact drift `ComponentRecord` exists to prevent. `ScreenGenerator` also has an
`expressionPackages` allow-list so a document cannot make it generate
`Files.readString(Path.of("/etc/passwd"))` — a hazard `ScreenCodegen` does not
consider at all, and which matters the moment generated code is compiled and run.

**Recommendation: retire `ScreenCodegen`, `ComponentSpec` and `catalogComponentSpecs`,
and re-point the builder at `ScreenDocument` + `ScreenGenerator`.** Do not publish
`:screen-model`.

**What is worth keeping**, because `ScreenGenerator` does not do it — it generates
source, it does not render:

* `CatalogScreen` — rendering a document live **in the browser**, with per-instance
  knobs resolved through `LocalCatalogInstance`.
* `ScreenBuilderApp` and `ScreenPreviewHost` — the browser editor and the seam
  below.

Those want re-pointing at `ScreenDocument` rather than deleting. The rest of this
document was written under the wrong premise; what survives it is the Figma
projection (below), which is unaffected because it targets a *document*, and the
preview-host seam, which is about rendering rather than generation.

---

## The problem

There are **two** assemble surfaces, and neither is wrong.

* **The Figma plugin** (`yschimke/design-parity`, `packages/figma-plugin/`) —
  place a container, stamp its declared slots, pick-and-fill each one, edit knobs
  live, refresh against current code, reconcile non-destructively. It works on a
  real canvas with identity tracking, and it is the more capable *tool*.
* **The wasm builder** (`ScreenBuilderApp`, this repo) — assemble an arbitrary
  **tree**, edit per-instance knobs, and **generate Kotlin**. Cruder as a UI; it
  does the two things the plugin does not.

Duplicating the overlap indefinitely is the bad outcome. The good one is that they
edit **the same document**.

## The document is already almost shared

Every node the plugin puts on the canvas carries, in shared plugin data, what a
`ScreenNode` needs. From the source, not the summary:

| Figma node | `ScreenNode` | Where |
|---|---|---|
| `readRenderSource(node).previewId` | `componentId` | `provenance.ts` |
| `.overrides` — *"Override key → value (fixed keys + `knob.<k>`)"* | `knobs` | `render.ts` |
| `slotName(frame)`, stamped by `placeSlots` | `slot` | `structure.ts` |
| `slotFilledWith` / `slotContainer` nesting | `children` | `structure.ts` |

The knob halves already speak one language: `RenderSourceOptions.knobEdits` is
`Record<string, string>` **"keyed by `seedKey`"**, and `Screen.knobSeeds()` emits
the same `key` / `key[index]` scheme. Both descend from
`PreviewOverrideHost.seedKey`. That is not a coincidence to be preserved by
discipline — it is the reason a projection is possible at all.

`ScreenNode.slot` was written before any of this was read, on a guess about what a
scaffold needs. It matches what `placeSlots` actually stamps.

## Two preview hosts, because Android cannot compose in the browser

The wasm pane can only compose catalogs that reach `wasmJs` — M3, not Wear
(`androidx.wear.compose` is Android-only). Reaching an **Android** catalog means
the composition runs on the Robolectric daemon and the browser shows **streamed
frames**, sending pointer events back — which `wear-m3` already does live on
`preview.coo.ee`.

So the preview pane is an interface with two implementations, both taking the same
`Screen`:

```kotlin
interface ScreenPreviewHost {
  @Composable fun Preview(screen: Screen, modifier: Modifier)
  val label: String
}
```

* `WasmCatalogPreviewHost` — in-process, M3, no server, an edit is a
  recomposition. The default, and it must keep working with no network.
* A streamed host — any catalog including Android and Wear, over a serve host.

**This seam is in place now** (`ScreenPreviewHost.kt`), deliberately ahead of the
streaming implementation: hard-wiring `CatalogScreen` would have made the
browser-only path structural, and unpicking it later means touching all of the
builder rather than one binding.

## How a streamed host reaches an assembled screen

An assembled screen has **no discovered `@Preview`** for a daemon to render. There
is nothing to point `renderNow` at. So streaming one means it must first become
code:

```
Screen ──> ScreenCodegen ──> Kotlin ──> BtaCompileSession ──> streamed frames
```

That is the playground pipeline, already shipped ([PLAYGROUND.md](PLAYGROUND.md)),
and it is the **only** path that reaches Android and Wear. It also means the two
things this document is about — combining the editors, and streaming the preview —
resolve to the same requirement: **codegen has to be reachable as a service.**

### Consequences, stated plainly

1. **Codegen runs server-side — and already does.** `ScreenGeneratorComposeExportExecutor`
   generates from the component record, and `UiBuilderGeneratedPreviewAdapter`
   submits the result to `PlaygroundCompileService`, returning the same
   compiled-snippet token that enters the first-frame and redeem paths. It is an
   **internal trusted-source seam**, not a public arbitrary-Kotlin endpoint: its
   caller must snapshot and authorize the export before setting `isSecurityChecked`.
   The first draft listed this as work to do. It is done.
2. ~~**`:screen-model` gets published.**~~ It should be **retired** instead —
   see [§0](#0-correction-screen-model-duplicates-screengenerator).
3. **Three repositories.** The model here, the codegen service and streaming in
   `compose-preview-server`, the canvas editor in `design-parity`. That is the real
   cost of combining, and it is why the document has to be the contract.

## Open — and these are design, not plumbing

### 1. `previewId` is not `componentId`

Three spellings of one thing, in three places:

* the plugin stamps a **preview id**: `button-filled__ideal__default__dark`
* `catalogComponentSpecs` and `CatalogScreen` are keyed on a **catalog id**:
  `button-filled`
* `designMap.ts` notes a catalog carries a **component id** like `"Button/Filled"`

Projecting a canvas into a `Screen` needs this resolved. Guessing it would
silently mis-key knobs — the seed key would land on a component that isn't there,
the value would vanish, and it would read as a knob bug. The preview id also
encodes variant and mode (`__ideal__default__dark`), which is information a
`ScreenNode` has nowhere to put (see 2).

**Do not start the projection before this is settled.** It is the load-bearing
mapping and everything downstream inherits its mistakes.

### 2. `ScreenNode` has no variant

"Select the style" is variant selection, and `@CatalogVariant` / `@OverrideVariant`
cells already exist — but the document has no field for one, so a screen cannot
say *which* variant of a component an instance is. The preview id above carries
exactly that, which is a hint the two questions are one question.

## What this does to the wasm builder

Honestly: if the Figma canvas becomes the editor, `ScreenBuilderApp` stops being
the product and becomes two useful lesser things — a **no-server fallback** (it is
the only path that works with no host at all) and a **test harness** for the
document and codegen without a canvas in the loop. That is a fair outcome for it.
It should not be defended past its usefulness.
