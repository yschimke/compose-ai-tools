# Preview view harness

A lightweight "Compose Preview" for the VS Code panel itself. Renders the
real `<preview-app>` Lit element in headless Chromium, driven by fixture
JSON, and writes a PNG per `(fixture × theme)` pair. Use it to iterate on
panel design — accessibility extension layouts, focus mode chrome, error
states — without rebuilding the extension and reattaching to a host.

## Run

```sh
npm run harness:snapshot         # build the bundle + capture all fixtures
node preview-harness/snapshot.mjs --fixture grid-default --theme dark
```

PNGs land in `preview-harness/out/` (gitignored).

To preview interactively, serve the extension root and open
`http://localhost:.../preview-harness/index.html`:

```sh
npx --yes http-server -c-1 .       # from vscode-extension/
```

## How it works

1. `scenario.html` is a static page that loads `media/preview.css`,
   `media/codicon.css`, and the harness's `vscode-theme.css` (VS Code's
   CSS custom property defaults for the dark + light themes).
2. `vscode-api.js` installs a stub `acquireVsCodeApi()` so `getVsCodeApi()`
   resolves outside a real webview. Posted messages land in
   `window.__composePreviewHarness.postedMessageLog` for inspection.
3. The page fetches the named fixture, stamps its `dataset.*` onto a
   freshly-created `<preview-app>` element (BEFORE injecting `preview.js`
   so Lit's `firstUpdated` reads the intended boot flags), then injects
   the bundle.
4. After `customElements.whenDefined('preview-app')` and the element's
   first `updateComplete`, the page replays `fixture.messages` via
   `window.postMessage(...)` — the same path the real webview takes for
   `setPreviews` / `updateImage` / etc.
5. `snapshot.mjs` spins up a tiny `http.createServer` (so `fetch()`
   works — `file://` blocks it in Chromium), drives Playwright through
   each fixture/theme, waits for `__composePreviewHarness.ready` plus
   any in-flight CSS animations, and writes a full-page PNG.

## Adding a fixture

Drop `preview-harness/fixtures/<name>.json` with shape:

```json
{
    "description": "What this scenario shows",
    "dataset": {
        "earlyFeatures": "false",
        "autoEnableCheap": "false",
        "collapseVariants": "false",
        "minimalMode": "false",
        "autoInject": "true"
    },
    "messages": [
        { "command": "setPreviews", "moduleDir": "...", "previews": [...], "heavyStaleIds": [] },
        { "command": "updateImage", "previewId": "...", "captureIndex": 0, "imageData": "<raw base64>" }
    ]
}
```

`messages` are `ExtensionToWebview` payloads (see
`src/types.ts`). `updateImage.imageData` is **raw base64** — the card
prepends `data:<mime>;base64,` itself. For repeatable bytes, write a
generator next to the fixture (`grid-default.gen.mjs` is the template)
and check it into git alongside the JSON it emits.

## Fidelity caveats

This is a design preview, not a substitute for `test:electron`. Drift
sources:

- **Theme tokens** are VS Code's defaults frozen into `vscode-theme.css`.
  Real VS Code installs can swap the colour theme and the panel will
  follow; the harness will not.
- **CSP** is the http server's default (anything goes). The live webview
  enforces a strict policy.
- **Host messaging** is purely scripted. The harness can't exercise
  daemon round-trips or the focus/diff flows that depend on the
  extension replying.

When a new `--vscode-*` token starts appearing in `media/preview.css`,
add a value for it in `vscode-theme.css` (both dark and light blocks).
