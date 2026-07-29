# Vendored: `remote-compose-player` (TypeScript Remote Compose player)

A pure-TypeScript renderer for the Remote Compose binary format — parses an RC
document and paints it to a Canvas2D (with a WebGL path for shader ops). Runs in
the browser, Node, and VS Code webviews.

We vendor it so `compose-preview` can offer an **in-browser Remote Compose render
lane** — the client-side counterpart of the CMP Kotlin/Wasm tier — so a viewer can
render a catalog's captured `.rc` document (the bytes packed at `ir/<id>.rc`)
without a server-side Robolectric daemon.

## Upstream

- Repository: <https://github.com/camaelon/remotecompose-experiments>
- Path: `players/typescript/`
- Commit: `d8b07da2ad540eaf2d0b7f59cb9d7fb4624719c0`
- License: Apache-2.0 (see `LICENSE`)

## Local modifications

Vendored from the upstream path above (`src/`, `package.json`,
`package-lock.json`, `tsconfig.json`, `README.md`, `BUILDING.md`). Upstream's own
`packaging/`, `vscode-extension/`, and standalone-site tooling are intentionally
not vendored; only the library source needed to build the browser bundle.

Local deltas over that snapshot (each also filed upstream):

- **`CanvasOperations` (opcode 173).** Replaced the parse-only stub with a real
  implementation (`src/core/operations/layout/CanvasOperations.ts`, registered in
  `src/core/Operations.ts`), mirroring `CanvasOperations` in remote-core: a
  `Container` whose child draw ops (between it and its `ContainerEnd`) are grouped
  via `getList()` and replayed in `apply()` — so a component's `drawWithContent`
  decoration (Material3 button/card fill + label) paints instead of being dropped.
- **`LayoutComponent` draw-content guard** (`operations/layout/LayoutComponent.ts`).
  The draw-content path only replaces normal painting when the block actually
  holds a drawing op (a `PaintOperation`); a `DrawContentModifier` trailed solely
  by non-visual ops (e.g. `CoreSemantics`) no longer blanks the component's real
  content. Known remaining gap: a fill whose path geometry comes from
  layout-bound `FloatExpression`s still resolves empty, so the fill *shape*
  (not its colour or the label) is missing — tracked separately.

- **`CoreSemantics` (opcode 250 / `ACCESSIBILITY_SEMANTICS`).** Added
  `src/core/operations/semantics/CoreSemantics.ts` and registered it in
  `src/core/Operations.ts`. Before this, an `AccessibilityModifier` op in the
  stream hit the unknown-opcode path in `RemoteComposeBuffer.inflateFromBuffer`,
  which logs `Unknown operation opcode: 250, skipping rest of buffer` and
  abandons the rest of the document — so any component carrying accessibility
  semantics (every Material3 catalog preview) lost all operations after it. The
  op is accessibility metadata with no visual instructions, so the reader just
  consumes its wire payload (matching `CoreSemantics.read` in remote-core) and
  paints nothing, letting the rest of the document parse.

- **`TextLayout` (opcode 208 / `TEXT_LAYOUT`).** Replaced the parse-only stub
  with a real component: `src/core/operations/layout/managers/TextLayout.ts`
  extends `CoreText` (239) and is registered in `src/core/Operations.ts` in place
  of the `StubOperations` entry. `TEXT_LAYOUT` is the same text component as
  `CoreText` in a narrower, fixed-positional encoding — the form the Glance Wear
  widget capture (`WearWidgetDocument.captureRawContent`) and `remote-material3`'s
  `RemoteText` emit. The stub consumed the right number of bytes, so the stream
  stayed aligned and **no** unknown-opcode warning fired, but it discarded every
  field and painted nothing: a document whose text arrived this way replayed as
  its background alone. That is a strictly worse failure than opcode 250's
  truncation, because nothing reports it — the PNG↔RC parity page scored the
  text-less renders inside the catalog's normal mismatch band, since a missing
  string and a font-substituted one cost a similar pixel count. Extending
  `CoreText` reuses its measure/layout/paint path; only the wire decoding
  differs. One decoding subtlety: `fontSize`/`fontWeight` are NaN-boxed (a
  literal float, or an id in the NaN payload), and `WireBuffer.readNanId()`
  routes them through `readFloat()`, which collapses the payload because JS
  canonicalises NaN — so those two fields are read as raw int bits via
  `readInt()` (same 4 bytes, so the stream stays aligned), matching what
  `CoreText` stores and decodes with `isNaNBits`/`intBitsToFloat`.

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
await player.loadFromArrayBuffer(rcBytes);
// Named-value overrides (match a preview's declared knob names):
player.getRemoteContext().setNamedFloatOverride('progress', 0.15);
player.repaint();
```

## Validation

Built as above and rendered our actual captured `remote-m3` documents in headless
Chromium: `CircularProgressRemote.rc` paints correctly (the determinate arc at
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
