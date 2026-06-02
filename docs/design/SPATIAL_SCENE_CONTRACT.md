# SpatialScene contract

The wire format that decouples the **producer** — the offline renderer (`:renderer-xr`, Phase A) that
recovers a Compose-XR subspace layout and renders each panel's 2D content to a PNG — from the
**consumer** — the VS Code webview's WebGL 3D spatial-layout viewer. Both sides build to this shape so
they can be developed in parallel and meet here.

- **TypeScript source of truth:** [`vscode-extension/src/webview/shared/spatialScene.ts`](../../vscode-extension/src/webview/shared/spatialScene.ts)
- **Sample fixture:** [`vscode-extension/preview-harness/fixtures/spatial-scene/`](../../vscode-extension/preview-harness/fixtures/spatial-scene/) (`scene.json` + `top.png` / `bottom.png`)
- **Background:** [`XR_SPATIAL_PREVIEW.md`](XR_SPATIAL_PREVIEW.md) (how poses are recovered offline)

This is a **WebGL** viewer contract (Three.js/Babylon), **not** WebXR — VS Code ships stock Electron
with WebXR disabled, so there is no `navigator.xr` and no immersive session. The viewer is an inline
"magic window" with an orbit camera.

## Conventions

- **Units:** every linear quantity (`translation`, `distance`, `sizeDp`) is **density-independent
  pixels (dp)**. On-device XR poses are in metres; the producer converts to dp (the subspace
  semantics tree already reports dp — a 560 dp-wide panel comes back as `width = 560`).
- **Axes:** right-handed — **+x right, +y up, +z toward the viewer** (camera looks down −z). A panel
  with identity rotation faces +z. (Recovered example: a vertical `SpatialColumn` puts the top panel
  at `y = +80` and the bottom at `y = −100`.)
- **Rotation:** unit quaternion `{ x, y, z, w }`; identity is `{ 0, 0, 0, 1 }`.
- **Textures:** `texture` is a path relative to the scene file's directory (e.g. `top.png`) or an
  absolute URI the consumer resolves to a webview resource. It is the panel's rendered 2D content,
  mapped onto the quad with `sizeDp` as the quad's extent.
- **Versioning:** `version` must equal `SPATIAL_SCENE_VERSION` (currently `1`). Bump it on any
  breaking shape change; additive optional fields don't require a bump.

## Shape

See `spatialScene.ts` for the authoritative types. In brief:

```jsonc
{
    "version": 1,
    "units": "dp",
    "previewId": "…", // optional: the source preview
    "camera": {
        // default view; only "orbit" defined today
        "kind": "orbit",
        "target": { "x": 0, "y": -10, "z": 0 },
        "distance": 1200,
        "yawDeg": 0,
        "pitchDeg": -10,
    },
    "panels": [
        {
            "id": "top", // subspace node testTag / semantics id
            "label": "Now Playing", // optional, for overlays
            "poseInRoot": {
                "translation": { "x": 0, "y": 80, "z": 0 },
                "rotation": { "x": 0, "y": 0, "z": 0, "w": 1 },
            },
            "sizeDp": { "width": 560, "height": 200 },
            "texture": "top.png",
            "parentId": "column", // optional; null/omitted = top-level
        },
    ],
    "orbiters": [], // optional: edge-anchored control strips (same shape + "edge")
    "environment": { "kind": "color", "color": "#101014" }, // optional backdrop
}
```

## Producer mapping (`:renderer-xr`, Phase A)

When the renderer mode is built it must emit exactly this shape:

1. Compose the `Subspace`, recover each tagged node's `poseInRoot` (`androidx.xr.runtime.math.Pose`)
   and `size` (`androidx.xr.compose.unit.IntVolumeSize`) from the spatial-semantics tree (the
   technique proven by `SubspaceLayoutPoseTest`).
2. Render each panel's 2D content to a PNG (the existing capture path), one `texture` per panel.
3. Map androidx `Pose` → this contract's frame (document the exact axis/handedness transform in the
   renderer when written; the recovered data is already +y-up dp, so it's expected to be close to
   identity) and stamp `version = SPATIAL_SCENE_VERSION`.
4. Write `scene.json` next to the panel PNGs under the render output dir.

A `@Serializable` Kotlin mirror of these types lives in
[`:preview-data-api`](../../api/preview-data-api/src/main/kotlin/ee/schimke/composeai/xr/SpatialScene.kt)
(`ee.schimke.composeai.xr.SpatialScene`) — the wire-DTO module the renderer and other tooling
already build on. Its field names and JSON shape are identical to `spatialScene.ts`, and
`SpatialSceneTest` deserializes the committed fixture to keep the two languages locked; change one
side and the other must follow.

## For the webview agent

Build the viewer against this contract **and the committed fixture** — load `scene.json`, resolve the
relative texture paths to webview resources, place a textured quad per panel at its `poseInRoot` with
extent `sizeDp`, and drive an orbit camera from `camera`. You do **not** need `:renderer-xr` to exist;
it will emit this same JSON later. Flag any shape change back through this doc + `spatialScene.ts` so
the producer stays in sync.

### Consumer (implemented)

The WebGL viewer now lives in
[`vscode-extension/src/webview/spatial/`](../../vscode-extension/src/webview/spatial/README.md) — a
three.js `<spatial-view>` element (textured quads, orbit/pan/zoom, grid + axes, labels,
click-to-focus) bundled separately as `media/webview/spatial.js`, mounted behind the panel's 2D ⇄ 3D
toggle (`SpatialToggleController`). The host hands it a scene via the `setSpatialScene`
`ExtensionToWebview` message (`PreviewPanel.showSpatialScene`), with a `textureBaseUri` resolving the
relative `texture` paths to webview resources under the CSP. The producer's job is to emit
`scene.json` + the panel PNGs into a render-output dir; the host resolves that dir to a
`textureBaseUri` and posts it. Until the producer exists, the `Compose Preview: Open 3D Spatial
Fixture (dev)` command loads the committed fixture.
