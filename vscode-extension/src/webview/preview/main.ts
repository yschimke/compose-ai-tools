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
import type {
    AccessibilityFinding,
    AccessibilityNode,
    PreviewInfo,
} from "../shared/types";
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
import "./components/BundleLegend";
import "./components/BundleRowDetail";
import { PreviewGrid } from "./components/PreviewGrid";
import { BundleChipBar } from "./components/BundleChipBar";
import { DataTabs } from "./components/DataTabs";
import { DataTable } from "./components/DataTable";
import { BundleExpander } from "./components/BundleExpander";
import {
    BundleLegend,
    type BundleLegendEntry,
} from "./components/BundleLegend";
import type {
    BundleRowDetail,
    BundleRowDetailSection,
} from "./components/BundleRowDetail";
import { findLegendTarget } from "./bundleLegendTarget";
import {
    clearBundleBoxes,
    getVisiblePreviewIds,
    paintBundleBoxes,
    paintBundleBoxesEverywhere,
} from "./cardBundleOverlay";
import type { OverlayBox } from "./components/BoxOverlay";
import { BundleController, type BundleSnapshot } from "./bundleController";
import { getBundle, type BundleId } from "./bundleRegistry";
import { wireExpanderToController } from "./bundleExpanderWiring";
import { a11yTableColumns, computeA11yBundleData } from "./a11yBundlePresenter";
import { buildA11yRowDetail } from "./a11yRowDetail";
import {
    buildDrawnTextRowDetail,
    buildFontRowDetail,
    buildTranslationRowDetail,
} from "./textRowDetail";
import {
    buildInspectionRowDetail,
    inspectionRowTitle,
} from "./inspectionRowDetail";
import { buildHistoryRowDetail } from "./historyRowDetail";
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
import {
    ambientTableColumns,
    computeAmbientBundleData,
    type AmbientPayload,
    type AmbientStateLevel,
} from "./ambientBundlePresenter";
import {
    computeDisplayFilterBundleData,
    displayFilterTableColumns,
    type DisplayFilterVariantsPayload,
} from "./displayFilterBundlePresenter";
import {
    computeResourcesBundleData,
    resourcesTableColumns,
} from "./resourcesBundlePresenter";
import {
    computeErrorsBundleData,
    errorsTableColumns,
    renderErrorsStackFrames,
    type TestFailurePayload,
} from "./errorsBundlePresenter";
import {
    computeHistoryDiffBundleData,
    historyDiffTableColumns,
    renderHistoryDiffHeader,
    type HistoryDiffPayload,
} from "./historyDiffBundlePresenter";
import {
    computeTextBundleData,
    textBundleFontColumns,
    textBundleStringColumns,
    textBundleTranslationColumns,
    translationOpenResourcePayload,
    type DrawnTextRow,
    type FontRow,
    type TranslationRow,
} from "./textBundlePresenter";
import {
    computeInspectionBundleData,
    type InspectionKind,
} from "./inspectionPresenters";
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
    @query("#bundle-legend") private _bundleLegend!: BundleLegend;
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
        const minimal = this.dataset.minimalMode === "true";
        return html`
            <progress-bar></progress-bar>
            <compile-errors-banner></compile-errors-banner>
            <filter-toolbar></filter-toolbar>
            ${minimal
                ? html`
                      <div
                          class="minimal-refresh-bar"
                          id="minimal-refresh-bar"
                          role="region"
                          aria-label="Minimal mode"
                      >
                          <div class="minimal-refresh-text">
                              <strong>Minimal mode</strong>
                              <span class="minimal-refresh-hint"
                                  >Renders don't auto-update on save. Click
                                  Refresh to apply changes, or apply the Compose
                                  Preview Gradle plugin to enable
                                  auto-render.</span
                              >
                              <span
                                  class="minimal-save-pending"
                                  id="minimal-save-pending"
                                  hidden
                                  >Saved changes pending — click Refresh
                                  previews.</span
                              >
                          </div>
                          <div class="minimal-refresh-actions">
                              <button
                                  type="button"
                                  id="btn-minimal-refresh"
                                  class="minimal-refresh-button"
                                  title="Re-run renderAllPreviews"
                                  aria-label="Refresh previews"
                              >
                                  <i
                                      class="codicon codicon-refresh"
                                      aria-hidden="true"
                                  ></i>
                                  <span>Refresh previews</span>
                              </button>
                          </div>
                      </div>
                  `
                : ""}

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
            <div class="focus-stage">
                <preview-grid
                    id="preview-grid"
                    role="list"
                    aria-label="Preview cards"
                ></preview-grid>
                <bundle-legend
                    id="bundle-legend"
                    class="focus-stage-legend"
                    hidden
                ></bundle-legend>
            </div>
            <data-tabs hidden></data-tabs>
            <div
                id="focus-inspector"
                class="focus-inspector"
                hidden
                aria-label="Focused preview data"
            ></div>
            <bundle-chip-bar></bundle-chip-bar>
        `;
    }

    protected firstUpdated(): void {
        const initialEarlyFeaturesEnabled =
            this.dataset.earlyFeatures === "true";
        const minimalMode = this.dataset.minimalMode === "true";
        const vscode = getVsCodeApi<PersistedState>();
        // Listen for runtime mode flips from the extension (post-Gradle-sync
        // re-evaluation that upgrades minimal -> full once `applied.json`
        // markers prove the plugin is applied). Flip the dataset attribute and
        // request a Lit re-render so the minimal banner disappears without a
        // window reload. The other direction is unreachable per the
        // monotonicity argument in `extension.ts`.
        window.addEventListener("message", (event: MessageEvent) => {
            const data = event.data as { command?: string; minimal?: boolean };
            if (data?.command !== "setMinimalMode") return;
            const next = data.minimal === true ? "true" : "false";
            if (this.dataset.minimalMode !== next) {
                this.dataset.minimalMode = next;
                this.requestUpdate();
            }
        });
        if (minimalMode) {
            // Minimal mode hides the extension chrome (bundle chip bar +
            // data tabs) since data extensions are disabled — see CSS rule.
            // Wire the in-view "Refresh previews" button to post a refresh
            // request so the user doesn't have to hunt for the title-bar
            // icon. The button only exists in minimal mode (rendered above
            // in `render()`); the existing title-bar refresh command stays
            // available too.
            const pending = this.querySelector<HTMLElement>(
                "#minimal-save-pending",
            );
            this.querySelector<HTMLButtonElement>(
                "#btn-minimal-refresh",
            )?.addEventListener("click", () => {
                if (pending) pending.hidden = true;
                vscode.postMessage({ command: "requestRefresh" });
            });
            // Toggle the "Saved changes pending" hint based on extension
            // signals. The extension fires `minimalSavePending` from the
            // save handler when it deliberately skips an auto-render so
            // the user understands why the existing image isn't updating;
            // a follow-up `setPreviews` (manual refresh completed) or an
            // explicit `minimalSavePendingClear` puts the hint away.
            window.addEventListener("message", (event: MessageEvent) => {
                const data = event.data as { command?: string };
                if (!pending) return;
                if (data?.command === "minimalSavePending") {
                    pending.hidden = false;
                } else if (
                    data?.command === "minimalSavePendingClear" ||
                    data?.command === "setPreviews"
                ) {
                    pending.hidden = true;
                }
            });
        }
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
        let bundleController!: BundleController;
        const dataProductsByPreview = new Map<string, Map<string, unknown>>();
        // Panel-side cache for the Text/i18n bundle's data-URI font
        // preview path. Keyed by `(previewId, fontRowId)` because two
        // previews owned by the same module can carry the same row id
        // but resolve different bytes; the row-id-only fallback would
        // race. `undefined` → not yet requested, `null` → host said no
        // (file missing / not allowed / > 5 MB), `string` → data URI.
        const fontPreviewBytesByPreview = new Map<
            string,
            Map<string, string | null>
        >();
        // In-flight `loadFontPreview` requests so we don't pile up
        // duplicate messages while the host is still reading. Cleared
        // when the response lands.
        const fontPreviewPending = new Set<string>();
        const fontPreviewKey = (previewId: string, rowId: string): string =>
            previewId + " " + rowId;
        // Idempotent `@font-face` injector — keyed on `(previewId, rowId)`
        // so the same row's bytes only land in the document head once.
        // Lives on a single `<style>` element with `data-font-preview`
        // so dev-tools shows the whole set in one place.
        const fontFaceEmitted = new Set<string>();
        const ensureFontFaceStyleElement = (): HTMLStyleElement => {
            let el = document.querySelector(
                "style[data-font-preview]",
            ) as HTMLStyleElement | null;
            if (!el) {
                el = document.createElement("style");
                el.setAttribute("data-font-preview", "");
                document.head.appendChild(el);
            }
            return el;
        };
        const injectFontFace = (
            previewId: string,
            rowId: string,
            dataUri: string,
        ): void => {
            const key = fontPreviewKey(previewId, rowId);
            if (fontFaceEmitted.has(key)) return;
            fontFaceEmitted.add(key);
            const style = ensureFontFaceStyleElement();
            // `preview-<rowId>` is the synthetic family name the
            // resolved-family cell renders against; rowId is internal
            // ("font-0", "font-1", …) so no escaping needed today, but
            // we still wrap it in a regex-safe identifier check at
            // injection time by quoting in the CSS literal.
            style.appendChild(
                document.createTextNode(
                    '@font-face { font-family: "preview-' +
                        rowId +
                        '"; src: url("' +
                        dataUri +
                        '"); }\n',
                ),
            );
        };
        const loadFontPreview = (rowId: string, sourceFile: string): void => {
            const target = currentBundleTarget();
            if (!target) return;
            const key = fontPreviewKey(target, rowId);
            if (fontPreviewPending.has(key)) return;
            const bucket = fontPreviewBytesByPreview.get(target);
            if (bucket && bucket.has(rowId)) return;
            fontPreviewPending.add(key);
            vscode.postMessage({
                command: "loadFontPreview",
                previewId: target,
                fontRowId: rowId,
                sourceFile,
            });
        };
        const fontPreviewDataUri = (
            rowId: string,
        ): string | null | undefined => {
            const target = currentBundleTarget();
            if (!target) return undefined;
            return fontPreviewBytesByPreview.get(target)?.get(rowId);
        };

        inspector = new FocusInspectorController({
            el: focusInspector,
            earlyFeatures,
            getPreview: (id) =>
                previewStore.getState().allPreviews.find((p) => p.id === id),
            isHistoryActive: () =>
                bundleController.state().activeBundles.includes("history"),
            onRequestFocusedDiff: (against) =>
                focusController.requestFocusedDiff(against),
        });

        // Bundle controller — owns the chip ↔ tab ↔ overlay state machine
        // for the new panel shell. Additive to the existing focus-inspector
        // chrome; the two coexist during migration. The controller batches
        // every kind in a chip activation into a single `setKindsEnabled`
        // host call so the wire ships one `setDataExtensionEnabled`
        // message — per-kind dispatch raced the daemon's mode-lock-on-
        // first-subscribe and left bundles with partial data products.
        const bundleChipBar = this._bundleChipBar;
        const dataTabs = this._dataTabs;
        const bundleLegend = this._bundleLegend;
        // Mirror of `(previewId, kind)` subscriptions the host has
        // already posted to the extension. The daemon scheduler drops
        // its side whenever a previewId leaves `setVisible`, and the
        // chip's `setKindsEnabled` only posts at toggle time — so the
        // bundle target drifting (focus moves, card scrolls out and
        // back) silently desyncs intent from wire state and the
        // panel paints "No rows" against a chip that's still pressed.
        // We re-emit from `previewStore` focus events and from the
        // viewport tracker's `onAfterPublish`; re-emits are idempotent
        // on the scheduler (`subscribedPairs` short-circuits when the
        // pair is already live) so over-posting is cheap.
        const bundleSubscriptions = new Map<string, Set<string>>();
        const postSetDataExtensionEnabled = (
            previewId: string,
            kinds: readonly string[],
            enabled: boolean,
        ): void => {
            if (kinds.length === 0) return;
            if (enabled) {
                let set = bundleSubscriptions.get(previewId);
                if (!set) bundleSubscriptions.set(previewId, (set = new Set()));
                for (const k of kinds) set.add(k);
            } else {
                const set = bundleSubscriptions.get(previewId);
                if (set) {
                    for (const k of kinds) set.delete(k);
                    if (set.size === 0) bundleSubscriptions.delete(previewId);
                }
            }
            vscode.postMessage({
                command: "setDataExtensionEnabled",
                previewId,
                kinds: [...kinds],
                enabled,
            });
        };
        bundleController = new BundleController(
            {
                setKindsEnabled: (kinds, enabled) => {
                    // Subscriptions are per-preview at the wire layer; we
                    // forward against the focused preview when there is
                    // one, otherwise the first visible card (the default
                    // multi-preview scoping rule from the design doc).
                    const target = currentBundleTarget();
                    if (!target || kinds.length === 0) return;
                    postSetDataExtensionEnabled(target, kinds, enabled);
                },
                persist: (snapshot) => {
                    state.bundles = snapshot;
                    vscode.setState(state);
                },
            },
            state.bundles,
        );
        // Desired-kinds union across every active bundle — the rebind
        // paths below subscribe the bundle target to whatever the
        // chip-state says it currently wants.
        const desiredKindsForActiveBundles = (): string[] => {
            const out = new Set<string>();
            const snap = bundleController.state();
            for (const b of snap.activeBundles) {
                for (const k of snap.enabledKinds(b)) out.add(k);
            }
            return [...out];
        };
        const currentBundleTarget = (): string | null => {
            const focused = focusController?.focusedCard?.();
            if (focused?.dataset.previewId) return focused.dataset.previewId;
            const visible = grid.querySelector<HTMLElement>(
                ".preview-card[data-preview-id]",
            );
            return visible?.dataset.previewId ?? null;
        };
        /**
         * Paint [bundleId]'s overlay boxes onto either the focused
         * card (focus mode) or every visible card (grid mode), using
         * [computeOverlay] to derive that card's `OverlayBox[]` from
         * its own data caches. Centralises the focused-vs-grid
         * switch the design doc promises (see
         * `docs/design/EXTENSION_DATA_EXPOSURE.md` § "Open question
         * 1 — Multi-preview selection") so each bundle refresh
         * function only has to supply the per-preview compute.
         */
        const paintOverlaysForBundle = (
            bundleId: string,
            computeOverlay: (previewId: string) => readonly OverlayBox[],
        ): void => {
            if (focusController?.inFocus?.()) {
                const focused = focusController.focusedCard();
                const previewId = focused?.dataset.previewId;
                if (!focused || !previewId) return;
                paintBundleBoxes(focused, bundleId, computeOverlay(previewId));
                return;
            }
            const ids = getVisiblePreviewIds();
            const perCard = new Map<string, readonly OverlayBox[]>();
            for (const id of ids) perCard.set(id, computeOverlay(id));
            paintBundleBoxesEverywhere(bundleId, perCard);
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
            /** Detail panel rendered below the table when the user
             *  clicks a row. Hidden until the host calls
             *  `rowDetail.setDetail(...)` in the bundle's
             *  `row-clicked` listener. Generic across bundles —
             *  each bundle decides what fields to surface. */
            rowDetail: BundleRowDetail;
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
            wireExpanderToController(expander, bundleController);
            const table = document.createElement(
                "data-table",
            ) as DataTable<unknown>;
            table.heading = heading;
            table.setColumns(columns);
            const rowDetail = document.createElement(
                "bundle-row-detail",
            ) as BundleRowDetail;
            wrapper.appendChild(expander);
            wrapper.appendChild(table);
            wrapper.appendChild(rowDetail);
            return { wrapper, expander, table, rowDetail };
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
            // Wire row click → detail panel. The listener is attached
            // once (body is cached for the panel lifetime) and reads
            // the last-refresh data product context from
            // `a11yLastRefresh`. `row-clicked` carries `row: null`
            // when the user re-clicks the selected row to deselect.
            b.table.addEventListener("row-clicked", (evt) => {
                const det = (
                    evt as CustomEvent<
                        import("./components/DataTable").RowClickedDetail<
                            import("./a11yBundlePresenter").A11yRow
                        >
                    >
                ).detail;
                if (!det.row) {
                    b!.rowDetail.clear();
                    return;
                }
                const sections = buildA11yRowDetail(
                    det.row,
                    a11yLastRefresh?.findings ?? [],
                    a11yLastRefresh?.touchTargets ?? [],
                );
                b!.rowDetail.setDetail(det.row.label, sections);
            });
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
            wireExpanderToController(expander, bundleController);
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
        // Text / i18n bundle body — three stacked sub-sections (drawn
        // text, fonts, translations) under one expander, same shape as
        // the Performance bundle. The expander toggles the default-OFF
        // `i18n/translations` kind; drawn text + fonts are default-ON
        // so they appear as soon as the chip is pressed.
        interface TextBody {
            wrapper: HTMLElement;
            expander: BundleExpander;
            stringsTable: DataTable<DrawnTextRow>;
            fontsTable: DataTable<FontRow>;
            translationsTable: DataTable<TranslationRow>;
            /** Shared `<bundle-row-detail>` panel rendered below the
             *  three sub-tables — any of them dispatches `row-clicked`
             *  into this panel via the listener wired in `textBody()`.
             *  Same pattern as the a11y bundle. */
            rowDetail: BundleRowDetail;
        }
        let textCachedBody: TextBody | null = null;
        const textBody = (): TextBody => {
            if (textCachedBody) return textCachedBody;
            const wrapper = document.createElement("div");
            wrapper.className = "bundle-tab-body text-bundle-body";
            wrapper.dataset.bundle = "text";
            const expander = document.createElement(
                "bundle-expander",
            ) as BundleExpander;
            wireExpanderToController(expander, bundleController);
            const stringsTable = document.createElement(
                "data-table",
            ) as DataTable<DrawnTextRow>;
            stringsTable.heading = "Drawn text";
            stringsTable.setColumns(
                textBundleStringColumns() as unknown as ReadonlyArray<
                    import("./components/DataTable").DataTableColumn<DrawnTextRow>
                >,
            );
            const fontsTable = document.createElement(
                "data-table",
            ) as DataTable<FontRow>;
            fontsTable.heading = "Fonts";
            fontsTable.setColumns(
                textBundleFontColumns({
                    openExternal: (url) =>
                        vscode.postMessage({ command: "openExternal", url }),
                    loadFontPreview,
                    fontPreviewDataUri,
                }) as unknown as ReadonlyArray<
                    import("./components/DataTable").DataTableColumn<FontRow>
                >,
            );
            const translationsTable = document.createElement(
                "data-table",
            ) as DataTable<TranslationRow>;
            translationsTable.heading = "Translations";
            translationsTable.setColumns(
                textBundleTranslationColumns({
                    openResource: (row) =>
                        vscode.postMessage(translationOpenResourcePayload(row)),
                }) as unknown as ReadonlyArray<
                    import("./components/DataTable").DataTableColumn<TranslationRow>
                >,
            );
            const rowDetail = document.createElement(
                "bundle-row-detail",
            ) as BundleRowDetail;
            wrapper.appendChild(expander);
            wrapper.appendChild(stringsTable);
            wrapper.appendChild(fontsTable);
            wrapper.appendChild(translationsTable);
            wrapper.appendChild(rowDetail);
            // Wire each sub-table's row-clicked event to the shared
            // detail panel. The three tables carry different row
            // shapes, so each listener routes to the matching
            // `buildXxxRowDetail` helper; deselect (row=null) clears.
            const onTextRowClick = <T extends { id: string }>(
                evt: Event,
                build: (row: T) => readonly BundleRowDetailSection[],
                titleOf: (row: T) => string,
            ): void => {
                const det = (
                    evt as CustomEvent<
                        import("./components/DataTable").RowClickedDetail<T>
                    >
                ).detail;
                if (!det.row) {
                    rowDetail.clear();
                    return;
                }
                rowDetail.setDetail(titleOf(det.row), build(det.row));
                // Clear any other table's pinned selection so only
                // the just-clicked row stays highlighted.
                if (det !== null) clearOtherTextSelections(evt.target);
            };
            const clearOtherTextSelections = (
                target: EventTarget | null,
            ): void => {
                if (target !== stringsTable) {
                    stringsTable.setSelectedOverlayId(null);
                }
                if (target !== fontsTable) {
                    fontsTable.setSelectedOverlayId(null);
                }
                if (target !== translationsTable) {
                    translationsTable.setSelectedOverlayId(null);
                }
            };
            stringsTable.addEventListener("row-clicked", (evt) =>
                onTextRowClick<DrawnTextRow>(
                    evt,
                    buildDrawnTextRowDetail,
                    (r) => r.text || "(drawn text)",
                ),
            );
            fontsTable.addEventListener("row-clicked", (evt) =>
                onTextRowClick<FontRow>(
                    evt,
                    buildFontRowDetail,
                    (r) => r.requestedFamily,
                ),
            );
            translationsTable.addEventListener("row-clicked", (evt) =>
                onTextRowClick<TranslationRow>(
                    evt,
                    buildTranslationRowDetail,
                    (r) => r.resourceName ?? r.rendered ?? "(translation)",
                ),
            );
            textCachedBody = {
                wrapper,
                expander,
                stringsTable,
                fontsTable,
                translationsTable,
                rowDetail,
            };
            return textCachedBody;
        };
        const refreshTextBundle = (): void => {
            const target = currentBundleTarget();
            if (!target) return;
            const byKind = dataProductsByPreview.get(target);
            const stringsPayload = byKind?.get("text/strings") ?? null;
            const fontsPayload = byKind?.get("fonts/used") ?? null;
            const translationsPayload =
                byKind?.get("i18n/translations") ?? null;
            const data = computeTextBundleData(
                stringsPayload,
                fontsPayload,
                translationsPayload,
            );
            const body = textBody();
            const bundleDescriptor = getBundle("text");
            if (bundleDescriptor) {
                body.expander.setState({
                    bundleId: "text",
                    kinds: bundleDescriptor.kinds,
                    enabledKinds: bundleController.state().enabledKinds("text"),
                });
            }
            body.stringsTable.setRows(data.drawnText);
            body.stringsTable.summary =
                data.drawnText.length === 1
                    ? "1 string"
                    : data.drawnText.length + " strings";
            body.stringsTable.setOverlayId((row) => row.id);
            body.stringsTable.setJsonPayload(() => ({
                previewId: target,
                textStrings: data.jsonPayload.textStrings,
            }));
            body.fontsTable.setRows(data.fonts);
            body.fontsTable.summary =
                data.fonts.length === 1
                    ? "1 font"
                    : data.fonts.length + " fonts";
            body.fontsTable.setOverlayId((row) => row.id);
            body.fontsTable.setJsonPayload(() => ({
                previewId: target,
                fontsUsed: data.jsonPayload.fontsUsed,
            }));
            // Translations sub-table only mounts visibly when the kind is
            // enabled. Setting rows on the cached element regardless is
            // cheap and keeps the JSON-payload accessor in sync if the
            // user later flips the expander on.
            body.translationsTable.setRows(data.translations);
            body.translationsTable.summary =
                data.translations.length === 1
                    ? "1 entry"
                    : data.translations.length + " entries";
            body.translationsTable.setOverlayId((row) => row.id);
            body.translationsTable.setJsonPayload(() => ({
                previewId: target,
                i18nTranslations: data.jsonPayload.i18nTranslations,
            }));
            const enabledKinds = new Set(
                bundleController.state().enabledKinds("text"),
            );
            body.translationsTable.hidden =
                !enabledKinds.has("i18n/translations") &&
                data.translations.length === 0;
            // Drop any pinned row selection so the detail panel
            // doesn't point at renumbered rows after the refresh.
            body.stringsTable.setSelectedOverlayId(null);
            body.fontsTable.setSelectedOverlayId(null);
            body.translationsTable.setSelectedOverlayId(null);
            body.rowDetail.clear();
            dataTabs.setTabBody("text", body.wrapper);
            bundleLegend.setBundleEntries(
                "text",
                "Text / i18n",
                overlayToLegendEntries(data.overlay),
            );
            reflectLegendActiveTab();
            // Paint per-row overflow / truncation overlays. Focus
            // mode paints only the focused card; grid mode paints
            // every visible card from its own
            // `text/strings`/`fonts/used`/`i18n/translations`
            // payloads. Chip dismissal clears the layer via the
            // bundle-off branch in `reflectBundleState`.
            paintOverlaysForBundle("text", (previewId) => {
                if (previewId === target) return data.overlay;
                const cardByKind = dataProductsByPreview.get(previewId);
                const cardStrings = cardByKind?.get("text/strings") ?? null;
                const cardFonts = cardByKind?.get("fonts/used") ?? null;
                const cardTranslations =
                    cardByKind?.get("i18n/translations") ?? null;
                return computeTextBundleData(
                    cardStrings,
                    cardFonts,
                    cardTranslations,
                ).overlay;
            });
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
        // Per-card a11y source data shared by the focused panel
        // render and the grid-mode overlay paint. Returns the
        // nodes/findings shape `computeA11yBundleData` consumes so
        // panel and overlay always agree on which boxes are visible.
        const a11yDataForCard = (
            previewId: string,
        ): {
            nodes: readonly AccessibilityNode[];
            findings: readonly AccessibilityFinding[];
        } => {
            const store = previewStore.getState();
            const preview = store.allPreviews.find((p) => p.id === previewId);
            // a11y data arrives over two parallel channels: a dedicated
            // `updateA11y` post (consumed by `applyA11yUpdate`, which
            // writes the `cardA11yNodes` / `cardA11yFindings` caches)
            // and the generic `updateDataProducts` post that feeds
            // `dataProductsByPreview` like every other bundle. The
            // dedicated channel is gated on the preview card being in
            // the DOM at receive time (`applyA11yUpdate.ts:85`); if a
            // render-with-attachments lands before the card is mounted
            // — or before `earlyFeatures()` flips on — the cache stays
            // empty even though the payload is sitting in
            // `dataProductsByPreview`. Fall back to the same generic
            // cache the rest of the bundles drink from so the
            // accessibility table doesn't paint "No rows" against a
            // bundle whose data is already in memory.
            const byKind = dataProductsByPreview.get(previewId);
            const hierarchyPayload = byKind?.get("a11y/hierarchy") as
                | { nodes?: readonly AccessibilityNode[] }
                | undefined;
            const atfPayload = byKind?.get("a11y/atf") as
                | { findings?: readonly AccessibilityFinding[] }
                | undefined;
            const nodes =
                store.cardA11yNodes.get(previewId) ??
                hierarchyPayload?.nodes ??
                preview?.a11yNodes ??
                [];
            const findings =
                store.cardA11yFindings.get(previewId) ??
                atfPayload?.findings ??
                preview?.a11yFindings ??
                [];
            return { nodes, findings };
        };
        const a11yTouchTargetsForCard = (
            previewId: string,
        ): readonly import("./a11yBundlePresenter").AccessibilityTouchTarget[] => {
            const byKind = dataProductsByPreview.get(previewId);
            const raw = byKind?.get("a11y/touchTargets") as
                | { targets?: unknown }
                | undefined;
            const targets = raw?.targets;
            if (!Array.isArray(targets)) return [];
            return targets as readonly import("./a11yBundlePresenter").AccessibilityTouchTarget[];
        };
        // Captures the last refresh's per-preview a11y context so the
        // body's `row-clicked` listener (attached once in
        // `a11yBody()`) can build the detail panel from the freshest
        // data without re-attaching on every refresh.
        let a11yLastRefresh: {
            findings: readonly AccessibilityFinding[];
            touchTargets: readonly import("./a11yBundlePresenter").AccessibilityTouchTarget[];
        } | null = null;
        const refreshA11yBundle = (): void => {
            const target = currentBundleTarget();
            if (!target) return;
            const targetSrc = a11yDataForCard(target);
            const touchTargets = a11yTouchTargetsForCard(target);
            const data = computeA11yBundleData(
                targetSrc.nodes,
                targetSrc.findings,
                touchTargets,
            );
            const body = a11yBody();
            body.table.setRows(data.rows);
            body.table.summary = data.rows.length + " elements";
            body.table.setOverlayId(
                (row) => (row as { id: string }).id ?? "a11y-row",
            );
            // Mirror the daemon's wire shape so Copy JSON yields the
            // same bytes a CLI consumer would get from
            // `dataProducts/get` for each enabled kind, rather than a
            // UI-flattened projection. `a11y/hierarchy` and `a11y/atf`
            // live in `previewStore` (routed via `applyA11yUpdate`)
            // rather than the generic `dataProductsByPreview` cache, so
            // we rewrap them into their native payload envelopes here;
            // `a11y/touchTargets` and `a11y/overlay` come straight from
            // the daemon payload cache. Missing kinds emit `null` so
            // the output's shape is stable across subscription states.
            body.table.setJsonPayload(() => {
                const byKind = dataProductsByPreview.get(target);
                return {
                    previewId: target,
                    "a11y/hierarchy": { nodes: targetSrc.nodes },
                    "a11y/atf": { findings: targetSrc.findings },
                    "a11y/touchTargets":
                        byKind?.get("a11y/touchTargets") ?? null,
                    "a11y/overlay": byKind?.get("a11y/overlay") ?? null,
                };
            });
            // Stash for the row-click listener installed in
            // `a11yBody()`. Drop any stale selection so the detail
            // panel doesn't point at row indices the new data may
            // have renumbered.
            a11yLastRefresh = { findings: targetSrc.findings, touchTargets };
            body.table.setSelectedOverlayId(null);
            body.rowDetail.clear();
            refreshExpanderFor("a11y");
            dataTabs.setTabBody("a11y", body.wrapper);
            // Populate the shared bundle legend with the same rows so
            // the overlay-box on the preview, the legend swatch, and
            // the data-table row all share the `id` for hover /
            // selection correlation. Other bundles with overlay
            // boxes (inspection, history-diff) wire their entries
            // the same way; the legend swaps slices based on the
            // active tab.
            // Legend lists the focusable / TalkBack stops only —
            // merged nodes plus orphan finding / touch-target rows
            // (which carry their own bounds and synthesize as
            // merged=true). Unmerged children render in the data
            // table beneath their merged parent to show the tree
            // structure, but they share bounds with that parent and
            // would duplicate swatches on the overlay.
            const legendEntries: BundleLegendEntry[] = data.rows
                .filter((row) => row.bounds !== null && row.merged)
                .map((row, idx) => ({
                    id: row.id,
                    label: row.displayLabel,
                    detail: a11yLegendDetail(row),
                    level: row.topFindingLevel ?? "info",
                    color:
                        row.topFindingLevel === null
                            ? A11Y_LEGEND_PALETTE[
                                  idx % A11Y_LEGEND_PALETTE.length
                              ]
                            : undefined,
                }));
            bundleLegend.setBundleEntries(
                "a11y",
                "Accessibility",
                legendEntries,
            );
            reflectLegendActiveTab();
            // Paint the merged hierarchy + findings overlay. In focus
            // mode only the focused card paints; in grid mode every
            // visible card paints with its own per-card data. Realises
            // "Open question 1" from
            // `docs/design/EXTENSION_DATA_EXPOSURE.md`. Coexists with
            // the legacy `applyHierarchyOverlay` path in
            // `PreviewCard._repaintA11yOverlaysFromCache` for now; a
            // follow-up will remove the legacy paint once the new
            // path is verified end-to-end.
            paintOverlaysForBundle("a11y", (previewId) => {
                if (previewId === target) return data.overlay;
                const src = a11yDataForCard(previewId);
                const tts = a11yTouchTargetsForCard(previewId);
                return computeA11yBundleData(src.nodes, src.findings, tts)
                    .overlay;
            });
        };
        const refreshPerformanceBundle = (): void => {
            const target = currentBundleTarget();
            if (!target) return;
            const byKind = dataProductsByPreview.get(target);
            // Gate each cached payload on the user's current
            // enabled-kinds set — otherwise a kind the user has just
            // unchecked still shows its cached section, breaking the
            // immediate-unsubscribe contract the Configure expander
            // promises. Cache stays intact (the daemon keeps the
            // subscription open), but we render only what's enabled.
            const enabledKinds = bundleController
                .state()
                .enabledKinds("performance");
            const enabledSet = new Set(enabledKinds);
            const recompPayload = enabledSet.has("compose/recomposition")
                ? (byKind?.get("compose/recomposition") ?? null)
                : null;
            const tracePayload = enabledSet.has("render/trace")
                ? (byKind?.get("render/trace") ?? null)
                : null;
            const perfettoPayload = enabledSet.has("render/composeAiTrace")
                ? (byKind?.get("render/composeAiTrace") ?? null)
                : null;
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
                    enabledKinds,
                });
            }
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
                    (url) =>
                        vscode.postMessage({
                            command: "openExternal",
                            url,
                        }),
                );
            }
            dataTabs.setTabBody("performance", body.wrapper);
        };
        const refreshThemingBundle = (): void => {
            const target = currentBundleTarget();
            if (!target) return;
            const byKind = dataProductsByPreview.get(target);
            // The cached payload outlives a Configure-expander toggle:
            // the daemon stops streaming new updates once we unsubscribe,
            // but the last payload sits in `dataProductsByPreview`. If
            // we feed it into the presenter unconditionally the checkbox
            // looks broken — the rows persist even though the kind is
            // off. Honour the enabled-kinds set so unchecking "Theme
            // tokens" or "Wallpaper" actually clears the corresponding
            // section.
            const enabled = bundleController.state().enabledKinds("theming");
            const theme = enabled.includes("compose/theme")
                ? ((byKind?.get("compose/theme") as ThemePayload | undefined) ??
                  null)
                : null;
            const wallpaper = enabled.includes("compose/wallpaper")
                ? ((byKind?.get("compose/wallpaper") as
                      | WallpaperPayload
                      | undefined) ?? null)
                : null;
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

        // ---- Display bundle (displayfilter/*) ------------------------------
        const displayBodyBuilt = (): BundleBody => {
            let b = bundleBodies.get("display");
            if (b) return b;
            b = buildBundleBody(
                "display",
                "Display filters",
                displayFilterTableColumns() as unknown as ReadonlyArray<
                    import("./components/DataTable").DataTableColumn<unknown>
                >,
            );
            bundleBodies.set("display", b);
            return b;
        };
        const refreshDisplayBundle = (): void => {
            const target = currentBundleTarget();
            if (!target) return;
            // The daemon advertises a single `displayfilter/variants`
            // kind whose payload manifest enumerates every enabled
            // filter. Build rows from the latest attached payload, not
            // the bundle catalog or chip state — the on-disk manifest
            // is the source of truth for which filters actually have
            // PNGs to display.
            const byKind = dataProductsByPreview.get(target);
            const payload = (byKind?.get("displayfilter/variants") ??
                null) as DisplayFilterVariantsPayload | null;
            const data = computeDisplayFilterBundleData(payload);
            const body = displayBodyBuilt();
            const table = body.table;
            table.setRows(data.rows);
            table.summary =
                data.rows.length +
                " filter" +
                (data.rows.length === 1 ? "" : "s");
            table.setOverlayId(
                (row) => (row as { id: string }).id ?? "displayfilter-row",
            );
            table.setJsonPayload(() => ({
                previewId: target,
                variants: data.rows.map((r) => ({
                    filter: r.filterId,
                    label: r.label,
                    path: r.path,
                })),
            }));
            // TODO: when daemon-side per-filter override plumbing lands,
            // forward `row-selected` events to a `requestDisplayFilter`
            // wire message so clicking a row swaps the focused card's
            // image. Today the table is read-only.
            refreshExpanderFor("display");
            dataTabs.setTabBody("display", body.wrapper);
        };

        // ---- Resources bundle (resources/used) -----------------------------
        const resourcesBodyBuilt = (): BundleBody => {
            let b = bundleBodies.get("resources");
            if (b) return b;
            b = buildBundleBody(
                "resources",
                "Resources used",
                resourcesTableColumns() as unknown as ReadonlyArray<
                    import("./components/DataTable").DataTableColumn<unknown>
                >,
            );
            bundleBodies.set("resources", b);
            return b;
        };
        const refreshResourcesBundle = (): void => {
            const target = currentBundleTarget();
            if (!target) return;
            const byKind = dataProductsByPreview.get(target);
            const payload = byKind?.get("resources/used") ?? null;
            const data = computeResourcesBundleData(payload);
            const body = resourcesBodyBuilt();
            const table = body.table;
            table.setRows(data.rows);
            table.summary =
                data.rows.length +
                " reference" +
                (data.rows.length === 1 ? "" : "s");
            table.setOverlayId(
                (row) => (row as { id: string }).id ?? "resource-row",
            );
            table.setJsonPayload(() => ({
                previewId: target,
                payload,
            }));
            refreshExpanderFor("resources");
            dataTabs.setTabBody("resources", body.wrapper);
        };

        // ---- Watch bundle (compose/ambient) --------------------------------
        const ambientBodyBuilt = (): BundleBody => {
            let b = bundleBodies.get("watch");
            if (b) return b;
            b = buildBundleBody(
                "watch",
                "Ambient state",
                ambientTableColumns() as unknown as ReadonlyArray<
                    import("./components/DataTable").DataTableColumn<unknown>
                >,
            );
            bundleBodies.set("watch", b);
            return b;
        };
        const refreshWatchBundle = (): void => {
            const target = currentBundleTarget();
            if (!target) return;
            const byKind = dataProductsByPreview.get(target);
            const payload =
                (byKind?.get("compose/ambient") as
                    | AmbientPayload
                    | undefined) ?? null;
            const data = computeAmbientBundleData(payload);
            const body = ambientBodyBuilt();
            const table = body.table;
            table.setRows(data.rows);
            table.summary = data.state ?? "no data";
            table.setOverlayId(
                (row) => (row as { id: string }).id ?? "ambient-row",
            );
            table.setJsonPayload(() => ({
                previewId: target,
                payload,
            }));
            refreshExpanderFor("watch");
            dataTabs.setTabBody("watch", body.wrapper);
            stampAmbientBadge(target, data.state, data.stateLevel);
        };
        // Per-card ambient badge state — keyed on previewId so we can
        // clear or update it across focus changes without DOM churn.
        const ambientBadges = new Map<string, HTMLElement>();
        const stampAmbientBadge = (
            previewId: string,
            state: string | null,
            level: AmbientStateLevel,
        ): void => {
            const card = document.getElementById(
                "preview-" + previewId.replace(/[^a-zA-Z0-9_-]/g, "_"),
            );
            if (!card) return;
            const container =
                card.querySelector<HTMLElement>(".image-container");
            if (!container) return;
            let badge = ambientBadges.get(previewId);
            if (!state) {
                if (badge) {
                    badge.remove();
                    ambientBadges.delete(previewId);
                }
                return;
            }
            if (!badge) {
                badge = document.createElement("span");
                badge.className = "ambient-state-badge";
                ambientBadges.set(previewId, badge);
            }
            badge.dataset.level = level;
            badge.dataset.state = state;
            badge.textContent = state;
            if (badge.parentElement !== container) {
                container.appendChild(badge);
            }
        };
        // Tear down every stamped ambient badge — called when the
        // Watch bundle deactivates so stale telemetry doesn't linger
        // on cards after the chip is dismissed.
        const clearAllAmbientBadges = (): void => {
            for (const badge of ambientBadges.values()) {
                badge.remove();
            }
            ambientBadges.clear();
        };

        // ---- History bundle (history/diff/regions) -------------------------
        // Captures the freshest history payload so the body's
        // `row-clicked` listener (attached once in
        // `historyDiffBodyBuilt`) can rebuild the detail panel —
        // specifically the per-channel deltas, which the bundle row
        // doesn't carry — against the current data without re-
        // attaching on every refresh.
        let historyLastPayload: HistoryDiffPayload | null = null;
        const historyDiffBodyBuilt = (): BundleBody => {
            let b = bundleBodies.get("history");
            if (b) return b;
            b = buildBundleBody(
                "history",
                "History diff regions",
                historyDiffTableColumns() as unknown as ReadonlyArray<
                    import("./components/DataTable").DataTableColumn<unknown>
                >,
            );
            // Wire row click → detail panel, mirroring a11y. The
            // bundle row carries the Euclidean Δ magnitude only;
            // the detail builder re-joins against
            // `historyLastPayload` for per-channel deltas plus the
            // shared baseline-history context.
            b.table.addEventListener("row-clicked", (evt) => {
                const det = (
                    evt as CustomEvent<
                        import("./components/DataTable").RowClickedDetail<
                            import("./historyDiffBundlePresenter").HistoryDiffRow
                        >
                    >
                ).detail;
                if (!det.row || det.index === null) {
                    b!.rowDetail.clear();
                    return;
                }
                b!.rowDetail.setDetail(
                    det.row.boundsLabel || "Region " + (det.index + 1),
                    buildHistoryRowDetail(
                        det.row,
                        historyLastPayload,
                        det.index,
                    ),
                );
            });
            bundleBodies.set("history", b);
            return b;
        };
        // History tab body is a header + table — wrap them in a host
        // element kept across refreshes so the table doesn't lose its
        // hover state when the header is rebuilt.
        const refreshHistoryBundle = (): void => {
            const target = currentBundleTarget();
            if (!target) return;
            const byKind = dataProductsByPreview.get(target);
            const payload =
                (byKind?.get("history/diff/regions") as
                    | HistoryDiffPayload
                    | undefined) ?? null;
            const data = computeHistoryDiffBundleData(payload);
            const body = historyDiffBodyBuilt();
            const host = body.wrapper;
            // Wipe the prior header but keep the expander + table
            // mounted across refreshes — `<data-table>` updates in
            // place when its rows change. Stamp the new header just
            // before the table so the order is `[expander] [header]
            // [table]`.
            host.querySelector(".history-diff-header")?.remove();
            host.insertBefore(renderHistoryDiffHeader(data.header), body.table);
            const table = body.table;
            table.setRows(data.rows);
            table.summary =
                data.rows.length +
                " region" +
                (data.rows.length === 1 ? "" : "s");
            table.setOverlayId(
                (row) => (row as { id: string }).id ?? "history-diff-row",
            );
            table.setJsonPayload(() => ({
                previewId: target,
                payload,
            }));
            // Stash for the row-click listener installed once in
            // `historyDiffBodyBuilt()`. Drop the prior selection so
            // the detail panel doesn't point at row indices the new
            // payload may have renumbered.
            historyLastPayload = payload;
            body.table.setSelectedOverlayId(null);
            body.rowDetail.clear();
            refreshExpanderFor("history");
            dataTabs.setTabBody("history", host);
            bundleLegend.setBundleEntries(
                "history",
                "History diff",
                overlayToLegendEntries(data.overlay),
            );
            reflectLegendActiveTab();
            // Paint the per-region tinted boxes. Focus mode paints
            // only the focused card; grid mode paints every visible
            // card from its own `history/diff/regions` payload. Empty
            // arrays clear in place; the bundle-deactivation path in
            // `reflectBundleState` removes the layer entirely.
            paintOverlaysForBundle("history", (previewId) => {
                if (previewId === target) return data.overlay;
                const cardPayload =
                    (dataProductsByPreview
                        .get(previewId)
                        ?.get("history/diff/regions") as
                        | HistoryDiffPayload
                        | undefined) ?? null;
                return computeHistoryDiffBundleData(cardPayload).overlay;
            });
        };

        // ---- Errors bundle (test/failure) ----------------------------------
        const errorsBodyBuilt = (): BundleBody => {
            let b = bundleBodies.get("errors");
            if (b) return b;
            b = buildBundleBody(
                "errors",
                "Errors",
                errorsTableColumns() as unknown as ReadonlyArray<
                    import("./components/DataTable").DataTableColumn<unknown>
                >,
            );
            bundleBodies.set("errors", b);
            return b;
        };
        const refreshErrorsBundle = (): void => {
            const target = currentBundleTarget();
            if (!target) return;
            const byKind = dataProductsByPreview.get(target);
            const payload =
                (byKind?.get("test/failure") as
                    | TestFailurePayload
                    | undefined) ?? null;
            const data = computeErrorsBundleData(payload);
            const body = errorsBodyBuilt();
            const host = body.wrapper;
            const table = body.table;
            table.setRows(data.rows);
            table.summary = data.hasFailure
                ? data.stackFrames.length > 0
                    ? data.stackFrames.length +
                      " stack frame" +
                      (data.stackFrames.length === 1 ? "" : "s")
                    : "no stack"
                : "no failure";
            table.setOverlayId(
                (row) => (row as { id: string }).id ?? "errors-row",
            );
            table.setJsonPayload(() => ({
                previewId: target,
                payload,
            }));
            // Replace any prior stack-frames block; null path strips
            // the section when the new payload has no frames. The
            // expander + table mount inside `body.wrapper` already.
            host.querySelector(".errors-stack-frames")?.remove();
            const stack = renderErrorsStackFrames(data.stackFrames);
            if (stack) host.appendChild(stack);
            refreshExpanderFor("errors");
            dataTabs.setTabBody("errors", host);
        };

        // ---- Inspection bundle (compose/semantics, layout/inspector, uia/hierarchy) -
        // Three stacked tree-tables (one per kind) under one expander,
        // same shape as the Performance / Text bundles. The merged
        // overlay paints node bounds via the shared cardBundleOverlay
        // helper (same path as a11y / history-diff), keyed on
        // `data-bundle="inspection"` so multiple kinds within one
        // bundle still share one DOM layer.
        interface InspectionBody {
            wrapper: HTMLElement;
            expander: BundleExpander;
            host: HTMLElement;
            /** Shared `<bundle-row-detail>` panel rendered below the
             *  per-kind tree-table sections. The inspection refresh
             *  attaches a delegated click handler on `host` (since
             *  tree-table rows don't dispatch `row-clicked` like
             *  `<data-table>` does) and looks the clicked row's
             *  node up by `data-legend-id`. */
            rowDetail: BundleRowDetail;
        }
        // The inspection tree-tables don't dispatch `row-clicked`
        // (they're a bespoke primitive, not `<data-table>`), so the
        // body-level click handler reads the freshest payload's
        // `nodeById` lookup from here.
        let inspectionLastNodeById: ReadonlyMap<
            string,
            import("./inspectionPresenters").InspectionNodeRecord
        > | null = null;
        let inspectionSelectedRowId: string | null = null;
        const applyInspectionRowSelection = (row: HTMLElement | null): void => {
            if (!inspectionCachedBody) return;
            for (const prev of Array.from(
                inspectionCachedBody.host.querySelectorAll<HTMLElement>(
                    "tr.inspection-tree-row-selected",
                ),
            )) {
                prev.classList.remove("inspection-tree-row-selected");
            }
            if (row) row.classList.add("inspection-tree-row-selected");
        };
        let inspectionCachedBody: InspectionBody | null = null;
        const inspectionBody = (): InspectionBody => {
            if (inspectionCachedBody) return inspectionCachedBody;
            const wrapper = document.createElement("div");
            wrapper.className = "bundle-tab-body inspection-bundle-body";
            wrapper.dataset.bundle = "inspection";
            const expander = document.createElement(
                "bundle-expander",
            ) as BundleExpander;
            wireExpanderToController(expander, bundleController);
            const host = document.createElement("section");
            host.className = "inspection-bundle-host";
            const rowDetail = document.createElement(
                "bundle-row-detail",
            ) as BundleRowDetail;
            // Delegated row click handler — finds the nearest <tr>
            // with `data-legend-id`, looks up the node record from
            // the last refresh, and dispatches to
            // `buildInspectionRowDetail`. Re-clicking the selected
            // row deselects (panel cleared).
            host.addEventListener("click", (evt) => {
                const target = evt.target;
                if (!(target instanceof Element)) return;
                const row = target.closest<HTMLElement>("tr[data-legend-id]");
                if (!row || !host.contains(row)) return;
                const id = row.dataset.legendId ?? "";
                if (!id) return;
                if (inspectionSelectedRowId === id) {
                    inspectionSelectedRowId = null;
                    applyInspectionRowSelection(null);
                    rowDetail.clear();
                    return;
                }
                const record = inspectionLastNodeById?.get(id);
                if (!record) return;
                inspectionSelectedRowId = id;
                applyInspectionRowSelection(row);
                rowDetail.setDetail(
                    inspectionRowTitle(record),
                    buildInspectionRowDetail(record),
                );
            });
            wrapper.appendChild(expander);
            wrapper.appendChild(host);
            wrapper.appendChild(rowDetail);
            inspectionCachedBody = { wrapper, expander, host, rowDetail };
            return inspectionCachedBody;
        };
        const refreshInspectionBundle = (): void => {
            const target = currentBundleTarget();
            if (!target) return;
            const byKind = dataProductsByPreview.get(target);
            const enabledKindsRaw = new Set(
                bundleController.state().enabledKinds("inspection"),
            );
            const enabledKinds = new Set<InspectionKind>();
            for (const k of [
                "compose/semantics",
                "layout/inspector",
                "uia/hierarchy",
            ] as const) {
                if (enabledKindsRaw.has(k)) enabledKinds.add(k);
            }
            const data = computeInspectionBundleData(
                (kind) => byKind?.get(kind),
                enabledKinds,
            );
            const body = inspectionBody();
            const bundleDescriptor = getBundle("inspection");
            if (bundleDescriptor) {
                body.expander.setState({
                    bundleId: "inspection",
                    kinds: bundleDescriptor.kinds,
                    enabledKinds: bundleController
                        .state()
                        .enabledKinds("inspection"),
                });
            }
            // Repaint sections — the tree-table elements are throwaway
            // per refresh (rebuilt by `buildInspectionTreeTable`), so we
            // clear and re-append rather than diff in place. Cheap
            // enough for typical inspection trees (≤ few hundred nodes).
            body.host.replaceChildren();
            for (const section of data.sections) {
                const wrap = document.createElement("section");
                wrap.className = "inspection-bundle-section";
                wrap.dataset.kind = section.kind;
                wrap.appendChild(section.data.body);
                body.host.appendChild(wrap);
            }
            // Stash the per-id node lookup so the body-host click
            // handler can dispatch the detail panel without re-
            // walking the per-kind trees. Drop any prior selection
            // since the new payload may have renumbered the rows.
            inspectionLastNodeById = data.nodeById;
            inspectionSelectedRowId = null;
            applyInspectionRowSelection(null);
            body.rowDetail.clear();
            dataTabs.setTabBody("inspection", body.wrapper);
            bundleLegend.setBundleEntries(
                "inspection",
                "Inspection",
                overlayToLegendEntries(data.overlay),
            );
            reflectLegendActiveTab();
            // Paint the merged + de-duped overlay. In focus mode only
            // the focused card paints; in grid mode every visible card
            // paints with its own per-card data via the grid-aware
            // helper from #1096.
            paintOverlaysForBundle("inspection", (previewId) => {
                if (previewId === target) return data.overlay;
                const cardByKind = dataProductsByPreview.get(previewId);
                if (!cardByKind) return [];
                return computeInspectionBundleData(
                    (kind) => cardByKind.get(kind),
                    enabledKinds,
                ).overlay;
            });
        };

        // Compact "role · states" subtitle for the a11y legend entries.
        // Mirror of the legacy `a11yHierarchyPresenter` detail string
        // so users see the same short tag in both surfaces.
        const a11yLegendDetail = (row: {
            role: string;
            states: string;
            touchTargetSizeDp: string | null;
        }): string => {
            const parts: string[] = [];
            if (row.role) parts.push(row.role);
            if (row.states) parts.push(row.states);
            if (row.touchTargetSizeDp) parts.push(row.touchTargetSizeDp);
            return parts.join(" · ");
        };
        // Generic mapping from a bundle's `OverlayBox[]` to legend
        // entries. Tooltips on overlay boxes are already shaped as
        // `label · detail · …`, so splitting on ` · ` gives a clean
        // bold label + muted subtitle without bespoke per-bundle
        // wiring. Bundles that want a richer label / detail (e.g.
        // a11y carries `touchTargetSizeDp` separately) compose
        // their own; this is the common case.
        const overlayToLegendEntries = (
            boxes: readonly OverlayBox[],
        ): BundleLegendEntry[] =>
            boxes.map((b) => {
                const tooltip = b.tooltip ?? "";
                const parts = tooltip.split(" · ");
                return {
                    id: b.id,
                    label: parts[0] || b.id,
                    detail: parts.slice(1).join(" · "),
                    level: b.level ?? "info",
                    color: b.color,
                };
            });
        // Palette colours for info-level a11y entries — keep the
        // legend swatch in sync with the overlay's per-node colour
        // pick. Matches the `PALETTE` constant in
        // `a11yBundlePresenter.ts`.
        const A11Y_LEGEND_PALETTE: readonly string[] = [
            "#f28b82",
            "#aecbfa",
            "#a8dab5",
            "#fdd663",
            "#d7aefb",
            "#fcad70",
            "#80cbc4",
            "#f6aea9",
        ];
        // Show the legend slice for the currently active tab. Called
        // from each bundle's refresh after it stashes its entries,
        // and from `reflectBundleState` when the tab switches.
        const reflectLegendActiveTab = (): void => {
            const tab = bundleController.state().activeTab;
            // `focusController` is assigned later in `firstUpdated`,
            // and `reflectBundleState()` runs once at the bottom of
            // bundle setup before that assignment lands — guard so
            // the initial call (always with no active tab) doesn't
            // dereference an undefined controller. Subsequent
            // refresh calls run after the assignment.
            const inFocus = focusController?.inFocus() === true;
            const showBundleUi = inFocus && earlyFeatures();
            if (!showBundleUi || !tab) {
                bundleLegend.showBundle(null);
                bundleLegend.hidden = true;
                return;
            }
            const count = bundleLegend.showBundle(tab);
            // Hidden when the active bundle has no entries (e.g. the
            // Perf tab — no overlay boxes to legend) so the empty
            // panel doesn't reserve layout space next to the preview.
            bundleLegend.hidden = count === 0;
        };
        const reflectBundleState = (): void => {
            const s = bundleController.state();
            // Without early features only the graduated bundles (a11y for
            // now) show their chip — the rest are still in-progress and
            // shouldn't surface from the always-visible chip bar.
            const availableBundles: BundleId[] = earlyFeatures()
                ? s.bundles.map((b) => b.id)
                : ["a11y"];
            // Grey out inactive chips while the preview daemon is still
            // spawning. A click during that window queues subscriptions
            // whose follow-up renderNow races the warm-up render and
            // misses the daemon's subscriptionDrivenRenderMode lock —
            // surfacing the wait directly is clearer than the dataless
            // bundle body the user would otherwise see. Active chips
            // stay enabled so the user can still turn an in-flight
            // bundle off if they want.
            const daemonReady = isFocusedModuleReady(
                liveState.getModuleDaemonReady(),
            );
            bundleChipBar.setState({
                bundles: s.bundles,
                activeBundles: s.activeBundles,
                availableBundles,
                daemonReady,
            });
            dataTabs.setState({
                bundles: s.bundles,
                activeBundles: s.activeBundles,
                activeTab: s.activeTab,
            });
            if (s.activeBundles.includes("a11y")) {
                refreshA11yBundle();
            } else {
                clearBundleBoxes(null, "a11y");
            }
            if (s.activeBundles.includes("performance")) {
                refreshPerformanceBundle();
            }
            if (s.activeBundles.includes("theming")) refreshThemingBundle();
            if (s.activeBundles.includes("display")) refreshDisplayBundle();
            if (s.activeBundles.includes("resources")) refreshResourcesBundle();
            if (s.activeBundles.includes("watch")) {
                refreshWatchBundle();
            } else {
                clearAllAmbientBadges();
            }
            if (s.activeBundles.includes("history")) {
                refreshHistoryBundle();
            } else {
                // Tear down the per-card box-overlay layer on every
                // card the bundle painted into. Mirrors the
                // ambient-badge teardown above so chip dismissal
                // wipes every bundle-attached card surface.
                clearBundleBoxes(null, "history");
            }
            if (s.activeBundles.includes("errors")) refreshErrorsBundle();
            if (s.activeBundles.includes("text")) {
                refreshTextBundle();
            } else {
                clearBundleBoxes(null, "text");
            }
            if (s.activeBundles.includes("inspection")) {
                refreshInspectionBundle();
            } else {
                // Tear down the per-card box-overlay layer the bundle
                // painted into. Mirrors the history-diff teardown above
                // so chip dismissal wipes every bundle-attached card
                // surface.
                clearBundleBoxes(null, "inspection");
            }
            // Drop legend slices for bundles that are no longer
            // active so re-pressing the chip starts from a clean
            // state, and refresh the visible slice to track the
            // current tab.
            for (const b of s.bundles) {
                if (!s.activeBundles.includes(b.id)) {
                    bundleLegend.clearBundle(b.id);
                }
            }
            reflectLegendActiveTab();
            // The focus inspector's HISTORY panel is gated on the
            // history chip — re-render it so the panel mounts /
            // unmounts in lockstep with the chip state. Guarded
            // because `focusController` is initialised later in this
            // setup body and the initial `reflectBundleState()` call
            // below runs before it lands; the post-init `onChange`
            // path always finds it ready.
            if (focusController) {
                inspector.render(focusController.focusedCard());
            }
        };
        bundleController.onChange(() => reflectBundleState());
        reflectBundleState();
        // Rebind bundle subscriptions when the focused preview moves.
        // The chip's `setKindsEnabled` only posts at toggle time
        // against the then-current target; the daemon-side scheduler
        // unsubscribes silently when the target leaves `setVisible`.
        // Without this hop the chip stays pressed against a stale
        // (previewId, kind) set and the new focus reads an empty
        // a11y cache. The previewStore listener fires on every
        // `setState`, so we dedupe against the last target we saw.
        let lastBundleTarget = currentBundleTarget();
        const rebindBundleTarget = (): void => {
            const next = currentBundleTarget();
            if (next === lastBundleTarget) return;
            const prev = lastBundleTarget;
            lastBundleTarget = next;
            if (prev) {
                const prevKinds = bundleSubscriptions.get(prev);
                if (prevKinds && prevKinds.size > 0) {
                    postSetDataExtensionEnabled(prev, [...prevKinds], false);
                }
            }
            if (!next) return;
            const desired = desiredKindsForActiveBundles();
            if (desired.length === 0) return;
            postSetDataExtensionEnabled(next, desired, true);
        };
        previewStore.subscribe(rebindBundleTarget);
        // Mirror hover between the legend and the per-bundle overlay
        // layer. Only the *focused* card has a `<box-overlay>`
        // currently rendering the active bundle's boxes, so we hop to
        // it via the focusController and call `setActiveOverlayId`
        // on the layer that matches the active tab.
        const overlayLayerForActiveTab = ():
            | import("./components/BoxOverlay").BoxOverlay
            | null => {
            const tab = bundleController.state().activeTab;
            if (!tab) return null;
            const card = focusController.focusedCard();
            if (!card) return null;
            return card.querySelector<
                import("./components/BoxOverlay").BoxOverlay
            >(`box-overlay[data-bundle="${tab}"]`);
        };
        bundleLegend.addEventListener("legend-hovered", (evt) => {
            const det = (
                evt as CustomEvent<
                    import("./components/BundleLegend").BundleLegendHoveredDetail
                >
            ).detail;
            const layer = overlayLayerForActiveTab();
            if (layer) layer.setActiveOverlayId(det.entryId);
        });
        bundleLegend.addEventListener("legend-selected", (evt) => {
            const det = (
                evt as CustomEvent<
                    import("./components/BundleLegend").BundleLegendSelectedDetail
                >
            ).detail;
            const tab = bundleController.state().activeTab;
            if (!tab) return;
            // Scoped to the `<data-tabs>` subtree — see
            // `bundleLegendTarget.ts` for why a document-wide
            // selector picks the overlay box instead of the table row.
            const row = findLegendTarget(dataTabs, tab, det.entryId);
            row?.scrollIntoView({ block: "nearest", behavior: "smooth" });
        });
        bundleChipBar.addEventListener("bundle-toggled", (evt) => {
            const detail = (evt as CustomEvent<{ id: BundleId }>).detail;
            const wasActive = bundleController
                .state()
                .activeBundles.includes(detail.id);
            bundleController.toggleBundle(detail.id);
            // Chip + tabs sit below the preview, so a fresh activation
            // can scroll off-screen on tall previews. Bring the new tab
            // body into view so the click has visible feedback.
            if (!wasActive) {
                requestAnimationFrame(() => {
                    dataTabs.scrollIntoView({
                        behavior: "smooth",
                        block: "nearest",
                    });
                });
            }
        });
        dataTabs.addEventListener("tab-closed", (evt) => {
            const detail = (evt as CustomEvent<{ id: BundleId }>).detail;
            bundleController.closeTab(detail.id);
        });
        dataTabs.addEventListener("tab-selected", (evt) => {
            const detail = (evt as CustomEvent<{ id: BundleId | null }>).detail;
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
            bundleChipBar,
            dataTabs,
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
            onAfterPublish: (visible, previous) => {
                // Previews that just re-entered the viewport had their
                // `(previewId, kind)` subscriptions dropped on the
                // daemon side via `setVisible` cleanup. Re-issue the
                // host's intended subscriptions for those previews so
                // the next render attaches the bundle's data products
                // again. The mirror persists across visibility churn
                // (toggle is the only thing that clears it) so this
                // hook can rely on it as the source of truth.
                if (bundleSubscriptions.size === 0) return;
                const prevSet = new Set(previous);
                for (const id of visible) {
                    if (prevSet.has(id)) continue;
                    const kinds = bundleSubscriptions.get(id);
                    if (!kinds || kinds.size === 0) continue;
                    vscode.postMessage({
                        command: "setDataExtensionEnabled",
                        previewId: id,
                        kinds: [...kinds],
                        enabled: true,
                    });
                }
            },
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
                    activeBundles.includes("watch") &&
                    dataProducts.some((dp) => dp.kind === "compose/ambient")
                ) {
                    refreshWatchBundle();
                }
                if (
                    matchesTarget &&
                    activeBundles.includes("history") &&
                    dataProducts.some(
                        (dp) => dp.kind === "history/diff/regions",
                    )
                ) {
                    refreshHistoryBundle();
                }
                if (
                    matchesTarget &&
                    activeBundles.includes("errors") &&
                    dataProducts.some((dp) => dp.kind === "test/failure")
                ) {
                    refreshErrorsBundle();
                }
                // Auto-light the Errors chip when a test/failure
                // payload arrives. Hooked here rather than on the
                // daemon-side renderFailed because by the time
                // test/failure is fetched the panel already has the
                // payload via updateDataProducts; routing through the
                // dispatcher would need a new PreviewMessageContext
                // callback. Re-pressing the chip later still toggles
                // it off.
                if (dataProducts.some((dp) => dp.kind === "test/failure")) {
                    bundleController.handleExternalKindToggle(
                        "test/failure",
                        true,
                    );
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
                if (
                    matchesTarget &&
                    activeBundles.includes("text") &&
                    dataProducts.some(
                        (dp) =>
                            dp.kind === "text/strings" ||
                            dp.kind === "fonts/used" ||
                            dp.kind === "i18n/translations",
                    )
                ) {
                    refreshTextBundle();
                }
                if (
                    matchesTarget &&
                    activeBundles.includes("inspection") &&
                    dataProducts.some(
                        (dp) =>
                            dp.kind === "compose/semantics" ||
                            dp.kind === "layout/inspector" ||
                            dp.kind === "uia/hierarchy",
                    )
                ) {
                    refreshInspectionBundle();
                }
                if (
                    matchesTarget &&
                    activeBundles.includes("resources") &&
                    dataProducts.some((dp) => dp.kind === "resources/used")
                ) {
                    refreshResourcesBundle();
                }
            },
            applyFontPreviewBytes: (previewId, fontRowId, dataUri) => {
                // Stash the response (null included — represents "host
                // tried and the file couldn't be read"; we cache that
                // negative result so we don't retry on every re-render).
                let bucket = fontPreviewBytesByPreview.get(previewId);
                if (!bucket) {
                    bucket = new Map();
                    fontPreviewBytesByPreview.set(previewId, bucket);
                }
                bucket.set(fontRowId, dataUri);
                fontPreviewPending.delete(fontPreviewKey(previewId, fontRowId));
                // Inject the `@font-face` rule into the document head
                // when we have bytes. On null we leave the CSS-only
                // fallback in place — the cell already paints in the
                // resolved family stack.
                if (typeof dataUri === "string" && dataUri.length > 0) {
                    injectFontFace(previewId, fontRowId, dataUri);
                }
                // Re-render the Text bundle if we're showing the
                // preview the bytes belong to. Other previews don't
                // need a redraw — their cache entries are independent
                // and they re-render on their own bundle target swap.
                if (
                    bundleController.state().activeBundles.includes("text") &&
                    currentBundleTarget() === previewId
                ) {
                    refreshTextBundle();
                }
            },
            focusOnCard,
            deactivateAllBundles: () => bundleController.deactivateAll(),
            refreshBundleState: () => reflectBundleState(),
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
