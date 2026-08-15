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
// The design page's own render stub, and the ONLY fixture that gets it. Every other page shows a
// render as a picture; the design page drops it into the slot the design left for that node, and
// what it fits there is the render's DRAWN pixels — the component — not its canvas. The flat
// placeholder above is opaque edge to edge, so under it ink-fitting and canvas-fitting are the same
// operation and the behaviour this page turns on would move no baseline at all. This one is what a
// real render looks like to that code: a 240×240 canvas with a 200×112 component centred in it and
// transparent margin around, non-square on purpose so a slot filled by the CANVAS and a slot filled
// by the COMPONENT are told apart by eye.
const designRenderPlaceholder = resolve(pagesDir, "_design-render-placeholder.png");
// The motion lane's stub, and it has to be a genuinely ANIMATED file rather than another flat
// placeholder: what the capture below is asserting is that the lane puts a moving image on the
// stage in place of the still, and a static PNG served as `image/apng` would satisfy every
// selector in the shot while proving none of it. 14 frames of an M3 switch travelling and
// settling, at the same 200x420 the still lanes use.
//
// It plays ONCE and rests on its last frame, which a real capture does not — those loop. That is
// a deliberate divergence and the only one: `page.screenshot({animations: "disabled"})` reaches
// CSS animations, not an image's own, so a looping stub would put the shutter on an arbitrary
// frame and the baseline would flake by the whole width of the switch's travel. Resting on a
// fixed final frame keeps the shot deterministic while still proving the thing under test: those
// pixels are on the stage only because the lane fetched an animated file and played it.
const motionPlaceholder = resolve(pagesDir, "_motion-placeholder.apng");
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
    "serve-viewer",
    "serve-parity",
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
    // painted at runtime by `<cp-inspect-layers>` — boxes over the stage, a legend beside it — so
    // captured bare there is nothing to see at all. Its `layers` state below is what draws them.
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
    // The Source lane. The chip is styled by `serve.css` and the panel is drawn entirely at
    // runtime by `viewer.js` from a fetched snippet, so captured bare this page is an unstyled
    // button over a placeholder and its whole claim — code on the stage where the render was —
    // moves no baseline at all. The `source-panel` state below is the shot that matters.
    "serve-viewer-source",
    // The design page is nothing BUT layout: this catalog's renders measured into the slots the
    // design left for them, on the sheet's own geometry. Captured bare it is a list of links under
    // a picture and the entire claim moves no baseline. The page opens on the render lane, so the
    // default shot IS the swap; its `design-lane` and `selected` states below are the flip it
    // exists to support and the affordance that replaced the resting outlines.
    "serve-design-page",
]);
const SERVE_ASSETS = [
    ["serve.css", "text/css"],
    ["playground.css", "text/css"],
    ["serve-chrome.js", "text/javascript"],
    ["serve-components.js", "text/javascript"],
    ["viewer.js", "text/javascript"],
    ["format-compare.js", "text/javascript"],
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
// The viewer's theme bar FOLDS behind its title-bar toggle once a catalog declares more themes
// than the single-row bar can show, so a chip on such a page is behind one click before it is
// clickable. Drive it the way a visitor does — open the disclosure, then press the chip — rather
// than reaching past the fold with a forced click, which would keep passing if the toggle stopped
// opening anything. A no-op on a viewer whose bar is already inline, and on the landing grid,
// whose theme picker is a wrapping row that never folds.
async function openThemeBar(page) {
    const bar = page.locator("#cp-theme-bar");
    if ((await bar.count()) && (await bar.isHidden()))
        await page.click("#cp-theme-toggle");
}

/**
 * Open the overrides drawer — the COLUMN, not the groups inside it.
 *
 * Everything a viewer can be told to do lives in there (the knobs, the size and locale groups, the
 * inspection layers, the exploded camera), and the drawer is revealed by `cp-controls-open` on
 * `.cp-viewer`. The server stopped emitting that class in #3893, so the drawer now starts closed at
 * every width and `.cp-viewer:not(.cp-controls-open) .cp-controls { display: none }` hides every
 * group whatever its own `open` says. Six states and contracts here reached straight for a group's
 * summary or a knob and found it resolvable, invisible, and unclickable — which is a sixty-second
 * timeout each, and is what took this suite red on every branch.
 *
 * Through the toggle rather than by setting the class, so it keeps testing the control a reader
 * uses; idempotent, so a caller need not know what the last state left behind.
 */
async function openControlsDrawer(page) {
    const viewer = page.locator(".cp-viewer");
    if (!(await viewer.count())) return;
    const open = await viewer.evaluate((v) =>
        v.classList.contains("cp-controls-open"),
    );
    if (!open) await page.click("#cp-controls-toggle");
    await expect(viewer).toHaveClass(/cp-controls-open/);
}

// A point on the design page's sheet, given in the EXPORT's own user units — the coordinates the
// fixture SVG is written in. States aim at a card's padding or the gap between two slots, which is
// where a reader double-clicks to zoom, and neither has a client pixel that survives a viewport
// change. Correct at any zoom too: the SVG's client rect carries the stage's transform already.
async function sheetPoint(page, ux, uy) {
    const at = await page.evaluate(
        ([x, y]) => {
            const svg = document.querySelector(".cp-page-stage svg");
            const rect = svg.getBoundingClientRect();
            const box = svg.viewBox.baseVal;
            return {
                x: rect.left + (x / box.width) * rect.width,
                y: rect.top + (y / box.height) * rect.height,
                width: window.innerWidth,
                height: window.innerHeight,
            };
        },
        [ux, uy],
    );
    // A zoom moves the sheet under these coordinates: a spot that was mid-stage at 1:1 can be well
    // below the fold once a section fills the view, and a click there lands on nothing while
    // `elementsFromPoint` (which the drill itself uses) answers an empty list for any point outside
    // the viewport. That reads as "the gesture did nothing", which is exactly the failure these
    // states exist to catch — so say what actually happened instead of timing out on a wait.
    if (at.x < 0 || at.y < 0 || at.x > at.width || at.y > at.height) {
        throw new Error(
            `sheet point ${ux},${uy} is off-screen at (${Math.round(at.x)}, ${Math.round(at.y)}) ` +
                `in a ${at.width}x${at.height} viewport — aim at a spot the current view still shows`,
        );
    }
    return at;
}

// How far in the sheet is, read off the control the reader reads it off.
async function zoomPercent(page) {
    return page.evaluate(() => {
        const level = document.querySelector("[data-cp-page-zoom-level]");
        return level ? parseInt(level.textContent, 10) : 0;
    });
}

// A phone, in CSS pixels. The serve pages carry a whole `@media (max-width: 640px)` layout — a
// stacked viewer, drawers that become bottom sheets, a sticky title row, and the single-row chip
// scrollers that keep a catalog's chrome off the fold — and until these states existed NOTHING
// captured it: every baseline was shot at 1024, so the mobile half of the stylesheet could
// regress, or be deleted, without moving a single pixel of evidence. A state carrying `viewport`
// is captured at that size and the runner puts the viewport back afterwards, so a mobile shot can
// sit anywhere in a fixture's state list without shifting the states after it.
const PHONE_VIEWPORT = { width: 412, height: 800 };

const FIXTURE_STATES = [
    {
        // A component under the POINTER. The sheet carries no resting marks, so this is the whole
        // discovery story: the outline appears where you point, and it appears whether or not the
        // opt-in layer is on (this shot is taken with it off, which is the default). A hover state
        // exists only under a pointer, so it is invisible to every other shot here — exactly the
        // kind of affordance that reaches production unreviewed. Shot before `selected` below so
        // the page under it is the untouched default.
        fixture: "serve-design-page",
        suffix: "hover",
        apply: async (page) => {
            await page.addStyleTag({
                content: "*, *::before, *::after { transition-duration: 0ms !important; }",
            });
            await page.hover('.cp-page-node[data-link="manifest"]');
        },
    },
    {
        // A component under the pointer, DESCRIBED. The sheet carries no resting marks, so
        // pointing is how it is interrogated, and the answer is a tooltip at the cursor rather than
        // a strip under a sheet that is taller than the fold. Invisible to every other shot: the
        // tip only exists under a pointer, and it is built by cloning that node's audit row.
        fixture: "serve-design-page",
        suffix: "selected",
        apply: async (page) => {
            // HOVERED, not clicked: pointing is what describes a node now, and clicking navigates.
            // A linked node, deliberately: its strip carries a code path and a link out, which is
            // the full state. An unlinked one would shoot the degenerate half of the same card.
            await page.hover('.cp-page-node[data-link="manifest"]');
            await page.waitForSelector(".cp-page-tip:not([hidden]) .cp-page-tip-card");
            // The overlay is a real link now — that is what makes clicking it GO, and what makes a
            // middle click, a modifier click and the status-bar preview work. A `<button>` here
            // would pass every pixel assertion and none of that.
            const tag = await page.locator('.cp-page-node[data-link="manifest"]').first().evaluate(
                (el) => el.tagName.toLowerCase() + "|" + (el.getAttribute("href") || ""),
            );
            expect(tag.startsWith("a|")).toBe(true);
            expect(tag).toContain("/p/");
        },
    },
    {
        // The flip, thrown to the design's own drawing. The page OPENS on our renders, so the spec
        // lane is the half no ordinary shot holds — and the two lanes are the same sheet in the
        // same layout, which is exactly the pair a pixel diff is good at telling apart.
        fixture: "serve-design-page",
        suffix: "design-lane",
        apply: async (page) => {
            // Clicked by its LABEL, not `check()` on the input: the lane radios are visually
            // removed (the segmented pill is the control a reader sees), so the input itself is
            // not an actionable target. Driving the label is both what a person does and what
            // keeps this honest — a pill that stopped forwarding its click would fail here.
            await page.click('.cp-page-lane label:has([data-cp-page-lane][value="design"])');
            // The design's own drawing must come BACK, not just our renders go away: a lane that
            // hid both would read as a blank slot rather than a flip.
            await page.waitForFunction(
                () => document.querySelectorAll("svg .cp-page-replaced").length > 0,
            );
            await page.waitForFunction(
                () => !document.querySelector(".cp-page-stage").classList.contains("cp-page-hide-design"),
            );
        },
    },
    {
        // The diff lane: one number per slot, saying how far our render is from the design's own
        // drawing of that node. Every part of it is produced at runtime — the sheet is cropped per
        // node, rasterised and scored in the browser — so the committed HTML holds none of it and
        // a change to the scoring, the bands or the badge would move no baseline without this.
        fixture: "serve-design-page",
        suffix: "diff-lane",
        apply: async (page) => {
            await page.click('.cp-page-lane label:has([data-cp-page-lane][value="diff"])');
            // Hold for the settled numbers rather than the "…" placeholders, and require at least
            // one: `every()` over an empty list is true, so without the length check this would go
            // green if the scoring never ran at all.
            await page.waitForFunction(() => {
                const badges = Array.from(document.querySelectorAll(".cp-page-score"));
                return (
                    badges.length > 0 &&
                    badges.every((b) => b.textContent !== "…" && b.textContent !== "")
                );
            });
            // Only nodes we can actually draw get a number. A badge on a node with no render would
            // be reporting "absent" as "100% different", which is the one wrong thing this readout
            // could say.
            const badges = await page.locator(".cp-page-score").count();
            const renders = await page.locator(".cp-page-render").count();
            expect(badges).toBe(renders);
            // A control that navigates must not announce itself as a pressed toggle. It is a real
            // anchor in every lane now, so this is checked on the element itself rather than on a
            // role swapped in for one lane — and `aria-pressed` must be gone everywhere, since a
            // link that claims a pressed state is describing something it does not have.
            const semantics = await page.evaluate(() => {
                const spots = Array.from(document.querySelectorAll(".cp-page-node"));
                return {
                    anchors: spots.filter((s) => s.tagName.toLowerCase() === "a").length,
                    pressed: spots.filter((s) => s.hasAttribute("aria-pressed")).length,
                };
            });
            expect(semantics.pressed).toBe(0);
            expect(semantics.anchors).toBeGreaterThan(0);
            // Where the lane leads. Asserted on the anchor rather than by clicking it, so this
            // stays a capture rather than a navigation — but it still fails if the deep link stops
            // naming the viewer's Figma comparison.
            const href = await page
                .locator(".cp-page-diff-link")
                .first()
                .getAttribute("href");
            expect(href).toContain("mode=spec");
            expect(href).toContain("specView=diff");
            // THE DIRECTION OF THE NUMBER. `scoreImages` answers with a MATCH percentage —
            // identical images score 100 — and this lane reports DRIFT, so it inverts. Shipping
            // that backwards printed "100.0%" in red for a perfect match and green for a total
            // mismatch, and no screenshot caught it because the fixture's numbers looked plausible
            // either way. Scoring an image against ITSELF is the assertion that cannot be fooled:
            // if the scorer's convention ever flips, this fails instead of the badge lying.
            const selfMatch = await page.evaluate(async () => {
                const img = document.querySelector(".cp-page-render");
                const result = await window.ComposePreviewCompare.scoreImages(img, img);
                return result.percent;
            });
            expect(selfMatch).toBeGreaterThan(99);
            // …and the badge for that same node must therefore read near zero drift, not near 100.
            const worst = await page.evaluate(() => {
                const values = Array.from(document.querySelectorAll(".cp-page-score"))
                    .map((b) => parseFloat(b.textContent));
                return Math.max(...values);
            });
            expect(worst).toBeLessThanOrEqual(100);
        },
    },
    {
        // "Only what we don't implement": the coverage read. Everything this catalog implements is
        // muted and the dashed-red outlines — the components on the sheet with no code behind them
        // — are what's left. Asking for the filter turns the outline layer on by itself, since a
        // filter over an unmarked sheet would show the reader nothing at all; that coupling is part
        // of what this shot pins.
        fixture: "serve-design-page",
        suffix: "unlinked-only",
        apply: async (page) => {
            // States compose on the already-loaded page: put the flip back on our renders so this
            // shot is about the filter alone.
            await page.click('.cp-page-lane label:has([data-cp-page-lane][value="code"])');
            await page.check("[data-cp-page-unlinked]");
            await page.waitForFunction(
                () => document.querySelector(".cp-page-stage").classList.contains("cp-page-outlines-on"),
            );
        },
    },
    {
        // The audit list, opened. It ships collapsed now, so the inventory every row of which used
        // to be the bottom half of this page would otherwise be diffed by nothing at all.
        fixture: "serve-design-page",
        suffix: "nodes-open",
        apply: async (page) => {
            await page.uncheck("[data-cp-page-unlinked]");
            await page.click(".cp-page-nodes > summary");
            await page.waitForSelector(".cp-page-nodes[open]");
        },
    },
    {
        // ZOOM, LEVEL ONE. A specimen sheet is drawn at the size the design drew it and lands in a
        // column a fraction of that, so the surface is unreadable until it can be zoomed — and every
        // part of that is produced at runtime by a transform on `.cp-page-canvas`, so none of it is
        // in the committed HTML and a regression would otherwise move no baseline at all.
        //
        // Driven as a reader drives it: a real double-click on the left card's own ground (its
        // padding, outside every slot), which is the gesture the hint next to the controls names.
        // The card fills the stage, its neighbour is cropped away, and the corner readout appears —
        // the only thing on the page that says how far in you are.
        fixture: "serve-design-page",
        suffix: "zoom-section",
        // The pointer's resting place is incidental here; the claim is the framed card. Left where
        // the double-click put it, a later state's assertions would inherit a hover on the sheet.
        parkPointer: true,
        apply: async (page) => {
            const at = await sheetPoint(page, 65, 430);
            await page.mouse.dblclick(at.x, at.y);
            // The readout is the reader's own evidence, so assert on it rather than on the transform
            // string. Above 150% is the real claim: a drill that framed the whole SHEET (or a
            // wrapper the same size as it) would settle near 100 and look like a working feature.
            await page.waitForFunction(() => {
                // The TAG: the bar is `<cp-page-zoom>`, a Lit element in `cli/serve-web`, and it
                // is the element itself that hides at 1:1.
                const bar = document.querySelector("cp-page-zoom");
                const level = document.querySelector("[data-cp-page-zoom-level]");
                return bar && !bar.hidden && level && parseInt(level.textContent, 10) > 150;
            });
            // The overlays must have come WITH the sheet. They are placed in percentages of the
            // canvas, so this holds by construction — and it is exactly what breaks if the transform
            // is ever moved onto the SVG alone, which is the one way this feature can go subtly wrong
            // (the sheet zooms, every hit area stays behind, and no screenshot of the sheet notices).
            const aligned = await page.evaluate(() => {
                const spot = document.querySelector('.cp-page-node[data-cp-node="1:2"]');
                const node = Array.from(document.querySelectorAll("svg [data-node-id]")).find(
                    (el) => el.getAttribute("data-node-id") === "1:2",
                );
                const a = spot.getBoundingClientRect();
                const b = node.getBoundingClientRect();
                return Math.abs(a.left - b.left) + Math.abs(a.top - b.top);
            });
            expect(aligned).toBeLessThan(2);
        },
    },
    {
        // ZOOM, LEVEL TWO — the nesting. The same gesture again, now inside the card, lands on the
        // SLOT the specimen sits in rather than re-framing the card: one addressable level per
        // double-click, drilling the export's own `<g data-node-id>` tree the way Figma drills a
        // frame. Nothing in the markup describes those levels, which is why the fixture's export is
        // nested (a page holds cards, a card holds slots, a slot holds the component) — while it was
        // flat this gesture had nothing to walk.
        fixture: "serve-design-page",
        suffix: "zoom-nested",
        parkPointer: true,
        apply: async (page) => {
            const was = await zoomPercent(page);
            // The top slot's own padding: inside the slot panel, outside the component's hit area,
            // and — with the card now filling the stage — still somewhere the view actually shows. A
            // double-click ON the component would navigate to its preview instead: the first click of
            // it follows the overlay's anchor, which is deliberate and documented in `design-page.js`.
            const at = await sheetPoint(page, 110, 200);
            await page.mouse.dblclick(at.x, at.y);
            // STRICTLY deeper. A drill that resolved to the level already framed would leave the
            // number where it was, which is the failure this whole state exists to catch.
            await page.waitForFunction((before) => {
                const level = document.querySelector("[data-cp-page-zoom-level]");
                return level && parseInt(level.textContent, 10) > before;
            }, was);
        },
    },
    {
        // ⌘/Ctrl + WHEEL, zooming about the pointer — the continuous half of the gesture, and the one
        // that carries the sheet the rest of the way once a drill has framed a section. Reset first,
        // so the wheel is the only thing this shot is about.
        //
        // The modifier is the contract, not a flourish: a plain wheel over the stage must keep
        // scrolling the document, since the sheet is one element on a taller page and a surface that
        // swallowed the wheel would trap the reader inside it.
        fixture: "serve-design-page",
        suffix: "zoom-wheel",
        parkPointer: true,
        apply: async (page) => {
            await page.click("[data-cp-page-zoom-reset]");
            const at = await sheetPoint(page, 320, 215);
            await page.mouse.move(at.x, at.y);
            await page.keyboard.down("Control");
            for (let i = 0; i < 6; i++) await page.mouse.wheel(0, -120);
            await page.keyboard.up("Control");
            await page.waitForFunction(() => {
                const level = document.querySelector("[data-cp-page-zoom-level]");
                return level && parseInt(level.textContent, 10) > 300;
            });
            // Zoomed four-odd times over, the instrumentation must NOT have grown with the drawing:
            // a node's mark is counter-scaled by `--cp-page-zoom` so it stays a hairline over the
            // shape instead of becoming a slab that hides it.
            //
            // The OUTLINE, which is what a node is actually marked with — its `border` is 0, so an
            // earlier version of this assertion read the border width, got 0, and passed however
            // wrong the stylesheet was. Reading the property that paints is the difference between a
            // test and a decoration.
            const marks = await page.evaluate(() => {
                const stage = document.querySelector(".cp-page-stage");
                const spot = document.querySelector('.cp-page-node[data-cp-node="1:1"]');
                const style = getComputedStyle(spot);
                return {
                    zoom: parseFloat(getComputedStyle(stage).getPropertyValue("--cp-page-zoom")),
                    outline: parseFloat(style.outlineWidth),
                    offset: parseFloat(style.outlineOffset),
                };
            });
            expect(marks.zoom).toBeGreaterThan(3);
            // `2px ÷ zoom`, and its inset offset with it — which a browser then floors at one device
            // pixel, so one is the floor and not a miss. Without the counter-scale both stay at the
            // specified 2px while the transform paints them four times that, so this still fails the
            // moment the rule stops applying.
            expect(marks.outline).toBeLessThanOrEqual(1);
            expect(Math.abs(marks.offset)).toBeLessThanOrEqual(1);
        },
    },
    {
        // The way back out, and the reason the corner control exists at all: at 24x, with the sheet
        // panned somewhere unrecognisable, "where was I?" needs an answer that is one click and not a
        // reload. Reset restores exactly 1:1 and takes the control off the stage with it, since at
        // 1:1 there is nothing left to reset.
        fixture: "serve-design-page",
        suffix: "zoom-reset",
        apply: async (page) => {
            await page.click("[data-cp-page-zoom-reset]");
            // The bar does NOT vanish under the pointer that just pressed it: hiding a
            // focused button deletes the focused element, and the browser drops focus to
            // `<body>` — a keyboard reader who pressed Reset would lose the sheet. It
            // waits for focus to leave, which is what the blur below is.
            await page.waitForFunction(() => {
                const level = document.querySelector("[data-cp-page-zoom-level]");
                return level && parseInt(level.textContent, 10) === 100;
            });
            await page.evaluate(() => document.activeElement?.blur());
            await page.waitForFunction(() => document.querySelector("cp-page-zoom").hidden);
            // Back to the IDENTITY, not merely to something small: a reset that left a residual
            // translate would look right in a screenshot and mis-place every overlay measured after
            // the next resize. Asserted on the computed transform rather than by comparing the
            // canvas's box to the stage's — those legitimately differ by the stage's border.
            const view = await page.evaluate(() => {
                const canvas = document.querySelector(".cp-page-canvas");
                const stage = document.querySelector(".cp-page-stage");
                return {
                    transform: getComputedStyle(canvas).transform,
                    zoom: stage.style.getPropertyValue("--cp-page-zoom"),
                };
            });
            expect(["none", "matrix(1, 0, 0, 1, 0, 0)"]).toContain(view.transform);
            expect(parseFloat(view.zoom)).toBe(1);
        },
    },
    {
        // A render the server could not produce — a preview that throws, a daemon that fell over, a
        // 404. The page opens on the render lane, so the design's own drawing must come BACK in
        // that slot rather than leaving a hole where the whole claim is that something stands in
        // it. Driven by dispatching the same `error` event the browser fires, on an image that has
        // already loaded, so this exercises the production handler rather than a stub of it.
        fixture: "serve-design-page",
        suffix: "render-failed",
        apply: async (page) => {
            await page.click(".cp-page-nodes > summary");
            const failed = await page.evaluate(() => {
                const img = document.querySelector(".cp-page-render");
                if (!img) return null;
                img.dispatchEvent(new Event("error"));
                return img.getAttribute("data-cp-node");
            });
            // The node's own drawing is showing again and the broken image is out of the way.
            // Compared against THAT node rather than "any node", so a handler that restored the
            // wrong target — or every target — would fail here instead of passing on a count.
            await page.waitForFunction(
                (id) => {
                    const target = Array.from(document.querySelectorAll("svg [data-node-id]")).find(
                        (el) =>
                            el.getAttribute("data-node-id") === id ||
                            el.getAttribute("data-node-id") === String(id).replace(/:/g, "-"),
                    );
                    const img = document.querySelector(`.cp-page-render[data-cp-node="${id}"]`);
                    return (
                        !!target &&
                        !target.classList.contains("cp-page-replaced") &&
                        !!img &&
                        // COMPUTED display, not the `hidden` property. The property was true while
                        // the image was still painted on top of the restored drawing: the swap
                        // lane's `display: block` is a two-class selector and outranked the UA
                        // stylesheet's `[hidden]`. Asserting the property passed that bug straight
                        // through; asserting what the browser actually draws does not.
                        getComputedStyle(img).display === "none" &&
                        // The rest of the sheet must be untouched — this is one slot falling back,
                        // not the lane collapsing.
                        document.querySelectorAll("svg .cp-page-replaced").length > 0
                    );
                },
                failed,
            );
        },
    },
    {
        // A component opened onto its variants — the state the two deepest levels exist for, and
        // one the committed HTML cannot hold: components ship collapsed, so without this the
        // variant rows would never appear in a baseline at all.
        fixture: "serve-landing-tree-depth",
        suffix: "component-open",
        // The click that expands the component leaves the pointer on that row,
        // and the variant rows it reveals slide out from under it — so which
        // element ends up hovered was a coin flip per run (issue #3837). The
        // hover is incidental here; the claim is the revealed variant rows.
        parkPointer: true,
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
        // The sectioned catalog ON A PHONE, which is the shape most published design systems are
        // in and the one the phone layout costs the most: above 960px the tree is a sidebar beside
        // the grid, below it a full-width outline ABOVE the grid, and on a phone that outline put
        // the first card 526px down an 800px screen. Here the top level is a scrolling strip, the
        // levels below it are folded away, and the filter field that belongs to that sidebar has
        // been moved up into the toolbar row — three claims no 1024px baseline can hold, one of
        // them a DOM move. Shot FIRST, before the section-switching states below reach the page.
        fixture: "serve-landing-sections",
        suffix: "mobile",
        viewport: PHONE_VIEWPORT,
        apply: async () => {},
    },
    {
        // The filter field FOCUSED, which is the only way its ring is on screen to be diffed.
        //
        // The ring is drawn 5px outside the field (`outline: 3px` + `outline-offset: 2px`) and the
        // sidebar is a scroll container above 960px, so three of the field's four edges used to be
        // clipped by it — the top, because the field is the column's first row, and both sides,
        // because it is full-width and a scroll container clips the axis it was not given as well.
        // Nothing else in the suite focuses anything in this column, so the clipping survived every
        // capture: a ring that is not on screen cannot be missing from a baseline.
        //
        // The ring is a plain `:focus` rule, so focusing the field is enough to draw it — no
        // pointer needed, which is why this parks the mouse: the shot is about the ring, and a
        // cursor resting on some card behind it would be a second change in the same frame.
        // The render-history menu OPEN. A `<details>` ships closed, so a page screenshot captures
        // only the trigger — the list, which is the part most likely to break visually, would never
        // be diffed. Same reasoning (and the same shape) as `serve-viewer-revisions-open`.
        //
        // Opened through the summary rather than by setting `.open`, so the shot is a state a
        // reader can actually produce, and the caret's rotated treatment comes with it.
        fixture: "serve-viewer-history",
        suffix: "menu-open",
        apply: async (page) => {
            await page.waitForSelector(".cp-history-menu");
            await page.click(".cp-history-btn");
            await page.waitForSelector(".cp-history-menu[open] .cp-history-list");
        },
    },
    {
        // The parity page's visual-difference band with something IN it. The committed fixture's
        // one mapped pair scores clean, so the default shot above holds the "everything matches"
        // sentence and nothing else — the issues table, the worst-first order, the drift suffix and
        // the red score cell would be diffed by nothing, which is how a table that renders garbage
        // ships unnoticed.
        //
        // The scorer is stubbed rather than fed a deliberately-mismatched fixture pair: what is
        // under test here is `<cp-parity-scores>`'s rendering of a finding, and the metric itself
        // already has its own spec (`format-compare-scorer.spec.mjs`). Replacing the element
        // re-runs its scan the way a reload would, so nothing here reaches inside it.
        fixture: "serve-parity",
        suffix: "visual-findings",
        apply: async (page) => {
            await page.evaluate(() => {
                window.ComposePreviewCompare = {
                    scoreImageUrls: async () => ({ percent: 61.4, geometry: 5.2 }),
                };
                document
                    .querySelector("cp-parity-scores")
                    .replaceWith(document.createElement("cp-parity-scores"));
            });
            await page.waitForSelector("#cp-parity-score-issues tr");
        },
    },
    {
        // The PAGES pane. The design file's pages used to be a branch at the foot of the component
        // tree, below every family, component and variant — so on a real catalog they sat past a
        // hundred-odd rows of the inventory you were not looking for. They are a peer list now,
        // behind the second half of a segmented switch, and this is the only shot that holds it:
        // the committed page ships with Components selected, so the pages pane and the switch's
        // selected treatment would otherwise be diffed by nothing.
        fixture: "serve-landing-grouped",
        suffix: "pages-pane",
        apply: async (page) => {
            await page.click('.cp-pane-tab[data-pane="pages"]');
            await page.waitForSelector("#cp-pane-pages:not([hidden])");
        },
    },
    {
        // …and the pages pane FILTERED, which is the half of this that is not layout. The one
        // search box serves whichever pane is showing — before this the pages were the only list
        // in the column the filter could not reach — so the claim is that typing narrows them and
        // that the box says which list it is about to search ("Filter pages…").
        //
        // Runs against the same page as the state above, so it inherits the pages pane.
        fixture: "serve-landing-grouped",
        suffix: "pages-filtered",
        apply: async (page) => {
            await page.fill("#cp-search", "shape");
            await page.waitForFunction(() => {
                const rows = Array.from(
                    document.querySelectorAll("#cp-pane-pages .cp-tree-page"),
                );
                return (
                    rows.length > 1 &&
                    rows.some((r) => !r.parentElement.hidden) &&
                    rows.some((r) => r.parentElement.hidden)
                );
            });
        },
    },
    {
        // Found by a SECTION name. "corner" appears nowhere in either page's title — it is the name
        // of one grouping on the Shape sheet — so a page surfacing here is the whole reason the
        // sections are in the sidebar rather than only on the page itself. The page is kept and
        // OPENED, showing just the sections that matched: a hit you have to expand a twisty to see
        // is a hit the filter did not really surface.
        fixture: "serve-landing-grouped",
        suffix: "pages-section-match",
        apply: async (page) => {
            await page.fill("#cp-search", "corner");
            await page.waitForFunction(() => {
                const shape = document.querySelector(
                    '#cp-pane-pages .cp-tree-page[data-search="Shape"]',
                );
                const type = document.querySelector(
                    '#cp-pane-pages .cp-tree-page[data-search="Typography"]',
                );
                const kept = document.querySelectorAll(
                    "#cp-pane-pages .cp-page-sections li:not([hidden])",
                );
                return (
                    shape &&
                    !shape.parentElement.hidden &&
                    shape.getAttribute("aria-expanded") === "true" &&
                    type &&
                    type.parentElement.hidden &&
                    kept.length === 1
                );
            });
        },
    },
    {
        fixture: "serve-landing-sections",
        suffix: "filter-focus",
        parkPointer: true,
        apply: async (page) => {
            await page.locator("#cp-search").focus();
            await page.waitForFunction(
                () => document.activeElement?.id === "cp-search",
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
        // — the boxes only exist once `<cp-inspect-layers>` has fetched the (stubbed) products — so
        // without this shot a change to the overlay or legend would move no baseline at all.
        fixture: "serve-viewer-inspect",
        suffix: "layers",
        apply: async (page) => {
            // The Overrides drawer's groups open on demand; the checkboxes aren't clickable (or
            // visible in the shot) until the Overlays group is expanded.
            await openControlsDrawer(page);
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
        // The Motion lane open: a recorded interaction on the stage in place of the still.
        //
        // The committed HTML cannot hold this, and deliberately so — the capture's `src` is
        // assigned only on the lane's FIRST ENTRY, because for an APNG assigning `src` IS starting
        // playback, and a component page must not animate at a reader who did not ask. So the whole
        // feature would move no baseline without this shot: at rest the page differs from before by
        // one chip. Captured here to cover both halves — the chip pressed, and the frames actually
        // on the stage.
        //
        // The bytes are stubbed for the same reason the Source panel's are: the harness serves
        // pages, not a catalog with a `motion/` directory behind it. And it runs against a fixture
        // of its OWN for the same reason too: states run in order against one page, and this one
        // leaves the lane open — on `serve-viewer` it would have re-shot that fixture's later
        // `connecting` baseline with a capture on the stage instead of the render.
        fixture: "serve-viewer-motion",
        suffix: "motion-lane",
        apply: async (page) => {
            await page.route("**/motion/**", (route) =>
                route.fulfill({ path: motionPlaceholder, contentType: "image/apng" }),
            );
            await page.click("#cp-motion-chip");
            // The capture has to have DECODED before the shot, not merely been requested — a
            // screenshot taken between the two catches an empty stage and bakes it into a baseline
            // that then reads as "the lane shows nothing".
            await page.waitForFunction(() => {
                const el = document.getElementById("cp-motion-img");
                return el && !el.hidden && el.complete && el.naturalWidth > 0;
            });
            // …and then PLAYED OUT. The stub runs 14 frames at 40ms and stops on the last one (see
            // motionPlaceholder), so this waits past its 560ms total with a wide margin rather than
            // racing it — a margin is cheaper than a baseline that disagrees with itself run to run.
            await page.waitForTimeout(1500);
        },
    },
    {
        // The Source panel open: the usage code on the stage, with its note, Copy button and the
        // links onward to the playground and the whole sticker. The committed HTML cannot hold any
        // of it — the panel is server-rendered EMPTY on purpose, and filled only once the chip is
        // pressed and `/usage/<id>` answers — so without this shot the entire feature would move no
        // baseline. The response is stubbed here for the same reason the inspect layers' data
        // products are: the harness serves pages, not a catalog.
        fixture: "serve-viewer-source",
        suffix: "source-panel",
        apply: async (page) => {
            await page.route("**/usage/**", (route) =>
                route.fulfill({
                    status: 200,
                    contentType: "application/json",
                    body: JSON.stringify({
                        text: [
                            "import androidx.compose.material3.Button",
                            "import androidx.compose.material3.MaterialTheme",
                            "import androidx.compose.material3.Text",
                            "import androidx.compose.runtime.Composable",
                            "import androidx.compose.ui.tooling.preview.Preview",
                            "",
                            "@Preview",
                            "@Composable",
                            "fun FilledButton() = MaterialTheme {",
                            "  Button(onClick = {}) {",
                            '    Text("Filled")',
                            "  }",
                            "}",
                        ].join("\n"),
                        entryFunction: "FilledButton",
                        scaffoldsDeclared: true,
                        residue: [],
                        blobUrl: "https://github.com/example/catalog/blob/main/Buttons.kt",
                        playgroundHref: "/playground?from=compose-m3/com.example.ProfileCardPreview",
                    }),
                }),
            );
            await page.click("#cp-source-chip");
            await page.waitForFunction(
                () => !!document.querySelector("#cp-source-panel pre code"),
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
            await openThemeBar(page);
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
            // Park the pointer, as the mobile-menu shot does: it otherwise rests on the chip it
            // just clicked, and a hover wash on one segment is not what this shot is evidence for.
            // This used to be hidden — `viewer-drawers.js` re-inserted the row on load, which
            // detaches it and drops `:hover` with it, so the capture accidentally showed a
            // pointer-free page. `<cp-viewer-drawers>` only moves a row that needs moving, so the
            // hover now persists and has to be parked deliberately.
            await page.mouse.move(0, 0);
        },
    },
    // The three comparison views the spec lane offers once it is up. Each is drawn entirely at
    // runtime — pre-normalised canvases painted by `<cp-spec-compare>` — so the committed HTML holds
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
            // Off the view segment it just clicked — see `spec-lane` above.
            await page.mouse.move(0, 0);
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
        // The catalog landing ON A PHONE — the shot that says whether opening a catalog leads with
        // its components. Everything between the heading and the first card is chrome, and the two
        // chip GROUPS in that gap (the catalog's actions, and one Theme chip per declared theme)
        // each used to grow a line per chip; this fixture declares five themes, and a published
        // design system declares a dozen. Shot FIRST, on the untouched page, so it is the resting
        // catalog rather than one of the daemon/theme-error states below it.
        fixture: "serve-landing-declared-themes",
        suffix: "mobile",
        viewport: PHONE_VIEWPORT,
        apply: async () => {},
    },
    {
        // …and that bar's `⋮` OPEN. The phone bar is one row because the navigation — Catalogs,
        // Status, GitHub, the page's own action, Settings — collapses behind that button, so the
        // menu is where all of it went: if it stopped opening, or opened empty, the bar would
        // still look right in every other shot. Only the initial closed state is script-set
        // (`ServeWeb.siteMenuCollapseScript`); the toggle itself is a bare `<details>`, and
        // clicking the summary is what a visitor does.
        fixture: "serve-landing-declared-themes",
        suffix: "mobile-menu",
        viewport: PHONE_VIEWPORT,
        apply: async (page) => {
            await page.click("#cp-site-menu > summary");
            await page.waitForSelector("#cp-site-menu[open] + .cp-site-menu-panel");
            // The pointer rests on the summary otherwise, which is a hover state on the button and
            // not part of what this shot is about.
            await page.mouse.move(0, 0);
        },
    },
    {
        // The phone toolbar's Theme menu, open. Folded, that pill is the ONLY thing on the page
        // that says which theme the grid is on, and its label is written at runtime from whichever
        // chip the landing's own script marked pressed (`<cp-catalog-toolbar>`), so a shot of the
        // closed pill proves neither half. Opening it puts both on one baseline: the chips that
        // were a five-row wall, and the value the pill carries while they are away.
        fixture: "serve-landing-declared-themes",
        suffix: "mobile-theme",
        viewport: PHONE_VIEWPORT,
        apply: async (page) => {
            // The state above left the bar's own menu open; they are two menus over one page.
            await page.click("#cp-site-menu > summary");
            await page.click(".cp-catalog-theme > summary");
            await page.waitForSelector(".cp-catalog-theme[open] + .cp-theme");
            await page.mouse.move(0, 0);
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
        // (the press timing, the overlay, the chip) is the real `<cp-catalog-live>` doing its job.
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
        // A lane the server REFUSED: the card explains itself in place rather than silently going
        // back to being a link, which would be indistinguishable from a press that never
        // registered.
        //
        // Captured because nothing else renders this surface. The message is the one the viewer
        // shows for the same close code, and the two drifted apart unnoticed for exactly as long as
        // neither had a shot — a wording change here moved no baseline at all before this state
        // existed.
        fixture: "serve-landing-live",
        suffix: "live-refused",
        apply: async (page) => {
            await page.addStyleTag({
                content: "*, *::before, *::after { transition-duration: 0ms !important; }",
            });
            // States run in order against ONE page, and `live-card` above leaves its session
            // holding this very card — a press on a live card belongs to the canvas, so without
            // this the gesture below never starts.
            await page.keyboard.press("Escape");
            await page.waitForSelector(".cp-card-live", { state: "detached" });
            await page.evaluate(() => {
                // 1006, a bare abnormal close — typically a proxy 502 on the WS upgrade, and the
                // branch that fires most. It is also the longest of the four messages, so this shot
                // is where the box's wrapping at card width is pinned.
                window.WebSocket = class {
                    constructor() {
                        this.readyState = 1;
                        setTimeout(() => this.onclose?.({ code: 1006 }), 0);
                    }
                    send() {}
                    close() {}
                };
            });
            const card = page.locator(".cp-grid .cp-card").first();
            const box = await card.boundingBox();
            await page.mouse.move(box.x + box.width / 2, box.y + box.height / 3);
            await page.mouse.down();
            await page.waitForSelector(".cp-live-error");
            await page.mouse.up();
        },
    },
    {
        // Keep the full-page export control expanded so its supported formats and future visual
        // changes are visible to the screenshot diff instead of hidden in a closed <details>.
        fixture: "serve-viewer-catalog-knobs",
        suffix: "scroll-full-page",
        apply: async (page) => {
            await openControlsDrawer(page);
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
            await openControlsDrawer(page);
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
            for (const kind of ["layout", "typography", "theme"]) {
                await page.check(`[data-cp-annotation-kind="${kind}"]`);
            }
            // The theme layer had a box and a legend row built for it and no control able to reveal
            // either — CSS hid it unconditionally. Assert the drawn state, not just the checkbox, so
            // a future toggle added without its display rule fails here instead of shipping invisible.
            await expect(page.locator(".cp-annotation--theme")).toHaveCount(1);
            await expect(
                page.locator(".cp-annotation-entry--theme"),
            ).toHaveCount(1);
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
            await openControlsDrawer(page);
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
    {
        // The player wall's OTHER diff path, and the only one with no coverage of any kind: picking
        // a player as the reference means nothing was precomputed, so the two renders are decoded
        // onto a canvas and diffed here. `diff-baked` above never reaches that code — it replays
        // the offline run's PNGs — so an in-browser pass that threw, or produced no image at all,
        // would leave every shot in this suite unchanged.
        //
        // The harness serves ONE placeholder for every `/rc-compare/` URL, so the honest numbers
        // here are 0.00%: what this holds is the pipeline (load → canvas → getImageData → diff →
        // data URL → an `<img>` that renders) and the status line that admits an in-browser number
        // is not the build's measurement. The arithmetic is `pixelDiff.test.ts`'s job, down to the
        // bytes of the red overlay.
        fixture: "serve-rc-lanes",
        suffix: "diff-player",
        apply: async (page) => {
            await page.click('[data-rc-ref="cmp-jvm"]');
            // Every started row FINISHED, not "the first diff image appeared". A row measures its
            // lanes one after another, and several rows are in flight at once, so the first image
            // lands while most of the wall is still decoding — a baseline taken there would hold
            // whichever subset happened to win the race that run, and re-diff itself forever.
            // `<cp-rc-lanes>` marks each row `pending` then `done` for exactly this.
            await page.waitForFunction(() => {
                const started = document.querySelectorAll(".cp-rc-row[data-scored]");
                const done = document.querySelectorAll('.cp-rc-row[data-scored="done"]');
                return started.length > 0 && started.length === done.length;
            });
            // …and then the diff images themselves. `data-scored="done"` covers the COMPUTATION:
            // the element sets each `img.src` to a fresh data URL and moves on without awaiting the
            // decode, and the runner's generic `document.images` wait already ran, before any state
            // was applied. Screenshotting between the two would catch empty diff slots — the same
            // race one layer down.
            await page.waitForFunction(() =>
                Array.from(document.images).every((img) => img.complete),
            );
        },
    },
    {
        // The viewer's disclosures OPENED. The committed fixture captures the resting state — every
        // control folded behind the title bar — so what the toggles exist to reveal would be
        // diffed by nothing, and this is the only shot in which they carry their expanded (tonal)
        // treatment.
        //
        // It used to open the state-axis fold and the theme bar. #3893 replaced both: the variant
        // subtree moved into the component list, and the theme bar became a menu hanging off its
        // own summary. The state follows them rather than keeping a click on `#cp-axes-toggle`,
        // which has not existed since — and which is why this shot, and eleven others, timed out
        // on every branch.
        fixture: "serve-viewer-axes-folded",
        suffix: "disclosures-open",
        apply: async (page) => {
            await page.click("#cp-nav-toggle");
            await page.click("#cp-theme-toggle");
            await page.waitForSelector(".cp-viewer.cp-nav-open");
            await expect(page.locator("#cp-theme-bar")).toBeVisible();
        },
    },
    {
        // The cross-product subtree OPENED. Its whole claim is the row LABELS: with both axes in
        // play, the row that resets the state and the row that resets the props are both "Default"
        // unless each names both coordinates, and the render on screen is labelled by whichever
        // axis reached it first. That is invisible while the subtree is folded — and it arrives
        // folded, being six rows — so without this shot the naming rule is diffed by nothing.
        fixture: "serve-viewer-cross-product",
        suffix: "subtree-open",
        apply: async (page) => {
            // The subtree moved into the component list in #3893 (`.cp-nav-current`), which is
            // where those labels are read now; the fold it used to live in is gone.
            await page.click("#cp-nav-toggle");
            await expect(
                page.locator(".cp-nav-current .cp-tree-variants"),
            ).toBeVisible();
        },
    },
    {
        // …and the component list CLOSED, which is new: above 1100px the list used to be nailed
        // open by CSS with its toggle hidden, so "the stage with the 240px column given back" is a
        // layout no baseline has ever held. Widened past that breakpoint first — the harness runs
        // at 1024 by default, where the list is already hidden and closing it would prove nothing.
        // States run in order against the SAME page, so this one re-folds what the state above
        // opened; otherwise it would shoot two changes at once and diff neither cleanly.
        fixture: "serve-viewer-axes-folded",
        suffix: "nav-closed",
        apply: async (page) => {
            // Re-folds what the state above opened — the theme menu, and then the list itself —
            // so this shoots one change rather than two.
            await page.click("#cp-theme-toggle");
            await page.setViewportSize({ width: 1280, height: 900 });
            await page.click("#cp-nav-toggle");
            await page.waitForSelector(".cp-viewer.cp-nav-closed");
        },
    },
    {
        // The component page ON A PHONE, and the claim is simply that the render is on screen. Two
        // things stand between the title and the stage at this width: the disclosure pills, which
        // are now every control the viewer has and wrapped to two rows twelve pixels short of
        // fitting on one, and the renderer row, which wraps to three. Both are `@media` decisions
        // no 1024px baseline can hold.
        //
        // Shot at the END of this fixture's states — it has none of its own today, and a viewport
        // change here is undone by the runner regardless, but a mobile shot that also depended on
        // a preceding state's mutation would be two claims in one baseline.
        fixture: "serve-viewer-variants",
        suffix: "mobile",
        viewport: PHONE_VIEWPORT,
        apply: async () => {},
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
                    if (!svg && fixture === "serve-design-page") {
                        return route.fulfill({
                            path: designRenderPlaceholder,
                            contentType: "image/png",
                        });
                    }
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

            // The design page's renders are `loading="lazy"` — a live catalog serves one daemon
            // render per node and the sheet is taller than the fold, so the production page must
            // not ask for all of them at once. A full-page screenshot does not itself scroll, so
            // without a pass down the page the below-fold slots would shoot empty and the generic
            // image wait below would burn its whole timeout on images that never started.
            if (fixture === "serve-design-page") {
                await page.evaluate(async () => {
                    const step = window.innerHeight;
                    for (let y = 0; y < document.body.scrollHeight; y += step) {
                        window.scrollTo(0, y);
                        await new Promise((r) => requestAnimationFrame(r));
                    }
                    window.scrollTo(0, 0);
                });
            }

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
            // The parity page's visual-difference band is asynchronous the same way — fetch,
            // decode and score every mapped render/reference pair — and the settled result is the
            // entire point of it. Without this wait the shot is the "Checking N mapped
            // comparison(s)…" line, which looks identical whether the scan works, scores
            // everything wrong, or has been deleted: no coverage at all for the summary sentence,
            // the issues table, or the per-row score cells.
            if (fixture === "serve-parity") {
                await page
                    .waitForFunction(() => {
                        const status = document.getElementById(
                            "cp-parity-score-status",
                        );
                        // Both in-flight lines start "Check"; every settled one starts with a
                        // count or "All".
                        return status && !/^Check/.test(status.textContent.trim());
                    })
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

            // THE SWAP IS TO SCALE. Asserted on the default shot rather than as a state of its
            // own, because the page already opens on the render lane — this is the picture being
            // captured, not another one.
            //
            // What is checked is the render's DRAWN box against the design node's, not the
            // `<img>`'s. Those are different boxes, and the difference is the whole behaviour: an
            // image is a canvas with the component somewhere inside it, so an `<img>` sized exactly
            // to the slot can still draw a component half that size. Reading the drawn box back
            // out of the ink measurement is what fails if the fit ever goes back to the canvas —
            // this catalog's own Shape page shipped that way, drawing a semicircle at 58% of the
            // size the design drew it while every lane, number and screenshot looked plausible.
            //
            // Uniform scale is asserted too: the fit must never stretch. A render whose aspect
            // differs from the design's is a finding about the code, and a fit that squared it up
            // would report every component as the right shape.
            if (fixture === "serve-design-page") {
                const fits = await page.evaluate(() => {
                    const stage = document.querySelector(".cp-page-stage");
                    const svg = stage.querySelector("svg");
                    return Array.from(stage.querySelectorAll(".cp-page-render"))
                        .filter((img) => !img.hidden && img.naturalWidth > 0)
                        .map((img) => {
                            const id = img.getAttribute("data-cp-node");
                            const node = Array.from(svg.querySelectorAll("[data-node-id]")).find(
                                (el) => el.getAttribute("data-node-id") === id,
                            );
                            if (!node) return null;
                            const slot = node.getBoundingClientRect();
                            const drawn = img.getBoundingClientRect();
                            // The placeholder's ink box, as a fraction of its canvas — the stub is
                            // committed, so these are fixed: a 200×112 component in 240×240.
                            const inkW = (drawn.width * 202) / 240;
                            const inkH = (drawn.height * 114) / 240;
                            return {
                                id,
                                filled: Math.max(inkW / slot.width, inkH / slot.height),
                                aspect: inkW / inkH,
                            };
                        })
                        .filter(Boolean);
                });
                expect(fits.length).toBeGreaterThan(0);
                for (const fit of fits) {
                    // One axis is flush against the slot, and neither spills out of it.
                    expect(fit.filled).toBeGreaterThan(0.98);
                    expect(fit.filled).toBeLessThan(1.02);
                    // Never stretched: the stub's own 202:114 stays 202:114 in the slot.
                    expect(fit.aspect).toBeGreaterThan(202 / 114 - 0.05);
                    expect(fit.aspect).toBeLessThan(202 / 114 + 0.05);
                }
            }

            await page.screenshot({
                path: resolve(outDir, `${fixture}.${theme}.png`),
                fullPage: true,
                animations: "disabled",
            });

            // Extra runtime states of this same fixture, shot from the already-loaded page.
            for (const state of FIXTURE_STATES.filter((s) => s.fixture === fixture)) {
                // A state may declare its own viewport (`PHONE_VIEWPORT` above). Restored after
                // the shot rather than left in place, because states run in order against the SAME
                // page: a mobile shot that leaked its 412px viewport would silently re-capture
                // every state after it at phone width. Restored to whatever the page was actually
                // at — the spec's own `nav-closed` state widens to 1280 mid-list, so a constant
                // here would undo that instead of preserving it.
                const restoreViewport = state.viewport ? page.viewportSize() : null;
                if (state.viewport) {
                    await page.setViewportSize(state.viewport);
                    // The mobile layout is JS-assisted: `viewer-drawers.js` resolves the component
                    // list against the breakpoint on a `matchMedia` change, and the sticky rows
                    // reflow. Give the page a frame to settle before the shutter.
                    await page.evaluate(
                        () => new Promise((r) => requestAnimationFrame(() => r())),
                    );
                }
                await state.apply(page);
                // Opt-IN pointer parking (issue #3837). `page.click()` leaves
                // the mouse where it clicked, and an `apply` that expands
                // something moves fresh rows UNDER that resting pointer — so
                // `serve-landing-tree-depth-component-open` shot whichever of
                // the component row or the revealed "Default" link happened to
                // land there, a ~5,800px difference between two runs of the
                // same code.
                //
                // Opt-in, not opt-out, and that is load-bearing. Parking by
                // default was tried and moved 53 of 196 captures, because a
                // large minority of these states are ABOUT the resting pointer
                // — `serve-home-index-card-hover`, `serve-design-page-hover`,
                // `serve-landing-public-card-hover`, both `serve-landing-live`
                // long-press states. Defaulting to park silently guts those:
                // they keep passing while capturing the un-hovered page the
                // baseline already had. So a state declares `parkPointer` only
                // when its pointer position is incidental.
                if (state.parkPointer) {
                    await page.mouse.move(0, 0);
                }
                await page.screenshot({
                    path: resolve(
                        outDir,
                        `${fixture}-${state.suffix}.${theme}.png`,
                    ),
                    fullPage: true,
                    animations: "disabled",
                });
                if (restoreViewport) await page.setViewportSize(restoreViewport);
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

    // The drawer these groups live in starts closed (see `openControlsDrawer`), so opening the
    // groups alone leaves their controls hidden and unfillable.
    await openControlsDrawer(page);
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
    // were wired to nothing. This fixture declares enough themes that the bar arrives folded, so
    // the chip is one disclosure click away.
    await openThemeBar(page);
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
    // The knob controls live in the Overrides drawer, behind two shut things: the drawer column
    // itself (see `openControlsDrawer`) and the group inside it.
    await openControlsDrawer(page);
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

test("contract · the sidebar filter follows the pane it is pointed at", async ({
    page,
}) => {
    for (const [name, contentType] of SERVE_ASSETS) {
        await page.route(`**/assets/serve/**/${name}`, (route) =>
            route.fulfill({ path: resolve(serveAssetsDir, name), contentType }),
        );
    }
    await page.route("**/render/**", (route) =>
        route.fulfill({ path: renderPlaceholder, contentType: "image/png" }),
    );
    await page.goto("/preview-harness/fixtures/pages/serve-landing-grouped.html");

    const shownCards = () =>
        page.evaluate(
            () =>
                Array.from(document.querySelectorAll(".cp-card")).filter(
                    (c) => !c.hidden,
                ).length,
        );
    const shownPages = () =>
        page.evaluate(
            () =>
                Array.from(
                    document.querySelectorAll("#cp-pane-pages .cp-tree-page"),
                ).filter((p) => !p.parentElement.hidden).length,
        );

    const allCards = await shownCards();
    const allPages = await shownPages();
    expect(allCards).toBeGreaterThan(1);
    expect(allPages).toBeGreaterThan(1);

    // On Components the box is a grid query, exactly as it has always been.
    await page.fill("#cp-search", "button");
    await expect.poll(shownCards).toBeLessThan(allCards);

    // Switching to Pages re-points it: the same text now narrows the pages, and RELEASES the grid
    // rather than leaving it filtered by a query that was never about it. Getting this wrong is
    // not subtle — it answers a page search with "No previews match your filter" under a sidebar
    // that just found the page.
    await page.click('.cp-pane-tab[data-pane="pages"]');
    await expect.poll(shownCards).toBe(allCards);
    await page.fill("#cp-search", "shape");
    await expect.poll(shownPages).toBeLessThan(allPages);
    await expect.poll(shownPages).toBeGreaterThan(0);
    expect(await shownCards()).toBe(allCards);

    // …and switching back puts both right again.
    await page.click('.cp-pane-tab[data-pane="components"]');
    await expect.poll(shownPages).toBe(allPages);
    await expect.poll(shownCards).toBeLessThan(allCards);
});

test("contract · a page branch opens from the keyboard, and Enter still follows it", async ({
    page,
}) => {
    for (const [name, contentType] of SERVE_ASSETS) {
        await page.route(`**/assets/serve/**/${name}`, (route) =>
            route.fulfill({ path: resolve(serveAssetsDir, name), contentType }),
        );
    }
    await page.route("**/render/**", (route) =>
        route.fulfill({ path: renderPlaceholder, contentType: "image/png" }),
    );
    await page.goto("/preview-harness/fixtures/pages/serve-landing-grouped.html");
    await page.click('.cp-pane-tab[data-pane="pages"]');

    const branch = page.locator("#cp-pane-pages .cp-page-branch > .cp-tree-page").first();
    // Collapse it first, so what follows is the state every page but the first ships in.
    await branch.evaluate((el) => el.setAttribute("aria-expanded", "false"));
    await branch.focus();

    // The fold is pointer-only (a keyboard click reports offsetX 0, inside the twisty), so without
    // an explicit key a collapsed branch would be mouse-only — its sections are `display: none`
    // while it is shut. Right opens and Left closes, the two keys the component tree already binds.
    await page.keyboard.press("ArrowRight");
    await expect(branch).toHaveAttribute("aria-expanded", "true");
    await expect(
        page.locator("#cp-pane-pages .cp-page-sections a").first(),
    ).toBeVisible();
    await page.keyboard.press("ArrowLeft");
    await expect(branch).toHaveAttribute("aria-expanded", "false");

    // …and Enter still does what a link does. This is the half that regressed first: the fold used
    // to swallow keyboard activation, so Enter expanded the row instead of opening the sheet.
    await page.keyboard.press("Enter");
    await page.waitForURL(/\/pages\/shape/);
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
    await openThemeBar(page);
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

test("contract · the render history is a menu in the toggle row, and the fit cap agrees", async ({
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

    // The fixture carries an INLINE history payload, which `<cp-history-menu>` reads without a
    // round trip, so the menu lands close behind the first fit. Strip the inline payload so the
    // fetch path runs instead: that is the delivery-backed shape, where the manifest arrives over
    // the network and the menu lands long after viewer.js has measured and fitted.
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
    // The menu arrives asynchronously, from the fetch above.
    await page.waitForSelector(".cp-history-menu");
    await page.waitForTimeout(200);

    const { applied, expected, inToggleRow, stageSibling, closed } =
        await page.evaluate(() => {
            const stage = document.querySelector(".cp-stage");
            const menu = document.querySelector(".cp-history-menu");
            const viewer = document.querySelector(".cp-viewer");
            const top = stage.getBoundingClientRect().top + (window.scrollY || 0);
            return {
                applied: parseInt(
                    document.getElementById("cp-img").style.maxHeight,
                    10,
                ),
                // fitCap()'s own arithmetic, re-run against the geometry as it stands NOW.
                expected: Math.max(320, Math.round(window.innerHeight - top - 64)),
                inToggleRow: !!menu.closest(".cp-head-toggles"),
                // A band of its own in the viewer's own column is the strip coming back. Being
                // inside the toggle row means it necessarily PRECEDES the stage in document order,
                // so document position proves nothing here — what distinguishes the two shapes is
                // whether it is a sibling of the stage or a control inside a row that already
                // existed.
                stageSibling: menu.parentNode === viewer.parentNode,
                closed: !menu.open,
            };
        });

    // The history is a MENU beside Revision, not a strip above the render. Asserted structurally
    // because that is the regression: the timeline used to be a row of dated chips under the
    // viewer bar, and restoring it there costs the width above the preview and puts a wall of
    // chips back on a page that had just replaced one with a dropdown.
    expect(inToggleRow).toBe(true);
    expect(stageSibling).toBe(false);
    // And it ships closed, so it costs one control until someone asks for the list.
    expect(closed).toBe(true);
    // The late arrival must still leave the fit cap agreeing with the geometry — the row can wrap
    // when it takes another control, and a wrapped row moves the stage exactly as the strip did.
    expect(applied).toBe(expected);
});
