# Spatial semantics tree (3D-over-2D)

> Status: **design / target architecture.** The leaf renderer (2D per-panel wireframe) and the 3D
> spatial scene both exist today (see "What exists"); this doc specifies the unified tree they
> converge on and the producer/viewer work to get there. Cross-links:
> [`SPATIAL_SCENE_CONTRACT.md`](SPATIAL_SCENE_CONTRACT.md) (the 3D wire format),
> [`XR_SPATIAL_PREVIEW.md`](XR_SPATIAL_PREVIEW.md) (how 3D poses are recovered offline),
> [`DATA-PRODUCTS.md`](../daemon/DATA-PRODUCTS.md) (`compose/semantics` + `compose/semantics-wireframe`).

## The shape

The end result is **one tree of semantics whose top levels are 3D and whose every panel carries a
normal 2D semantics tree**:

```
SpatialSemanticsNode                      ← 3D (subspace layout)
├─ pose: { translation, rotation }        ← androidx.xr SubspaceSemanticsInfo.poseInRoot (dp)
├─ sizeDp: 3D extent                       ← .size
├─ kind: "subspaceRoot" | "row" | "column" | "box" | "panel"
├─ children: [SpatialSemanticsNode, …]    ← the 3D subspace hierarchy
└─ panelContent: ComposeSemanticsNode?    ← 2D semantics tree of the panel's hosted content
                                            (only on `kind = "panel"` leaves)
```

- **Top levels = 3D.** A `Subspace { SpatialColumn { SpatialPanel … } }` becomes a
  `SpatialSemanticsNode` hierarchy: each node has a 3D `poseInRoot` + `sizeDp`. This is precisely
  what [`SubspaceSceneRecorder`](../../renderers/xr/src/main/kotlin/ee/schimke/composeai/renderer/xr/SubspaceSceneRecorder.kt)
  already recovers offline (no headset / OpenXR — see `XR_SPATIAL_PREVIEW.md`).
- **A normal semantics tree per panel.** Each `SpatialPanel` hosts ordinary 2D Compose content with
  its own `SemanticsOwner`. Its root `SemanticsNode` projects to a `ComposeSemanticsNode` via
  [`ComposeSemanticsDataProducer.buildPayload`](../../data/layoutinspector/connector/src/main/kotlin/ee/schimke/composeai/daemon/ComposeSemanticsDataProduct.kt)
  — the same 2D tree the `compose/semantics` product and the wireframe already use.

### The degenerate (non-XR) case

**An ordinary preview is a single-panel spatial tree.** A plain `@Preview` has no subspace, so its
spatial tree is one `panel` node at the origin (identity pose) whose `panelContent` is the entire 2D
semantics tree. This is why the 2D wireframe work is not throwaway: the per-panel wireframe is the
**leaf renderer** for every preview, XR or not — XR previews just have more than one panel, arranged
in 3D.

```jsonc
// Ordinary preview — one panel, identity pose, the whole 2D tree inside.
{
  "version": 1,
  "previewId": "NowPlayingCardPreview",
  "root": {
    "id": "root", "kind": "panel",
    "poseInRoot": { "translation": {"x":0,"y":0,"z":0}, "rotation": {"x":0,"y":0,"z":0,"w":1} },
    "sizeDp": { "width": 360, "height": 640, "depth": 0 },
    "panelContent": { "nodeId": "1", "boundsInRoot": "0,0,360,640", "children": [ /* … */ ] }
  }
}
```

```jsonc
// XR preview — a SpatialColumn of two panels, each with its own 2D tree.
{
  "version": 1,
  "previewId": "NowPlayingSpatialPreview",
  "root": {
    "id": "column", "kind": "column",
    "poseInRoot": { "translation": {"x":0,"y":0,"z":0}, "rotation": {"x":0,"y":0,"z":0,"w":1} },
    "sizeDp": { "width": 560, "height": 360, "depth": 0 },
    "children": [
      { "id": "now-playing", "kind": "panel",
        "poseInRoot": { "translation": {"x":0,"y":80,"z":0}, "rotation": {"x":0,"y":0,"z":0,"w":1} },
        "sizeDp": { "width": 560, "height": 200, "depth": 0 },
        "panelContent": { "nodeId": "…", "boundsInRoot": "0,0,560,200", "children": [ /* … */ ] } },
      { "id": "transport", "kind": "panel",
        "poseInRoot": { "translation": {"x":0,"y":-100,"z":0}, "rotation": {"x":0,"y":0,"z":0,"w":1} },
        "sizeDp": { "width": 560, "height": 96, "depth": 0 },
        "panelContent": { "nodeId": "…", "boundsInRoot": "0,0,560,96", "children": [ /* … */ ] } }
    ]
  }
}
```

The 3D primitives (`Vec3`, `Quat`, `SpatialPose`, `SizeDp`) are the **same units and axes** as
`SpatialScene` (dp; right-handed +x right / +y up / +z toward viewer; identity quaternion). The only
additions are the `depth` on `SizeDp` (panels are flat → 0; a `SpatialBox` can have depth) and the
recursive `children` + `panelContent`.

## What exists today

| Piece | Status | Where |
| --- | --- | --- |
| 3D panel poses/sizes recovered offline | ✅ | `SubspaceSceneRecorder` → `SpatialScene` |
| 3D viewer (WebGL) + headless compositor | ✅ | `<spatial-view>`, `renderers/xr-composite` |
| 2D semantics tree (`compose/semantics`) | ✅ Android producer | `ComposeSemanticsDataProducer` |
| 2D wireframe renderer (SVG + PNG) | ✅ both backends | `SemanticsWireframeSvg`, `*SemanticsWireframe` bakers, `compose/semantics-wireframe` data product |
| **Unified `SpatialSemanticsTree`** | ▶ this doc | — |
| Per-panel 2D semantics harvested into the tree | ▶ todo | extend `SubspaceSceneRecorder` |
| Viewer: panel face = 2D wireframe, drill-in | ▶ todo | `<spatial-view>` |

## Producer plan

1. **Tree DTO.** Add `SpatialSemanticsTree` / `SpatialSemanticsNode` as a `@Serializable` wire DTO
   (Kotlin in `:preview-data-api` beside `SpatialScene`, TS mirror beside `spatialScene.ts`), with a
   committed fixture + round-trip test locking the two languages — same discipline as `SpatialScene`.
2. **Harvest per-panel 2D semantics.** `SubspaceSceneRecorder` already recovers each panel's live
   content `View` (`contentView`, for the texture pass). That `View`'s `SemanticsOwner` root →
   `ComposeSemanticsDataProducer.buildPayload` gives the panel's `ComposeSemanticsNode`. Attach it as
   `panelContent`. The 3D hierarchy (row/column/box → panel) comes from the subspace node tree the
   recorder already walks.
3. **Degenerate case.** For a non-XR render, wrap the existing single semantics root in one `panel`
   node at identity pose — a tiny adapter in the render engines (Android post-capture / desktop
   `RenderEngine`), reusing the captured root they already hand the wireframe extension.
4. **Data product.** Emit it as `compose/spatial-semantics` (JSON, path transport) alongside the
   per-panel wireframes, so an agent gets the whole 3D-over-2D structure in one fetch.

## Viewer plan

The existing 3D `<spatial-view>` already places a textured quad per panel at its `poseInRoot`. The
unified view swaps each panel's **texture from the rendered screenshot to its 2D wireframe** (the
per-panel `compose-semantics-wireframe.svg`/`.png`), and a click on a panel drills into that panel's
2D semantics tree (the existing inspection tree-table, scoped to `panelContent`). One navigable
structure: orbit the 3D layout, drill a panel, read its 2D semantics — exactly "a tree of semantics,
3D at the top, a normal semantics tree per panel."

This supersedes a flat 2D-only "wireframe chip": the wireframe is the panel **face** inside the 3D
viewer, not a separate toggle.

## Open questions

- **`SizeDp.depth`** — add now (forward-compatible for `SpatialBox`) vs. keep panels 2D-only until a
  depth-bearing case lands. Leaning: add it, default 0.
- **Where `panelContent` is the *unmerged* vs merged tree** — match `compose/semantics` (unmerged,
  `onRoot(useUnmergedTree = true)`) for consistency.
- **Orbiters / spatial affordances** (`Orbiter`, `SpatialElevation`) — model as `kind = "orbiter"`
  panel nodes, or keep the `SpatialScene.orbiters` split? Leaning: fold into the node tree with a
  `kind`, since they too host 2D content with a 2D semantics tree.
