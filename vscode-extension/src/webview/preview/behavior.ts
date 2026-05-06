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
import { getVsCodeApi, type VsCodeApi } from "../shared/vscode";
import {
    applyA11yUpdate,
    applyRelativeSizing,
    buildPreviewCard,
    type CardBuilderConfig,
    renderPreviews as renderPreviewsImpl,
    updateCardMetadata,
    updateImage,
} from "./cardBuilder";
import { FilterToolbar } from "./components/FilterToolbar";
import { MessageBanner, type MessageOwner } from "./components/MessageBanner";
import { PreviewGrid } from "./components/PreviewGrid";
import { showDiffOverlay, type DiffMode } from "./diffOverlay";
import {
    FocusController,
    type FocusControllerPersistedState,
} from "./focusController";
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

    // Forward references — `inspector` / `liveState` / `focusController`
    // close over each other via callback shapes, so we late-bind through
    // these `let !` declarations. Each binding is dereferenced only at
    // runtime (inside arrow callbacks fired by user events / message
    // handlers), by which point all three are initialised.
    let inspector!: FocusInspectorController;
    let liveState!: LiveStateController;
    let focusController!: FocusController;

    inspector = new FocusInspectorController({
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
        onToggleA11yOverlay: () => focusController.toggleA11yOverlay(),
        onToggleInteractive: (shift) => liveState.toggleInteractive(shift),
        onToggleRecording: () => liveState.toggleRecording(),
        onRequestFocusedDiff: (against) =>
            focusController.requestFocusedDiff(against),
        onRequestLaunchOnDevice: () => focusController.requestLaunchOnDevice(),
    });

    // Config for the interactive-input pointer machine. The predicate
    // unifies live/recording state — both forward pointer/wheel input
    // to the daemon — so the module doesn't need direct access to
    // either Set.
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
        inFocus: () => focusController.inFocus(),
        focusedCard: () => focusController.focusedCard(),
        applyInteractiveButtonState: () =>
            focusController.applyInteractiveButtonState(),
        applyRecordingButtonState: () =>
            focusController.applyRecordingButtonState(),
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

    focusController = new FocusController({
        vscode: vscode as VsCodeApi<FocusControllerPersistedState>,
        grid,
        filterToolbar,
        focusControls,
        focusPosition,
        btnPrev,
        btnNext,
        focusToolbar,
        inspector,
        liveState,
        diffOverlayConfig,
        state,
        moduleDaemonReady,
        moduleInteractiveSupported,
        earlyFeatures,
        getA11yOverlayId: a11yOverlay,
        setA11yOverlayId: setA11yOverlay,
        getFocusIndex: () => focusIndex,
        setFocusIndex,
        getPreviousLayout: () => previousLayout,
        setPreviousLayout,
        getLastScopedPreviewId: () => lastScopedPreviewId,
        setLastScopedPreviewId,
    });

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

    // Focus-mode orchestration (applyLayout, button-state hooks, focus
    // navigation, the a11y-overlay toggle, focused-card actions) lives in
    // `./focusController.ts` — see `FocusController`. The thin shims
    // below keep the call shape stable for the message-context callbacks
    // and for `applyFilters`, which is itself a closure over filterToolbar.
    function applyLayout(): void {
        focusController.applyLayout();
    }
    function applyInteractiveButtonState(): void {
        focusController.applyInteractiveButtonState();
    }
    function applyRecordingButtonState(): void {
        focusController.applyRecordingButtonState();
    }
    function navigateFocus(delta: number): void {
        focusController.navigateFocus(delta);
    }
    function focusOnCard(card: HTMLElement): void {
        focusController.focusOnCard(card);
    }
    function exitFocus(): void {
        focusController.exitFocus();
    }
    function requestFocusedDiff(against: "head" | "main"): void {
        focusController.requestFocusedDiff(against);
    }
    function requestLaunchOnDevice(): void {
        focusController.requestLaunchOnDevice();
    }
    function toggleA11yOverlay(): void {
        focusController.toggleA11yOverlay();
    }

    function saveFilterState(): void {
        state.filters = {
            fn: filterToolbar.getFunctionValue(),
            group: filterToolbar.getGroupValue(),
        };
        vscode.setState(state);
    }

    function restoreFilterState(): void {
        const f = state.filters || {};
        if (f.fn && filterToolbar.hasFunctionOption(f.fn))
            filterToolbar.setFunctionValue(f.fn);
        if (f.group && filterToolbar.hasGroupOption(f.group))
            filterToolbar.setGroupValue(f.group);
    }

    function applyFilters(): void {
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
    function ensureNotBlank(): void {
        const hasCards = grid.querySelector(".preview-card") !== null;
        if (!hasCards && !messageBanner.isVisible()) {
            messageBanner.setMessage("Preparing previews…", "fallback");
        }
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
        grid,
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
        inFocus: () => focusController.inFocus(),
        focusedCard: () => focusController.focusedCard(),
        enterFocus: focusOnCard,
        exitFocus,
        observeForViewport: observeCardForViewport,
        forgetViewport: (id, card) => viewport.forget(id, card),
        setMessage,
        getMessageOwner: () => messageBanner.getOwner(),
    };
    function renderPreviews(previews: readonly PreviewInfo[]): void {
        renderPreviewsImpl(previews, cardBuilderConfig);
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
