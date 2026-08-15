// The arithmetic behind `<cp-page-zoom>`, as pure functions over plain boxes.
//
// Separated from the element on purpose: every interesting decision this feature
// makes is geometric — how far to zoom to frame a section, how far a pan may go
// before it shows white ground, which level of the export's tree is "one level
// in" — and none of it needs a DOM to be right or wrong. Held in the element it
// would only be reachable through a browser; held here it is unit-tested against
// the two shapes that actually break it (a 3.4:1 specimen sheet and a full-height
// section on it), which is how the `nothing happens` bug below stays fixed.

/** How far the sheet is zoomed, and where it has been dragged to (CSS pixels). */
export interface View {
    scale: number;
    x: number;
    y: number;
}

/** The rectangle subset this module needs — a `DOMRect` satisfies it. */
export interface Box {
    left: number;
    top: number;
    width: number;
    height: number;
}

/** 1:1 is the floor: the sheet is served at exactly the width of its stage. */
export const MIN_SCALE = 1;

/**
 * Far enough to read 8 px design type on a sheet squeezed into a sixth of its
 * drawn width, and short of the factor where a PNG render standing in a slot is
 * pure blur.
 */
export const MAX_SCALE = 24;

/** One notch of the corner buttons, and of a double-click with nowhere to go. */
export const STEP = 1.45;

/** Air around a framed section, so it reads as an object and not as a crop. */
export const FRAME_PAD = 0.94;

/** The identity view: the whole sheet, unpanned. */
export function rest(): View {
    return { scale: MIN_SCALE, x: 0, y: 0 };
}

export function zoomed(view: View): boolean {
    return view.scale > 1.001;
}

/**
 * Hold the sheet inside its stage. Without this the canvas could be dragged clean
 * off, leaving a reader looking at white ground with no clue which way the
 * drawing went — and at 1:1 there is nothing to pan, so the view is pinned.
 */
export function clamp(view: View, box: Box): View {
    if (!(view.scale > MIN_SCALE)) return rest();
    const scale = Math.min(view.scale, MAX_SCALE);
    return {
        scale,
        x: Math.min(0, Math.max(box.width - box.width * scale, view.x)),
        y: Math.min(0, Math.max(box.height - box.height * scale, view.y)),
    };
}

/**
 * Carry a pan across a change in the stage's size, keeping the reader looking at the same part of
 * the sheet.
 *
 * The canvas fills the stage, so a pan offset means "this many of THIS stage's pixels" — clamping it
 * against the new bounds and leaving it otherwise alone silently moves the centre of the view. Halve
 * the width of a centred 2x view and the middle of the screen slides from 50% to 75% across the
 * sheet, which is how opening a side panel loses the reader's place. Scaling the offsets by the same
 * ratio the box changed by keeps the fraction — and therefore the content point — where it was.
 */
export function rescale(view: View, from: Box, to: Box): View {
    if (!(from.width > 0 && from.height > 0)) return view;
    if (!(to.width > 0 && to.height > 0)) return view;
    return clamp(
        {
            scale: view.scale,
            x: view.x * (to.width / from.width),
            y: view.y * (to.height / from.height),
        },
        to,
    );
}

/**
 * Zoom about a point, keeping whatever is under it under it — the wheel gesture,
 * and what makes the corner buttons zoom the middle of the view rather than the
 * top-left corner.
 */
export function zoomAbout(
    view: View,
    box: Box,
    clientX: number,
    clientY: number,
    factor: number,
): View {
    const px = clientX - box.left;
    const py = clientY - box.top;
    const scale = Math.min(MAX_SCALE, Math.max(MIN_SCALE, view.scale * factor));
    const cx = (px - view.x) / view.scale;
    const cy = (py - view.y) / view.scale;
    return clamp({ scale, x: px - cx * scale, y: py - cy * scale }, box);
}

/**
 * How much bigger a rect gets when it is framed in the stage.
 *
 * FITTING BOTH AXES IS NOT ENOUGH, AND THE STYLES PAGE IS WHY. The stage wears
 * the SHEET's aspect ratio (the design decides the shape of the box), and a
 * specimen sheet can be 3.4:1 — m3-catalog's Styles page is 6263x1851. Its
 * sections are full-height cards, so "fit this section" is limited by the height
 * it already fills and resolves to about 1.0: the reader double-clicks Typography
 * and nothing happens, which is the one outcome this gesture must not have.
 *
 * So a section far taller than the viewport is fitted to its WIDTH and the caller
 * anchors it near the top, to be panned down like a column of text. The crop is
 * capped at 3x the height that would fit, so framing can never leave a tenth of
 * the thing on screen, and a rect whose shape is within 1.5x of the stage's is
 * still a plain fit that crops nothing at all.
 */
export function fitFactor(rect: Box, box: Box): number {
    if (!(rect.width > 0 && rect.height > 0)) return 1;
    const sw = box.width / rect.width;
    const sh = box.height / rect.height;
    return (
        (sw > sh * 1.5 ? Math.min(sw, sh * 3) : Math.min(sw, sh)) * FRAME_PAD
    );
}

/** Frame a measured rect in the stage: centred when it fits, top-anchored when not. */
export function frameRect(view: View, box: Box, rect: Box): View {
    if (!(rect.width > 0 && rect.height > 0)) return view;
    if (!(box.width > 0 && box.height > 0)) return view;
    const scale = Math.min(
        MAX_SCALE,
        Math.max(MIN_SCALE, view.scale * fitFactor(rect, box)),
    );
    const grew = scale / view.scale;
    const cx = (rect.left + rect.width / 2 - box.left - view.x) / view.scale;
    const top = (rect.top - box.top - view.y) / view.scale;
    const height = rect.height * grew;
    return clamp(
        {
            scale,
            x: box.width / 2 - cx * scale,
            // The top of a column is where reading starts, so a section three
            // viewports tall opens at its top rather than in its middle.
            y:
                height <= box.height
                    ? (box.height - height) / 2 - top * scale
                    : box.height * 0.03 - top * scale,
        },
        box,
    );
}

/**
 * The smallest pan that brings a rect inside the stage, or null if it is already
 * there. Used when keyboard focus lands on a node the current zoom has pushed
 * off-screen — without it, tabbing a zoomed sheet lights an outline somewhere the
 * reader cannot see.
 */
export function revealDelta(
    rect: Box,
    box: Box,
    pad = 12,
): { x: number; y: number } | null {
    let x = 0;
    let y = 0;
    const right = box.left + box.width;
    const bottom = box.top + box.height;
    if (rect.left < box.left + pad) x = box.left + pad - rect.left;
    else if (rect.left + rect.width > right - pad) {
        x = right - pad - (rect.left + rect.width);
    }
    if (rect.top < box.top + pad) y = box.top + pad - rect.top;
    else if (rect.top + rect.height > bottom - pad) {
        y = bottom - pad - (rect.top + rect.height);
    }
    return x || y ? { x, y } : null;
}

/** One addressable box of the export, as the drill sees it. */
export interface Level<T> {
    node: T;
    box: Box;
}

/**
 * Pick the next level in, given the chain of boxes under the pointer (outermost
 * first) and how deep the reader already is. Answers null for "nothing deeper
 * here", which the caller reads as a step back out.
 *
 * `start` is the index in `chain` of the level currently framed, or -1 for the
 * whole sheet — depth is REMEMBERED rather than inferred from the view, because a
 * framed section fills the stage's height but a quarter of its width (or the
 * reverse), so "does this box already fill the view" has no answer that holds for
 * both shapes. Getting that wrong means a double-click that re-frames the level
 * you are already on, i.e. a gesture that appears to do nothing.
 */
export function pickLevel<T>(
    chain: Array<Level<T>>,
    start: number,
    box: Box,
    outer: Box,
    scale = 1,
): Level<T> | null {
    const current = start >= 0 ? chain[start].box : outer;
    for (let i = start + 1; i < chain.length; i++) {
        const level = chain[i];
        // A wrapper the same size as the level we are on — a clip group, a frame
        // around a frame, which real exports are full of. Entering it would
        // re-frame the same picture, so it is not a level.
        if (
            level.box.width >= current.width * 0.92 &&
            level.box.height >= current.height * 0.92
        ) {
            continue;
        }
        // A hairline or a single glyph stroke: filling the stage with one edge
        // loses the reader entirely, and the level above it is the thing worth
        // looking at.
        //
        // Measured in the SHEET's own pixels, not the screen's. These boxes come
        // from `getBoundingClientRect`, so they already carry the zoom — and a
        // fixed screen cutoff therefore stops filtering anything once the reader
        // is in far enough: at 12x a 1 px stroke measures 12 and sails through the
        // guard that exists to reject it.
        if (level.box.width / scale < 6 || level.box.height / scale < 6)
            continue;
        // A level that cannot be MAGNIFIED is not a level either, however
        // differently shaped its box is: a section as wide as the sheet frames at
        // 1.0x, so keep descending until something can actually be enlarged.
        if (fitFactor(level.box, box) < 1.15) continue;
        return level;
    }
    return null;
}
