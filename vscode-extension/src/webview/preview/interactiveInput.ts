// Pointer + wheel state machine for live (interactive) previews.
//
// Both handler families attach to the `.preview-card` element, gated
// per event by an `isLive` predicate, and resolve the visible render
// surface dynamically via `liveRenderSurface(card)`:
//
//  - **Wheel** — capture-phase listener on the card. Vertical deltas
//    over the surface forward as rotary scroll; deltas over chrome are
//    still consumed so enthusiastic scrolling can't push the live
//    preview out of view.
//  - **Pointer** — bubble-phase listeners on the card. Pointerdown is
//    only hijacked when `evt.target === surface.el`, so the stop
//    button / focus toolbar / badges keep native click semantics. The
//    surface resolved at pointerdown is held in `state.surface` for
//    the lifetime of the gesture, so coords stay in a single natural-
//    pixel space even if the painter swaps the surface mid-drag (the
//    `<img>` ↔ `<canvas>` flip is asynchronous).
//
// Listeners are idempotent — flagged via
// `dataset.interactiveWheelBound` and `dataset.interactivePointerBound`
// so re-attaching on subsequent `updateImage` / live-toggle calls
// doesn't stack duplicates. The handlers stay attached after a card
// leaves live mode; they go inert because every event re-checks the
// `isLive` predicate.
//
// Coordinates the daemon expects are image-natural pixel space — the
// same space the renderer paints in. The CSS-pixel offsets the browser
// gives us are scaled by the displayed/natural ratio in `surfacePoint`.
// See docs/daemon/INTERACTIVE.md §§ 3, 6, 7.
//
// Streaming-mode rationale: the painter hides the legacy `<img>`
// (display:none → 0×0 rect, pointer events never fire on it) and paints
// into a `<canvas class="stream-canvas">` overlay. Both handler families
// hang off the card (not the img) so they keep working across that
// swap. `liveRenderSurface` prefers the canvas when present.

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

    if (card.dataset.interactivePointerBound === "1") return;
    card.dataset.interactivePointerBound = "1";

    interface PointerState {
        pointerId: number | null;
        start: ImagePoint | null;
        last: ImagePoint | null;
        dragging: boolean;
        sentDown: boolean;
        /**
         * Surface captured at pointerdown. Held for the lifetime of a
         * drag so coords stay in a single natural-pixel space even if
         * the painter swaps surfaces mid-gesture (rare but the
         * `<img>` ↔ `<canvas>` flip is asynchronous).
         */
        surface: LiveSurface | null;
    }
    const state: PointerState = {
        pointerId: null,
        start: null,
        last: null,
        dragging: false,
        sentDown: false,
        surface: null,
    };

    card.addEventListener("pointerdown", (evt) => {
        const id = card.dataset.previewId;
        if (!id || !config.isLive(id)) return;
        if (evt.button !== 0 && evt.button !== 2) return;
        const surface = liveRenderSurface(card);
        // Only hijack the gesture when it starts on the live surface.
        // Anything else (stop button, focus toolbar, badges) is chrome and
        // must keep its native click semantics — those overlay siblings
        // sit on top of the surface so `evt.target` is the truth.
        if (!surface || evt.target !== surface.el) return;
        const point = surfacePoint(surface, evt);
        if (!point) return;
        state.pointerId = evt.pointerId;
        state.start = point;
        state.last = point;
        state.dragging = false;
        state.sentDown = false;
        state.surface = surface;
        // Capture on the card so subsequent move/up route here even when
        // the cursor leaves the surface (or the surface gets swapped out
        // mid-drag by the streaming painter).
        card.setPointerCapture?.(evt.pointerId);
        evt.preventDefault();
        evt.stopPropagation();
    });

    card.addEventListener("pointermove", (evt) => {
        const id = card.dataset.previewId;
        if (!id || !config.isLive(id)) return;
        if (state.pointerId !== evt.pointerId || !state.start || !state.surface)
            return;
        const next = surfacePoint(state.surface, evt);
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
                    state.surface,
                    "pointerDown",
                    state.start,
                );
                state.sentDown = true;
            }
            postInteractiveInput(
                config.vscode,
                id,
                state.surface,
                "pointerMove",
                next,
            );
            state.last = next;
            evt.preventDefault();
            evt.stopPropagation();
        }
    });

    card.addEventListener("pointerup", (evt) => {
        const id = card.dataset.previewId;
        if (!id || !config.isLive(id)) return;
        if (state.pointerId !== evt.pointerId || !state.start || !state.surface)
            return;
        const point =
            surfacePoint(state.surface, evt) || state.last || state.start;
        if (state.dragging) {
            if (!state.sentDown) {
                postInteractiveInput(
                    config.vscode,
                    id,
                    state.surface,
                    "pointerDown",
                    state.start,
                );
            }
            postInteractiveInput(
                config.vscode,
                id,
                state.surface,
                "pointerUp",
                point,
            );
        } else {
            postInteractiveInput(
                config.vscode,
                id,
                state.surface,
                "click",
                point,
            );
        }
        card.releasePointerCapture?.(evt.pointerId);
        state.pointerId = null;
        state.start = null;
        state.last = null;
        state.dragging = false;
        state.sentDown = false;
        state.surface = null;
        evt.preventDefault();
        evt.stopPropagation();
    });

    card.addEventListener("pointercancel", (evt) => {
        if (state.pointerId !== evt.pointerId) return;
        card.releasePointerCapture?.(evt.pointerId);
        state.pointerId = null;
        state.start = null;
        state.last = null;
        state.dragging = false;
        state.sentDown = false;
        state.surface = null;
    });

    card.addEventListener("contextmenu", (evt) => {
        const id = card.dataset.previewId;
        if (!id || !config.isLive(id)) return;
        const surface = liveRenderSurface(card);
        if (!surface || evt.target !== surface.el) return;
        evt.preventDefault();
        evt.stopPropagation();
    });
}

/** DOM-bound shim around `computeImagePoint` — extracts the surface's
 *  bounding rect and lets the pure helper do the natural-pixel math. */
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
    surface: LiveSurface,
    kind: InteractiveInputKind,
    point: ImagePoint | null,
    scrollDeltaY?: number,
): void {
    if (!point || !surface.naturalWidth || !surface.naturalHeight) return;
    vscode.postMessage({
        command: "recordInteractiveInput",
        previewId,
        kind,
        pixelX: point.pixelX,
        pixelY: point.pixelY,
        imageWidth: surface.naturalWidth,
        imageHeight: surface.naturalHeight,
        scrollDeltaY,
    });
}
