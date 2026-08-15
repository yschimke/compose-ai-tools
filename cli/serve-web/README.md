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
`window.cpRcFonts`, `viewer-drawers.js` → `<cp-viewer-drawers>`,
`url-state.js` + `page-theme.js` → `serve-chrome.js`.

**Port the file whose bugs you keep paying for, not the smallest one left.**
`viewer-drawers.js` was fifth by the cheapest-seam ordering and first by every
other measure: it owns both drawers, the phone row order, the theme toggle's
value and the component filter, and #3893 changed one of its inputs without a
single test noticing until page captures started timing out weeks later. Three
suites were red by then. A file that can only be exercised through a browser is
a file whose defaults drift, so the ones with defaults are worth taking early.

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

`<cp-viewer-drawers>` uses that same split, and it is the clearest argument for
it: `viewer/drawerState.ts` holds three viewport bands crossed with a stored
preference and the server's own default, with no DOM in it, so the case that
actually broke — `isWide` is not `!isMobile`, and each band answers "is the nav
open" its own way — is a 13-line table rather than a screenshot nobody reads.
`viewer/navFilter.ts` does the same for the filter's three exceptions.

That shape came from `<cp-page-zoom>`:
`src/zoom/viewport.ts` holds the framing, clamping and level-picking arithmetic
with no DOM in it, so the cases that actually broke it — a full-height section on
a 3.4:1 specimen sheet frames at 1.0x and looks like a dead gesture — are unit
tests rather than screenshots. Reach for the same split whenever a component's
real content is a calculation.

`<cp-history-menu>` is the same split applied to a component whose real content
is URL arithmetic: `viewer/historyUrls.ts` decides which links can be built at
all (and safely — every one of them is DOM text landing in an `href`, the
`js/xss-through-dom` flow three earlier attempts got wrong the same way), and
`viewer/historyModel.ts` decides which of them are worth showing. The element is
left with a fetch and a template. The port also deleted `place()`: the old script
built the menu at runtime and then went looking for somewhere to put it, and the
server already knows where the control belongs, so it declares the tag in the
toggle row and the placement question stops existing.

The parity page took the split furthest: `parity/laneFilter.ts` and
`parity/findings.ts` hold everything the page decides — which entries a lane
shows, what counts as a visual finding, how a score cell reads, what order the
issues table is in, what the summary sentence says — and `<cp-parity-lanes>` and
`<cp-parity-scores>` are left with event wiring and a template. That last one is
also the first element to take over a band the server used to emit, which is
what let two problems go away rather than move: the issues table was built by
hand-escaping into `innerHTML`, with an `esc()` that neutralised `<`, `>` and `&`
but not `"` while its output was interpolated straight into `href="…"`; and a
page with JavaScript off was left promising `Checking 40 mapped comparison(s)…`
forever. A binding cannot be broken out of, and an element that renders nothing
until it has something to say leaves no false promise behind.

`dom/whenParsed.ts` came out of that page and `<cp-history-menu>` hitting the
same wall. A light-DOM element is upgraded when the parser reaches its tag, so
anything it reads from a sibling further down the page does not exist yet. The
served pages only got away with it because their script tags happen to sit near
the end of `<body>`. Every component that reads outside its own subtree now waits
for the parse.

`<cp-rc-lanes>` is where the split paid off most, because the thing it split out
had never been checked at all: `rc/pixelDiff.ts` is pixelmatch's YIQ metric,
hand-transcribed into nine magic constants and a threshold scale, and it produces
the only number on the compare page the offline run did not compute. It now has
15 tests, one of which sweeps the RGB cube to confirm the transform still tops
out at the 35,215 its threshold is expressed against — the constant everything
else on that page is scaled by, and previously a number nothing could disagree
with. `rc/rowPlan.ts` holds the rest: a row against a reference is a list of
steps, each of which either already knows what its chip says or names the two
images to measure, so the five ways a cell can end up without a number are a
table rather than a promise chain.

`<cp-spec-compare>` is the first port whose hardest part was not arithmetic but
a question of authority. Three sources want to pick the spec lane's view — the
address bar, the design-spec chip, and the visitor — and they are not equal: an
explicit choice latches and never clears, a named view in the URL *is* an
explicit choice, and the chip's request is therefore only ever a default that is
spent the moment it is used. Get any of that backwards and the bug is a view
that silently changes under the reader, which no screenshot shows.
`spec/views.ts` makes it a reducer with a table of cases; the element is left
holding canvases. `spec/sameOrigin.ts` came out with it — the guard on every URL
reaching `drawImage`, now tried against the schemes it exists to refuse.

`compare/api.ts` arrived at the same time, and is worth knowing about: two
components now reach for `window.ComposePreviewCompare`, and declaring it in each
was two `Window` augmentations of one property, which TypeScript rejects outright.
One declaration, one typed handle. It is NOT a port of `format-compare.js` —
that file still owns the metric.

Next, cheapest seam first: `catalog-live.js` (400), `inspect.js` (424),
`design-page.js` (789). The big ones — `format-compare.js` (1,706) and
`viewer.js` (3,127) — want breaking into pure modules the way the drawers were,
not porting whole.

## Two bundles, and which one a thing belongs in

`serve-components.js` carries the Lit elements and is emitted by the surfaces
whose markup contains their tags. `serve-chrome.js` carries what *every* page
needs — `window.cpUrlState` and the Page theme setting — and the shell
(`ServeWeb.document`) emits it unconditionally, as the first thing in `<body>`.

The split exists because those two answer different questions, and the numbers
are lopsided enough that one bundle could not serve both:

| | raw | gzip |
| --- | --- | --- |
| `serve-components.js` | 36 kB | 12 kB |
| `serve-chrome.js` | 2 kB | 1 kB |

Almost all of the component bundle is Lit. Putting that on the front door would
undo the reason its imagery is prebaked — a visit should cost the HTML and
nothing else. Neither chrome module is a custom element, so that bundle carries
no Lit at all and is *smaller* than the two files it replaced (`url-state.js` +
`page-theme.js` were 3.6 kB gzipped between them). Every page got cheaper.

It also settles load order in one place. `window.cpUrlState` has to exist before
the component bundle — `backgroundChoice.ts` reads it as `<cp-bg-toggle>`
upgrades — and before `format-compare.js`,
which read it at their own IIFE time. One shell tag ahead of everything replaces
four per-surface `url-state.js` tags that each had to be kept in the right
place.

**So: a custom element goes in `main.ts`. A global, or anything the page shell
needs on every surface, goes in `chrome.ts` — and stays free of Lit, or the
front door pays for it.** If a chrome module ever does need an element, that is
the moment to ask whether it is really chrome.

One consequence worth knowing before you write one: a chrome module is evaluated
*before* the document is parsed. Publish the API at evaluation and defer any DOM
wiring to `DOMContentLoaded`, as `pageTheme.ts` does. It used to get a parsed
document for free by sitting last in `<body>`; that was never stated, and it is
the only thing that had to change to move it.

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
