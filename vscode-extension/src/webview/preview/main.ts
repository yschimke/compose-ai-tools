// Bundled entry for the live "Compose Preview" webview panel.
//
// `<preview-app>` renders the panel skeleton via Lit's `html` template,
// then runs the imperative behaviour (filters, focus mode, carousel,
// diff overlays, interactive input, viewport tracking, message routing)
// once on `firstUpdated`. The setup body now lives inline in
// `firstUpdated` — a verbatim port from the previously-inline IIFE in
// `previewPanel.ts`. Future commits can incrementally lift sub-trees
// (toolbar, focus controls, preview cards, diff overlay, focus inspector)
// into reactive sub-components.

import { LitElement, html, type TemplateResult } from "lit";
import { customElement, query } from "lit/decorators.js";
import type { PreviewInfo } from "../shared/types";
import { getVsCodeApi, type VsCodeApi } from "../shared/vscode";
import {
    applyA11yUpdate,
    applyRelativeSizing,
    type CardBuilderConfig,
    renderPreviews as renderPreviewsImpl,
} from "./cardBuilder";
import { FilterToolbar } from "./components/FilterToolbar";
import { MessageBanner, type MessageOwner } from "./components/MessageBanner";
import "./components/CompileErrorsBanner";
import "./components/FilterToolbar";
import "./components/MessageBanner";
import "./components/PreviewCard";
import "./components/PreviewGrid";
import "./components/ProgressBar";
import { PreviewGrid } from "./components/PreviewGrid";
import { FilterController } from "./filterController";
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
import { FrameCarouselController } from "./frameCarousel";
import { LiveStateController } from "./liveState";
import { LoadingOverlay } from "./loadingOverlay";
import {
    handleExtensionMessage,
    type PreviewMessageContext,
} from "./messageHandlers";
import { previewStore } from "./previewStore";
import { StaleBadgeController } from "./staleBadge";
import { StreamingPainter } from "./streamingPainter";
import { ViewportTracker } from "./viewportTracker";

/** Persisted webview state stored via `vscode.setState` / `getState`. Survives
 *  across webview reloads (panel hidden + revealed) but not across full
 *  extension reloads. */
interface PersistedState {
    filters?: { fn?: string; group?: string };
    layout?: "grid" | "flow" | "column" | "focus";
    diffMode?: DiffMode;
    /**
     * Per-scope MRU of focus-inspector data-product `kind` strings —
     * scope is the active module dir today (see `getScope` in
     * `FocusInspectorConfig`). Most-recent-first within each list,
     * trimmed to taxonomy `bumpMru`'s default cap. Used as a
     * tiebreaker for in-bucket sorting and as a topup signal in
     * `suggestFor`. Persists across webview reload (panel hide/show)
     * but not extension reload — same lifecycle as `filters` / `layout`.
     */
    focusMruByScope?: Record<string, string[]>;
}

@customElement("preview-app")
export class PreviewApp extends LitElement {
    // Render in light DOM so `media/preview.css` applies and so
    // `document.getElementById(...)` queries from the inlined setup body
    // resolve.
    protected createRenderRoot(): HTMLElement {
        return this;
    }

    // Element handles into the rendered template. Lit resolves these lazily on
    // first access via `this.querySelector`, which works in light DOM. We read
    // them inside `firstUpdated` (after the template is in the DOM) and alias
    // each to a local `const` to minimise diff churn against the previous
    // `requireElementById` / `requireSelector` setup body — step 3+ will lift
    // these onto controllers and the locals will go away naturally.
    @query("#preview-grid") private _grid!: PreviewGrid;
    @query("#focus-inspector") private _focusInspector!: HTMLElement;
    @query("message-banner") private _messageBanner!: MessageBanner;
    @query("filter-toolbar") private _filterToolbar!: FilterToolbar;
    @query("#focus-controls") private _focusControls!: HTMLElement;
    @query("#btn-prev") private _btnPrev!: HTMLButtonElement;
    @query("#btn-next") private _btnNext!: HTMLButtonElement;
    @query("#btn-diff-head") private _btnDiffHead!: HTMLButtonElement;
    @query("#btn-diff-main") private _btnDiffMain!: HTMLButtonElement;
    @query("#btn-launch-device") private _btnLaunchDevice!: HTMLButtonElement;
    @query("#btn-a11y-overlay") private _btnA11yOverlay!: HTMLButtonElement;
    @query("#btn-interactive") private _btnInteractive!: HTMLButtonElement;
    @query("#btn-stop-interactive")
    private _btnStopInteractive!: HTMLButtonElement;
    @query("#btn-recording") private _btnRecording!: HTMLButtonElement;
    @query("#recording-format") private _recordingFormat!: HTMLSelectElement;
    @query("#btn-exit-focus") private _btnExitFocus!: HTMLButtonElement;
    @query("#focus-position") private _focusPosition!: HTMLElement;

    protected render(): TemplateResult {
        return html`
            <progress-bar></progress-bar>
            <compile-errors-banner></compile-errors-banner>
            <filter-toolbar></filter-toolbar>

            <message-banner></message-banner>
            <div id="focus-controls" class="focus-controls" hidden>
                <button
                    class="icon-button"
                    id="btn-prev"
                    title="Previous preview"
                    aria-label="Previous preview"
                >
                    <i
                        class="codicon codicon-arrow-left"
                        aria-hidden="true"
                    ></i>
                </button>
                <span id="focus-position" aria-live="polite"></span>
                <button
                    class="icon-button"
                    id="btn-next"
                    title="Next preview"
                    aria-label="Next preview"
                >
                    <i
                        class="codicon codicon-arrow-right"
                        aria-hidden="true"
                    ></i>
                </button>
                <button
                    class="icon-button"
                    id="btn-diff-head"
                    title="Diff vs last archived render (HEAD)"
                    aria-label="Diff vs HEAD"
                >
                    <i
                        class="codicon codicon-git-compare"
                        aria-hidden="true"
                    ></i>
                </button>
                <button
                    class="icon-button"
                    id="btn-diff-main"
                    title="Diff vs the latest render archived on main"
                    aria-label="Diff vs main"
                >
                    <i
                        class="codicon codicon-source-control"
                        aria-hidden="true"
                    ></i>
                </button>
                <button
                    class="icon-button"
                    id="btn-launch-device"
                    title="Launch on connected Android device"
                    aria-label="Launch on device"
                >
                    <i
                        class="codicon codicon-device-mobile"
                        aria-hidden="true"
                    ></i>
                </button>
                <button
                    class="icon-button"
                    id="btn-a11y-overlay"
                    title="Show accessibility overlay"
                    aria-label="Toggle accessibility overlay"
                    aria-pressed="false"
                >
                    <i class="codicon codicon-eye" aria-hidden="true"></i>
                </button>
                <button
                    class="icon-button"
                    id="btn-interactive"
                    title="Daemon not ready — live mode unavailable"
                    aria-label="Toggle live (interactive) mode"
                    aria-pressed="false"
                    disabled
                    hidden
                >
                    <i
                        class="codicon codicon-circle-large-outline"
                        aria-hidden="true"
                    ></i>
                </button>
                <button
                    class="icon-button"
                    id="btn-stop-interactive"
                    title="Stop live preview"
                    aria-label="Stop live preview"
                    hidden
                >
                    <i
                        class="codicon codicon-debug-stop"
                        aria-hidden="true"
                    ></i>
                </button>
                <button
                    class="icon-button"
                    id="btn-recording"
                    title="Record focused preview"
                    aria-label="Record focused preview"
                    aria-pressed="false"
                    disabled
                    hidden
                >
                    <i
                        class="codicon codicon-record-keys"
                        aria-hidden="true"
                    ></i>
                </button>
                <select
                    id="recording-format"
                    title="Recording format"
                    aria-label="Recording format"
                    hidden
                >
                    <option value="apng">APNG</option>
                    <option value="mp4">MP4</option>
                </select>
                <button
                    class="icon-button"
                    id="btn-exit-focus"
                    title="Exit focus mode"
                    aria-label="Exit focus mode"
                >
                    <i class="codicon codicon-close" aria-hidden="true"></i>
                </button>
            </div>
            <preview-grid
                id="preview-grid"
                role="list"
                aria-label="Preview cards"
            ></preview-grid>
            <div
                id="focus-inspector"
                class="focus-inspector"
                hidden
                aria-label="Focused preview data"
            ></div>
        `;
    }

    protected firstUpdated(): void {
        const initialEarlyFeaturesEnabled =
            this.dataset.earlyFeatures === "true";
        const initialAutoEnableCheap = this.dataset.autoEnableCheap === "true";
        const vscode = getVsCodeApi<PersistedState>();
        const state: PersistedState = vscode.getState() ?? { filters: {} };
        // `earlyFeaturesEnabled` lives in `previewStore` so future
        // components can subscribe to it without going through this
        // closure. Reads inside this file go through the local helper for
        // terseness; writes go straight to `previewStore.setState`.
        previewStore.setState({
            earlyFeaturesEnabled: initialEarlyFeaturesEnabled,
            autoEnableCheapEnabled: initialAutoEnableCheap,
        });
        const earlyFeatures = (): boolean =>
            previewStore.getState().earlyFeaturesEnabled;
        const streamingPainter = new StreamingPainter();

        // Element handles resolve via `@query` decorators on this element —
        // see the field declarations above. We alias them to local `const`s
        // so the rest of `firstUpdated` (controllers, cardBuilderConfig,
        // messageContext) can keep reading them by their short names.
        const grid = this._grid;
        const focusInspector = this._focusInspector;
        // `<message-banner>` owns the status strip; we use a typed handle to
        // call setMessage / read its current owner from the few cases that
        // still need to drive it (filter narrowing, ensureNotBlank fallback,
        // clearAll). showMessage messages from the extension reach the
        // component directly without going through this code.
        const messageBanner = this._messageBanner;
        // `<filter-toolbar>` owns the function/group/layout selects,
        // their options, and the user-interaction events. We grab a handle
        // here for the programmatic get/set + populate paths used by
        // applyFilters / applyLayout / setPreviews / setFunctionFilter /
        // focusOnCard / exitFocus / restoreFilterState.
        const filterToolbar = this._filterToolbar;
        const focusControls = this._focusControls;
        const btnPrev = this._btnPrev;
        const btnNext = this._btnNext;
        const btnDiffHead = this._btnDiffHead;
        const btnDiffMain = this._btnDiffMain;
        const btnLaunchDevice = this._btnLaunchDevice;
        const btnA11yOverlay = this._btnA11yOverlay;
        const btnInteractive = this._btnInteractive;
        const btnStopInteractive = this._btnStopInteractive;
        const btnRecording = this._btnRecording;
        const recordingFormat = this._recordingFormat;
        const btnExitFocus = this._btnExitFocus;
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
        // The per-preview a11y caches (`cardA11yFindings`, `cardA11yNodes`)
        // and the per-preview capture cache (`cardCaptures`) live in
        // `previewStore` — populated from `setPreviews` so `updateImage`
        // can re-read findings + hierarchy nodes on every image (re)load
        // without re-querying the DOM. The store owns them so the upcoming
        // `<preview-card>` Lit component can subscribe per-card without
        // going through this closure (see the versioned-counter notes in
        // `previewStore.ts`).
        const focusPosition = this._focusPosition;
        // Progress bar is owned by `<progress-bar>` — see
        // `components/ProgressBar.ts`. It listens for `setProgress` /
        // `clearProgress` directly and owns its own deferred-paint timing.

        // Compile-error banner is owned by `<compile-errors-banner>` —
        // see `components/CompileErrorsBanner.ts`. It listens for
        // `setCompileErrors` / `clearCompileErrors` directly and toggles
        // the `compile-stale` class on `#preview-grid` itself.

        // Panel-level scalars (`allPreviews`, `moduleDir`, `focusIndex`,
        // `previousLayout`, `lastScopedPreviewId`) live in `previewStore`.
        // Readers go through `previewStore.getState()` and writers through
        // `previewStore.setState({ ... })` — the wrappers passed via
        // `messageContext` / `FocusController` config are now thin arrows
        // defined inline at their call sites. Seed `previousLayout` from
        // the persisted layout so initial subscribers see the right value.
        previewStore.setState({
            previousLayout:
                state.layout && state.layout !== "focus"
                    ? state.layout
                    : "grid",
        });
        let filterDebounce: ReturnType<typeof setTimeout> | null = null;

        // Interactive (live-stream) mode state — the live + recording sets,
        // their state machine, and the per-module daemon-readiness +
        // interactive-supported maps (populated from
        // `setInteractiveAvailability`) all live on `LiveStateController` in
        // `./liveState.ts`. Constructed below, after `interactiveInputConfig`
        // so the controller can hand the config to
        // `attachInteractiveInputHandlers`.
        //
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
            autoEnableCheap: () =>
                previewStore.getState().autoEnableCheapEnabled,
            getPreview: (id) =>
                previewStore.getState().allPreviews.find((p) => p.id === id),
            getA11yFindings: (id) => {
                const store = previewStore.getState();
                return (
                    store.cardA11yFindings.get(id) ||
                    store.allPreviews.find((p) => p.id === id)?.a11yFindings ||
                    []
                );
            },
            getA11yNodes: (id) => {
                const store = previewStore.getState();
                return (
                    store.cardA11yNodes.get(id) ||
                    store.allPreviews.find((p) => p.id === id)?.a11yNodes ||
                    []
                );
            },
            getA11yOverlayId: a11yOverlay,
            isLive: (id) => liveState.isLive(id),
            onToggleA11yOverlay: () => focusController.toggleA11yOverlay(),
            onToggleInteractive: (shift) => liveState.toggleInteractive(shift),
            onToggleRecording: () => liveState.toggleRecording(),
            onRequestFocusedDiff: (against) =>
                focusController.requestFocusedDiff(against),
            onRequestLaunchOnDevice: () =>
                focusController.requestLaunchOnDevice(),
            onToggleDataExtension: (previewId, kind, enabled) => {
                vscode.postMessage({
                    command: "setDataExtensionEnabled",
                    previewId,
                    kind,
                    enabled,
                });
            },
            getScope: () => previewStore.getState().moduleDir,
            loadMru: (scope) => state.focusMruByScope?.[scope] ?? [],
            saveMru: (scope, mru) => {
                // Persist to vscode workspace state. Same shape as the
                // existing filter/layout fields — survives webview
                // hide/show but not extension reload, which matches
                // the lifetime users intuit for "ranked layers."
                const existing = state.focusMruByScope ?? {};
                state.focusMruByScope = {
                    ...existing,
                    [scope]: [...mru],
                };
                vscode.setState(state);
            },
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
            earlyFeatures,
            getA11yOverlayId: a11yOverlay,
            setA11yOverlayId: setA11yOverlay,
            getFocusIndex: () => previewStore.getState().focusIndex,
            setFocusIndex: (next) =>
                previewStore.setState({ focusIndex: next }),
            getPreviousLayout: () => previewStore.getState().previousLayout,
            setPreviousLayout: (next) =>
                previewStore.setState({ previousLayout: next }),
            getLastScopedPreviewId: () =>
                previewStore.getState().lastScopedPreviewId,
            setLastScopedPreviewId: (next) =>
                previewStore.setState({ lastScopedPreviewId: next }),
        });

        // Filter + message-banner orchestration lives in `./filterController.ts`
        // — see `FilterController`. Built after `focusController` because
        // `apply()` calls `applyLayout()` to recompute focus bounds against the
        // narrowed visible set.
        const filterController = new FilterController({
            vscode,
            state,
            filterToolbar,
            grid,
            messageBanner,
            getAllPreviews: () => previewStore.getState().allPreviews,
            applyLayout: () => focusController.applyLayout(),
        });

        const staleBadge = new StaleBadgeController(vscode);
        const loadingOverlay = new LoadingOverlay();

        // Per-preview carousel runtime state — imageData / errorMessage per
        // capture — lives on `previewStore.cardCaptures`. Populated from
        // updateImage / setImageError messages so prev/next navigation can
        // swap the visible <img> without a fresh extension round-trip; the
        // carousel reads it via `previewStore.getState().cardCaptures`.
        const frameCarousel = new FrameCarouselController({
            vscode,
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
                previewStore.setState({
                    previousLayout: state.layout ?? "grid",
                });
            }
            state.layout = next;
            vscode.setState(state);
            applyLayout();
        });

        btnPrev.addEventListener("click", () => navigateFocus(-1));
        btnNext.addEventListener("click", () => navigateFocus(1));
        btnDiffHead.addEventListener("click", () => requestFocusedDiff("head"));
        btnDiffMain.addEventListener("click", () => requestFocusedDiff("main"));
        btnLaunchDevice.addEventListener("click", () =>
            requestLaunchOnDevice(),
        );
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
        btnRecording.addEventListener("click", () =>
            liveState.toggleRecording(),
        );
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

        // Filter + message-banner orchestration shims — implementations live
        // in `./filterController.ts`. The shims keep the call shape stable
        // for the message-context callbacks and the various event listeners.
        const saveFilterState = (): void => filterController.save();
        const restoreFilterState = (): void => filterController.restore();
        const applyFilters = (): void => filterController.apply();
        const setMessage = (text: string, owner?: MessageOwner): void =>
            filterController.setMessage(text, owner);
        const ensureNotBlank = (): void => filterController.ensureNotBlank();

        // populateFilter / hasOption are gone — `<filter-toolbar>` owns the
        // option lists via setFunctionOptions / setGroupOptions and exposes
        // hasFunctionOption / hasGroupOption for membership tests. The
        // current selected value is preserved across reseeds because
        // `<filter-toolbar>`'s reactive state retains `fnValue` / `grpValue`
        // when only `fnOptions` / `grpOptions` change.

        // Card lifecycle: initial DOM build in `./cardBuilder.ts`
        // (`buildPreviewCard` / `populatePreviewCard`); reactive metadata
        // refresh + per-frame image paint live behind `<preview-card>`'s
        // `updated()` hook + `paintCapture` method (delegating to
        // `./cardMetadata.refreshCardMetadata` and `./cardImage.paintCardCapture`).
        // `applyA11yUpdate` and `applyRelativeSizing` still flow through
        // `cardBuilderConfig` for now.
        const cardBuilderConfig: CardBuilderConfig = {
            vscode,
            grid,
            staleBadge,
            frameCarousel,
            liveState,
            interactiveInputConfig,
            diffOverlayConfig,
            inspector,
            getAllPreviews: () => previewStore.getState().allPreviews,
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
            streamingPainter,
            earlyFeatures,
            getA11yOverlayId: a11yOverlay,
            setA11yOverlayId: setA11yOverlay,
            setAllPreviews: (next) =>
                previewStore.setState({ allPreviews: next }),
            setModuleDir: (next) => previewStore.setState({ moduleDir: next }),
            setLastScopedPreviewId: (next) =>
                previewStore.setState({ lastScopedPreviewId: next }),
            renderPreviews,
            applyRelativeSizing,
            applyFilters,
            applyLayout,
            applyInteractiveButtonState,
            applyRecordingButtonState,
            saveFilterState,
            restoreFilterState,
            ensureNotBlank,
            applyA11yUpdate: (previewId, findings, nodes) =>
                applyA11yUpdate(previewId, findings, nodes, cardBuilderConfig),
            focusOnCard,
        };
        window.addEventListener("message", (event) => {
            handleExtensionMessage(event.data, messageContext);
        });
        // Tell the extension we exist. The host posts `setPreviews` /
        // `setModules` / etc. as soon as it has data — but `postMessage`
        // silently drops messages while the webview view is unresolved
        // (panel hidden when the extension activated on `onLanguage:kotlin`).
        // Replying to this signal is the host's cue to republish the latest
        // stateful messages so the grid isn't permanently empty.
        getVsCodeApi().postMessage({ command: "webviewReady" });
    }
}
