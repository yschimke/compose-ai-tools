// The preview viewer: the stage, its lanes, and every control that changes what is on it.
//
// Ported from the last hand-written `assets/*.js`. It is still one long imperative module rather
// than a Lit element, and deliberately so: the viewer renders NO markup of its own. Every control
// on the page is server-rendered by `ServeWeb.viewerPage`, and this file is behaviour over that
// markup — the same shape `format-compare.js` kept when it became generated. A `render()` that
// returned nothing would be ceremony, and moving the markup into a template would be a rewrite of
// the server page, not a port of this file.
//
// What the move buys is the type check and the seam: the DOM-free decisions now come in through
// `viewer/rules.js` as ordinary imports, each with a test file beside it, instead of through the
// `window.cpViewerQuery` handle that existed only because this file used to live in another build.

// Types only: the player bundle is script-injected at runtime, never imported.
import type { RcPlayer, RemoteContext } from "./rc/player.js";
import * as rules from "./viewer/rules.js";

// Typed handles onto the server-rendered markup this file drives.
//
// `must` is for the handles a viewer page ALWAYS renders and this file has always used unguarded —
// the stage image, its canvas, the status line. It asserts rather than checks because a guard would
// invent a fallback path that has never run: without these the page is not a viewer at all, and the
// hand-written file threw on first use just the same. `may` is for everything a particular preview
// may not offer — a Wasm lane, a design spec, a motion capture — and every caller of those already
// guards. Keeping the two apart is the point: the `| null` is what keeps those guards honest, and
// collapsing them either way would lose a real distinction the page makes.
//
// Inside a lane, a `may` handle is asserted with `!` — `wasmFrame!.contentWindow`, `specImg!.src`.
// That is not a shortcut around the guard, it is the lane's own precondition: the server emits a
// lane's elements as a SET, so `#cp-wasm` exists on exactly the pages `#cp-wasm-toggle` does, and
// every one of those bodies is already behind `wasmActive()` or the `if (wasmToggle)` wiring. The
// compiler cannot read that fact off `wasmActive()`, and re-guarding each use would add a second,
// never-taken branch per line — noise that also reads as though the pairing were in doubt.
function must<T extends HTMLElement>(id: string): T {
    return document.getElementById(id) as T;
}
function may<T extends HTMLElement>(id: string): T | null {
    return document.getElementById(id) as T | null;
}

/** A bag of `/render` override parameters, keyed by the daemon's own parameter names. */
type Overrides = Record<string, string>;

/** Any form control on the viewer's panels: a knob row, an RC seed, an overlay tick, a mode radio. */
type Control = HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement;

/** Every control matching a panel selector. */
function controls(selector: string): NodeListOf<Control> {
    return document.querySelectorAll<Control>(selector);
}

/** Controls that are always a checkbox or a radio — the overlay ticks and the mode radios. */
function ticks(selector: string): NodeListOf<HTMLInputElement> {
    return document.querySelectorAll<HTMLInputElement>(selector);
}

/**
 * A control's value as every override map here wants it.
 *
 * A checkbox reports its tick as the string the daemon parses; everything else reports its text.
 * The `instanceof` is what keeps that honest — a `<select>` knob has no `checked` at all, and its
 * `type` ("select-one") could never have matched anyway.
 */
function controlValue(el: Control): string {
    if (el instanceof HTMLInputElement && el.type === "checkbox")
        return el.checked ? "true" : "false";
    return el.value;
}

/**
 * A render the server REFUSED because it could not apply the overrides asked for (#3449).
 *
 * `cpDropped` names the parameters it dropped, so the message can say which control is not being
 * honoured rather than "render failed" — the preview is fine, the live lane is not. `cpTerminal`
 * marks a 409: this preview has no live lane at all, so retrying never helps.
 */
interface DroppedOverridesError extends Error {
    cpDropped?: string;
    cpTerminal?: boolean;
}

/**
 * The `/usage/<id>` payload, mirroring `UsageSnippetResponse` in `ServeHttpServer.kt`.
 *
 * Every field is optional here, unlike on the server: this is JSON off the wire, and the lane is
 * built to degrade — an older server, or a snippet the catalog could not derive, still renders the
 * panel with its note rather than throwing on a missing key.
 */
interface UsageSnippet {
    text?: string;
    entryFunction?: string | null;
    /** `false` when the catalog has not declared what its own helpers mean in plain Compose. */
    scaffoldsDeclared?: boolean;
    /** Catalog-local helpers left in the snippet, which will not resolve outside it. */
    residue?: string[];
    blobUrl?: string | null;
    playgroundHref?: string | null;
}

/** The small CodeMirror surface the read-only Source lane uses. */
interface SourceCodeMirror {
    getWrapperElement(): HTMLElement;
}

/**
 * CodeMirror is a selectively loaded, vendored global rather than a bundle import. Keep the
 * declaration deliberately narrow: the playground owns the full editor API; this lane only needs
 * the constructor and its generated wrapper.
 */
interface SourceCodeMirrorFactory {
    (place: HTMLElement, options: Record<string, unknown>): SourceCodeMirror;
}

declare global {
    interface Window {
        CodeMirror?: SourceCodeMirrorFactory;
    }
}

/** One live-lane input message, in the daemon's wire shape. */
interface InputMessage {
    kind: string;
    pixelX?: number;
    pixelY?: number;
    pointerId?: number;
    pointerType?: string;
    scrollDeltaY?: number;
    keyCode?: string;
    text?: string;
}

/**
 * A pointer the live lane is tracking between its press and its release.
 *
 * `moved` is what turns a tap into a drag: the press is deferred until the first movement, so a
 * tap with no drag becomes a single `click` — which is the daemon's fast path, rendering between
 * press and release where a batched down+up can race `Modifier.clickable`.
 */
interface PointerState {
    x: number;
    y: number;
    moved: boolean;
    pointerType: string;
}

const root = document.querySelector<HTMLElement>(".cp-viewer")!;
const img = must<HTMLImageElement>("cp-img");
const stage = document.querySelector<HTMLElement>(".cp-stage")!;
const canvas = must<HTMLCanvasElement>("cp-canvas");
const status = must<HTMLElement>("cp-status");
const errorBox = may<HTMLElement>("cp-error");
const live = must<HTMLInputElement>("cp-live");
// Tall previews used to size the stage from their full width-constrained height, which could
// push the rest of the viewer several screens below the fold. Default to a viewport-bounded
// contain fit; "Fit width" deliberately restores the old unconstrained-height presentation.
// The snapshot remains the geometry source for Live/Wasm, so re-pin an active overlay after
// changing modes.
// ONE button, not a Fit screen / Fit width pair: this is a two-state axis with a default, which
// is what `aria-pressed` on a single toggle expresses — the label names the non-default state
// ("Fit width") and pressed-ness says whether it is on. A two-button group spends twice the bar
// width to say the same thing, and always shows one button that does nothing when clicked.
const zoomToggle = document.querySelector<HTMLButtonElement>(".cp-zoom-toggle");
// "Fit screen" means the WHOLE preview is on screen, so the cap is whatever the viewport has
// left BELOW the chrome above the stage — measured, not a fixed 72vh guess. The guess was wrong
// in both directions: on the viewer, where the title block and two control rows sit above the
// stage, 72vh reached past the fold and cut the render off; on a short window it left the image
// taller than the space it had. Floored at 320px so a very short window still shows a usable
// stage rather than a sliver, and re-measured on resize.
function fitCap() {
    if (!stage) return "72vh";
    var top = stage.getBoundingClientRect().top + (window.scrollY || 0);
    return rules.fitCap(top, window.innerHeight);
}
// The cap last written to the stage, so a re-measure that lands on the same answer can do
// nothing. That is what keeps the observer below off a feedback loop: applying a cap resizes
// the image, which resizes the container being observed, which re-measures — and stops there,
// because the second measurement matches the first.
var appliedFitCap: string | null = null;
function applyZoom(rawMode: string | null) {
    var mode = rules.zoomMode(rawMode);
    var maxHeight = mode === "fit" ? fitCap() : "";
    appliedFitCap = mode === "fit" ? maxHeight : null;
    img.style.maxHeight = maxHeight;
    var rcZoomCanvas = may<HTMLCanvasElement>("cp-rc-canvas");
    if (rcZoomCanvas) rcZoomCanvas.style.maxHeight = maxHeight;
    // The spec lane paints into its own <img>, so the zoom limit has to reach it too — a
    // phone-shaped imported reference would otherwise blow the stage past the 72vh Fit-screen
    // cap the render it is being compared against obeys. Looked up rather than closed over:
    // applyZoom("fit") runs at page load, before the lane's own declarations.
    var specZoomImg = may<HTMLImageElement>("cp-spec-img");
    if (specZoomImg) specZoomImg.style.maxHeight = maxHeight;
    // The motion lane paints into its own <img> too, so the cap has to reach it for the same
    // reason: a tall capture would otherwise ignore Fit screen and push the card past the fold —
    // and unlike a still, it would do so while animating. Looked up rather than closed over,
    // exactly as the spec image is: applyZoom("fit") runs before the lane's own declarations.
    var motionZoomImg = may<HTMLImageElement>("cp-motion-img");
    if (motionZoomImg) motionZoomImg.style.maxHeight = maxHeight;
    root.setAttribute("data-zoom", mode);
    if (zoomToggle)
        zoomToggle.setAttribute(
            "aria-pressed",
            mode === "width" ? "true" : "false",
        );
    window.requestAnimationFrame(function () {
        if (live && live.checked && !canvas.hidden) fitLiveCanvas();
        if (wasmActive()) positionWasmFrame();
    });
}
if (zoomToggle) {
    zoomToggle.addEventListener("click", function () {
        applyZoom(root.getAttribute("data-zoom") === "width" ? "fit" : "width");
    });
}
applyZoom("fit");
// Re-measure when the answer fitCap() gave could have changed. "Fit width" is an explicit choice
// to ignore the viewport's height, so it is left alone.
function refit() {
    var mode = rules.zoomMode(root.getAttribute("data-zoom"));
    if (!rules.needsRefit(mode, fitCap(), appliedFitCap)) return;
    applyZoom("fit");
}
window.addEventListener("resize", refit);
// A resize is not the only thing that invalidates the cap: fitCap() measures from the stage's
// TOP, so anything inserted above the stage moves it down and shortens the space it has. The
// render-history menu does exactly that — <cp-history-menu> fetches its manifest and inserts
// `.cp-history` between the toolbar and the stage well after this first ran — and a DOM
// insertion fires no `resize`, so a delivery-backed viewer kept a cap measured for a stage that
// had since moved, pushing a tall history strip's preview back below the fold.
//
// Observed rather than called back from the history builder: the cap is invalidated by the
// stage MOVING, whoever moved it, and an observer catches the next thing to grow above the
// stage without that code having to know this cap exists.
//
// The observed box is the BODY, not the stage or its parent. ResizeObserver reports size, not
// position, and the strip is inserted after `.cp-viewer-bar` — a sibling of `.cp-viewer`, so
// the stage's own container merely moves down and never changes size. Only an ancestor
// containing both the insertion point and the stage grows, and the body is the one element
// guaranteed to be that for any future insertion too.
if (typeof ResizeObserver === "function" && document.body) {
    new ResizeObserver(function () {
        // Coalesce to a frame: an insertion can fire the observer mid-layout, and measuring then
        // reads geometry the browser is still settling.
        window.requestAnimationFrame(refit);
    }).observe(document.body);
}
// Surface a mode-activation failure visibly, instead of leaving a stale frame that reads as a
// (wrong) render. Every lane routes its failure here — a dead Live stream, a Wasm app that
// never boots, a /render that errors — so "can't activate this mode" is never silent.
// Activation state for a lane that hasn't painted yet, surfaced on the stage's backend badge
// (see backendBadgeScript) instead of the controls footer — a "connecting…" nobody scrolls to
// isn't feedback. Pass null to clear.
function setPending(label: string | null) {
    if (label) root.setAttribute("data-pending", label);
    else root.removeAttribute("data-pending");
}
function showModeError(msg: string) {
    setPending(null);
    if (!errorBox) {
        status.textContent = msg;
        return;
    }
    errorBox.textContent = msg;
    errorBox.hidden = false;
    status.textContent = "";
}
function clearModeError() {
    if (errorBox) {
        errorBox.hidden = true;
        errorBox.textContent = "";
    }
}
// Human-readable reason for a Live stream that closed before delivering a frame. Maps the
// server's close codes (1013 capacity, 1008 unauthorized, 1003/CANNOT_ACCEPT carries a reason);
// a bare abnormal close (1006, e.g. a proxy 502 on the WS upgrade) gets the generic message.
function liveCloseReason(ev: CloseEvent | null) {
    if (ev && ev.code === 1013)
        return "Live preview is at capacity — try again shortly.";
    if (ev && ev.code === 1008) return "Live preview unauthorized.";
    if (ev && ev.reason) return "Live preview unavailable: " + ev.reason;
    return "Live preview couldn't connect — the live stream may be unavailable on this server.";
}
// Whether the snapshot lane is static (baked PNGs, no /render re-render) — the explicit signal
// for the wasm auto-enable below. NOT `live.disabled`: a trusted-catalog live session serves
// static snapshots yet leaves the Live toggle enabled, so `live.disabled` no longer implies
// "static".
var staticSnapshot = root.getAttribute("data-static-snapshot") === "true";
// Whether an override-bearing /render returns fresh pixels even on a static snapshot lane (a
// trusted-catalog live session: its carried daemon re-renders author-declared knob edits on
// demand). When true, a knob edit re-points the snapshot /render URL rather than sitting dead.
var canRenderOverrides =
    root.getAttribute("data-can-render-overrides") === "true";
// The delivery-branch commit this page is pinned to, when it is a historical permalink
// (`?at=<sha>`). Every render URL built here carries it, so the stage, the export links and Copy
// PNG all read the same publish — a page where only some of those were pinned would be worse
// than one that wasn't pinned at all. Validated rather than trusted: it is DOM text that ends up
// in a request URL, and only a sha shape can reach one.
var pinnedAt = (root.getAttribute("data-pinned-at") || "").toLowerCase();
if (!/^[0-9a-f]{7,40}$/.test(pinnedAt)) pinnedAt = "";
var previewId = root.getAttribute("data-preview-id") || "";
// The session path prefix ("/<system>") when this viewer is served under a path — it sits at
// "<base>/p/<id>", so stripping the trailing "/p/<id>" recovers the base ("" for the root
// mount / legacy ?session= form). /render + /ws requests are prefixed with it so they hit the
// same session without needing ?session= threaded through.
var base = location.pathname.replace(/\/p\/[^/]*\/?$/, "");
var token = new URLSearchParams(location.search).get("token") || "";
// Carry the tenant through follow-up requests so a non-default ?session= stays on its module.
var session = new URLSearchParams(location.search).get("session") || "";
// Hydrating the controls from the page URL's params — the knobs this used to do inline, plus
// every display axis — now happens in one place (hydrateFromUrl, at the bottom of this file),
// because Back/Forward needs to run exactly the same restore. It still lands before the first
// render, so a deep link (or a copied "Direct links — overrides applied" URL) opens with those
// values already set and carries them through whichever transport is live.
// The selects + text input are opt-in (empty value = "use the preview's default"). The font
// scale slider has no empty state, so it's gated separately: we only send fontScale once the
// user moves it (fontScaleTouched), otherwise the slider's standing 1.0 would override a
// preview's declared default font scale and the first render wouldn't match the thumbnail.
// No "background" here: the viewer no longer offers a Background override — the viewer bar's
// Transparent toggle is the single background affordance, and the panel select that used to sit
// beside it read as its duplicate. `/render?background=clear` still strips a preview's authored
// background for the authoring lanes (CLI, exports, the VS Code extension); the viewer simply
// does not drive it.
var fields = ["device", "localeTag", "orientation"];
const fs = may<HTMLInputElement>("cp-fontScale");
const fsVal = may<HTMLElement>("cp-fontScale-val");
var fontScaleTouched = false;
var ws: WebSocket | null = null;
const themeChoice = may<HTMLSelectElement>("cp-theme");
// The two lanes that put a FIXED frame on the stage: the spec lane's imported raster, and a
// finished motion recording. Neither is re-pointed by an override, so `syncServerControls`
// disables every control that would re-render — but the frame underneath was still produced with
// whatever was picked before the lane opened, so the choices themselves are NOT moot. Read off
// `data-mode` rather than the mode radios so it is safe to call during module init, before the
// lane's own elements are declared.
//
// One predicate, two consumers: what `syncServerControls` disables and what `activeThemeChoice`
// still lets ride the URL are the same set of lanes, and they must not be able to drift.
function onFixedFrameLane(): boolean {
    var mode = root.getAttribute("data-mode") || "";
    return mode === "spec" || mode === "motion";
}
function activeThemeChoice() {
    return rules.activeThemeChoice(
        themeChoice && {
            value: themeChoice.value,
            disabled: themeChoice.disabled,
            active: themeChoice.getAttribute("data-theme-active") === "1",
        },
        onFixedFrameLane(),
    );
}
function chosenUiMode() {
    return rules.chosenUiMode(activeThemeChoice());
}
function chosenThemeProvider() {
    return rules.chosenThemeProvider(activeThemeChoice());
}
// The Theme bar: the visible face of #cp-theme, which is in the DOM but visually removed. The
// chips carry the select's own option values, so driving one from the other is a straight
// assignment plus the `change` every existing lane already listens for — no second code path
// for themed rendering, and none of the enabled-state logic below is duplicated: syncThemeBar
// simply mirrors what syncServerControls has just decided about the select and its options.
const themeBarBtns = document.querySelectorAll<HTMLButtonElement>(
    ".cp-theme-bar .cp-theme-btn",
);
function themeOptionFor(value: string | null): HTMLOptionElement | null {
    if (!themeChoice) return null;
    for (const o of Array.from(themeChoice.options)) {
        if (o.value === value) return o;
    }
    return null;
}
function syncThemeBar() {
    // Captured, not read through the `var` in the callback: this is where the narrowing the guard
    // above establishes has to survive into a nested function.
    const select = themeChoice;
    if (!select) return;
    themeBarBtns.forEach(function (b) {
        var choice = b.getAttribute("data-theme-choice") || "";
        var option = themeOptionFor(choice);
        var state = rules.themeBarButton(
            choice,
            { value: select.value, disabled: select.disabled },
            option && { disabled: option.disabled },
        );
        b.disabled = state.disabled;
        b.setAttribute("aria-pressed", state.pressed ? "true" : "false");
    });
}
themeBarBtns.forEach(function (b) {
    b.addEventListener("click", function () {
        var value = b.getAttribute("data-theme-choice");
        if (!themeChoice || b.disabled || value === null) return;
        if (themeChoice.value === value) return;
        themeChoice.value = value;
        themeChoice.dispatchEvent(new Event("change", { bubbles: true }));
        syncThemeBar();
    });
});
// The snapshot lane serves either the raster PNG or the vector SVG through the same <img>.
// The render-mode radio flips this (".png" default, ".svg" in SVG mode); refreshSnapshot and
// the copyable links read it so a re-render / copied URL matches the on-screen format.
var snapshotExt = ".png";
// Keep the current frame visible while an override-triggered render is in flight. A generation
// token prevents an older, slower request from clearing the busy treatment (or replacing the
// pixels) after a newer control edit has already started another render.
var snapshotGen = 0;
function setSnapshotLoading(loading: boolean) {
    if (loading) {
        root.setAttribute("data-reloading", "true");
        if (stage) stage.setAttribute("aria-busy", "true");
    } else {
        root.removeAttribute("data-reloading");
        if (stage) stage.removeAttribute("aria-busy");
    }
}
function cancelSnapshotLoading() {
    snapshotGen++;
    status.textContent = "";
    setSnapshotLoading(false);
}

// Size overrides (the Fixed / Max / Min / Within modes). Which query params carry the numbers
// is chosen by the mode: Fixed pins the frame via widthPx/heightPx; Max / Min / Within are
// wrapped-axis bounds (maxWidthPx / minWidthPx …). Blank inputs are omitted, so one axis can be
// bounded without the other. Server-side only (a daemon re-measures) — the inputs are
// disabled on a static snapshot like Device/Orientation, so it never emits them.
//
// The inputs are authored in dp (the Compose unit); the wire stays in px like every other
// override, so a dp value is multiplied by the backend's render density before it's sent (and
// the copyable /render URL stays px-consistent). data-render-density carries the factor.
var renderDensity =
    parseFloat(root.getAttribute("data-render-density") || "") || 2;
// dp (string from the input) → a positive integer px value, or null when blank/non-positive.
function sizePx(id: string) {
    var el = may<HTMLInputElement>(id);
    if (!el || !el.value) return null;
    return rules.sizePx(el.value, renderDensity);
}
function sizeOverrides() {
    var mode = may<HTMLSelectElement>("cp-sizeMode");
    return rules.sizeOverrides(
        (mode ? mode.value : "") as rules.SizeMode,
        function (field) {
            return sizePx("cp-" + field);
        },
    );
}
function overrides(): Overrides {
    var o: Overrides = {};
    fields.forEach(function (f) {
        var el = may<Control>("cp-" + f);
        if (el && !el.disabled && el.value) o[f] = el.value;
    });
    var uiMode = chosenUiMode();
    if (uiMode) o.uiMode = uiMode;
    if (fontScaleTouched && fs) o.fontScale = fs.value;
    var size = sizeOverrides();
    Object.keys(size).forEach(function (k) {
        o[k] = size[k];
    });
    // Overlay toggles (touchOverlay). Their id is "cp-<key>", so the daemon key is the
    // id minus the prefix. Collected HERE, in the map query() serializes, rather than only in
    // liveOverrides(): the daemon renders these on the ordinary render path, so they belong on the
    // page URL, the export links, and the live socket's connect query — which is what makes a
    // ticked box arrive with `stream/start` instead of a second setOverrides that restarts the
    // stream a frame later. Only a CHECKED overlay is sent: every consumer re-parses this whole
    // map, so an absent key already means "off", and omitting the false ones keeps
    // `&touchOverlay=false` out of every link.
    ticks(".cp-overlay").forEach(function (el) {
        if (el.disabled || !el.checked) return;
        o[el.id.replace(/^cp-/, "")] = "true";
    });
    return o;
}
// A knob control's declared kind (`string` / `int` / `float` / `bool` / `color`), from the row
// the server rendered. Only the empty-value rules in the collectors below consult it — everything
// else sends the control's text verbatim and lets the server type it from the same declaration.
// Defaults to `string`, which is what an undeclared knob parses as server-side.
function knobKind(el: Control) {
    return el.getAttribute("data-knob-kind") || "string";
}
// The live-stream override map: the display fields PLUS the author-declared knob values as
// `knob.<key>=<value>` entries (the daemon's setOverrides parses the same map /render does,
// typing each from the preview's declaration). Kept separate from overrides() so query() and
// the Wasm patch — which append/ignore knobs their own way — are unaffected; without this a
// knob edit during an active Live (stream) would send only the display fields and the daemon
// would reset the others to their defaults. Unlike query(), every knob is sent (not just
// changed ones) for exactly that reason, so defaults are not filtered here.
function liveOverrides() {
    var o = overrides();
    controls(".cp-knob").forEach(function (el) {
        if (el.disabled) return;
        var key = el.getAttribute("data-knob-key");
        if (!key) return;
        var val = controlValue(el);
        // An empty STRING knob is a real value (a cleared label, or a variant seeded to ""), so it
        // is sent; the server keeps it for a string knob and skips it for a kind that can't parse
        // it. An emptied number field has nothing to send, and this map REPLACES the daemon's whole
        // override bag, so sending `knob.count=` would be indistinguishable from clearing it.
        if (val === "" && knobKind(el) !== "string") return;
        o["knob." + key] = val;
    });
    // Remote Compose knobs carry their own `<kind>:` tag: `rc.<name>=<kind>:<value>`. Sent for
    // every RC knob (like the plain knobs) so a Live setOverrides doesn't reset the others.
    controls(".cp-rc-knob").forEach(function (el) {
        if (el.disabled) return;
        var name = el.getAttribute("data-rc-name");
        if (!name) return;
        var kind = el.getAttribute("data-rc-kind") || "string";
        var val = controlValue(el);
        if (val === "") return;
        o["rc." + name] = kind + ":" + val;
    });
    // (The overlay toggles are collected by overrides() above, not here — they ride the URL and
    // the connect query like the display fields do.)
    // App-declared theme (themeProvider = provider FQN). Only when a theme is picked and the
    // control is live; "(default)" (empty) leaves the daemon on the preview's own wrapper.
    var tp = chosenThemeProvider();
    if (tp) o["themeProvider"] = tp;
    // Detected-feature: keyboard focus. Checked ⇒ focus the first focusable + draw the overlay
    // (focus=0). Daemon-only, so skipped when disabled.
    var fc = may<HTMLInputElement>("cp-focus");
    if (fc && !fc.disabled && fc.checked) o["focus"] = "0";
    // Detected-feature: one-handed gesture hints. Checked ⇒ draw the gesture-hint overlay
    // (gestures=true). Android-daemon-only, so skipped when disabled.
    var gc = may<HTMLInputElement>("cp-gestures");
    if (gc && !gc.disabled && gc.checked) o["gestures"] = "true";
    // setOverrides REPLACES the stream's entire override map. Keep an explicit server-side player
    // in that replacement, especially when CMP Android/JVM is the page default; otherwise the
    // connect URL selects it and the first onopen replay immediately clears it back to Java.
    var livePlayer = rules.serverPlayerParam(rcPlayerBackend, !!rcPlayerPicked);
    if (livePlayer) o.rcPlayer = livePlayer;
    return o;
}
// Renderer-picker state (the #cp-lane-select combo). `rcPlayerBackend` is the current Remote
// Compose player and `rcPlayerPicked` gates whether it rides the render URL. It is also true for an
// embedded default that differs from the server's absent-param Java fallback.
const laneSelect = may<HTMLSelectElement>("cp-lane-select");
// The design-spec lane's own chip, beside the combo rather than inside it (see ServeWeb's
// specChipHtml). Present only when this preview carries an imported reference.
const specChip = may<HTMLButtonElement>("cp-spec-chip");
var rcDefaultBackend = laneSelect
    ? laneSelect.getAttribute("data-rc-default") || ""
    : "";
var rcPlayerBackend = rcDefaultBackend;
// An absent `rcPlayer` means Java to the server. If the page presents another server-side backend
// as its default, that backend must ride the very first snapshot request just like a user pick.
var rcPlayerPicked = rules.backendRequiresRenderParam(rcDefaultBackend);
// Reconcile the picker (the combo's value AND the chip's label) with the active lane. Hoisted
// (the real impl is assigned in the picker block below) so the common mode-transition path
// (enterMode) can call it whenever the viewer leaves a lane through ANY control — not only a
// pick — so the combo can't keep naming a renderer that Live / SVG has taken off the stage. A
// no-op stub for a single-lane preview (no combo present).
var syncLaneSelect = function () {};
function query() {
    var o = overrides();
    // Public routes are open, so a page that arrived without a token stays token-free — only
    // carry token= when this page's own URL had one (a token-gated box).
    var parts: string[] = [];
    if (token) parts.push("token=" + encodeURIComponent(token));
    if (session) parts.push("session=" + encodeURIComponent(session));
    // The pin rides with the request rather than being applied per route server-side, because the
    // server cannot tell the viewer's own snapshot request from any other /render call. A pinned
    // page has every re-rendering control disabled, so the overrides below are empty in practice —
    // the pin is what this URL is for.
    if (pinnedAt) parts.push("at=" + encodeURIComponent(pinnedAt));
    Object.keys(o).forEach(function (k) {
        parts.push(k + "=" + encodeURIComponent(o[k]));
    });
    // Author-declared knobs: knob.<key>=<value>. The server infers the type from the preview's
    // declaration, so no <kind>: prefix. A knob still at its declared default is omitted — that
    // keeps the URL on the instant baked snapshot (any knob.* param routes a published catalog
    // to the daemon for a fresh re-render); only an actually-changed knob is sent.
    controls(".cp-knob").forEach(function (el) {
        if (el.disabled) return;
        var key = el.getAttribute("data-knob-key");
        if (!key) return;
        var val = controlValue(el);
        if (
            !rules.knobEmitted(
                val,
                el.getAttribute("data-knob-initial") || "",
                knobKind(el),
            )
        )
            return;
        parts.push(
            "knob." + encodeURIComponent(key) + "=" + encodeURIComponent(val),
        );
    });
    // Remote Compose knobs: rc.<name>=<kind>:<value>. The <kind>: prefix types the seed
    // (color:%23AARRGGBB, int:…, bool:true, …). A knob still at its declared default is omitted
    // so the URL stays on the instant baked snapshot until it's actually changed.
    controls(".cp-rc-knob").forEach(function (el) {
        if (el.disabled) return;
        var name = el.getAttribute("data-rc-name");
        if (!name) return;
        var kind = el.getAttribute("data-rc-kind") || "string";
        var val = controlValue(el);
        if (!rules.rcKnobEmitted(val, el.getAttribute("data-rc-initial") || ""))
            return;
        parts.push(
            "rc." +
                encodeURIComponent(name) +
                "=" +
                encodeURIComponent(rules.rcKnobValue(kind, val)),
        );
    });
    // App-declared theme (themeProvider = provider FQN). Routes to the daemon like a knob; a
    // published catalog re-renders on demand. Omitted at "(default)" so the URL stays on the
    // instant baked snapshot until a theme is actually chosen.
    var tp = chosenThemeProvider();
    if (tp) parts.push("themeProvider=" + encodeURIComponent(tp));
    // Detected-feature: keyboard focus (focus=0). Routes to the daemon like a knob; omitted when
    // unchecked so the URL stays on the baked snapshot.
    var fc = may<HTMLInputElement>("cp-focus");
    if (fc && !fc.disabled && fc.checked) parts.push("focus=0");
    // Detected-feature: one-handed gesture hints (gestures=true). Routes to the daemon like a
    // knob; omitted when unchecked so the URL stays on the baked snapshot.
    var gc = may<HTMLInputElement>("cp-gestures");
    if (gc && !gc.disabled && gc.checked) parts.push("gestures=true");
    // Remote Compose render backend: a server-side player selection rides the render as
    // rcPlayer=<wire>. Emitted for a visitor pick or a non-Java server-side default, and only
    // for a server-side lane — java / cmp-android render through the daemon, cmp-jvm through its
    // isolated desktop subprocess (all three PNG lanes). The js canvas replays the doc in-browser
    // (no server render), so it never sends the param, and an unpicked default stays on the
    // instant baked snapshot.
    var serverPlayer = rules.serverPlayerParam(
        rcPlayerBackend,
        !!rcPlayerPicked,
    );
    if (serverPlayer)
        parts.push("rcPlayer=" + encodeURIComponent(serverPlayer));
    return parts.join("&");
}
// "Full page (scroll)" appends `scroll=long` to both snapshot formats. The server routes SVG to
// compose/figma-svg-long and PNG to render/scroll/long.
const scrollLong = may<HTMLInputElement>("cp-scroll-long");
// The exploded 3D view (`?exploded=1` on the SVG lane): the layered figma-svg tilted back and
// pulled apart into one sheet per visible drawing level. It is a *presentation* of the
// vector export, so it rides only the `.svg` extension — appending it to the raster PNG lane
// would silently do nothing, and the toggle turns SVG on rather than offering the combination.
//
// Every knob lands in the URL, which is the whole reason the projection is server-side: the
// angle someone tuned is part of the link they copy, the SVG they download, and the picture a
// reviewer sees in a PR — not client state that dies with the tab.
const explodeToggle = may<HTMLButtonElement>("cp-explode-toggle");
var EXPLODE_KNOBS = [
    ["cp-explode-tilt", "explodeTilt"],
    ["cp-explode-spin", "explodeSpin"],
    ["cp-explode-gap", "explodeGap"],
    ["cp-explode-depth", "explodeDepth"],
];
function explodeOn() {
    return !!(
        explodeToggle && explodeToggle.getAttribute("aria-pressed") === "true"
    );
}
// The same boolean forms `ServeExplodedSvg.enabled` accepts, so a hand-typed or bookmarked
// `?exploded=on` opens the view the render endpoint would serve for that URL — rather than
// showing the flat PNG and then dropping the parameter on the next sync, which is what a
// stricter reading here produced.
function explodeParamOn(raw: string | null) {
    return rules.explodeParamOn(raw);
}
// A server-side Remote Compose player pick cannot survive the exploded view, so entering it
// releases the pick rather than hiding it. `rcPlayer=cmp-jvm` is a renderer choice, not a mode,
// so it outlives a return to the static lane — and the server routes it to the desktop player's
// own subprocess *before* it ever looks at `exploded=`, leaving the chip pressed over an
// untouched flat SVG. There is nothing to explode there either way: that lane renders a Remote
// Compose document, which carries none of the `<g id="…">` composable nesting this view splits
// on.
//
// Resetting the STATE rather than filtering `rcPlayer` out of the request string is the whole
// point: the string is copied into the page URL by syncUrl() and read back by the lane picker's
// label, so a filtered request left the address bar and the "current renderer" chip both naming
// a player that is no longer drawing anything.
function dropRcPlayerPick() {
    if (!rcPlayerPicked && rcPlayerBackend === rcDefaultBackend) return;
    rcPlayerPicked = rules.backendRequiresRenderParam(rcDefaultBackend);
    rcPlayerBackend = rcDefaultBackend;
    if (typeof syncLaneSelect === "function") syncLaneSelect();
}
// Whether pressing 3D is what turned the vector lane on. Leaving 3D then hands the lane back
// rather than stranding the visitor on a flat SVG they never asked for — but only in that case:
// someone who was already reading the SVG and exploded it should get their SVG back, not a PNG.
var explodeEnabledSvg = false;
// The knobs as plain values, for the rules next door to decide on.
function explodeKnobValues() {
    return EXPLODE_KNOBS.map(function (pair) {
        var el = may<HTMLInputElement>(pair[0]);
        return {
            param: pair[1],
            value: el ? el.value : "",
            defaultValue: el ? el.getAttribute("data-cp-default") || "" : "",
        };
    });
}
// Just the exploded parameters, for `syncUrl` — the page's own address is written from the same
// helper the render URL uses, so the address bar, the copied link and the fetched bytes can never
// disagree about the angle on screen.
function explodeQuery() {
    return rules.explodeParams(explodeKnobValues()).join("&");
}
function withSnapshotFormat(ext: string, qs: string) {
    return rules.withSnapshotFormat(ext, qs, {
        scrollLong: !!(scrollLong && scrollLong.checked),
        exploded: explodeOn(),
        knobs: explodeKnobValues(),
    });
}
// Called once the snapshot request has SETTLED, whichever way it went — pixels decoded, or a
// failure that leaves the stage without them. The bookmarked-mode bootstrap waits on this: it
// used to wait on the <img>'s own load/error, which a failed render never fires (no src is ever
// assigned), so a deep link into an interactive lane sat on the snapshot for the full 8s timeout.
// A refusal is a settled snapshot too, and one the Wasm/live lane may well be able to honour.
var onSnapshotSettled: (() => void) | null = null;
function snapshotSettled() {
    var fn = onSnapshotSettled;
    if (fn) {
        onSnapshotSettled = null;
        fn();
    }
}
function refreshSnapshot() {
    status.textContent = "rendering…";
    var gen = ++snapshotGen;
    setSnapshotLoading(true);
    var qs = withSnapshotFormat(snapshotExt, query());
    var url =
        base +
        "/render/" +
        encodeURIComponent(previewId) +
        snapshotExt +
        (qs ? "?" + qs : "");
    var requestedExt = snapshotExt;
    // Override-bearing renders are deliberately `no-store`. Preloading with `new Image()` and
    // then assigning the same URL to the visible image therefore performs two server renders,
    // and the second one can race the first through the daemon's shared override state. Fetch the
    // bytes once and hand the resulting blob URL to the image instead. This also keeps the current
    // frame visible until the replacement has decoded.
    fetch(url, { credentials: "same-origin" })
        .then(function (response) {
            if (!response.ok) {
                // The server refused to answer an override it could not apply, rather than handing back
                // the un-overridden snapshot under a 200 (#3449). Name the params it dropped: "render
                // failed" would read as a broken preview, when in fact the preview is fine and the live
                // lane isn't.
                var dropped = response.headers.get(
                    "X-Compose-Preview-Dropped-Overrides",
                );
                if (dropped) {
                    var e: DroppedOverridesError = new Error(
                        "dropped overrides",
                    );
                    e.cpDropped = dropped;
                    // 409 is terminal: this preview has no live lane, so retrying never helps.
                    e.cpTerminal = response.status === 409;
                    throw e;
                }
                throw new Error("render " + response.status);
            }
            return response.blob();
        })
        .then(function (blob) {
            if (gen !== snapshotGen) return;
            var objectUrl = URL.createObjectURL(blob);
            var next = new Image();
            next.onload = function () {
                if (gen !== snapshotGen) {
                    URL.revokeObjectURL(objectUrl);
                    return;
                }
                var previous = img.getAttribute("data-cp-blob");
                img.src = objectUrl;
                img.setAttribute("data-cp-blob", objectUrl);
                // The blob URL is opaque — it says nothing about which render produced the pixels on
                // screen. Record the /render URL we actually fetched so the visible frame's provenance
                // stays inspectable: which format, which knobs, which lane. `#cp-url-png` / `#cp-url-svg`
                // track the *current controls*, which is not the same thing — they update the instant a
                // knob moves, while this only lands once the matching bytes have decoded. That gap is
                // exactly what the serve-lanes e2e asserts on.
                img.setAttribute("data-cp-src", url);
                if (previous) URL.revokeObjectURL(previous);
                status.textContent = "";
                setSnapshotLoading(false);
                clearModeError();
                syncSpecBaseline();
                snapshotSettled();
            };
            next.onerror = function () {
                URL.revokeObjectURL(objectUrl);
                if (gen !== snapshotGen) return;
                setSnapshotLoading(false);
                showModeError(
                    (requestedExt === ".svg" ? "SVG" : "PNG") +
                        " render failed for this preview.",
                );
                snapshotSettled();
            };
            next.src = objectUrl;
        })
        .catch(function (e: DroppedOverridesError) {
            if (gen !== snapshotGen) return;
            setSnapshotLoading(false);
            if (e && e.cpDropped) {
                showModeError(
                    "Not rendered with " +
                        e.cpDropped.split(",").join(", ") +
                        " — " +
                        (e.cpTerminal
                            ? "this preview can only be served as its published snapshot."
                            : "the live render is unavailable right now; retry shortly."),
                );
                snapshotSettled();
                return;
            }
            showModeError(
                (requestedExt === ".svg" ? "SVG" : "PNG") +
                    " render failed for this preview.",
            );
            snapshotSettled();
        });
    refreshLinks();
}
// The copyable direct-link panel: rebuild the absolute /render URLs (PNG + optional SVG) from
// the current controls so a copied/downloaded link reproduces exactly what's on screen. Built
// on location.origin so the link is absolute (curl-able / shareable), and kept in sync on
// every control or knob change — even the ones that don't re-render the snapshot themselves.
function renderUrl(ext: string) {
    var qs = withSnapshotFormat(ext, query());
    return (
        location.origin +
        base +
        "/render/" +
        encodeURIComponent(previewId) +
        ext +
        (qs ? "?" + qs : "")
    );
}
// Whether the stage is showing the render the PUBLISHED design-spec score was measured against.
//
// The catalog bakes that score against its own snapshot — default theme, declared knob defaults,
// no detected features. Every control here omits itself from `query()` while it sits at that
// default, precisely so the URL stays on the baked snapshot, which makes the render URL a direct
// reading of whether anything has moved: strip the link-only params and whatever is left is a
// deviation from what was scored.
//
// It matters because the design reference does NOT move with the render. A spec is imported once,
// not re-exported per theme, so choosing a theme changes one side of the comparison and not the
// other. The baked number then describes a frame that is no longer on the stage — and the spec
// lane, scoring what IS on the stage, disagrees with the chip by ten points or more.
function specAtBaseline() {
    return rules.specAtPublishedBaseline(
        root.getAttribute("data-mode") || "snapshot",
        renderUrl(".png"),
        img.getAttribute("data-cp-src"),
    );
}
// The inline theme bootstrap publishes the initial value before serve-components.js upgrades
// the spec element. Keep both the stage attribute and the installed element current from here on:
// the attribute serves reconnects, while the push updates the existing install immediately.
function syncSpecBaseline() {
    var at = specAtBaseline();
    root.setAttribute("data-spec-baseline", at ? "1" : "0");
    if (window.cpSpecCompare) window.cpSpecCompare.baseline(at);
}
function withMode(url: string, mode: string) {
    return url + (url.indexOf("?") >= 0 ? "&" : "?") + "mode=" + mode;
}
// [skipUrlSync] refreshes the copyable links WITHOUT touching history. Only one caller wants it:
// a path that is about to hand the URL to a lane transition, where syncing first would replace
// the entry the visitor came from moments before that transition pushes a new one — spending the
// previous state to write an intermediate nobody asked for. See `onKnobEdited`.
function refreshLinks(skipUrlSync?: boolean) {
    // The page's own URL is kept in step with the controls for the same reason the direct links
    // are: what's on screen should be something you can bookmark or hand to someone. Every path
    // that changes viewer state already refreshes the links, so this one call covers all of them.
    if (!skipUrlSync) syncUrl();
    [
        ["png", ".png"],
        ["svg", ".svg"],
    ].forEach(function (pair) {
        var field = may<HTMLInputElement>("cp-url-" + pair[0]);
        if (!field) return;
        var embed = renderUrl(pair[1]);
        var dl = may<HTMLAnchorElement>("cp-dl-" + pair[0]);
        if (pair[1] === ".svg") {
            // Copy URL yields the web/document variant (`?mode=web` → external Google Fonts
            // @import), so opening the copied link in a browser pulls the faces from Google. Copy
            // SVG and the download stay on the embedded variant (self-contained — right for pasting
            // into Figma or an <img>, where external refs don't load); the button reads it from
            // data-embed-url.
            field.value = withMode(embed, "web");
            field.setAttribute("data-embed-url", embed);
            if (dl) dl.href = embed;
        } else {
            field.value = embed;
            if (dl) dl.href = embed;
        }
    });
    updateSvgMatch();
    syncSpecBaseline();
    refreshReportLink();
}
// Keep the "report an issue" report pointed at what is on screen. The server filled the form's
// hidden `body` for the settings the page was served at (so this works with JS off); the
// template it carries has the render URL as a `{{render}}` placeholder, which we swap for the
// live /render URL so a report filed after fiddling with the knobs shows the render that
// prompted it. The token is stripped for the same reason the server strips it: an issue body is
// public, a session token is a capability.
//
// Note this writes an INPUT VALUE, never an href: the affordance is a GET form whose action is a
// server-rendered literal, so no page-derived string ever reaches a navigation sink. The browser
// does the query encoding on submit, which is why the substituted URL goes in raw here.
function refreshReportLink() {
    var body = may<HTMLInputElement>("cp-report-body");
    if (!body) return;
    var tpl = body.getAttribute("data-report-template");
    var field = may<HTMLInputElement>("cp-url-png");
    if (!tpl || !field || !field.value) return;
    body.value = tpl.replace("{{render}}", stripToken(field.value));
}
function stripToken(url: string) {
    var cut = url.indexOf("?");
    if (cut < 0) return url;
    var kept = url
        .slice(cut + 1)
        .split("&")
        .filter(function (p: string) {
            return p && p.slice(0, 6) !== "token=";
        });
    return kept.length
        ? url.slice(0, cut) + "?" + kept.join("&")
        : url.slice(0, cut);
}
const svgMatch = may<HTMLElement>("cp-svg-match");
const svgDiff = may<HTMLAnchorElement>("cp-svg-diff");
var svgMatchGeneration = 0;
var svgMatchKey = "";
function updateSvgMatch() {
    // Captured so the guard survives into the callbacks below, which run long after it.
    const match = svgMatch;
    if (!match) return;
    if (!svgOn()) {
        match.hidden = true;
        if (svgDiff) svgDiff.hidden = true;
        return;
    }
    var png = may<HTMLInputElement>("cp-url-png");
    var svg = may<HTMLInputElement>("cp-url-svg");
    var svgUrl = svg && (svg.getAttribute("data-embed-url") || svg.value);
    if (!png || !png.value || !svgUrl || !window.ComposePreviewCompare) return;
    var key = png.value + "\n" + svgUrl;
    if (
        key === svgMatchKey &&
        match.textContent &&
        match.textContent !== "comparing…"
    ) {
        match.hidden = false;
        if (svgDiff) svgDiff.hidden = false;
        return;
    }
    svgMatchKey = key;
    var generation = ++svgMatchGeneration;
    match.hidden = false;
    match.className = "cp-match";
    match.textContent = "comparing…";
    if (svgDiff) svgDiff.hidden = true;
    window.ComposePreviewCompare.scoreSvgUrls(png.value, svgUrl).then(
        function (percent) {
            if (generation !== svgMatchGeneration || !svgOn()) return;
            match.textContent = percent.toFixed(1) + "% match";
            match.className =
                "cp-match cp-match--" +
                (percent >= 90 ? "good" : percent >= 75 ? "warn" : "bad");
            if (svgDiff) svgDiff.hidden = false;
        },
        function () {
            if (generation !== svgMatchGeneration || !svgOn()) return;
            match.textContent = "match unavailable";
            match.className = "cp-match cp-match--na";
        },
    );
}
// Copy the /render URL of the current view. The URL itself lives in the `#cp-url-<ext>` field
// (kept off-screen — nobody reads a 200-character absolute URL), so this is what puts it on the
// clipboard; the field is still selected as the execCommand fallback's requirement, and the
// button reports back in its own label rather than flashing a control the visitor can't see.
document.querySelectorAll<HTMLElement>(".cp-copyurl").forEach(function (btn) {
    btn.addEventListener("click", function () {
        var field = may<HTMLInputElement>(
            btn.getAttribute("data-copyurl-target") || "",
        );
        if (!field || !field.value) return;
        var was =
            btn.getAttribute("data-copyurl-label") || btn.textContent || "";
        btn.setAttribute("data-copyurl-label", was);
        var report = function (label: string) {
            btn.textContent = label;
            setTimeout(function () {
                btn.textContent = was;
            }, 1400);
        };
        field.select();
        if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(field.value).then(
                function () {
                    report("Copied");
                },
                function () {
                    report("Copy failed");
                },
            );
        } else {
            try {
                document.execCommand("copy");
                report("Copied");
            } catch (e) {
                report("Copy failed");
            }
        }
    });
});
// "Copy PNG" / "Copy SVG": fetch the current /render artefact and put it on the clipboard —
// PNG as real image/png bytes (falling back to a base64 data: URI), SVG as markup verbatim — so
// it can be pasted straight into an issue, editor, or prompt without downloading a file. Uses
// the same live cp-url-<ext> field the URL Copy button reads, so the copied artefact matches the
// on-screen overrides.
document.querySelectorAll<HTMLElement>(".cp-copyimg").forEach(function (btn) {
    btn.addEventListener("click", function () {
        var field = may<HTMLInputElement>(
            btn.getAttribute("data-copyimg-target") || "",
        );
        if (!field || !field.value) return;
        var ext = btn.getAttribute("data-copyimg-ext");
        var was =
            btn.getAttribute("data-copyimg-label") || btn.textContent || "";
        btn.setAttribute("data-copyimg-label", was);
        var reset = function (label: string) {
            btn.textContent = label;
            setTimeout(function () {
                btn.textContent = was;
            }, 1400);
        };
        if (!navigator.clipboard) {
            reset("No clipboard");
            return;
        }
        btn.textContent = "Copying…";
        // Copy SVG targets the EMBEDDED variant (data-embed-url) — the field itself holds the
        // web-mode URL for Copy URL, but a copied SVG is usually pasted into Figma / an editor,
        // which needs the fonts baked in, not an external @import. PNG has one variant.
        var src =
            (ext === ".svg" && field.getAttribute("data-embed-url")) ||
            field.value;
        // fetch() resolves even on a non-2xx render (503 saturated, 400 bad override, 404 a
        // preview that can't export that lane), so guard on r.ok — otherwise the error body,
        // not the artefact, would land on the clipboard and still report "Copied".
        var okOrThrow = function (r: Response) {
            if (!r.ok) throw new Error("render " + r.status);
            return r;
        };
        // PNG: hand the clipboard the real image/png bytes when the browser has ClipboardItem, so
        // pasting into a GitHub issue (or a doc, or a chat) lands the picture — which is what makes
        // "Copy PNG → paste into the bug report" a one-keystroke screenshot. The blob goes in as a
        // *promise* because Safari requires the ClipboardItem to be constructed synchronously inside
        // the click; awaiting the fetch first would lose the user gesture. Anything that can't do it
        // — no ClipboardItem, a denied permission, a non-image response — falls through to the
        // original base64 data: URI text, which still pastes into an editor or a prompt.
        var copyAsText = function () {
            if (!navigator.clipboard.writeText) {
                reset("No clipboard");
                return;
            }
            var toText =
                ext === ".svg"
                    ? fetch(src)
                          .then(okOrThrow)
                          .then(function (r) {
                              return r.text();
                          })
                    : fetch(src)
                          .then(okOrThrow)
                          .then(function (r) {
                              return r.blob();
                          })
                          .then(function (blob) {
                              return new Promise<string>(function (
                                  resolve,
                                  reject,
                              ) {
                                  var fr = new FileReader();
                                  fr.onload = function () {
                                      resolve(fr.result as string);
                                  };
                                  fr.onerror = function () {
                                      reject(fr.error);
                                  };
                                  fr.readAsDataURL(blob);
                              });
                          });
            toText
                .then(function (text) {
                    return navigator.clipboard.writeText(text);
                })
                .then(
                    function () {
                        reset("Copied");
                    },
                    function () {
                        reset("Failed");
                    },
                );
        };
        if (
            ext === ".png" &&
            window.ClipboardItem &&
            navigator.clipboard.write
        ) {
            var pngBlob = fetch(src)
                .then(okOrThrow)
                .then(function (r) {
                    return r.blob();
                });
            navigator.clipboard
                .write([new ClipboardItem({ "image/png": pngBlob })])
                .then(function () {
                    reset("Copied");
                }, copyAsText);
            return;
        }
        copyAsText();
    });
});
function drawFrame(b64: string, codec: string) {
    var im = new Image();
    im.onload = function () {
        canvas.width = im.naturalWidth;
        canvas.height = im.naturalHeight;
        canvas.getContext("2d")!.drawImage(im, 0, 0);
        // A <canvas> stretches its buffer to fill its CSS box, so a daemon frame whose aspect
        // differs from the pinned snapshot box would squish. Cache the buffer dims and re-fit the
        // element (contain, centred) so the frame letterboxes within the snapshot footprint
        // instead of distorting to fill it.
        liveW = im.naturalWidth;
        liveH = im.naturalHeight;
        fitLiveCanvas();
    };
    im.src = "data:image/" + (codec || "png") + ";base64," + b64;
}
// --- Live input forwarding (no-op on the snapshot lane). Coordinates are image-natural
// pixels; pointer events are grouped by pointerId so Compose's gesture pipeline tracks drags
// and multi-touch. Keys map to Android KEYCODE_* decimal strings (the daemon's wire format).
function liveActive(): boolean {
    return !!(ws && ws.readyState === 1 && canvas.width);
}
function sendInput(msg: InputMessage) {
    var socket = ws;
    if (!socket || !liveActive()) return;
    socket.send(JSON.stringify(Object.assign({ type: "input" }, msg)));
}
function pixel(ev: MouseEvent) {
    var rect = canvas.getBoundingClientRect();
    if (!rect.width || !rect.height) return null;
    return {
        x: Math.round(((ev.clientX - rect.left) / rect.width) * canvas.width),
        y: Math.round(((ev.clientY - rect.top) / rect.height) * canvas.height),
    };
}
// Per-pointer state. The pointerDown is *deferred* until the first move so a tap with no drag
// becomes a single `click` (matching the daemon's CLICK fast-path, which renders between press
// and release — a batched down+up can race Modifier.clickable). pointermove is coalesced to one
// send per pointerId per animation frame, so a fast drag doesn't flood the lane and concurrent
// fingers don't overwrite each other (multi-touch).
var pointers: Record<string, PointerState> = {};
var pendingMoves: Record<string, InputMessage> = {};
var moveScheduled = false;
function flushMoves() {
    moveScheduled = false;
    var snapshot = pendingMoves;
    pendingMoves = {};
    Object.keys(snapshot).forEach(function (id) {
        sendInput(snapshot[id]);
    });
}
canvas.addEventListener("pointerdown", function (ev) {
    if (!liveActive()) return;
    var p = pixel(ev);
    if (!p) return;
    canvas.focus();
    try {
        canvas.setPointerCapture(ev.pointerId);
    } catch (e) {}
    // The device class travels with every event of this gesture. Compose treats a mouse drag and
    // a finger drag as different gestures — only the mouse one drags out a text selection — so
    // forwarding a real mouse as touch made selection impossible on the live lane.
    pointers[ev.pointerId] = {
        x: p.x,
        y: p.y,
        moved: false,
        pointerType: ev.pointerType || "mouse",
    };
});
canvas.addEventListener("pointermove", function (ev) {
    if (!liveActive() || ev.buttons === 0) return; // only while pressed (a drag)
    var st = pointers[ev.pointerId];
    if (!st) return;
    var p = pixel(ev);
    if (!p) return;
    if (!st.moved) {
        // First movement → this is a drag: emit the deferred press at the original point.
        st.moved = true;
        sendInput({
            kind: "pointerDown",
            pixelX: st.x,
            pixelY: st.y,
            pointerId: ev.pointerId,
            pointerType: st.pointerType,
        });
    }
    pendingMoves[ev.pointerId] = {
        kind: "pointerMove",
        pixelX: p.x,
        pixelY: p.y,
        pointerId: ev.pointerId,
        pointerType: st.pointerType,
    };
    if (!moveScheduled) {
        moveScheduled = true;
        requestAnimationFrame(flushMoves);
    }
});
function endPointer(ev: PointerEvent) {
    var st = pointers[ev.pointerId];
    if (!st) return;
    delete pointers[ev.pointerId];
    var p = pixel(ev) || { x: st.x, y: st.y };
    if (st.moved) {
        flushMoves();
        sendInput({
            kind: "pointerUp",
            pixelX: p.x,
            pixelY: p.y,
            pointerId: ev.pointerId,
            pointerType: st.pointerType,
        });
    } else {
        // No drag → a tap. Send a single CLICK (the daemon renders between press and release).
        sendInput({
            kind: "click",
            pixelX: st.x,
            pixelY: st.y,
            pointerId: ev.pointerId,
            pointerType: st.pointerType,
        });
    }
}
canvas.addEventListener("pointerup", endPointer);
canvas.addEventListener("pointercancel", endPointer);
canvas.addEventListener(
    "wheel",
    function (ev) {
        if (!liveActive()) return;
        var p = pixel(ev);
        if (!p) return;
        ev.preventDefault();
        // Both daemon dispatchers drop non-key input without a position, so include the pixel.
        sendInput({
            kind: "rotaryScroll",
            pixelX: p.x,
            pixelY: p.y,
            scrollDeltaY: ev.deltaY,
        });
    },
    { passive: false },
);
// Keyboard: focus the canvas (tabindex) to type. Maps the common keys to Android keycodes;
// unmapped keys are dropped (the daemon ignores codes outside its translation table anyway).
canvas.tabIndex = 0;
// The keycode/text pair moved to `cli/serve-web/src/viewer/keyInput.ts`. Both halves matter and
// conflating them is a shipped bug: a keycode names a PHYSICAL KEY, so sending only that made the
// arrows and Backspace work while nothing could ever be typed.
function keyInput(kind: string, ev: KeyboardEvent) {
    if (!liveActive()) return;
    // Carried on the release too, not just the press: a backend that suppresses the physical key
    // event for a focused text field (so the character isn't typed twice) needs to suppress both
    // halves, or the composition sees an unpaired key-up.
    var message = rules.keyMessage(ev);
    if (!message) return;
    var code = message.code;
    var text = message.text;
    ev.preventDefault();
    var msg: InputMessage = { kind: kind };
    if (code !== null) msg.keyCode = code;
    if (text !== null) msg.text = text;
    sendInput(msg);
}
canvas.addEventListener("keydown", function (ev) {
    keyInput("keyDown", ev);
});
canvas.addEventListener("keyup", function (ev) {
    keyInput("keyUp", ev);
});
function openStream() {
    root.setAttribute("data-mode", "live");
    // Seed the canvas buffer with the current snapshot *before* the swap, so there's no blank
    // flash while "connecting…" (the first frame overwrites it).
    if (img.naturalWidth && img.naturalHeight) {
        canvas.width = img.naturalWidth;
        canvas.height = img.naturalHeight;
        try {
            canvas.getContext("2d")!.drawImage(img, 0, 0);
        } catch (e) {}
    }
    // Mount the canvas as an absolute overlay on the snapshot's slot — the same fixed box the
    // Wasm tier locks to. The img stays in flow (visibility:hidden keeps its slot), so the stage
    // geometry is defined once by the snapshot and a live frame whose pixel dims differ from the
    // baked PNG scales into this box instead of resizing the stage (drawFrame only touches the
    // buffer now, never the layout). Input mapping reads the buffer size, so it's unaffected.
    canvas.classList.add("cp-canvas-live");
    positionOverlay(canvas);
    img.style.visibility = "hidden";
    canvas.hidden = false;
    setPending("connecting…");
    var proto = location.protocol === "https:" ? "wss:" : "ws:";
    // Request WebP frames (smaller; the browser decodes them via the data URL, and the daemon
    // downgrades to PNG when it can't encode WebP — each frame carries its actual codec).
    var qs = query();
    // Track whether the stream ever delivered a frame: a close/error *before* the first frame is
    // a failed activation (surface it), whereas a close *after* frames is just a normal teardown.
    var liveGotFrame = false;
    // Hold the socket in a per-activation local as well as `ws`, and gate every callback on
    // `ws === sock`. Toggling Live off and straight back on opens a replacement before the old
    // socket's close event is delivered, and that stale callback would otherwise clear the NEW
    // connection's pending badge (its own liveGotFrame is true, so it skips the error branch)
    // and null out `ws` — orphaning the live socket the input/override senders reach for.
    var sock = new WebSocket(
        proto +
            "//" +
            location.host +
            base +
            "/ws/" +
            encodeURIComponent(previewId) +
            "?" +
            (qs ? qs + "&codec=webp" : "codec=webp"),
    );
    ws = sock;
    sock.onopen = function () {
        // The connect URL seeds only query()'s fields — the display axes, the overlays, and changed
        // knobs — so every knob, and anything toggled during the connecting window, isn't in it.
        // Replay the full live override map once the socket is ready so the daemon reflects the
        // exact current control state, including a control changed before onopen whose change event
        // the readyState guard dropped.
        sock.send(
            JSON.stringify({
                type: "setOverrides",
                overrides: liveOverrides(),
            }),
        );
    };
    sock.onmessage = function (ev) {
        // A frame from a socket the viewer has already replaced is stale in both senses: it must
        // not paint over the new lane's stage, nor report it as connected.
        if (ws !== sock) return;
        var m;
        try {
            m = JSON.parse(ev.data);
        } catch (e) {
            return;
        }
        if (m.type === "frame") {
            liveGotFrame = true;
            clearModeError();
            drawFrame(m.dataBase64, m.codec);
            setPending(null);
        } else if (m.type === "error") {
            showModeError(m.message || "Live preview error.");
        }
    };
    // onerror always precedes onclose; let onclose decide (it carries the code/reason). Only
    // surface here if the socket somehow errors while already open+frame-less and never closes.
    sock.onerror = function () {
        if (ws === sock && !liveGotFrame) setPending("connecting…");
    };
    sock.onclose = function (ev) {
        // Not the current socket ⇒ a teardown the viewer already accounted for in closeStream()
        // (which cleared pending and restored the snapshot). Leave the live lane's state alone.
        if (ws !== sock) return;
        ws = null;
        // The lane is done waiting either way — it painted, or it failed (showModeError below).
        setPending(null);
        // Closed before any frame ⇒ the mode failed to activate. Drop the stale seeded snapshot
        // from the canvas so it can't masquerade as a live render, and surface why.
        if (!liveGotFrame && live && live.checked) {
            canvas.hidden = true;
            img.style.removeProperty("visibility");
            showModeError(liveCloseReason(ev));
        }
    };
}
function closeStream() {
    root.setAttribute("data-mode", "snapshot");
    // Toggling Live off mid-connect must not leave the badge stuck on "connecting…".
    setPending(null);
    if (ws) {
        ws.close();
        ws = null;
    }
    canvas.hidden = true;
    // Tear down the overlay: drop the absolute positioning and restore the snapshot img's slot.
    canvas.classList.remove("cp-canvas-live");
    canvas.style.removeProperty("left");
    canvas.style.removeProperty("top");
    canvas.style.removeProperty("width");
    canvas.style.removeProperty("height");
    // Forget the last frame's dims so a reconnect seeds from the snapshot (fill) until its own
    // first frame re-fits, rather than briefly letterboxing to a stale aspect.
    liveW = 0;
    liveH = 0;
    img.style.removeProperty("visibility");
    img.hidden = false;
}
// --- Wasm tier (the in-browser CMP app, mounted in a sandboxed iframe). Only wired when the
// session carries a Wasm app (data-wasm-src present). Theme/font-scale/locale re-point the
// iframe's ?uiMode/?fontScale/?localeTag (device/orientation are server-render-only).
const wasmFrame = may<HTMLIFrameElement>("cp-wasm");
const wasmToggle = may<HTMLInputElement>("cp-wasm-toggle");
var wasmSrc = root.getAttribute("data-wasm-src") || "";
// Set once the app has painted its first frame (its "cp-wasm-ready" message). Until then a
// control change re-points ?query (initial load); after, it posts an override patch so the
// app recomposes in place instead of reloading the whole ~20 MB Wasm bundle.
var wasmReady = false;
// Boot watchdog: if the app never signals "cp-wasm-ready" (bundle 404, Wasm/GL failure, …),
// surface a visible error instead of leaving the stage stuck on "loading Wasm…" forever.
var wasmBootTimer: ReturnType<typeof setTimeout> | null = null;
function wasmBaseSrc() {
    if (!wasmSrc) return "";
    // The src comes from a server-set data- attribute, but resolve it against our own origin and
    // refuse anything not same-origin http(s) anyway — so a `javascript:`/`data:` URL can never
    // reach the iframe even if the attribute were ever mis-set (defuses DOM-text-as-HTML). The
    // query is left as the server baked it (the variant's default theme) — session overrides
    // never go in it, so it stays the app's clean base to revert to when a control is cleared.
    var u;
    try {
        u = new URL(wasmSrc, location.origin);
    } catch (e) {
        return "";
    }
    if (u.origin !== location.origin) return "";
    return u.href;
}
// NOTE: the font prefetch lives in the app's own index.html (it starts the manifest+font
// fetches at document load, in parallel with the Wasm boot), not on this page. That's where
// it belongs regardless of the sandbox: it must be in flight before the iframe navigates, and
// the app is the one that consumes the promises. (Historically the iframe was opaque-origin
// with its own cache partition, so a page-side preload was also unreusable and fetched every
// font twice; with allow-same-origin the partition is shared, but the app-side prefetch is
// still the right home, so keep page-side preloads out — see the ServeWebFixtureTest guard.)
// The override patch (theme / font scale / locale) the running app merges over its baked base —
// a bare `a=b&c=d` query. An absent key falls back to the app's baked default (e.g. cleared
// Theme → the variant's uiMode). Device / orientation are server-render-only, so not forwarded.
// The stage checkerboard's tile origin in the iframe's own CSS-px coordinates. The app can't
// render a transparent surface, so it paints this same pattern itself; handing it the phase
// makes the in-canvas cells continue the page's cells exactly (the stylesheet positions the
// 16px tile at 50% of the stage's padding box; the iframe sits at style.left/top within it).
function wasmBgPhase() {
    var left = parseFloat(wasmFrame!.style.left) || 0;
    var top = parseFloat(wasmFrame!.style.top) || 0;
    var x = (stage.clientWidth - 16) / 2 - left;
    var y = (stage.clientHeight - 16) / 2 - top;
    return x.toFixed(2) + "," + y.toFixed(2);
}
// The stage's own backdrop, handed to the Wasm app so the sticker sits on the same thing it
// sits on in the snapshot. The app can't render a transparent surface, so it paints *something*
// behind the component either way: without this it always painted the checkerboard, which
// appeared out of nowhere the moment Wasm was enabled on the solid (default) stage. In the
// page's Transparent mode there is no solid colour to send — the app continues the checkerboard
// itself, positioned by `bgPhase`.
function wasmStageBg() {
    if (document.documentElement.classList.contains("cp-bg-transparent"))
        return "checker";
    var rgb = getComputedStyle(stage).backgroundColor || "";
    const m = rgb.match(/^rgba?\((\d+),\s*(\d+),\s*(\d+)/);
    if (!m) return "checker";
    return (
        "#" +
        [1, 2, 3]
            .map(function (i) {
                return ("0" + parseInt(m[i], 10).toString(16)).slice(-2);
            })
            .join("")
    );
}
function wasmOverridePatch() {
    var parts = [];
    var uiMode = chosenUiMode();
    if (uiMode) parts.push("uiMode=" + encodeURIComponent(uiMode));
    var loc = may<HTMLInputElement>("cp-localeTag");
    if (loc && loc.value)
        parts.push("localeTag=" + encodeURIComponent(loc.value));
    if (fontScaleTouched && fs)
        parts.push("fontScale=" + encodeURIComponent(fs.value));
    parts.push("bgPhase=" + encodeURIComponent(wasmBgPhase()));
    parts.push("stageBg=" + encodeURIComponent(wasmStageBg()));
    // Author-declared knobs also apply in the browser: the wasm catalog seeds its
    // `catalogOverride*` from these `knob.<key>` params.
    //
    // Compared against the AUTHOR DEFAULT, not against `data-knob-initial` as query() does — and
    // that difference is the whole point. A `@OverrideVariant` sticker (the unchecked checkbox, the
    // disabled button) opens with its knob already seeded away from the author default, so
    // `val === initial` holds and an initial-based filter would send nothing. The PNG lane can
    // afford that because the baked capture already carries the seed; the Wasm tier has no baked
    // artifact — it mounts the live component from `?id=<slug>`, and `wasmAppSrc` strips the
    // variant axis off the id — so an unsent seed silently mounts the PRIMARY (a checked checkbox,
    // an enabled button) under a sticker that says otherwise. A knob genuinely at its author
    // default is still omitted, so an ordinary sticker is unchanged.
    controls(".cp-knob").forEach(function (el) {
        if (el.disabled) return;
        var key = el.getAttribute("data-knob-key");
        if (!key) return;
        var val = controlValue(el);
        // See liveOverrides(): "" is a value for a string knob and a no-op for every other kind.
        // This one matters most — an @OverrideVariant seeding `label=` opens the control empty, and
        // wasmAppSrc has already stripped the variant axis off the id, so dropping the seed here
        // mounts the PRIMARY under a sticker that says otherwise, with no baked artifact to fall
        // back on.
        if (val === "" && knobKind(el) !== "string") return;
        // Older pages carry no `data-knob-default`; fall back to the initial so they behave as before
        // rather than sending every knob.
        var authorDefault = el.getAttribute("data-knob-default");
        if (authorDefault === null)
            authorDefault = el.getAttribute("data-knob-initial") || "";
        if (val === authorDefault) return;
        parts.push(
            "knob." + encodeURIComponent(key) + "=" + encodeURIComponent(val),
        );
    });
    return parts.join("&");
}
// Initial iframe URL: the baked base plus the current overrides in the `#…` fragment, so the
// app's first paint honours them yet keeps the query as its true base (a later clear reverts).
function wasmInitialSrc() {
    var base = wasmBaseSrc();
    if (!base) return "";
    var patch = wasmOverridePatch();
    return patch ? base + "#" + patch : base;
}
// Pixel parity: lay an absolute overlay ([el] — the Wasm iframe or the live canvas) exactly
// over the snapshot's rendered box, so switching to it shouldn't move anything. Both the Wasm
// app (contain-fitting the same sticker geometry the snapshot baked) and the daemon (the same
// preview re-rendered) fill this box, so the three transports share one geometry.
function positionOverlay(el: HTMLElement) {
    var sr = stage.getBoundingClientRect();
    var r = img.getBoundingClientRect();
    if (r.width > 0 && r.height > 0) {
        // Offsets are relative to the stage's padding box — subtract its border (clientLeft/Top).
        el.style.left = r.left - sr.left - stage.clientLeft + "px";
        el.style.top = r.top - sr.top - stage.clientTop + "px";
        el.style.width = r.width + "px";
        el.style.height = r.height + "px";
    } else {
        // No snapshot box to mirror (e.g. its render 404'd): fill the stage's content box.
        el.style.left = "12px";
        el.style.top = "12px";
        el.style.width = "calc(100% - 24px)";
        el.style.height = stage.clientHeight - 24 + "px";
    }
}
function positionWasmFrame() {
    positionOverlay(wasmFrame!);
}
// The live canvas can't just fill the snapshot box like the Wasm frame does: a <canvas>
// stretches its buffer to its CSS box, so pinning a differently-shaped daemon frame to the
// snapshot's rect squished the render. Fit the frame (contain) inside that rect, centred — it
// letterboxes within the snapshot footprint (so the stage still never resizes, the property
// the pinned box was introduced for) instead of distorting. liveW/liveH cache the current
// buffer so a window resize re-fits; unset (before the first frame, when the buffer is seeded
// from the same-aspect snapshot) it fills the box exactly, matching positionOverlay.
var liveW = 0;
var liveH = 0;
function fitLiveCanvas() {
    var sr = stage.getBoundingClientRect();
    var r = img.getBoundingClientRect();
    var boxLeft, boxTop, boxW, boxH;
    if (r.width > 0 && r.height > 0) {
        boxLeft = r.left - sr.left - stage.clientLeft;
        boxTop = r.top - sr.top - stage.clientTop;
        boxW = r.width;
        boxH = r.height;
    } else {
        boxLeft = 12;
        boxTop = 12;
        boxW = stage.clientWidth - 24;
        boxH = stage.clientHeight - 24;
    }
    var w = boxW;
    var h = boxH;
    if (liveW > 0 && liveH > 0) {
        var scale = Math.min(boxW / liveW, boxH / liveH);
        w = liveW * scale;
        h = liveH * scale;
    }
    canvas.style.left = boxLeft + (boxW - w) / 2 + "px";
    canvas.style.top = boxTop + (boxH - h) / 2 + "px";
    canvas.style.width = w + "px";
    canvas.style.height = h + "px";
}
// Swap the stage from the snapshot to the (already-painted) Wasm frame. The snapshot keeps
// its layout slot (visibility, not display) so the stage geometry — and the overlay tracking
// it — never shifts.
function revealWasm() {
    if (!wasmActive() || wasmReady) return;
    wasmReady = true;
    if (wasmBootTimer) {
        clearTimeout(wasmBootTimer);
        wasmBootTimer = null;
    }
    clearModeError();
    positionWasmFrame();
    wasmFrame!.classList.add("cp-wasm-live");
    img.style.visibility = "hidden";
    status.textContent = "";
    // Re-sync any control changed during load (the fragment only captured open-time state).
    var patch = wasmOverridePatch();
    if (patch && wasmFrame!.contentWindow)
        wasmFrame!.contentWindow.postMessage(patch, "*");
}
function openWasm() {
    // No-op without a Wasm app (wasmFrame absent): enterMode() calls this unconditionally, but a
    // non-Wasm daemon/static session has no iframe to drive. Guard so touching it can't throw.
    if (!wasmFrame) return;
    // Wasm and the daemon stream are mutually exclusive; the mode switch (enterMode) tears the
    // stream down before opening Wasm, so there's nothing extra to close here.
    root.setAttribute("data-mode", "wasm");
    canvas.hidden = true;
    // Keep the snapshot visible while the app loads; the iframe mounts over it at opacity 0
    // and only fades in on the app's first-frame signal — no blank/white flash.
    positionWasmFrame();
    wasmFrame.hidden = false;
    wasmReady = false;
    wasmFrame.src = wasmInitialSrc();
    status.textContent = "loading Wasm…";
    if (wasmBootTimer) clearTimeout(wasmBootTimer);
    wasmBootTimer = setTimeout(function () {
        if (!wasmReady && wasmActive()) {
            showModeError(
                "Wasm preview didn't start — the in-browser app failed to load.",
            );
        }
    }, 20000);
}
function closeWasm() {
    // No Wasm iframe (non-Wasm session) ⇒ nothing to tear down; enterMode() still calls this
    // unconditionally when switching to Live/PNG, so guard against the null frame.
    if (!wasmFrame) return;
    root.setAttribute("data-mode", "snapshot");
    wasmReady = false;
    if (wasmBootTimer) {
        clearTimeout(wasmBootTimer);
        wasmBootTimer = null;
    }
    wasmFrame.classList.remove("cp-wasm-live");
    wasmFrame.hidden = true;
    wasmFrame.removeAttribute("src");
    img.style.removeProperty("visibility");
    img.hidden = false;
    status.textContent = "";
}
function wasmActive() {
    return !!(wasmToggle && wasmToggle.checked);
}

// ---- Design spec lane -------------------------------------------------------------------
// The imported design reference (design-parity's Figma/PNG spec for this exact preview id),
// shown on the same stage as the render so "what the code draws" and "what the design says"
// occupy the same pixels and can be flipped between. Present only when the served catalog
// published a reference — every other session finds no elements here and the lane is inert.
//
// Nothing is fetched from Figma: the src is this server's own `/reference/<id>.png`, carried on
// the lane element by the server. It is assigned on first entry rather than at page load, so a
// visitor who never opens the lane never pays for the bytes.
const specLane = may<HTMLElement>("cp-spec-lane");
const specImg = may<HTMLImageElement>("cp-spec-img");
const specToggle = may<HTMLInputElement>("cp-spec-toggle");
var specSrc = specLane ? specLane.getAttribute("data-spec-src") || "" : "";
var specLoaded = false; // the raster is requested once, on the lane's first entry
// Same treatment as the Wasm iframe's src (see wasmBaseSrc): the URL comes from a server-set
// data- attribute, but it is resolved against our own origin and refused unless it stays on it,
// so a `javascript:`/`data:` URL can never reach the stage even if the attribute were ever
// mis-set — which is also what defuses the DOM-text-as-HTML rule for this assignment.
function specRasterSrc() {
    if (!specSrc) return "";
    var u;
    try {
        u = new URL(specSrc, location.origin);
    } catch (e) {
        return "";
    }
    if (u.origin !== location.origin) return "";
    return u.href;
}
function specAvailable() {
    return !!(specImg && specRasterSrc());
}
function specActive() {
    return !!(specToggle && specToggle.checked);
}
function openSpec() {
    if (!specAvailable()) return;
    root.setAttribute("data-mode", "spec");
    // The snapshot is taken out of flow (not merely hidden) exactly like the RC canvas lane, so
    // the stage sizes to the spec raster instead of stacking two images.
    img.style.display = "none";
    canvas.hidden = true;
    specImg!.hidden = false;
    if (!specLoaded) {
        specLoaded = true;
        specImg!.addEventListener("error", function () {
            showModeError("The design spec could not be loaded.");
        });
        // A property write, like every other image/frame lane here (`img.src`, `wasmFrame.src`),
        // of an origin-checked URL.
        specImg!.src = specRasterSrc();
    }
    if (status) status.textContent = "";
    if (window.cpSpecCompare) window.cpSpecCompare.open(specActualUrl());
}
function closeSpec() {
    if (window.cpSpecCompare) window.cpSpecCompare.close();
    if (!specImg) return;
    if (root.getAttribute("data-mode") === "spec")
        root.setAttribute("data-mode", "snapshot");
    specImg.hidden = true;
    img.style.removeProperty("display");
    img.hidden = false;
}
// ---- The Motion lane -------------------------------------------------------------------------
// The recorded interaction for this preview, on the stage in place of the still.
//
// Two things set this lane apart from every other one here, and both point the same way: nothing
// until asked. The capture is tens to hundreds of frames against one PNG, and an APNG/GIF starts
// playing the moment it decodes — so assigning `src` IS starting playback. The src is therefore
// written on the lane's FIRST ENTRY and never at page load, which is what makes "selectable, not
// default" true of the bytes and not merely of the pixels.
const motionImg = may<HTMLImageElement>("cp-motion-img");
const motionChip = may<HTMLButtonElement>("cp-motion-chip");
const motionToggle = may<HTMLInputElement>("cp-motion-toggle");
const motionLane = may<HTMLElement>("cp-motion-lane");
const motionCaption = may<HTMLElement>("cp-motion-caption");
var motionButtons = motionLane
    ? Array.prototype.slice.call(motionLane.querySelectorAll(".cp-motion-view"))
    : [];
var motionErrorBound = false;
// The buttons are the lane's source of truth, so a preview with ONE capture still renders one
// (its group merely hidden) and this has a single code path rather than a special case.
function motionPicked() {
    for (var i = 0; i < motionButtons.length; i++) {
        if (motionButtons[i].getAttribute("aria-pressed") === "true")
            return motionButtons[i];
    }
    return motionButtons[0] || null;
}
// The same origin check the spec raster and the Wasm frame get, for the same reason: the URL
// arrives on a server-set data- attribute, so it is resolved against our own origin and refused
// unless it stays on it — which is also what defuses the DOM-text-as-HTML rule for the write.
function motionSrcOf(button: HTMLElement | null) {
    var raw = button ? button.getAttribute("data-motion-src") || "" : "";
    if (!raw) return "";
    var u;
    try {
        u = new URL(raw, location.origin);
    } catch (e) {
        return "";
    }
    if (u.origin !== location.origin) return "";
    return u.href;
}
function motionAvailable() {
    return !!(motionImg && motionSrcOf(motionPicked()));
}
/** The picked capture's published id — what `?motion=` carries. */
function motionPickedId() {
    var button = motionPicked();
    return button ? button.getAttribute("data-motion-id") || "" : "";
}
/**
 * Press the button for a named capture, if this preview published one. Used by URL hydration:
 * a shared `?mode=motion&motion=<id>` link has to open on the recording that was shared, and an
 * id this preview does not carry is ignored rather than blanking the lane.
 */
function pickMotion(id: string) {
    if (!id) return false;
    const wanted = motionButtons.find(function (b) {
        return b.getAttribute("data-motion-id") === id;
    });
    if (!wanted) return false;
    motionButtons.forEach(function (b) {
        b.setAttribute("aria-pressed", b === wanted ? "true" : "false");
    });
    return true;
}
// The radio, not `data-mode` — exactly as specActive() and sourceActive() do, and for the same
// reason: on a URL restore the radio is checked before the transition paints, so reading the
// stage attribute here would report the lane inactive while the page was entering it.
function motionActive() {
    return !!(motionToggle && motionToggle.checked);
}
function playMotion() {
    var button = motionPicked();
    var src = motionSrcOf(button);
    if (!src) return;
    // One capture failing must not condemn the rest. The error overlay is shared across lanes and
    // is raised by this lane's own `error` handler, so without this a picker click away from a
    // missing artifact would load the replacement successfully and leave the previous failure's
    // message sitting over it. Cleared on the way IN, so the message survives until something is
    // actually done about it.
    clearModeError();
    // The caption names the recording — but ONLY when nothing else already does. With two or more
    // captures the picker is visible and its pressed button carries the same words, and printing
    // them again beside it is two controls stating one fact ("Tap the avatar [Tap the avatar]").
    // So the readout is what stands in for the picker on the single-capture case, not a label
    // duplicating it.
    if (motionCaption) {
        motionCaption.textContent =
            motionButtons.length > 1 || !button
                ? ""
                : button.getAttribute("data-motion-label") || "";
    }
    // A property write of an origin-checked URL, like every other image lane here. Guarded so
    // re-entering the lane on the capture already loaded does not re-request it; closeMotion()
    // drops the attribute on the way out, so the guard never sees a stale match.
    if (motionImg!.getAttribute("src") !== src) motionImg!.src = src;
}
function openMotion() {
    if (!motionAvailable()) return;
    root.setAttribute("data-mode", "motion");
    // Out of flow rather than merely hidden, like the spec and Source lanes: the stage sizes to the
    // capture instead of reserving the still's box underneath it.
    img.style.display = "none";
    canvas.hidden = true;
    motionImg!.hidden = false;
    if (motionLane) motionLane.hidden = false;
    if (!motionErrorBound) {
        motionErrorBound = true;
        motionImg!.addEventListener("error", function () {
            showModeError("The recorded interaction could not be loaded.");
        });
    }
    if (status) status.textContent = "";
    playMotion();
}
function closeMotion() {
    if (!motionImg) return;
    // `data-mode`, not motionActive(): by the time a transition calls this, the radio for the lane
    // being ENTERED is already checked, so the flag would say we are not on Motion and the stage
    // would keep its attribute. The same split the spec and Source lanes make.
    if (root.getAttribute("data-mode") === "motion")
        root.setAttribute("data-mode", "snapshot");
    motionImg.hidden = true;
    if (motionLane) motionLane.hidden = true;
    // Dropping the src STOPS the animation and releases its frames. Left assigned, a hidden capture
    // would keep looping for the rest of the visit — invisible, and still decoding.
    motionImg.removeAttribute("src");
    img.style.removeProperty("display");
    img.hidden = false;
}
// ---- The Source lane -------------------------------------------------------------------------
// The usage code behind this card, on the stage in place of the render.
//
// Not a renderer, so not an option in the renderer combo — a chip of its own, exactly like the
// design-spec chip beside it and for the same reason. The snippet is fetched from `/usage/<id>`
// on FIRST ENTRY, never at page load: deriving it costs the server a GitHub read on a cold cache,
// and most visitors to a preview never open this.
const sourceChip = may<HTMLButtonElement>("cp-source-chip");
const sourcePanel = may<HTMLElement>("cp-source-panel");
const sourceToggle = may<HTMLInputElement>("cp-source-toggle");
var sourceLoaded = false;
var pendingSourceData: UsageSnippet | null | undefined;
function sourceAvailable() {
    return !!(sourceChip && sourcePanel && usageSrc());
}
// The radio, not `data-mode` — exactly as specActive() does. On a URL restore the radio is
// checked before the transition paints, so reading the stage attribute here would report the
// lane as inactive while the page was in the middle of entering it.
function sourceActive() {
    return !!(sourceToggle && sourceToggle.checked);
}
// Same origin check the spec raster and the Wasm frame get: the URL arrives on a server-set
// data- attribute, and is refused unless it resolves onto our own origin.
function usageSrc() {
    var raw = sourceChip ? sourceChip.getAttribute("data-usage-src") || "" : "";
    if (!raw) return "";
    var u;
    try {
        u = new URL(raw, location.origin);
    } catch (e) {
        return "";
    }
    if (u.origin !== location.origin) return "";
    return u.href;
}
function openSource() {
    if (!sourceAvailable()) return;
    root.setAttribute("data-mode", "source");
    // Out of flow rather than merely hidden, like the spec lane: the stage sizes to the panel
    // instead of reserving the render's box underneath it.
    img.style.display = "none";
    canvas.hidden = true;
    sourcePanel!.hidden = false;
    if (status) status.textContent = "";
    if (sourceLoaded && pendingSourceData !== undefined) {
        var pending = pendingSourceData;
        pendingSourceData = undefined;
        renderSource(pending);
    }
    if (!sourceLoaded) {
        sourceLoaded = true;
        renderSourceMessage("Loading…");
        fetch(usageSrc(), { credentials: "same-origin" })
            .then(function (r) {
                return r.ok ? r.json() : Promise.reject(r.status);
            })
            .then(function (data: UsageSnippet | null) {
                // Do not initialise CodeMirror in a display:none panel: it caches fallback
                // dimensions and reopens with a broken gutter. Keep the payload until Source is
                // visible again, then paint and measure it in-flow.
                if (sourcePanel!.hidden) pendingSourceData = data;
                else renderSource(data);
            })
            .catch(function () {
                // A cleaner that declined, or a catalog whose source moved, answers 404 — which is a
                // real answer and not an error to shout about. The `source` link in the provenance row
                // still reaches the preview's own Kotlin, so say that rather than leaving a blank panel.
                sourceLoaded = false;
                renderSourceMessage(
                    "The usage source for this preview could not be derived. The \u201csource\u201d link " +
                        "above opens the preview\u2019s own Kotlin on GitHub.",
                );
            });
    }
}
function closeSource() {
    if (!sourcePanel) return;
    // `data-mode`, not sourceActive(): by the time a transition calls this the radio for the lane
    // being entered is already checked, so the flag would say we are not on Source and the stage
    // would keep its attribute. Same split the spec lane makes for the same reason.
    if (root.getAttribute("data-mode") === "source")
        root.setAttribute("data-mode", "snapshot");
    sourcePanel.hidden = true;
    img.style.removeProperty("display");
    img.hidden = false;
}
function renderSourceMessage(text: string) {
    if (!sourcePanel) return;
    sourcePanel.textContent = "";
    var p = document.createElement("p");
    p.className = "cp-source-note";
    p.textContent = text;
    sourcePanel.appendChild(p);
}
function codeMirrorStylesReady() {
    var link = document.querySelector<HTMLLinkElement>(
        'link[href*="codemirror.css"]',
    );
    return !!(link && link.sheet);
}
/**
 * Paints the fetched snippet.
 *
 * Every node here is created and filled through `textContent`, never through innerHTML: the
 * payload is Kotlin source read from a catalog's repository, so it is exactly the kind of content
 * that must never be parsed as markup.
 */
function renderSource(data: UsageSnippet | null) {
    if (!sourcePanel) return;
    sourcePanel.textContent = "";
    // Says what this is, and — when the catalog has not declared what its own helpers mean — is
    // honest that what follows still carries them. The playground's seed note makes the same
    // distinction; the two must not disagree about the same snippet.
    var note = document.createElement("p");
    note.className = "cp-source-note";
    if (data && data.scaffoldsDeclared === false) {
        note.className += " cp-source-note--warn";
        note.textContent =
            "This catalog has not declared what its own helpers mean in plain " +
            "Compose, so some of the code below is catalog machinery rather than usage.";
    } else if (data && data.residue && data.residue.length) {
        note.className += " cp-source-note--warn";
        note.textContent =
            "Usage code, with " +
            data.residue.join(", ") +
            " left as the catalog wrote them \u2014 those will not resolve outside it.";
    } else {
        note.textContent = "The plain Compose that produces this render.";
    }
    sourcePanel.appendChild(note);
    var pre = document.createElement("pre");
    var code = document.createElement("code");
    var sourceText = (data && data.text) || "";
    code.textContent = sourceText;
    pre.appendChild(code);
    sourcePanel.appendChild(pre);
    // Upgrade the already-readable <pre> only after CodeMirror has successfully initialised. The
    // asset is optional by design: a blocked/failed script costs line numbers and Kotlin colours,
    // never the source itself. The read-only instance uses the exact `text/x-kotlin` clike grammar
    // the playground editor does, so the two surfaces colour the same code the same way.
    var selectionTarget: HTMLElement = code;
    if (window.CodeMirror && codeMirrorStylesReady()) {
        var mirrorHost = document.createElement("div");
        mirrorHost.className = "cp-source-code";
        sourcePanel.insertBefore(mirrorHost, pre);
        try {
            var mirror = window.CodeMirror(mirrorHost, {
                value: sourceText,
                mode: "text/x-kotlin",
                lineNumbers: true,
                readOnly: "nocursor",
                viewportMargin: Infinity,
                screenReaderLabel: "Kotlin usage source",
            });
            selectionTarget = mirror.getWrapperElement();
            pre.remove();
        } catch (e) {
            mirrorHost.remove();
        }
    }
    var actions = document.createElement("div");
    actions.className = "cp-source-actions";
    var copy = document.createElement("button");
    copy.type = "button";
    copy.className = "cp-fmt-toggle";
    copy.textContent = "Copy";
    copy.addEventListener("click", function () {
        var text = sourceText;
        var done = function () {
            copy.textContent = "Copied";
            setTimeout(function () {
                copy.textContent = "Copy";
            }, 1500);
        };
        // The Clipboard API needs a secure context, which a serve host reached over plain HTTP on a
        // LAN address is not. The fallback SELECTS the code rather than only telling the visitor to
        // press a shortcut: an instruction to press ⌘C with nothing selected copies whatever else
        // happened to be, and names the wrong key on every non-Mac platform besides.
        var fallback = function () {
            try {
                var range = document.createRange();
                // CodeMirror tokenises into spans; selecting its code body preserves the source's
                // visible line breaks without including the line-number gutter. The plain <code>
                // is the same target when the optional highlighter did not load.
                var visibleCode =
                    selectionTarget.querySelector<HTMLElement>(
                        ".CodeMirror-code",
                    ) || selectionTarget;
                range.selectNodeContents(visibleCode);
                var sel = window.getSelection();
                sel!.removeAllRanges();
                sel!.addRange(range);
                // Deprecated, and still the only synchronous copy an insecure context has. When it
                // works the visitor needs no shortcut at all; when it does not, the text is at least
                // selected and ready for one.
                if (document.execCommand && document.execCommand("copy")) {
                    sel!.removeAllRanges();
                    done();
                    return;
                }
            } catch (e) {
                /* fall through to the prompt */
            }
            copy.textContent = /Mac|iP(hone|ad|od)/.test(navigator.platform)
                ? "Selected \u2014 press \u2318C"
                : "Selected \u2014 press Ctrl+C";
            setTimeout(function () {
                copy.textContent = "Copy";
            }, 3000);
        };
        if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(text).then(done, fallback);
        } else {
            fallback();
        }
    });
    actions.appendChild(copy);
    // Onward to the editor, but only where this host can actually compile the catalog — the
    // server decides that and sends a href or nothing, so the panel never offers a dead run.
    if (data && data.playgroundHref) {
        var run = document.createElement("a");
        run.className = "cp-format-link";
        run.href = data.playgroundHref;
        run.textContent = "open in playground \u2192";
        actions.appendChild(run);
    }
    if (data && data.blobUrl) {
        var whole = document.createElement("a");
        whole.className = "cp-format-link";
        whole.href = data.blobUrl;
        whole.rel = "noopener";
        whole.textContent = "the whole sticker \u2192";
        actions.appendChild(whole);
    }
    sourcePanel.appendChild(actions);
}
/**
 * The render the spec is compared against: the exact bytes that were on the stage when we can
 * name them, and a fresh `/render` URL when we cannot.
 *
 * Reusing the blob costs no second render — an override-bearing render is `no-store`, so
 * re-fetching the same URL renders again and can race the daemon's shared override state (see
 * refreshSnapshot) — and it guarantees the comparison is against the pixels the visitor was
 * actually looking at rather than a re-run that might land differently.
 *
 * But the blob is only THAT frame when the visitor arrived from the static raster lane. Two cases
 * where it is a stale bystander instead, and both must fall back to asking the server:
 *
 *  - **An interactive lane.** Live, Wasm and the Remote Compose players paint into a canvas or an
 *    iframe while `#cp-img` still holds the snapshot fetched at page load. Worse, those lanes
 *    apply overrides in place (the daemon takes `setOverrides` over the socket; the Wasm app
 *    applies them in the browser) without ever re-pointing `/render` — so a theme or locale
 *    changed in Live mode leaves the blob describing the state BEFORE that change. Comparing it
 *    would score a frame the visitor never saw. `enterMode` records the outgoing lane before
 *    tearing it down, which is the only moment that fact is still knowable.
 *  - **The SVG toggle.** The blob then holds a vector document whose intrinsic size a `<canvas>`
 *    may not be able to resolve. `data-cp-src` names the `/render` URL that produced the blob, so
 *    its extension is the direct evidence of what those bytes are.
 *
 * The fallback is the PNG of the *current* controls, which is what the server would draw for the
 * state the visitor is in — and if it cannot honour those overrides it refuses, and the
 * comparison honestly reports itself unavailable rather than scoring the wrong frame.
 */
function specActualUrl() {
    var blob = img.getAttribute("data-cp-blob");
    var rendered = (img.getAttribute("data-cp-src") || "").split("?")[0];
    if (blob && outgoingStage === "snapshot" && rendered.slice(-4) === ".png")
        return blob;
    return renderUrl(".png");
}

// ---- In-browser Remote Compose canvas lane ----------------------------------------------
// When this preview carries a captured `.rc` document, the "RC (browser)" toggle paints it with
// the vendored player (RC.RcdPlayer) into #cp-rc-canvas — no daemon — and Remote Compose knob
// edits apply live via setNamed*Override + repaint (onRcKnobChanged) instead of a /render
// round-trip. Opt-in like Live / Wasm, so the default PNG snapshot is untouched.
const rcCanvasEl = may<HTMLCanvasElement>("cp-rc-canvas");
const rcToggle = may<HTMLInputElement>("cp-rc-toggle");
const rcWasmFrame = may<HTMLIFrameElement>("cp-rc-wasm");
const rcWasmToggle = may<HTMLInputElement>("cp-rc-wasm-toggle");
var rcWasmReady = false;
var rcWasmBootTimer: ReturnType<typeof setTimeout> | null = null;
var hasRcDoc = root.getAttribute("data-has-rc-doc") === "1";
var rcPlayer: RcPlayer | null = null; // created lazily on first open
var rcCtx: RemoteContext | null = null; // its WebRemoteContext, for named-value overrides
var rcReady = false; // a first frame is painted and the canvas revealed
var rcScriptState = 0; // 0 = not loaded, 1 = loading, 2 = ready
var rcScriptWaiters: Array<(ok: boolean) => void> = [];
function rcAvailable() {
    return !!(hasRcDoc && rcCanvasEl);
}
function rcActive() {
    return !!(rcToggle && rcToggle.checked);
}
function rcWasmActive() {
    return !!(rcWasmToggle && rcWasmToggle.checked);
}
// Lazy-load the shared player bundle once (a constant, session-independent path); queue callers
// while it loads so a fast re-open can't inject the script twice.
function ensureRcScript(cb: (ok: boolean) => void) {
    if (rcScriptState === 2 || window.RC) {
        rcScriptState = 2;
        cb(true);
        return;
    }
    rcScriptWaiters.push(cb);
    if (rcScriptState === 1) return;
    rcScriptState = 1;
    var s = document.createElement("script");
    s.src = "/rc-player/bundle.js";
    s.onload = function () {
        rcScriptState = 2;
        var ws = rcScriptWaiters;
        rcScriptWaiters = [];
        ws.forEach(function (f) {
            f(true);
        });
    };
    s.onerror = function () {
        rcScriptState = 0;
        var ws = rcScriptWaiters;
        rcScriptWaiters = [];
        ws.forEach(function (f) {
            f(false);
        });
    };
    document.head.appendChild(s);
}
// The `.rc` document URL — the same `base` + token/session as the snapshot, but no override qs
// (the lane serves the document verbatim; knob edits apply client-side).
function rcDocUrl() {
    var parts: string[] = [];
    if (token) parts.push("token=" + encodeURIComponent(token));
    if (session) parts.push("session=" + encodeURIComponent(session));
    var qs = parts.join("&");
    return (
        base +
        "/render/" +
        encodeURIComponent(previewId) +
        ".rc" +
        (qs ? "?" + qs : "")
    );
}
// Parse #RRGGBB / #AARRGGBB (optionally %23-escaped) into a 0xAARRGGBB int (opaque when no alpha).
function parseRcColor(v: string) {
    if (!v) return null;
    var h = v.replace(/^%23/, "").replace(/^#/, "");
    if (h.length === 6) h = "FF" + h;
    if (h.length !== 8) return null;
    var n = parseInt(h, 16);
    return isNaN(n) ? null : n >>> 0;
}
// Push every Remote Compose knob's current value onto the player's context, then repaint. Names
// are USER:-domain-qualified (the connector registers author knobs under USER:), matching the
// document's named variables; kinds mirror query()'s rc.<name>=<kind>:<value> typing.
function applyRcOverrides() {
    const ctx = rcCtx;
    if (!ctx) return;
    controls(".cp-rc-knob").forEach(function (el) {
        var name = el.getAttribute("data-rc-name");
        if (!name) return;
        var qn = "USER:" + name;
        var kind = el.getAttribute("data-rc-kind") || "string";
        var val = controlValue(el);
        try {
            if (kind === "color") {
                var argb = parseRcColor(val);
                if (argb !== null && ctx.setNamedColorOverride)
                    ctx.setNamedColorOverride(qn, argb);
            } else if (kind === "float" || kind === "dp") {
                var f = parseFloat(val);
                if (!isNaN(f) && ctx.setNamedFloatOverride)
                    ctx.setNamedFloatOverride(qn, f);
            } else if (kind === "int" || kind === "integer") {
                var n = parseInt(val, 10);
                if (!isNaN(n) && ctx.setNamedIntegerOverride)
                    ctx.setNamedIntegerOverride(qn, n);
            } else if (kind === "bool" || kind === "boolean") {
                // The player's setNamedBooleanOverride only records the value — it doesn't touch the
                // render state — so route booleans through the integer setter as 1/0, matching the
                // daemon's BooleanValue → user-local-integer mapping.
                if (ctx.setNamedIntegerOverride) {
                    ctx.setNamedIntegerOverride(qn, val === "true" ? 1 : 0);
                }
            } else if (ctx.setNamedStringOverride) {
                ctx.setNamedStringOverride(qn, val);
            }
        } catch (e) {
            /* a knob the document doesn't declare is a harmless no-op */
        }
    });
    if (rcPlayer && rcPlayer.repaint) rcPlayer.repaint();
}
function openRc() {
    if (!rcAvailable()) return;
    root.setAttribute("data-mode", "rc");
    canvas.hidden = true;
    rcReady = false;
    status.textContent = "loading RC player…";
    ensureRcScript(function (ok: boolean) {
        if (!ok || !window.RC) {
            showModeError("The Remote Compose player failed to load.");
            return;
        }
        if (!rcActive()) return; // toggled away while the script loaded
        // The page registers the vendored faces the player's generic-family stacks name
        // (`/rc-fonts/fonts.css`), but `@font-face` is lazy and canvas neither triggers a load nor
        // repaints when one finishes — so an unawaited first paint draws this document in the
        // *viewer's* own `sans-serif`, at different metrics, with no Medium weight. Load the faces
        // alongside the fetch: they're jar-local and cached, so this costs a frame at most.
        var rcFonts = window.cpRcFonts
            ? window.cpRcFonts.ready()
            : Promise.resolve();
        Promise.all([
            rcFonts,
            fetch(rcDocUrl()).then(function (r) {
                if (!r.ok) throw new Error("doc " + r.status);
                return r.arrayBuffer();
            }),
        ])
            .then(function (settled) {
                var buf = settled[1];
                if (!rcActive()) return null;
                // Size the canvas to the preview's real pixel dimensions BEFORE loading: the player
                // derives the document viewport from the canvas's current size at load time, and a
                // resize afterwards can't recover it. The baked snapshot <img> carries those
                // dimensions (rendered at the same density), so a non-default-shaped preview fills
                // the canvas instead of being letterboxed into the 300×150 default.
                var w = img.naturalWidth || 0,
                    h = img.naturalHeight || 0;
                if (w > 0 && h > 0) {
                    rcCanvasEl!.width = w;
                    rcCanvasEl!.height = h;
                }
                if (!rcPlayer) rcPlayer = new window.RC!.RcdPlayer(rcCanvasEl!);
                return rcPlayer.loadFromArrayBuffer(buf);
            })
            .then(function () {
                if (!rcActive()) return;
                rcCtx = rcPlayer!.getRemoteContext
                    ? rcPlayer!.getRemoteContext!()
                    : null;
                applyRcOverrides();
                if (rcPlayer!.repaint) rcPlayer!.repaint!();
                revealRc();
            })
            .catch(function () {
                showModeError("Rendering the Remote Compose document failed.");
            });
    });
}
// Swap the stage from the snapshot to the painted canvas. The snapshot is removed from flow
// (display:none) so the stage takes the document's own size rather than stacking both.
function revealRc() {
    if (!rcActive() || rcReady) return;
    rcReady = true;
    clearModeError();
    rcCanvasEl!.hidden = false;
    img.style.display = "none";
    status.textContent = "";
}
function closeRc() {
    if (!rcCanvasEl) return;
    root.setAttribute("data-mode", "snapshot");
    rcReady = false;
    rcCanvasEl.hidden = true;
    img.style.removeProperty("display");
    img.hidden = false;
}

// AndroidX-conformant Compose Multiplatform/Wasm RC lane. This is an isolated app rather than
// another implementation hidden behind the legacy canvas API: it receives the document URL and
// explicitly announces its first rendered frame.
function positionRcWasmFrame() {
    if (rcWasmFrame) positionOverlay(rcWasmFrame);
}
function rcWasmNamedValues() {
    var values: Array<{ name: string; kind: string; value: string }> = [];
    controls(".cp-rc-knob").forEach(function (el) {
        var name = el.getAttribute("data-rc-name");
        if (!name) return;
        values.push({
            name: name,
            kind: el.getAttribute("data-rc-kind") || "string",
            value: controlValue(el),
        });
    });
    return values;
}
function rcWasmSrc() {
    var absoluteDoc = new URL(rcDocUrl(), location.origin).href;
    var src =
        "/rc-player-wasm/index.html?src=" + encodeURIComponent(absoluteDoc);
    var uiMode = may<HTMLSelectElement>("cp-uiMode");
    if (uiMode && (uiMode.value === "light" || uiMode.value === "dark")) {
        src += "&theme=" + encodeURIComponent(uiMode.value);
    }
    var namedValues = rcWasmNamedValues();
    if (namedValues.length) {
        src +=
            "&namedValues=" + encodeURIComponent(JSON.stringify(namedValues));
    }
    return src;
}
function revealRcWasm() {
    if (!rcWasmActive() || rcWasmReady) return;
    rcWasmReady = true;
    if (rcWasmBootTimer) {
        clearTimeout(rcWasmBootTimer);
        rcWasmBootTimer = null;
    }
    clearModeError();
    positionRcWasmFrame();
    rcWasmFrame!.classList.add("cp-wasm-live");
    img.style.visibility = "hidden";
    status.textContent = "";
}
function openRcWasm() {
    if (!rcWasmFrame) return;
    root.setAttribute("data-mode", "rc-wasm");
    canvas.hidden = true;
    positionRcWasmFrame();
    rcWasmFrame.hidden = false;
    rcWasmReady = false;
    rcWasmFrame.src = rcWasmSrc();
    status.textContent = "loading CMP Wasm RC player…";
    if (rcWasmBootTimer) clearTimeout(rcWasmBootTimer);
    rcWasmBootTimer = setTimeout(function () {
        if (!rcWasmReady && rcWasmActive())
            showModeError("CMP Wasm RC player didn't start.");
    }, 20000);
}
function closeRcWasm() {
    if (!rcWasmFrame) return;
    rcWasmReady = false;
    if (rcWasmBootTimer) {
        clearTimeout(rcWasmBootTimer);
        rcWasmBootTimer = null;
    }
    rcWasmFrame.classList.remove("cp-wasm-live");
    rcWasmFrame.hidden = true;
    rcWasmFrame.removeAttribute("src");
    img.style.removeProperty("visibility");
}
if (rcWasmFrame) {
    window.addEventListener("message", function (e) {
        if (
            e.source !== rcWasmFrame.contentWindow ||
            e.origin !== location.origin
        )
            return;
        if (e.data === "cp-rc-wasm-ready") revealRcWasm();
        else if (
            typeof e.data === "string" &&
            e.data.indexOf("cp-rc-wasm-error:") === 0
        ) {
            showModeError(
                "Rendering the Remote Compose document failed in CMP Wasm.",
            );
        } else if (
            e.data &&
            (e.data.type === "cp-rc-host-action" ||
                e.data.type === "cp-rc-host-named-action")
        ) {
            // The viewer never executes an action payload. It exposes the validated event to an
            // embedding host and leaves policy/navigation to that host.
            window.dispatchEvent(
                new CustomEvent(e.data.type, { detail: e.data }),
            );
            status.textContent =
                e.data.type === "cp-rc-host-action"
                    ? "Remote Compose host action " + String(e.data.actionId)
                    : "Remote Compose named action “" +
                      String(e.data.name || "") +
                      "”";
        }
    });
}

function onControlsChanged() {
    // Keep the copyable direct links current no matter which transport handles the change.
    refreshLinks();
    if (rcWasmActive()) {
        // Remote Compose currently consumes only Day/Night at this boundary. The control is the
        // only wasm-honoured field enabled in this lane, so reload the isolated player with the
        // new theme query while retaining the same tokened document URL.
        openRcWasm();
        return;
    }
    if (wasmActive()) {
        // Recompose in place once the app is up; before it's ready, re-point the initial src (the
        // fragment carries the overrides) — the load handler re-syncs the final state either way.
        if (wasmReady && wasmFrame!.contentWindow) {
            wasmFrame!.contentWindow.postMessage(wasmOverridePatch(), "*");
        } else {
            wasmFrame!.src = wasmInitialSrc();
        }
        return;
    }
    if (live.checked && ws && ws.readyState === 1) {
        ws.send(
            JSON.stringify({
                type: "setOverrides",
                overrides: liveOverrides(),
            }),
        );
        return;
    }
    // Not in an interactive lane. Whenever the server can produce a fresh overridden render — a
    // live daemon session (!staticSnapshot) OR a published catalog whose carried daemon
    // re-renders on demand (canRenderOverrides) — just re-point /render. This is what lets Size,
    // Locale, Device, … take effect for a CMP catalog while still showing static snapshots, so
    // the controls aren't dead until a live stream is opened.
    if (!staticSnapshot || canRenderOverrides) {
        refreshSnapshot();
        return;
    }
    // Pure static published catalog whose only interactive lane is the in-browser app: the
    // wasm-honoured controls (day/night, font scale, locale) can only apply in the browser, so
    // auto-enable the Wasm tier and let it apply the change, instead of a dead /render the
    // catalog can't serve. (Size/Device stay disabled here — the Wasm app can't honour them.)
    if (wasmToggle) {
        setMode("wasm");
        return;
    }
    refreshSnapshot();
}

// The single Static⇄Live toggle drives these transports. "live" opens the daemon stream,
// "wasm" mounts the in-browser app, "png" (the default) is the static snapshot. closeStream /
// closeWasm are idempotent, so a switch safely tears down both regardless of the prior state.
// The static lane additionally honours the SVG format toggle: the same <img>, pointed at the
// vector `/render/<id>.svg` instead of the raster `.png`. A live lane (stream / wasm) is raster
// frames, so entering it clears SVG.
const svgToggle = may<HTMLButtonElement>("cp-svg-toggle");
function svgOn() {
    return !!(svgToggle && svgToggle.getAttribute("aria-pressed") === "true");
}
// Leaving the static lane drops BOTH vector affordances together: a live stream / Wasm app /
// Remote Compose canvas produces raster frames, and an exploded view of a frame that no longer
// exists is a pressed chip describing nothing. Kept as one call so a future lane can't clear the
// SVG chip and forget this one.
function dropVectorModes() {
    if (svgToggle) svgToggle.setAttribute("aria-pressed", "false");
    clearExploded();
}
// Un-press the 3D chip and re-sync its controls. Shared by every path that leaves the vector
// lane — the interactive lanes above, and the SVG chip being switched off — so a future lane
// can't drop one and forget the other.
function clearExploded() {
    if (!explodeToggle || !explodeOn()) return;
    explodeToggle.setAttribute("aria-pressed", "false");
    if (root) root.setAttribute("data-exploded", "0");
    syncExplodeControls();
    explodeEnabledSvg = false;
}
// The stage lane the current transition is leaving, latched by enterMode before it tears that
// lane down. Read by specActualUrl, which cannot otherwise tell whether the snapshot <img> holds
// the frame the visitor was looking at. Starts on the snapshot, which is what the page opens on.
var outgoingStage = "snapshot";
function enterMode(m: string) {
    // A lane switch is a discrete choice, so the URL sync it ends up triggering pushes a history
    // entry rather than replacing one — Back returns to the lane the visitor came from. Set here
    // rather than on each control because every transition (radio, Live/Wasm/RC toggle, or an
    // auto-enable) passes through this function.
    urlPush = true;
    // The lane being LEFT, read before any close() below tears it down. The spec lane's comparison
    // needs it: whether the snapshot <img> holds the frame the visitor was actually looking at
    // depends entirely on which lane they are arriving from (see specActualUrl).
    outgoingStage = root.getAttribute("data-mode") || "snapshot";
    // A mode switch always clears a prior lane's error; the new lane re-raises its own if it fails.
    clearModeError();
    // The spec lane is closed by EVERY other transition (it has no per-branch close call below),
    // so leaving it always restores the snapshot <img> to the stage.
    if (m !== "spec") closeSpec();
    // Closed by EVERY other transition, exactly as the spec lane is: leaving Source always puts the
    // render back on the stage, whichever control the visitor left by.
    if (m !== "source") closeSource();
    // Closed by EVERY other transition, exactly as those two are — and here that is also what stops
    // the capture playing on behind a lane the visitor has already moved to.
    if (m !== "motion") closeMotion();
    if (m === "live") {
        cancelSnapshotLoading();
        snapshotExt = ".png";
        dropVectorModes();
        closeWasm();
        closeRc();
        closeRcWasm();
        openStream();
    } else if (m === "wasm") {
        cancelSnapshotLoading();
        snapshotExt = ".png";
        dropVectorModes();
        closeStream();
        closeRc();
        closeRcWasm();
        openWasm();
    } else if (m === "rc") {
        cancelSnapshotLoading();
        snapshotExt = ".png";
        dropVectorModes();
        closeStream();
        closeWasm();
        closeRcWasm();
        openRc();
    } else if (m === "rc-wasm") {
        cancelSnapshotLoading();
        snapshotExt = ".png";
        dropVectorModes();
        closeStream();
        closeWasm();
        closeRc();
        openRcWasm();
    } else if (m === "source") {
        // Reading the code is not a render either: same treatment as the spec lane — cancel any
        // in-flight snapshot, leave every interactive lane, and put the panel on the stage. Going
        // through the branch rather than returning early keeps the transition in the one path that
        // reconciles the picker, the chips and the URL, so `?mode=source` is bookmarkable and Back
        // returns to the lane the visitor came from.
        cancelSnapshotLoading();
        snapshotExt = ".png";
        dropVectorModes();
        closeStream();
        closeWasm();
        closeRc();
        closeRcWasm();
        openSource();
    } else if (m === "motion") {
        // Playing the capture is not a render either: cancel any in-flight snapshot, drop every
        // interactive lane, and put the recording on the stage. Going through the branch rather than
        // returning early keeps the transition in the one path that reconciles the picker, the chips
        // and the URL, so `?mode=motion` is bookmarkable and Back returns to the lane behind it.
        cancelSnapshotLoading();
        snapshotExt = ".png";
        dropVectorModes();
        closeStream();
        closeWasm();
        closeRc();
        closeRcWasm();
        openMotion();
    } else if (m === "spec") {
        // Looking at the spec is not a render: cancel any in-flight snapshot, drop every interactive
        // lane, and paint the imported reference instead. Nothing is re-requested on the way in.
        cancelSnapshotLoading();
        snapshotExt = ".png";
        dropVectorModes();
        closeStream();
        closeWasm();
        closeRc();
        closeRcWasm();
        openSpec();
    } else {
        // Browser RC lanes deliberately clear the server-side pick while they paint. Returning to
        // the static lane must restore a non-Java default before query() renders it; otherwise the
        // chip says CMP Android/JVM while the absent rcPlayer parameter silently selects Java.
        var restoredPlayer = rules.restoreStaticPlayer({
            defaultBackend: rcDefaultBackend,
            pickedBackend: rcPlayerBackend,
            picked: !!rcPlayerPicked,
        });
        rcPlayerBackend = restoredPlayer.pickedBackend;
        rcPlayerPicked = restoredPlayer.picked;
        closeStream();
        closeWasm();
        closeRc();
        closeRcWasm();
        // Static snapshot lane: raster PNG, or the vector SVG when the format toggle is on.
        snapshotExt = svgOn() ? ".svg" : ".png";
        if (svgOn()) root.setAttribute("data-mode", "svg");
    }
    syncOverlayToggles();
    syncServerControls();
    // Every lane transition passes through here, so the picker is re-reconciled whether the
    // viewer entered/left a lane via the combo or via the Live / SVG / snapshot controls. It runs
    // BEFORE updateLiveToggle(), which reads the combo's value for the chip's label.
    syncLaneSelect();
    updateLiveToggle();
    // Render the static lane AFTER syncServerControls() has reconciled the daemon-only controls
    // for the new lane. Returning from Wasm this re-enables the `.cp-rc-knob` inputs first, so
    // query() includes an rc.* value edited before the Wasm detour in the first snapshot render
    // (and its direct links) instead of skipping the still-disabled control. The live/wasm lanes
    // drive their own render (openStream / openWasm), so only the static lane renders here.
    if (
        m !== "live" &&
        m !== "wasm" &&
        m !== "rc" &&
        m !== "rc-wasm" &&
        m !== "spec" &&
        m !== "source" &&
        m !== "motion"
    )
        refreshSnapshot();
    // The interactive lanes drive their own render and never reach refreshLinks, so the URL would
    // still describe the snapshot the visitor just left — the chosen lane unbookmarkable until
    // some unrelated control moved, and the pending push landing on that edit instead. Sync here
    // so every transition writes `?mode=` at the moment it happens. (The snapshot branch already
    // synced via refreshSnapshot; this second call is a no-op replace with identical values.)
    else {
        syncUrl();
        syncSpecBaseline();
    }
}
// SVG format toggle: swap the static snapshot between raster and vector. Pressing it while a
// live lane is active drops back to the static vector render; pressing it in the static lane
// swaps the extension in place.
if (svgToggle) {
    svgToggle.addEventListener("click", function () {
        var turnOn = !svgOn();
        svgToggle.setAttribute("aria-pressed", turnOn ? "true" : "false");
        // Every non-static lane has to be LEFT before the vector snapshot can own the stage —
        // otherwise the badge flips to SVG and a hidden snapshot reloads underneath a canvas /
        // iframe / spec image that is still on screen, with its chip still pressed. The daemon and
        // Wasm lanes were already routed this way; the RC canvas, the RC/Wasm frame and the spec
        // lane are the same case.
        // The exploded view is a view OF the vector export, so leaving the vector lane leaves it
        // too. Without this the 3D chip stayed pressed over a flat PNG, its sliders stayed live, and
        // the copied/downloaded SVG stayed exploded while the stage showed something else — three
        // controls describing a frame that is no longer on screen.
        if (!turnOn) clearExploded();
        // `sourceActive()` belongs in this list for exactly the reason the others do: openSource()
        // takes the snapshot <img> out of flow, and only closeSource() puts it back. Flipping
        // `data-mode` straight to "svg" hid the panel (its CSS is mode-scoped) without restoring the
        // image — a blank stage under a still-checked Source radio.
        if (
            turnOn &&
            (live.checked ||
                wasmActive() ||
                rcActive() ||
                rcWasmActive() ||
                specActive() ||
                sourceActive() ||
                motionActive())
        ) {
            setMode("png"); // enterMode("png") reads svgOn() → renders the .svg
        } else {
            snapshotExt = turnOn ? ".svg" : ".png";
            root.setAttribute("data-mode", turnOn ? "svg" : "snapshot");
            refreshSnapshot();
        }
    });
}
// The exploded 3D toggle. It is a *view of the vector export*, so pressing it implies the SVG
// lane: from anywhere else the viewer switches to the static vector snapshot on the way in,
// exactly as the SVG toggle does, rather than presenting a control that quietly does nothing on
// a raster frame.
if (explodeToggle) {
    explodeToggle.addEventListener("click", function () {
        var turnOn = !explodeOn();
        explodeToggle.setAttribute("aria-pressed", turnOn ? "true" : "false");
        if (root) root.setAttribute("data-exploded", turnOn ? "1" : "0");
        syncExplodeControls();
        urlPush = true;
        if (turnOn) dropRcPlayerPick();
        if (turnOn && svgToggle && !svgOn()) {
            // Turning SVG on is itself a lane change; let its handler drive the render so there is
            // exactly one request, with `exploded=1` already folded in by withSnapshotFormat.
            // Remembered so leaving 3D can hand the lane back (see explodeEnabledSvg).
            explodeEnabledSvg = true;
            svgToggle.click();
            return;
        }
        if (!turnOn) explodeEnabledSvg = false;
        refreshSnapshot();
    });
}
// The angle / separation / depth knobs. Continuous drags leave `urlPush` false, so tuning the
// view replaces one history entry instead of burying the page under fifty.
EXPLODE_KNOBS.forEach(function (pair) {
    var el = may<HTMLInputElement>(pair[0]);
    if (!el) return;
    el.addEventListener("input", function () {
        updateExplodeReadout(el!);
        if (explodeOn()) scheduleExplodeRender();
    });
});
// The knobs only mean anything while the view is on, and a slider that renders nothing reads as
// broken; grey them out instead.
function syncExplodeControls() {
    var on = explodeOn();
    EXPLODE_KNOBS.forEach(function (pair) {
        var el = may<HTMLInputElement>(pair[0]);
        if (el) el.disabled = !on;
    });
}
function updateExplodeReadout(el: HTMLInputElement) {
    var out = may<HTMLElement>(el.id + "-value");
    if (out)
        out.textContent = el.value + (el.getAttribute("data-cp-unit") || "");
}
// Dragging a slider fires `input` per pixel of travel. Each one is a fetch of a re-projected
// SVG — cheap on the server (no re-render, just a rewrite of cached bytes) but not free on the
// wire, so coalesce a drag into one request per frame-ish.
var explodeTimer: ReturnType<typeof setTimeout> | null = null;
function scheduleExplodeRender() {
    if (explodeTimer) clearTimeout(explodeTimer);
    explodeTimer = setTimeout(function () {
        explodeTimer = null;
        refreshSnapshot();
    }, 120);
}
// "Full page (scroll)" re-renders the active snapshot format and reshapes both export URLs.
if (scrollLong) {
    scrollLong.addEventListener("change", function () {
        refreshSnapshot();
    });
}
// The overlay toggles (touchOverlay) are rendered by the daemon, so they're enabled
// whenever the daemon lane is REACHABLE — not only while it's the active mode. Ticking one from
// the static snapshot switches into Live Compose (see onOverlayChanged), which is what the
// visitor meant; greying them out until "Live preview" was clicked made the group look broken.
// They stay disabled only when the lane genuinely can't be entered (the transport radio is
// disabled — e.g. the stream is behind sign-in). Called on every mode transition.
const overlayToggles =
    document.querySelectorAll<HTMLInputElement>(".cp-overlay");
function syncOverlayToggles() {
    var on = !!(live && !live.disabled);
    Array.prototype.forEach.call(overlayToggles, function (el) {
        el.disabled = !on;
    });
}
// Enable/disable the display controls to match what the active session can actually render.
// A server-render control (Size / Device / Orientation) takes effect whenever the
// server can produce a fresh overridden render: a live daemon session (!staticSnapshot), a
// catalog whose carried daemon re-renders on demand (canRenderOverrides), or an active live
// stream. The wasm-honoured trio (Day/Night / Locale / Font scale) additionally applies in the
// in-browser app, so it's also enabled whenever a Wasm app backs the session. This is what
// makes "most override modes" live for a CMP catalog (compose-m3) instead of greyed out until
// a stream is opened; the server-rendered markup already reflects this, and this keeps it in
// sync across mode transitions.
var serverOnlyControlIds = [
    "device",
    "orientation",
    "sizeMode",
    "fixedW",
    "fixedH",
    "minW",
    "minH",
    "maxW",
    "maxH",
];
var wasmHonouredControlIds = ["localeTag", "fontScale"];
var alwaysDark = root.getAttribute("data-always-dark") === "1";
// This preview is redrawn by replaying a captured Remote Compose document, never by re-running
// the composable that authored it. A control whose only route to the pixels is a fresh
// composition is therefore dead: the server refuses it with a 409 rather than answering with
// unchanged pixels, so leaving it live invites the visitor to drag a slider into an error.
//
// NOT everything is dead — this is the narrow set, matching the server's
// `CatalogLiveRouting.irReplayDroppedOverrideNames`:
//  * Day/Night and Font scale STAY LIVE. A document can defer both to the host and resolve them
//    at paint time (the player's default text size scales by `Configuration.fontScale`, and it
//    derives its paint theme from `Configuration.isNightModeActive()`), so a document that reads
//    them genuinely responds. One that baked absolute sizes and colours silently won't — that is
//    authored behaviour, and not ours to grey out.
//  * Remote Compose knobs stay live except the string-valued ones, and only in the server lane —
//    see the `.cp-rc-knob` pass below.
var irReplay = root.getAttribute("data-ir-replay") === "1";
// A replayed preview whose session publishes its declared themes as named colour values. The
// server rewrites `?themeProvider=` into those seeds, so the provider options work here even
// though nothing else `irReplay` disables does — those still need a composition.
var replayThemes = root.getAttribute("data-replay-themes") === "1";
// Force [el] off for the IR-replay reason, tagging it so the visitor gets the "why" on hover
// rather than a control that is merely dead. Only ever *adds* the disable — every call site
// assigns `el.disabled` from its own lane logic immediately before, so the not-dead branch just
// restores the authored title and leaves that decision alone.
function gateForIrReplay(el: Control | null, dead: boolean, why: string) {
    if (!el) return;
    if (dead) {
        if (!el.hasAttribute("data-ir-title"))
            el.setAttribute("data-ir-title", el.title || "");
        el.title = why;
        el.disabled = true;
    } else if (el.hasAttribute("data-ir-title")) {
        el.title = el.getAttribute("data-ir-title") || "";
        el.removeAttribute("data-ir-title");
    }
}
var IR_WHY_RECOMPOSE =
    "Not available on this preview — it is replayed from a captured document, " +
    "which cannot be recomposed.";
var IR_WHY_RC_STRING =
    "Not applied on the server lane — the Remote Compose player does not honour string " +
    "overrides on a replayed document. Switch to the JS player to edit it.";
function syncServerControls() {
    // The in-browser Wasm lane only honours the wasm-honoured trio (uiMode/locale/fontScale) +
    // knobs (see wasmOverridePatch); size/device/orientation and the app-theme
    // selector re-point /render, which the iframe ignores. So while Wasm is the active lane they
    // are dead — disable them (even on a catalog that can otherwise re-render) and restore them
    // when the lane leaves Wasm. Called on every mode transition, so the states track the lane.
    var onWasm = wasmActive();
    // The RC canvas lane, like Wasm, honours only its own overrides (the Remote Compose knobs,
    // applied client-side): size/device/locale/theme all re-point /render, which the painted
    // canvas ignores. So it's as "dead" for the server + wasm-honoured controls as the Wasm lane.
    var onRcCanvas = rcActive();
    var onRcWasm = rcWasmActive();
    var onRc = onRcCanvas || onRcWasm;
    // The two lanes that put a FIXED frame on the stage — an imported raster, or a finished
    // recording. Neither is re-pointed by an override, so every control that would re-render is as
    // dead here as it is in the Wasm / RC canvas lanes: left enabled, editing one re-renders the
    // HIDDEN snapshot underneath, or flips a static catalog into Wasm, while what is on screen goes
    // on saying something else.
    //
    // ONE predicate, consumed by every override family below. It was briefly `onSpec` plus a
    // motion-only addition on two of them, which is the worst of both: the theme select and the
    // Remote Compose knobs stayed live over a recording while the code read as though the lane were
    // gated. If a family is dead on a fixed frame it is dead on both lanes, and it says so here.
    //
    // Shared with `activeThemeChoice`, which needs the same answer to decide that a disabled theme
    // select still names the theme the frozen frame was rendered with. Both read `data-mode`, so
    // "which lane froze the controls" has a single definition — the radios and the attribute are
    // set by the same transition, and two copies of this could disagree during one.
    var onFixedFrame = onFixedFrameLane();
    var canServerRender =
        !onWasm &&
        !onRc &&
        !onFixedFrame &&
        (!staticSnapshot || canRenderOverrides || !!(live && live.checked));
    serverOnlyControlIds.forEach(function (id) {
        var el = may<Control>("cp-" + id);
        if (el) el.disabled = !canServerRender;
    });
    // The wasm-honoured trio stays live in the in-browser Wasm lane (the app applies it), so it's
    // enabled whenever the server can render OR a Wasm app backs the session — but not in the RC
    // canvas lane, which doesn't map them onto the document.
    wasmHonouredControlIds.forEach(function (id) {
        var el = may<Control>("cp-" + id);
        if (el)
            el.disabled =
                (id === "uiMode" && alwaysDark) ||
                !(
                    canServerRender ||
                    (wasmSrc && !onRc && !onFixedFrame) ||
                    (id === "uiMode" && onRcWasm)
                );
        // Locale is the one member of this trio a replayed document cannot express: `stringResource`
        // resolved to a literal at capture, and `RemoteContext` carries no locale among its system
        // variables, so there is nothing for the host to supply. Dead in every lane that replays the
        // document; still live in a genuine CMP/Wasm app lane, which runs a real composition.
        // Font scale deliberately stays untouched — see the `irReplay` note above.
        if (id === "localeTag")
            gateForIrReplay(el, irReplay && !onWasm, IR_WHY_RECOMPOSE);
    });
    // Day/Night options work in Wasm; declared provider options need the daemon. Keep the unified
    // select usable whenever at least one kind can work, and gate its individual option families.
    if (themeChoice) {
        var hasDeclaredThemes =
            themeChoice.getAttribute("data-has-declared-themes") === "true";
        // This preview's SUBJECT is a theme (@FixedTheme, or a Themes-section specimen). Both axes
        // are off: `theme:<provider>` redraws it under another theme, and Day/Night is a `uiMode`
        // override that re-renders it in the opposite mode rather than navigating to the baked
        // sibling. Recomputed here rather than left to the server's `disabled` attribute, because
        // this block reassigns `themeChoice.disabled` outright and would otherwise re-enable it.
        var fixedTheme =
            themeChoice.getAttribute("data-fixed-theme") === "true";
        // `!irReplay`: a declared provider theme substitutes a wrapper composable around the preview,
        // which needs a composition to wrap. Day/Night is NOT gated the same way — the player can
        // derive its paint theme from the host at draw time, so that axis stays offered.
        var canProviderTheme =
            !fixedTheme &&
            hasDeclaredThemes &&
            !onWasm &&
            !onRc &&
            !onFixedFrame &&
            (!irReplay || replayThemes) &&
            (!staticSnapshot || canRenderOverrides);
        // Wear has no day/night axis, but Night (Default) must remain selectable when provider
        // themes are offered so the visitor can clear a chosen provider and return to the app.
        var canDefaultTheme =
            !fixedTheme &&
            !onRc &&
            !onFixedFrame &&
            ((!alwaysDark && (canServerRender || !!wasmSrc)) ||
                (alwaysDark && canProviderTheme));
        Array.prototype.forEach.call(themeChoice.options, function (option) {
            option.disabled =
                option.value.indexOf("theme:") === 0
                    ? !canProviderTheme
                    : !canDefaultTheme;
        });
        themeChoice.disabled = !canDefaultTheme && !canProviderTheme;
    }
    // The bar is the select's visible face, so it is reconciled from the same pass that just
    // decided what the select and each of its options may offer in this lane.
    syncThemeBar();
    // Remote Compose knobs are LIVE in the RC canvas lane — an edit applies client-side via
    // setNamed*Override + repaint (onRcKnobChanged), no daemon needed — so enable them whenever
    // that lane is active. The CMP/Wasm lane applies the same typed values while reloading its
    // isolated document; outside either browser RC lane they're gated on server rendering.
    controls(".cp-rc-knob").forEach(function (el) {
        var onBrowserRc = rcActive() || rcWasmActive();
        el.disabled = onBrowserRc
            ? false
            : onWasm ||
              onFixedFrame ||
              !(!staticSnapshot || canRenderOverrides);
        // A *string* seed doesn't land on the server lane: the Android player's
        // `setUserLocalString` reaches `RemoteComposeState.overrideData` and stops there, so the
        // render comes back unchanged (the server reports it dropped). The browser RC lanes drive a
        // different player and apply it client-side, so the control stays live there — which is why
        // this gates on the lane rather than on the preview.
        if ((el.getAttribute("data-rc-kind") || "string") === "string") {
            gateForIrReplay(el, irReplay && !onBrowserRc, IR_WHY_RC_STRING);
        }
    });
    // Author-declared knobs are seeded into a composition by the daemon's named-override planner.
    // No composition, nothing to seed — dead in every lane but a real CMP/Wasm app, which mounts
    // the live component and honours them.
    //
    // Unlike every other control here these have no lane-derived enabled state to fall back on:
    // nothing re-synced them across transitions before this, so their base is whatever the server
    // rendered (`disabled` when neither a re-render nor a Wasm app can honour an edit). Record that
    // once and restore it on each pass — `gateForIrReplay` only ever *adds* the disable, so without
    // a base assignment a knob switched off for the snapshot lane would stay off after entering
    // Wasm, and `wasmOverridePatch()` skips disabled knobs, so the edit would never reach the app.
    controls(".cp-knob").forEach(function (el) {
        if (!el.hasAttribute("data-base-disabled")) {
            el.setAttribute("data-base-disabled", el.disabled ? "1" : "0");
        }
        el.disabled =
            el.getAttribute("data-base-disabled") === "1" || onFixedFrame;
        gateForIrReplay(el, irReplay && !onWasm, IR_WHY_RECOMPOSE);
    });
}
// Programmatic switch (the live toggle, or a wasm-only control auto-enabling Wasm): tick the
// hidden mode radio so its state is consistent, then run the transition.
function setMode(m: string) {
    var radioId =
        m === "live"
            ? "cp-live"
            : m === "wasm"
              ? "cp-wasm-toggle"
              : m === "rc-wasm"
                ? "cp-rc-wasm-toggle"
                : m === "spec"
                  ? "cp-spec-toggle"
                  : m === "source"
                    ? "cp-source-toggle"
                    : m === "motion"
                      ? "cp-motion-toggle"
                      : m === "rc"
                        ? "cp-rc-toggle"
                        : "cp-mode-png";
    var r = radioId ? may<HTMLInputElement>(radioId) : null;
    if (r) r.checked = true;
    enterMode(m);
}
Array.prototype.forEach.call(
    ticks('input[name="cp-mode"]'),
    function (r: HTMLInputElement) {
        r.addEventListener("change", function () {
            if (r.checked) enterMode(r.value);
        });
    },
);
// The primary chip. It does two jobs at once, which is what lets one control replace the row of
// per-lane chips this page used to carry: it NAMES the renderer currently on the stage ("Java",
// "JS", "Figma spec", "Live") and its status dot says whether that render is interactive, and
// clicking it TOGGLES interactivity — into the best live lane this session offers (the daemon
// stream when present, else the in-browser Wasm app), and back out to the static snapshot.
// Overrides still take effect while static (a catalog re-renders /render on demand), so the
// toggle is specifically about *interacting* with the running composition — clicking, scrolling,
// typing. The corner backend badge flips its icon/accent to match (see backendBadgeScript).
const liveToggle = may<HTMLButtonElement>("cp-live-toggle");
const liveToggleLabel = may<HTMLElement>("cp-live-toggle-label");
// Present only when GitHub auth is the one thing blocking the daemon lane (see ServeWeb's
// liveSignInLink). Deliberately not `#cp-live-toggle` — it's a link, so the toggle's
// `.disabled` / `aria-pressed` handling must not touch it — which is why it's looked up
// separately here rather than inferred from `liveToggle`.
const liveSignIn = may<HTMLAnchorElement>("cp-live-signin");
const modeHint = may<HTMLElement>("cp-mode-hint");
// The lane decisions live in `cli/serve-web/src/viewer/laneState.ts`.
function liveOffer() {
    return { daemon: !!(live && !live.disabled), wasm: !!wasmToggle };
}
function liveTransportAvailable() {
    return rules.liveTransportAvailable(liveOffer());
}
function bestLiveMode() {
    return rules.bestLiveMode(liveOffer());
}
function anyLiveActive() {
    return !!(live && live.checked) || !!(wasmToggle && wasmToggle.checked);
}
// Every lane that paints a *running* composition rather than a finished image: the daemon
// stream, the in-browser Wasm app, and both Remote Compose player lanes (which replay the
// document client-side). This is what the status dot reports, so picking "JS" from the combo
// lights the same indicator that clicking into Live does — they are the same claim.
function laneFlags() {
    return {
        rcWasm: rcWasmActive(),
        rc: rcActive(),
        wasm: wasmActive(),
        spec: specActive(),
        live: !!(live && live.checked),
    };
}
function anyInteractive() {
    return rules.anyInteractive(laneFlags());
}
// The lane the picker is (or would be) sitting on, in the combo's own value space. A daemon
// stream is not one of the offered renderers — it is the live form of whichever one is picked —
// so it deliberately falls through to the static player lane the toggle will return to.
function currentLaneValue() {
    return rules.currentLaneValue(laneFlags(), {
        defaultBackend: rcDefaultBackend || "",
        pickedBackend: rcPlayerBackend || "",
        picked: !!rcPlayerPicked,
    });
}
// What the chip calls the current lane. "Live" while the daemon stream is up (that lane IS the
// live form of whichever renderer is picked, and the picked one is a click away again); other-
// wise the matching option's own label, so the chip and the combo can never name a lane two
// different things. With no combo at all the chip is the only control on the row, and the
// generic invitation reads better than "Snapshot".
// The renderer the chip returns to when the current lane isn't one of the combo's own — today
// that means the design spec, which is a chip of its own. Server-rendered from the same
// `primaryLaneLabel` the chip opens on, so a preview with no combo to read has a name too.
function defaultLaneLabel() {
    return (
        (liveToggle && liveToggle.getAttribute("data-default-lane-label")) ||
        "Live preview"
    );
}
function laneLabelText() {
    var options: Map<string, string> | null = null;
    if (laneSelect) {
        options = new Map();
        for (const o of Array.from(laneSelect.options)) {
            options.set(o.value, o.textContent || "");
        }
    }
    return rules.laneLabelText({
        live: !!(live && live.checked),
        laneOptions: options,
        wanted: currentLaneValue(),
        defaultLabel: defaultLaneLabel(),
    });
}
function updateLiveToggle() {
    var interactive = anyInteractive();
    if (liveToggle) {
        liveToggle.setAttribute("aria-pressed", interactive ? "true" : "false");
        // Enabled when there is a live lane to enter — or when an interactive lane is already on the
        // stage, which is the only way back out of a Remote Compose player lane the combo entered.
        liveToggle.disabled = !liveTransportAvailable() && !interactive;
    }
    if (liveToggleLabel) liveToggleLabel.textContent = laneLabelText();
    // The spec chip is a toggle, so it reports the lane's state the same way the Live chip does.
    // Driven from here rather than from its own click handler so every route out of the lane (the
    // Live chip, a combo pick, an SVG swap, Back/Forward) un-presses it too.
    if (specChip) {
        var onSpecLane = specActive();
        var specState = rules.laneChip({
            onLane: onSpecLane,
            available: specAvailable(),
        });
        specChip.setAttribute(
            "aria-pressed",
            specState.pressed ? "true" : "false",
        );
        specChip.disabled = specState.disabled;
        specChip.title = onSpecLane
            ? "Showing the imported design spec — click to return to the render"
            : specChip.getAttribute("data-spec-chip-tip") || specChip.title;
    }
    // The Source chip reports its lane the same way, and from the same place, so every route out
    // of it (the Live chip, a combo pick, the spec chip, Back/Forward) un-presses it too.
    if (sourceChip) {
        var onSourceLane = sourceActive();
        sourceChip.setAttribute(
            "aria-pressed",
            onSourceLane ? "true" : "false",
        );
        sourceChip.disabled = !sourceAvailable() && !onSourceLane;
        sourceChip.title = onSourceLane
            ? "Showing the usage source \u2014 click to return to the render"
            : sourceChip.getAttribute("data-source-chip-tip") ||
              sourceChip.title;
    }
    // The Motion chip reports its lane the same way, and from the same place, so every route out
    // of it (the Live chip, a combo pick, the spec or Source chip, Back/Forward) un-presses it too.
    if (motionChip) {
        var onMotionLane = motionActive();
        motionChip.setAttribute(
            "aria-pressed",
            onMotionLane ? "true" : "false",
        );
        motionChip.disabled = !motionAvailable() && !onMotionLane;
        motionChip.title = onMotionLane
            ? "Playing the recorded interaction \u2014 click to return to the render"
            : motionChip.getAttribute("data-motion-chip-tip") ||
              motionChip.title;
    }
    // …and the tooltip, from the same state. The chip's meaning inverts as the visitor moves
    // through the lanes — on the static snapshot a click enters Live, on an interactive lane it
    // exits back to the snapshot — so a fixed `title` would end up describing the opposite of what
    // the control now does. The sign-in case never reaches here (that affordance is an <a> with no
    // `#cp-live-toggle` id, so `liveToggle` is null), which is why its wording isn't repeated.
    if (liveToggle) {
        liveToggle.title = interactive
            ? "Interactive — click to return to the static snapshot"
            : liveTransportAvailable()
              ? "Static snapshot — click for the live, interactive preview"
              : "Static snapshot — this session has no live lane to switch to";
    }
    if (modeHint) {
        modeHint.textContent = sourceActive()
            ? "usage source — not a render"
            : motionActive()
              ? "recorded interaction — not a live render"
              : specActive()
                ? "imported design spec — not a render"
                : interactive
                  ? "interactive — click / scroll the preview"
                  : // "no live lane" is only true when there is genuinely nothing to switch to. When the lane
                    // exists and is merely behind sign-in, the transport radio is (correctly) disabled, so
                    // liveTransportAvailable() is false and this used to read "no live lane" right beside a
                    // chip offering to sign in for one — telling the visitor in the same breath that the thing
                    // is available and that it doesn't exist. The sign-in link's presence is the signal.
                    liveTransportAvailable()
                    ? "static snapshot"
                    : liveSignIn
                      ? "static snapshot — sign in for live"
                      : "static snapshot (no live lane)";
    }
}
if (liveToggle) {
    liveToggle.addEventListener("click", function () {
        // Toggling off returns to the static snapshot, which for a Remote Compose preview means the
        // server-side player the combo will show — there is no static form of the JS canvas lane.
        if (anyInteractive()) {
            setMode("png");
        } else {
            var m = bestLiveMode();
            if (m) setMode(m);
        }
    });
}
// The design-spec chip: in and straight back out of the spec lane, no menu in between. Leaving
// returns to the static snapshot — the same place the Live chip returns to — rather than to
// whichever interactive lane was up before, because the spec is entered to compare against the
// *render*, and that is the lane the comparison views (Diff / Triptych / Slider) draw from.
if (specChip) {
    specChip.addEventListener("click", function () {
        if (specActive()) setMode("png");
        else if (specAvailable()) {
            // Straight into Diff, not onto the spec-on-the-stage view the lane used to open on. The
            // chip now STATES the divergence ("Figma 96.3%"), and a number like that raises exactly one
            // question — where? Opening on the spec alone answers a question nobody asked and leaves
            // the delta another click away. A visitor who wants the spec by itself has the view group
            // right there, and a URL that already names a view still wins (see cpSpecCompare.prefer).
            if (window.cpSpecCompare) window.cpSpecCompare.prefer("diff");
            setMode("spec");
        }
    });
}
// The Source chip: in and straight back out, like the spec chip. Leaving returns to the static
// snapshot rather than to whichever interactive lane was up, for the same reason — the code is
// read against the *render*, and that is the lane it was entered from.
if (sourceChip) {
    sourceChip.addEventListener("click", function () {
        if (sourceActive()) setMode("png");
        else if (sourceAvailable()) setMode("source");
    });
}
// The Motion chip: in and straight back out, like the spec and Source chips, and leaving returns
// to the static snapshot for the same reason — the recording is watched against the *still*, and
// that is the lane it was entered from.
if (motionChip) {
    motionChip.addEventListener("click", function () {
        if (motionActive()) setMode("png");
        else if (motionAvailable()) setMode("motion");
    });
}
// The per-capture picker, shown only when a preview published more than one. Switching while the
// lane is up swaps the capture in place rather than leaving and re-entering: the visitor is
// changing WHICH recording they are watching, not which lane they are in, so it is not a mode
// transition and does not belong in the history stack.
motionButtons.forEach(function (button) {
    button.addEventListener("click", function () {
        motionButtons.forEach(function (other) {
            other.setAttribute(
                "aria-pressed",
                other === button ? "true" : "false",
            );
        });
        if (motionActive()) {
            playMotion();
            // Replaces rather than pushes: switching recording inside the lane is not a lane change,
            // so it belongs in the address without burying the page the visitor arrived from under a
            // Back entry per click. `urlPush` is left false, which is exactly what replace means here.
            syncUrl();
        } else setMode("motion");
    });
});
// ---- The renderer combo box ------------------------------------------------------------------
// One `<select>` holding every lane this preview can be drawn by: the Remote Compose players
// (`rc:js` paints client-side via setMode("rc"), `rc:cmp-wasm` in its own frame, and
// `java` / `cmp-android` / `cmp-jvm` re-render the PNG server-side with rcPlayer=<wire>, see
// query()), the in-browser Wasm app, and the imported design spec. Its value tracks the active
// lane however the lane was entered — a pick, the Live toggle, an SVG swap, or Back/Forward.
if (laneSelect) {
    // Assign the hoisted stub with the real reconciler (see the declaration before query()).
    // The combo is a command menu ("switch renderer"), not a state field — the chip beside it holds
    // the state — so reconciling it means returning it to its placeholder. Doing that here rather
    // than in the change handler covers every route out of a lane (the Live toggle, an SVG swap,
    // Back/Forward), not just a pick.
    syncLaneSelect = function () {
        laneSelect.value = "";
    };
    function pickLane(value: string) {
        if (!value) return; // the placeholder; nothing was chosen
        if (value.indexOf("rc:") === 0) {
            var wire = value.substring(3);
            if (wire === "js") {
                // The client canvas lane. Leave the server pick untouched so returning to a server-side
                // player restores it. setMode("rc") (via enterMode) closes any Live/Wasm lane, opens the
                // canvas, and re-syncs the picker; if the canvas is already up there is nothing to do
                // but reconcile.
                rcPlayerPicked = false;
                if (!rcActive()) setMode("rc");
                else syncLaneSelect();
            } else if (wire === "cmp-wasm") {
                rcPlayerPicked = false;
                if (!rcWasmActive()) setMode("rc-wasm");
                else syncLaneSelect();
            } else {
                // A server-side player. Record the pick FIRST so the single static-lane render carries
                // rcPlayer=<wire>, then transition to the static snapshot exactly once for EVERY other
                // lane — js canvas, Live, or Wasm. setMode("png") → enterMode("png") closes them all and
                // renders the snapshot once (no racing double render). Without this a pick made while
                // Live/Wasm was active only reloaded the hidden <img> and left the interactive renderer
                // on screen under a combo naming something else.
                rcPlayerBackend = wire;
                rcPlayerPicked = true;
                setMode("png");
            }
        } else if (value === "wasm") {
            if (!wasmActive()) setMode("wasm");
            else syncLaneSelect();
        } else {
            setMode("png");
        }
    }
    laneSelect.addEventListener("change", function () {
        pickLane(laneSelect.value);
    });
    syncLaneSelect();
}
// Keep the live canvas overlay tracking the snapshot's slot when the page reflows (the Wasm
// overlay has its own resize hook below; this covers a live session with no Wasm app).
window.addEventListener("resize", function () {
    if (live && live.checked && !canvas.hidden) fitLiveCanvas();
    if (rcWasmActive()) positionRcWasmFrame();
});
// Re-pin the active overlay whenever the snapshot image itself loads — its first render, or a
// re-render at a new size. positionOverlay/fitLiveCanvas measure `img.getBoundingClientRect()`,
// which only becomes final once the new bytes are decoded, so without this an overlay picked
// (e.g. Live selected before the first /render lands) or a re-rendered snapshot kept the stale
// box until the next window resize (issue #2359).
img.addEventListener("load", function () {
    if (live && live.checked && !canvas.hidden) fitLiveCanvas();
    // Mirror the Wasm resize handler: the checkerboard phase moves with the overlay box, so
    // re-hand the patch (which carries bgPhase) to a ready app — not just reposition the frame.
    if (wasmActive()) {
        positionWasmFrame();
        if (wasmReady && wasmFrame!.contentWindow) {
            wasmFrame!.contentWindow.postMessage(wasmOverridePatch(), "*");
        }
    }
    if (rcWasmActive()) positionRcWasmFrame();
});
if (wasmToggle) {
    // The app posts "cp-wasm-ready" once its first frame is on the canvas — the swap signal.
    // Match on source (the known frame's contentWindow), not e.origin — robust regardless of
    // the frame's origin, and the payload is a fixed string so there's no data surface.
    window.addEventListener("message", function (e) {
        if (e.source !== wasmFrame!.contentWindow || e.data !== "cp-wasm-ready")
            return;
        revealWasm();
    });
    // Fallback for an app build that predates the ready signal: reveal a beat after the
    // document's load event rather than holding the snapshot forever.
    wasmFrame!.addEventListener("load", function () {
        setTimeout(function () {
            revealWasm();
        }, 8000);
    });
    // The overlay tracks the snapshot's box, which moves when the page reflows — and the
    // checkerboard phase moves with it, so re-hand it to the app.
    window.addEventListener("resize", function () {
        if (!wasmActive()) return;
        positionWasmFrame();
        if (wasmReady && wasmFrame!.contentWindow) {
            wasmFrame!.contentWindow.postMessage(wasmOverridePatch(), "*");
        }
    });
    // The page's Transparent toggle (owned by <cp-bg-toggle> in serve-components.js, which flips
    // `cp-bg-transparent` on <html>) changes what the stage paints — and the app mirrors that
    // backdrop, so it has to hear about it. Watching the class beats reaching across to that
    // script's click handler: the stage also changes with the render theme, and both land here.
    if (typeof MutationObserver === "function") {
        new MutationObserver(function () {
            if (!wasmActive() || !wasmReady || !wasmFrame!.contentWindow)
                return;
            wasmFrame!.contentWindow.postMessage(wasmOverridePatch(), "*");
        }).observe(document.documentElement, {
            attributes: true,
            attributeFilter: ["class"],
        });
    }
}
if (fs) {
    fs.addEventListener("input", function () {
        fsVal!.textContent = fs.value;
        fontScaleTouched = true;
        onControlsChanged();
    });
}
fields.forEach(function (f) {
    var el = document.getElementById("cp-" + f);
    if (el) el.addEventListener("change", onControlsChanged);
});
// Size mode: show only the input rows the chosen mode uses (Within shows both min + max), then
// re-render. The number inputs re-render on "input" (live typing) like the locale field.
const sizeMode = may<HTMLSelectElement>("cp-sizeMode");
if (sizeMode) {
    var syncSizeRows = function () {
        var m = sizeMode.value;
        var show: Record<string, boolean> = {
            fixed: m === "fixed",
            min: m === "min" || m === "within",
            max: m === "max" || m === "within",
        };
        ["fixed", "min", "max"].forEach(function (g) {
            var row = document.getElementById("cp-size-" + g);
            if (row) row.hidden = !show[g];
        });
    };
    syncSizeRows();
    sizeMode.addEventListener("change", function () {
        syncSizeRows();
        onControlsChanged();
    });
    [
        "cp-fixedW",
        "cp-fixedH",
        "cp-minW",
        "cp-minH",
        "cp-maxW",
        "cp-maxH",
    ].forEach(function (id) {
        var el = document.getElementById(id);
        if (el) el.addEventListener("input", onControlsChanged);
    });
}
// Overlay toggles are daemon-rendered: on the live lane they push a fresh setOverrides through
// the open stream; off it, ticking one ENTERS the live lane rather than doing nothing — the
// ticked box is already part of openStream()'s initial overrides, so the overlay is on in the
// first frame. They get their own handler rather than onControlsChanged so a toggle mid connect
// (ws not yet readyState 1) can't fall through to the snapshot / wasm-auto-enable branches — an
// overlay never applies to a baked PNG or the in-browser tier.
function onOverlayChanged() {
    // Overlays are part of overrides(), so the page URL and the export links have to be re-synced
    // like any other control — otherwise the ticked box is unshareable and Back can't restore it.
    refreshLinks();
    if (live && live.checked) {
        if (ws && ws.readyState === 1) {
            ws.send(
                JSON.stringify({
                    type: "setOverrides",
                    overrides: liveOverrides(),
                }),
            );
        }
        return;
    }
    // Not on the daemon lane yet. Only a *check* starts it: unticking an already-off overlay from
    // the snapshot lane shouldn't drag the visitor into Live Compose.
    if (anyOverlayChecked() && live && !live.disabled) setMode("live");
}
function anyOverlayChecked() {
    return Array.prototype.some.call(overlayToggles, function (el) {
        return el.checked;
    });
}
Array.prototype.forEach.call(overlayToggles, function (el) {
    el.addEventListener("change", onOverlayChanged);
});
// Author-declared **named knobs** (label, count, colour, …) re-render on edit (text/number
// debounce via "input", toggles "change"). Unlike the app-theme selector and detected-feature
// toggles below, these ARE honoured by the in-browser Wasm tier (its `catalogOverride*` seeds
// from the `knob.<key>` patch), so a knob edit drives whichever transport is live: the Wasm
// iframe when it's active (or auto-enable it on a static published catalog), the daemon stream
// when Live is up, or a `/render` snapshot when the session can re-render.
// A closed value-set knob (`previewOverrideChoice`) renders as a <select>, and a <select> silently
// drops an assignment it has no option for — `.value` becomes "". That matters because "" is a
// REAL value for a string knob (a cleared label, a variant seeded empty), so it would be sent as
// `knob.<key>=` rather than ignored: opening `?knob.size=xxl` would render the wrong override
// instead of the stale one it names. The server already keeps an unknown *baked* value by adding
// it as an option; this is the same courtesy for a value that only ever existed in the URL — a
// hand-written link, or one from before a value was renamed.
function adoptChoiceValue(el: Control, value: string) {
    if (!(el instanceof HTMLSelectElement) || value === "") return;
    for (var i = 0; i < el.options.length; i++)
        if (el.options[i].value === value) return;
    var option = document.createElement("option");
    option.value = value;
    option.textContent = value;
    el.insertBefore(option, el.firstChild);
}
// Which transport will carry a knob edit. Resolved BEFORE the URL sync rather than inline in the
// dispatch below, because the answer decides who owns the history entry — see [discrete] in
// onKnobEdited.
function knobRoute() {
    if (wasmActive()) return "wasm";
    if (live.checked && ws && ws.readyState === 1) return "live";
    if (canRenderOverrides) return "snapshot";
    // A published catalog can't re-render on the server, but its in-browser app can apply the
    // knob — auto-enable the Wasm tier and let its load carry the edit (wasmInitialSrc bakes the
    // patch into the fragment), mirroring the display-axis auto-enable in onControlsChanged.
    if (staticSnapshot && wasmToggle) return "enable-wasm";
    return "none";
}
// [discrete] marks an edit that earns its own history entry (a value picked from a closed set),
// as opposed to a continuous one (typing a label) that replaces.
//
// The `enable-wasm` route writes no history here at all — not a push, and not the replace a
// non-discrete edit would otherwise do. That path ends in `setMode("wasm")`, and `enterMode`
// syncs with a push of its own for the lane change. Doing anything here first spends the entry
// the visitor came from on an intermediate `choice + png` state — one that cannot apply the
// choice it names — and Back then lands on that instead of on the previous choice. Suppressing
// only the push is not enough: the replace clobbers the same entry just as thoroughly.
function onKnobEdited(discrete: boolean) {
    var route = knobRoute();
    if (discrete && route !== "enable-wasm") urlPush = true;
    refreshLinks(route === "enable-wasm");
    if (route === "wasm") {
        if (wasmReady && wasmFrame!.contentWindow) {
            wasmFrame!.contentWindow.postMessage(wasmOverridePatch(), "*");
        } else {
            wasmFrame!.src = wasmInitialSrc();
        }
        return;
    }
    if (route === "live") {
        ws!.send(
            JSON.stringify({
                type: "setOverrides",
                overrides: liveOverrides(),
            }),
        );
    } else if (route === "snapshot") {
        refreshSnapshot();
    } else if (route === "enable-wasm") {
        setMode("wasm");
    }
}
controls(".cp-knob").forEach(function (el) {
    el.addEventListener(
        el.type === "checkbox" ? "change" : "input",
        function () {
            // A closed value-set knob renders as a <select>, and picking from it is a DISCRETE choice —
            // like a lane switch or a theme pick — so it earns a history entry and Back returns to the
            // previously chosen value. A typed knob stays continuous and replaces instead, or one edit
            // of a label would bury the page under an entry per keystroke. See `urlPush`.
            onKnobEdited(el.tagName === "SELECT");
        },
    );
});
// The app-theme selector and detected-feature toggles route ONLY through the server daemon —
// an app-declared theme provider is a server-side wrapper, and focus/gesture overlays are
// daemon-rendered, neither of which the in-browser tier can produce — so they use a
// daemon-only handler and never the wasm path.
function onKnobChanged() {
    refreshLinks();
    if (live.checked && ws && ws.readyState === 1) {
        ws.send(
            JSON.stringify({
                type: "setOverrides",
                overrides: liveOverrides(),
            }),
        );
    } else if (canRenderOverrides) {
        refreshSnapshot();
    }
}
if (themeChoice)
    themeChoice.addEventListener("change", function () {
        themeChoice.setAttribute("data-theme-active", "1");
        syncThemeBar();
        // Like a lane switch: a picked theme earns its own history entry.
        urlPush = true;
        if (chosenThemeProvider()) onKnobChanged();
        else onControlsChanged();
    });
// Detected-feature toggles (Keyboard focus) re-render on the daemon like a knob — same routing,
// never the wasm auto-enable path.
controls(".cp-feature").forEach(function (el) {
    el.addEventListener("change", onKnobChanged);
});
// Remote Compose knobs apply in-browser in both RC lanes: repaint for JS, isolated reload for
// CMP/Wasm. Otherwise they route through the server daemon like theme/feature controls.
function onRcKnobChanged() {
    if (rcActive()) {
        refreshLinks();
        applyRcOverrides();
        return;
    }
    if (rcWasmActive()) {
        refreshLinks();
        openRcWasm();
        return;
    }
    onKnobChanged();
}
controls(".cp-rc-knob").forEach(function (el) {
    el.addEventListener(
        el.type === "checkbox" ? "change" : "input",
        onRcKnobChanged,
    );
});
// ——— Address-bar state ————————————————————————————————————————————————————————————————————
//
// The viewer's controls already produce a shareable /render URL; until now the *page* URL said
// nothing about them, so a bookmark of "this preview, Dynamic Dark, RTL, font scale 1.3"
// reopened on the preview's defaults. The params are exactly the /render override names, so the
// viewer URL and the copyable render URL describe the same state and a param learned from one
// works in the other.
//
// Only the params below are ours: `token` / `session` (and anything else the server put on the
// URL) are never touched, and a control returning to its default *removes* its param rather
// than pinning a redundant value, so an untouched viewer keeps the clean URL it was opened
// with.
// Which parameters the viewer manages lives in `cli/serve-web/src/viewer/ownedParams.ts`.
// `cpUrlState.sync` DROPS any owned parameter the caller does not supply, so over-claiming
// deletes someone else's parameter on the next edit and under-claiming leaves a stale one behind.
function ownsUrlParam(name: string) {
    return rules.ownsUrlParam(name);
}
function currentMode() {
    var checked = document.querySelector<HTMLInputElement>(
        'input[name="cp-mode"]:checked',
    );
    return checked ? checked.value : "png";
}
// Set before a discrete choice (a lane switch, a theme pick) so the sync it triggers PUSHES a
// history entry — Back then returns to the previous lane/theme. Continuous edits (a slider, a
// typed knob) leave it false and replace instead, so one drag can't bury the catalog page under
// fifty entries. Consumed by the first sync that follows.
var urlPush = false;
function syncUrl() {
    var push = urlPush;
    urlPush = false;
    if (!window.cpUrlState) return;
    var values: Record<string, string> = {};
    new URLSearchParams(query()).forEach(function (value, name) {
        if (ownsUrlParam(name)) values[name] = value;
    });
    if (scrollLong && scrollLong.checked) values.scroll = "long";
    // The exploded view and its knobs, written from the same helper the render URL uses so the
    // page's own address, the copied link and the fetched bytes can never disagree about the
    // angle on screen.
    if (explodeOn()) {
        new URLSearchParams(explodeQuery()).forEach(function (value, name) {
            values[name] = value;
        });
    }
    var mode = currentMode();
    if (mode !== "png") values.mode = mode;
    var sizeModeEl = may<HTMLSelectElement>("cp-sizeMode");
    if (sizeModeEl && sizeModeEl.value) values.sizeMode = sizeModeEl.value;
    // The spec lane's comparison view (diff / triptych / slider). Re-emitted on every sync because
    // `sync` drops any owned param the values don't supply — spec-compare.js pushes it the moment
    // it is picked, and this is what stops the next knob edit from clearing it again. Only while
    // the lane is actually up: `?specView=` on a page showing a render describes nothing.
    var specView = window.cpSpecCompare ? window.cpSpecCompare.view() : "";
    if (mode === "spec" && specView && specView !== "spec")
        values.specView = specView;
    // Which recording is playing, on the same terms: only while the lane is up (`?motion=` beside a
    // render describes nothing), and only past the first, which is what the lane opens on anyway.
    // Without it a multi-capture preview's shared link always restored the FIRST capture, so the
    // page someone sent was not the page they were looking at.
    if (mode === "motion" && motionButtons.length > 1) {
        var pickedMotion = motionPickedId();
        if (pickedMotion && motionPicked() !== motionButtons[0])
            values.motion = pickedMotion;
    }
    window.cpUrlState.sync(values, ownsUrlParam, !push);
}
// What the controls hold when the URL names nothing — captured after the server markup and the
// sticky-theme script have had their say, so Back out of a choice restores the page as it first
// opened rather than whatever localStorage was last written with.
var initialTheme = themeChoice ? themeChoice.value : "";
var initialThemeActive = themeChoice
    ? themeChoice.getAttribute("data-theme-active")
    : "0";
// px on the wire (like every override), dp in the input — the inverse of sizePx().
function setSizeInput(id: string, px: string | null) {
    var el = may<HTMLInputElement>(id);
    if (!el) return;
    var value = parseFloat(px || "");
    el.value = value > 0 ? String(Math.round(value / renderDensity)) : "";
}
// Restore every owned control from the URL. Also runs for Back/Forward, so a param the entry
// does NOT carry has to reset its control — leaving the live value would make the restored page
// disagree with its own URL.
function hydrateFromUrl(popped: boolean) {
    var q = new URLSearchParams(location.search);
    fields.forEach(function (f) {
        var el = may<Control>("cp-" + f);
        if (el) el.value = q.get(f) || "";
    });
    if (fs) {
        var scale = q.get("fontScale");
        fontScaleTouched = !!scale;
        fs.value = scale || "1.0";
        if (fsVal) fsVal.textContent = scale ? fs.value : "default";
    }
    if (scrollLong) scrollLong.checked = q.get("scroll") === "long";
    // The exploded view restores from the URL like every other axis, so a shared
    // `?exploded=1&explodeTilt=40` link opens on the picture it names and Back/Forward walks the
    // angles someone tried. A knob the entry doesn't carry resets to its authored default rather
    // than keeping the live value, which would leave the page disagreeing with its own address.
    if (explodeToggle) {
        var explodeWanted = explodeParamOn(q.get("exploded"));
        explodeToggle.setAttribute(
            "aria-pressed",
            explodeWanted ? "true" : "false",
        );
        if (root) root.setAttribute("data-exploded", explodeWanted ? "1" : "0");
        EXPLODE_KNOBS.forEach(function (pair) {
            var el = may<HTMLInputElement>(pair[0]);
            if (!el) return;
            // Validate before assigning. `<input type="range">` runs the browser's value-sanitization
            // algorithm on whatever it is given, and a value it can't parse — a stale
            // `?explodeTilt=nope`, or one outside min..max — lands on the range's MIDPOINT, not on the
            // authored default. The next refresh would then render a camera nobody asked for and
            // rewrite the shared URL to match it, which is the opposite of the documented fallback.
            var raw = q.get(pair[1]);
            var num = raw === null || raw === "" ? NaN : Number(raw);
            if (isFinite(num)) {
                // Finite but out of range is CLAMPED, not rejected — `ExplodedSvg` clamps the angles and
                // the separation it is handed, so `?explodeTilt=76` renders at 75° from the endpoint and
                // must open at 75° here too rather than snapping to the default and rewriting the URL
                // out from under whoever shared it.
                var min = parseFloat(el.getAttribute("min") || "");
                var max = parseFloat(el.getAttribute("max") || "");
                if (!isNaN(min) && num < min) num = min;
                if (!isNaN(max) && num > max) num = max;
                el.value = String(num);
            } else {
                // Only a value the browser cannot parse falls back. Assigning it raw would be worse than
                // useless: `<input type="range">` sanitizes an unparseable value to its MIDPOINT, so a
                // stale `?explodeTilt=nope` rendered a camera nobody asked for.
                el.value = el.getAttribute("data-cp-default") || el.value;
            }
            updateExplodeReadout(el);
        });
        syncExplodeControls();
        // `?exploded=1` names a view of the VECTOR export, and the SVG lane has no URL param of its
        // own (it is a format toggle, not a mode) — so the exploded param is what puts the page on
        // `.svg`. Doing it here rather than at bootstrap covers Back/Forward too, and running before
        // the first refreshSnapshot means a shared exploded link fetches the exploded SVG once
        // instead of painting the flat PNG and replacing it. Leaving the view does NOT force the
        // vector lane back off: the visitor may have been reading the plain SVG before they exploded
        // it, and that is the state Back should return them to.
        if (explodeWanted && svgToggle && !svgOn()) {
            explodeEnabledSvg = true;
            svgToggle.setAttribute("aria-pressed", "true");
            snapshotExt = ".svg";
            root.setAttribute("data-mode", "svg");
        } else if (!explodeWanted && explodeEnabledSvg && svgToggle) {
            // Back out of an entry that 3D created: the vector lane has no URL parameter of its own,
            // so without this the restored page keeps the SVG that 3D switched it to and shows a flat
            // vector render the visitor never chose. Only when 3D is what turned it on.
            explodeEnabledSvg = false;
            svgToggle.setAttribute("aria-pressed", "false");
            snapshotExt = ".png";
            root.setAttribute("data-mode", "snapshot");
        }
    }
    ["focus", "gestures"].forEach(function (f) {
        var el = may<HTMLInputElement>("cp-" + f);
        if (el) el.checked = q.get(f) !== null;
    });
    // Overlays ride the URL now that they're collected outside the live lane, so a shared
    // `?touchOverlay=true&mode=live` link opens with the box already ticked (and Back restores it).
    // Only `true` is ever written, so presence-with-that-value is the whole state.
    ticks(".cp-overlay").forEach(function (el) {
        el.checked = q.get(el.id.replace(/^cp-/, "")) === "true";
    });
    var sizeModeEl = may<HTMLSelectElement>("cp-sizeMode");
    if (sizeModeEl) {
        sizeModeEl.value = q.get("sizeMode") || "";
        setSizeInput("cp-fixedW", q.get("widthPx"));
        setSizeInput("cp-fixedH", q.get("heightPx"));
        setSizeInput("cp-minW", q.get("minWidthPx"));
        setSizeInput("cp-minH", q.get("minHeightPx"));
        setSizeInput("cp-maxW", q.get("maxWidthPx"));
        setSizeInput("cp-maxH", q.get("maxHeightPx"));
        if (typeof syncSizeRows === "function") syncSizeRows();
    }
    controls(".cp-knob").forEach(function (el) {
        var key = el.getAttribute("data-knob-key");
        if (!key) return;
        var value = q.get("knob." + key);
        if (value === null) value = el.getAttribute("data-knob-initial") || "";
        if (el instanceof HTMLInputElement && el.type === "checkbox")
            el.checked = value === "true" || value === "1";
        else {
            adoptChoiceValue(el, value);
            el.value = value;
        }
    });
    controls(".cp-rc-knob").forEach(function (el) {
        var name = el.getAttribute("data-rc-name");
        if (!name) return;
        var kind = el.getAttribute("data-rc-kind") || "";
        var value = q.get("rc." + name);
        if (value === null) value = el.getAttribute("data-rc-initial") || "";
        else if (kind && value.indexOf(kind + ":") === 0)
            value = value.substring(kind.length + 1);
        if (el instanceof HTMLInputElement && el.type === "checkbox")
            el.checked = value === "true" || value === "1";
        else el.value = value;
    });
    // The theme select is seeded (from the URL first, then localStorage) by the sticky script
    // before this file runs, so the initial pass must not touch it. A Back/Forward pass owns it:
    // the entry's theme, or the one the page opened with when it names none.
    if (popped && themeChoice) {
        var provider = q.get("themeProvider");
        var uiMode = q.get("uiMode");
        var choice = provider
            ? "theme:" + provider
            : uiMode === "light" || uiMode === "dark"
              ? uiMode
              : "";
        var offered = false;
        Array.prototype.forEach.call(themeChoice.options, function (o) {
            if (choice && o.value === choice) offered = true;
        });
        themeChoice.value = (offered ? choice : initialTheme) || "";
        themeChoice.setAttribute(
            "data-theme-active",
            offered ? "1" : initialThemeActive || "0",
        );
        syncThemeBar();
        // …and the page around the stage, when the Page theme setting says to follow the choice.
        // Setting `.value` fires no `change`, so the sticky script's handler — which is what keeps
        // the chrome in step when a theme is PICKED — never runs on this path. Without this, going
        // Back from Dark to a Light entry re-rendered the preview light inside a page still pinned
        // dark. The same call the format-comparison pop handler already makes.
        //
        // The ACTIVE choice, not the displayed one. A viewer opened with no theme in the URL and
        // none remembered shows the preview's baked default while `data-theme-active="0"` says
        // nobody chose it, and the chrome correctly follows the OS. Restoring that entry re-sets
        // the attribute to "0" above, so passing `.value` here would pin the page to a baked mode
        // the visitor never picked — the one state Back is supposed to return them to.
        // `activeThemeChoice()` yields "" for it, which paints neither class and hands the page
        // back to `prefers-color-scheme`.
        if (window.cpPageTheme) window.cpPageTheme.follow(activeThemeChoice());
    }
    // The Remote Compose player pick already rode the URL as `rcPlayer=<wire>` (query() emits it,
    // URL_STATE_PARAMS owns it) but nothing ever read it back, so a shared `?rcPlayer=cmp-android`
    // link opened on the default player under a combo naming it — the link described a render the
    // page wasn't showing. Restore it here, from the offered options only, so an unknown or
    // unavailable wire falls back to this preview's default rather than pinning a dead param.
    if (rcDefaultBackend) {
        var wantedPlayer = q.get("rcPlayer");
        var playerOffered = false;
        if (wantedPlayer && laneSelect) {
            Array.prototype.forEach.call(laneSelect.options, function (o) {
                if (o.value === "rc:" + wantedPlayer && !o.disabled)
                    playerOffered = true;
            });
        }
        rcPlayerPicked =
            playerOffered || rules.backendRequiresRenderParam(rcDefaultBackend);
        rcPlayerBackend =
            (playerOffered ? wantedPlayer : rcDefaultBackend) || "";
    }
    // Restore the spec lane's comparison view before the lane itself is entered (the bookmarked
    // `?mode=spec` lands at the very bottom of this file), so a shared
    // `?mode=spec&specView=slider` link opens on the wipe rather than flashing the plain spec.
    if (window.cpSpecCompare)
        window.cpSpecCompare.hydrate(q.get("specView") || "");
    // Same ordering, same reason: the bookmarked `?mode=motion` is applied at the very bottom of
    // this file, so pressing the named capture's button first is what makes openMotion() request
    // the shared recording instead of loading the first one and swapping a moment later. A URL
    // that names no capture falls back to the first, which is where the lane opens.
    if (motionButtons.length && !pickMotion(q.get("motion") || "")) {
        motionButtons.forEach(function (b, i) {
            b.setAttribute("aria-pressed", i === 0 ? "true" : "false");
        });
    }
    syncLaneSelect();
}
hydrateFromUrl(false);
// Read the bookmarked lane NOW, before the first refreshSnapshot's sync clears a param no
// control is holding yet. It is applied at the very bottom of this file, once the snapshot every
// lane falls back to has been requested.
var initialUrlMode = new URLSearchParams(location.search).get("mode") || "";
if (window.cpUrlState) {
    window.cpUrlState.onPop(function () {
        hydrateFromUrl(true);
        var mode = currentMode();
        var wanted = new URLSearchParams(location.search).get("mode") || "png";
        // A lane change re-renders through enterMode; otherwise the restored overrides go out over
        // whichever transport is already up. Either way nothing reloads.
        if (wanted !== mode) setMode(wanted);
        // Same lane, and Motion is the one where that still means something changed: it carries no
        // overrides for onControlsChanged() to push and no transport to push them over, but a
        // restored entry can name a different capture. hydrateFromUrl() has already pressed that
        // button, so without this the picker would describe a recording that is not on screen.
        else if (wanted === "motion") playMotion();
        else onControlsChanged();
    });
}
// Reconcile the control enabled-state + the toggle's initial look with the session's
// capabilities (matches the server-rendered markup; keeps them in sync after hydration).
syncServerControls();
syncOverlayToggles();
updateLiveToggle();
// Before the first snapshot goes out, so a deep link that already names a theme never paints the
// baked verdict — not even for the one frame it would take refreshLinks() to correct it.
syncSpecBaseline();
refreshSnapshot();
// A bookmarked `?mode=live` / `wasm` / `rc` opens in that lane — but only once the initial
// snapshot has LANDED, not merely been requested.
//
// The stage's <img> is emitted with no src: the refreshSnapshot() above is the only thing that
// will ever put pixels in it. Entering an interactive lane cancels any in-flight snapshot
// (cancelSnapshotLoading bumps the generation), so switching immediately would discard that one
// render and leave a cold bookmarked load looking at an empty stage behind a lane that may take
// seconds to paint — or that fails and shows an activation error over nothing. Waiting for the
// frame first makes the bookmark land in exactly the state a visitor reaches by loading the page
// and clicking the toggle, which is the whole claim.
//
// Bounded, because the snapshot may never settle: a render that errors sets no src (so neither
// event fires) and one that hangs would strand the bookmark on the snapshot lane forever.
// A mode this session doesn't offer (no daemon, no Wasm app) is ignored rather than entering a
// lane whose control is absent or disabled — the page stays on the snapshot and the param clears
// on the next sync.
(function () {
    var wanted = initialUrlMode;
    if (!wanted || wanted === "png" || wanted === currentMode()) return;
    const radio = Array.from(ticks('input[name="cp-mode"]')).find(function (r) {
        return r.value === wanted;
    });
    if (!radio || radio.disabled) return;
    var entered = false;
    function enterBookmarkedMode() {
        if (entered) return;
        entered = true;
        img.removeEventListener("load", enterBookmarkedMode);
        img.removeEventListener("error", enterBookmarkedMode);
        setMode(wanted);
    }
    img.addEventListener("load", enterBookmarkedMode);
    img.addEventListener("error", enterBookmarkedMode);
    // A snapshot that FAILS assigns no src, so neither <img> event fires. Settling on the request
    // itself (see snapshotSettled) enters the bookmarked lane immediately instead of after the
    // timeout below — which matters for a link like `?mode=wasm&fontScale=2.0` on a baked-only
    // session: the snapshot is refused (#3449), but the in-browser Wasm lane can apply the override.
    onSnapshotSettled = enterBookmarkedMode;
    setTimeout(enterBookmarkedMode, 8000);
})();
