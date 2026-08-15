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
`<cp-backend-badge>`, `viewer-groups.js` → `<cp-group-memory>`, `rc-fonts.js` →
`window.cpRcFonts`.

**New behaviour on a page that has not been ported yet starts here anyway.**
`<cp-page-zoom>` (the design page's zoom: double-click to drill into a section,
⌘/Ctrl + wheel, drag to pan) is the first component with no legacy file behind it.
`assets/design-page.js` is still an IIFE — it measures an overlay onto every node,
flips the lanes, scores the per-node diff — and adding three hundred lines of
gesture handling to it would have made that port harder and left the feature
testable only through a browser. So the rule is *port a file when you touch it,
and write anything new in here regardless*, with the coupling one-way and through
the DOM: `<cp-page-zoom>` reads `.cp-page-selected` to know the page has a
selection (so Escape unwinds that before the zoom) and writes `--cp-page-zoom` on
the stage for `serve.css` to counter-scale the marks by. The legacy file knows
nothing about the element.

That component is also where the geometry-in-a-pure-module shape came from:
`src/zoom/viewport.ts` holds the framing, clamping and level-picking arithmetic
with no DOM in it, so the cases that actually broke it — a full-height section on
a 3.4:1 specimen sheet frames at 1.0x and looks like a dead gesture — are unit
tests rather than screenshots. Reach for the same split whenever a component's
real content is a calculation.

Next: `page-theme.js` (103 lines), then `url-state.js` — that one is the shared
global every legacy script reads at IIFE time, so it wants its own change once
more than one thing here needs it.

**Both of those are blocked on where the bundle is loaded from, and that is the
decision to make before either.** `ServeWeb` emits `serve-components.js` from a
handful of surfaces rather than from the page shell, so it does not reach every
page; `page-theme.js` does, because the shell (`document()`) emits it for
everything. So porting a piece of shared chrome means the bundle has to come
from the shell too, and the shell's script slot is the last line of `<body>`,
after the surface's own scripts. That is fine for a component (it upgrades
whenever the definition lands) and wrong for a global that a legacy script calls
as it starts — which is exactly why `rc-fonts.js` became a per-surface swap
rather than a shell change. Moving the bundle to the top of `<body>` fixes the
ordering and makes every page carry it; it also puts a render-blocking bundle on
the front door, which the prebaked landing imagery exists to avoid. Weigh that
before porting `page-theme.js`, don't discover it halfway through.

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

Three shapes have shown up, and it is worth naming which one a script is before
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
- **A global the legacy scripts call** (`window.cpRcFonts`): not an element at
  all. `viewer.js`, `format-compare.js` and the inline doc-player script read it
  at call time, so the port keeps the global and moves only the implementation.
  Changing the seam as well would mean editing three untyped callers in the same
  change, which is the opposite of one reviewable step — the seam goes when
  those callers do.

Whichever shape it is, the same rule holds for the elements the server emits:
they are declared in the page's HTML, so a fixture regeneration and a pixel
check are still part of the change.
