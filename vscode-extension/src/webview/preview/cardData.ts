// Pure data helpers for the preview-card surface.
//
// Lifted verbatim from `behavior.ts` so the per-card label / tooltip /
// shape derivations stop being shielded by `@ts-nocheck` and stop
// depending on any closed-over state in `setupPreviewBehavior`. None
// of these reach into the DOM or the vscode handle.

import type { PreviewInfo } from "../shared/types";

/** DOM-safe id derived from a preview id. Used as the `id` of the
 *  card element (`preview-${sanitizeId(p.id)}`) so callers can find
 *  it via `getElementById` even when the underlying preview id has
 *  characters that would be invalid in CSS / HTML id attributes. */
export function sanitizeId(id: string): string {
    return id.replace(/[^a-zA-Z0-9_-]/g, "_");
}

/**
 * Data-URL MIME for a preview image, derived from its `renderOutput`
 * extension. `@ScrollingPreview(GIF)` captures land at `.gif`; all
 * other captures are PNG. Browsers sniff magic bytes and would
 * actually render a GIF served as `image/png` — but declaring the
 * right type matters for the webview's `<img>` fallback /
 * accessibility paths and avoids a console warning when saving the
 * preview.
 */
export function mimeFor(renderOutput: string | null | undefined): string {
    return typeof renderOutput === "string" &&
        renderOutput.toLowerCase().endsWith(".gif")
        ? "image/gif"
        : "image/png";
}

/**
 * A preview is shown with a carousel when it has more than one
 * capture, or a single capture with a non-null dimension (e.g. an
 * explicit 500ms snapshot or a scroll capture).
 */
export function isAnimatedPreview(p: PreviewInfo): boolean {
    const caps = p.captures;
    if (caps.length > 1) return true;
    if (caps.length === 1) {
        const c = caps[0];
        return c.advanceTimeMillis != null || c.scroll != null;
    }
    return false;
}

/**
 * Compact one-line label shown as the variant badge on each card.
 * Longer-form info still lives in the hover tooltip ([buildTooltip])
 * — here we only surface what distinguishes siblings: name / group /
 * device first, then dimensions, non-default fontScale, uiMode.
 * Skips redundant bits (e.g. no `1.0×` for default font).
 */
export function buildVariantLabel(p: PreviewInfo): string {
    const parts: string[] = [];
    const primary =
        p.params.name || p.params.group || shortDevice(p.params.device);
    if (primary) parts.push(primary);
    if (p.params.widthDp && p.params.heightDp) {
        parts.push(p.params.widthDp + "×" + p.params.heightDp);
    }
    if (p.params.fontScale && p.params.fontScale !== 1.0) {
        parts.push(p.params.fontScale + "×");
    }
    if (p.params.uiMode) parts.push("uiMode " + p.params.uiMode);
    if (p.params.locale) parts.push(p.params.locale);
    return parts.join(" · ");
}

export function shortDevice(d: string | null | undefined): string {
    if (!d) return "";
    return d.replace(/^id:/, "").replace(/_/g, " ");
}

/**
 * Heuristic for "this preview is for a Wear OS surface." Trips on an
 * explicit `wear` device id or a square preview at or under 260dp —
 * which catches the standard Wear sizes (192dp small round, 227dp
 * large round, 240dp square) without requiring the device id to be
 * set.
 */
export function isWearPreview(p: PreviewInfo): boolean {
    const device = (p.params.device || "").toLowerCase();
    if (device.includes("wear")) return true;
    const w = p.params.widthDp || 0;
    const h = p.params.heightDp || 0;
    return w > 0 && h > 0 && w === h && w <= 260;
}

/**
 * Hover tooltip for the card title button — `Open source: <FQN>`,
 * followed by a `·`-separated digest of the preview's parameters.
 */
export function buildTooltip(p: PreviewInfo): string {
    const base = "Open source: " + p.className + "." + p.functionName;
    const parts: string[] = [];
    if (p.params.name) parts.push(p.params.name);
    if (p.params.device) parts.push(p.params.device);
    if (p.params.widthDp && p.params.heightDp) {
        parts.push(p.params.widthDp + "×" + p.params.heightDp + "dp");
    }
    if (p.params.fontScale && p.params.fontScale !== 1.0) {
        parts.push("font " + p.params.fontScale + "×");
    }
    if (p.params.uiMode) parts.push("uiMode=" + p.params.uiMode);
    if (p.params.locale) parts.push(p.params.locale);
    if (p.params.group) parts.push("group: " + p.params.group);
    return parts.length ? base + "\\n" + parts.join(" · ") : base;
}

export interface ParsedBounds {
    left: number;
    top: number;
    right: number;
    bottom: number;
}

/**
 * Parse an `AccessibilityFinding.boundsInScreen` / `AccessibilityNode.
 * boundsInScreen` string of the form `"left,top,right,bottom"` (in
 * source-bitmap pixels) into numeric bounds. Returns `null` for
 * malformed input — overlay paint code skips the finding rather than
 * dropping zero/NaN boxes onto the image.
 */
export function parseBounds(s: string | null | undefined): ParsedBounds | null {
    if (!s) return null;
    const parts = s.split(",").map((x) => parseInt(x.trim(), 10));
    if (parts.length !== 4 || parts.some(isNaN)) return null;
    return {
        left: parts[0],
        top: parts[1],
        right: parts[2],
        bottom: parts[3],
    };
}
