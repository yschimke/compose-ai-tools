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
import "./components/BundleChipBar";
import "./components/DataTabs";
import "./components/DataTable";
import "./components/BoxOverlay";
import "./components/BundleExpander";
import { PreviewGrid } from "./components/PreviewGrid";
import { BundleChipBar } from "./components/BundleChipBar";
import { DataTabs } from "./components/DataTabs";
import { DataTable } from "./components/DataTable";
import { BundleExpander } from "./components/BundleExpander";
import { BundleController, type BundleSnapshot } from "./bundleController";
import { getBundle, type BundleId } from "./bundleRegistry";
import { a11yTableColumns, computeA11yBundleData } from "./a11yBundlePresenter";
import {
    computePerformanceBundleData,
    performanceTableColumns,
    renderPerfPlaceholder,
    renderPerformanceSections,
} from "./performanceBundlePresenter";
import {
    computeThemingBundleData,
    themingTableColumns,
    type ThemePayload,
    type WallpaperPayload,
} from "./themingBundlePresenter";
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
    /**
     * Bundle controller snapshot — chip ON/OFF state, per-bundle enabled
     * kinds, and the active tab. Persists across panel hide/show so a
     * reload doesn't snap the tab row back to "no inspector" mid-session.
     */
    bundles?: BundleSnapshot;
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
    @query("bundle-chip-bar") private _bundleChipBar!: BundleChipBar;
    @query("data-tabs") private _dataTabs!: DataTabs;
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
            <bundle-chip-bar></bundle-chip-bar>
            <data-tabs></data-tabs>

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
        // Default true keeps the new collapse-by-default behaviour in
        // ad-hoc test contexts (where the dataset attribute is missing);
        // explicit `false` opts out.
        const initialCollapseVariants =
            this.dataset.collapseVariants !== "false";
        const vscode = getVsCodeApi<PersistedState>();
        const state: PersistedState = vscode.getState() ?? { filters: {} };
        // `earlyFeaturesEnabled` lives in `previewStore` so future
        // components can subscribe to it without going through this
        // closure. Reads inside this file go through the local helper for
        // terseness; writes go straight to `previewStore.setState`.
        previewStore.setState({
            earlyFeaturesEnabled: initialEarlyFeaturesEnabled,
            autoEnableCheapEnabled: initialAutoEnableCheap,
            collapseVariantsEnabled: initialCollapseVariants,
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
        const dataProductsByPreview = new Map<string, Map<string, unknown>>();

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
            getDataProduct: (previewId, kind) =>
                dataProductsByPreview.get(previewId)?.get(kind),
            postMessage: (msg) => vscode.postMessage(msg),
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
                // Mirror the toggle into BundleController so the chip
                // bar / tab row stay in sync with subscriptions that
                // originated outside the new shell (focus-inspector
                // bucket checkboxes, suggestion chips). Without this
                // hook, deactivating the bundle later could miss
                // unsubscribing the kind, and the chip/tab state
                // drifts from actual daemon subscriptions.
                bundleController.handleExternalKindToggle(kind, enabled);
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

        // Bundle controller — owns the chip ↔ tab ↔ overlay state machine
        // for the new panel shell. Additive to the existing focus-inspector
        // chrome; the two coexist during migration. The controller's
        // `setKindEnabled` forwards through the same `setDataExtensionEnabled`
        // wire message the inspector already uses, so subscriptions land in
        // a single place.
        const bundleChipBar = this._bundleChipBar;
        const dataTabs = this._dataTabs;
        const bundleController = new BundleController(
            {
                setKindEnabled: (kind, enabled) => {
                    // Subscriptions are per-preview at the wire layer; we
                    // forward against the focused preview when there is
                    // one, otherwise the first visible card (the default
                    // multi-preview scoping rule from the design doc).
                    const target = currentBundleTarget();
                    if (!target) return;
                    vscode.postMessage({
                        command: "setDataExtensionEnabled",
                        previewId: target,
                        kind,
                        enabled,
                    });
                },
                persist: (snapshot) => {
                    state.bundles = snapshot;
                    vscode.setState(state);
                },
            },
            state.bundles,
        );
        const currentBundleTarget = (): string | null => {
            const focused = focusController?.focusedCard?.();
            if (focused?.dataset.previewId) return focused.dataset.previewId;
            const visible = grid.querySelector<HTMLElement>(
                ".preview-card[data-preview-id]",
            );
            return visible?.dataset.previewId ?? null;
        };
        // Per-bundle tab bodies. Each entry holds the wrapper element
        // we attach to `<data-tabs>` plus the `<bundle-expander>` /
        // `<data-table>` children we refresh in place. Lazy-built on
        // first activation so a panel that never touches a bundle
        // doesn't pay the DOM cost.
        interface BundleBody {
            wrapper: HTMLElement;
            expander: BundleExpander;
            table: DataTable<unknown>;
        }
        const bundleBodies = new Map<BundleId, BundleBody>();
        const buildBundleBody = (
            id: BundleId,
            heading: string,
            columns: ReadonlyArray<
                import("./components/DataTable").DataTableColumn<unknown>
            >,
        ): BundleBody => {
            const wrapper = document.createElement("div");
            wrapper.className = "bundle-tab-body";
            wrapper.dataset.bundle = id;
            const expander = document.createElement(
                "bundle-expander",
            ) as BundleExpander;
            expander.addEventListener("kind-toggled", (evt) => {
                const det = (
                    evt as CustomEvent<
                        import("./components/BundleExpander").BundleKindToggledDetail
                    >
                ).detail;
                bundleController.setKindEnabled(
                    det.bundleId,
                    det.kind,
                    det.enabled,
                );
            });
            const table = document.createElement(
                "data-table",
            ) as DataTable<unknown>;
            table.heading = heading;
            table.setColumns(columns);
            wrapper.appendChild(expander);
            wrapper.appendChild(table);
            return { wrapper, expander, table };
        };
        const a11yBody = (): BundleBody => {
            let b = bundleBodies.get("a11y");
            if (b) return b;
            b = buildBundleBody(
                "a11y",
                "Accessibility",
                a11yTableColumns() as unknown as ReadonlyArray<
                    import("./components/DataTable").DataTableColumn<unknown>
                >,
            );
            bundleBodies.set("a11y", b);
            return b;
        };
        // Performance bundle body is shaped differently from the others
        // — it stacks three sub-sections (recomposition table, render
        // trace bar chart, Perfetto handoff) under the shared expander.
        // We reuse the expander wiring from `buildBundleBody` but route
        // section painting through `renderPerformanceSections` instead
        // of the single `<data-table>` slot the other bundles use.
        interface PerformanceBody {
            wrapper: HTMLElement;
            expander: BundleExpander;
            recompTable: DataTable<unknown>;
            host: HTMLElement;
        }
        let performanceCachedBody: PerformanceBody | null = null;
        const performanceBody = (): PerformanceBody => {
            if (performanceCachedBody) return performanceCachedBody;
            const wrapper = document.createElement("div");
            wrapper.className = "bundle-tab-body";
            wrapper.dataset.bundle = "performance";
            const expander = document.createElement(
                "bundle-expander",
            ) as BundleExpander;
            expander.addEventListener("kind-toggled", (evt) => {
                const det = (
                    evt as CustomEvent<
                        import("./components/BundleExpander").BundleKindToggledDetail
                    >
                ).detail;
                bundleController.setKindEnabled(
                    det.bundleId,
                    det.kind,
                    det.enabled,
                );
            });
            // Recomposition uses the shared `<data-table>` so row hover
            // and copy-JSON parity with the other bundles is automatic.
            // Render trace + Perfetto don't fit the row model, so they
            // paint their own DOM into `host` below.
            const recompTable = document.createElement(
                "data-table",
            ) as DataTable<unknown>;
            recompTable.heading = "Recomposition";
            recompTable.setColumns(
                performanceTableColumns() as unknown as ReadonlyArray<
                    import("./components/DataTable").DataTableColumn<unknown>
                >,
            );
            const host = document.createElement("section");
            host.className = "perf-bundle-host";
            wrapper.appendChild(expander);
            wrapper.appendChild(host);
            performanceCachedBody = { wrapper, expander, recompTable, host };
            return performanceCachedBody;
        };
        const refreshExpanderFor = (id: BundleId): void => {
            const body = bundleBodies.get(id);
            const bundle = getBundle(id);
            if (!body || !bundle) return;
            body.expander.setState({
                bundleId: id,
                kinds: bundle.kinds,
                enabledKinds: bundleController.state().enabledKinds(id),
            });
        };
        const themingBody = (): BundleBody => {
            let b = bundleBodies.get("theming");
            if (b) return b;
            b = buildBundleBody(
                "theming",
                "Theming",
                themingTableColumns() as unknown as ReadonlyArray<
                    import("./components/DataTable").DataTableColumn<unknown>
                >,
            );
            bundleBodies.set("theming", b);
            return b;
        };
        const refreshA11yBundle = (): void => {
            const target = currentBundleTarget();
            if (!target) return;
            const store = previewStore.getState();
            const nodes =
                store.cardA11yNodes.get(target) ??
                store.allPreviews.find((p) => p.id === target)?.a11yNodes ??
                [];
            const findings =
                store.cardA11yFindings.get(target) ??
                store.allPreviews.find((p) => p.id === target)?.a11yFindings ??
                [];
            const data = computeA11yBundleData(nodes, findings);
            const body = a11yBody();
            body.table.setRows(data.rows);
            body.table.summary = data.rows.length + " elements";
            body.table.setOverlayId(
                (row) => (row as { id: string }).id ?? "a11y-row",
            );
            body.table.setJsonPayload(() => ({
                previewId: target,
                nodes,
                findings,
            }));
            refreshExpanderFor("a11y");
            dataTabs.setTabBody("a11y", body.wrapper);
        };
        const refreshPerformanceBundle = (): void => {
            const target = currentBundleTarget();
            if (!target) return;
            const byKind = dataProductsByPreview.get(target);
            const recompPayload = byKind?.get("compose/recomposition") ?? null;
            const tracePayload = byKind?.get("render/trace") ?? null;
            const perfettoPayload =
                byKind?.get("render/composeAiTrace") ?? null;
            const data = computePerformanceBundleData(
                recompPayload,
                tracePayload,
                perfettoPayload,
            );
            const body = performanceBody();
            // Sync the expander row regardless of payload — it's the
            // user's only way to turn the default-OFF kinds on.
            // `refreshExpanderFor` needs the entry registered in
            // `bundleBodies` so it can read the cached BundleBody, but
            // performance has its own cache. Hand-roll the equivalent
            // setState call here.
            const bundleDescriptor = getBundle("performance");
            if (bundleDescriptor) {
                body.expander.setState({
                    bundleId: "performance",
                    kinds: bundleDescriptor.kinds,
                    enabledKinds: bundleController
                        .state()
                        .enabledKinds("performance"),
                });
            }
            const enabledKinds = bundleController
                .state()
                .enabledKinds("performance");
            const hasAnyPayload =
                data.recomposition !== null ||
                data.renderTrace !== null ||
                data.composeAiTrace !== null;
            if (enabledKinds.length === 0 && !hasAnyPayload) {
                // Placeholder hint — every kind in this bundle is
                // medium+ cost, so we don't auto-enable one on chip
                // press. The user opens Configure… and picks.
                renderPerfPlaceholder(body.host);
            } else {
                renderPerformanceSections(
                    body.host,
                    data,
                    target,
                    body.recompTable,
                    (text) =>
                        vscode.postMessage({
                            command: "copyToClipboard",
                            text,
                        }),
                    {
                        recomposition: recompPayload,
                        renderTrace: tracePayload,
                        composeAiTrace: perfettoPayload,
                    },
                );
            }
            dataTabs.setTabBody("performance", body.wrapper);
        };
        const refreshThemingBundle = (): void => {
            const target = currentBundleTarget();
            if (!target) return;
            const byKind = dataProductsByPreview.get(target);
            const theme =
                (byKind?.get("compose/theme") as ThemePayload | undefined) ??
                null;
            const wallpaper =
                (byKind?.get("compose/wallpaper") as
                    | WallpaperPayload
                    | undefined) ?? null;
            const data = computeThemingBundleData(theme, wallpaper, target);
            const body = themingBody();
            const table = body.table;
            table.setRows(data.rows);
            // Summary mirrors the per-section row counts so the user
            // gets a quick feel for token volume without expanding the
            // table. Tags on each row let us count without double-
            // counting the seed summary as a colour.
            const colorCount = data.rows.filter(
                (r) => (r as { kind?: string }).kind === "color",
            ).length;
            const typoCount = data.rows.filter(
                (r) => (r as { kind?: string }).kind === "typography",
            ).length;
            const shapeCount = data.rows.filter(
                (r) => (r as { kind?: string }).kind === "shape",
            ).length;
            table.summary =
                colorCount +
                " colour" +
                (colorCount === 1 ? "" : "s") +
                " · " +
                typoCount +
                " type · " +
                shapeCount +
                " shape" +
                (shapeCount === 1 ? "" : "s");
            // Theme tokens are global — no per-row overlay box — but
            // `<data-table>` still wants a stable id per row for hover
            // correlation with any future legend element.
            table.setOverlayId(
                (row) => (row as { id?: string }).id ?? "theming-row",
            );
            table.setJsonPayload(() => data.jsonPayload);
            refreshExpanderFor("theming");
            dataTabs.setTabBody("theming", body.wrapper);
        };
        const reflectBundleState = (): void => {
            const s = bundleController.state();
            bundleChipBar.setState({
                bundles: s.bundles,
                activeBundles: s.activeBundles,
            });
            dataTabs.setState({
                bundles: s.bundles,
                activeBundles: s.activeBundles,
                activeTab: s.activeTab,
            });
            if (s.activeBundles.includes("a11y")) refreshA11yBundle();
            if (s.activeBundles.includes("performance")) {
                refreshPerformanceBundle();
            }
            if (s.activeBundles.includes("theming")) refreshThemingBundle();
        };
        bundleController.onChange(() => reflectBundleState());
        reflectBundleState();
        bundleChipBar.addEventListener("bundle-toggled", (evt) => {
            const detail = (evt as CustomEvent<{ id: BundleId }>).detail;
            bundleController.toggleBundle(detail.id);
        });
        dataTabs.addEventListener("tab-closed", (evt) => {
            const detail = (evt as CustomEvent<{ id: BundleId }>).detail;
            bundleController.closeTab(detail.id);
        });
        dataTabs.addEventListener("tab-selected", (evt) => {
            const detail = (evt as CustomEvent<{ id: BundleId }>).detail;
            bundleController.selectTab(detail.id);
        });
        dataTabs.addEventListener("copy-json", (evt) => {
            const detail = (evt as CustomEvent<{ payload: unknown }>).detail;
            vscode.postMessage({
                command: "copyToClipboard",
                text: JSON.stringify(detail.payload, null, 2),
            });
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
            collapseVariants: () =>
                previewStore.getState().collapseVariantsEnabled,
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

        // Mirror the gutter-icon `setFunctionFilter` path: persist the new
        // function/group picks and re-run filtering so the grid updates.
        // Without this, the dropdowns updated their internal state but
        // nothing ever called `filterController.apply()`.
        filterToolbar.addEventListener("filter-changed", () => {
            saveFilterState();
            applyFilters();
        });

        // The "+N variants" chip on a collapsed-variant survivor card
        // is the only in-grid affordance pointing at hidden siblings.
        // Clicking it narrows the function filter to that card's
        // function, which disables variant collapse and reveals all
        // variants — same trio the gutter-icon `setFunctionFilter`
        // message handler runs.
        grid.addEventListener("variant-chip-clicked", (evt) => {
            const detail = (evt as CustomEvent<{ fn?: string }>).detail;
            const fn = detail?.fn;
            if (!fn) return;
            filterToolbar.setFunctionValue(fn);
            saveFilterState();
            applyFilters();
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
            applyA11yUpdate: (previewId, findings, nodes) => {
                applyA11yUpdate(previewId, findings, nodes, cardBuilderConfig);
                // Refresh the A11y bundle tab body if it's active for
                // this preview — keeps the table in sync with the
                // incoming hierarchy/findings without waiting for the
                // user to re-click the chip.
                if (
                    bundleController.state().activeBundles.includes("a11y") &&
                    currentBundleTarget() === previewId
                ) {
                    refreshA11yBundle();
                }
            },
            updateDataProducts: (previewId, dataProducts) => {
                let byKind = dataProductsByPreview.get(previewId);
                if (!byKind) {
                    byKind = new Map();
                    dataProductsByPreview.set(previewId, byKind);
                }
                for (const dp of dataProducts) {
                    byKind.set(dp.kind, dp.payload);
                }
                const focused = focusController.focusedCard();
                const focusedId = focused?.dataset.previewId ?? null;
                const matches = focusedId === previewId;
                console.log(
                    `[compose-preview] updateDataProducts previewId=${previewId} ` +
                        `kinds=[${dataProducts.map((dp) => dp.kind).join(",")}] ` +
                        `focused=${focusedId ?? "<none>"} matches=${matches}`,
                );
                if (matches && focused) {
                    inspector.render(focused);
                }
                // Refresh bundle tab bodies that depend on this preview's
                // data. Each bundle gates on its own active flag so a
                // preview that ships unrelated kinds doesn't redraw
                // every open tab.
                const activeBundles = bundleController.state().activeBundles;
                const matchesTarget = currentBundleTarget() === previewId;
                if (matchesTarget && activeBundles.includes("a11y")) {
                    refreshA11yBundle();
                }
                if (
                    matchesTarget &&
                    activeBundles.includes("performance") &&
                    dataProducts.some(
                        (dp) =>
                            dp.kind === "compose/recomposition" ||
                            dp.kind === "render/trace" ||
                            dp.kind === "render/composeAiTrace",
                    )
                ) {
                    refreshPerformanceBundle();
                }
                if (
                    matchesTarget &&
                    activeBundles.includes("theming") &&
                    dataProducts.some(
                        (dp) =>
                            dp.kind === "compose/theme" ||
                            dp.kind === "compose/wallpaper",
                    )
                ) {
                    refreshThemingBundle();
                }
            },
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
