// Imperative behaviour for the live "Compose Preview" webview panel.
//
// Verbatim port of the previously-inline IIFE script in
// `src/previewPanel.ts` (the `<script nonce="...">` block in `getHtml()`).
// Now type-checked end-to-end; pieces still intentionally imperative
// (`createCard` / `updateCardMetadata` / `renderPreviews` / `updateImage`
// / `applyA11yUpdate`) are slated to fold into a future `<preview-card>`
// Lit component.
//
// Runs once per webview load. Assumes `<preview-app>` has already
// rendered its skeleton into light DOM, so `document.getElementById(...)`
// queries below resolve.

import type {
    AccessibilityFinding,
    AccessibilityNode,
    PreviewInfo,
} from "../shared/types";
import { getVsCodeApi } from "../shared/vscode";
import {
    applyA11yUpdate,
    applyRelativeSizing,
    buildPreviewCard,
    type CardBuilderConfig,
    updateCardMetadata,
    updateImage,
} from "./cardBuilder";
import { FilterToolbar } from "./components/FilterToolbar";
import { MessageBanner, type MessageOwner } from "./components/MessageBanner";
import { PreviewGrid } from "./components/PreviewGrid";
import { showDiffOverlay, type DiffMode } from "./diffOverlay";
import { FocusInspectorController } from "./focusInspector";
import {
    FocusToolbarController,
    isFocusedInteractiveSupported,
    isFocusedModuleReady,
} from "./focusToolbar";
import {
    FrameCarouselController,
    type CapturePresentation,
} from "./frameCarousel";
import { LiveStateController } from "./liveState";
import { LoadingOverlay } from "./loadingOverlay";
import {
    handleExtensionMessage,
    type PreviewMessageContext,
} from "./messageHandlers";
import { previewStore } from "./previewStore";
import { StaleBadgeController } from "./staleBadge";
import { ViewportTracker } from "./viewportTracker";

/** Persisted webview state stored via `vscode.setState` / `getState`. Survives
 *  across webview reloads (panel hidden + revealed) but not across full
 *  extension reloads. */
interface PersistedState {
    filters?: { fn?: string; group?: string };
    layout?: "grid" | "flow" | "column" | "focus";
    diffMode?: DiffMode;
}

/** Look up a known-present DOM element. Used for the static ids that
 *  `<preview-app>` has already rendered into the light DOM by the time this
 *  module runs. Throws so a missing template (e.g. an HTML-template typo)
 *  surfaces early rather than landing as a runtime null-deref deeper in. */
function requireElementById<T extends HTMLElement>(id: string): T {
    const el = document.getElementById(id);
    if (!el) throw new Error(`Required element #${id} not found`);
    return el as T;
}

function requireSelector<T extends Element>(selector: string): T {
    const el = document.querySelector<T>(selector);
    if (!el) throw new Error(`Required element ${selector} not found`);
    return el;
}

export function setupPreviewBehavior(
    initialEarlyFeaturesEnabled: boolean,
): void {
    const vscode = getVsCodeApi<PersistedState>();
    const state: PersistedState = vscode.getState() ?? { filters: {} };
    // `earlyFeaturesEnabled` lives in `previewStore` so future
    // components can subscribe to it without going through this
    // closure. Reads inside this file go through the local helper for
    // terseness; writes go straight to `previewStore.setState`.
    previewStore.setState({
        earlyFeaturesEnabled: initialEarlyFeaturesEnabled,
    });
    const earlyFeatures = (): boolean =>
        previewStore.getState().earlyFeaturesEnabled;

    const grid = requireElementById<PreviewGrid>("preview-grid");
    const focusInspector = requireElementById<HTMLElement>("focus-inspector");
    // `<message-banner>` owns the status strip; we use a typed handle to
    // call setMessage / read its current owner from the few cases that
    // still need to drive it (filter narrowing, ensureNotBlank fallback,
    // clearAll). showMessage messages from the extension reach the
    // component directly without going through this code.
    const messageBanner = requireSelector<MessageBanner>("message-banner");
    // `<filter-toolbar>` owns the function/group/layout selects,
    // their options, and the user-interaction events. We grab a handle
    // here for the programmatic get/set + populate paths used by
    // applyFilters / applyLayout / setPreviews / setFunctionFilter /
    // focusOnCard / exitFocus / restoreFilterState.
    const filterToolbar = requireSelector<FilterToolbar>("filter-toolbar");
    const focusControls = requireElementById<HTMLElement>("focus-controls");
    const btnPrev = requireElementById<HTMLButtonElement>("btn-prev");
    const btnNext = requireElementById<HTMLButtonElement>("btn-next");
    const btnDiffHead = requireElementById<HTMLButtonElement>("btn-diff-head");
    const btnDiffMain = requireElementById<HTMLButtonElement>("btn-diff-main");
    const btnLaunchDevice =
        requireElementById<HTMLButtonElement>("btn-launch-device");
    const btnA11yOverlay =
        requireElementById<HTMLButtonElement>("btn-a11y-overlay");
    const btnInteractive =
        requireElementById<HTMLButtonElement>("btn-interactive");
    const btnStopInteractive = requireElementById<HTMLButtonElement>(
        "btn-stop-interactive",
    );
    const btnRecording = requireElementById<HTMLButtonElement>("btn-recording");
    const recordingFormat =
        requireElementById<HTMLSelectElement>("recording-format");
    const btnExitFocus =
        requireElementById<HTMLButtonElement>("btn-exit-focus");
    const focusToolbar = new FocusToolbarController({
        btnPrev,
        btnNext,
        btnDiffHead,
        btnDiffMain,
        btnLaunchDevice,
        btnA11yOverlay,
        btnInteractive,
        btnStopInteractive,
        btnRecording,
        btnExitFocus,
        recordingFormat,
        focusInspector,
    });
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
    // previewId -> findings. Populated from setPreviews so updateImage can
    // re-read the list on every image (re)load without re-querying the
    // DOM for data attributes.
    const cardA11yFindings = new Map<string, readonly AccessibilityFinding[]>();
    // D2 — previewId -> nodes for the daemon-attached a11y/hierarchy payload. Drives
    // the local hierarchy overlay (translucent rectangles + label/role/states tooltip
    // on hover) drawn on top of the existing finding overlay. Populated by
    // applyA11yUpdate and re-read on each image (re)load via applyHierarchyOverlay.
    const cardA11yNodes = new Map<string, readonly AccessibilityNode[]>();
    const focusPosition = requireElementById<HTMLElement>("focus-position");
    // Progress bar is owned by `<progress-bar>` — see
    // `components/ProgressBar.ts`. It listens for `setProgress` /
    // `clearProgress` directly and owns its own deferred-paint timing.

    // Compile-error banner is owned by `<compile-errors-banner>` —
    // see `components/CompileErrorsBanner.ts`. It listens for
    // `setCompileErrors` / `clearCompileErrors` directly and toggles
    // the `compile-stale` class on `#preview-grid` itself.

    // Panel-level scalars are mirrored to `previewStore` on every write so
    // future components (the upcoming `<preview-card>`, the focus inspector,
    // etc.) can subscribe via `StoreController`. Local mutable bindings stay
    // here for terseness in the heavy imperative paths
    // (`createCard`/`renderPreviews`/`updateImage`/etc.); keep the two
    // synchronised by going through these wrappers.
    let allPreviews: PreviewInfo[] = [];
    function setAllPreviews(next: PreviewInfo[]): void {
        allPreviews = next;
        previewStore.setState({ allPreviews: next });
    }
    let moduleDir = "";
    function setModuleDir(next: string): void {
        moduleDir = next;
        previewStore.setState({ moduleDir: next });
    }
    let focusIndex = 0;
    function setFocusIndex(next: number): void {
        focusIndex = next;
        previewStore.setState({ focusIndex: next });
    }
    // Last previewId published to the extension via previewScopeChanged.
    // Tracked here so we don't spam the History panel with redundant
    // re-scopes (e.g. layout reapplies on every filter tweak).
    let lastScopedPreviewId: string | null = null;
    function setLastScopedPreviewId(next: string | null): void {
        lastScopedPreviewId = next;
        previewStore.setState({ lastScopedPreviewId: next });
    }
    // Layout to fall back to when the user exits focus mode. Captured
    // whenever we transition into focus from another layout (dropdown
    // change, dblclick on a card). Defaults to grid so the very first
    // exit lands somewhere sensible.
    let previousLayout: "grid" | "flow" | "column" =
        state.layout && state.layout !== "focus" ? state.layout : "grid";
    function setPreviousLayout(next: "grid" | "flow" | "column"): void {
        previousLayout = next;
        previewStore.setState({ previousLayout: next });
    }
    // Seed the store with the persisted-state-derived defaults so initial
    // subscribers see the right values.
    previewStore.setState({ previousLayout });
    let filterDebounce: ReturnType<typeof setTimeout> | null = null;

    // Interactive (live-stream) mode state. Declared up here — *before*
    // the first applyLayout() call below — because applyLayout reaches
    // through `liveState` on the early-exit-on-focus-change path.
    // moduleDaemonReady tracks per-module daemon readiness pushed by the
    // extension via setInteractiveAvailability; the button enables only
    // when the focused card's owning module is ready. moduleInteractiveSupported
    // distinguishes full v2 live mode from the Android/v1 fallback where
    // renders refresh but pointer input doesn't mutate held composition state.
    //
    // The live + recording sets and their state machine live in
    // `./liveState.ts` — see `LiveStateController`. Constructed below,
    // after `interactiveInputConfig` so the controller can hand the
    // config to `attachInteractiveInputHandlers`.
    const moduleDaemonReady = new Map<string, boolean>();
    const moduleInteractiveSupported = new Map<string, boolean>();

    const inspector = new FocusInspectorController({
        el: focusInspector,
        earlyFeatures,
        getPreview: (id) => allPreviews.find((p) => p.id === id),
        getA11yFindings: (id) =>
            cardA11yFindings.get(id) ||
            allPreviews.find((p) => p.id === id)?.a11yFindings ||
            [],
        getA11yNodes: (id) =>
            cardA11yNodes.get(id) ||
            allPreviews.find((p) => p.id === id)?.a11yNodes ||
            [],
        getA11yOverlayId: a11yOverlay,
        isLive: (id) => liveState.isLive(id),
        onToggleA11yOverlay: () => toggleA11yOverlay(),
        onToggleInteractive: (shift) => liveState.toggleInteractive(shift),
        onToggleRecording: () => liveState.toggleRecording(),
        onRequestFocusedDiff: (against) => requestFocusedDiff(against),
        onRequestLaunchOnDevice: () => requestLaunchOnDevice(),
    });

    // Config for the interactive-input pointer machine. The predicate
    // unifies live/recording state — both forward pointer/wheel input
    // to the daemon — so the module doesn't need direct access to
    // either Set. `liveState` is initialised right below and only read
    // when a real pointer event fires, so the closure access can never
    // hit before the controller is bound.
    let liveState!: LiveStateController;
    const interactiveInputConfig = {
        isLive: (id: string) =>
            liveState.isLive(id) || liveState.isRecording(id),
        vscode,
    };

    liveState = new LiveStateController({
        vscode,
        recordingFormat,
        interactiveInputConfig,
        earlyFeatures,
        inFocus: () => filterToolbar.getLayoutValue() === "focus",
        focusedCard: () =>
            filterToolbar.getLayoutValue() === "focus"
                ? (getVisibleCards()[focusIndex] ?? null)
                : null,
        applyInteractiveButtonState: () => applyInteractiveButtonState(),
        applyRecordingButtonState: () => applyRecordingButtonState(),
        renderInspector: (card) => inspector.render(card),
    });

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

    // Per-preview carousel runtime state — imageData / errorMessage per
    // capture. Populated from updateImage / setImageError messages so
    // prev/next navigation can swap the visible <img> without a fresh
    // extension round-trip.
    const cardCaptures = new Map<string, CapturePresentation[]>();
    const frameCarousel = new FrameCarouselController({
        vscode,
        cardCaptures,
        interactiveInputConfig,
    });

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
        const next = filterToolbar.getLayoutValue();
        if (next === "focus" && state.layout !== "focus") {
            // state.layout is now narrowed to "grid"|"flow"|"column"|undefined.
            setPreviousLayout(state.layout ?? "grid");
        }
        state.layout = next;
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
        liveState.toggleInteractive(e.shiftKey),
    );
    btnStopInteractive.addEventListener("click", () =>
        liveState.stopAllInteractive(),
    );
    btnRecording.addEventListener("click", () => liveState.toggleRecording());
    btnExitFocus.addEventListener("click", () => exitFocus());

    function applyEarlyFeatureVisibility() {
        focusToolbar.applyEarlyFeatureVisibility({
            earlyFeatures: earlyFeatures(),
            inFocus: filterToolbar.getLayoutValue() === "focus",
        });
    }

    function applyInteractiveButtonState() {
        const inFocus = filterToolbar.getLayoutValue() === "focus";
        const card = inFocus ? getVisibleCards()[focusIndex] : null;
        const previewId = card?.dataset.previewId ?? null;
        const isLive = !!previewId && liveState.isLive(previewId);
        focusToolbar.applyInteractiveButtonState({
            inFocus,
            focusedPreviewId: previewId,
            isLive,
            otherLiveCount: liveState.liveCount - (isLive ? 1 : 0),
            hasLive: liveState.liveCount > 0,
            daemonReady: isFocusedModuleReady(moduleDaemonReady),
            interactiveSupported: isFocusedInteractiveSupported(
                moduleDaemonReady,
                moduleInteractiveSupported,
            ),
        });
    }

    function applyRecordingButtonState() {
        const inFocus = filterToolbar.getLayoutValue() === "focus";
        const card = inFocus ? getVisibleCards()[focusIndex] : null;
        const previewId = card?.dataset.previewId ?? null;
        focusToolbar.applyRecordingButtonState({
            inFocus,
            earlyFeatures: earlyFeatures(),
            focusedPreviewId: previewId,
            daemonReady: isFocusedModuleReady(moduleDaemonReady),
            isRecording: !!previewId && liveState.isRecording(previewId),
        });
    }

    function applyA11yOverlayButtonState() {
        const inFocus = filterToolbar.getLayoutValue() === "focus";
        const card = inFocus ? getVisibleCards()[focusIndex] : null;
        focusToolbar.applyA11yOverlayButtonState({
            inFocus,
            earlyFeatures: earlyFeatures(),
            focusedPreviewId: card?.dataset.previewId ?? null,
            a11yOverlayId: a11yOverlay(),
        });
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
        inspector.render(card);
    }

    // Live + recording state (toolbar/per-card buttons, badge re-stamping,
    // single-target follow-focus, viewport auto-stop) lives in
    // `./liveState.ts` — see `LiveStateController`. The pointer + wheel
    // state machine the live cards use lives in `./interactiveInput.ts`.

    // Document-level Left/Right in focus mode steps between cards. The
    // animated-carousel frame-controls handler stops propagation so
    // its arrow keys still walk captures within a single card. Skip
    // when an input-like element has focus (the layout dropdown,
    // future text inputs) so native keyboard semantics aren't stolen.
    document.addEventListener("keydown", (e) => {
        if (filterToolbar.getLayoutValue() !== "focus") return;
        if (e.key !== "ArrowLeft" && e.key !== "ArrowRight") return;
        const tag = e.target instanceof Element ? e.target.tagName : null;
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
    function setMessage(text: string, owner?: MessageOwner): void {
        messageBanner.setMessage(text, owner ?? "extension");
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
                inspector.render(null);
                publishScopedPreview();
                return;
            }
            if (focusIndex >= visible.length) {
                setFocusIndex(visible.length - 1);
            }
            if (focusIndex < 0) setFocusIndex(0);
            grid.applyFocusVisibility(visible[focusIndex]);
            focusPosition.textContent = focusIndex + 1 + " / " + visible.length;
            btnPrev.disabled = focusIndex === 0;
            btnNext.disabled = focusIndex === visible.length - 1;
            inspector.render(visible[focusIndex]);
        } else {
            grid.applyFocusVisibility(null);
            inspector.render(null);
        }
        document
            .querySelectorAll(".image-container")
            .forEach((c) => c.removeAttribute("title"));
        publishScopedPreview();
        // Single-target follow-focus teardown — see
        // `LiveStateController.enforceSingleTargetFollowFocus`.
        liveState.enforceSingleTargetFollowFocus(
            mode === "focus" ? (getVisibleCards()[focusIndex] ?? null) : null,
        );
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
        setLastScopedPreviewId(previewId);
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

    function navigateFocus(delta: number): void {
        const visible = getVisibleCards();
        if (visible.length === 0) return;
        setFocusIndex(
            Math.max(0, Math.min(visible.length - 1, focusIndex + delta)),
        );
        applyLayout();
    }

    // Switch the layout to focus mode and target the supplied card.
    // No-op when the card is filtered out (it wouldn't be in the visible
    // set anyway, and forcing focus on an invisible card surfaces an
    // empty pane).
    function focusOnCard(card: HTMLElement): void {
        const visible = getVisibleCards();
        const idx = visible.indexOf(card);
        if (idx === -1) return;
        setFocusIndex(idx);
        const current = filterToolbar.getLayoutValue();
        if (current !== "focus") {
            setPreviousLayout(current);
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

    // Live-panel diff: only meaningful when one preview is focused. Pulls
    // the currently focused card's previewId and asks the extension to
    // resolve the comparison anchor (HEAD = latest archived render,
    // main = latest archived render on the main branch).
    function requestFocusedDiff(against: "head" | "main"): void {
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

    // Card lifecycle lives in `./cardBuilder.ts` — see `buildPreviewCard`,
    // `updateCardMetadata`, `applyRelativeSizing`, `updateImage`,
    // `applyA11yUpdate`. The eventual `<preview-card>` Lit component will
    // fold all five into a single reactive `render()` and consume the same
    // `CardBuilderConfig` shape for its collaborator surface.
    const cardBuilderConfig: CardBuilderConfig = {
        vscode,
        cardCaptures,
        cardA11yFindings,
        cardA11yNodes,
        staleBadge,
        frameCarousel,
        liveState,
        interactiveInputConfig,
        diffOverlayConfig,
        inspector,
        getAllPreviews: () => allPreviews,
        earlyFeatures,
        inFocus: () => filterToolbar.getLayoutValue() === "focus",
        focusedCard: () =>
            filterToolbar.getLayoutValue() === "focus"
                ? (getVisibleCards()[focusIndex] ?? null)
                : null,
        enterFocus: focusOnCard,
        exitFocus,
        observeForViewport: observeCardForViewport,
    };
    function createCard(p: PreviewInfo): HTMLElement {
        return buildPreviewCard(p, cardBuilderConfig);
    }

    /**
     * Incremental diff: update existing cards, add new ones, remove missing.
     * Keeps rendered images in place during refresh — they're replaced as
     * new images stream in from updateImage messages.
     */
    function renderPreviews(previews: readonly PreviewInfo[]): void {
        if (previews.length === 0) {
            // Defensive fallback — the extension now always sends an
            // explicit showMessage for empty states, so this branch
            // shouldn't normally fire. Kept so the view never ends up
            // with an empty grid + empty message if a bug slips through.
            grid.innerHTML = "";
            setMessage("No @Preview functions found", "fallback");
            return;
        }
        const newIds = new Set(previews.map((p) => p.id));
        const existingCards = new Map<string, HTMLElement>();
        grid.querySelectorAll<HTMLElement>(".preview-card").forEach((card) => {
            const id = card.dataset.previewId;
            if (id) existingCards.set(id, card);
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
                updateCardMetadata(existing, p, cardBuilderConfig);
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

    // ----- Viewport tracking (daemon scroll-ahead, PREDICTIVE.md § 7) -----
    // The actual machinery lives in `./viewportTracker.ts`. The auto-stop-
    // interactive-on-scroll-out rule lives in `liveState`; the tracker
    // forwards the leave event via `onCardLeftViewport`.
    const viewport = new ViewportTracker({
        vscode,
        onCardLeftViewport: (id) => liveState.onCardLeftViewport(id),
    });

    function observeCardForViewport(card: HTMLElement): void {
        viewport.observe(card);
    }

    // Message dispatch lives in a typed sibling module — see
    // `./messageHandlers.ts`. The discriminated `ExtensionToWebview` union
    // flows through `handleExtensionMessage` so every variant is exhaustively
    // checked at compile time. The context exposes the orchestration
    // callbacks and pieces of imperative state still owned here.
    const messageContext: PreviewMessageContext = {
        vscode,
        grid,
        filterToolbar,
        inspector,
        liveState,
        staleBadge,
        loadingOverlay,
        diffOverlayConfig,
        cardCaptures,
        cardA11yFindings,
        cardA11yNodes,
        moduleDaemonReady,
        moduleInteractiveSupported,
        earlyFeatures,
        getA11yOverlayId: a11yOverlay,
        setA11yOverlayId: setA11yOverlay,
        setAllPreviews,
        setModuleDir,
        setLastScopedPreviewId,
        renderPreviews,
        applyRelativeSizing,
        applyFilters,
        applyLayout,
        applyInteractiveButtonState,
        applyRecordingButtonState,
        saveFilterState,
        restoreFilterState,
        ensureNotBlank,
        updateImage: (previewId, captureIndex, imageData) =>
            updateImage(previewId, captureIndex, imageData, cardBuilderConfig),
        applyA11yUpdate: (previewId, findings, nodes) =>
            applyA11yUpdate(previewId, findings, nodes, cardBuilderConfig),
        focusOnCard,
    };
    window.addEventListener("message", (event) => {
        handleExtensionMessage(event.data, messageContext);
    });
}
