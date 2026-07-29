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

- **Concrete font stacks for the generic families** (`src/web/CanvasPaintContext.ts`). The typeface
  id → CSS family mapping named only the generics (`sans-serif` / `serif` / `monospace`, with
  `DEFAULT` and every unrecognised id collapsing to `sans-serif`). Android resolves those families
  to specific faces — `DEFAULT` and `sans-serif` are **Roboto**, not whatever the host calls
  `sans-serif` — so every string the player drew used a different typeface from the snapshot
  renderer, which reads as a permanent few-percent parity residual that no layout fix can close.
  `cssFontStackFor` now names the concrete face first and keeps the generic as the fallback, so a
  page that registers the faces matches the baked raster and one that does not renders exactly as
  before. Registering them is the harness's job (`scripts/design-artifacts/rc-fonts.mjs`), which
  also owns the weight ranges: declared at discrete weights, a request for an in-between weight
  (Wear M3 asks for 450) resolves upward to Medium and renders visibly too heavy.

- **Named font families, served from Google Fonts** (`src/web/WebFonts.ts`, wired into
  `src/web/CanvasPaintContext.ts` and `src/web/main.ts`). A document can name a family rather than
  pick one of the four generic typefaces (`RemoteFontFamily.Named(...)`), but that name never reached
  the paint layer: `CoreText.updateVariables` ends `else this.mType = this.mFontFamilyId`, so a named
  family arrives at `PaintBundle.TYPEFACE` as the document's **text id**, which `cssFontStackFor`
  didn't recognise and mapped to its `default` — every branded string silently rendered in Roboto.
  The operand is now disambiguated by magnitude (ids are handed out from `RemoteComposeState
  .START_ID` = 42 upward, so no text id can collide with the generics 0..3), resolved back through
  the text table, and the face registered on demand.

  Which families to fetch is stated by the document rather than guessed: a family namespaced
  `google:Orbitron` is fetched from the Google Fonts CSS API, an unprefixed one is only *named* and
  left to the host. Treating any unrecognised family as a Google Font would turn a typo — or a name
  that only means something on the host, like `SF Pro` — into a network request, with no way to say
  "this one is local".

  Three things this has to get right, all covered by
  `scripts/design-artifacts/rc-webfonts.test.mjs`:

  - **The request form must enumerate weights, not range them.** `wght@100..900` is rejected with
    HTTP 400 by any family that isn't variable (`Lobster`, `Pacifico`), and at the `<link>` a 400 is
    indistinguishable from a network failure — so a static family would be reported unavailable and
    fall back. The enumerated `ital,wght@0,100;…;1,900` is accepted for variable and static families
    alike, and the API returns only the faces the family actually ships, so over-asking is free.
  - **`@font-face` is lazy and canvas does not drive it.** `ctx.font` neither triggers a load nor
    waits for one, so the face is loaded explicitly. Note `document.fonts.check()` is useless as an
    assertion here — it answers *true* for a family that was never declared at all, so it cannot tell
    "registered" from "fell back"; the tests measure text width instead. The load is asked for *by
    font shorthand*, for the (weight, style) the paint op actually carries, so the browser's own CSS
    matching fetches just that face: the stylesheet declares every weight, but declaring is free and
    fetching is not — pulling all six of Orbitron's to draw one regular label would also hold
    `fontsReady()` open on faces nothing paints.
  - **Callbacks are per waiter and fire once.** Resolution runs per *paint*, so a settled variant
    must never re-announce — that would schedule a repaint from inside painting, forever — while a
    caller that arrives mid-fetch must still be recorded: with two players on a page, dropping the
    second leaves that canvas in the fallback permanently, since a static document has no later
    frame to recover on.
  - **Resolution is synchronous but fetching is not.** Resolution happens mid-paint, so the stack
    names the family immediately and the face lands later: interactive players repaint via
    `onFontLoaded`, and single-shot renderers await `player.fontsReady()` *after* the first paint,
    which is what discovers the families in the first place.

  `configureWebFonts({enabled, baseUrl})` is the embedder's switch — off for a webview whose CSP
  forbids the font origins or a hermetic CI lane, redirected for a mirror. A family that cannot be
  served never throws: it degrades to the fallback generic, because a document naming a font we
  can't fetch is an authoring fact, not a player fault.

  **Known asymmetry.** The snapshot renderer does *not* yet honour a named family in an `.rc`
  document — Robolectric resolves it through `DefaultTypefaceResolver`, which looks the name up as a
  file under `/system/fonts/` and finds nothing, so the baked raster stays Roboto while the browser
  lane now draws the real face. The hook to close this exists (`RemoteDocumentPlayer` takes a
  `TypefaceResolver`, and `renderers/android` already downloads Google Fonts for Compose's own font
  resolver via `GoogleFontCache`); what it needs is that downloader lifted out of `renderers/android`
  into a module the Remote Compose connector can also see, rather than a second copy of it. Until
  then the `Text/Branded` sticker is expected to read high on the PNG↔Remote-Compose parity page —
  that row is measuring the renderer gap, not a player regression.

  That gap also shows up as *clipping*, which is worth knowing before it is mistaken for a text-layout
  bug: an `.rc` carries geometry the authoring renderer measured, so while that renderer resolves the
  family to Roboto the player is drawing a wider face into a box measured for a narrower one. Closing
  the renderer side fixes the metrics and the typeface together.

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
