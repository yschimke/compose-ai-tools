// Live (interactive) + recording state controller for the preview panel.
//
// Lifted verbatim from `behavior.ts`'s `applyLiveBadge` / `ensureLiveCardControls`
// / `stopAllInteractive` / `stopInteractiveForCard` / `toggleInteractive` /
// `setInteractiveForCard` / `enterInteractiveOnCard` / `toggleRecording`
// cluster, plus the lone-stream follow-focus teardown that used to live inline
// in `applyLayout` and the ad-hoc Set manipulations spread across the
// `setInteractiveAvailability` / `clearInteractive` / `clearRecording` /
// `setEarlyFeatures` / `setPreviews` message handlers.
//
// Owns two Sets:
//  - `interactivePreviewIds` — cards currently in v2 live (interactive) mode.
//  - `recordingPreviewIds` — cards currently capturing a recording.
//
// Both sets forward pointer / wheel input to the daemon (predicate exposed via
// `isLive` / `isRecording`); `attachInteractiveInputHandlers` from
// `./interactiveInput.ts` consults them through `interactiveInputConfig.isLive`.
//
// Also owns two per-module availability Maps populated from the
// `setInteractiveAvailability` wire message:
//  - `moduleDaemonReady` — whether the module's daemon is up and ready.
//  - `moduleInteractiveSupported` — whether the daemon advertises full v2
//    live mode (vs the Android/v1 fallback where renders refresh but pointer
//    input doesn't mutate held composition state).
// Writes flow through `setAvailability(moduleId, ready, interactiveSupported)`;
// reads are exposed as ReadonlyMaps so callers (focus toolbar predicates) keep
// their existing signatures.
//
// Plain click on the LIVE button is single-target — drop every prior stream
// before adding (or re-removing) this one. Shift+click is multi-target — toggle
// just this preview without disturbing the others. Recording is currently
// single-target only (Shift modifier intentionally not wired through).
//
// The controller posts the `requestStreamStart` / `requestStreamStop` /
// `setRecording` wire commands and then re-runs the supplied button-state
// hooks so the toolbar reflects the new truth synchronously. Silent
// variants (`handleDaemonLost`,
// `handleExtensionClearInteractive`, `handleExtensionClearRecording`,
// `pruneLive`) drop UI bookkeeping without posting back — those paths are
// triggered after the extension or daemon has already torn the streams down,
// and re-posting would race the flush.

import {
    liveToggleCommand,
    liveViewportCommand,
} from "../../daemon/liveCommand";
import { attachInteractiveInputHandlers } from "./interactiveInput";
import type { InteractiveInputConfig } from "./interactiveInput";
import { stampLiveBadgesOnGrid } from "./liveBadge";
import { planLiveToggle, planRecordingToggle } from "./liveTransitions";
import type { VsCodeApi } from "../shared/vscode";

export interface LiveStateConfig {
    vscode: VsCodeApi<unknown>;
    /** Selected recording format (`mp4` / `webm`). Read fresh on every
     *  recording action so format-dropdown changes take effect on the
     *  next gesture. */
    recordingFormat: HTMLSelectElement;
    /** Predicate config handed through to `attachInteractiveInputHandlers`
     *  for the per-card pointer/wheel listeners. The `isLive` it carries
     *  is expected to consult this controller (`isLive(id) || isRecording(id)`). */
    interactiveInputConfig: InteractiveInputConfig;
    /** Whether `composePreview.earlyFeatures` is on — recording is gated on it. */
    earlyFeatures(): boolean;
    /** Whether the panel is currently in focus layout. The Live / Recording
     *  toolbar buttons only act when one card is focused. */
    inFocus(): boolean;
    /** The currently-focused preview card, or null when none is focused. */
    focusedCard(): HTMLElement | null;
    /** Re-run focus-toolbar button-state hooks after a state change. */
    applyInteractiveButtonState(): void;
    applyRecordingButtonState(): void;
    /** Re-render the focus inspector for [card] — the inspector reads
     *  `isLive` / `isRecording` to keep its Tools strip in sync. */
    renderInspector(card: HTMLElement | null): void;
}

export class LiveStateController {
    // Mutable references — the planner-driven mutators in
    // `setInteractiveForCard` / `toggleRecording` replace these with
    // fresh Sets returned by the planners, while the local-mutation
    // methods (`stopAllInteractive`, the silent extension-clear paths,
    // etc.) call `.clear()` / `.delete()` directly.
    private interactivePreviewIds: Set<string> = new Set<string>();
    private recordingPreviewIds: Set<string> = new Set<string>();

    // Per-module availability — written from `setInteractiveAvailability`
    // via `setAvailability`, read by the focus toolbar predicates through
    // the `getModuleDaemonReady` / `getModuleInteractiveSupported`
    // ReadonlyMap accessors. See module-header doc.
    private readonly moduleDaemonReady = new Map<string, boolean>();
    private readonly moduleInteractiveSupported = new Map<string, boolean>();

    constructor(private readonly cfg: LiveStateConfig) {}

    /** Record per-module daemon readiness + interactive-support flags. The
     *  callers (`handleSetInteractiveAvailability`) coerce the wire payload
     *  to booleans before calling. */
    setAvailability(
        moduleId: string,
        ready: boolean,
        interactiveSupported: boolean,
    ): void {
        this.moduleDaemonReady.set(moduleId, ready);
        this.moduleInteractiveSupported.set(moduleId, interactiveSupported);
    }

    /** Read view of the per-module daemon-readiness map. Exposed as a
     *  ReadonlyMap so the existing `isFocusedModuleReady` /
     *  `isFocusedInteractiveSupported` predicates in `./moduleReadiness.ts`
     *  consume it without signature changes. */
    getModuleDaemonReady(): ReadonlyMap<string, boolean> {
        return this.moduleDaemonReady;
    }

    /** Read view of the per-module interactive-supported map. See
     *  `getModuleDaemonReady`. */
    getModuleInteractiveSupported(): ReadonlyMap<string, boolean> {
        return this.moduleInteractiveSupported;
    }

    /**
     * Posts the live-mode wire command for [previewId] — routes through
     * [liveToggleCommand] which always picks `requestStreamStart` /
     * `requestStreamStop` now that streaming is the only live path.
     * Single choke point so every per-card / toolbar / focus-mode entry
     * point shares one rule.
     */
    private postLiveCommand(previewId: string, enabled: boolean): void {
        this.cfg.vscode.postMessage(liveToggleCommand(previewId, enabled));
    }

    isLive(previewId: string): boolean {
        return this.interactivePreviewIds.has(previewId);
    }

    isRecording(previewId: string): boolean {
        return this.recordingPreviewIds.has(previewId);
    }

    get liveCount(): number {
        return this.interactivePreviewIds.size;
    }

    get recordingCount(): number {
        return this.recordingPreviewIds.size;
    }

    /** Re-stamp every `.preview-card.live` decoration from the current set.
     *  Tear-down first so removals (Shift+click off, daemon-not-ready,
     *  setPreviews dropping a previewId) cleanly wipe the prior decoration.
     *
     *  The DOM mutation lives in `./liveBadge.ts` so it's testable under
     *  happy-dom without dragging this controller's wider transitive
     *  imports into the host tsconfig; we delegate, passing
     *  `ensureLiveCardControls` as the per-card overlay hook. */
    applyLiveBadge(): void {
        stampLiveBadgesOnGrid(this.interactivePreviewIds, (card) =>
            this.ensureLiveCardControls(card),
        );
    }

    /** Toolbar `<i class="codicon codicon-debug-stop">` button — stop every
     *  live stream at once. */
    stopAllInteractive(): void {
        if (this.interactivePreviewIds.size === 0) return;
        const ids = Array.from(this.interactivePreviewIds);
        this.interactivePreviewIds.clear();
        ids.forEach((previewId) => {
            this.postLiveCommand(previewId, false);
        });
        this.applyLiveBadge();
        this.cfg.applyInteractiveButtonState();
    }

    /** Per-card stop button (the codicon overlay) — stop only [card]. */
    stopInteractiveForCard(card: HTMLElement): void {
        const previewId = card.dataset.previewId;
        if (!previewId || !this.interactivePreviewIds.has(previewId)) return;
        this.interactivePreviewIds.delete(previewId);
        this.postLiveCommand(previewId, false);
        this.applyLiveBadge();
        this.cfg.applyInteractiveButtonState();
    }

    /** Focus-mode LIVE button — operates on the currently focused card. */
    toggleInteractive(shift: boolean): void {
        if (!this.cfg.inFocus()) return;
        const card = this.cfg.focusedCard();
        if (!card) return;
        this.setInteractiveForCard(card, shift);
    }

    /**
     * Toggle interactive mode for [card] honouring plain/Shift semantics:
     *  - Plain: single-target. Drop every prior live target before adding (or
     *    re-removing) this one — keeps the casual UX matching v1's "one card
     *    live at a time" mental model.
     *  - Shift: multi-target. Toggle just this preview in/out of the live set
     *    without touching the others.
     */
    setInteractiveForCard(card: HTMLElement, shift: boolean): void {
        const previewId = card.dataset.previewId;
        if (!previewId) return;
        const plan = planLiveToggle(
            this.interactivePreviewIds,
            previewId,
            shift,
        );
        for (const prior of plan.deactivate) {
            this.postLiveCommand(prior, false);
        }
        this.interactivePreviewIds = plan.next;
        if (plan.turnOnTarget) {
            attachInteractiveInputHandlers(
                card,
                this.cfg.interactiveInputConfig,
            );
        }
        this.postLiveCommand(previewId, plan.turnOnTarget);
        this.applyLiveBadge();
        this.cfg.applyInteractiveButtonState();
        this.cfg.renderInspector(card);
    }

    /** Single-click-to-LIVE entry point from the in-card image click handler.
     *  Same effect as `setInteractiveForCard` — alias kept so the call site
     *  documents intent. */
    enterInteractiveOnCard(card: HTMLElement, shift: boolean): void {
        this.setInteractiveForCard(card, shift);
    }

    /** Focus-mode REC button — operates on the currently focused card.
     *  Recording is currently single-target; no Shift modifier. */
    toggleRecording(): void {
        if (!this.cfg.earlyFeatures()) return;
        if (!this.cfg.inFocus()) return;
        const card = this.cfg.focusedCard();
        const previewId = card ? card.dataset.previewId : null;
        if (!card || !previewId) return;
        const format = this.cfg.recordingFormat.value;
        const plan = planRecordingToggle(this.recordingPreviewIds, previewId);
        for (const prior of plan.deactivate) {
            this.cfg.vscode.postMessage({
                command: "setRecording",
                previewId: prior,
                enabled: false,
                format,
            });
        }
        this.recordingPreviewIds = plan.next;
        if (plan.turnOnTarget) {
            attachInteractiveInputHandlers(
                card,
                this.cfg.interactiveInputConfig,
            );
        }
        this.cfg.vscode.postMessage({
            command: "setRecording",
            previewId,
            enabled: plan.turnOnTarget,
            format,
        });
        this.cfg.applyRecordingButtonState();
        this.cfg.renderInspector(card);
    }

    /**
     * Single-target follow-focus teardown: when there is exactly one live
     * stream and the user navigates off it, drop the stream so the LIVE chip
     * follows the focused card. Multi-target (size > 1) is treated as an
     * explicit opt-in via Shift+click — those streams persist across focus
     * navigation until the user explicitly toggles them off.
     */
    enforceSingleTargetFollowFocus(focusedCard: HTMLElement | null): void {
        if (this.interactivePreviewIds.size !== 1) return;
        const lone = this.interactivePreviewIds.values().next().value;
        if (lone === undefined) return;
        if (focusedCard && focusedCard.dataset.previewId === lone) return;
        this.postLiveCommand(lone, false);
        this.interactivePreviewIds.clear();
        this.applyLiveBadge();
    }

    /**
     * Viewport callback — soft-throttle a live stream once its card has
     * scrolled fully out of view.
     *
     * The daemon's `stream/visibility` "throttle to keyframes-only" mode
     * keeps the held session warm so scroll-back-into-view repaints from
     * the cached anchor instead of cold-blanking. The local
     * `interactivePreviewIds` set still says "this card is live" so the
     * LIVE badge survives the throttle.
     */
    onCardLeftViewport(previewId: string): void {
        if (!this.interactivePreviewIds.has(previewId)) return;
        this.cfg.vscode.postMessage(liveViewportCommand(previewId, false));
    }

    /** Drop live previewIds that are gone from a fresh setPreviews manifest.
     *  Silent — the preview no longer exists for the daemon to dispatch into
     *  anyway. Caller is expected to follow up with `applyLiveBadge` +
     *  `applyInteractiveButtonState`. */
    pruneLive(stillExists: (previewId: string) => boolean): void {
        this.interactivePreviewIds.forEach((id) => {
            if (!stillExists(id)) this.interactivePreviewIds.delete(id);
        });
    }

    /** Daemon-not-ready — drop UI bookkeeping silently. */
    handleDaemonLost(): void {
        if (this.interactivePreviewIds.size > 0) {
            this.interactivePreviewIds.clear();
            this.applyLiveBadge();
        }
        if (this.recordingPreviewIds.size > 0) {
            this.recordingPreviewIds.clear();
        }
        this.cfg.applyInteractiveButtonState();
        this.cfg.applyRecordingButtonState();
    }

    /** Extension-driven `clearInteractive` — silent, the extension already
     *  stopped the streams server-side. */
    handleExtensionClearInteractive(previewId: string | null): void {
        if (previewId) {
            this.interactivePreviewIds.delete(previewId);
            this.applyLiveBadge();
            this.cfg.applyInteractiveButtonState();
        } else if (this.interactivePreviewIds.size > 0) {
            this.interactivePreviewIds.clear();
            this.applyLiveBadge();
            this.cfg.applyInteractiveButtonState();
        }
    }

    handleExtensionClearRecording(previewId: string | null): void {
        if (previewId) {
            this.recordingPreviewIds.delete(previewId);
        } else if (this.recordingPreviewIds.size > 0) {
            this.recordingPreviewIds.clear();
        }
        this.cfg.applyRecordingButtonState();
    }

    /** Early-features flag flipped off — explicitly stop every recording
     *  (live is implicitly torn down by the daemon-side teardown earlier in
     *  the same setEarlyFeatures path). */
    handleEarlyFeaturesDisabled(): void {
        if (this.recordingPreviewIds.size === 0) return;
        const format = this.cfg.recordingFormat.value;
        this.recordingPreviewIds.forEach((previewId) => {
            this.cfg.vscode.postMessage({
                command: "setRecording",
                previewId,
                enabled: false,
                format,
            });
        });
        this.recordingPreviewIds.clear();
    }

    private ensureLiveCardControls(card: HTMLElement): void {
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
                this.stopInteractiveForCard(card);
            });
            container.appendChild(btn);
        }
        attachInteractiveInputHandlers(card, this.cfg.interactiveInputConfig);
    }
}
