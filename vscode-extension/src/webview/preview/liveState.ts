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
// Plain click on the LIVE button is single-target — drop every prior stream
// before adding (or re-removing) this one. Shift+click is multi-target — toggle
// just this preview without disturbing the others. Recording is currently
// single-target only (Shift modifier intentionally not wired through).
//
// The controller posts the `setInteractive` / `setRecording` wire commands and
// then re-runs the supplied button-state hooks so the toolbar reflects the new
// truth synchronously. Silent variants (`handleDaemonLost`,
// `handleExtensionClearInteractive`, `handleExtensionClearRecording`,
// `pruneLive`) drop UI bookkeeping without posting back — those paths are
// triggered after the extension or daemon has already torn the streams down,
// and re-posting would race the flush.

import { attachInteractiveInputHandlers } from "./interactiveInput";
import type { InteractiveInputConfig } from "./interactiveInput";
import { planLiveToggle, planRecordingToggle } from "./liveTransitions";
import type { VsCodeApi } from "../shared/vscode";
import { sanitizeId } from "./cardData";

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

    constructor(private readonly cfg: LiveStateConfig) {}

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
     *  setPreviews dropping a previewId) cleanly wipe the prior decoration. */
    applyLiveBadge(): void {
        document.querySelectorAll(".preview-card.live").forEach((c) => {
            c.classList.remove("live");
            c.querySelector(".card-live-stop-btn")?.remove();
        });
        if (this.interactivePreviewIds.size === 0) return;
        this.interactivePreviewIds.forEach((previewId) => {
            const card = document.getElementById(
                "preview-" + sanitizeId(previewId),
            );
            if (!card) return;
            card.classList.add("live");
            this.ensureLiveCardControls(card);
        });
    }

    /** Toolbar `<i class="codicon codicon-debug-stop">` button — stop every
     *  live stream at once. */
    stopAllInteractive(): void {
        if (this.interactivePreviewIds.size === 0) return;
        const ids = Array.from(this.interactivePreviewIds);
        this.interactivePreviewIds.clear();
        ids.forEach((previewId) => {
            this.cfg.vscode.postMessage({
                command: "setInteractive",
                previewId,
                enabled: false,
            });
        });
        this.applyLiveBadge();
        this.cfg.applyInteractiveButtonState();
    }

    /** Per-card stop button (the codicon overlay) — stop only [card]. */
    stopInteractiveForCard(card: HTMLElement): void {
        const previewId = card.dataset.previewId;
        if (!previewId || !this.interactivePreviewIds.has(previewId)) return;
        this.interactivePreviewIds.delete(previewId);
        this.cfg.vscode.postMessage({
            command: "setInteractive",
            previewId,
            enabled: false,
        });
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
            this.cfg.vscode.postMessage({
                command: "setInteractive",
                previewId: prior,
                enabled: false,
            });
        }
        this.interactivePreviewIds = plan.next;
        if (plan.turnOnTarget) {
            const img = card.querySelector(".image-container img");
            if (img instanceof HTMLImageElement) {
                attachInteractiveInputHandlers(
                    card,
                    img,
                    this.cfg.interactiveInputConfig,
                );
            }
        }
        this.cfg.vscode.postMessage({
            command: "setInteractive",
            previewId,
            enabled: plan.turnOnTarget,
        });
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
            const img = card.querySelector(".image-container img");
            if (img instanceof HTMLImageElement) {
                attachInteractiveInputHandlers(
                    card,
                    img,
                    this.cfg.interactiveInputConfig,
                );
            }
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
        this.cfg.vscode.postMessage({
            command: "setInteractive",
            previewId: lone,
            enabled: false,
        });
        this.interactivePreviewIds.clear();
        this.applyLiveBadge();
    }

    /** Viewport callback — auto-stop a live stream once its card has scrolled
     *  fully out of view. */
    onCardLeftViewport(previewId: string): void {
        if (!this.interactivePreviewIds.has(previewId)) return;
        this.interactivePreviewIds.delete(previewId);
        this.cfg.vscode.postMessage({
            command: "setInteractive",
            previewId,
            enabled: false,
        });
        this.applyLiveBadge();
        this.cfg.applyInteractiveButtonState();
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
        const img = container.querySelector("img");
        if (img instanceof HTMLImageElement) {
            attachInteractiveInputHandlers(
                card,
                img,
                this.cfg.interactiveInputConfig,
            );
        }
    }
}
