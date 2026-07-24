# Vendored: `remote-compose-player` (TypeScript Remote Compose player)

A pure-TypeScript renderer for the Remote Compose binary format — parses an RC
document and paints it to a Canvas2D (with a WebGL path for shader ops). Runs in
the browser, Node, and VS Code webviews.

We vendor it so `compose-preview` can offer an **in-browser Remote Compose render
lane** — the client-side counterpart of the CMP Kotlin/Wasm tier — so a viewer can
render a catalog's captured `.rcdoc` document (the bytes packed at `ir/<id>.rcdoc`)
without a server-side Robolectric daemon.

## Upstream

- Repository: <https://github.com/camaelon/remotecompose-experiments>
- Path: `players/typescript/`
- Commit: `d8b07da2ad540eaf2d0b7f59cb9d7fb4624719c0`
- License: Apache-2.0 (see `LICENSE`)

## Local modifications

None — vendored verbatim from the upstream path above (`src/`, `package.json`,
`package-lock.json`, `tsconfig.json`, `README.md`, `BUILDING.md`). Upstream's own
`packaging/`, `vscode-extension/`, and standalone-site tooling are intentionally
not vendored; only the library source needed to build the browser bundle.

## Building the browser bundle

```sh
npm ci
# esbuild IIFE, global `RC`. The browser build never touches the Node-only
# `canvas` dependency, so mark it external:
npx esbuild src/web/main.ts --bundle --outfile=web-player/bundle.js \
  --format=iife --target=es2020 --global-name=RC --external:canvas
```

The bundle exposes `RC.RcdPlayer`, `RC.createPlayer`, the `<rc-player>` custom
element, and `RC.base64ToArrayBuffer`. Render a document:

```js
const player = new RC.RcdPlayer(canvas);
await player.loadFromArrayBuffer(rcdocBytes);
// Named-value overrides (match a preview's declared knob names):
player.getRemoteContext().setNamedFloatOverride('progress', 0.15);
player.repaint();
```

## Validation

Built as above and rendered our actual captured `remote-m3` documents in headless
Chromium: `CircularProgressRemote.rcdoc` paints correctly (the determinate arc at
its 0.66 default). The render is captured at
`docs/design/evidence/rc-ts-player/circularprogress-clientside.png`.

Known gaps to resolve when wiring the live viewer lane (tracked as follow-ups, not
blockers for vendoring):

- **Shader stickers** (e.g. `ShaderGradientSticker`) use the player's WebGL path,
  which needs a real WebGL context — headless Chromium must be launched with
  software GL (`--enable-unsafe-swiftshader`), as the repo's `serve-lanes` harness
  already does.
- **By-name overrides** (`setNamed*Override`) did not take effect in a first pass;
  the name lookup likely needs the `USER:`-domain-qualified name our connector
  binds. Until resolved, the lane renders the baked document but not live knob edits.

## Follow-up

Upstream is not published to npm. The plan is to publish this library (upstream or
ourselves) and switch this vendored copy for a normal npm dependency bundled by our
own esbuild, the way `three` is consumed by the VS Code webview.
