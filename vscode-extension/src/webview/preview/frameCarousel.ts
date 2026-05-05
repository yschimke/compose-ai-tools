// Per-card frame carousel for animated / multi-capture previews.
//
// Lifted verbatim from `behavior.ts`'s `buildFrameControls` /
// `stepFrame` / `showFrame` / `updateFrameIndicator` cluster. The DOM
// shape is unchanged — same `<div class="frame-controls">` with prev /
// indicator / next, same arrow-key handling, same per-capture image
// swap and error-panel attach.
//
// Carousel state lives outside this module: `cardCaptures` is the
// shared `Map<previewId, CapturePresentation[]>` owned by `behavior.ts`
// (populated from `setPreviews`, mutated by `updateImage` /
// `setImageError`, drained by `renderPreviews`'s removal pass), and
// `card.dataset.currentIndex` tracks the visible frame as a string.
// Both are passed in via `FrameCarouselConfig` so this module never
// needs to reach into closures in `behavior.ts`.
//
// Held as a `FrameCarouselController` so the dependencies (vscode,
// the captures Map, the interactive-input config used when swapping
// to a fresh `<img>`) bind once at panel boot rather than threading
// through every call site.

import { mimeFor } from "./cardData";
import { buildErrorPanel } from "./errorPanel";
import {
    attachInteractiveInputHandlers,
    type InteractiveInputConfig,
} from "./interactiveInput";
import type { PreviewRenderError } from "../shared/types";
import type { VsCodeApi } from "../shared/vscode";

/** Per-capture state shared with `behavior.ts`. */
export interface CapturePresentation {
    /** Pre-formatted human-readable label of the capture's non-null
     *  dimensions — `'500ms'`, `'scrolled end'`, etc. */
    label: string;
    /** Module-relative output path; drives the image MIME type. */
    renderOutput: string;
    /** Latest base64 image bytes, or `null` until the renderer has
     *  produced a result for this capture. */
    imageData: string | null;
    /** One-line error message when the capture failed without a
     *  structured sidecar. */
    errorMessage: string | null;
    /** Structured render-error sidecar when available. */
    renderError: PreviewRenderError | null;
}

export interface FrameCarouselConfig {
    vscode: VsCodeApi<unknown>;
    cardCaptures: Map<string, CapturePresentation[]>;
    interactiveInputConfig: InteractiveInputConfig;
}

export class FrameCarouselController {
    constructor(private readonly config: FrameCarouselConfig) {}

    /**
     * Builds the prev / indicator / next strip placed under the image
     * on multi-capture cards. Caller (`createCard`) appends the result
     * to the card. Indicator text is seeded one frame later via
     * `requestAnimationFrame` so the label matches the carousel's
     * post-mount state.
     */
    buildControls(card: HTMLElement): HTMLElement {
        const bar = document.createElement("div");
        bar.className = "frame-controls";

        const prev = document.createElement("button");
        prev.className = "icon-button frame-prev";
        prev.setAttribute("aria-label", "Previous capture");
        prev.title = "Previous capture";
        prev.innerHTML =
            '<i class="codicon codicon-chevron-left" aria-hidden="true"></i>';
        prev.addEventListener("click", () => this.step(card, -1));

        const indicator = document.createElement("span");
        indicator.className = "frame-indicator";
        indicator.setAttribute("aria-live", "polite");

        const next = document.createElement("button");
        next.className = "icon-button frame-next";
        next.setAttribute("aria-label", "Next capture");
        next.title = "Next capture";
        next.innerHTML =
            '<i class="codicon codicon-chevron-right" aria-hidden="true"></i>';
        next.addEventListener("click", () => this.step(card, 1));

        bar.appendChild(prev);
        bar.appendChild(indicator);
        bar.appendChild(next);

        // Arrow keys when the carousel has focus. Stop propagation so
        // the document-level focus-mode nav doesn't also advance the card.
        bar.tabIndex = 0;
        bar.addEventListener("keydown", (e) => {
            if (e.key === "ArrowLeft") {
                this.step(card, -1);
                e.preventDefault();
                e.stopPropagation();
            } else if (e.key === "ArrowRight") {
                this.step(card, 1);
                e.preventDefault();
                e.stopPropagation();
            }
        });

        // Seed indicator text so it's not blank before any image arrives.
        requestAnimationFrame(() => this.updateIndicator(card));
        return bar;
    }

    /**
     * Update the `idx / total · label` indicator and disable the
     * boundary buttons. Called from `behavior.ts` whenever capture
     * state churn might shift what's visible (`updateCardMetadata`,
     * `updateImage`, `setImageError`) — outside those points this
     * module updates the indicator itself after `step` / `show`.
     */
    updateIndicator(card: HTMLElement): void {
        const indicator = card.querySelector<HTMLElement>(".frame-indicator");
        const prevBtn = card.querySelector<HTMLButtonElement>(".frame-prev");
        const nextBtn = card.querySelector<HTMLButtonElement>(".frame-next");
        if (!indicator) return;
        const previewId = card.dataset.previewId ?? "";
        const caps = this.config.cardCaptures.get(previewId);
        if (!caps) return;
        const idx = parseInt(card.dataset.currentIndex || "0", 10);
        const capture = caps[idx];
        const label = capture && capture.label ? capture.label : "—";
        indicator.textContent = idx + 1 + " / " + caps.length + " · " + label;
        if (prevBtn) prevBtn.disabled = idx === 0;
        if (nextBtn) nextBtn.disabled = idx === caps.length - 1;
    }

    private step(card: HTMLElement, delta: number): void {
        const previewId = card.dataset.previewId ?? "";
        const caps = this.config.cardCaptures.get(previewId);
        if (!caps) return;
        const cur = parseInt(card.dataset.currentIndex || "0", 10);
        const next = Math.max(0, Math.min(caps.length - 1, cur + delta));
        if (next === cur) return;
        card.dataset.currentIndex = String(next);
        this.show(card, next);
    }

    private show(card: HTMLElement, index: number): void {
        const previewId = card.dataset.previewId ?? "";
        const caps = this.config.cardCaptures.get(previewId);
        if (!caps) return;
        const capture = caps[index];
        if (!capture) return;
        const container = card.querySelector<HTMLElement>(".image-container");
        if (!container) return;

        if (capture.imageData) {
            const skeleton = container.querySelector(".skeleton");
            const errorMsg = container.querySelector(".error-message");
            if (skeleton) skeleton.remove();
            if (errorMsg) errorMsg.remove();
            let img = container.querySelector<HTMLImageElement>("img");
            if (!img) {
                img = document.createElement("img");
                img.alt = (card.dataset.function ?? "") + " preview";
                container.appendChild(img);
            }
            img.src =
                "data:" +
                mimeFor(capture.renderOutput) +
                ";base64," +
                capture.imageData;
            img.className = "fade-in";
            attachInteractiveInputHandlers(
                card,
                img,
                this.config.interactiveInputConfig,
            );
            if (capture.errorMessage || capture.renderError) {
                container.appendChild(
                    buildErrorPanel(
                        this.config.vscode,
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
                    this.config.vscode,
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
        this.updateIndicator(card);
    }
}
