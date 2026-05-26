// Focus-mode orchestration for the live "Compose Preview" panel.
//
// Lifted verbatim from `behavior.ts`'s `applyLayout` / `publishScopedPreview`
// / `focusOnCard` / `exitFocus` / `requestFocusedDiff` /
// `requestLaunchOnDevice` / `toggleA11yOverlay` cluster, plus the four
// `applyXxxButtonState` hooks that drive the focus-mode toolbar. After
// this lift `behavior.ts` is mostly setup + message-event wiring; the
// focus-mode behaviour lives here.
//
// Owns no closure state of its own — the panel-level scalars (focusIndex,
// previousLayout, lastScopedPreviewId, a11yOverlayPreviewId) live in
// `previewStore` and are read/written via the getter/setter pairs the
// caller threads through `FocusControllerConfig`. The persisted-state
// scalar (`state.layout`) is shared by-reference so layout writes persist
// through the same `vscode.setState(state)` round-trip the rest of the
// panel uses.

import { showDiffOverlay, type DiffOverlayConfig } from "./diffOverlay";
import {
    type FocusToolbarController,
    isFocusedInteractiveSupported,
    isFocusedModuleReady,
} from "./focusToolbar";
import type { LiveStateController } from "./liveState";
import type { FilterToolbar } from "./components/FilterToolbar";
import { previewStore } from "./previewStore";
import type { PreviewGrid } from "./components/PreviewGrid";
import type { DiffMode } from "../shared/diffModeBar";
import type { VsCodeApi } from "../shared/vscode";

/** Mutable persisted-state scalar shared with `behavior.ts`. The
 *  controller mutates `state.layout` and the caller persists via
 *  `vscode.setState(state)`. */
export interface FocusControllerPersistedState {
    layout?: "grid" | "flow" | "column" | "focus";
    diffMode?: DiffMode;
    filters?: { fn?: string; group?: string };
    /** Previously focused previewId — written on every focus navigation
     *  so a webview reload can verify the focused preview still belongs
     *  to the currently active file before re-entering focus mode.
     *  See the deferred restore at boot in `main.ts`. */
    focusedPreviewId?: string | null;
    /** Layout to fall back to when exiting focus. Persisted (not just
     *  held in `previewStore`) so the cold-boot "drop out of focus if
     *  the focused preview isn't in the current file" path can restore
     *  the prior dropdown choice. */
    previousLayout?: "grid" | "flow" | "column";
}

export interface FocusControllerConfig {
    vscode: VsCodeApi<FocusControllerPersistedState>;
    grid: PreviewGrid;
    filterToolbar: FilterToolbar;
    /** `<div id="focus-controls">` — the focus-mode toolbar container.
     *  Toggled `hidden` based on layout mode. */
    focusControls: HTMLElement;
    /** `<bundle-chip-bar>` — data-extensions chip strip, rendered below
     *  the preview grid. Visible only in focus layout; the bundles only
     *  act on the focused preview, so the strip would mislead outside
     *  focus mode. */
    bundleChipBar: HTMLElement;
    /** `<data-tabs>` — active-bundle tab row, rendered below the chip
     *  strip. Same focus-only visibility rule as `bundleChipBar`. */
    dataTabs: HTMLElement;
    /** Re-evaluate `<bundle-legend>` visibility. The legend rides the
     *  focus stage (visible only in focus mode, hidden when the active
     *  tab has no entries) but its `hidden` flag is otherwise only
     *  written by `reflectLegendActiveTab` on bundle-state changes. Wire
     *  this from main so layout transitions also re-evaluate it —
     *  without it, activating a11y in focus mode and then exiting
     *  leaves the legend rendering next to the preview grid. */
    refreshBundleLegend(): void;
    focusToolbar: FocusToolbarController;
    /** Repaint the focus inspector for the given card, or clear it
     *  when `null` (no card focused / exiting focus layout). */
    inspectorRender(card: HTMLElement | null): void;
    liveState: LiveStateController;
    diffOverlayConfig: DiffOverlayConfig;
    /** Shared mutable persisted state. The controller writes
     *  `state.layout` directly and calls `vscode.setState(state)` for
     *  layout transitions. */
    state: FocusControllerPersistedState;
    earlyFeatures(): boolean;
    /** When true the panel is a bundle viewer. Daemon-driven buttons
     *  gate on [bundleDaemonReady] instead of being forced hidden.
     *  Module-only buttons (launch on device, diff vs HEAD/main) stay
     *  hidden regardless. Optional so non-bundle hosts can omit it. */
    bundleMode?(): boolean;
    /** True once `bundleDaemonReady` has been observed for this panel.
     *  Only consulted when [bundleMode] returns true. */
    bundleDaemonReady?(): boolean;
    getA11yOverlayId(): string | null;
    setA11yOverlayId(id: string | null): void;
    getFocusIndex(): number;
    setFocusIndex(idx: number): void;
    getPreviousLayout(): "grid" | "flow" | "column";
    setPreviousLayout(layout: "grid" | "flow" | "column"): void;
    getLastScopedPreviewId(): string | null;
    setLastScopedPreviewId(id: string | null): void;
}

export class FocusController {
    constructor(private readonly config: FocusControllerConfig) {}

    getVisibleCards(): HTMLElement[] {
        return this.config.grid.getVisibleCards();
    }

    /** Whether the panel is currently in focus layout. */
    inFocus(): boolean {
        return this.config.filterToolbar.getLayoutValue() === "focus";
    }

    /** Currently-focused preview card, or null when no card is focused. */
    focusedCard(): HTMLElement | null {
        if (!this.inFocus()) return null;
        const visible = this.getVisibleCards();
        return visible[this.config.getFocusIndex()] ?? null;
    }

    applyEarlyFeatureVisibility(): void {
        const bundle = this.config.bundleMode?.() ?? false;
        this.config.focusToolbar.applyEarlyFeatureVisibility({
            earlyFeatures: this.config.earlyFeatures(),
            inFocus: this.inFocus(),
            bundleMode: bundle,
            // In non-bundle mode, daemon readiness is intentionally
            // forced true — sidebar panels gate daemon-backed buttons
            // per-button via the `applyXxxButtonState` hooks instead.
            bundleDaemonReady:
                !bundle || (this.config.bundleDaemonReady?.() ?? false),
        });
    }

    applyInteractiveButtonState(): void {
        const inFocus = this.inFocus();
        const card = inFocus
            ? this.getVisibleCards()[this.config.getFocusIndex()]
            : null;
        const previewId = card?.dataset.previewId ?? null;
        const isLive = !!previewId && this.config.liveState.isLive(previewId);
        this.config.focusToolbar.applyInteractiveButtonState({
            inFocus,
            focusedPreviewId: previewId,
            isLive,
            otherLiveCount: this.config.liveState.liveCount - (isLive ? 1 : 0),
            hasLive: this.config.liveState.liveCount > 0,
            daemonReady: isFocusedModuleReady(
                this.config.liveState.getModuleDaemonReady(),
            ),
            interactiveSupported: isFocusedInteractiveSupported(
                this.config.liveState.getModuleDaemonReady(),
                this.config.liveState.getModuleInteractiveSupported(),
            ),
        });
    }

    applyRecordingButtonState(): void {
        const inFocus = this.inFocus();
        const card = inFocus
            ? this.getVisibleCards()[this.config.getFocusIndex()]
            : null;
        const previewId = card?.dataset.previewId ?? null;
        this.config.focusToolbar.applyRecordingButtonState({
            inFocus,
            earlyFeatures: this.config.earlyFeatures(),
            focusedPreviewId: previewId,
            daemonReady: isFocusedModuleReady(
                this.config.liveState.getModuleDaemonReady(),
            ),
            isRecording:
                !!previewId && this.config.liveState.isRecording(previewId),
        });
    }

    applyA11yOverlayButtonState(): void {
        const inFocus = this.inFocus();
        const card = inFocus
            ? this.getVisibleCards()[this.config.getFocusIndex()]
            : null;
        this.config.focusToolbar.applyA11yOverlayButtonState({
            inFocus,
            earlyFeatures: this.config.earlyFeatures(),
            focusedPreviewId: card?.dataset.previewId ?? null,
            a11yOverlayId: this.config.getA11yOverlayId(),
        });
    }

    /**
     * Push the touch-overlay / keyboard-band / controls toggle state for the
     * focused preview onto the focus-toolbar buttons. These were previously
     * stamped as per-card overlay icons on top of the rendered preview
     * (the legacy `card-*-toggle-btn` overlays); they now live exclusively in
     * the focus-controls bar so they stop covering the preview content.
     */
    applyFocusedToggleButtonStates(): void {
        const inFocus = this.inFocus();
        const card = inFocus
            ? this.getVisibleCards()[this.config.getFocusIndex()]
            : null;
        const previewId = card?.dataset.previewId ?? null;
        const live = this.config.liveState;
        this.config.focusToolbar.applyTouchOverlayButtonState({
            inFocus,
            focusedPreviewId: previewId,
            advertised: live.isTouchOverlayAdvertised(),
            enabled:
                previewId !== null && live.isTouchOverlayEnabled(previewId),
        });
        this.config.focusToolbar.applyKeyboardBandButtonState({
            inFocus,
            focusedPreviewId: previewId,
            // Wear OS surfaces have no software keyboard to force open, so
            // the toggle is meaningless there — hide it on round-watch
            // previews instead of showing a dead button on an already
            // cramped focus toolbar. `data-wear-preview` is stamped by
            // cardBuilder/cardMetadata via `isWearPreview`.
            advertised:
                live.isKeyboardBandAdvertised() &&
                card?.dataset.wearPreview !== "1",
            enabled: previewId !== null && live.isKeyboardBandForced(previewId),
        });
        this.config.focusToolbar.applyControlsButtonState({
            inFocus,
            focusedPreviewId: previewId,
            advertised: live.isControlsAdvertised(),
            enabled: previewId !== null && live.isControlsEnabled(previewId),
        });
        this.config.focusToolbar.applyLauncherWidgetButtonState({
            inFocus,
            focusedPreviewId: previewId,
            advertised: live.isLauncherWidgetAdvertised(),
            enabled:
                previewId !== null &&
                live.launcherWidgetCellsForPreview(previewId) !== null,
        });
    }

    /**
     * D2 — clicking the a11y toggle subscribes/unsubscribes via the
     * extension. When turning OFF, the extension also pushes an empty
     * updateA11y so the cached overlay tears down immediately rather
     * than waiting for a next render. When turning ON for a different
     * preview, first turn the previous one off so the wire stays clean.
     */
    toggleA11yOverlay(): void {
        // a11y is the graduated bundle — no early-features gate (the chip bar
        // and the focus-toolbar button are both visible in basic mode, the
        // host's `setA11yOverlay` handler also drops its gate). Other entry
        // points (`launch-on-device`, `record`, `export-bundle`) keep theirs.
        if (!this.inFocus()) return;
        const card = this.getVisibleCards()[this.config.getFocusIndex()];
        const previewId = card ? card.dataset.previewId : null;
        if (!previewId) return;
        const currentId = this.config.getA11yOverlayId();
        const turningOn = previewId !== currentId;
        if (currentId && currentId !== previewId) {
            this.config.vscode.postMessage({
                command: "setA11yOverlay",
                previewId: currentId,
                enabled: false,
            });
        }
        this.config.setA11yOverlayId(turningOn ? previewId : null);
        this.config.vscode.postMessage({
            command: "setA11yOverlay",
            previewId,
            enabled: turningOn,
        });
        this.applyA11yOverlayButtonState();
        this.config.inspectorRender(card);
    }

    applyLayout(): void {
        const mode = this.config.filterToolbar.getLayoutValue();
        const inFocus = mode === "focus";
        this.config.grid.setLayoutMode(mode);
        // Focus is the inspector mode: it owns the focus toolbar, chip
        // bar, data-extension tabs, and the focus inspector sidebar.
        // Multi modes (grid/flow/column) are the browser modes: filter
        // toolbar only, no extensions. The split keeps each mode
        // single-purpose so the user is never looking at extension
        // data for a preview that isn't the one they targeted.
        this.config.focusControls.hidden = !inFocus;
        this.config.bundleChipBar.hidden = !inFocus;
        this.config.dataTabs.hidden = !inFocus;
        // Filter dropdowns are a multi-mode concept (which previews to
        // show in the grid); in focus mode the user has already picked.
        // `<filter-toolbar>` and the focus-inspector sidebar are also
        // managed elsewhere — the inspector toggles its own `hidden`
        // through `inspectorRender(card | null)` below.
        this.config.filterToolbar.hidden = inFocus;

        if (inFocus) {
            const visible = this.getVisibleCards();
            if (visible.length === 0) {
                this.config.inspectorRender(null);
                this.publishScopedPreview();
                this.config.refreshBundleLegend();
                return;
            }
            let focusIndex = this.config.getFocusIndex();
            if (focusIndex >= visible.length) {
                focusIndex = visible.length - 1;
                this.config.setFocusIndex(focusIndex);
            }
            if (focusIndex < 0) {
                focusIndex = 0;
                this.config.setFocusIndex(0);
            }
            this.config.grid.applyFocusVisibility(visible[focusIndex]);
            this.config.inspectorRender(visible[focusIndex]);
        } else {
            this.config.grid.applyFocusVisibility(null);
            this.config.inspectorRender(null);
        }
        document
            .querySelectorAll(".image-container")
            .forEach((c) => c.removeAttribute("title"));
        this.publishScopedPreview();
        // Single-target follow-focus teardown — see
        // `LiveStateController.enforceSingleTargetFollowFocus`.
        this.config.liveState.enforceSingleTargetFollowFocus(
            mode === "focus"
                ? (this.getVisibleCards()[this.config.getFocusIndex()] ?? null)
                : null,
        );
        // D2 — same teardown for the a11y overlay: navigating off the
        // previewed card (or exiting focus mode) unsubscribes so the wire
        // stays quiet for cards the user isn't looking at.
        const a11yId = this.config.getA11yOverlayId();
        if (a11yId) {
            const visible = this.getVisibleCards();
            const card =
                mode === "focus" ? visible[this.config.getFocusIndex()] : null;
            if (!card || card.dataset.previewId !== a11yId) {
                if (this.config.earlyFeatures()) {
                    this.config.vscode.postMessage({
                        command: "setA11yOverlay",
                        previewId: a11yId,
                        enabled: false,
                    });
                }
                this.config.setA11yOverlayId(null);
            }
        }
        this.applyInteractiveButtonState();
        this.applyRecordingButtonState();
        this.applyA11yOverlayButtonState();
        this.applyFocusedToggleButtonStates();
        this.applyEarlyFeatureVisibility();
        this.config.refreshBundleLegend();
    }

    /**
     * Compute the focus-mode previewId. History is intentionally focus-
     * only: list/grid/filter layouts publish null even if only one card is
     * visible. Posts only when it changes so the extension does not
     * rebuild history scope on ordinary filter/layout churn.
     */
    publishScopedPreview(): void {
        const visible = this.getVisibleCards();
        let previewId: string | null = null;
        if (this.inFocus()) {
            const focusIndex = this.config.getFocusIndex();
            if (
                visible.length > 0 &&
                focusIndex >= 0 &&
                focusIndex < visible.length
            ) {
                previewId = visible[focusIndex].dataset.previewId || null;
            }
        }
        const prev = this.config.getLastScopedPreviewId();
        if (previewId === prev) return;
        this.config.setLastScopedPreviewId(previewId);
        // Mirror to the store so subscribed components (the upcoming
        // `<focus-controls>`, `<focus-inspector>`, etc.) react without
        // re-walking the DOM. Same value goes upstream to the extension
        // so the History panel can re-scope.
        previewStore.setState({ focusedPreviewId: previewId });
        // Also persist so the next webview boot can verify the focused
        // preview is still in the active file's manifest before re-
        // entering focus mode.
        if (this.config.state.focusedPreviewId !== previewId) {
            this.config.state.focusedPreviewId = previewId;
            this.config.vscode.setState(this.config.state);
        }
        this.config.vscode.postMessage({
            command: "previewScopeChanged",
            previewId,
        });
    }

    /**
     * Switch the layout to focus mode and target the supplied card. No-op
     * when the card is filtered out (it wouldn't be in the visible set
     * anyway, and forcing focus on an invisible card surfaces an empty
     * pane).
     */
    focusOnCard(card: HTMLElement): void {
        const visible = this.getVisibleCards();
        const idx = visible.indexOf(card);
        if (idx === -1) return;
        this.config.setFocusIndex(idx);
        const current = this.config.filterToolbar.getLayoutValue();
        if (current !== "focus") {
            this.config.setPreviousLayout(current);
            this.config.filterToolbar.setLayoutValue("focus");
            this.config.state.layout = "focus";
            this.config.vscode.setState(this.config.state);
        }
        this.applyLayout();
    }

    exitFocus(): void {
        if (!this.inFocus()) return;
        const target = this.config.getPreviousLayout();
        this.config.filterToolbar.setLayoutValue(target);
        this.config.state.layout = target;
        this.config.vscode.setState(this.config.state);
        this.applyLayout();
    }

    /**
     * Live-panel diff: only meaningful when one preview is focused. Pulls
     * the currently focused card's previewId and asks the extension to
     * resolve the comparison anchor (HEAD = latest archived render, main
     * = latest archived render on the main branch).
     */
    requestFocusedDiff(against: "head" | "main"): void {
        if (!this.config.earlyFeatures()) return;
        if (!this.inFocus()) return;
        const visible = this.getVisibleCards();
        const card = visible[this.config.getFocusIndex()];
        if (!card) return;
        const previewId = card.dataset.previewId;
        if (!previewId) return;
        showDiffOverlay(
            card,
            against,
            null,
            null,
            this.config.diffOverlayConfig,
        );
        this.config.vscode.postMessage({
            command: "requestPreviewDiff",
            previewId,
            against,
        });
    }

    /**
     * Live-panel "Launch on Device": runs the consumer's installDebug
     * task and uses adb to start the launcher activity on a connected
     * device. Only meaningful when one preview is focused — the
     * extension uses the focused previewId to pick the owning module
     * before falling back to a quick-pick.
     */
    requestLaunchOnDevice(): void {
        if (!this.config.earlyFeatures()) return;
        if (!this.inFocus()) return;
        const visible = this.getVisibleCards();
        const card = visible[this.config.getFocusIndex()];
        if (!card) return;
        const previewId = card.dataset.previewId;
        if (!previewId) return;
        this.config.vscode.postMessage({
            command: "requestLaunchOnDevice",
            previewId,
        });
    }

    /**
     * Early-feature export: pack the focused preview into a portable
     * PNG+ZIP polyglot via the consumer module's `composePreviewBundle`
     * task. The host shows a save dialog and then runs the Gradle task
     * with `-PbundlePreviewIds=<focused>` so the resulting file contains
     * just this one preview (cover = its rendered image).
     */
    requestExportPreviewBundle(): void {
        if (!this.config.earlyFeatures()) return;
        if (!this.inFocus()) return;
        const visible = this.getVisibleCards();
        const card = visible[this.config.getFocusIndex()];
        if (!card) return;
        const previewId = card.dataset.previewId;
        if (!previewId) return;
        this.config.vscode.postMessage({
            command: "requestExportPreviewBundle",
            previewId,
        });
    }
}
