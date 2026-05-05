// @ts-nocheck
//
// Imperative behaviour for the live "Compose Preview" webview panel.
//
// Verbatim port of the previously-inline IIFE script in
// `src/previewPanel.ts` (the `<script nonce="...">` block in `getHtml()`).
// Kept untyped for now so the lift-into-bundle change stays mechanical;
// future commits can incrementally type-tighten and split this into
// reactive Lit components.
//
// Runs once per webview load. Assumes `<preview-app>` has already
// rendered its skeleton into light DOM, so `document.getElementById(...)`
// queries below resolve.

import { getVsCodeApi } from "../shared/vscode";
import {
    applyHierarchyOverlay,
    buildA11yLegend,
    buildA11yOverlay,
    ensureHierarchyOverlay,
} from "./a11yOverlay";
import {
    buildTooltip,
    buildVariantLabel,
    isAnimatedPreview,
    isWearPreview,
    mimeFor,
    parseBounds,
    sanitizeId,
} from "./cardData";
import { PreviewGrid } from "./components/PreviewGrid";
import { showDiffOverlay, type DiffMode } from "./diffOverlay";
import { buildErrorPanel } from "./errorPanel";
import { attachInteractiveInputHandlers } from "./interactiveInput";
import { LoadingOverlay } from "./loadingOverlay";
import { previewStore } from "./previewStore";
import { StaleBadgeController } from "./staleBadge";
import { ViewportTracker } from "./viewportTracker";

export function setupPreviewBehavior(
    initialEarlyFeaturesEnabled: boolean,
): void {
    const vscode = getVsCodeApi();
    const state = vscode.getState() || { filters: {} };
    // `earlyFeaturesEnabled` lives in `previewStore` so future
    // components can subscribe to it without going through this
    // closure. Reads inside this file go through the local helper for
    // terseness; writes go straight to `previewStore.setState`.
    previewStore.setState({
        earlyFeaturesEnabled: initialEarlyFeaturesEnabled,
    });
    const earlyFeatures = (): boolean =>
        previewStore.getState().earlyFeaturesEnabled;

    const grid = document.getElementById("preview-grid") as PreviewGrid;
    const focusInspector = document.getElementById("focus-inspector");
    // `<message-banner>` owns the status strip; we use a typed-ish handle
    // to call setMessage / read its current owner from the few cases that
    // still need to drive it (filter narrowing, ensureNotBlank fallback,
    // clearAll). showMessage messages from the extension reach the
    // component directly without going through this code.
    const messageBanner = document.querySelector("message-banner");
    // `<filter-toolbar>` owns the function/group/layout selects,
    // their options, and the user-interaction events. We grab a handle
    // here for the programmatic get/set + populate paths used by
    // applyFilters / applyLayout / setPreviews / setFunctionFilter /
    // focusOnCard / exitFocus / restoreFilterState.
    const filterToolbar = document.querySelector("filter-toolbar");
    const focusControls = document.getElementById("focus-controls");
    const btnPrev = document.getElementById("btn-prev");
    const btnNext = document.getElementById("btn-next");
    const btnDiffHead = document.getElementById("btn-diff-head");
    const btnDiffMain = document.getElementById("btn-diff-main");
    const btnLaunchDevice = document.getElementById("btn-launch-device");
    const btnA11yOverlay = document.getElementById("btn-a11y-overlay");
    const btnInteractive = document.getElementById("btn-interactive");
    const btnStopInteractive = document.getElementById("btn-stop-interactive");
    const btnRecording = document.getElementById("btn-recording");
    const recordingFormat = document.getElementById("recording-format");
    const btnExitFocus = document.getElementById("btn-exit-focus");
    // D2 — focus-mode a11y overlay toggle. Off by default; turning it on subscribes the
    // focused preview to a11y/atf + a11y/hierarchy via the extension, off unsubscribes.
    // Also gates the panel-side hierarchy overlay so the existing finding overlay (which
    // can also arrive via the Gradle sidecar path) doesn't appear without an explicit
    // user gesture. State is per-previewId because hopping between focused cards re-applies
    // the toggle to the new target.
    // `a11yOverlayPreviewId` lives in `previewStore`. Local helpers
    // for terseness — same pattern as `earlyFeatures()`.
    const a11yOverlay = (): string | null =>
        previewStore.getState().a11yOverlayPreviewId;
    const setA11yOverlay = (id: string | null): void => {
        previewStore.setState({ a11yOverlayPreviewId: id });
    };
    const enabledFocusProducts = new Set();
    let focusProductPickerOpen = false;
    let focusHistoryOpen = false;
    const focusPosition = document.getElementById("focus-position");
    // Progress bar is owned by `<progress-bar>` — see
    // `components/ProgressBar.ts`. It listens for `setProgress` /
    // `clearProgress` directly and owns its own deferred-paint timing.

    // Compile-error banner is owned by `<compile-errors-banner>` —
    // see `components/CompileErrorsBanner.ts`. It listens for
    // `setCompileErrors` / `clearCompileErrors` directly and toggles
    // the `compile-stale` class on `#preview-grid` itself.

    let allPreviews = [];
    let moduleDir = "";
    let filterDebounce = null;
    let focusIndex = 0;
    // Last previewId published to the extension via previewScopeChanged.
    // Tracked here so we don't spam the History panel with redundant
    // re-scopes (e.g. layout reapplies on every filter tweak).
    let lastScopedPreviewId = null;
    // Layout to fall back to when the user exits focus mode. Captured
    // whenever we transition into focus from another layout (dropdown
    // change, dblclick on a card). Defaults to grid so the very first
    // exit lands somewhere sensible.
    let previousLayout =
        state.layout && state.layout !== "focus" ? state.layout : "grid";

    // Interactive (live-stream) mode state. Declared up here — *before*
    // the first applyLayout() call below — because applyLayout reads
    // interactivePreviewIds via the early-exit-on-focus-change path.
    // moduleDaemonReady tracks per-module daemon readiness pushed by the
    // extension via setInteractiveAvailability; the button enables only
    // when the focused card's owning module is ready. moduleInteractiveSupported
    // distinguishes full v2 live mode from the Android/v1 fallback where
    // renders refresh but pointer input doesn't mutate held composition state.
    //
    // interactivePreviewIds is a Set so Shift+click on the LIVE toggle
    // can add/remove a preview without disturbing others (multi-stream
    // UI). The wire and daemon already support concurrent streams
    // (INTERACTIVE.md § 8); the panel just exposes it under a modifier
    // key. Default un-modified click stays single-target — clearing the
    // set before adding the new one — so casual users don't accidentally
    // pile up live streams.
    const moduleDaemonReady = new Map();
    const moduleInteractiveSupported = new Map();
    const interactivePreviewIds = new Set();
    const recordingPreviewIds = new Set();

    // Config for the interactive-input pointer machine. The predicate
    // unifies live/recording state — both forward pointer/wheel input
    // to the daemon — so the module doesn't need direct access to
    // either Set.
    const interactiveInputConfig = {
        isLive: (id) =>
            interactivePreviewIds.has(id) || recordingPreviewIds.has(id),
        vscode,
    };

    // Config for `showDiffOverlay` — reads/writes the persisted Side/
    // Overlay/Onion mode through the same `state` object that holds the
    // layout / filter preferences.
    const diffOverlayConfig = {
        vscode,
        getDiffMode: (): DiffMode =>
            state.diffMode === "overlay" || state.diffMode === "onion"
                ? state.diffMode
                : "side",
        setDiffMode: (mode: DiffMode): void => {
            state.diffMode = mode;
            vscode.setState(state);
        },
    };

    const staleBadge = new StaleBadgeController(vscode);
    const loadingOverlay = new LoadingOverlay();

    // Restore layout preference
    if (
        state.layout &&
        ["grid", "flow", "column", "focus"].includes(state.layout)
    ) {
        filterToolbar.setLayoutValue(state.layout);
    }
    applyLayout();

    // Seed a placeholder so the view isn't blank during the ~1s boot
    // window before the extension posts its first message. Any real
    // message (Building…, empty-state notice, cards) will replace it.
    messageBanner.setMessage("Loading Compose previews…", "fallback");

    filterToolbar.addEventListener("layout-changed", () => {
        if (
            filterToolbar.getLayoutValue() === "focus" &&
            state.layout !== "focus"
        ) {
            previousLayout = state.layout || "grid";
        }
        state.layout = filterToolbar.getLayoutValue();
        vscode.setState(state);
        applyLayout();
    });

    btnPrev.addEventListener("click", () => navigateFocus(-1));
    btnNext.addEventListener("click", () => navigateFocus(1));
    btnDiffHead.addEventListener("click", () => requestFocusedDiff("head"));
    btnDiffMain.addEventListener("click", () => requestFocusedDiff("main"));
    btnLaunchDevice.addEventListener("click", () => requestLaunchOnDevice());
    btnA11yOverlay.addEventListener("click", () => toggleA11yOverlay());
    // Shift modifier opts into the multi-stream path: keep the prior live targets, add or
    // remove just this one. Plain click keeps the single-target single-card UX casual users
    // expect.
    btnInteractive.addEventListener("click", (e) =>
        toggleInteractive(e.shiftKey),
    );
    btnStopInteractive.addEventListener("click", () => stopAllInteractive());
    btnRecording.addEventListener("click", () => toggleRecording());
    btnExitFocus.addEventListener("click", () => exitFocus());

    // ----- Interactive (live-stream) mode helpers -----
    // State (moduleDaemonReady, interactivePreviewIds) is declared
    // earlier so the first applyLayout() can reach it. The function
    // declarations below are hoisted, so call sites above this block
    // resolve fine.

    function applyEarlyFeatureVisibility() {
        btnDiffHead.hidden = !earlyFeatures();
        btnDiffMain.hidden = !earlyFeatures();
        btnLaunchDevice.hidden = !earlyFeatures();
        btnA11yOverlay.hidden =
            !earlyFeatures() || filterToolbar.getLayoutValue() !== "focus";
        btnRecording.hidden =
            !earlyFeatures() || filterToolbar.getLayoutValue() !== "focus";
        recordingFormat.hidden =
            !earlyFeatures() || filterToolbar.getLayoutValue() !== "focus";
        if (!earlyFeatures()) {
            focusInspector.hidden = true;
        }
    }

    function isFocusedDaemonReady() {
        // The webview doesn't know moduleId per preview today. We fall
        // back to "any module ready" because the extension-side panel
        // is single-module-scoped (it only ever holds one module's
        // previews at a time) — so the readiness of the sole module is
        // also the readiness for whatever's focused. If the panel ever
        // shows multiple modules at once, swap this to lookup by the
        // focused card's data-module-id.
        for (const ready of moduleDaemonReady.values()) {
            if (ready) return true;
        }
        return false;
    }

    function isFocusedInteractiveSupported() {
        for (const [moduleId, ready] of moduleDaemonReady.entries()) {
            if (ready && moduleInteractiveSupported.get(moduleId) === true)
                return true;
        }
        return false;
    }

    function applyInteractiveButtonState() {
        const inFocus = filterToolbar.getLayoutValue() === "focus";
        // Hide outright when not in focus mode — the toolbar already
        // hides itself, but this keeps aria-pressed correct for tests
        // that snapshot the button in either layout.
        btnInteractive.hidden = !inFocus;
        const hasLive = interactivePreviewIds.size > 0;
        btnStopInteractive.hidden = !inFocus || !hasLive;
        btnStopInteractive.disabled = !hasLive;
        if (!inFocus) {
            // Cheap fast-path: applyLayout fires this on every layout
            // change (filter tweaks, focus nav, every setPreviews). In
            // non-focus modes the focus-controls strip is hidden by CSS,
            // so nothing visible would change — skip the getVisibleCards
            // DOM walk and the per-attribute writes. State on re-entry to
            // focus mode is rebuilt fresh by the full path below.
            btnInteractive.setAttribute("aria-pressed", "false");
            btnInteractive.classList.remove("live-on");
            return;
        }
        const visible = getVisibleCards();
        const card = visible[focusIndex];
        if (!card) {
            btnInteractive.disabled = true;
            btnInteractive.setAttribute("aria-pressed", "false");
            btnInteractive.title = "Daemon not ready — live mode unavailable";
            btnInteractive.innerHTML =
                '<i class="codicon codicon-circle-large-outline" aria-hidden="true"></i>';
            return;
        }
        const previewId = card.dataset.previewId;
        const ready = isFocusedDaemonReady();
        const supported = isFocusedInteractiveSupported();
        const live = !!previewId && interactivePreviewIds.has(previewId);
        const otherLiveCount = interactivePreviewIds.size - (live ? 1 : 0);
        btnInteractive.disabled = !ready && !live;
        btnInteractive.setAttribute("aria-pressed", live ? "true" : "false");
        btnInteractive.classList.toggle("live-on", live);
        btnInteractive.title = !ready
            ? "Daemon not ready — live mode unavailable"
            : live
              ? "Live · click to exit · Shift+click to leave others on"
              : !supported
                ? "Live v1 fallback — renders refresh, but clicks do not preserve Compose state"
                : otherLiveCount > 0
                  ? otherLiveCount +
                    " other live · click to make this one live too · " +
                    "Shift+click to add without unsubscribing the rest"
                  : "Enter live mode (stream renders) · Shift+click to add to multi-stream";
        btnInteractive.innerHTML = live
            ? '<i class="codicon codicon-record" aria-hidden="true"></i>'
            : '<i class="codicon codicon-circle-large-outline" aria-hidden="true"></i>';
    }

    function applyRecordingButtonState() {
        const inFocus = filterToolbar.getLayoutValue() === "focus";
        btnRecording.hidden = !earlyFeatures() || !inFocus;
        recordingFormat.hidden = !earlyFeatures() || !inFocus;
        if (!earlyFeatures() || !inFocus) {
            btnRecording.setAttribute("aria-pressed", "false");
            btnRecording.classList.remove("recording-on");
            recordingFormat.disabled = true;
            return;
        }
        const card = getVisibleCards()[focusIndex];
        const previewId = card ? card.dataset.previewId : null;
        const ready = isFocusedDaemonReady();
        const recording = !!previewId && recordingPreviewIds.has(previewId);
        btnRecording.disabled = !ready && !recording;
        recordingFormat.disabled = recording || (!ready && !recording);
        btnRecording.setAttribute("aria-pressed", recording ? "true" : "false");
        btnRecording.classList.toggle("recording-on", recording);
        btnRecording.title = !ready
            ? "Daemon not ready — recording unavailable"
            : recording
              ? "Stop recording focused preview"
              : "Record focused preview";
        btnRecording.innerHTML = recording
            ? '<i class="codicon codicon-debug-stop" aria-hidden="true"></i>'
            : '<i class="codicon codicon-record-keys" aria-hidden="true"></i>';
    }

    // D2 — keep the a11y-overlay toggle in lockstep with focus-mode + the focused card
    // identity. Outside focus mode the button hides; inside focus mode aria-pressed
    // tracks whether the currently focused preview has the overlay subscription on.
    function applyA11yOverlayButtonState() {
        const inFocus = filterToolbar.getLayoutValue() === "focus";
        btnA11yOverlay.hidden = !earlyFeatures() || !inFocus;
        if (!earlyFeatures() || !inFocus) {
            btnA11yOverlay.setAttribute("aria-pressed", "false");
            return;
        }
        const card = getVisibleCards()[focusIndex];
        const previewId = card ? card.dataset.previewId : null;
        const on = previewId !== null && previewId === a11yOverlay();
        btnA11yOverlay.setAttribute("aria-pressed", on ? "true" : "false");
        btnA11yOverlay.title = on
            ? "Hide accessibility overlay"
            : "Show accessibility overlay";
        btnA11yOverlay.classList.toggle("a11y-overlay-on", on);
    }

    // D2 — clicking the a11y toggle subscribes/unsubscribes via the extension. When
    // turning OFF, the extension also pushes an empty updateA11y so the cached overlay
    // tears down immediately rather than waiting for a next render. When turning ON for a
    // different preview, first turn the previous one off so the wire stays clean.
    function toggleA11yOverlay() {
        if (!earlyFeatures()) return;
        if (filterToolbar.getLayoutValue() !== "focus") return;
        const card = getVisibleCards()[focusIndex];
        const previewId = card ? card.dataset.previewId : null;
        if (!previewId) return;
        const turningOn = previewId !== a11yOverlay();
        if (a11yOverlay() && a11yOverlay() !== previewId) {
            vscode.postMessage({
                command: "setA11yOverlay",
                previewId: a11yOverlay(),
                enabled: false,
            });
        }
        setA11yOverlay(turningOn ? previewId : null);
        vscode.postMessage({
            command: "setA11yOverlay",
            previewId,
            enabled: turningOn,
        });
        applyA11yOverlayButtonState();
        renderFocusInspector(card);
    }

    function applyLiveBadge() {
        // Tear down every prior live decoration first — a removal from interactivePreviewIds
        // (Shift+click off, set-cleared on daemon-not-ready, etc.) needs the badge to come
        // off the now-not-live card. Then re-stamp every still-live preview.
        document.querySelectorAll(".preview-card.live").forEach((c) => {
            c.classList.remove("live");
            c.querySelector(".card-live-stop-btn")?.remove();
        });
        if (interactivePreviewIds.size === 0) return;
        interactivePreviewIds.forEach((previewId) => {
            const card = document.getElementById(
                "preview-" + sanitizeId(previewId),
            );
            if (!card) return;
            card.classList.add("live");
            ensureLiveCardControls(card);
        });
    }

    function ensureLiveCardControls(card) {
        const container = card.querySelector(".image-container");
        if (!container) return;
        if (!container.querySelector(".card-live-stop-btn")) {
            const btn = document.createElement("button");
            btn.type = "button";
            btn.className = "icon-button card-live-stop-btn";
            btn.title = "Stop live preview";
            btn.setAttribute("aria-label", "Stop live preview");
            btn.innerHTML =
                '<i class="codicon codicon-debug-stop" aria-hidden="true"></i>';
            btn.addEventListener("click", (evt) => {
                evt.preventDefault();
                evt.stopPropagation();
                stopInteractiveForCard(card);
            });
            container.appendChild(btn);
        }
        const img = container.querySelector("img");
        if (img)
            attachInteractiveInputHandlers(card, img, interactiveInputConfig);
    }

    function stopAllInteractive() {
        if (interactivePreviewIds.size === 0) return;
        const ids = Array.from(interactivePreviewIds);
        interactivePreviewIds.clear();
        ids.forEach((previewId) => {
            vscode.postMessage({
                command: "setInteractive",
                previewId,
                enabled: false,
            });
        });
        applyLiveBadge();
        applyInteractiveButtonState();
    }

    function stopInteractiveForCard(card) {
        const previewId = card.dataset.previewId;
        if (!previewId || !interactivePreviewIds.has(previewId)) return;
        interactivePreviewIds.delete(previewId);
        vscode.postMessage({
            command: "setInteractive",
            previewId,
            enabled: false,
        });
        applyLiveBadge();
        applyInteractiveButtonState();
    }

    function toggleInteractive(shift) {
        // Drives the LIVE button in the focus-mode toolbar — operates on the currently
        // focused card. Body factored into setInteractiveForCard so the in-card click handler
        // (any layout) can reuse the same plain-vs-Shift logic.
        if (filterToolbar.getLayoutValue() !== "focus") return;
        const visible = getVisibleCards();
        const card = visible[focusIndex];
        if (!card) return;
        setInteractiveForCard(card, shift);
    }

    /**
     * Toggle interactive mode for [card] honouring plain/Shift semantics:
     *  - Plain: single-target. Drop every prior live target before adding (or re-removing)
     *    this one — keeps the casual UX matching v1's "one card live at a time" mental model.
     *  - Shift: multi-target. Toggle just this preview in/out of the live set without
     *    touching the others.
     */
    function setInteractiveForCard(card, shift) {
        const previewId = card.dataset.previewId;
        if (!previewId) return;
        const wasLive = interactivePreviewIds.has(previewId);
        if (!shift) {
            interactivePreviewIds.forEach((prior) => {
                if (prior === previewId) return;
                vscode.postMessage({
                    command: "setInteractive",
                    previewId: prior,
                    enabled: false,
                });
            });
            interactivePreviewIds.clear();
        }
        const turnOn = !wasLive;
        if (turnOn) {
            interactivePreviewIds.add(previewId);
            const img = card.querySelector(".image-container img");
            if (img)
                attachInteractiveInputHandlers(
                    card,
                    img,
                    interactiveInputConfig,
                );
        } else {
            interactivePreviewIds.delete(previewId);
        }
        vscode.postMessage({
            command: "setInteractive",
            previewId,
            enabled: turnOn,
        });
        applyLiveBadge();
        applyInteractiveButtonState();
        renderFocusInspector(card);
    }

    function toggleRecording() {
        if (!earlyFeatures()) return;
        if (filterToolbar.getLayoutValue() !== "focus") return;
        const card = getVisibleCards()[focusIndex];
        const previewId = card ? card.dataset.previewId : null;
        if (!card || !previewId) return;
        const turnOn = !recordingPreviewIds.has(previewId);
        if (turnOn) {
            recordingPreviewIds.forEach((prior) => {
                if (prior === previewId) return;
                vscode.postMessage({
                    command: "setRecording",
                    previewId: prior,
                    enabled: false,
                    format: recordingFormat.value,
                });
            });
            recordingPreviewIds.clear();
            recordingPreviewIds.add(previewId);
            const img = card.querySelector(".image-container img");
            if (img)
                attachInteractiveInputHandlers(
                    card,
                    img,
                    interactiveInputConfig,
                );
        } else {
            recordingPreviewIds.delete(previewId);
        }
        vscode.postMessage({
            command: "setRecording",
            previewId,
            enabled: turnOn,
            format: recordingFormat.value,
        });
        applyRecordingButtonState();
        renderFocusInspector(card);
    }

    /**
     * Single-click-to-LIVE entry point from the in-card image click handler. Ensures
     * image clicks land here in any layout (focus, grid, flow, column) — the focus-mode
     * LIVE button is a redundant affordance for the focus-mode user, this lets every layout
     * reach interactive mode in one click. Subsequent clicks while LIVE forward to the
     * daemon via the per-image handler in updateImage; that is a separate code path and
     * the click here short-circuits.
     */
    function enterInteractiveOnCard(card, shift) {
        setInteractiveForCard(card, shift);
    }

    // Pointer + wheel state machine for live previews lives in
    // `./interactiveInput.ts` — see `attachInteractiveInputHandlers`.

    // Document-level Left/Right in focus mode steps between cards. The
    // animated-carousel frame-controls handler stops propagation so
    // its arrow keys still walk captures within a single card. Skip
    // when an input-like element has focus (the layout dropdown,
    // future text inputs) so native keyboard semantics aren't stolen.
    document.addEventListener("keydown", (e) => {
        if (filterToolbar.getLayoutValue() !== "focus") return;
        if (e.key !== "ArrowLeft" && e.key !== "ArrowRight") return;
        const tag = e.target && e.target.tagName;
        if (tag === "INPUT" || tag === "SELECT" || tag === "TEXTAREA") return;
        navigateFocus(e.key === "ArrowLeft" ? -1 : 1);
        e.preventDefault();
    });

    filterToolbar.addEventListener("filter-changed", () => {
        saveFilterState();
        if (filterDebounce) clearTimeout(filterDebounce);
        filterDebounce = setTimeout(applyFilters, 100);
    });

    function saveFilterState() {
        state.filters = {
            fn: filterToolbar.getFunctionValue(),
            group: filterToolbar.getGroupValue(),
        };
        vscode.setState(state);
    }

    function restoreFilterState() {
        const f = state.filters || {};
        if (f.fn && filterToolbar.hasFunctionOption(f.fn))
            filterToolbar.setFunctionValue(f.fn);
        if (f.group && filterToolbar.hasGroupOption(f.group))
            filterToolbar.setGroupValue(f.group);
    }

    function applyFilters() {
        const visibleCount = grid.applyFilters({
            fn: filterToolbar.getFunctionValue(),
            group: filterToolbar.getGroupValue(),
        });

        // Only own the message when we have a filter-specific thing to
        // say. When there are no previews at all, the extension owns the
        // message (e.g. "No @Preview functions in this file") — clearing
        // it here was how the view went blank after a refresh.
        if (allPreviews.length > 0 && visibleCount === 0) {
            setMessage("No previews match the current filters", "filter");
        } else if (messageBanner.getOwner() === "filter") {
            // We set this earlier; clear it now that it no longer applies.
            setMessage("", "filter");
        }

        // Re-apply layout so focus mode updates correctly after filter change
        applyLayout();
    }

    // Thin shim around `<message-banner>.setMessage` that keeps the
    // ensureNotBlank() backstop wired in. The owner tag is used only to
    // let applyFilters clear its own message without touching extension-
    // set text (empty-file notice, build errors, etc.).
    function setMessage(text, owner) {
        messageBanner.setMessage(text, owner || "extension");
        ensureNotBlank();
    }

    // Safety net: if the grid ends up empty *and* no message is showing,
    // surface a placeholder so the user doesn't stare at a void. This
    // shouldn't normally trigger — the extension sends an explicit
    // message for every empty state — but a silent blank view was the
    // original complaint, so this catches any future regressions.
    function ensureNotBlank() {
        const hasCards = grid.querySelector(".preview-card") !== null;
        if (!hasCards && !messageBanner.isVisible()) {
            messageBanner.setMessage("Preparing previews…", "fallback");
        }
    }

    function getVisibleCards() {
        return grid.getVisibleCards();
    }

    function applyLayout() {
        const mode = filterToolbar.getLayoutValue();
        grid.setLayoutMode(mode);
        focusControls.hidden = mode !== "focus";

        if (mode === "focus") {
            const visible = getVisibleCards();
            if (visible.length === 0) {
                focusPosition.textContent = "0 / 0";
                renderFocusInspector(null);
                publishScopedPreview();
                return;
            }
            if (focusIndex >= visible.length) focusIndex = visible.length - 1;
            if (focusIndex < 0) focusIndex = 0;
            grid.applyFocusVisibility(visible[focusIndex]);
            focusPosition.textContent = focusIndex + 1 + " / " + visible.length;
            btnPrev.disabled = focusIndex === 0;
            btnNext.disabled = focusIndex === visible.length - 1;
            renderFocusInspector(visible[focusIndex]);
        } else {
            grid.applyFocusVisibility(null);
            renderFocusInspector(null);
        }
        document
            .querySelectorAll(".image-container")
            .forEach((c) => c.removeAttribute("title"));
        publishScopedPreview();
        // Single-target follow-focus: when there's exactly one live stream and the user
        // navigates off it, drop the stream so the LIVE chip follows the focused card.
        // Multi-target (size > 1) is treated as an explicit opt-in via Shift+click — those
        // streams persist across focus navigation until the user explicitly toggles them off
        // (or daemon dies, or the webview disposes).
        if (interactivePreviewIds.size === 1) {
            const visible = getVisibleCards();
            const card = mode === "focus" ? visible[focusIndex] : null;
            const lone = interactivePreviewIds.values().next().value;
            if (!card || card.dataset.previewId !== lone) {
                vscode.postMessage({
                    command: "setInteractive",
                    previewId: lone,
                    enabled: false,
                });
                interactivePreviewIds.clear();
                applyLiveBadge();
            }
        }
        // D2 — same teardown for the a11y overlay: navigating off the previewed card
        // (or exiting focus mode) unsubscribes so the wire stays quiet for cards the
        // user isn't looking at.
        if (a11yOverlay()) {
            const visible = getVisibleCards();
            const card = mode === "focus" ? visible[focusIndex] : null;
            if (!card || card.dataset.previewId !== a11yOverlay()) {
                if (earlyFeatures()) {
                    vscode.postMessage({
                        command: "setA11yOverlay",
                        previewId: a11yOverlay(),
                        enabled: false,
                    });
                }
                setA11yOverlay(null);
            }
        }
        applyInteractiveButtonState();
        applyRecordingButtonState();
        applyA11yOverlayButtonState();
        applyEarlyFeatureVisibility();
    }

    // Compute the focus-mode previewId. History is intentionally focus-only:
    // list/grid/filter layouts publish null even if only one card is visible.
    // Posts only when it changes so the extension does not rebuild history scope
    // on ordinary filter/layout churn.
    function publishScopedPreview() {
        const visible = getVisibleCards();
        let previewId = null;
        if (filterToolbar.getLayoutValue() === "focus") {
            if (
                visible.length > 0 &&
                focusIndex >= 0 &&
                focusIndex < visible.length
            ) {
                previewId = visible[focusIndex].dataset.previewId || null;
            }
        }
        if (previewId === lastScopedPreviewId) return;
        lastScopedPreviewId = previewId;
        // Mirror to the store so subscribed components (the upcoming
        // `<focus-controls>`, `<focus-inspector>`, etc.) react without
        // re-walking the DOM. Same value goes upstream to the extension
        // so the History panel can re-scope.
        previewStore.setState({ focusedPreviewId: previewId });
        vscode.postMessage({
            command: "previewScopeChanged",
            previewId,
        });
    }

    function navigateFocus(delta) {
        const visible = getVisibleCards();
        if (visible.length === 0) return;
        focusIndex = Math.max(
            0,
            Math.min(visible.length - 1, focusIndex + delta),
        );
        applyLayout();
    }

    // Switch the layout to focus mode and target the supplied card.
    // No-op when the card is filtered out (it wouldn't be in the visible
    // set anyway, and forcing focus on an invisible card surfaces an
    // empty pane).
    function focusOnCard(card) {
        const visible = getVisibleCards();
        const idx = visible.indexOf(card);
        if (idx === -1) return;
        focusIndex = idx;
        if (filterToolbar.getLayoutValue() !== "focus") {
            previousLayout = filterToolbar.getLayoutValue();
            filterToolbar.setLayoutValue("focus");
            state.layout = "focus";
            vscode.setState(state);
        }
        applyLayout();
    }

    function exitFocus() {
        if (filterToolbar.getLayoutValue() !== "focus") return;
        filterToolbar.setLayoutValue(previousLayout);
        state.layout = previousLayout;
        vscode.setState(state);
        applyLayout();
    }

    function renderFocusInspector(card) {
        focusInspector.innerHTML = "";
        focusInspector.hidden = !earlyFeatures() || !card;
        if (!earlyFeatures() || !card) return;
        const previewId = card.dataset.previewId;
        const p = allPreviews.find((pp) => pp.id === previewId);
        if (!previewId || !p) return;

        const inspect = document.createElement("section");
        inspect.className = "focus-panel focus-inspect-panel";
        inspect.appendChild(sectionHeader("search", "Inspect"));
        const findings =
            cardA11yFindings.get(previewId) || p.a11yFindings || [];
        const nodes = cardA11yNodes.get(previewId) || p.a11yNodes || [];
        inspect.appendChild(
            productPicker([
                {
                    icon: "eye",
                    label: "Accessibility",
                    value:
                        findings.length > 0
                            ? findings.length +
                              " finding" +
                              (findings.length === 1 ? "" : "s")
                            : "Overlay",
                    enabled: previewId === a11yOverlay(),
                    state: findings.length > 0 ? "warn" : "idle",
                    onToggle: () => toggleA11yOverlay(),
                },
                {
                    icon: "list-tree",
                    label: "Layout",
                    value:
                        nodes.length > 0
                            ? nodes.length +
                              " node" +
                              (nodes.length === 1 ? "" : "s")
                            : "Placeholder",
                    enabled: enabledFocusProducts.has("layout"),
                    state: nodes.length > 0 ? "ok" : "idle",
                    onToggle: () => toggleFocusProduct("layout"),
                },
                productSpec("symbol-string", "Strings", "strings"),
                productSpec("file-code", "Resources", "resources"),
                productSpec("text-size", "Fonts", "fonts"),
                productSpec("pulse", "Render", "render"),
                productSpec("symbol-color", "Theme", "theme"),
                {
                    icon: "sync",
                    label: "Recomposition",
                    value: interactivePreviewIds.has(previewId)
                        ? "Live"
                        : "Placeholder",
                    enabled:
                        enabledFocusProducts.has("recomposition") ||
                        interactivePreviewIds.has(previewId),
                    state: interactivePreviewIds.has(previewId) ? "ok" : "idle",
                    onToggle: () => toggleFocusProduct("recomposition"),
                },
            ]),
        );
        const controls = document.createElement("section");
        controls.className = "focus-panel focus-controls-panel";
        controls.appendChild(sectionHeader("settings-gear", "Tools"));
        const toolActions = document.createElement("div");
        toolActions.className = "focus-actions";
        toolActions.appendChild(
            actionButton("eye", "A11y", "Toggle accessibility overlay", () => {
                toggleA11yOverlay();
            }),
        );
        toolActions.appendChild(
            actionButton(
                "device-mobile",
                "Device",
                "Launch on connected Android device",
                () => {
                    requestLaunchOnDevice();
                },
            ),
        );
        toolActions.appendChild(
            actionButton(
                "circle-large-outline",
                "Live",
                "Toggle live preview",
                () => {
                    toggleInteractive(false);
                },
            ),
        );
        toolActions.appendChild(
            actionButton(
                "record-keys",
                "Record",
                "Record focused preview",
                () => {
                    toggleRecording();
                },
            ),
        );
        controls.appendChild(toolActions);
        focusInspector.appendChild(inspect);
        focusInspector.appendChild(historyPanel());
        focusInspector.appendChild(controls);
        const placeholders = buildFocusPlaceholders();
        if (placeholders) inspect.appendChild(placeholders);
    }

    function sectionHeader(icon, label) {
        const header = document.createElement("div");
        header.className = "focus-panel-header";
        header.innerHTML =
            '<i class="codicon codicon-' + icon + '" aria-hidden="true"></i>';
        const span = document.createElement("span");
        span.textContent = label;
        header.appendChild(span);
        return header;
    }

    function actionButton(icon, label, title, onClick) {
        const btn = document.createElement("button");
        btn.type = "button";
        btn.className = "focus-action";
        btn.title = title;
        btn.innerHTML =
            '<i class="codicon codicon-' + icon + '" aria-hidden="true"></i>';
        const span = document.createElement("span");
        span.textContent = label;
        btn.appendChild(span);
        btn.addEventListener("click", onClick);
        return btn;
    }

    function historyPanel() {
        const history = document.createElement("details");
        history.className = "focus-panel focus-history-panel";
        history.open = focusHistoryOpen;
        history.addEventListener("toggle", () => {
            focusHistoryOpen = history.open;
        });
        const summary = document.createElement("summary");
        summary.className = "focus-panel-header focus-history-summary";
        summary.innerHTML =
            '<i class="codicon codicon-history" aria-hidden="true"></i>';
        const label = document.createElement("span");
        label.textContent = "History";
        summary.appendChild(label);
        const chevron = document.createElement("i");
        chevron.className =
            "codicon codicon-chevron-down focus-summary-chevron";
        chevron.setAttribute("aria-hidden", "true");
        summary.appendChild(chevron);
        history.appendChild(summary);
        const historyActions = document.createElement("div");
        historyActions.className = "focus-actions";
        historyActions.appendChild(
            actionButton(
                "git-compare",
                "HEAD",
                "Diff vs last archived render",
                () => {
                    requestFocusedDiff("head");
                },
            ),
        );
        historyActions.appendChild(
            actionButton(
                "source-control",
                "main",
                "Diff vs latest archived main render",
                () => {
                    requestFocusedDiff("main");
                },
            ),
        );
        history.appendChild(historyActions);
        return history;
    }

    function productSpec(icon, label, id) {
        return {
            icon,
            label,
            value: "Placeholder",
            enabled: enabledFocusProducts.has(id),
            state: "idle",
            onToggle: () => toggleFocusProduct(id),
        };
    }

    function productPicker(products) {
        const picker = document.createElement("details");
        picker.className = "focus-product-picker";
        picker.open = focusProductPickerOpen;
        picker.addEventListener("toggle", () => {
            focusProductPickerOpen = picker.open;
        });
        const summary = document.createElement("summary");
        summary.className = "focus-product-summary";
        const selected = products.filter((product) => product.enabled);
        const summaryText = document.createElement("span");
        summaryText.textContent =
            selected.length === 0
                ? "Choose inspection layers"
                : selected.length === 1
                  ? selected[0].label
                  : selected.length + " layers selected";
        summary.appendChild(summaryText);
        const chevron = document.createElement("i");
        chevron.className =
            "codicon codicon-chevron-down focus-summary-chevron";
        chevron.setAttribute("aria-hidden", "true");
        summary.appendChild(chevron);
        picker.appendChild(summary);

        const menu = document.createElement("div");
        menu.className = "focus-product-menu";
        products.forEach((product) => {
            menu.appendChild(productOption(product));
        });
        picker.appendChild(menu);
        return picker;
    }

    function productOption(product) {
        const option = document.createElement("label");
        option.className = "focus-product-option";
        option.dataset.state = product.state || "idle";
        const input = document.createElement("input");
        input.type = "checkbox";
        input.checked = product.enabled;
        input.addEventListener("change", product.onToggle);
        option.appendChild(input);
        const icon = document.createElement("i");
        icon.className = "codicon codicon-" + product.icon;
        icon.setAttribute("aria-hidden", "true");
        option.appendChild(icon);
        const text = document.createElement("div");
        text.className = "focus-product-text";
        const name = document.createElement("span");
        name.className = "focus-product-name";
        name.textContent = product.label;
        const val = document.createElement("span");
        val.className = "focus-product-value";
        val.textContent = product.value;
        text.appendChild(name);
        text.appendChild(val);
        option.appendChild(text);
        return option;
    }

    function toggleFocusProduct(id) {
        if (enabledFocusProducts.has(id)) {
            enabledFocusProducts.delete(id);
        } else {
            enabledFocusProducts.add(id);
        }
        const card = getVisibleCards()[focusIndex];
        if (filterToolbar.getLayoutValue() === "focus" && card)
            renderFocusInspector(card);
    }

    function buildFocusPlaceholders() {
        const defs = [
            ["layout", "Layout", "layout tree and bounds"],
            ["strings", "Strings", "text/strings and i18n/translations"],
            ["resources", "Resources", "resources/used"],
            ["fonts", "Fonts", "fonts/used"],
            ["render", "Render", "render/trace and render/composeAiTrace"],
            ["theme", "Theme", "compose/theme"],
            ["recomposition", "Recomposition", "compose/recomposition"],
        ].filter(([id]) => enabledFocusProducts.has(id));
        if (defs.length === 0) return null;
        const wrapper = document.createElement("div");
        wrapper.className = "focus-placeholder-list";
        defs.forEach(([id, label, kind]) => {
            const details = document.createElement("details");
            details.className = "focus-placeholder";
            details.dataset.product = id;
            const summary = document.createElement("summary");
            summary.textContent = label;
            details.appendChild(summary);
            const body = document.createElement("div");
            body.className = "focus-placeholder-body";
            body.textContent = kind;
            details.appendChild(body);
            wrapper.appendChild(details);
        });
        return wrapper;
    }

    function renderSummary(p) {
        const captures = p.captures ? p.captures.length : 0;
        if (captures > 1) return captures + " captures";
        const c = p.captures && p.captures[0];
        if (c && c.label) return c.label;
        return "Static";
    }

    function themeSummary(p) {
        if (p.params.backgroundColor) return "Background";
        return p.params.showSystemUi ? "System UI" : "Preview";
    }

    // Live-panel diff: only meaningful when one preview is focused. Pulls
    // the currently focused card's previewId and asks the extension to
    // resolve the comparison anchor (HEAD = latest archived render,
    // main = latest archived render on the main branch).
    function requestFocusedDiff(against) {
        if (!earlyFeatures()) return;
        if (filterToolbar.getLayoutValue() !== "focus") return;
        const visible = getVisibleCards();
        const card = visible[focusIndex];
        if (!card) return;
        const previewId = card.dataset.previewId;
        if (!previewId) return;
        showDiffOverlay(card, against, null, null, diffOverlayConfig);
        vscode.postMessage({
            command: "requestPreviewDiff",
            previewId,
            against,
        });
    }

    // Live-panel "Launch on Device": runs the consumer's
    // installDebug task and uses adb to start the launcher activity on
    // a connected device. Only meaningful when one preview is focused
    // -- the extension uses the focused previewId to pick the owning
    // module before falling back to a quick-pick.
    function requestLaunchOnDevice() {
        if (!earlyFeatures()) return;
        if (filterToolbar.getLayoutValue() !== "focus") return;
        const visible = getVisibleCards();
        const card = visible[focusIndex];
        if (!card) return;
        const previewId = card.dataset.previewId;
        if (!previewId) return;
        vscode.postMessage({ command: "requestLaunchOnDevice", previewId });
    }

    // populateFilter / hasOption are gone — `<filter-toolbar>` owns the
    // option lists via setFunctionOptions / setGroupOptions and exposes
    // hasFunctionOption / hasGroupOption for membership tests. The
    // current selected value is preserved across reseeds because
    // `<filter-toolbar>`'s reactive state retains `fnValue` / `grpValue`
    // when only `fnOptions` / `grpOptions` change.

    // Per-preview carousel runtime state — imageData / errorMessage per
    // capture. Populated from updateImage / setImageError messages so
    // prev/next navigation can swap the visible <img> without a fresh
    // extension round-trip.
    // Map<previewId, [{ label, imageData, errorMessage }]>
    const cardCaptures = new Map();

    function createCard(p) {
        const animated = isAnimatedPreview(p);
        const captures = p.captures;

        const card = document.createElement("div");
        card.className = "preview-card" + (animated ? " animated-card" : "");
        card.id = "preview-" + sanitizeId(p.id);
        card.setAttribute("role", "listitem");
        card.dataset.function = p.functionName;
        card.dataset.group = p.params.group || "";
        card.dataset.previewId = p.id;
        card.dataset.className = p.className;
        card.dataset.wearPreview = isWearPreview(p) ? "1" : "0";
        card.dataset.currentIndex = "0";
        cardCaptures.set(
            p.id,
            captures.map((c) => ({
                label: c.label || "",
                renderOutput: c.renderOutput || "",
                imageData: null,
                errorMessage: null,
                renderError: null,
            })),
        );

        const header = document.createElement("div");
        header.className = "card-header";

        const titleRow = document.createElement("div");
        titleRow.className = "card-title-row";

        const title = document.createElement("button");
        title.className = "card-title";
        title.textContent =
            p.functionName + (p.params.name ? " — " + p.params.name : "");
        title.title = buildTooltip(p);
        title.addEventListener("click", () => {
            vscode.postMessage({
                command: "openFile",
                className: p.className,
                functionName: p.functionName,
            });
        });
        titleRow.appendChild(title);

        if (animated) {
            // Inline marker so the title row telegraphs "this one has
            // multiple captures"; the carousel strip under the image is
            // the interactive surface.
            const icon = document.createElement("i");
            icon.className = "codicon codicon-play-circle animation-icon";
            icon.title = captures.length + " captures";
            icon.setAttribute(
                "aria-label",
                "Animated preview (" + captures.length + " captures)",
            );
            titleRow.appendChild(icon);
        }

        // Per-card focus icon. Replaces the previous "double-click image"
        // affordance — single-click on the image is now reserved for
        // entering LIVE (interactive) mode, so we need an explicit handle
        // for "view this card by itself". Same hot zone toggles between
        // enter-focus (other layouts) and exit-focus (focus layout).
        const focusBtn = document.createElement("button");
        focusBtn.type = "button";
        focusBtn.className = "card-focus-btn";
        focusBtn.innerHTML =
            '<i class="codicon codicon-screen-full" aria-hidden="true"></i>';
        focusBtn.title = "Focus this preview";
        focusBtn.setAttribute("aria-label", "Focus this preview");
        focusBtn.addEventListener("click", (evt) => {
            evt.stopPropagation();
            if (filterToolbar.getLayoutValue() === "focus") {
                exitFocus();
            } else {
                focusOnCard(card);
            }
        });
        titleRow.appendChild(focusBtn);

        // Stale-tier refresh button — only attached up front for cards
        // already known to be stale at setPreviews time. updateStaleBadges
        // also adds/removes it on subsequent renders. Placed before the
        // header is appended so its DOM order stays predictable.
        staleBadge.apply(card, false);

        header.appendChild(titleRow);
        card.appendChild(header);

        const imgContainer = document.createElement("div");
        imgContainer.className = "image-container";
        const skeleton = document.createElement("div");
        skeleton.className = "skeleton";
        skeleton.setAttribute("aria-label", "Loading preview");
        imgContainer.appendChild(skeleton);
        card.appendChild(imgContainer);

        // Single-click on the image enters LIVE for this preview (in any
        // layout — focus, grid, flow, column). The first click toggles
        // interactive on; subsequent clicks while LIVE forward as pointer
        // events to the daemon (handled by attachInteractiveInputHandlers
        // attached via updateImage). The handler is on the container, not
        // the <img>, so clicks land before the image renders too. Modifier-
        // aware: Shift+click follows the multi-stream semantics from
        // toggleInteractive().
        imgContainer.addEventListener("click", (evt) => {
            const previewId = card.dataset.previewId;
            if (!previewId) return;
            // If we're already live for this preview, the per-image click
            // handler routes to recordInteractiveInput. Check before the
            // stale-card branch so interactive clicks do not also queue a
            // heavyweight refresh for stale captures.
            if (interactivePreviewIds.has(previewId)) return;
            if (card.classList.contains("is-stale")) {
                evt.preventDefault();
                evt.stopPropagation();
                staleBadge.requestHeavyRefresh(card);
                return;
            }
            enterInteractiveOnCard(card, evt.shiftKey);
        });

        // ATF legend + overlay layer — rendered in the webview (not
        // baked into the PNG) so rows stay interactive: hovering a
        // finding highlights its bounds on the clean image. Populated
        // only when findings exist AND `composePreview.earlyFeatures`
        // is on; the overlay layer's boxes get computed lazily once
        // the image is loaded (see buildA11yOverlay).
        if (earlyFeatures() && p.a11yFindings && p.a11yFindings.length > 0) {
            const overlay = document.createElement("div");
            overlay.className = "a11y-overlay";
            overlay.setAttribute("aria-hidden", "true");
            imgContainer.appendChild(overlay);
            card.appendChild(buildA11yLegend(card, p));
        }

        const variantLabel = buildVariantLabel(p);
        if (variantLabel) {
            const badge = document.createElement("div");
            badge.className = "variant-badge";
            badge.textContent = variantLabel;
            card.appendChild(badge);
        }

        if (animated) {
            card.appendChild(buildFrameControls(card));
        }

        observeCardForViewport(card);
        return card;
    }

    function buildFrameControls(card) {
        const bar = document.createElement("div");
        bar.className = "frame-controls";

        const prev = document.createElement("button");
        prev.className = "icon-button frame-prev";
        prev.setAttribute("aria-label", "Previous capture");
        prev.title = "Previous capture";
        prev.innerHTML =
            '<i class="codicon codicon-chevron-left" aria-hidden="true"></i>';
        prev.addEventListener("click", () => stepFrame(card, -1));

        const indicator = document.createElement("span");
        indicator.className = "frame-indicator";
        indicator.setAttribute("aria-live", "polite");

        const next = document.createElement("button");
        next.className = "icon-button frame-next";
        next.setAttribute("aria-label", "Next capture");
        next.title = "Next capture";
        next.innerHTML =
            '<i class="codicon codicon-chevron-right" aria-hidden="true"></i>';
        next.addEventListener("click", () => stepFrame(card, 1));

        bar.appendChild(prev);
        bar.appendChild(indicator);
        bar.appendChild(next);

        // Arrow keys when the carousel has focus. Stop propagation so
        // the document-level focus-mode nav doesn't also advance the card.
        bar.tabIndex = 0;
        bar.addEventListener("keydown", (e) => {
            if (e.key === "ArrowLeft") {
                stepFrame(card, -1);
                e.preventDefault();
                e.stopPropagation();
            } else if (e.key === "ArrowRight") {
                stepFrame(card, 1);
                e.preventDefault();
                e.stopPropagation();
            }
        });

        // Seed indicator text so it's not blank before any image arrives.
        requestAnimationFrame(() => updateFrameIndicator(card));
        return bar;
    }

    function stepFrame(card, delta) {
        const caps = cardCaptures.get(card.dataset.previewId);
        if (!caps) return;
        const cur = parseInt(card.dataset.currentIndex || "0", 10);
        const next = Math.max(0, Math.min(caps.length - 1, cur + delta));
        if (next === cur) return;
        card.dataset.currentIndex = String(next);
        showFrame(card, next);
    }

    function showFrame(card, index) {
        const caps = cardCaptures.get(card.dataset.previewId);
        if (!caps) return;
        const capture = caps[index];
        if (!capture) return;
        const container = card.querySelector(".image-container");
        if (!container) return;

        if (capture.imageData) {
            const skeleton = container.querySelector(".skeleton");
            const errorMsg = container.querySelector(".error-message");
            if (skeleton) skeleton.remove();
            if (errorMsg) errorMsg.remove();
            let img = container.querySelector("img");
            if (!img) {
                img = document.createElement("img");
                img.alt = card.dataset.function + " preview";
                container.appendChild(img);
            }
            img.src =
                "data:" +
                mimeFor(capture.renderOutput) +
                ";base64," +
                capture.imageData;
            img.className = "fade-in";
            attachInteractiveInputHandlers(card, img, interactiveInputConfig);
            if (capture.errorMessage || capture.renderError) {
                container.appendChild(
                    buildErrorPanel(
                        vscode,
                        capture.errorMessage,
                        capture.renderError,
                        card.dataset.className,
                    ),
                );
                card.classList.add("has-error");
            } else {
                card.classList.remove("has-error");
            }
        } else if (capture.errorMessage || capture.renderError) {
            const existingErr = container.querySelector(".error-message");
            if (existingErr) existingErr.remove();
            container.appendChild(
                buildErrorPanel(
                    capture.errorMessage,
                    capture.renderError,
                    card.dataset.className,
                ),
            );
            card.classList.add("has-error");
        } else {
            // No data for this capture yet — render will fill it in later.
            const existing = container.querySelector("img");
            if (existing) existing.remove();
            if (!container.querySelector(".skeleton")) {
                const s = document.createElement("div");
                s.className = "skeleton";
                s.setAttribute("aria-label", "Loading capture");
                container.appendChild(s);
            }
        }
        updateFrameIndicator(card);
    }

    function updateFrameIndicator(card) {
        const indicator = card.querySelector(".frame-indicator");
        const prevBtn = card.querySelector(".frame-prev");
        const nextBtn = card.querySelector(".frame-next");
        if (!indicator) return;
        const caps = cardCaptures.get(card.dataset.previewId);
        if (!caps) return;
        const idx = parseInt(card.dataset.currentIndex || "0", 10);
        const capture = caps[idx];
        const label = capture && capture.label ? capture.label : "\u2014";
        indicator.textContent =
            idx + 1 + " / " + caps.length + " \u00B7 " + label;
        if (prevBtn) prevBtn.disabled = idx === 0;
        if (nextBtn) nextBtn.disabled = idx === caps.length - 1;
    }

    function updateCardMetadata(card, p) {
        card.dataset.function = p.functionName;
        card.dataset.group = p.params.group || "";
        card.dataset.wearPreview = isWearPreview(p) ? "1" : "0";
        const title = card.querySelector(".card-title");
        if (title) {
            title.textContent =
                p.functionName + (p.params.name ? " — " + p.params.name : "");
            title.title = buildTooltip(p);
        }
        // Refresh capture labels in place. If the capture count changed
        // (e.g. user edited @RoboComposePreviewOptions) we preserve
        // already-received imageData for renderOutputs that carry over.
        const newCaps = p.captures.map((c) => ({
            renderOutput: c.renderOutput,
            label: c.label || "",
        }));
        const prior = cardCaptures.get(p.id) || [];
        // Match by index rather than renderOutput since filenames may
        // legitimately change (e.g. a preview gains a @RoboComposePreviewOptions
        // annotation). Mismatched positions just reset to null-image.
        const mergedCaps = newCaps.map((nc, i) => ({
            label: nc.label,
            renderOutput: nc.renderOutput || "",
            imageData: prior[i]?.imageData ?? null,
            errorMessage: prior[i]?.errorMessage ?? null,
            renderError: prior[i]?.renderError ?? null,
        }));
        cardCaptures.set(p.id, mergedCaps);
        const curIdx = parseInt(card.dataset.currentIndex || "0", 10);
        if (curIdx >= mergedCaps.length) {
            card.dataset.currentIndex = String(
                Math.max(0, mergedCaps.length - 1),
            );
        }
        if (isAnimatedPreview(p)) updateFrameIndicator(card);
        const variantLabel = buildVariantLabel(p);
        let badge = card.querySelector(".variant-badge");
        if (variantLabel) {
            if (!badge) {
                badge = document.createElement("div");
                badge.className = "variant-badge";
                card.appendChild(badge);
            }
            badge.textContent = variantLabel;
        } else if (badge) {
            badge.remove();
        }

        // Refresh the a11y legend + overlay in place when findings
        // change (e.g. toggling a11y on turns findings from null → list,
        // or a fresh render updates the set). Tear down the old nodes
        // and rebuild: simpler than reconciling row-by-row for what is
        // a rare event.
        const existingLegend = card.querySelector(".a11y-legend");
        const existingOverlay = card.querySelector(".a11y-overlay");
        if (existingLegend) existingLegend.remove();
        if (existingOverlay) existingOverlay.innerHTML = "";
        if (earlyFeatures() && p.a11yFindings && p.a11yFindings.length > 0) {
            const container = card.querySelector(".image-container");
            if (container && !container.querySelector(".a11y-overlay")) {
                const overlay = document.createElement("div");
                overlay.className = "a11y-overlay";
                overlay.setAttribute("aria-hidden", "true");
                container.appendChild(overlay);
            }
            const legend = buildA11yLegend(card, p);
            card.appendChild(legend);
            // Repopulate box geometry if the image is already loaded —
            // otherwise updateImage's load handler will pick it up on
            // the next render cycle.
            const img = card.querySelector(".image-container img");
            if (img && img.complete && img.naturalWidth > 0) {
                buildA11yOverlay(card, p.a11yFindings, img);
            }
        } else if (existingOverlay) {
            // No findings or feature off — drop any leftover overlay
            // div so cards stay clean when the user toggles
            // earlyFeatures off mid-session.
            existingOverlay.remove();
        }
    }

    // Scale image containers so preview variants at different device sizes
    // (e.g. wearos_large_round 227dp vs wearos_small_round 192dp) render at
    // relative sizes in fixed-layout modes. Only applied when we have real
    // widthDp/heightDp — variants without known dimensions fall back to
    // the default CSS (full card width, auto aspect).
    function applyRelativeSizing(previews) {
        const widths = previews
            .map((p) => p.params.widthDp || 0)
            .filter((w) => w > 0);
        const maxW = widths.length > 0 ? Math.max.apply(null, widths) : 0;
        for (const p of previews) {
            const card = document.getElementById("preview-" + sanitizeId(p.id));
            if (!card) continue;
            const w = p.params.widthDp;
            const h = p.params.heightDp;
            if (w && h && maxW > 0) {
                card.style.setProperty("--size-ratio", (w / maxW).toFixed(4));
                card.style.setProperty("--aspect-ratio", w + " / " + h);
            } else {
                card.style.removeProperty("--size-ratio");
                card.style.removeProperty("--aspect-ratio");
            }
        }
    }

    /**
     * Incremental diff: update existing cards, add new ones, remove missing.
     * Keeps rendered images in place during refresh — they're replaced as
     * new images stream in from updateImage messages.
     */
    function renderPreviews(previews) {
        if (previews.length === 0) {
            // Defensive fallback — the extension now always sends an
            // explicit showMessage for empty states, so this branch
            // shouldn't normally fire. Kept so the view never ends up
            // with an empty grid + empty message if a bug slips through.
            grid.innerHTML = "";
            setMessage("No @Preview functions found", "empty");
            return;
        }
        const newIds = new Set(previews.map((p) => p.id));
        const existingCards = new Map();
        grid.querySelectorAll(".preview-card").forEach((card) => {
            existingCards.set(card.dataset.previewId, card);
        });

        // Remove cards that no longer exist — drop their cached capture
        // data so stale entries don't pile up if a preview is renamed.
        for (const [id, card] of existingCards) {
            if (!newIds.has(id)) {
                cardCaptures.delete(id);
                viewport.forget(id, card);
                card.remove();
            }
        }

        // Refresh per-preview findings cache so updateImage can attach
        // them to each new image load. Drop stale entries (preview
        // removed) so the map doesn't grow across sessions.
        cardA11yFindings.clear();
        for (const p of previews) {
            if (p.a11yFindings && p.a11yFindings.length > 0) {
                cardA11yFindings.set(p.id, p.a11yFindings);
            }
        }

        // Add new cards / update existing ones, preserving order
        let lastInsertedCard = null;
        for (const p of previews) {
            const existing = existingCards.get(p.id);
            if (existing) {
                updateCardMetadata(existing, p);
                // Ensure correct position
                if (lastInsertedCard) {
                    if (lastInsertedCard.nextSibling !== existing) {
                        grid.insertBefore(
                            existing,
                            lastInsertedCard.nextSibling,
                        );
                    }
                } else if (grid.firstChild !== existing) {
                    grid.insertBefore(existing, grid.firstChild);
                }
                lastInsertedCard = existing;
            } else {
                const card = createCard(p);
                if (lastInsertedCard) {
                    grid.insertBefore(card, lastInsertedCard.nextSibling);
                } else {
                    grid.insertBefore(card, grid.firstChild);
                }
                lastInsertedCard = card;
            }
        }

        // Clear transient owner messages now that cards are in the DOM.
        // The 'loading' Building… banner and the 'fallback' "Preparing
        // previews…" placeholder both get cleared here. 'extension'-owned
        // messages (build errors, empty-state notices) are left alone —
        // those are terminal states the extension is asserting and the
        // caller wouldn't be sending setPreviews alongside them anyway.
        //
        // Must run *after* cards are inserted: setMessage('', …) calls
        // ensureNotBlank, which would re-set "Preparing previews…" if
        // the grid still looked empty when the message was cleared.
        const owner = messageBanner.getOwner();
        if (owner && owner !== "extension") {
            setMessage("", owner);
        }
    }

    function updateImage(previewId, captureIndex, imageData) {
        const card = document.getElementById(
            "preview-" + sanitizeId(previewId),
        );
        if (!card) return;

        // Cache so carousel navigation can restore this capture without
        // a fresh extension round-trip.
        const caps = cardCaptures.get(previewId);
        if (caps && caps[captureIndex]) {
            caps[captureIndex].imageData = imageData;
            caps[captureIndex].errorMessage = null;
            caps[captureIndex].renderError = null;
        }

        // Only paint the <img> if the currently-displayed capture is the
        // one that just arrived. Otherwise the cached bytes wait for
        // prev/next.
        const cur = parseInt(card.dataset.currentIndex || "0", 10);
        if (cur !== captureIndex) {
            if (caps) updateFrameIndicator(card);
            return;
        }

        const container = card.querySelector(".image-container");
        // Tear down every prior state before showing the new image.
        // Leftover .error-message divs here are what caused the
        // "Render pending — save the file to trigger a render" banner
        // to stay visible forever even after a successful render.
        const skeleton = container.querySelector(".skeleton");
        const overlay = container.querySelector(".loading-overlay");
        const errorMsg = container.querySelector(".error-message");
        if (skeleton) skeleton.remove();
        if (overlay) overlay.remove();
        if (errorMsg) errorMsg.remove();
        card.classList.remove("has-error");

        const ro =
            caps && caps[captureIndex] ? caps[captureIndex].renderOutput : "";
        const newSrc = "data:" + mimeFor(ro) + ";base64," + imageData;

        let img = container.querySelector("img");
        if (!img) {
            img = document.createElement("img");
            img.alt = card.dataset.function + " preview";
            container.appendChild(img);
        }
        img.src = newSrc;
        // In live mode the new bytes are a frame, not a card reload —
        // skip the fade-in so successive frames read as a stream rather
        // than a sequence of independent renders. See INTERACTIVE.md § 3.
        const isLive = interactivePreviewIds.has(previewId);
        img.className = isLive ? "live-frame" : "fade-in";
        attachInteractiveInputHandlers(card, img, interactiveInputConfig);

        if (caps) updateFrameIndicator(card);

        // If a diff overlay is open on this card and uses the live render
        // as its left anchor (head / main / current), the bytes the
        // overlay is showing just went stale. Re-issue so the user sees
        // the new render without clicking — symmetric with the
        // compose-preview/main ref watcher's auto-refresh on the right anchor.
        const openDiff = container.querySelector(".preview-diff-overlay");
        if (earlyFeatures() && openDiff) {
            const against = openDiff.dataset.against;
            if (against === "head" || against === "main") {
                showDiffOverlay(card, against, null, null, diffOverlayConfig);
                vscode.postMessage({
                    command: "requestPreviewDiff",
                    previewId,
                    against,
                });
            }
        }

        // Re-build the a11y overlay once the image natural dimensions
        // are known. Data-URL srcs may resolve synchronously; in that
        // case img.complete is true and load will not fire, so we
        // check both. Findings are stashed at setPreviews time via the
        // renderPreviews pipeline. Gated on earlyFeatures so the
        // overlay only paints when the user has opted into the
        // accessibility-overlay feature surface.
        const findings = cardA11yFindings.get(previewId);
        const nodes = cardA11yNodes.get(previewId);
        if (
            earlyFeatures() &&
            ((findings && findings.length > 0) || (nodes && nodes.length > 0))
        ) {
            const apply = () => {
                if (findings && findings.length > 0)
                    buildA11yOverlay(card, findings, img);
                if (nodes && nodes.length > 0)
                    applyHierarchyOverlay(card, nodes, img);
            };
            if (img.complete && img.naturalWidth > 0) {
                apply();
            } else {
                img.addEventListener("load", apply, { once: true });
            }
        }
    }

    // previewId -> findings. Populated from setPreviews so updateImage can
    // re-read the list on every image (re)load without re-querying the
    // DOM for data attributes.
    const cardA11yFindings = new Map();

    // D2 — previewId -> nodes for the daemon-attached a11y/hierarchy payload. Drives
    // the local hierarchy overlay (translucent rectangles + label/role/states tooltip
    // on hover) drawn on top of the existing finding overlay. Populated by
    // applyA11yUpdate and re-read on each image (re)load via applyHierarchyOverlay.
    const cardA11yNodes = new Map();

    // D2 — handles updateA11y from the extension (daemon-attached a11y data products).
    // Updates the per-preview caches and re-applies whichever overlays are now relevant
    // without rebuilding the whole card. Findings -> legend + finding overlay; nodes ->
    // hierarchy overlay. Either argument may be omitted to leave that side untouched.
    // Gated on earlyFeatures so daemon-attached a11y data is dropped silently when the
    // user has not opted into the accessibility-overlay feature surface.
    function applyA11yUpdate(previewId, findings, nodes) {
        if (!earlyFeatures()) return;
        const card = document.getElementById(
            "preview-" + sanitizeId(previewId),
        );
        if (!card) return;
        const container = card.querySelector(".image-container");
        const img = container && container.querySelector("img");
        if (findings !== undefined) {
            if (findings && findings.length > 0) {
                cardA11yFindings.set(previewId, findings);
                if (container && !container.querySelector(".a11y-overlay")) {
                    const overlay = document.createElement("div");
                    overlay.className = "a11y-overlay";
                    overlay.setAttribute("aria-hidden", "true");
                    container.appendChild(overlay);
                }
                const existingLegend = card.querySelector(".a11y-legend");
                if (existingLegend) existingLegend.remove();
                const p = allPreviews.find((pp) => pp.id === previewId);
                if (p) {
                    p.a11yFindings = findings;
                    card.appendChild(buildA11yLegend(card, p));
                }
                if (img && img.complete && img.naturalWidth > 0) {
                    buildA11yOverlay(card, findings, img);
                }
            } else {
                cardA11yFindings.delete(previewId);
                const overlay = card.querySelector(".a11y-overlay");
                if (overlay) overlay.remove();
                const legend = card.querySelector(".a11y-legend");
                if (legend) legend.remove();
            }
        }
        if (nodes !== undefined) {
            if (nodes && nodes.length > 0) {
                cardA11yNodes.set(previewId, nodes);
                ensureHierarchyOverlay(container);
                if (img && img.complete && img.naturalWidth > 0) {
                    applyHierarchyOverlay(card, nodes, img);
                }
            } else {
                cardA11yNodes.delete(previewId);
                const layer = card.querySelector(".a11y-hierarchy-overlay");
                if (layer) layer.remove();
            }
        }
        if (filterToolbar.getLayoutValue() === "focus") {
            const focused = getVisibleCards()[focusIndex];
            if (focused === card) renderFocusInspector(card);
        }
    }

    // ----- Viewport tracking (daemon scroll-ahead, PREDICTIVE.md § 7) -----
    // The actual machinery lives in `./viewportTracker.ts`. The auto-stop-
    // interactive-on-scroll-out rule stays here because the live set lives
    // here; the tracker just notifies us via `onCardLeftViewport`.
    const viewport = new ViewportTracker({
        vscode,
        onCardLeftViewport: (id) => {
            if (!interactivePreviewIds.has(id)) return;
            interactivePreviewIds.delete(id);
            vscode.postMessage({
                command: "setInteractive",
                previewId: id,
                enabled: false,
            });
            applyLiveBadge();
            applyInteractiveButtonState();
        },
    });

    function observeCardForViewport(card) {
        viewport.observe(card);
    }

    window.addEventListener("message", (event) => {
        const msg = event.data;
        switch (msg.command) {
            case "setPreviews": {
                allPreviews = msg.previews;
                moduleDir = msg.moduleDir;
                renderPreviews(msg.previews);
                applyRelativeSizing(msg.previews);
                // Stale-tier badges depend on the latest render's tier
                // (sent from the extension as heavyStaleIds). Apply
                // *after* renderPreviews so the badge attaches to cards
                // that were just inserted, not stripped by a stale-state
                // diff from the previous setPreviews.
                staleBadge.updateAll(grid, msg.heavyStaleIds);

                const fns = [
                    ...new Set(msg.previews.map((p) => p.functionName)),
                ].sort();
                const groups = [
                    ...new Set(
                        msg.previews.map((p) => p.params.group).filter(Boolean),
                    ),
                ].sort();

                filterToolbar.setFunctionOptions(fns);
                filterToolbar.setGroupOptions(groups);

                restoreFilterState();
                applyFilters();
                applyLayout();
                // setPreviews can rebuild the focused card from scratch;
                // re-stamp the live badge so the LIVE chip reattaches to
                // the right card(s). Drop any live previewIds that are
                // gone from the new manifest — silent cleanup; we don't
                // bother sending interactive/stop because the preview no
                // longer exists for the daemon to dispatch into anyway.
                const newIds = new Set(msg.previews.map((p) => p.id));
                interactivePreviewIds.forEach((id) => {
                    if (!newIds.has(id)) interactivePreviewIds.delete(id);
                });
                applyLiveBadge();
                applyInteractiveButtonState();
                // Tell the extension the cards reached the grid. Powers the
                // e2e test's "real webview consumed setPreviews" assertion —
                // postedMessageLog alone only proves the host posted the
                // message, not that a resolved webview ever received it.
                vscode.postMessage({
                    command: "webviewPreviewsRendered",
                    count: grid.querySelectorAll(".preview-card").length,
                });
                break;
            }

            case "markAllLoading":
                loadingOverlay.markAll();
                break;

            case "clearAll":
                allPreviews = [];
                grid.innerHTML = "";
                // Reset so the next setPreviews can re-publish the
                // narrowed-preview scope if applicable — otherwise a
                // stale id from the previous module would dedupe the
                // first publish and the History panel would miss it.
                lastScopedPreviewId = null;
                previewStore.setState({ focusedPreviewId: null });
                // Cards are gone — escalation timer has nothing left to
                // promote. Avoids a stray timer firing after the next
                // refresh has installed fresh minimal overlays.
                loadingOverlay.cancel();
                // Don't clear the message here — if it came with a
                // follow-up showMessage (the usual pattern) it'll be
                // replaced; if not, ensureNotBlank will backstop a
                // placeholder so the view never ends up empty+silent.
                ensureNotBlank();
                break;

            case "updateImage":
                updateImage(
                    msg.previewId,
                    msg.captureIndex || 0,
                    msg.imageData,
                );
                break;

            case "updateA11y":
                // D2 — daemon-attached a11y data products landed for one preview. Refresh
                // the per-card caches and re-apply the overlays in place; no full re-render
                // of the grid. findings/nodes left undefined means leave that side alone.
                applyA11yUpdate(msg.previewId, msg.findings, msg.nodes);
                break;

            case "setModules":
                // Module selector removed from UI — module is resolved from the active editor.
                break;

            case "setFunctionFilter": {
                // Driven by the gutter-icon hover link: narrow the grid
                // to a single @Preview function. `<filter-toolbar>`'s
                // setFunctionValue ensures the option exists for the
                // gutter-before-setPreviews case so the value sticks.
                filterToolbar.setFunctionValue(msg.functionName);
                saveFilterState();
                applyFilters();
                break;
            }

            case "setLoading":
                if (msg.previewId) {
                    const card = document.getElementById(
                        "preview-" + sanitizeId(msg.previewId),
                    );
                    if (card) {
                        const container =
                            card.querySelector(".image-container");
                        if (!container.querySelector(".loading-overlay")) {
                            const overlay = document.createElement("div");
                            overlay.className = "loading-overlay";
                            overlay.innerHTML =
                                '<div class="spinner" aria-label="Rendering"></div>';
                            container.appendChild(overlay);
                        }
                    }
                }
                // Whole-panel loading state is now carried by the slim
                // progress bar at the top of the view (setProgress).
                // Avoid double-signalling with a "Building…" banner —
                // it competes with the bar for visual attention.
                break;

            // setProgress / clearProgress are handled by <progress-bar>.
            // setCompileErrors / clearCompileErrors are handled by
            // <compile-errors-banner>.

            case "setError":
            case "setImageError": {
                const errCard = document.getElementById(
                    "preview-" + sanitizeId(msg.previewId),
                );
                if (errCard) {
                    // Stash per-capture error so carousel navigation
                    // restores the message when the user returns to
                    // that specific capture. setError is preview-wide
                    // (captureIndex defaulted to 0) — applies to the
                    // representative image container only.
                    const captureIndex =
                        msg.command === "setImageError"
                            ? msg.captureIndex || 0
                            : 0;
                    const renderError =
                        msg.command === "setImageError"
                            ? msg.renderError || null
                            : null;
                    const caps = cardCaptures.get(msg.previewId);
                    const replaceExisting =
                        msg.command !== "setImageError" ||
                        msg.replaceExisting !== false;
                    const existingImageData =
                        caps && caps[captureIndex]
                            ? caps[captureIndex].imageData
                            : null;
                    const container = errCard.querySelector(".image-container");
                    const existingImg = container.querySelector("img");
                    const keepExistingImage =
                        !replaceExisting && (existingImageData || existingImg);
                    if (caps && caps[captureIndex]) {
                        caps[captureIndex].errorMessage = msg.message;
                        caps[captureIndex].renderError = renderError;
                        if (!keepExistingImage) {
                            caps[captureIndex].imageData = null;
                        }
                    }
                    const cur = parseInt(
                        errCard.dataset.currentIndex || "0",
                        10,
                    );
                    if (caps && cur !== captureIndex) break;

                    errCard.classList.add("has-error");
                    const previousErr =
                        container.querySelector(".error-message");
                    if (previousErr) previousErr.remove();
                    const overlay = container.querySelector(".loading-overlay");
                    if (overlay) overlay.remove();
                    const skeleton = container.querySelector(".skeleton");
                    if (
                        skeleton &&
                        (keepExistingImage || msg.command === "setImageError")
                    ) {
                        skeleton.remove();
                    }
                    // setImageError keeps any existing rendered <img>
                    // visible underneath the error overlay so the user
                    // still has the previous render as a reference.
                    // setError is the preview-wide path — wipe everything
                    // and replace with just the error.
                    if (msg.command === "setError") {
                        const existingImg = container.querySelector("img");
                        if (existingImg) existingImg.remove();
                    }
                    container.appendChild(
                        buildErrorPanel(
                            vscode,
                            msg.message,
                            renderError,
                            errCard.dataset.className,
                        ),
                    );
                }
                break;
            }

            // showMessage is handled by <message-banner>.

            case "previewDiffReady": {
                if (!earlyFeatures()) break;
                const card = document.getElementById(
                    "preview-" + sanitizeId(msg.previewId),
                );
                if (!card) break;
                showDiffOverlay(
                    card,
                    msg.against,
                    {
                        leftLabel: msg.leftLabel,
                        leftImage: msg.leftImage,
                        rightLabel: msg.rightLabel,
                        rightImage: msg.rightImage,
                    },
                    null,
                    diffOverlayConfig,
                );
                break;
            }
            case "previewDiffError": {
                if (!earlyFeatures()) break;
                const card = document.getElementById(
                    "preview-" + sanitizeId(msg.previewId),
                );
                if (!card) break;
                showDiffOverlay(
                    card,
                    msg.against,
                    null,
                    msg.message || "Diff unavailable.",
                    diffOverlayConfig,
                );
                break;
            }
            case "focusAndDiff": {
                if (!earlyFeatures()) break;
                const card = document.getElementById(
                    "preview-" + sanitizeId(msg.previewId),
                );
                if (!card) break;
                focusOnCard(card);
                showDiffOverlay(
                    card,
                    msg.against,
                    null,
                    null,
                    diffOverlayConfig,
                );
                vscode.postMessage({
                    command: "requestPreviewDiff",
                    previewId: msg.previewId,
                    against: msg.against,
                });
                break;
            }
            case "setInteractiveAvailability": {
                moduleDaemonReady.set(msg.moduleId, !!msg.ready);
                moduleInteractiveSupported.set(
                    msg.moduleId,
                    !!msg.interactiveSupported,
                );
                // Daemon went away while a card was live — drop the live
                // state so the user doesn't keep seeing a LIVE badge on
                // a card whose stream has stopped. Today the panel is
                // single-module-scoped so any not-ready signal applies
                // to every live preview; if the panel ever shows multi-
                // module previews simultaneously, this needs scoping by
                // each preview's owning module.
                if (!msg.ready && interactivePreviewIds.size > 0) {
                    interactivePreviewIds.clear();
                    applyLiveBadge();
                }
                if (!msg.ready && recordingPreviewIds.size > 0) {
                    recordingPreviewIds.clear();
                }
                applyInteractiveButtonState();
                applyRecordingButtonState();
                break;
            }
            case "clearInteractive": {
                // Extension flushed daemon-side streams (e.g. user moved focus to a
                // different editor). Drop our UI-side bookkeeping in lockstep — the
                // extension already stopped the streams server-side, so we MUST NOT post
                // setInteractive messages back; that would race the flush.
                if (msg.previewId) {
                    interactivePreviewIds.delete(msg.previewId);
                    applyLiveBadge();
                    applyInteractiveButtonState();
                } else if (interactivePreviewIds.size > 0) {
                    interactivePreviewIds.clear();
                    applyLiveBadge();
                    applyInteractiveButtonState();
                }
                break;
            }
            case "clearRecording": {
                if (msg.previewId) {
                    recordingPreviewIds.delete(msg.previewId);
                } else if (recordingPreviewIds.size > 0) {
                    recordingPreviewIds.clear();
                }
                applyRecordingButtonState();
                break;
            }
            case "previewMainRefChanged": {
                if (!earlyFeatures()) break;
                // compose-preview/main moved — re-issue any open vs-main
                // diff overlay so the user sees the new bytes without
                // clicking. Other diffs (HEAD, current, previous) are
                // unaffected.
                document
                    .querySelectorAll(
                        '.preview-diff-overlay[data-against="main"]',
                    )
                    .forEach((overlay) => {
                        const card = overlay.closest(".preview-card");
                        const previewId = card && card.dataset.previewId;
                        if (!card || !previewId) return;
                        showDiffOverlay(
                            card,
                            "main",
                            null,
                            null,
                            diffOverlayConfig,
                        );
                        vscode.postMessage({
                            command: "requestPreviewDiff",
                            previewId,
                            against: "main",
                        });
                    });
                break;
            }
            case "setEarlyFeatures": {
                previewStore.setState({ earlyFeaturesEnabled: !!msg.enabled });
                if (!earlyFeatures()) {
                    document
                        .querySelectorAll(".preview-diff-overlay")
                        .forEach((overlay) => overlay.remove());
                    // Tear down every a11y rendering surface — finding
                    // legends, finding-overlay boxes, and the daemon-
                    // attached hierarchy overlay — and drop the cached
                    // findings/nodes so re-enabling the feature picks
                    // up fresh data from the next setPreviews / updateA11y.
                    document
                        .querySelectorAll(
                            ".a11y-legend, .a11y-overlay, .a11y-hierarchy-overlay",
                        )
                        .forEach((el) => el.remove());
                    cardA11yFindings.clear();
                    cardA11yNodes.clear();
                    enabledFocusProducts.clear();
                    if (a11yOverlay()) {
                        vscode.postMessage({
                            command: "setA11yOverlay",
                            previewId: a11yOverlay(),
                            enabled: false,
                        });
                        setA11yOverlay(null);
                    }
                    if (recordingPreviewIds.size > 0) {
                        recordingPreviewIds.forEach((previewId) => {
                            vscode.postMessage({
                                command: "setRecording",
                                previewId,
                                enabled: false,
                                format: recordingFormat.value,
                            });
                        });
                        recordingPreviewIds.clear();
                    }
                }
                applyLayout();
                break;
            }
        }
    });

    function escapeHtml(text) {
        const div = document.createElement("div");
        div.textContent = text;
        return div.innerHTML;
    }
}
