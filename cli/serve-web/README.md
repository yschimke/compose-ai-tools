# `serve-web` — Vue components for the preview server

The browser side of `compose-preview serve`, as typed, tested Vue components
instead of hand-rolled IIFEs in `assets/*.js`.

Built to a single committed bundle at
`cli/serve/src/main/resources/ee/schimke/composeai/cli/serve/assets/serve-components.js`,
which `ServeWebAssets` then serves like any other static asset.

```
npm install
npm run build       # rebuild the committed bundle
npm run watch       # rebuild on save
npm test            # mocha + happy-dom
npm run verify      # typecheck + test + build + assert the bundle is committed
```

## Why Vue, without a client-side application shell

The server still owns page structure and useful no-JavaScript content. Vue patches the small
reactive controls and data-driven bands in light DOM through `VueElement`; behavior-only custom
elements extend `ControllerElement` and enhance the server's markup directly. This keeps one
Kotlin/TypeScript boundary without mounting an empty Vue app around every page controller.

The migration is atomic: the production bundle contains Vue and no Lit runtime. Vue's compile-time
feature flags in `esbuild.mjs` remove unused Options API, devtools, and verbose hydration code.

## Tooling

The package keeps the existing TypeScript, esbuild, mocha + happy-dom, and preview-harness capture
pipeline. Vue is integrated at the renderer boundary, so the Gradle build remains Node-free and
the committed browser assets retain the same delivery model.

## The constraint that shapes everything here: this is a *server-rendered* surface

The VS Code webview renders itself from scratch on the client. `serve` does not,
and must not:

- It is a **public web server**. Pages are crawled, unfurled into link cards,
  and cached. The front door's imagery is prebaked precisely so a visit costs
  the HTML and nothing else.
- The **committed page fixtures are the regression net.** `ServeWebFixtureTest`
  renders `ServeWeb`'s pages to
  `preview-server/preview-harness/fixtures/pages/*.html`, and
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
export class BgToggle extends VueElement {
    protected renderVue(): VNode { /* serve.css reaches the light DOM */ }
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

This bundle is loaded whole. Vue's renderer is shared by every markup-owning
element, an element whose tag is not on the page costs only its bytes, and the
single bundle lets components import each other without a second runtime.

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
   cd preview-server/preview-harness
   npm run harness:pages
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

`compare/api.ts` arrived at the same time, and is worth knowing about: several
components reach for `window.ComposePreviewCompare`, and declaring it in each was
two `Window` augmentations of one property, which TypeScript rejects outright. One
declaration, one typed handle — and now that the scorer is TypeScript too, that
handle's SHAPE is taken from the implementation (`typeof scoreImages`, and so on)
rather than restated beside it, so a changed signature is a type error at every
call site instead of a global that answers `undefined` at runtime.

`<cp-inspect-layers>` is the port where the extracted rules were least visible
and most load-bearing. A rectangle drawn over a component looks equally right
whether it is the correct node, a duplicate of its parent, or a finding that was
silently dropped — so `inspect/entries.ts` is where the accessibility pass now
lives, with each case named after the wrong picture it prevents. Three are worth
knowing: `merged` is ABSENT on the wire when true (read as `!merged`, every
unmerged inner Text stacks a second box on its ancestor's pixels); a finding
whose bounds match no node is still a real problem and is surfaced rather than
dropped; and a too-small touch target is a warning even when nothing else flagged
the node, because `info` there reads as a pass.

`<cp-catalog-live>` is the grid's long-press live lane. Its risk was in
`live/pointerMap.ts`: the canvas is `object-fit: contain`, so a frame whose
aspect differs from the thumbnail slot is letterboxed inside it, and scaling a
press against the element's bounding rect rather than the frame's painted rect
offsets every coordinate by the margin — no error, an identical-looking card,
and presses reaching a different widget. A press in the margin is refused rather
than clamped, because clamping invents one on whatever sits at the frame's edge.

It also ended a divergence a comment claimed did not exist: the grid said it
explained a refused lane "in the same words" as the viewer, and its fallback —
the branch that fires most — was two-thirds of the viewer's. Nothing rendered
that surface, which is how it drifted, so the port added a `live-refused`
harness state alongside the fix. `sameOrigin` moved to `dom/` and is now shared
rather than copied, with a separate, stricter guard for navigation.

`<cp-design-page>` was the largest port and the one with the most extractable
arithmetic: `design/ink.ts` fits our render's DRAWN PIXELS onto the design's
drawn box (plain `contain` fits canvas-to-ink, which shrinks every component by
its own transparent margin — 4% to 42% on one catalog page, read as "everything
scales when you flip the lane"); `design/score.ts` holds the badge, where both
ways of conflating drift with proportion difference have already been shipped
once; `design/geometry.ts` holds the slot/crop/tip placement, including the
zoom-invariance that lets a zoom skip re-measuring entirely.

Its one non-obvious hazard is worth remembering for `viewer.js`, which has the
same shape: `design-page.js` read `window.ComposePreviewCompare` at IIFE time
from a script tag emitted AFTER `format-compare.js`, but the components bundle
comes before it. An element caching that handle on upgrade caches `null` and the
diff lane silently scores nothing. `<cp-design-page>` reads it when the lane is
entered instead, so no script order can break it.

`format-compare.js` came apart in four steps rather than porting whole, because
four consumers read that file by path or basename and could not all move at once
— the publish-time score driver, the Chromium scorer spec, the compare audit's
route stub, and the harness's asset list.

1. The dead SSIM block, and the shared geometry threshold.
2. The `/compare` wall as `<cp-compare-wall>`, with `compare/pairing.ts` (which
   two artifacts a row pairs — never the opposite baked theme, because a
   plausible-looking pair scores a number that means nothing), `compare/state.ts`
   (the URL beats the remembered theme, and a format the catalog does not publish
   is ignored rather than emptying the table), `compare/wallRows.ts` and
   `compare/grade.ts`.
3. The reference page as `<cp-reference-compare>`, with the annotation engine in
   `annotate/`.
4. The primitives themselves.

Step 4 did NOT need the shim it was planned around. `format-compare.js` still
exists, at the same path, publishing the same global — it is simply *generated*
now, from `src/scorer/`, as a fourth esbuild bundle. All four consumers depend on
the path and on `window.ComposePreviewCompare`, and both are unchanged, so none
of them had to move at all.

What that bought is the point: the metric every design-parity surface is built on
— the badge on a catalog chip, the verdict in the spec lane, the ordering of the
compare wall — had **no test of any kind**, because it lived inside an IIFE
behind a canvas. Everything that decides a number is now DOM-free and pinned:
`scorer/planes.ts` (the edge-tolerant search, in both directions, because
otherwise the same pair scores differently depending on which frame you pass
first), `scorer/contentBox.ts` (two shipped regressions live behind these rules,
both silent — guessing an opaque backdrop from the corner pixel stripped a bled
card's own surface, and cropping a near-empty capture stretched one heading
across the whole comparison), `scorer/deltaMap.ts` and `scorer/svgTranslate.ts`.
Only `scorer/frames.ts` needs a browser, and it does nothing but decode,
downscale, sample and hand off.

`viewer.js` (3,151) was last, and went the way `format-compare.js` went in step
4: same path, same script tag, generated from `src/viewer.ts` rather than
hand-written. It is **not** a Vue element and deliberately so — the viewer
renders no markup of its own. Every control on the page is server-rendered by
`ServeWeb.viewerPage`, so a `render()` returning nothing would be ceremony, and
moving the markup into a template would be a rewrite of the server page rather
than a port of this file. What the move buys is the type check over 3,000 lines
of lane machinery and the retirement of the last legacy seam: the five DOM-free
slices that had been extracted ahead of it (`viewer/fit.ts`, `keyInput.ts`,
`laneState.ts`, `ownedParams.ts`, `renderQuery.ts`, `themeChoice.ts`) reached it
through a `window.cpViewerQuery` global that existed *only* because the caller
lived in another build. It imports them now, and the global is gone.

It reads `window.ComposePreviewCompare` and `window.cpSpecCompare` exactly the way
the design page did, and keeps the same read-it-late treatment: both handles are
looked up at call time, never cached at load, because no script order guarantees
which bundle evaluated first.

The one thing to know before editing it: the served asset is **minified**, so the
Kotlin assertions that pin how the viewer is written read
`cli/serve-web/src/viewer.ts` through `viewerSource()` in the test source, not
`ServeWebAssets.load("viewer.js")`. Pointing them at the bundle would not merely
fail — a *negative* assertion ("the viewer must no longer spell it the old way")
is vacuously true against minified text, so it would retire itself silently.

One thing worth knowing about `grade`: the 90/75 bands are NOT the spec lane's
95/85, and unifying them would make one of the two surfaces lie. A wall
triaging dozens of rows and a lane judging one chosen pair are different
questions about the same number.

## The bundles, and which one a thing belongs in

`serve-components.js` carries the Vue elements and DOM controllers and is emitted by the surfaces
whose markup contains their tags. `serve-chrome.js` carries what *every* page
needs — `window.cpUrlState` and the Page theme setting — and the shell
(`ServeWeb.document`) emits it unconditionally, as the first thing in `<body>`.
`format-compare.js` carries the comparison scorer and is emitted only by the
surfaces that measure something; it keeps its own name because two consumers
outside the browser load that exact path.

The split exists because they answer different questions, and the numbers are
lopsided enough that one bundle could not serve all three:

| | raw | gzip |
| --- | --- | --- |
| `serve-components.js` | 189 kB | 61 kB |
| `viewer.js` | 57 kB | 18 kB |
| `known-differences.js` | 104 kB | 39 kB |
| `format-compare.js` | 9 kB | 4 kB |
| `serve-chrome.js` | 8 kB | 3 kB |

Putting the component bundle on the front door would undo the reason its imagery
is prebaked — a visit should cost the HTML and nothing else. Neither chrome
module is a custom element, so that bundle carries no Vue at all and is *smaller*
than the two files it replaced (`url-state.js` + `page-theme.js` were 3.6 kB
gzipped between them). Every page got cheaper.

The scorer is its own bundle for the opposite reason: it is small, but only the
handful of surfaces that measure something need it, and folding it in would put
it on every page carrying a Vue element. Keeping the filename is what lets the
two out-of-browser consumers go on loading it by path.

It also settles load order in one place. `window.cpUrlState` has to exist before
the component bundle — `backgroundChoice.ts` reads it as `<cp-bg-toggle>`
upgrades — and before `format-compare.js`,
which read it at their own IIFE time. One shell tag ahead of everything replaces
four per-surface `url-state.js` tags that each had to be kept in the right
place.

**So: a custom element goes in `main.ts`. A global, or anything the page shell
needs on every surface, goes in `chrome.ts` — and stays free of Vue, or the
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
  those callers do. `window.cpViewerQuery` was the same shape and is the worked
  example: its only caller was `viewer.js`, so the seam went the moment that
  caller moved into this package, and the rules it published are plain imports
  now. `cpRcFonts` stays because the inline doc-player script has not moved.
- **A page-sized script that renders no markup of its own** (`viewer.js`, and
  `format-compare.js` before it): not an element either, and not a candidate to
  become one. It keeps its path, its script tag and its position in the load
  order, and only its *source* moves — into `src/`, type-checked, importing the
  DOM-free rules directly. Reach for this when the server already renders every
  control and the script is behaviour over that markup at page scale.

Whichever shape it is, the same rule holds for the elements the server emits:
they are declared in the page's HTML, so a fixture regeneration and a pixel
check are still part of the change.
