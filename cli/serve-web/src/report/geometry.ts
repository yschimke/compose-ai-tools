// Turning "the part of the page I pointed at" into "these pixels of the captured frame".
//
// The capture and the selection are in two different coordinate systems and neither is negotiable.
// A tab capture arrives in DEVICE pixels — the viewport multiplied by whatever
// `devicePixelRatio` the visitor's display, browser zoom and OS scaling combine to, and clamped by
// the browser to its own maximum capture size — while everything the page can tell you about an
// element (`getBoundingClientRect`, a pointer's `clientX`) is in CSS pixels relative to the
// viewport. Getting the ratio between them wrong does not fail; it crops the wrong part of the
// picture, which is a bug nobody can see in a code review and everybody sees in a filed issue.
//
// So the conversion is derived from the frame that actually arrived rather than assumed from
// `devicePixelRatio`, and it is derived per axis: a browser that clamps a very wide capture scales
// one axis more than the other, and a single scalar would then shear every crop.

export interface Rect {
    x: number;
    y: number;
    width: number;
    height: number;
}

export interface Size {
    width: number;
    height: number;
}

/** CSS px → captured-frame px, per axis. */
export interface Scale {
    x: number;
    y: number;
}

/**
 * The scale a frame implies, given the viewport it is a picture of.
 *
 * Falls back to 1 on either axis whose viewport dimension is zero or absurd, which is not a real
 * browser state but *is* a reachable one in a test and in a background tab that reports nothing.
 * Cropping at 1:1 there yields a wrong-sized crop; dividing by zero yields `Infinity`, and a canvas
 * sized `Infinity` throws.
 */
export function frameScale(frame: Size, viewport: Size): Scale {
    return {
        x: viewport.width > 0 ? frame.width / viewport.width : 1,
        y: viewport.height > 0 ? frame.height / viewport.height : 1,
    };
}

/** The rectangle two pointer positions describe, in either drag direction. */
export function rectFromPoints(
    ax: number,
    ay: number,
    bx: number,
    by: number,
): Rect {
    return {
        x: Math.min(ax, bx),
        y: Math.min(ay, by),
        width: Math.abs(ax - bx),
        height: Math.abs(ay - by),
    };
}

/**
 * Trim a rectangle to the viewport.
 *
 * Load-bearing for the element mode. An element's bounding box is its box in the DOCUMENT's flow
 * expressed relative to the viewport, so a table taller than the window has a rect that runs off
 * the bottom — and the frame holds only what was visible. Cropping to the untrimmed rect reads
 * outside the source canvas, which `drawImage` renders as transparent padding: a picture of the
 * table with a large empty area under it, which reads as "the page is broken here" in a bug report
 * about a page that is not.
 */
export function clampRect(rect: Rect, bounds: Size): Rect {
    const left = Math.max(0, Math.min(rect.x, bounds.width));
    const top = Math.max(0, Math.min(rect.y, bounds.height));
    const right = Math.max(left, Math.min(rect.x + rect.width, bounds.width));
    const bottom = Math.max(top, Math.min(rect.y + rect.height, bounds.height));
    return { x: left, y: top, width: right - left, height: bottom - top };
}

/**
 * Project a CSS-pixel rectangle onto the frame, rounded OUTWARD.
 *
 * Outward rather than nearest so a crop never loses the outermost row of pixels — the border of the
 * element that was pointed at is very often the thing being reported, and a half-pixel boundary
 * rounded inward shaves exactly that.
 */
export function mapRect(rect: Rect, scale: Scale): Rect {
    const x = Math.floor(rect.x * scale.x);
    const y = Math.floor(rect.y * scale.y);
    return {
        x,
        y,
        width: Math.max(1, Math.ceil((rect.x + rect.width) * scale.x) - x),
        height: Math.max(1, Math.ceil((rect.y + rect.height) * scale.y) - y),
    };
}

/**
 * Whether a selection is worth capturing at all.
 *
 * A click with no drag produces a 0×0 rectangle and a twitchy one produces a 3×2. Neither is a
 * selection; both used to become a capture, and a 3×2 PNG in a bug report is worse than none
 * because it looks like evidence.
 */
export const MIN_SELECTION = 8;

export function isUsable(rect: Rect): boolean {
    return rect.width >= MIN_SELECTION && rect.height >= MIN_SELECTION;
}

/**
 * Shrink a capture so it fits within [limit] on its longest side, or leave it alone.
 *
 * Not about looks — about the ~5 MB `sessionStorage` budget a capture has to survive a navigation
 * inside (see `store.ts`). A 3× display makes a full-viewport PNG large enough that two of them
 * evict each other; halving it costs a bug report nothing legible and keeps three.
 */
export function fitWithin(size: Size, limit: number): Size {
    const longest = Math.max(size.width, size.height);
    if (longest <= limit || longest === 0) return size;
    const factor = limit / longest;
    return {
        width: Math.max(1, Math.round(size.width * factor)),
        height: Math.max(1, Math.round(size.height * factor)),
    };
}
