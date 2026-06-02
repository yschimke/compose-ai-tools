# Spatial view — inline 3D layout viewer

A WebGL (three.js) viewer that renders a `SpatialScene` — a recovered
Compose-XR subspace layout — as flat, unlit **textured quads** floating at
their 3D poses, with an orbit / pan / zoom camera, a ground grid + axes,
panel labels, and click-to-focus. It is the panel's 3D counterpart to the
2D PNG grid, reachable via the 2D ⇄ 3D toggle.

This is an **inline** 3D viewer, not WebXR. VS Code webviews are Chromium
and run WebGL fine, but VS Code ships stock Electron with WebXR disabled
(`checkout_webxr` off — no `navigator.xr`, no headset), so the viewer is an
orbit-camera "magic window" and never touches `navigator.xr`. That is the
right and sufficient surface for a layout preview.

## The contract

The wire format is owned upstream in
[`../shared/spatialScene.ts`](../shared/spatialScene.ts) (prose spec:
[`docs/design/SPATIAL_SCENE_CONTRACT.md`](../../../../docs/design/SPATIAL_SCENE_CONTRACT.md)).
Units are dp; the frame is right-handed (+x right, +y up, +z toward the
viewer), identity rotation faces +z. The producer (`:renderer-xr`, later)
emits exactly this shape; this viewer consumes it and the committed
fixtures, so it ships independently.

## Files

| File | Role |
| --- | --- |
| [`sceneLoader.ts`](sceneLoader.ts) | Consumer-side parsing: validates an untyped payload via the contract's `isSpatialScene` guard + the version check, normalises optional collections, and flattens panels + orbiters into `renderableQuads`. Pure — unit-tested in [`spatialSceneLoader.test.ts`](../../test/spatialSceneLoader.test.ts). |
| [`spatialViewer.ts`](spatialViewer.ts) | `SpatialViewer` — the three.js renderer: `WebGLRenderer` + `OrbitControls`, textured quads at poses, grid/axes, billboarded labels, environment colour, raycast click-to-focus. |
| [`main.ts`](main.ts) | The `<spatial-view>` Lit element (bundle entry). Owns a `SpatialViewer`; emits `panel-focus` on click-to-focus; exposes `focusPanel(id)`. |

three.js is bundled into its own `media/webview/spatial.js` (esbuild entry
in [`esbuild.webview.mjs`](../../../esbuild.webview.mjs)) so the ~560 KB lib
only loads when the 3D view is requested. It's bundled locally — **no CDN** —
to satisfy the webview CSP.

## How it's wired into the panel

The 2D ⇄ 3D toggle lives in
[`../preview/spatialToggle.ts`](../preview/spatialToggle.ts)
(`SpatialToggleController`), constructed in `<preview-app>`'s `firstUpdated`:

1. The host posts `{ command: "setSpatialScene", scene, textureBaseUri }`
   (`PreviewPanel.showSpatialScene`). The producer and the dev
   `composePreview.openSpatialFixture` command both drive this.
2. The controller reveals the toggle button (hidden until a scene arrives,
   so non-XR panels are unchanged).
3. On first switch to 3D it injects `media/webview/spatial.js` with the page
   nonce (`data-spatial-src` / `data-csp-nonce` from `previewPanel.ts`),
   mounts `<spatial-view>`, hides the 2D grid, and hands it the scene.

### Textures and CSP

`texture` references are relative paths. The host resolves them to
webview-resource URIs (`webview.asWebviewUri(sceneDir)`), passed as
`textureBaseUri`; the viewer prepends it. The panel CSP includes
`img-src … ${webview.cspSource}` so those load. Scenes outside the
extension dir (real renderer output under `build/`) will additionally need
their directory added to the webview's `localResourceRoots`.

## Fixtures and dev preview

- **Contract fixture:** [`preview-harness/fixtures/spatial-scene/`](../../../preview-harness/fixtures/spatial-scene) — the recovered `SpatialColumn` (owned by the contract).
- **Rich fixture:** [`spatial-fixtures/spatial-rich/`](../../../spatial-fixtures) — angled panels + orbiters + environment, for exercising pose/orbiter handling. Regenerate with `node spatial-fixtures/spatial-rich.gen.mjs`.
- **In the panel:** run the `Compose Preview: Open 3D Spatial Fixture (dev)` command.
- **Standalone:** `node spatial-fixtures/snapshot.mjs --fixture spatial-rich` (PNG → `spatial-fixtures/out/`), or serve the extension root and open `/spatial-fixtures/`.
- **Panel visual record:** the `spatial-view` preview-harness fixture toggles to 3D; `npm run harness:snapshot -- --fixture spatial-view`.
