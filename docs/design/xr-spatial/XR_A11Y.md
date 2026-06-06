# XR accessibility — the two-level model

> Status: **design accepted**, implementation staged. Resolves the `xr/a11y-overlay` /
> `xr/structure` accessibility design the [renderer-service RFC](RENDERER_SERVICE.md) left open
> ("produced when we learn how XR a11y surfaces"). Background on the wire surface:
> [SPATIAL_SCENE_CONTRACT.md](../SPATIAL_SCENE_CONTRACT.md).

XR accessibility is **two levels that compose** — a spatial level *between* panels and a content
level *within* each panel — rather than one flat tree. They map directly onto data we already
produce, so most of level 1 is already shipped and level 2 reuses the existing 2D a11y pipeline.

## Level 1 — spatial structure (between panels)

The 3D scene graph a screen reader navigates to move **between** panels. It is about *where things
are in space*, not their text:

- each panel's **pose** (`poseInRoot`: translation + rotation),
- its **3D size / extent** (`sizeDp`, mapped onto the quad),
- its accessible **name** (`label`),
- **nesting** (`parentId`) and the orbiter affordances + camera.

A TalkBack-style spatial cursor uses this to step panel-to-panel by position (left→right,
near→far, top→bottom) and to announce spatial relationships ("Now Playing panel, above, 80 dp").

**Source — already shipped:** the `xr/structure` data product returns exactly this (the held
`SpatialScene` panel tree + poses + sizes + labels, kept in step with `xr/updatePanels`). Level 1
is therefore essentially **done**; this doc just names it the spatial-a11y layer and fixes the
reading-order convention (see Open questions).

## Level 2 — panel content (within a panel)

Once a panel is focused, accessibility is the **ordinary 2D Compose a11y of that panel's content** —
nothing XR-specific. Each panel hosts a 2D Compose subtree, and its semantics are exactly what the
existing 2D preview path already captures:

- **`a11y/hierarchy`** — the `ComposeSemanticsNode` tree (role, `contentDescription`, state,
  actions, bounds) for the panel's content.
- **`a11y/overlay`** — the rendered overlay PNG for that content.

The only new work is **scoping these per panel**: the XR producer (`:renderer-xr`) already renders
each panel's content to its own texture, so it can capture that subtree's `ComposeSemanticsNode`
the same way the 2D path does and tag it with the panel id.

## Composition — `xr/a11y` and `xr/a11y-overlay`

- **`xr/a11y`** (structure kind, mirrors `a11y/hierarchy`) = the level-1 spatial structure where
  each panel **carries (or references) its level-2 2D semantics tree**, keyed by panel id. A
  consumer walks level 1 to pick a panel by spatial position, then reads level 2 within it.
- **`xr/a11y-overlay`** (overlay kind, mirrors `a11y/overlay`) = each panel's 2D a11y overlay PNG
  composited onto its quad at its 3D pose — the spatial equivalent of the flat overlay, produced by
  the native compositor (it already textures panels at their poses; this swaps the content texture
  for the overlay texture).

```
xr/a11y
└─ panels[]                     ← level 1 (xr/structure: pose, sizeDp, label, parentId)
   └─ a11y: ComposeSemanticsNode tree   ← level 2 (per-panel 2D a11y, == a11y/hierarchy)
```

## Build status / plan

1. **Level 1 — done.** `xr/structure` is the spatial-a11y layer.
2. **Level 2 capture (producer).** `:renderer-xr` captures each panel's `ComposeSemanticsNode`
   subtree (reusing `data/a11y/hierarchy-android`) and attaches it to the panel — the substantive
   remaining work, on the Android/Robolectric producer side.
3. **`xr/a11y` data product (daemon).** A daemon kind that returns level-1 structure + per-panel
   level-2 semantics (level 1 from the held scene; level 2 from the producer's per-panel capture).
4. **`xr/a11y-overlay` (native).** The compositor renders per-panel overlay textures at their poses.

Steps 2–4 are independently shippable; 1 is in `main`.

## Open questions

- **Spatial reading order.** Default cursor traversal between panels — reading-order by `parentId`
  nesting then by pose (y desc, then x asc)? Worth pinning so consumers agree.
- **Overlay transport.** `xr/a11y-overlay` per-panel PNGs as additional textures in the frame
  stream vs. a separate fetch — likely reuse the texture path.
