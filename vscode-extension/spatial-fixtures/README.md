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
  spatial-rich/
    scene.json            # a SpatialScene (see shared/spatialScene.ts)
    semantics-tree.json   # a SpatialSemanticsTree harvested alongside the renders
    panels/*.png          # one PNG per panel + orbiter
  index.html             # standalone dev page (orbit/pan/zoom + fixture picker)
  snapshot.mjs           # headless Playwright capture → out/<name>.png (gitignored)
```

## Regenerate

The panel PNGs **and** the companion `semantics-tree.json` come from real Compose:
the desktop `RenderEngine` renders each panel composable
([`SpatialPanelFixtures.kt`](../../daemon/desktop/src/test/kotlin/ee/schimke/composeai/daemon/SpatialPanelFixtures.kt))
to a texture and harvests its real Compose semantics for the wireframe, so the two
can't drift. Regenerate (writes `scene.json` + `semantics-tree.json` + `panels/*.png`):

```sh
SPATIAL_FIXTURES_DIR="$PWD/vscode-extension/spatial-fixtures/spatial-rich" \
  ./gradlew :daemon:desktop:test --tests '*SpatialRichFixtureGeneratorTest' --rerun-tasks
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
