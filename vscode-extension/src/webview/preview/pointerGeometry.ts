// Pure geometry helpers used by the live (interactive) pointer / wheel
// state machine in `./interactiveInput.ts`. Lifted out so the
// CSS-pixel-to-natural-pixel mapping (the heart of the daemon's input
// coord-system requirement) is testable without DOM, and so the
// "is this event inside this rect" predicate can be re-used in
// other places without going through `Element.getBoundingClientRect`.
//
// `ImagePoint` is the result of mapping a pointer event to the image's
// natural pixel space — the same space the renderer paints in. The
// webview ships these coordinates to the daemon so live previews can
// dispatch pointer input back into a held composition without the host
// needing to know about CSS scaling. See `docs/daemon/INTERACTIVE.md`
// §§ 3, 6, 7.

export interface ImagePoint {
    /** Pointer client-X relative to the image's `getBoundingClientRect().left`. */
    clientX: number;
    /** Pointer client-Y relative to the image's `getBoundingClientRect().top`. */
    clientY: number;
    /** Image-natural-pixel X, clamped to `[0, naturalWidth - 1]`. */
    pixelX: number;
    /** Image-natural-pixel Y, clamped to `[0, naturalHeight - 1]`. */
    pixelY: number;
}

/**
 * Map a pointer event into the image's natural-pixel space. Returns
 * `null` when the image has no natural dimensions (`<img>` not yet
 * loaded) or has zero displayed size (hidden, off-screen, etc.) — in
 * either case there's no useful point to ship to the daemon.
 *
 * Inputs:
 *  - `naturalWidth` / `naturalHeight`: the image's intrinsic pixel
 *    dimensions (`HTMLImageElement.naturalWidth`).
 *  - `rectWidth` / `rectHeight` / `rectLeft` / `rectTop`: the image's
 *    `getBoundingClientRect()` displayed rectangle.
 *  - `eventClientX` / `eventClientY`: the pointer event's client
 *    coordinates (page-relative; the function subtracts `rect.left/top`
 *    to convert to image-relative).
 *
 * The returned `pixelX` / `pixelY` are rounded and clamped so the
 * daemon never receives an out-of-bounds index even if the user clicks
 * exactly on the right / bottom border.
 */
export function computeImagePoint(
    naturalWidth: number,
    naturalHeight: number,
    rectWidth: number,
    rectHeight: number,
    rectLeft: number,
    rectTop: number,
    eventClientX: number,
    eventClientY: number,
): ImagePoint | null {
    if (!naturalWidth || !naturalHeight || rectWidth === 0 || rectHeight === 0)
        return null;
    const clientX = eventClientX - rectLeft;
    const clientY = eventClientY - rectTop;
    const pixelX = Math.round(clientX * (naturalWidth / rectWidth));
    const pixelY = Math.round(clientY * (naturalHeight / rectHeight));
    return {
        clientX,
        clientY,
        pixelX: Math.max(0, Math.min(naturalWidth - 1, pixelX)),
        pixelY: Math.max(0, Math.min(naturalHeight - 1, pixelY)),
    };
}

/** Slim subset of `DOMRect` the geometry helpers actually need. Lets
 *  callers pass a real rect, a stub, or a `{ left, top, right, bottom }`
 *  literal. */
export interface RectBounds {
    left: number;
    top: number;
    right: number;
    bottom: number;
}

/**
 * Whether [event] (with client-coords) lies inside [rect]. Inclusive on
 * all four edges so a click exactly on the bottom-right pixel still
 * counts as inside.
 */
export function isEventInsideRect(
    rect: RectBounds,
    eventClientX: number,
    eventClientY: number,
): boolean {
    return (
        eventClientX >= rect.left &&
        eventClientX <= rect.right &&
        eventClientY >= rect.top &&
        eventClientY <= rect.bottom
    );
}
