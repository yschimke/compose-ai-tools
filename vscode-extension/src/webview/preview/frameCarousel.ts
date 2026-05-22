// Per-card frame carousel for animated / multi-capture previews.
//
// Lifted verbatim from `behavior.ts`'s `buildFrameControls` /
// `stepFrame` / `showFrame` / `updateFrameIndicator` cluster. The DOM
// shape is unchanged — same `<div class="frame-controls">` with prev /
// indicator / next, same arrow-key handling, same per-capture image
// swap and error-panel attach.
//
// Carousel state lives outside this module: `cardCaptures` is the
// shared `Map<previewId, CapturePresentation[]>` owned by
// `previewStore` (populated from `setPreviews`, mutated by
// `updateImage` / `setImageError`, drained by `renderPreviews`'s
// removal pass), and `card.dataset.currentIndex` tracks the visible
// frame as a string. The carousel reads the captures Map directly
// off `previewStore.getState()` so this module never needs to reach
// into closures in `behavior.ts`.
//
// Held as a `FrameCarouselController` so the dependencies (vscode,
// the interactive-input config used when swapping to a fresh `<img>`)
// bind once at panel boot rather than threading through every call site.

import { mimeFor } from "./cardData";
import { buildErrorPanel } from "./errorPanel";
import { buildFrameControls, updateFrameIndicator } from "./frameCarouselDom";
import {
    attachInteractiveInputHandlers,
    type InteractiveInputConfig,
} from "./interactiveInput";
import { previewStore } from "./previewStore";
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
        return buildFrameControls(
            card,
            (c, d) => this.step(c, d),
            (c) => requestAnimationFrame(() => this.updateIndicator(c)),
        );
    }

    /**
     * Update the `idx / total · label` indicator and disable the
     * boundary buttons. Called from `behavior.ts` whenever capture
     * state churn might shift what's visible (`updateCardMetadata`,
     * `updateImage`, `setImageError`) — outside those points this
     * module updates the indicator itself after `step` / `show`.
     */
    updateIndicator(card: HTMLElement): void {
        const previewId = card.dataset.previewId ?? "";
        const caps = previewStore.getState().cardCaptures.get(previewId);
        updateFrameIndicator(card, caps);
    }

    private step(card: HTMLElement, delta: number): void {
        const previewId = card.dataset.previewId ?? "";
        const caps = previewStore.getState().cardCaptures.get(previewId);
        if (!caps) return;
        const cur = parseInt(card.dataset.currentIndex || "0", 10);
        const next = Math.max(0, Math.min(caps.length - 1, cur + delta));
        if (next === cur) return;
        card.dataset.currentIndex = String(next);
        this.show(card, next);
    }

    private show(card: HTMLElement, index: number): void {
        const previewId = card.dataset.previewId ?? "";
        const caps = previewStore.getState().cardCaptures.get(previewId);
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
