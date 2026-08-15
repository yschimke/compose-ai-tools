# `serve-web` — Lit components for the preview server

The browser side of `compose-preview serve`, as typed, tested Lit components
instead of hand-rolled IIFEs in `assets/*.js`.

Built to a single committed bundle at
`cli/src/main/resources/ee/schimke/composeai/cli/serve/assets/serve-components.js`,
which `ServeWebAssets` then serves like any other static asset.

```
npm install
npm run build       # rebuild the committed bundle
npm run watch       # rebuild on save
npm test            # mocha + happy-dom
npm run verify      # typecheck + test + build + assert the bundle is committed
```

## Why Lit, and why the same setup as the VS Code extension

`vscode-extension/src/webview` is already Lit 3 + esbuild + TypeScript, with
mocha + happy-dom for unit tests and Playwright for visual capture. Reusing that
stack here means one set of idioms, one decorator mode, one test runner, and
`vscode-extension/preview-harness` already screenshots these pages — so the
capture pipeline needed no new machinery at all.

The pinned versions deliberately match the extension's. Two Lit majors in one
repo would be two component models to hold in your head for no benefit.

## The constraint that shapes everything here: this is a *server-rendered* surface

The VS Code webview renders itself from scratch on the client. `serve` does not,
and must not:

- It is a **public web server**. Pages are crawled, unfurled into link cards,
  and cached. The front door's imagery is prebaked precisely so a visit costs
  the HTML and nothing else.
- The **committed page fixtures are the regression net.** `ServeWebFixtureTest`
  renders `ServeWeb`'s pages to
  `vscode-extension/preview-harness/fixtures/pages/*.html`, and
  `pages-snapshot.spec.mjs` screenshots them per theme for the visual-diff bot.
  Move rendering to the client and those fixtures become empty shells — the net
  goes away in the same change that most needs it.
- Pages carry **pre-paint inline scripts** (the theme and transparency restores)
  specifically so nothing flashes before the bundle parses.

So components here are **light-DOM custom elements that the server's markup
declares**, not a client-side app:

```kotlin
// ServeWeb.kt
"<cp-bg-toggle label=\"…\"></cp-bg-toggle>"
```

```ts
// BgToggle.ts
protected createRenderRoot(): HTMLElement {
    return this;                       // light DOM: serve.css reaches the button
}
```

Shadow DOM would mean restating every `serve.css` rule the control depends on,
or piping them through custom properties, for controls that have no
encapsulation problem. Light DOM is what the extension's own components already
use, for the same reason.

**Page-wide state lives on the page, not in a module variable.** `<html>`'s
`cp-bg-transparent` class is the single source of truth for the transparency
choice because the pre-paint script has already set it before this bundle runs;
`backgroundChoice.ts` reads that class rather than re-deriving from
`localStorage`, so the two cannot disagree.

## Why the bundle is committed

`:cli` is a plain Kotlin/JVM module, and `installDist` / `distTar` / the release
chain / the GHCR image all run without node. Putting an npm build on that path
would mean every CLI build and every sandbox needs a node toolchain to produce a
tarball whose Kotlin didn't change. The vendored `codemirror.js` beside it is
committed for the same reason.

The cost of a committed artifact is that it can go stale silently. `npm run
verify` rebuilds and fails if the committed bytes differ from a fresh build of
`src/`; CI runs it, so "committed" cannot quietly become "stale".

## One bundle, where the legacy assets are per-page

`ServeWebAssets` loads most scripts selectively — `codemirror.js` only on the
playground, `catalog-live.js` only where a session can stream. That exists for
the heavy ones, and they keep their own tags.

This bundle is loaded whole. Lit is ~6 kB gzipped, an element whose tag is not
on the page costs only its bytes, and every serve page already loads
`url-state.js` — so splitting would buy less than the complexity costs, and it
lets components import each other.

Revisit if the bundle grows past roughly the size of the pages that load it.

## Porting the next component

One legacy `assets/*.js` file per change, so each step is a reviewable diff
against a moving fixture baseline:

1. Write the component under `src/components/`, light DOM, and add its import to
   `src/main.ts`.
2. Write its behavioural test under `test/`. **Port the contract, not the
   source.** The Kotlin tests that assert on an asset's *source text* (e.g.
   `ServeUrlStateTest` matching `urlState.push({ bg: choice });`) cannot survive
   minification and never proved the behaviour anyway — re-express them here
   against the real element, and delete the Kotlin assertion with a pointer to
   its replacement. Check the new test actually fails when you reintroduce the
   bug it names; the `bg-toggle` port did.
3. Change `ServeWeb.kt` to emit the element, drop the old `scriptTag(...)`, and
   remove the file from `ServeWebAssets.contentTypes`.
4. `npm run verify`, then regenerate the page fixtures:
   ```
   UPDATE_SERVE_WEB_FIXTURES=true ./gradlew :cli:test --tests '*ServeWebFixtureTest*'
   ```
5. Confirm the pixels didn't move:
   ```
   cd vscode-extension
   npx playwright test -c preview-harness/playwright.config.mjs pages-snapshot
   ```
   Set `HARNESS_CHROMIUM` when the sandbox's Chromium build doesn't match the
   pinned Playwright (`/opt/pw-browsers/chromium-*/chrome-linux/chrome`).

   These captures are deterministic (#3837): two runs of the same tree produce
   byte-identical PNGs for all 196. So a capture that moves is a real change —
   take it seriously rather than re-running until it agrees.

Ported so far: `bg-toggle.js` → `<cp-bg-toggle>`, `backend-badge.js` →
`<cp-backend-badge>`, `viewer-groups.js` → `<cp-group-memory>`.

Next, cheapest seam first: `rc-fonts.js` (50 lines), `page-theme.js` (103), then
`url-state.js` itself — that one is the shared global every legacy script reads
at IIFE time, so it wants its own change once more than one component here needs
it.

## New behaviour lands here too, not just ports

`<cp-catalog-toolbar>` was not ported from anything: the catalog landing's phone
toolbar needed a DOM reflow, and a new `assets/*.js` IIFE would have been one
more file for this migration to port later. A component that would have been
written as a legacy script is written here instead — same rules as a port (light
DOM, server-declared tag, a behavioural test, regenerate the fixtures, check the
pixels), so the legacy pile only ever shrinks.

Its test is worth reading before writing another controller like it. The
component's first version "restored" elements to where they already were on every
desktop load — a re-`insertBefore` of a node in its own position, which looks
like a no-op and is not: it detaches and re-attaches the element, and the browser
rebuilds what hangs off the attachment. The filter field is an
`<input type="search">`, so its clear button and its focus ring quietly went
missing, and the only thing that noticed was a page capture of a state two steps
later. The test now asserts *no element is moved at all* above the breakpoint,
because "ends up in the right place" was true the whole time it was broken.

Two shapes have shown up, and it is worth naming which one a script is before
porting it:

- **A control the server declares** (`<cp-bg-toggle>`): the element renders its
  own markup, because the control is inert without JS and one source of truth
  for the markup beats two.
- **A behaviour over markup the server already rendered** (`<cp-backend-badge>`,
  `<cp-group-memory>`): the element renders nothing structural. The badge is the
  host itself so the `role="status"` live region stays in the served HTML — a
  live region created by script with its text already in it is not announced —
  and `<cp-group-memory>` is a page-level controller because `<details>` cannot
  be a custom element and eight marker children would be worse than one.

Whichever shape it is, the same rule holds for the elements the server emits:
they are declared in the page's HTML, so a fixture regeneration and a pixel
check are still part of the change.
