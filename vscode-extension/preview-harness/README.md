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

## Page fixtures (standalone HTML surfaces)

Some web surfaces aren't the `<preview-app>` panel — e.g. the
`compose-preview serve` landing + viewer pages, which are self-contained HTML
documents produced by `ServeWeb` in `:cli`. Those are captured by
[`pages-snapshot.spec.mjs`](pages-snapshot.spec.mjs), which reuses the same
approach (Playwright + `_server.mjs` + `out/<fixture>.<theme>.png` → the generic
`vscode-preview-diff.py` bot) but a different invocation: it navigates straight
to the committed HTML and drives theme via `prefers-color-scheme` emulation
rather than booting `scenario.html` and replaying messages.

- Fixtures live in [`fixtures/pages/`](fixtures/pages/) as committed `*.html`
  (plus `_render-placeholder.png`, served for the daemon's `/render` endpoint
  which has no backend here). They're auto-discovered — drop a `.html` in and
  it's captured.
- The serve pages are generated from production `ServeWeb` by the Kotlin golden
  test `ServeWebFixtureTest`, which also fails CI if the committed HTML drifts
  from `ServeWeb`. Refresh after a serve-UI change with:

  ```sh
  UPDATE_SERVE_WEB_FIXTURES=true ./gradlew :cli:test --tests '*ServeWebFixtureTest*'
  ```

The `snapshot` path filter in `harness:snapshot` matches this spec too, so these
land in `out/` alongside the panel fixtures and need no separate CI wiring.

## Serve render lanes (daemon-backed, both backends)

[`serve-lanes.spec.mjs`](serve-lanes.spec.mjs) is the odd one out: it drives a
**real, daemon-backed `compose-preview serve`** rather than the static harness
server, and asserts every render lane (PNG, SVG, Live `/ws/`, Wasm) actually
honours a `?knob.…` override. Point it at a running serve with `SERVE_URL` and
name the session with `SERVE_SYSTEM`:

```sh
SERVE_URL=http://127.0.0.1:8725 SERVE_SYSTEM=compose-m3 npm run harness:serve-lanes
```

Two boot scripts stand one up, one per render backend — both are what
[`serve-lanes-e2e.yml`](../../.github/workflows/serve-lanes-e2e.yml) runs:

- [`serve-lanes-boot.sh`](serve-lanes-boot.sh) — the **desktop CMP/Skiko** daemon,
  live-rendering the fetched `compose-m3` catalog under xvfb + software GL. Same
  shape preview.coo.ee runs.
- [`serve-android-lane-boot.sh`](serve-android-lane-boot.sh) — the
  **Android/Robolectric** daemon, live-rendering a locally packed
  `:samples:android-live-lane` bundle (signed with a throwaway key so it verifies
  `Trusted` and earns the live lane). Needs `:cli:stageDaemonAndroidLibs`' output
  via `LIB_DAEMON_ANDROID_DIR` and an Android SDK for `android.jar`; no xvfb.

The Android script deliberately **fails instead of degrading**: the Robolectric
lane's historical failure mode (#2669) was fail-soft — a sandbox that aborts at
bootstrap leaves the catalog serving baked PNGs with no error — so the script
gates on the session registering `LIVE`, on a real `200` render, and on the log
being free of `ClassNotFoundException`. The fixture's manifest names an
`Application` the render classpath doesn't carry precisely to keep that gate
honest.

## Playground, end to end (compile → first frame → live redemption)

[`playground.spec.mjs`](playground.spec.mjs) drives the Kotlin **playground**
(`docs/design/PLAYGROUND.md`) the whole way through in a browser: the editor
compiles a Compose snippet on the server (`POST /api/1/compiler/run`, BTA), gets a
first-frame still back, and the returned `/pg/<token>` capability redeems into the
ordinary live viewer. The playground's seams are unit-tested against fakes; this is
the only thing that exercises the real wiring with a daemon behind it.

```sh
SERVE_URL=http://127.0.0.1:8727 SERVE_TOKEN=playground-e2e npm run harness:playground
```

[`playground-boot.sh`](playground-boot.sh) stands one up (also run by
[`serve-lanes-e2e.yml`](../../.github/workflows/serve-lanes-e2e.yml)). It reuses the
Android/Robolectric backend — the default editor snippet declares an Android
`@Preview` — booting serve with `--playground-android-bundle` over a locally packed
`:samples:android-live-lane`. The lane compiles and runs user code in-process, so it
is refused under `--public`: the boot is **token-gated** (`SERVE_TOKEN`, which the
spec carries as `?token=`), and the script fails loud unless the log shows
`playground enabled`. Same `LIB_DAEMON_ANDROID_DIR` + Android-SDK prerequisites as
the Android render lane above.

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

## What belongs in a capture, and what belongs in a component test

A capture answers **"does this look right"**. A component test
(`cli/serve-web/test/*.test.ts`, mocha + happy-dom) answers **"does this do the
right thing"**. Those are different questions, and a state that drives an
interaction and then asserts on text, counts, attributes or URLs is a component
test wearing a screenshot's clothes — it pays a browser's cost and a baseline's
maintenance for an answer that does not involve pixels.

Measured on this suite:

| | count |
| --- | --- |
| `FIXTURE_STATES` entries | 59 |
| …carrying any `expect()` | 9 |
| …pure captures | 50 |
| `cli/serve-web` tests | 743 in 51 files (19 element, 32 pure) |

The 50 pure captures are the harness doing its job. The 9 hybrids are worth
reading, because most of what they assert has a cheaper home:

- **URL construction** (`href` contains `mode=spec`, `specView=diff`; a tag
  starting `a|`) — string building, no pixels.
- **Zoom arithmetic** (`zoom === 1`, an identity transform, alignment within
  2px) — `cli/serve-web/src/zoom/viewport.ts` already has its own tests; the
  capture re-asserts them through a browser.
- **Typography grouping** (`.cp-typography-group` count, `.cp-typography-count`
  text, an override reading `wght 700`) — now covered directly by
  `annotateTypography.test.ts` and `referenceCompareElement.test.ts`.

Two of them genuinely need a browser and should stay: `toBeVisible()`, which is
a CSS question happy-dom cannot answer, and the design page's alignment check,
which measures real laid-out geometry rather than the arithmetic behind it.

### The limit that matters most

**A capture can only cover an input the fixture actually contains**, and each new
fixture costs a baseline. That is not a theoretical concern:

- Every fixture SVG sits at `translate(0, 0)`, so a scorer bug that dropped
  fractional offsets entirely — mis-registering the SVG lane by hundreds of
  pixels, worth ~20 points of score — was invisible to every capture in this
  suite. Fixed in #3979 with a case in `format-compare-scorer.spec.mjs`.
- Every fixture `fontFamily` is single-suffix, so a typography bug that showed
  two families and compared them as one was equally invisible. Fixed in #3981,
  covered by unit tests.

Both shipped, both were found by reading code rather than by ~500 captures. A
decision whose inputs are expensive to vary is under-covered by construction, so
coverage of *decisions* belongs where inputs are free.

### Cost, for calibration

743 unit tests run in about 2 seconds. One fixture's 10 captures take 8–13.
There are 58 page fixtures.

### Rule of thumb

1. Extract the decision into a DOM-free module and test it there.
2. If it needs the DOM but not pixels, test the element in happy-dom.
3. Keep the capture for what is left: the picture.

The same brittleness exists one layer down. Six Kotlin assertions that grepped
`viewer.js` / `format-compare.js` for the *spelling* of an expression broke
during the TypeScript migration without any behaviour changing; each was
retargeted at the seam, with the rule itself driven by a real test. A test that
pins source text is a screenshot of code.

## Capture determinism

Two runs of the same tree must produce byte-identical PNGs. Two things enforce
that, both added for issue #3837 after four `serve-*` captures were found
differing between runs of unmodified code:

- **Rasterization** — `playwright.config.mjs` launches Chromium with
  `--disable-partial-raster --disable-skia-runtime-opts`. Without them, cached
  tile reuse and CPU-feature-dependent Skia paths round antialiased rounded
  corners differently from run to run (20–30 px, ±1–3 colour units).
  `serve-design-page-index` was the worst: it latched onto one of two variants
  for a whole process, so it read as a real diff on PRs that touched nothing
  near it. Deliberately NOT `--disable-lcd-text` / `--disable-gpu-rasterization`
  — they fix it too, but change glyph rasterization and move 12–20k pixels on
  every capture.
- **Pointer position** — a `FIXTURE_STATES` entry can set `parkPointer: true`,
  which moves the mouse to (0, 0) before the shot. `page.click()` leaves the
  pointer where it clicked, so a state that expands something slides fresh rows
  under it and captures a coin-flip hover.

`parkPointer` is **opt-in on purpose.** Parking by default was measured and
moved 53 of 196 captures, because a large minority of these states exist to
capture the resting pointer — `serve-home-index-card-hover`,
`serve-design-page-hover`, `serve-landing-public-card-hover`, both
`serve-landing-live` long-press states. Those keep passing while quietly
capturing the un-hovered page, which is the worst possible failure for a
baseline. Set `parkPointer` only when the pointer position is incidental.

To check determinism after changing the harness, run the suite twice into
separate directories and compare:

```
cd vscode-extension
for i in 1 2; do
  rm -rf preview-harness/out
  npx playwright test -c preview-harness/playwright.config.mjs pages-snapshot
  cp -r preview-harness/out /tmp/run$i
done
diff -rq /tmp/run1 /tmp/run2   # must be silent
```
