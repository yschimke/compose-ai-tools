# Building the TypeScript Player

## Prerequisites

| Tool | Why |
|------|-----|
| Node 18+ | runs `npm`, `tsc`, `esbuild`, the dev tools |
| Python 3.8+ | `make_deck_site.py` |
| `code` CLI (optional) | install the resulting `.vsix` |

A one-time `npm install` from this directory pulls every JS / TS dev
tool used across all the build targets (esbuild, typescript, vsce). The
VS Code extension and the packaging scripts reuse this `node_modules/`
rather than installing their own copies.

```sh
cd players/typescript
npm install
```

---

## 1. The interactive web viewer

Bundles `src/web/main.ts` into `web-player/bundle.js` (an IIFE exposed
on `window.RC`) and serves the `web-player/` directory.

```sh
npm run bundle              # one-shot — produces web-player/bundle.js
npm run serve               # esbuild dev server on web-player/
```

The interactive viewer (`web-player/index.html`) has a file picker —
load any `.rc` / `.rcd` from your machine and it plays.

The bundle weighs ~600 KB minified. It pulls in nothing from a CDN; the
served HTML is fully self-contained.

---

## 2. Single-file standalone HTML

For when you want to share a single `.rc` as a single file that opens
without a server:

```sh
./packaging/build-standalone.sh ../../samples/canvas.rc rc-player.html
open rc-player.html
```

The script:

1. Re-bundles the player via `esbuild --minify` into a temp file.
2. Base64-encodes the input `.rc`.
3. Substitutes both into `web-player/standalone-template.html` with
   `sed`.

The resulting HTML is self-contained — drop it into Slack, email it,
host it anywhere. Open it locally with a `file://` URL and it just
works.

---

## 3. Static deck site

For a directory of slides (mixed `.rc` + `.mp4`/`.mov`/`.webp`):

```sh
./packaging/make_deck_site.py path/to/deck-dir
# → path/to/deck-dir/web/  (index.html + bundle.js + slides)

(cd path/to/deck-dir/web && python3 -m http.server 8000)
# open http://localhost:8000
```

Specify a custom output dir as the second argument:

```sh
./packaging/make_deck_site.py decks/talk-2026 site/talk-2026
```

The generated `index.html` includes:

- Slide list sidebar with file sizes and "deck share" percentages
- Keyboard navigation (←/→, Home/End, Space, F for fullscreen)
- Presentation mode (full-bleed black, no chrome)
- Tooltips with per-slide stats

---

## 4. VS Code extension

Builds a `.vsix` you can install via `code --install-extension`.

```sh
cd vscode-extension
./build.sh                                  # → rc-viewer-0.1.0.vsix
code --install-extension rc-viewer-0.1.0.vsix
```

The extension contributes a custom editor for `.rc` / `.rcd` files.
After installation, opening any RC file in VS Code shows a live
preview alongside a JSON inspector.

The extension itself is tiny — it wraps the player bundle in a webview
and adds the VS Code chrome around it.

---

## Common pitfalls

- **"esbuild not found"** → `npm install` from `players/typescript/`.
- **Bundle is stale** → `npm run bundle` again before re-running
  `make_deck_site.py` or testing the web viewer.
- **VS Code extension won't load** → verify `media/rc-bundle.js` exists
  (run `vscode-extension/build.sh` first).

---

## Cleaning

```sh
rm -rf node_modules web-player/bundle.js
rm -rf vscode-extension/{out,media/rc-bundle.js,media/rc2json-bundle.js,*.vsix}
rm -f rc-player.html
```
