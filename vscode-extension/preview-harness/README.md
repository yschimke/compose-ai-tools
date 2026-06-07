# Preview view harness

A lightweight "Compose Preview" for the VS Code panel itself. Renders the
real `<preview-app>` Lit element in headless Chromium, driven by fixture
JSON, and writes a PNG per `(fixture × theme)` pair. Use it to iterate on
panel design — accessibility extension layouts, focus mode chrome, error
states — without rebuilding the extension and reattaching to a host.

## Agent workflow

If you're an AI agent editing anything under `src/webview/`,
`media/preview.css`, `media/preview-*.svg`, or any other file that
affects what the panel looks like: render a baseline + post-change
snapshot via this harness and surface both PNGs to the user before
claiming the task is done. Production UI changes without a visual
record are not done.

The loop is:

1. **Pick a fixture** that exercises the surface you're touching. Check
   `fixtures/` first — `grid-default` covers the multi-card grid;
   `a11y-findings` covers focus mode + the Accessibility bundle (chip
   bar, data tab, on-image overlay). If neither hits your surface,
   write a new fixture (`<surface>.gen.mjs` + emitted `.json`) and
   commit it alongside the production change. A fixture is small and
   future-proof — they're the design contract for the panel.

2. **Baseline.** Before editing source:

    ```sh
    HARNESS_FIXTURE=<name> npm --prefix vscode-extension run harness:snapshot
    ```

    Copy the PNG out of `preview-harness/out/` so the re-snapshot
    doesn't clobber it (e.g. `cp out/<name>.dark.png /tmp/before.png`).
    Skip the baseline only when you know the file is brand new with no
    "before" — say so explicitly in your reply instead of silently
    skipping.

3. **Make the change.**

4. **Re-snapshot.** Same command. The bundle rebuild is wired into the
   npm script.

5. **Diff and surface.** Send the user both PNGs (baseline + after) via
   `SendUserFile` with a short caption pointing at what changed. The
   user signs off on the visual, not on prose claims about it.

A few specifics that come up:

- `imageData` in `updateImage` messages is **raw base64** — the card
  prepends `data:<mime>;base64,` itself. Half the cards rendering
  blank is almost always this.
- New `--vscode-*` tokens in `media/preview.css` need values in
  `vscode-theme.css` (both `:root` and `[data-vscode-theme="light"]`).
  If a token's missing, panel chrome silently falls back to
  unstyled — check the cascade if a control suddenly looks wrong in
  light mode only.
- Fixtures use scripted `actions` (CSS-selector clicks) to drive flows
  the extension normally triggers through user input — entering focus
  mode, toggling a bundle chip, picking a filter option. Reach for the
  `actions` array instead of trying to bake "I'm in focus mode" state
  into the manifest.
- The harness is a design preview, **not** a substitute for
  `test:electron`. Use it for shape, layout, and theming; use the
  electron suite for behavioural correctness (message routing, daemon
  round-trips, focus traversal).
- **WebGL fixtures** (the 3D `spatial-view`): the config's
  `launchOptions` run Chromium with software WebGL
  (`--enable-unsafe-swiftshader --use-gl=angle`), so no GPU is needed.
  three.js loads quad textures through its own image loader (not
  `.preview-card img`), which the rAF/img settle can't observe — give
  those a beat with a `settleMs` field on the fixture (the `spatial-view`
  fixture uses `1600`). Set
  `HARNESS_CHROMIUM=<path-to-chrome>` when the default Playwright
  Chromium download isn't present (some sandboxes ship only the full
  build, not the headless shell).
- **Live XR capture** (`spatial-xr-real`): the committed spatial fixtures wrap
  the `spatial-rich` proxy (per-panel renders assembled offline). To diff the
  *actual* `composePreviewRenderXr` output, the `render-xr-composite` CI job
  generates a fixture from the real render dir with
  [`fixtures/spatial-xr-real.gen.mjs`](fixtures/spatial-xr-real.gen.mjs)
  (`--render-dir <renders/<id>> --texture-base /spatial-fixtures/spatial-xr-real/`),
  snapshots it (`HARNESS_FIXTURE=spatial-xr-real`), and uploads the PNG as the
  `spatial-xr-real-render` artifact. The fixture + staged textures are generated
  in CI and git-ignored (never committed). The generator's shape is unit-tested
  in `src/test/spatialXrRealGen.test.ts`.

## Run

The harness runs on **`@playwright/test`** (config:
[`playwright.config.mjs`](playwright.config.mjs)). The runner owns the
static server's lifecycle (the `webServer` block boots
[`_server.mjs`](_server.mjs)), parallelises the `(fixture × theme)`
matrix, and retains a trace on failure.

```sh
npm run harness:snapshot         # build the bundle + capture all fixtures
# narrow the matrix (replaces the old --fixture / --theme flags):
HARNESS_FIXTURE=grid-default HARNESS_THEME=dark npm run harness:snapshot
# or use Playwright's own grep:
npx playwright test -c preview-harness/playwright.config.mjs -g grid-default
```

PNGs land in `preview-harness/out/` (gitignored), one
`<fixture>.<theme>.png` per case — same names the CI diff consumes. On a
failure, open the trace with `npx playwright show-trace
preview-harness/test-results/<case>/trace.zip` for a DOM + console +
network snapshot of the run.

To preview interactively, serve the extension root and open
`http://localhost:.../preview-harness/index.html`:

```sh
npx --yes http-server -c-1 .       # from vscode-extension/
```

## Wire-side contract

```sh
npm run harness:contract         # assert each fixture's expectedPosts
HARNESS_FIXTURE=a11y-findings npm run harness:contract
```

Same drive as `harness:snapshot` ([`contract.spec.mjs`](contract.spec.mjs)
shares [`_drive.mjs`](_drive.mjs) with [`snapshot.spec.mjs`](snapshot.spec.mjs)),
but instead of writing a PNG it reads
`window.__composePreviewHarness.postedMessageLog` after the fixture's
actions complete and asserts each fixture's optional `expectedPosts` /
`forbiddenPosts` arrays via `expect`. Fixtures with neither array are
skipped. Use it to pin the
end-to-end wire effect of clicking a chip / toggling a kind /
selecting a row, without launching real VS Code: the harness's
`vscode-api.js` shim already captures every `vscode.postMessage`
call, the contract just compares them against the per-fixture rules.

A fixture's `expectedPosts` is an array of subset-match templates —
every key on the template must equal the same key on some recorded
post; extra keys on the recorded post are ignored. Helper:

```js
import { expectSetDataExtension } from "./_utils.mjs";

expectedPosts: [
    expectSetDataExtension(focusId, "compose/semantics", true),
],
```

`forbiddenPosts` is the inverse — fail if any recorded post matches.
Useful for "this code path must NOT subscribe `kind X` until the user
opts in" assertions.

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
   `setPreviews` / `updateImage` / etc. — then flips
   `__composePreviewHarness.booted`.
5. **Who drives the `actions` differs by entry point.** Under Playwright
   (the page is opened with `?driver=playwright`), the page _stops_ at
   `booted` and the spec replays `actions` through locators
   (`dispatchEvent("click")`, which auto-waits for the element to attach
   and matches the bundle's real click handlers). Opened any other way —
   the manual `index.html` iframes — `scenario.html` replays the actions
   itself so the static preview still shows the post-action state.
6. The runner's `webServer` boots [`_server.mjs`](_server.mjs) (a tiny
   static server, since `file://` blocks `fetch()` in Chromium).
   [`snapshot.spec.mjs`](snapshot.spec.mjs) waits for `booted`, replays
   actions, then captures a full-page PNG with `animations: "disabled"`
   so CSS animations — including the infinite skeleton shimmer — are
   frozen at capture rather than waited on.

## Adding a fixture

Drop `preview-harness/fixtures/<name>.json` with shape:

```json
{
    "description": "What this scenario shows",
    "dataset": {
        "earlyFeatures": "false",
        "minimalMode": "false"
    },
    "messages": [
        { "command": "setPreviews", "moduleDir": "...", "previews": [...], "heavyStaleIds": [] },
        { "command": "updateImage", "previewId": "...", "captureIndex": 0, "imageData": "<raw base64>" }
    ],
    "actions": [
        { "click": ".preview-card[data-preview-id=\"...\"] .card-focus-btn" },
        { "click": "bundle-chip-bar button[data-bundle=\"a11y\"]" }
    ]
}
```

`messages` are `ExtensionToWebview` payloads (see
`src/types.ts`). `updateImage.imageData` is **raw base64** — the card
prepends `data:<mime>;base64,` itself.

`actions` replay user-style interactions after the manifest has
rendered, using CSS selectors against the panel's light DOM. Use
them to drive flows that the extension would normally trigger through
clicks — e.g. entering focus mode via `.card-focus-btn`, activating a
data extension via the bundle chip bar. Today the only action type is
`{ "click": "<selector>" }`.

For repeatable bytes, write a generator next to the fixture
(`grid-default.gen.mjs` is the template) and check it into git
alongside the JSON it emits.

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
