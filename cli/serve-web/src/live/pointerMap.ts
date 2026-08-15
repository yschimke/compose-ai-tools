// Where on the composition a pointer actually landed.
//
// The daemon's wire units are the frame's own natural pixels, so every press, drag and wheel has to
// be mapped out of screen coordinates before it is sent. This is the single most consequential
// calculation in the grid's live lane and the one whose failures are hardest to see: a wrong mapping
// does not error, it presses a different widget — and on a screenshot the card looks exactly the
// same either way.
//
// The trap is `object-fit: contain`. The canvas element fills the thumbnail's slot, but the FRAME
// inside it is letterboxed: its painted rect is smaller than the element's bounding rect and centred
// in it. Scaling against the bounding rect offsets and compresses every coordinate by the size of
// those margins, so a press near the top of a tall card reaches a widget somewhere else entirely.

export interface Rect {
    left: number;
    top: number;
    width: number;
    height: number;
}

export interface Point {
    x: number;
    y: number;
}

/**
 * The frame's painted rect inside an `object-fit: contain` element.
 *
 * One scale for both axes — that is what `contain` means — with the leftover split evenly as
 * margins.
 */
export function containedRect(rect: Rect, frame: Point): Rect | null {
    if (!rect.width || !rect.height || !frame.x || !frame.y) return null;
    const scale = Math.min(rect.width / frame.x, rect.height / frame.y);
    const width = frame.x * scale;
    const height = frame.y * scale;
    return {
        left: rect.left + (rect.width - width) / 2,
        top: rect.top + (rect.height - height) / 2,
        width,
        height,
    };
}

/**
 * The frame pixel under a client coordinate, or null if the pointer is in the letterbox margin.
 *
 * Null rather than a clamp: a press in the margin is a press on nothing. Clamping it to the nearest
 * edge would invent a press on whatever widget happens to sit at the frame's border — reliably, and
 * only for people whose window shape differs from the author's.
 */
export function framePixel(
    rect: Rect,
    frame: Point,
    client: Point,
): Point | null {
    const painted = containedRect(rect, frame);
    if (!painted) return null;
    const scale = painted.width / frame.x;
    const x = Math.round((client.x - painted.left) / scale);
    const y = Math.round((client.y - painted.top) / scale);
    // Inclusive of the far edge: `Math.round` puts the last half-pixel of a frame at exactly
    // `width`, and rejecting it would make the rightmost column unpressable.
    if (x < 0 || y < 0 || x > frame.x || y > frame.y) return null;
    return { x, y };
}

/**
 * Whether a pointer has drifted far enough during a hold that it reads as a scroll or a drag.
 *
 * Per-axis, so the tolerated region is a SQUARE rather than a circle. That is deliberate and worth
 * keeping: the gesture it has to survive is a vertical flick-scroll on touch, and a square is the
 * more forgiving shape along the axes where the competing gestures actually live.
 */
export function drifted(from: Point, to: Point, slopPx: number): boolean {
    return Math.abs(to.x - from.x) > slopPx || Math.abs(to.y - from.y) > slopPx;
}
