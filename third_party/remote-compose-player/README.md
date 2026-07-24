# typescript/ — RC TypeScript Player

A pure-TypeScript player for the RC binary format. Runs in the browser
on a `<canvas>`, in Node, and inside a VS Code webview. The same code
underpins three deliverables:

```
   .rc / .rcd  →  src/core (engine)  →  src/web (Canvas2D backend)  →  pixels
                                    ↘  src/rc2json.ts (debug)
```

## What ships from here

| Output | Built from | How |
|---|---|---|
| **Web player bundle** (`web-player/bundle.js`) | `src/web/main.ts` | `npm run bundle` |
| **Interactive web viewer** (`web-player/index.html` + bundle) | the bundle + UI HTML | served from `web-player/` |
| **Single-file standalone HTML** (one `.rc` baked in, no server needed) | `packaging/build-standalone.sh` | wraps esbuild + base64 |
| **Static deck site** (directory of `.rc` + media → website) | `packaging/make_deck_site.py` | wraps the bundle + an index.html generator |
| **VS Code extension** (`.vsix`) | `vscode-extension/` | `vscode-extension/build.sh` |

## Quick start

```sh
npm install               # one-time — installs esbuild, tsc, vsce
npm run bundle            # → web-player/bundle.js

# Serve the interactive viewer
(cd web-player && python3 -m http.server 8000)
# open http://localhost:8000

# Build a single-file demo
./packaging/build-standalone.sh ../../samples/canvas.rc out.html
open out.html

# Build a static site from a deck directory
./packaging/make_deck_site.py path/to/deck-dir
(cd path/to/deck-dir/web && python3 -m http.server 8000)

# Build the VS Code extension
(cd vscode-extension && ./build.sh)
code --install-extension vscode-extension/rc-viewer-0.1.0.vsix
```

See [BUILDING.md](BUILDING.md) for the per-deliverable walk-through and
common-pitfall list.

## Layout

```
typescript/
├── package.json         npm + esbuild build config
├── tsconfig.json
├── src/                 TypeScript engine + browser backend
│   ├── core/              port of the binary-format runtime
│   │                      (WireBuffer, CoreDocument, RemoteContext,
│   │                       expressions, layout, paint, particles, …)
│   ├── web/               Canvas2D backend implementing PaintContext
│   ├── rc2json.ts         binary → JSON dumper (used by VS Code, debug)
│   ├── debug_entry.ts     dev-only entry point for browser-side debugging
│   └── node-entry.ts      headless Node entry (canvas via the `canvas` npm pkg)
├── web-player/          interactive in-browser viewer
│   ├── index.html         file picker + canvas + scrubber
│   └── standalone-template.html   template the standalone builder fills in
├── doc/                 engine implementation notes
├── packaging/           tools that wrap the bundle into shippable artefacts
│   ├── build-standalone.sh
│   └── make_deck_site.py
└── vscode-extension/    VS Code custom editor for .rc / .rcd
    ├── package.json
    ├── src/
    └── build.sh
```

## Status

The player parses and renders the RC binary format the same way the C++
engine in [`../cpp/`](../cpp/) does. Documents that round-trip cleanly
through the C++ pipeline are expected to render identically here.

For Node-side rendering (server-side image export, gold comparisons),
see [`compare-gold.mjs`](compare-gold.mjs).

## License

Apache 2.0. See the repo-root `LICENSE`.
