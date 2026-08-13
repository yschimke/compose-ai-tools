// Headless capture of *standalone HTML page* fixtures — currently the
// `compose-preview serve` web surfaces (`ServeWeb.landingPage` /
// `viewerPage`), committed under `fixtures/pages/*.html` by the Kotlin
// `ServeWebFixtureTest`.
//
// This reuses the panel harness's *approach* (Playwright + the `_server.mjs`
// static server + per-`(fixture × theme)` PNGs in `out/`, diffed by the
// generic `vscode-preview-diff.py` bot) without its *invocation*: these
// pages are self-contained documents, so we navigate to them directly and
// drive theme via `prefers-color-scheme` emulation, instead of booting
// `scenario.html` and replaying webview messages.
//
// Output filenames match the rest of the harness (`<fixture>.<theme>.png`)
// and land in the same `out/` dir, so they're picked up by the existing
// `harness:snapshot` invocation (the `snapshot` path filter matches this
// file too) and the existing baseline/PR-comment actions — no CI change.

import { test, expect } from "@playwright/test";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
import { readdirSync, readFileSync } from "node:fs";
import { listThemes } from "./_fixtures.mjs";

const harnessDir = dirname(fileURLToPath(import.meta.url));
const outDir = resolve(harnessDir, "out");
const pagesDir = resolve(harnessDir, "fixtures", "pages");
const serveAssetsDir = resolve(
    harnessDir,
    "..",
    "..",
    "cli",
    "src",
    "main",
    "resources",
    "ee",
    "schimke",
    "composeai",
    "cli",
    "serve",
    "assets",
);

// The serve pages point `<img>` / their viewer JS at two image lanes with no
// backend in the harness: the daemon's `/render/<id>.png` endpoint, and the
// front door's prebaked `/hero/<system>/<hash>.png` thumbnails. Stub both with
// the committed placeholder so the capture is deterministic and tiles render at
// a realistic size instead of collapsing on broken images.
const renderPlaceholder = resolve(pagesDir, "_render-placeholder.png");
const renderSvgPlaceholder = resolve(pagesDir, "_render-placeholder.svg");
// The `?exploded=1` lane's stub. Unlike the flat placeholders above this one is NOT hand-drawn:
// `ExplodedSvgFixtureTest` generates it from `_render-placeholder-layered.svg` through the
// production `ExplodedSvg` renderer and commits the result, so the picture the diff bot posts for
// the exploded viewer is the real projection — every change to the camera, the sheet split, or the
// labels moves this baseline on its own.
const renderExplodedPlaceholder = resolve(pagesDir, "_render-placeholder-exploded.svg");
// Fixtures navigated with a query string, because the state they capture lives in the URL rather
// than in the served markup. The exploded viewer is the deep-link case in full: `?exploded=1` is
// what puts the page on the vector lane and presses the 3D chip, exactly as a shared link does.
const FIXTURE_QUERY = { "serve-viewer-exploded": "?exploded=1" };
const IMAGE_LANES = [
    "**/render/**",
    "**/hero/**",
    "**/reference/**",
    "**/rc-compare/**",
    // The design-page lane, which now feeds only the index card's thumbnail: the page VIEW inlines
    // its export, so there is no request to intercept there at all. It still needs its own stand-in
    // rather than the component placeholder, because a specimen sheet is wider than it is tall and
    // the card crops to the top of it.
    "**/pages/*.svg**",
];
const REFERENCE_PLACEHOLDER = `
<svg xmlns="http://www.w3.org/2000/svg" width="200" height="420" viewBox="0 0 200 420">
  <rect width="200" height="420" rx="20" fill="#f4efff"/>
  <rect x="20" y="28" width="160" height="42" rx="12" fill="#7657b5"/>
  <rect x="20" y="92" width="116" height="18" rx="9" fill="#5f5968"/>
  <rect x="20" y="132" width="160" height="104" rx="18" fill="#dfd2f5"/>
  <rect x="20" y="258" width="160" height="64" rx="18" fill="#ffffff"/>
  <circle cx="100" cy="378" r="18" fill="#7657b5"/>
</svg>`;
// A specimen sheet standing in for the exported design page, at the fixture's own 1200×800 user
// units and with the same shapes in the same places — the index card crops to the top of it, so a
// stand-in with no recognisable content would make that card's baseline meaningless. The page VIEW
// does not use this: it inlines the export the fixture HTML already carries.
const PAGE_PLACEHOLDER = `
<svg xmlns="http://www.w3.org/2000/svg" width="1200" height="800" viewBox="0 0 1200 800">
  <rect width="1200" height="800" fill="#F7F2FA"/>
  <rect x="40" y="32" width="1120" height="64" rx="16" fill="#EADDFF"/>
  <circle cx="180" cy="300" r="90" fill="#6750A4"/>
  <rect x="330" y="210" width="180" height="180" rx="36" fill="#6750A4"/>
  <path d="M690 210 L780 390 L600 390 Z" fill="#6750A4"/>
  <rect x="840" y="255" width="240" height="90" rx="45" fill="#6750A4"/>
  <rect x="330" y="500" width="180" height="180" rx="90" fill="#6750A4"/>
  <rect x="600" y="500" width="180" height="180" fill="#6750A4"/>
</svg>`;
// The viewer's inspection lanes (`/render/<id>.a11y`, `/render/<id>.annotations`) are daemon data
// products, so like the image lanes they have no backend here. These stand in for them, shaped
// exactly as `ServeRenderHost.renderA11y` / `renderAnnotations` emit them and with bounds inside
// the 200×420 placeholder render, so the captured overlay is the production drawing code fed
// production-shaped data. The mix is deliberate: a clean focus stop, one carrying an ATF error, and
// one whose touch target is undersized — the three states the legend has to tell apart.
const A11Y_PAYLOAD = {
    previewId: "com.example.ProfileCardPreview",
    nodes: [
        { label: "Ada Lovelace", role: "TextView", states: [], boundsInScreen: "20,28,180,70" },
        {
            label: "Follow",
            role: "Button",
            states: ["clickable"],
            boundsInScreen: "20,132,110,168",
        },
        { label: "", role: "ImageView", states: [], boundsInScreen: "20,258,60,298" },
        {
            label: "Share profile",
            role: "Button",
            states: ["clickable"],
            boundsInScreen: "120,258,168,290",
        },
    ],
    findings: [
        {
            level: "ERROR",
            type: "SpeakableTextPresentCheck",
            message: "View is missing speakable text needed for a screen reader",
            viewDescription: "ImageView",
            boundsInScreen: "20,258,60,298",
        },
    ],
    touchTargets: [
        {
            nodeId: "7",
            boundsInScreen: "120,258,168,290",
            widthDp: 24,
            heightDp: 16,
            findings: ["TouchTargetTooSmall"],
        },
    ],
};
const ANNOTATIONS_PAYLOAD = {
    previewId: "com.example.ProfileCardPreview",
    annotations: [
        {
            kind: "typography",
            bounds: { x: 20, y: 28, width: 160, height: 42 },
            label: "22.0sp/28.0sp · Roboto · 500",
            role: "Ada Lovelace",
        },
        {
            kind: "typography",
            bounds: { x: 20, y: 92, width: 116, height: 18 },
            label: "14.0sp/20.0sp · Roboto · 400",
            role: "Analytical engine",
        },
        {
            kind: "theme",
            bounds: { x: 20, y: 132, width: 160, height: 104 },
            label: "fill #FF6750A4 · radius 18.0dp",
            role: "Card",
        },
        {
            kind: "theme",
            bounds: { x: 20, y: 258, width: 160, height: 64 },
            label: "fill #FFFFFBFE · radius 18.0dp · border 1.0dp #FF79747E",
            role: "OutlinedCard",
        },
    ],
};

// Fixtures captured with the REAL production CSS/JS routed in, rather than the older static-page
// contract (bare HTML). Anything whose whole point is how the page is *painted* has to be here —
// the catalog-palette pair exists to show a served design system re-theming the chrome from its own
// `tokens.dtcg.json`, which is invisible without the stylesheet the palette overrides.
const STYLED_FIXTURES = new Set([
    "serve-format-compare",
    // The Remote Compose player wall. Its whole claim is a LAYOUT — one column per player, a diff
    // growing inside a player's own column — and it is only visible at all once `format-compare.js`
    // switches the format pane, so it needs both the stylesheet and the scripts routed in.
    "serve-rc-lanes",
    "serve-reference-compare",
    "serve-viewer-catalog-knobs",
    "serve-landing-catalog-palette",
    "serve-viewer-catalog-palette",
    // The render-server badge is a `.cp-daemon-status` pill whose whole diffable claim is its
    // STYLING — that "not running" reads as neutral information rather than a fault. Without the
    // real stylesheet routed in, `/assets/serve/.../serve.css` 404s and the daemon captures shoot
    // an unstyled span, so a change to that styling would move no baseline at all.
    "serve-landing-declared-themes",
    // Its IR-replay twin, whose whole claim is a control that ISN'T there: a catalog replayed from
    // captured documents keeps the baked Light/Dark pair and drops the declared-theme chips, since
    // a theme provider needs a composition to wrap and the server refuses that render 409. An
    // absent chip is a purely visual claim — captured bare, the toolbar it belongs to isn't even
    // laid out, so a regression that put the chips back would move no baseline at all.
    "serve-landing-ir-replay-themes",
    // The front door is a *layout*: publisher sections, the card grid's density, and the card
    // chrome (hero region, meta rhythm, hover affordance) are the whole page. Captured bare it
    // shot an unstyled column of links, so a change to any of that moved no baseline at all —
    // which is exactly how the section spacing and the card hover reached production unreviewed.
    "serve-home-index",
    // The catalog landing is the same claim one level down: the navigation, the group headings and
    // the preview-card grid ARE the page, and its cards share the front door's hover treatment.
    "serve-landing-public",
    // The sectioned catalog, and the only fixture that renders the navigation TREE. Every claim it
    // makes is painted: the sidebar standing beside the grid at all (a CSS grid above 960px), the
    // selected section's pill, the twisty that says a branch is open, the guide line down its
    // sub-groups, and the `aria-current` rule the scroll-spy moves. Captured bare — which is how it
    // was captured while it was a tab bar — the whole surface is a nested list of unstyled links
    // and none of that would move a baseline. Its two states below are the ones the tree exists
    // for.
    "serve-landing-sections",
    // The tree at full depth, and the only fixture that reaches its lower two levels: a group
    // opening onto its components, and a component onto the primary-axis variants the grid folds
    // out. Every claim it makes is painted — the nesting rails, the twisty on a component row, the
    // lighter treatment that separates a variant (which leaves the page) from a component (which
    // scrolls the grid). Captured bare it is a nested list of unstyled links.
    "serve-landing-tree-depth",
    // The section-less catalog, which is the shape most published design systems are in. Its whole
    // change is navigational and therefore visual — it went from a bare wall of cards with no
    // navigation at all to an outline tree beside them — so captured bare it would move nothing.
    "serve-landing-grouped",
    // The Wear viewer exists for ONE claim: the Size panel offers watch shapes and no Orientation
    // row. That claim lives entirely inside the override drawer, so it has to be painted by the
    // real stylesheet — captured bare it is a column of unstyled labels where a panel regression
    // moves nothing recognisable. Its `size-open` state below is what actually shows the menu.
    "serve-viewer-wear-screen",
    // The signed-out live lane. Its whole claim is how the chip is PAINTED: full contrast with a
    // dashed keyline reading as "an action to take", versus the 50%-opacity `:disabled` chip that
    // read as "not available here". Captured bare it is an ordinary underlined link, so the styling
    // that IS the change would move no baseline — the exact trap the entries above record.
    "serve-viewer-signin",
    // The only viewer fixture that carries app-declared themes, and so the only one where the
    // viewer bar's Theme chips are more than the Day/Night pair. That row IS the control now (the
    // `#cp-theme` select behind it is visually removed), and which chips are pressed / greyed is
    // decided by viewer.js at runtime — captured bare it is an unstyled run of buttons in which
    // neither the pill treatment nor the enabled-state sync moves a baseline at all.
    "serve-viewer-themes",
    // The grid's long-press live lane. Both halves of its claim are styling: the "hold for live"
    // affordance that appears under a pointer, and the canvas overlay + accent chip a streaming
    // card wears. Captured bare there is no overlay at all — the script never loads — so the whole
    // feature would move no baseline.
    "serve-landing-live",
    // The design-spec lane. meshcore-mobile is the catalog that publishes Figma-backed references,
    // so its viewer is where the spec becomes one of the renderer options — and the claim is
    // visual: selecting it swaps the stage, renames the chip, and drops the "spec diff →" step
    // beside it. Captured bare, the row is an unstyled select and the `spec-lane` state below
    // could not even be entered (the lane needs `viewer.js`).
    "serve-viewer-path",
    // The inspection layers (accessibility / typography / theme attributes). The whole surface is
    // painted at runtime by `inspect.js` — boxes over the stage, a legend beside it — so captured
    // bare there is nothing to see at all. Its `layers` state below is what actually draws them.
    "serve-viewer-inspect",
    // The exploded 3D view. Everything it claims is produced at runtime: `viewer.js` reads
    // `?exploded=1`, presses the 3D chip, switches the stage to the vector lane and fetches the
    // exploded SVG, and the camera sliders in the drawer are laid out by `serve.css`. Captured
    // bare, the page shows an unpressed button over a flat placeholder and neither the projection
    // nor the controls would move a baseline.
    "serve-viewer-exploded",
    // The Remote Compose viewer, and the page the renderer picker exists for: five players for one
    // captured document. The picker's whole claim is visual — one chip naming the current renderer
    // beside one combo of alternatives, where a row of six pressed-state chips used to be — so
    // captured bare it is an unstyled button next to an unstyled select and nothing about the
    // simplification would move a baseline. `viewer.js` is needed too, for the `player-cmp-android`
    // state below.
    "serve-viewer-rc-players",
    // The playground handoff this host cannot honour. Its whole claim is a NOTICE — an
    // error-container panel that says "this server cannot compile against <catalog>" before the
    // visitor spends a compile finding out — and captured bare that is an ordinary paragraph in
    // which neither the panel treatment nor its prominence above the buffer moves a baseline.
    // Deliberately only this fixture and not `serve-playground`: adding the stylesheet there would
    // rewrite an unrelated baseline wholesale for no claim this change makes.
    "serve-playground-uncompilable",
    // The design page is nothing BUT layout: outlines measured off the inlined export and
    // coloured by how each node was linked. Captured bare it is a list of links under a picture and
    // the geometry — the entire claim — moves no baseline. Its `swap` state below is the one the
    // feature exists for.
    "serve-design-page",
]);
const SERVE_ASSETS = [
    ["serve.css", "text/css"],
    ["playground.css", "text/css"],
    ["url-state.js", "text/javascript"],
    ["page-theme.js", "text/javascript"],
    ["bg-toggle.js", "text/javascript"],
    ["viewer.js", "text/javascript"],
    ["viewer-groups.js", "text/javascript"],
    ["viewer-drawers.js", "text/javascript"],
    ["backend-badge.js", "text/javascript"],
    ["format-compare.js", "text/javascript"],
    ["spec-compare.js", "text/javascript"],
    ["rc-lanes.js", "text/javascript"],
    ["catalog-live.js", "text/javascript"],
    ["inspect.js", "text/javascript"],
    ["design-page.js", "text/javascript"],
];

// Runtime *states* of a page fixture that the committed HTML can't express on its own, captured as
// extra shots so they're diffed on every PR like any other fixture. Each entry names a base
// fixture, a filename suffix, and a mutation applied in-page just before the screenshot.
//
// `connecting` covers the viewer's live-lane activation badge: `openStream()` sets `data-pending`
// on `.cp-viewer` while the WebSocket comes up and the backend badge shows `◌ connecting…` (amber)
// instead of the lane label. There's no daemon in the harness, so we set the attribute the same way
// the viewer JS does and let the page's own `MutationObserver` paint the badge — the badge text and
// accent under test are produced by the real `backendBadgeScript`, not faked here.
const FIXTURE_STATES = [
    {
        // The design page with this catalog's renders standing IN PLACE OF the design's own
        // drawing — the state the whole surface exists for, and one the committed HTML cannot hold
        // twice over: the renders ride an inert `<template>` until the toggle adopts them, and
        // every position is measured from the inlined SVG at runtime rather than declared in the
        // markup. Without this shot, a change to the measuring, the swap or the hiding would move
        // no baseline at all.
        fixture: "serve-design-page",
        suffix: "swap",
        apply: async (page) => {
            await page.check("[data-cp-page-renders]");
            // Requires at least one render, not just "all of them loaded" — `every()` over an
            // empty list is true, so without the length check this shot would go green if the
            // swap never armed at all, which is exactly the regression it exists to catch.
            await page.waitForFunction(() => {
                var imgs = Array.from(document.querySelectorAll(".cp-page-render"));
                return imgs.length > 0 && imgs.every((img) => img.complete && img.naturalWidth > 0);
            });
            // And that the design's own drawing actually went away underneath them. A swap that
            // showed our render while leaving theirs visible would look almost right in a diff.
            await page.waitForFunction(
                () => document.querySelectorAll("svg .cp-page-replaced").length > 0,
            );
        },
    },
    {
        // "Only what we don't implement": the coverage read. Everything this catalog implements is
        // muted and the dashed-red outlines — the components on the sheet with no code behind them
        // — are what's left. It is a pure CSS-state claim, so it needs its own shot for the same
        // reason the swap does.
        fixture: "serve-design-page",
        suffix: "unlinked-only",
        apply: async (page) => {
            // States compose on the already-loaded page: undo the swap above so this shot is about
            // the filter alone.
            await page.uncheck("[data-cp-page-renders]");
            await page.check("[data-cp-page-unlinked]");
        },
    },
    {
        // A component opened onto its variants — the state the two deepest levels exist for, and
        // one the committed HTML cannot hold: components ship collapsed, so without this the
        // variant rows would never appear in a baseline at all.
        fixture: "serve-landing-tree-depth",
        suffix: "component-open",
        apply: async (page) => {
            await page.click(
                '.cp-tree-component[data-group="cp-card-button-filled__ideal__default__light"]',
            );
            await page.waitForFunction(
                () =>
                    document
                        .querySelector(
                            '.cp-tree-component[data-group="cp-card-button-filled__ideal__default__light"]',
                        )
                        ?.getAttribute("aria-expanded") === "true",
            );
        },
    },
    {
        // Switching branches. The committed HTML can only ever hold ONE arrangement — the first
        // section selected and open, the rest collapsed — so the thing the tree is for (open
        // another branch, its sub-groups appear, the grid under it changes) is invisible to a
        // baseline without this. It also pins the two halves moving together: the pill and twisty
        // on the row, and the panel the section switching swapped in beside it.
        fixture: "serve-landing-sections",
        suffix: "section-open",
        apply: async (page) => {
            await page.click('.cp-tab[data-tab="components"]');
            await page.waitForFunction(
                () =>
                    document
                        .querySelector('.cp-tab[data-tab="components"]')
                        ?.getAttribute("aria-expanded") === "true",
            );
        },
    },
    {
        // Searching. A query spans every section, so the tree opens every branch that still holds a
        // match and drops the group rows whose sub-group the filter emptied — "device" here matches
        // in Components and Screens and nothing under Themes. That is the one state where the tree
        // shows more than one branch at once, and the rule that a row never survives the
        // destination it points at is exactly the kind of thing that regresses silently.
        fixture: "serve-landing-sections",
        suffix: "filtered",
        apply: async (page) => {
            // States are applied cumulatively to the SAME loaded page, so this one inherits
            // `section-open`'s Components selection. That matters: "device" matches inside
            // Components, so the selected row would survive the filter and the tab-stop assertion
            // below would hold whether or not the fallback it exists for is there at all. Put the
            // page back on Themes first, which the query matches nothing in — the only arrangement
            // in which the selected section is actually hidden.
            await page.click('.cp-tab[data-tab="themes"]');
            await page.waitForFunction(
                () =>
                    document
                        .querySelector('.cp-tab[data-tab="themes"]')
                        ?.getAttribute("aria-selected") === "true",
            );
            await page.fill("#cp-search", "device");
            await page.waitForFunction(
                () =>
                    document
                        .querySelector('.cp-tab[data-tab="screens"]')
                        ?.getAttribute("aria-expanded") === "true",
            );
            // The filter now hides the SELECTED section. The tree's single roving tab stop has to
            // move to a branch still on screen, or Tab skips the whole navigation. Not a pixel
            // claim, so it rides this capture as a wait rather than earning its own shot.
            await page.waitForFunction(() => {
                const themes = document.querySelector(
                    '.cp-tab[data-tab="themes"]',
                );
                // Guards the guard: if the selected section were somehow still visible, this
                // assertion would be trivially true and would pin nothing.
                if (!themes?.closest(".cp-tree-node")?.hidden) return false;
                const reachable = Array.from(
                    document.querySelectorAll(".cp-tab"),
                ).filter((r) => !r.closest(".cp-tree-node")?.hidden);
                return (
                    reachable.length > 0 &&
                    reachable.some((r) => r.tabIndex === 0)
                );
            });
        },
    },
    {
        // The playground editor's multi-file strip (#3017): a snippet is a list of files compiled
        // as one module, and the second file only exists after a click — the committed HTML always
        // opens on one buffer. Clicking "+ file" here means every future PR diffs the multi-file
        // editor (tab strip, active-tab styling, the Remove button appearing) for free.
        fixture: "serve-playground",
        suffix: "multifile",
        apply: async (page) => {
            await page.click("#pg-add-file");
            // `#pg-source` is the *backing* textarea. When the vendored CodeMirror bundle loads it
            // replaces the textarea with its own surface and sets `display: none` on the original,
            // so `page.fill("#pg-source", …)` waits forever on a hidden element. That used to pass
            // only because the harness served no assets, i.e. the scenario was exercising the
            // bundle-absent fallback rather than the page a user sees. Drive whichever is actually
            // live — the playground supports both states by design (see playground.css).
            await page.evaluate((src) => {
                const ta = document.getElementById("pg-source");
                if (!ta) return;
                const wrapper = ta.nextElementSibling;
                const cm = wrapper && wrapper.CodeMirror;
                if (cm) {
                    cm.setValue(src);
                    cm.save(); // mirror back into the textarea the page reads on submit
                } else {
                    ta.value = src;
                    ta.dispatchEvent(new Event("input", { bubbles: true }));
                }
            }, "val Brand = 0xFF6750A4");
        },
    },
    {
        // The playground's runtime catalog selector: a catalog's bundle backend decides its
        // renderer, so picking an Android catalog re-derives the Mode control rather than leaving a
        // mode the host would refuse. The committed HTML always opens on the first entry, so the
        // *derivation* — the thing worth diffing — only exists after a selection. Making it a
        // captured state means every future change to the bar (and to the mode labels) is diffed.
        fixture: "serve-playground",
        suffix: "android-catalog",
        apply: async (page) => {
            // States compose on the already-loaded page, so undo the multi-file state above first —
            // this shot is about the bar, and a stray second tab in it would read as part of the
            // selector's behaviour.
            await page.click("#pg-remove-file");
            await page.selectOption("#pg-catalog", "compose-wear");
        },
    },
    {
        // The multi-preview result list. A snippet routinely declares several `@Preview`s; the
        // still frame draws one and the rest are reachable through `?preview=<id>` on the same
        // redeemed session. That list only exists after a successful Run, so the committed HTML
        // can never show it — stubbing the compile response is the only way to diff it. The links
        // and their labels are built by the page's own JS from the response; this supplies only
        // the JSON the real server would have sent.
        fixture: "serve-playground",
        suffix: "multi-preview",
        apply: async (page) => {
            await page.route("**/api/1/compiler/run*", (route) =>
                route.fulfill({
                    status: 200,
                    contentType: "application/json",
                    body: JSON.stringify({
                        diagnostics: [],
                        previewToken: "pg_fixture",
                        previewUrl: "/pg/pg_fixture",
                        previewId: "com.example.SnippetKt.Greeting",
                        previews: [
                            "com.example.SnippetKt.Greeting",
                            "com.example.SnippetKt.GreetingDark",
                            "com.example.BrandKt.BrandSwatches",
                        ],
                    }),
                }),
            );
            await page.click("#pg-run");
            await page.waitForFunction(
                () => document.getElementById("pg-previews")?.hidden === false,
            );
        },
    },
    {
        // Every inspection layer on at once: the accessibility focus map, the resolved typography,
        // and the resolved theme attributes, drawn as numbered boxes over the stage with the legend
        // beside it. This is the state the feature exists for and the committed HTML cannot hold it
        // — the boxes only exist once `inspect.js` has fetched the (stubbed) data products — so
        // without this shot a change to the overlay or legend would move no baseline at all.
        fixture: "serve-viewer-inspect",
        suffix: "layers",
        apply: async (page) => {
            // The Overrides drawer's groups open on demand; the checkboxes aren't clickable (or
            // visible in the shot) until the Overlays group is expanded.
            await page.click('[data-cp-group="overlays"] > summary');
            await page.check("#cp-inspect-a11y");
            await page.check("#cp-inspect-typography");
            await page.check("#cp-inspect-theme");
            await page.waitForFunction(
                () => document.querySelectorAll(".cp-inspect-box").length > 0,
            );
        },
    },
    {
        // Going BACK to a history entry on which no theme was ever picked. The viewer opens showing
        // the preview's baked theme with `data-theme-active="0"` — displayed, not chosen — so the
        // chrome follows the OS. Picking a theme pins it; returning to that first entry has to
        // un-pin it again, and nothing else in this spec shoots a popstate, so a regression here
        // would move no baseline at all.
        //
        // Captured under both emulated OS preferences on purpose: "followed the OS" is only
        // distinguishable from "pinned to the baked default" when the two shots disagree with each
        // other. On the unfixed code they agree — both pinned dark — which is what the diff shows.
        fixture: "serve-viewer-themes",
        suffix: "theme-back-unpinned",
        apply: async (page) => {
            // Light, because this fixture's baked default is already dark: picking dark is a no-op
            // the bar handler drops, and would shoot the page that was there anyway.
            await page.click('[data-theme-choice="light"]');
            await page.waitForFunction(() =>
                document.documentElement.classList.contains("cp-scheme-light"),
            );
            await page.goBack();
            // Settle rather than wait for the un-pinned state. Waiting on the FIXED behaviour would
            // time out when the bot renders the base branch, turning a visual diff into a harness
            // error — and the whole point is that both sides capture so the pixels can disagree.
            await page.waitForTimeout(300);
        },
    },
    {
        // The page theme following the SELECTED PREVIEW THEME (the "Page theme" setting, on by
        // default). Picking Dark repaints the chrome as well as the grid, and the claim is
        // invisible to the two ordinary shots: they are captured under an emulated OS preference,
        // so the light one is exactly the page that used to frame a dark grid in a light shell.
        // Clicking the chip here means the pinned mode — chrome, catalog palette and badges — is
        // diffed on every PR, in BOTH OS preferences (the `.light` shot is the one that proves the
        // pin beats `prefers-color-scheme`).
        fixture: "serve-landing-catalog-palette",
        suffix: "theme-sync",
        apply: async (page) => {
            // Always pick the chip OPPOSITE the emulated OS preference. The grid's initial chip
            // already follows `prefers-color-scheme`, so picking the matching one would be a no-op
            // and shoot the page that was there anyway; the opposite one is the whole claim.
            const wantDark = await page.evaluate(
                () => !window.matchMedia("(prefers-color-scheme: dark)").matches,
            );
            await page.click(`[data-theme-choice="${wantDark ? "dark" : "light"}"]`);
            await page.waitForFunction(
                (cls) => document.documentElement.classList.contains(cls),
                wantDark ? "cp-scheme-dark" : "cp-scheme-light",
            );
        },
    },
    {
        // The Settings menu itself, open, with the setting being turned off — the control has to be
        // shot open or its contents are diffed by nothing at all.
        fixture: "serve-landing-catalog-palette",
        suffix: "theme-sync-menu",
        apply: async (page) => {
            await page.click(".cp-settings > summary");
            await page.check('[data-cp-page-theme][value="system"]');
            await page.waitForFunction(
                () =>
                    !document.documentElement.className.includes("cp-scheme-"),
            );
        },
    },
    {
        // …and the same page with the menu closed: the setting off is exactly the behaviour that
        // shipped before it existed — a dark grid inside a light shell — so this is the honest
        // before-picture as well as the opt-out's own baseline.
        fixture: "serve-landing-catalog-palette",
        suffix: "theme-sync-off",
        apply: async (page) => {
            await page.evaluate(() =>
                document.querySelector(".cp-settings").removeAttribute("open"),
            );
        },
    },
    {
        // Card hover. A hover state exists only under a pointer, so it is invisible to an ordinary
        // end-state screenshot — and it is the front door's main affordance: the tile lifts, takes
        // an accent rim and a top-edge wipe, and eases its artwork rather than underlining four
        // lines of metadata. Hovering one card here means every future change to that treatment is
        // diffed like any other pixel. Transitions are disabled first so the shot lands on the
        // settled state instead of racing the ease.
        fixture: "serve-home-index",
        suffix: "card-hover",
        apply: async (page) => {
            await page.addStyleTag({
                content: "*, *::before, *::after { transition-duration: 0ms !important; }",
            });
            await page.hover(".cp-syslist .cp-card");
        },
    },
    {
        // The same treatment on a catalog's preview cards, where the tiles are small and dense —
        // the case where an underline sweeping four lines of metadata was worst.
        fixture: "serve-landing-public",
        suffix: "card-hover",
        apply: async (page) => {
            await page.addStyleTag({
                content: "*, *::before, *::after { transition-duration: 0ms !important; }",
            });
            await page.hover(".cp-grid .cp-card");
        },
    },
    {
        // The design-spec lane with the spec actually on the stage. The committed HTML always
        // opens on the render — the imported reference is only fetched once the lane is entered —
        // so this is the only way the lane's *end state* is diffed: the lit Figma chip, the
        // "imported design spec — not a render" hint, the ◇ badge, and the reference filling the
        // stage where the render was. Entered through `#cp-spec-chip`, the lane's own top-level
        // control (it used to be an `<option>` in the renderer combo). The raster comes from the
        // harness's existing `**/reference/**` stub, so no design tool is contacted here either.
        fixture: "serve-viewer-path",
        suffix: "spec-lane",
        apply: async (page) => {
            await page.click("#cp-spec-chip");
            await page.waitForFunction(
                () => document.getElementById("cp-spec-img")?.hidden === false,
            );
        },
    },
    // The three comparison views the spec lane offers once it is up. Each is drawn entirely at
    // runtime — pre-normalised canvases painted by `spec-compare.js` — so the committed HTML holds
    // four empty `<canvas>` elements and none of what these views actually look like. Capturing
    // them here is what puts the diff colouring, the triptych's three-up rhythm and the wipe's seam
    // under the visual-diff bot, so a later change to any of them moves a baseline instead of
    // landing unreviewed. They chain off `spec-lane` above (same page, applied in order), and both
    // frames come from the harness's existing `**/reference/**` and `**/render/**` stubs — no
    // design tool and no daemon are contacted.
    ...["diff", "triptych", "slider"].map((view) => ({
        fixture: "serve-viewer-path",
        suffix: `spec-${view}`,
        apply: async (page) => {
            await page.click(`[data-cp-spec-view="${view}"]`);
            // The comparison is asynchronous (two decodes, a normalisation pass, then SSIM), so
            // hold for the settled readout rather than shooting the "comparing…" frame.
            await page
                .waitForFunction(() => {
                    const score = document.getElementById("cp-spec-score");
                    return score && score.textContent && score.textContent !== "comparing…";
                })
                .catch(() => {});
        },
    })),
    {
        // Switching player through the combo. The committed HTML always opens on the default
        // (`Java`), so this is the only way the picker's *moved* state is diffed: the combo on
        // `CMP Android`, and — the point of the whole control — the chip beside it renaming itself
        // to match instead of the visitor having to read which of six chips lit up.
        fixture: "serve-viewer-rc-players",
        suffix: "player-cmp-android",
        apply: async (page) => {
            await page.selectOption("#cp-lane-select", "rc:cmp-android");
            await page.waitForFunction(
                () =>
                    document.getElementById("cp-live-toggle-label")?.textContent ===
                    "CMP Android",
            );
        },
    },
    {
        fixture: "serve-viewer",
        suffix: "connecting",
        apply: async (page) => {
            await page.evaluate(() => {
                const root = document.querySelector(".cp-viewer");
                root.setAttribute("data-mode", "live");
                root.setAttribute("data-pending", "connecting…");
            });
        },
    },
    {
        // The render-server badge (#3274). Catalogs open their daemon on first use, so whether one
        // is up is a real question the page now answers — and "connected" is the state a visitor
        // sees while anything is warm. Stubbed rather than faked: the pill's text and styling come
        // from the real `presenceScript`, this only supplies the JSON it polls for.
        fixture: "serve-landing-declared-themes",
        suffix: "daemon-connected",
        apply: async (page) => {
            await page.route("**/api/daemons*", (route) =>
                route.fulfill({
                    status: 200,
                    contentType: "application/json",
                    body: JSON.stringify({
                        running: true,
                        instances: 2,
                        pooled: 1,
                        poolCapacity: 8,
                        activeStreams: 0,
                    }),
                }),
            );
            await page.evaluate(() => document.dispatchEvent(new Event("visibilitychange")));
            await page.waitForFunction(
                () =>
                    document
                        .getElementById("cp-daemon-status")
                        ?.getAttribute("data-cp-daemon-running") === "1",
            );
        },
    },
    {
        // The resting state, and the one most catalogs are in: registered, nobody has rendered on
        // it, so no process exists. It must read as neutral information rather than a fault, which
        // is a styling claim only a screenshot can hold honest.
        fixture: "serve-landing-declared-themes",
        suffix: "daemon-idle",
        apply: async (page) => {
            await page.route("**/api/daemons*", (route) =>
                route.fulfill({
                    status: 200,
                    contentType: "application/json",
                    body: JSON.stringify({
                        running: false,
                        instances: 0,
                        pooled: 0,
                        poolCapacity: 8,
                        activeStreams: 0,
                    }),
                }),
            );
            await page.evaluate(() => document.dispatchEvent(new Event("visibilitychange")));
            await page.waitForFunction(
                () =>
                    document
                        .getElementById("cp-daemon-status")
                        ?.getAttribute("data-cp-daemon-running") === "0",
            );
        },
    },
    {
        // Terminal failure: after the bounded retries are exhausted, the previous theme's pixels
        // stay visible but must be labelled as stale/unavailable rather than looking successfully
        // updated. Zero retries keeps this visual contract case fast while exercising the real
        // fetch-failure path.
        fixture: "serve-landing-declared-themes",
        suffix: "theme-error",
        apply: async (page) => {
            await page.route("**/render/**", (route) => {
                const url = new URL(route.request().url());
                if (!url.searchParams.has("themeProvider")) {
                    return route.fulfill({
                        path: renderPlaceholder,
                        contentType: "image/png",
                    });
                }
                return route.fulfill({ status: 503, body: "render unavailable" });
            });
            await page.evaluate(() => {
                themeRenderRetries = 0;
            });
            await page.getByRole("button", { name: "Brand Light" }).click();
            await page.waitForSelector(".cp-theme-render-error");
        },
    },
    {
        // Mid-swap: a themed render is in flight and has NOT come back yet. This is the frame the
        // visitor stared at for ~1s while it showed a broken-image glyph, and it is invisible to an
        // ordinary end-state screenshot — the finished pixels are identical either way. Hold the
        // render open and shoot: every card must still be showing its previous render under the
        // spinner, never an empty or broken image. This remains the last theme state because its
        // intentionally unresolved requests cannot be followed by another theme transition.
        fixture: "serve-landing-declared-themes",
        suffix: "theme-inflight",
        apply: async (page) => {
            await page.route("**/render/**", (route) => {
                const url = new URL(route.request().url());
                // Only the themed re-renders stall; the baked pixels must still load, since the
                // whole point is that they are what stays on screen.
                if (!url.searchParams.has("themeProvider")) {
                    return route.fulfill({
                        path: renderPlaceholder,
                        contentType: "image/png",
                    });
                }
                return new Promise(() => {});
            });
            await page.getByRole("button", { name: "Brand Dark" }).click();
            await page.waitForSelector(".cp-reloading");
        },
    },
    {
        // The long-press affordance. It exists only under a pointer — deliberately, so a grid of
        // 80 cards doesn't grow 80 permanent badges — which makes it invisible to an end-state
        // screenshot and exactly the kind of thing that reaches production unreviewed.
        fixture: "serve-landing-live",
        suffix: "live-hint",
        apply: async (page) => {
            await page.addStyleTag({
                content: "*, *::before, *::after { transition-duration: 0ms !important; }",
            });
            await page.hover(".cp-grid .cp-card");
        },
    },
    {
        // A card actually streaming: the daemon's frames paint into a canvas overlaid on the
        // thumbnail's slot, the card takes an accent outline, and the hint becomes a "live"
        // readout. There is no daemon in the harness, so the socket is stubbed — everything else
        // (the press timing, the overlay, the chip) is the real `catalog-live.js` doing its job.
        fixture: "serve-landing-live",
        suffix: "live-card",
        apply: async (page) => {
            await page.addStyleTag({
                content: "*, *::before, *::after { transition-duration: 0ms !important; }",
            });
            await page.evaluate(() => {
                // A stand-in frame: what the daemon would push, drawn locally so the shot is
                // deterministic and needs no render backend.
                const frame = document.createElement("canvas");
                frame.width = 320;
                frame.height = 130;
                const ctx = frame.getContext("2d");
                ctx.fillStyle = "#eef0ff";
                ctx.fillRect(0, 0, 320, 130);
                ctx.fillStyle = "#4b4bc8";
                ctx.beginPath();
                ctx.roundRect(60, 40, 200, 52, 26);
                ctx.fill();
                ctx.fillStyle = "#ffffff";
                ctx.font = "600 20px sans-serif";
                ctx.textAlign = "center";
                ctx.fillText("Pressed", 160, 73);
                const dataBase64 = frame.toDataURL("image/png").split(",")[1];
                window.WebSocket = class {
                    constructor() {
                        this.readyState = 1;
                        setTimeout(() => {
                            if (this.onmessage) {
                                this.onmessage({
                                    data: JSON.stringify({
                                        type: "frame",
                                        codec: "png",
                                        dataBase64,
                                    }),
                                });
                            }
                        }, 0);
                    }
                    send() {}
                    close() {}
                };
            });
            const card = page.locator(".cp-grid .cp-card").first();
            const box = await card.boundingBox();
            await page.mouse.move(box.x + box.width / 2, box.y + box.height / 3);
            await page.mouse.down();
            await page.waitForSelector(".cp-card-live");
            await page.mouse.up();
            await page.waitForFunction(
                () => document.querySelector(".cp-live-chip")?.textContent === "live",
            );
        },
    },
    {
        // Keep the full-page export control expanded so its supported formats and future visual
        // changes are visible to the screenshot diff instead of hidden in a closed <details>.
        fixture: "serve-viewer-catalog-knobs",
        suffix: "scroll-full-page",
        apply: async (page) => {
            await page.click('[data-cp-group="scroll"] > summary');
        },
    },
    {
        // The Wear device menu. A screen's device profiles are platform-specific — watch shapes on
        // a Wear system, phones/foldable/tablet elsewhere — and BOTH halves of that claim are
        // invisible to an end-state shot: the Size group renders closed, and a closed `<select>`
        // paints only its selected option, so a regression back to Pixel devices (or an Orientation
        // row reappearing on a watch) would move no baseline at all. Open the group and expand the
        // menu into a list box so every offered device is pixels the diff bot can see.
        fixture: "serve-viewer-wear-screen",
        suffix: "size-open",
        apply: async (page) => {
            await page.click('[data-cp-group="size"] > summary');
            await page.evaluate(() => {
                const devices = document.getElementById("cp-device");
                devices.size = devices.options.length;
            });
        },
    },
    {
        // A preview the server has permanently given up on (#3317). The theme grid used to retry
        // any failure three times and then say "Theme preview unavailable", which reads as "still
        // working on it" for a card that will never render — a `painterResource` whose drawable was
        // pruned out of the bundle, say. The server now answers those with a terminal 409 and the
        // card retires immediately with its own wording, so capture that state: the claim is what
        // the visitor is told, which only a screenshot holds honest.
        fixture: "serve-landing-declared-themes",
        suffix: "theme-render-terminal",
        apply: async (page) => {
            // Registered after the generic image lane, so this wins for render requests.
            await page.route("**/render/**", (route) =>
                route.fulfill({
                    status: 409,
                    contentType: "text/plain",
                    body: "NotFoundException: File res/drawable/ic_play.xml",
                }),
            );
            await page.click('[data-theme-choice^="theme:"]');
            await page.waitForSelector(".cp-theme-error");
            // Hold until the workers have settled, so the shot isn't racing a spinner. A terminal
            // failure takes no retries, so this resolves without waiting out any backoff — which is
            // itself the behaviour under test.
            await page.waitForFunction(
                () => !document.querySelector('[aria-busy="true"]'),
            );
        },
    },
    {
        // The annotation layers default to off — the page's first job is the pixel diff, and boxes
        // drawn over both panels would obscure exactly what is being judged. That leaves the drawn
        // state invisible to the diff bot unless it is captured deliberately, so switch both layers
        // on here: every future change to the redline/type overlays (geometry, colours, label
        // placement) is then diffed for free.
        fixture: "serve-reference-compare",
        suffix: "annotated",
        apply: async (page) => {
            for (const kind of ["layout", "typography"]) {
                await page.check(`[data-cp-annotation-kind="${kind}"]`);
            }
            // The boxes are positioned from the image's rendered size, so hold until the panels
            // have actually laid out rather than racing the placeholder's load.
            await page.waitForFunction(() => {
                const box = document.querySelector(".cp-annotation");
                return box && box.getBoundingClientRect().width > 0;
            });
            // Two adjacent text nodes per panel share one style. The annotation layer must cluster
            // them into one outline on each side and the summary must describe the style once,
            // instead of regressing to four numbered rows.
            await expect(page.locator(".cp-typography-group")).toHaveCount(2);
            await expect(
                page.locator(".cp-annotation--typography-cluster"),
            ).toHaveCount(3);
            await expect(
                page.locator(".cp-annotation--typography-hit"),
            ).toHaveCount(5);
            await expect(page.locator(".cp-typography-count")).toHaveText([
                "2 usages",
                "2 usages",
                "1 usage",
            ]);
            const override = page.locator(".cp-typography-override");
            await expect(override).toHaveText("wght 700");
            await expect(override).toHaveAttribute(
                "title",
                "Changed from bodyMedium default",
            );
            await page.locator(".cp-typography-group").first().hover();
            await expect(
                page.locator(
                    ".cp-annotation--typography-hit.cp-annotation-active",
                ),
            ).toHaveCount(4);
        },
    },
    {
        // The exploded view's camera controls. The default shot proves the projection lands on the
        // stage; the sliders that shape it sit inside a closed `<details>`, so their layout — a
        // range and a live readout on one 240px row — is invisible to it and a regression there
        // would move no baseline. Open the group and nudge the lean off its default so the readout
        // is shown holding a value rather than its initial text.
        fixture: "serve-viewer-exploded",
        suffix: "controls",
        apply: async (page) => {
            await page.click('[data-cp-group="explode"] > summary');
            await page.locator("#cp-explode-tilt").fill("46");
            await page.locator("#cp-explode-tilt").dispatchEvent("input");
            await expect(page.locator("#cp-explode-tilt-value")).toHaveText("46°");
            // The knob re-fetches the re-projected SVG (debounced). Hold until that has landed, or
            // the shot catches a spinner over the stage and the baseline flickers per run.
            await page.waitForFunction(
                () => !document.querySelector('.cp-stage[aria-busy="true"]'),
            );
        },
    },
    {
        // The player wall's other half. It opens as a plain side-by-side of every player — nothing
        // is diffed until a column is picked as the reference — so the diff layout (the badge on
        // the reference column, the mismatch chips, a diff growing inside each other column) is
        // invisible to the default shot. Picking the baked PNG captures it, and picking the one
        // reference whose diffs are precomputed keeps the shot deterministic.
        fixture: "serve-rc-lanes",
        suffix: "diff-baked",
        apply: async (page) => {
            await page.click('[data-rc-ref="baked"]');
            await page.waitForFunction(
                () => document.querySelector(".cp-rc-row .cp-rc-score") !== null,
            );
        },
    },
];

/** Page fixtures = `fixtures/pages/*.html`, honouring the `HARNESS_FIXTURE` narrow. */
function listPageFixtures() {
    const only = process.env.HARNESS_FIXTURE;
    const all = readdirSync(pagesDir)
        .filter((e) => e.endsWith(".html"))
        .map((e) => e.replace(/\.html$/, ""));
    return only ? all.filter((n) => n === only) : all;
}

for (const fixture of listPageFixtures()) {
    for (const theme of listThemes()) {
        test(`snapshot · ${fixture} · ${theme}`, async ({ page }) => {
            // The comparison fixture exercises production CSS and JS (including its asynchronous
            // scorer), while older static-page baselines retain their historical capture contract.
            if (STYLED_FIXTURES.has(fixture)) {
                for (const [name, contentType] of SERVE_ASSETS) {
                    await page.route(`**/assets/serve/**/${name}`, (route) =>
                        route.fulfill({
                            path: resolve(serveAssetsDir, name),
                            contentType,
                        }),
                    );
                }
            }
            for (const lane of IMAGE_LANES) {
                await page.route(lane, (route) => {
                    if (lane.includes("reference")) {
                        return route.fulfill({
                            body: REFERENCE_PLACEHOLDER,
                            contentType: "image/svg+xml",
                        });
                    }
                    if (lane.includes("pages")) {
                        return route.fulfill({
                            body: PAGE_PLACEHOLDER,
                            contentType: "image/svg+xml",
                        });
                    }
                    const url = new URL(route.request().url());
                    const svg = url.pathname.endsWith(".svg");
                    const exploded = svg && url.searchParams.get("exploded") === "1";
                    return route.fulfill({
                        path: exploded
                            ? renderExplodedPlaceholder
                            : svg
                              ? renderSvgPlaceholder
                              : renderPlaceholder,
                        contentType: svg ? "image/svg+xml" : "image/png",
                    });
                });
            }
            // After the image lanes on purpose: Playwright matches the most recently registered
            // route first, and `**/render/**` above would otherwise answer an inspection fetch with
            // a PNG.
            await page.route("**/render/*.a11y*", (route) =>
                route.fulfill({
                    contentType: "application/json",
                    body: JSON.stringify(A11Y_PAYLOAD),
                }),
            );
            await page.route("**/render/*.annotations*", (route) =>
                route.fulfill({
                    contentType: "application/json",
                    body: JSON.stringify(ANNOTATIONS_PAYLOAD),
                }),
            );
            await page.emulateMedia({ colorScheme: theme });
            await page.goto(
                `/preview-harness/fixtures/pages/${fixture}.html${FIXTURE_QUERY[fixture] || ""}`,
            );

            // Wait until every (placeholder-stubbed) image has decoded so the
            // shot isn't a pre-image-load frame. Tolerant: pages with no
            // images resolve immediately.
            await page
                .waitForFunction(
                    () =>
                        Array.from(document.images).every((i) => i.complete),
                    null,
                    { timeout: 5_000 },
                )
                .catch(() => {});

            // Comparison scores are asynchronous (fetch + decode + SSIM). Capture the settled
            // fidelity state, not the initial "waiting…" skeleton.
            if (fixture === "serve-format-compare") {
                await page
                    .waitForFunction(() =>
                        Array.from(
                            document.querySelectorAll(".cp-compare-score"),
                        ).every(
                            (cell) =>
                                cell.textContent !== "waiting…" &&
                                cell.textContent !== "comparing…",
                        ),
                    )
                    .catch(() => {});
            }
            if (fixture === "serve-reference-compare") {
                await page
                    .waitForFunction(
                        () =>
                            !document
                                .querySelector(".cp-reference-result")
                                .textContent.includes("comparing"),
                    )
                    .catch(() => {});
            }

            await page.screenshot({
                path: resolve(outDir, `${fixture}.${theme}.png`),
                fullPage: true,
                animations: "disabled",
            });

            // Extra runtime states of this same fixture, shot from the already-loaded page.
            for (const state of FIXTURE_STATES.filter((s) => s.fixture === fixture)) {
                await state.apply(page);
                await page.screenshot({
                    path: resolve(
                        outDir,
                        `${fixture}-${state.suffix}.${theme}.png`,
                    ),
                    fullPage: true,
                    animations: "disabled",
                });
            }
        });
    }
}

test("contract · declared theme renders use bounded parallelism", async ({ page }) => {
    let active = 0;
    let maxActive = 0;
    let completed = 0;
    const attempts = new Map();
    let released = false;

    await page.route("**/api/theme-render-lease?*", async (route) => {
        await route.fulfill({
            status: 200,
            contentType: "application/json",
            body: JSON.stringify({ lease: "page-lease", concurrency: 5 }),
        });
    });
    await page.route("**/api/theme-render-lease/release?*", async (route) => {
        released = true;
        await route.fulfill({ status: 204, body: "" });
    });

    await page.route("**/render/**", async (route) => {
        const url = new URL(route.request().url());
        if (!url.searchParams.has("themeProvider")) {
            await route.fulfill({
                path: renderPlaceholder,
                contentType: "image/png",
            });
            return;
        }
        expect(url.searchParams.get("_themeLease")).toBe("page-lease");

        active++;
        maxActive = Math.max(maxActive, active);
        const attempt = (attempts.get(url.pathname) ?? 0) + 1;
        attempts.set(url.pathname, attempt);
        await new Promise((resolve) => setTimeout(resolve, 100));
        active--;

        // Shed every card's first request. The page must retry it without exceeding the worker cap.
        if (attempt === 1) {
            await route.fulfill({ status: 503, body: "render busy" });
        } else {
            completed++;
            await route.fulfill({
                path: renderPlaceholder,
                contentType: "image/png",
            });
        }
    });

    // Tall enough that all three cards are on screen. Off-screen cards deliberately do NOT join the
    // leased burst — they trickle in one at a time as the viewport reaches them — so a short
    // viewport would measure the deferral, not the parallelism this test is about.
    await page.setViewportSize({ width: 1280, height: 2200 });
    await page.goto(
        "/preview-harness/fixtures/pages/serve-landing-declared-themes.html",
    );
    await page.getByRole("button", { name: "Brand Light" }).click();

    await expect.poll(() => completed, { timeout: 10_000 }).toBe(3);
    expect(maxActive).toBe(3);
    expect(Array.from(attempts.values())).toEqual([2, 2, 2]);
    await expect.poll(() => released).toBe(true);
});

test("contract · snapshot overrides stay composed with a declared theme", async ({
    page,
}) => {
    const requests = [];
    for (const [name, contentType] of SERVE_ASSETS) {
        await page.route(`**/assets/serve/**/${name}`, (route) =>
            route.fulfill({
                path: resolve(serveAssetsDir, name),
                contentType,
            }),
        );
    }
    await page.route("**/render/**", async (route) => {
        requests.push(new URL(route.request().url()));
        await route.fulfill({
            path: renderPlaceholder,
            contentType: "image/png",
        });
    });
    await page.goto("/preview-harness/fixtures/pages/serve-viewer-themes.html");
    await expect.poll(() => requests.length).toBeGreaterThan(0);
    await page.waitForTimeout(100);
    requests.length = 0;

    for (const group of ["size", "locale"]) {
        await page
            .locator(`details[data-cp-group="${group}"]`)
            .evaluate((details) => {
                details.open = true;
            });
    }

    const themeProvider = "com.example.BrandLightThemeCatalog";
    const assertSingleRender = async (before, expected) => {
        await expect
            .poll(() => requests.length, { timeout: 5_000 })
            .toBe(before + 1);
        // A second assignment of the no-store render URL used to arrive immediately after the
        // preload completed. Give it a beat so an accidental duplicate cannot pass this check.
        await page.waitForTimeout(100);
        expect(requests).toHaveLength(before + 1);
        const params = requests.at(-1).searchParams;
        expect(params.get("themeProvider")).toBe(themeProvider);
        for (const [key, value] of Object.entries(expected)) {
            expect(params.get(key), key).toBe(value);
        }
    };
    const change = async (action, expected) => {
        const before = requests.length;
        await action();
        await assertSingleRender(before, expected);
    };

    // Through the viewer bar's theme chip, which is the visible control now — `#cp-theme` is still
    // the state it writes, but a test that drives the hidden select would pass even if the chips
    // were wired to nothing.
    await change(
        () =>
            page
                .locator(
                    `.cp-theme-bar .cp-theme-btn[data-theme-choice="theme:${themeProvider}"]`,
                )
                .click(),
        {},
    );
    await change(
        async () => {
            await page.locator("#cp-localeTag").fill("ar-XB");
            await page.locator("#cp-localeTag").press("Tab");
        },
        { localeTag: "ar-XB" },
    );
    await change(
        () =>
            page.locator("#cp-fontScale").evaluate((slider) => {
                slider.value = "1.3";
                slider.dispatchEvent(new Event("input", { bubbles: true }));
            }),
        { fontScale: "1.3" },
    );
    await change(() => page.locator("#cp-device").selectOption("id:pixel_7"), {
        device: "id:pixel_7",
    });
    await change(
        () => page.locator("#cp-orientation").selectOption("landscape"),
        { orientation: "landscape" },
    );
});

// Clearing a string knob is an EDIT, not an absence. The viewer used to drop any empty knob value
// before it ever reached the URL, so emptying a label silently re-rendered the author default —
// and an `@OverrideVariant` seeded to "" (`strings = ["label="]`, which discovery preserves) opened
// its control empty and then mounted the primary. A knob whose kind can't parse "" (this fixture's
// colour) still has nothing to send, and must stay absent rather than becoming `knob.iconColor=`.
test("contract · an emptied string knob is sent, an emptied typed knob is not", async ({
    page,
}) => {
    const requests = [];
    for (const [name, contentType] of SERVE_ASSETS) {
        await page.route(`**/assets/serve/**/${name}`, (route) =>
            route.fulfill({
                path: resolve(serveAssetsDir, name),
                contentType,
            }),
        );
    }
    await page.route("**/render/**", async (route) => {
        requests.push(new URL(route.request().url()));
        await route.fulfill({
            path: renderPlaceholder,
            contentType: "image/png",
        });
    });
    await page.goto(
        "/preview-harness/fixtures/pages/serve-viewer-catalog-knobs.html",
    );
    await expect.poll(() => requests.length).toBeGreaterThan(0);
    // The knob controls live in the collapsed Overrides drawer; open it so they are editable.
    await page
        .locator('details[data-cp-group="overrides"]')
        .evaluate((details) => {
            details.open = true;
        });

    const clear = async (key) => {
        const before = requests.length;
        const input = page.locator(`.cp-knob[data-knob-key="${key}"]`);
        await input.fill("");
        await input.press("Tab");
        await expect
            .poll(() => requests.length, { timeout: 5_000 })
            .toBeGreaterThan(before);
        await page.waitForTimeout(100);
        return requests.at(-1).searchParams;
    };

    // `has` rather than `get(...) === ""`: the distinction under test is present-and-empty versus
    // absent, and `get` answers null for both an absent param and one this assertion would miss.
    const afterLabel = await clear("label");
    expect(afterLabel.has("knob.label")).toBe(true);
    expect(afterLabel.get("knob.label")).toBe("");

    const afterColor = await clear("iconColor");
    expect(afterColor.has("knob.iconColor")).toBe(false);
    // The string knob stays cleared across the second edit — the collector rebuilds the whole
    // query each time, so a regression that dropped empties would lose it here too.
    expect(afterColor.get("knob.label")).toBe("");
});

test("contract · the spec lane compares the frame that was on the stage", async ({
    page,
}) => {
    // What the diff / triptych / slider are scored against. The lane reuses the blob the viewer
    // already fetched — no second render, and the comparison is literally the pixels the visitor
    // was looking at — but ONLY when that blob is in fact the frame they were looking at. Live,
    // Wasm and the Remote Compose players paint into a canvas or an iframe while `#cp-img` keeps
    // the snapshot from page load, and they apply overrides in place without re-pointing /render,
    // so from those lanes the blob can describe a state the visitor left behind. Both halves are
    // asserted here by counting renders.
    const requests = [];
    for (const [name, contentType] of SERVE_ASSETS) {
        await page.route(`**/assets/serve/**/${name}`, (route) =>
            route.fulfill({
                path: resolve(serveAssetsDir, name),
                contentType,
            }),
        );
    }
    await page.route("**/reference/**", (route) =>
        route.fulfill({
            body: REFERENCE_PLACEHOLDER,
            contentType: "image/svg+xml",
        }),
    );
    await page.route("**/render/**", async (route) => {
        requests.push(new URL(route.request().url()));
        await route.fulfill({
            path: renderPlaceholder,
            contentType: "image/png",
        });
    });
    await page.goto("/preview-harness/fixtures/pages/serve-viewer-path.html");
    await expect.poll(() => requests.length).toBeGreaterThan(0);
    await page.waitForTimeout(100);

    const settled = () =>
        page
            .waitForFunction(() => {
                const score = document.getElementById("cp-spec-score");
                return score && score.textContent && score.textContent !== "comparing…";
            })
            .catch(() => {});

    // From the static raster lane: the blob is the frame, so comparing costs nothing extra.
    // The lane is entered through its own chip — `#cp-spec-chip`, beside the renderer combo rather
    // than inside it.
    const beforeSnapshot = requests.length;
    await page.click("#cp-spec-chip");
    await page.click('[data-cp-spec-view="triptych"]');
    await settled();
    await page.waitForTimeout(100);
    expect(requests).toHaveLength(beforeSnapshot);
    await expect(page.locator("#cp-spec-score")).not.toHaveText(
        "Comparison unavailable",
    );

    // Now arriving from an interactive lane. `data-mode` is the page's own record of what is on
    // the stage, and `enterMode` reads it before tearing the outgoing lane down — so marking it
    // exercises exactly the path a real Live/Wasm/RC lane takes, without needing a daemon or a
    // Wasm app in the harness. The stale blob must NOT be reused: the lane has to ask the server
    // for the current controls instead.
    await page.click("#cp-spec-chip");
    await page.waitForTimeout(100);
    await page
        .locator(".cp-viewer")
        .evaluate((root) => root.setAttribute("data-mode", "live"));
    const beforeLive = requests.length;
    await page.click("#cp-spec-chip");
    await settled();
    await expect.poll(() => requests.length).toBe(beforeLive + 1);
    expect(requests.at(-1).pathname.endsWith(".png")).toBe(true);
});

test("contract · Back to an unthemed entry hands the chrome back to the OS", async ({
    page,
}) => {
    for (const [name, contentType] of SERVE_ASSETS) {
        await page.route(`**/assets/serve/**/${name}`, (route) =>
            route.fulfill({
                path: resolve(serveAssetsDir, name),
                contentType,
            }),
        );
    }
    await page.route("**/render/**", (route) =>
        route.fulfill({ path: renderPlaceholder, contentType: "image/png" }),
    );
    await page.goto("/preview-harness/fixtures/pages/serve-viewer-themes.html");

    const scheme = () =>
        page.evaluate(() =>
            (document.documentElement.className.match(/cp-scheme-\w+/) || [""])[0],
        );

    // The entry this test is about: nothing in the URL, nothing remembered, so `#cp-theme` shows
    // the preview's BAKED default — `dark`, for this fixture — while `data-theme-active="0"`
    // records that nobody picked it. The chrome is left to `prefers-color-scheme`, no pinned
    // class at all.
    await expect(page.locator("#cp-theme")).toHaveAttribute(
        "data-theme-active",
        "0",
    );
    await expect(page.locator("#cp-theme")).toHaveJSProperty("value", "dark");
    expect(await scheme()).toBe("");

    // Pick Light — the chip that is NOT the baked default, so this is a real change rather than a
    // no-op the handler drops. It pushes a history entry and pins the chrome, the working half.
    // Driven through the visible chip rather than `#cp-theme`: the select is in the DOM but
    // visually removed (the bar is its face), so it is the chip a visitor can actually click.
    await page.click('[data-theme-choice="light"]');
    await expect.poll(scheme).toBe("cp-scheme-light");

    // Back to the entry that had no choice on it. The select's displayed value returns to the
    // baked `dark` and `data-theme-active` returns to "0" — so the chrome must return to the OS
    // too. Passing the DISPLAYED value here instead of the active one pins the page dark: a mode
    // the visitor never chose, reached by going Back from the one they did.
    await page.goBack();
    await expect(page.locator("#cp-theme")).toHaveAttribute(
        "data-theme-active",
        "0",
    );
    await expect.poll(scheme).toBe("");
});

test("contract · the fit cap re-measures when the history strip lands", async ({
    page,
}) => {
    for (const [name, contentType] of SERVE_ASSETS) {
        await page.route(`**/assets/serve/**/${name}`, (route) =>
            route.fulfill({
                path: resolve(serveAssetsDir, name),
                contentType,
            }),
        );
    }
    await page.route("**/render/**", (route) =>
        route.fulfill({ path: renderPlaceholder, contentType: "image/png" }),
    );

    // The fixture carries an INLINE history payload, which viewer-history.js consumes
    // synchronously — and it is ordered ahead of viewer.js, so the strip is already in the DOM
    // before the first fit is measured. That is the one arrangement in which this bug cannot
    // happen. Strip the inline payload so the fetch path runs instead: that is the delivery-backed
    // shape, where the manifest arrives over the network and the strip lands long after viewer.js
    // has measured and fitted.
    await page.route(
        "**/fixtures/pages/serve-viewer-history.html",
        async (route) => {
            const html = await (await route.fetch()).text();
            await route.fulfill({
                contentType: "text/html",
                body: html.replace(
                    /<script type="application\/json" id="cp-history-data">[\s\S]*?<\/script>/,
                    "",
                ),
            });
        },
    );
    let manifest = null;
    await page.route("**/history.json", async (route) => {
        const inline = await (await route.fetch()).text().catch(() => null);
        await route.fulfill({
            contentType: "application/json",
            body: manifest ?? inline ?? "{}",
        });
    });

    // Read the payload the fixture ships, and serve it from the manifest URL instead.
    const raw = readFileSync(
        resolve(pagesDir, "serve-viewer-history.html"),
        "utf8",
    );
    manifest = raw
        .match(
            /<script type="application\/json" id="cp-history-data">([\s\S]*?)<\/script>/,
        )[1]
        .trim();

    await page.goto("/preview-harness/fixtures/pages/serve-viewer-history.html");
    // The strip arrives asynchronously — this is the moment the stage moves down.
    await page.waitForSelector(".cp-history");
    await page.waitForTimeout(200);

    const { applied, expected, historyHeight } = await page.evaluate(() => {
        const stage = document.querySelector(".cp-stage");
        const top = stage.getBoundingClientRect().top + (window.scrollY || 0);
        return {
            applied: parseInt(
                document.getElementById("cp-img").style.maxHeight,
                10,
            ),
            // fitCap()'s own arithmetic, re-run against the geometry as it stands NOW.
            expected: Math.max(320, Math.round(window.innerHeight - top - 64)),
            historyHeight: document.querySelector(".cp-history").offsetHeight,
        };
    });

    // Guard the guard: if the strip were too short to move the stage, both numbers would agree no
    // matter what the code did, and this test would pass against the bug it exists to catch.
    expect(historyHeight).toBeGreaterThan(0);
    expect(applied).toBe(expected);
});
