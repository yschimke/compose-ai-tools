# Spatial-view fixtures (dev)

Extra `SpatialScene` fixtures + a standalone dev page for iterating on the 3D
spatial viewer ([`../src/webview/spatial/`](../src/webview/spatial/README.md))
without VS Code. The **canonical contract fixture** lives elsewhere —
[`../preview-harness/fixtures/spatial-scene/`](../preview-harness/fixtures/spatial-scene)
(owned by the contract); this directory adds a richer scene that exercises
rotation, orbiter affordances, and an environment colour.

## Layout

```
spatial-fixtures/
  spatial-rich.gen.mjs   # generator: emits spatial-rich/scene.json + panel PNGs
  spatial-rich/
    scene.json           # a SpatialScene (see shared/spatialScene.ts)
    panels/*.png          # one PNG per panel + orbiter
  index.html             # standalone dev page (orbit/pan/zoom + fixture picker)
  snapshot.mjs           # headless Playwright capture → out/<name>.png (gitignored)
```

## Regenerate

```sh
node spatial-fixtures/spatial-rich.gen.mjs
```

## Preview

```sh
node esbuild.webview.mjs                                  # build media/webview/spatial.js
node spatial-fixtures/snapshot.mjs --fixture spatial-rich
node spatial-fixtures/snapshot.mjs --fixture spatial-scene --focus bottom
```

or interactively — serve the extension root and open `/spatial-fixtures/`:

```sh
npx --yes http-server -c-1 .                              # from vscode-extension/
```

`snapshot.mjs` honours `SPATIAL_CHROMIUM=<path-to-chrome>` when the default
Playwright Chromium download isn't present (some CI sandboxes ship only the
full Chromium build, not the headless shell).

> For the **panel** (toggle + mounted viewer) visual record, use the
> preview-harness `spatial-view` fixture instead — see
> [`../preview-harness/README.md`](../preview-harness/README.md).
