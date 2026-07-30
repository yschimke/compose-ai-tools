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
 * Preview kinds whose rendered surface is an inflated platform View, not a
 * live Compose tree — they can't take pointer/keyboard input, so live mode
 * would do nothing useful and only confuses the toolbar. We disable the LIVE
 * control for these. Compose previews (interactive) and tiles (kept live by
 * request — Wear tiles carry clickable actions) are intentionally excluded.
 */
export const LIVE_UNSUPPORTED_KINDS: ReadonlySet<string> = new Set([
    "NOTIFICATION",
    "GLANCE_APPWIDGET",
]);

/**
 * Whether live mode should be offered for [p]. `true` for the default Compose
 * path (and `null`/absent kind, treated as Compose) and for tiles; `false` for
 * the non-interactive surfaces in [LIVE_UNSUPPORTED_KINDS]. A missing preview
 * is treated as supported so a transient lookup miss never wrongly greys the
 * button mid-navigation.
 */
export function kindSupportsLiveMode(
    p: PreviewInfo | undefined | null,
): boolean {
    const kind = p?.params?.kind ?? null;
    return kind == null || !LIVE_UNSUPPORTED_KINDS.has(kind);
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
 * Where the card title should navigate to. A `@Preview` is usually a thin
 * wrapper around a real component, and discovery infers that component into
 * [PreviewInfo.targets] (most-confident first — see `PreviewTargetInference`).
 * When a target is known we point navigation at the *component's* source rather
 * than the preview stub, which is what a developer clicking a preview expects
 * ("take me to the thing I'm looking at"). Falls back to the preview function's
 * own coordinates when no target cleared discovery's confidence threshold.
 *
 * [sourceFile] is the manifest-provided, module-relative path — authoritative
 * for resolving the file, and carried alongside so the host doesn't have to
 * reconstruct it from the class name (which breaks for files whose name differs
 * from the class). [isComponent] is `true` when the destination is an inferred
 * target rather than the preview itself; the tooltip uses it for wording.
 */
export interface PreviewSourceTarget {
    className: string;
    functionName: string;
    sourceFile: string | null;
    isComponent: boolean;
}

export function previewSourceTarget(p: PreviewInfo): PreviewSourceTarget {
    const target = p.targets && p.targets.length > 0 ? p.targets[0] : null;
    if (target) {
        return {
            className: target.className,
            functionName: target.functionName,
            sourceFile: target.sourceFile,
            isComponent: true,
        };
    }
    return {
        className: p.className,
        functionName: p.functionName,
        sourceFile: p.sourceFile,
        isComponent: false,
    };
}

/**
 * Stamp the title's navigation destination onto the card's dataset so the
 * click handler reads it fresh at click time. Called on initial build and on
 * every manifest reseed (`refreshCardMetadata`), so a reused card whose inferred
 * target changed navigates to the new component — matching the reseeded tooltip
 * — rather than the one captured when the handler was first attached.
 */
export function writeNavDataset(card: HTMLElement, p: PreviewInfo): void {
    const nav = previewSourceTarget(p);
    card.dataset.navClassName = nav.className;
    card.dataset.navFunction = nav.functionName;
    if (nav.sourceFile) {
        card.dataset.navSourceFile = nav.sourceFile;
    } else {
        delete card.dataset.navSourceFile;
    }
}

/**
 * Hover tooltip for the card title button — `Open source: <FQN>` (or
 * `Open component: <FQN>` when the title navigates to an inferred target
 * composable), followed by a `·`-separated digest of the preview's parameters.
 */
export function buildTooltip(p: PreviewInfo): string {
    const nav = previewSourceTarget(p);
    const verb = nav.isComponent ? "Open component: " : "Open source: ";
    const base = verb + nav.className + "." + nav.functionName;
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
    if (p.referenced && p.sourceFile) {
        parts.push("from " + p.sourceFile);
    }
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
