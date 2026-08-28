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

## Debugging what a document actually *does*: `trace.mjs`

```bash
npx esbuild src/node-entry.ts --bundle --outfile=build-node/node-entry.js \
    --format=esm --platform=node --external:canvas
node trace.mjs DOC.rc --frames 30 --ops
node trace.mjs DOC.rc --frames 30 --hold          # press and hold from frame 2
node trace.mjs DOC.rc --frames 12 --watch 54,58   # print these float ids every frame
```

It runs the real player over N frames in Node and reports **state**, not pixels: which
float variables changed, how often, and (with `--ops`) how many times each operation
class was applied. Exit code 1 if nothing changed at all, so it works as a regression
check.

This exists because pixels are the wrong instrument, and repeatedly cost hours:

- A frame that looks alive can be entirely driven by `continuousSeconds()` while every
  action in the document is a no-op. Colour-variety and frame-hash checks pass happily.
- A frame that looks frozen may just be a headless browser that painted **once** —
  headless Chrome under `--virtual-time-budget` does not run a sustained animation loop,
  so every before/after pixel comparison in it compares a frame to itself.
- Sprite-finding heuristics latch onto the wrong thing. One "droid tracker" here was
  measuring a cloud, and reported a confident zero.

`--ops` answers "did this operation ever run" directly, which is the question worth
asking first. Two examples it settles immediately on any document:

```
    30  RunActionOperation
    30  ValueFloatExpressionChangeAction     <- running once per frame
     0  ValueFloatExpressionChangeAction     <- never applied; look no further
```

For a per-feature check, trace the same document twice and diff the sets of ids that
move — idle versus `--hold` proves input reaches the document, and one build versus
another proves a change did what was claimed:

```
changed only PRE-FIX : 78
changed only FIXED   : 45
changed in both      : 1 30 31 44 48 54 58 59 60 61 62 73
```

### The other half: the reference trace

`remote-core` is plain Java — the same state engine the Android player uses — so the
reference trajectory can be produced headlessly, no device required:

```bash
printf 'file=/path/doc.rc\nframes=30\nwidth=400\nheight=800\n' > /tmp/rc-trace.properties
./gradlew :compose:remote:remote-core:test --tests '*RcTraceTest*' --rerun-tasks
```

(`RcTraceTest` lives in `remote-core/src/test`. It reads a properties file because
Gradle does not forward `-D` to the test JVM, and mocks `PaintContext` away — nothing
draws; the point is what the operations do to the variables.)

Both sides print `frame N id=value ...`, so the runs diff directly. First comparison of
`flappy.rc` at frame 0: **12 of 34 shared floats match, 22 differ.**

Getting a *fair* diff is most of the work, and two setup details dominate it:

- **Size the context before `initializeContext`.** That call seeds the system
  window/component variables; sizing afterwards leaves them at the document's 256
  default while everything else lays out at the real viewport.
- **A document that declares no size reports 256x256.** The host supplies the viewport
  — the browser player uses the canvas size. Tracing one side at 256 and the other at
  400x800 makes every position differ for reasons that have nothing to do with the port.

So treat ids 1/2/3 (clock), 5/6 (window) and anything derived from them as harness
parity until proven otherwise. The game's own state — on `flappy.rc` that is ids 44 and
48-61 — is where a real divergence would show, and those are still differing.

## Status, gaps and debugging

- [../../STATUS.md](../../STATUS.md) — repo-wide state: what is verified, what is broken.
- [GAPS.md](GAPS.md) — per-opcode: 11 unregistered, 14 that parse and do nothing, and the
  21 corpus documents that still diverge from the reference.
- [DEBUGGING.md](DEBUGGING.md) — the tooling (`trace.mjs`, `sweep.mjs`, `whowrites.mjs`)
  and the plan for what is still missing.

This player agrees with the androidx reference on **159 of 180** corpus documents,
frame by frame. `npx tsc --noEmit` reports pre-existing errors in `ColorAttribute.ts` and
`BoxLayout.ts`; they predate this work and do not block the bundle, which esbuild builds
without typechecking.
