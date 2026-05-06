// Pointer + wheel state machine for live (interactive) previews.
//
// Lifted verbatim from `behavior.ts` (`ensureInteractiveInputHandlers`,
// `imagePoint`, `eventInsideElement`, `postInteractiveInput`) so the
// pointer logic stops needing `@ts-nocheck`. Behaviour is unchanged:
// pointer down / move / up / cancel / context-menu on the live image,
// plus a capture-phase wheel listener on the whole card that maps
// vertical deltas to rotary scroll. Listeners are idempotent — flagged
// via `dataset.interactiveBound` / `dataset.interactiveWheelBound` so
// re-attaching on subsequent `updateImage` calls doesn't stack
// duplicates.
//
// The handlers stay attached after a card leaves live mode. They go
// inert because every event re-checks the `isLive` predicate — so a
// non-live card swallows nothing. We deliberately don't tear handlers
// down because live can be re-entered without producing a fresh `<img>`
// (e.g. the same image element survives a single-target → re-toggle
// cycle), and re-binding would race with in-flight pointer captures.
//
// Coordinates the daemon expects are image-natural pixel space — the
// same space the renderer paints in. The CSS-pixel offsets the browser
// gives us are scaled by the displayed/natural ratio in `imagePoint`.
// See docs/daemon/INTERACTIVE.md §§ 3, 6, 7.
//
// Streaming-mode wheel quirk: when the daemon is pushing live frames over
// `composestream/1` the painter hides the `<img>` (display:none → 0×0
// rect) and paints into a `<canvas class="stream-canvas">` overlay. The
// wheel handler resolves the live render surface dynamically per event
// via `liveRenderSurface(card)` — preferring the canvas — so rotary
// scroll keeps reaching the daemon after the painter swap.

import type { VsCodeApi } from "../shared/vscode";
import {
    computeImagePoint,
    isEventInsideRect,
    type ImagePoint,
} from "./pointerGeometry";

export type { ImagePoint };

/** Visible live render surface: an `<img>` in the legacy capture-and-show
 *  path, or the streaming `<canvas class="stream-canvas">` once the
 *  painter has taken over. The interactive wheel/pointer plumbing only
 *  cares about its bounding rect and natural-pixel dimensions. */
export interface LiveSurface {
    el: Element;
    naturalWidth: number;
    naturalHeight: number;
}

/** Resolve the live render surface inside [card]. Streaming canvas wins
 *  when present and sized — the painter hides the legacy `<img>` once
 *  it attaches, so `<img>.getBoundingClientRect()` is 0×0 and pointer
 *  geometry against it always returns null. Returns null when neither
 *  surface has usable natural dimensions yet. */
export function liveRenderSurface(card: Element): LiveSurface | null {
    const canvas = card.querySelector<HTMLCanvasElement>(
        "canvas.stream-canvas",
    );
    if (canvas && canvas.width > 0 && canvas.height > 0) {
        return {
            el: canvas,
            naturalWidth: canvas.width,
            naturalHeight: canvas.height,
        };
    }
    const img = card.querySelector<HTMLImageElement>(
        "img.preview-image, img.preview-gif, img",
    );
    if (img && img.naturalWidth > 0 && img.naturalHeight > 0) {
        return {
            el: img,
            naturalWidth: img.naturalWidth,
            naturalHeight: img.naturalHeight,
        };
    }
    return null;
}

export interface InteractiveInputConfig {
    /**
     * Predicate keyed on a card's `data-preview-id`. Returns true when
     * the card should currently consume pointer/wheel input — i.e. it
     * is in the `interactivePreviewIds` set OR the `recordingPreviewIds`
     * set. Both states forward input to the daemon.
     *
     * The predicate is called on every event, so non-live cards
     * naturally pass events through.
     */
    isLive(previewId: string): boolean;
    vscode: VsCodeApi<unknown>;
}

export function attachInteractiveInputHandlers(
    card: HTMLElement,
    img: HTMLImageElement,
    config: InteractiveInputConfig,
): void {
    const previewId = card.dataset.previewId;
    if (!previewId) return;
    if (!config.isLive(previewId)) return;

    if (card.dataset.interactiveWheelBound !== "1") {
        card.dataset.interactiveWheelBound = "1";
        card.addEventListener(
            "wheel",
            (evt) => {
                const id = card.dataset.previewId;
                if (!id || !config.isLive(id)) return;
                // Live previews own wheel input while the cursor is inside the card. If the
                // wheel lands on preview pixels, forward it as rotary scroll; if it lands on
                // the card chrome, still consume it so enthusiastic scrolling cannot bubble to
                // the list and push the live preview out of view.
                //
                // Resolve the visible surface dynamically so the streaming `<canvas>` takes
                // over from the (now hidden) `<img>` once `streamingPainter` attaches — see
                // module header.
                const surface = liveRenderSurface(card);
                const point =
                    surface && eventInsideElement(surface.el, evt)
                        ? surfacePoint(surface, evt)
                        : null;
                if (surface && point) {
                    postInteractiveInput(
                        config.vscode,
                        id,
                        surface,
                        "rotaryScroll",
                        point,
                        evt.deltaY,
                    );
                }
                evt.preventDefault();
                evt.stopImmediatePropagation();
            },
            { passive: false, capture: true },
        );
    }

    if (img.dataset.interactiveBound === "1") return;
    img.dataset.interactiveBound = "1";

    interface PointerState {
        pointerId: number | null;
        start: ImagePoint | null;
        last: ImagePoint | null;
        dragging: boolean;
        sentDown: boolean;
    }
    const state: PointerState = {
        pointerId: null,
        start: null,
        last: null,
        dragging: false,
        sentDown: false,
    };

    img.addEventListener("pointerdown", (evt) => {
        const id = card.dataset.previewId;
        if (!id || !config.isLive(id)) return;
        if (evt.button !== 0 && evt.button !== 2) return;
        state.pointerId = evt.pointerId;
        state.start = imagePoint(img, evt);
        state.last = state.start;
        state.dragging = false;
        state.sentDown = false;
        img.setPointerCapture?.(evt.pointerId);
        evt.preventDefault();
        evt.stopPropagation();
    });

    img.addEventListener("pointermove", (evt) => {
        const id = card.dataset.previewId;
        if (!id || !config.isLive(id)) return;
        if (state.pointerId !== evt.pointerId || !state.start) return;
        const next = imagePoint(img, evt);
        if (!next) return;
        const dx = next.clientX - state.start.clientX;
        const dy = next.clientY - state.start.clientY;
        if (!state.dragging && Math.hypot(dx, dy) >= 4) {
            state.dragging = true;
        }
        if (state.dragging) {
            if (!state.sentDown) {
                postInteractiveInput(
                    config.vscode,
                    id,
                    img,
                    "pointerDown",
                    state.start,
                );
                state.sentDown = true;
            }
            postInteractiveInput(config.vscode, id, img, "pointerMove", next);
            state.last = next;
            evt.preventDefault();
            evt.stopPropagation();
        }
    });

    img.addEventListener("pointerup", (evt) => {
        const id = card.dataset.previewId;
        if (!id || !config.isLive(id)) return;
        if (state.pointerId !== evt.pointerId || !state.start) return;
        const point = imagePoint(img, evt) || state.last || state.start;
        if (state.dragging) {
            if (!state.sentDown) {
                postInteractiveInput(
                    config.vscode,
                    id,
                    img,
                    "pointerDown",
                    state.start,
                );
            }
            postInteractiveInput(config.vscode, id, img, "pointerUp", point);
        } else {
            postInteractiveInput(config.vscode, id, img, "click", point);
        }
        img.releasePointerCapture?.(evt.pointerId);
        state.pointerId = null;
        state.start = null;
        state.last = null;
        state.dragging = false;
        state.sentDown = false;
        evt.preventDefault();
        evt.stopPropagation();
    });

    img.addEventListener("pointercancel", (evt) => {
        if (state.pointerId !== evt.pointerId) return;
        img.releasePointerCapture?.(evt.pointerId);
        state.pointerId = null;
        state.start = null;
        state.last = null;
        state.dragging = false;
        state.sentDown = false;
    });

    img.addEventListener("contextmenu", (evt) => {
        const id = card.dataset.previewId;
        if (!id || !config.isLive(id)) return;
        evt.preventDefault();
        evt.stopPropagation();
    });
}

/** DOM-bound shim around `computeImagePoint` — extracts the live `<img>`'s
 *  natural and rect dimensions and lets the pure helper do the math. */
function imagePoint(
    img: HTMLImageElement,
    evt: { clientX: number; clientY: number },
): ImagePoint | null {
    return surfacePoint(
        {
            el: img,
            naturalWidth: img.naturalWidth || 0,
            naturalHeight: img.naturalHeight || 0,
        },
        evt,
    );
}

/** Same as `imagePoint`, but for an arbitrary `LiveSurface` — used by the
 *  wheel handler so the streaming `<canvas>` is treated identically to a
 *  legacy `<img>`. */
function surfacePoint(
    surface: LiveSurface,
    evt: { clientX: number; clientY: number },
): ImagePoint | null {
    const rect = surface.el.getBoundingClientRect();
    return computeImagePoint(
        surface.naturalWidth,
        surface.naturalHeight,
        rect.width,
        rect.height,
        rect.left,
        rect.top,
        evt.clientX,
        evt.clientY,
    );
}

/** DOM-bound shim around `isEventInsideRect`. */
function eventInsideElement(
    el: Element,
    evt: { clientX: number; clientY: number },
): boolean {
    return isEventInsideRect(
        el.getBoundingClientRect(),
        evt.clientX,
        evt.clientY,
    );
}

type InteractiveInputKind =
    | "click"
    | "pointerDown"
    | "pointerMove"
    | "pointerUp"
    | "rotaryScroll";

function postInteractiveInput(
    vscode: VsCodeApi<unknown>,
    previewId: string,
    target: LiveSurface | HTMLImageElement,
    kind: InteractiveInputKind,
    point: ImagePoint | null,
    scrollDeltaY?: number,
): void {
    const natW =
        target instanceof HTMLImageElement
            ? target.naturalWidth || 0
            : target.naturalWidth;
    const natH =
        target instanceof HTMLImageElement
            ? target.naturalHeight || 0
            : target.naturalHeight;
    if (!point || !natW || !natH) return;
    vscode.postMessage({
        command: "recordInteractiveInput",
        previewId,
        kind,
        pixelX: point.pixelX,
        pixelY: point.pixelY,
        imageWidth: natW,
        imageHeight: natH,
        scrollDeltaY,
    });
}
