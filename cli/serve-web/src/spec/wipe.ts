// The slider view's arithmetic: where the seam sits, and what a pointer at some x means.
//
// Small, and entirely made of clamping — which is exactly the kind of code that is wrong at the
// ends and right everywhere a screenshot would look. The seam is drawn two pixels wide, so it has
// to stay inside the frame at both extremes rather than half-hanging off one edge.

/** The split as a fraction of the frame's width, from the range input's value. */
export function splitFraction(value: string | null | undefined): number {
    const parsed = parseInt(value ?? "", 10);
    if (Number.isNaN(parsed)) return 0.5;
    return Math.max(0, Math.min(100, parsed)) / 100;
}

/** The seam's x, in frame pixels. */
export function splitAt(width: number, fraction: number): number {
    return Math.round(width * fraction);
}

/** The seam is two pixels wide, and never hangs off either edge. */
export function seamX(width: number, split: number): number {
    return Math.max(0, Math.min(width - 2, split - 1));
}

/** Where a pointer at `clientX` lands, as a 0–100 range value. */
export function rangeValueAt(
    clientX: number,
    box: { left: number; width: number },
): string | null {
    // A frame with no width has no meaningful fraction, and dividing by it would answer Infinity.
    if (!box.width) return null;
    const fraction = (clientX - box.left) / box.width;
    return String(Math.round(Math.max(0, Math.min(1, fraction)) * 100));
}
