import { Capture, PreviewDataProduct, PreviewInfo } from "./types";

/**
 * Human-readable label for one capture of a preview, used in the carousel
 * frame-indicator. Picks a short summary of whichever non-null dimensions
 * the capture carries:
 *
 *   - static (no dimensions) ⇒ `''`
 *   - `advanceTimeMillis = 500` ⇒ `'500ms'`
 *   - `scroll.mode = 'END'` ⇒ `'scrolled end'`
 *   - both ⇒ `'500ms · scrolled end'`
 *
 * Kept here (and imported from the webview's inlined script via the build
 * output) so the carousel markup stays dumb — if more dimensions land
 * (motion scale, keyboard focus state, etc.) the addition is confined to
 * this helper.
 */
export function captureLabel(capture: Capture): string {
    const parts: string[] = [];
    if (capture.advanceTimeMillis != null) {
        parts.push(`${capture.advanceTimeMillis}ms`);
    }
    if (capture.scroll) {
        parts.push(scrollLabel(capture.scroll));
    }
    return parts.join(" \u00B7 ");
}

function scrollLabel(scroll: NonNullable<Capture["scroll"]>): string {
    // Prefer the outcome when the renderer has reported it; fall back to
    // the declared intent. `atEnd` wins over `reachedPx` so "scrolled end"
    // stays stable even when the renderer reports e.g. `reachedPx: 1200`
    // with content actually exhausted at that offset. TOP has no outcome
    // — the renderer doesn't drive any scrollable for it.
    if (scroll.mode === "TOP") return "scroll top";
    if (scroll.atEnd) return "scrolled end";
    if (scroll.reachedPx != null) return `scrolled ${scroll.reachedPx}px`;
    if (scroll.mode === "END") return "scroll end";
    if (scroll.mode === "LONG") return "scroll long";
    if (scroll.mode === "GIF") return "scroll gif";
    return `scroll ${scroll.mode.toLowerCase()}`;
}

/** A preview is shown with a carousel when it has >1 capture OR a single
 *  capture with any non-null dimension (explicit point in dim-space). */
export function isAnimatedPreview(p: PreviewInfo): boolean {
    const captures = p.captures;
    if (captures.length > 1) return true;
    if (captures.length === 1) {
        const c = captures[0];
        return c.advanceTimeMillis != null || c.scroll != null;
    }
    return false;
}

/** Image-bearing data product? `render/scroll/long` lands at .png and
 *  `render/scroll/gif` at .gif; non-image kinds (a11y/atf, a11y/hierarchy)
 *  carry JSON paths and are filtered out. */
function isImageDataProduct(p: PreviewDataProduct): boolean {
    if (!p.output) return false;
    const lower = p.output.toLowerCase();
    return lower.endsWith(".png") || lower.endsWith(".gif");
}

function dataProductToCapture(p: PreviewDataProduct): Capture {
    const cap: Capture = {
        advanceTimeMillis: p.advanceTimeMillis,
        scroll: p.scroll,
        renderOutput: p.output,
    };
    if (p.cost != null) cap.cost = p.cost;
    cap.label = captureLabel(cap);
    return cap;
}

/** Returns a copy of [preview] with image-bearing data products folded into
 *  the captures list as additional carousel frames. The webview only renders
 *  `captures`; surfacing `@ScrollingPreview(LONG/GIF)` requires moving those
 *  entries across the boundary so the panel actually paints them.
 *
 *  When a scroll image data product is present, the base static capture
 *  (no `advanceTimeMillis`, no `scroll`) is dropped: the LONG stitched image
 *  starts at the top and the GIF includes the initial frame, so the static
 *  frame is just a redundant duplicate of the scroll capture's starting
 *  state. Animated (`advanceTimeMillis`-bearing) and scroll-mode
 *  (`scroll != null`, e.g. `TOP`/`END`) captures are kept.
 *
 *  Order: surviving base captures first, then data products in declaration
 *  order. */
export function withDataProductCaptures(preview: PreviewInfo): PreviewInfo {
    const products = (preview.dataProducts ?? []).filter(isImageDataProduct);
    if (products.length === 0) return preview;
    const baseCaptures = preview.captures.filter(
        (c) => c.advanceTimeMillis != null || c.scroll != null,
    );
    return {
        ...preview,
        captures: [...baseCaptures, ...products.map(dataProductToCapture)],
    };
}

/** Returns the index in [preview]'s displayed captures (post
 *  [withDataProductCaptures]) of the static representative capture — the one
 *  with no `scroll` and no `advanceTimeMillis`. Returns `-1` when no such
 *  slot survives the fold.
 *
 *  The daemon's v1 `renderFinished` targets the representative (static)
 *  capture only; the host posts `updateImage` to this slot rather than
 *  blindly index 0. When `@ScrollingPreview(LONG/GIF)` replaces the static
 *  base with a data product, the daemon's PNG has nowhere to go and the
 *  caller skips the paint — otherwise the daemon's non-scrolling bytes
 *  would clobber the scroll-long card with the wrong image. */
export function staticBaseCaptureIndex(preview: PreviewInfo): number {
    const display = withDataProductCaptures(preview);
    for (let i = 0; i < display.captures.length; i++) {
        const c = display.captures[i];
        if (c.advanceTimeMillis == null && c.scroll == null) return i;
    }
    return -1;
}
