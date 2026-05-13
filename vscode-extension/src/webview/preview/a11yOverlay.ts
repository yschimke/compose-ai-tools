// Per-card accessibility overlays — finding box layer + hierarchy box
// layer.
//
// Lifted from `behavior.ts`'s `buildA11yOverlay` / `ensureHierarchyOverlay`
// / `applyHierarchyOverlay` cluster. All three are pure DOM helpers —
// absolutely-positioned percentage-coord boxes inside `.a11y-overlay`
// and `.a11y-hierarchy-overlay`. The host (`behavior.ts`) keeps owning
// the per-preview caches (`cardA11yFindings`, `cardA11yNodes`), the
// `applyA11yUpdate` orchestration, and the `updateImage` rebuild path
// — they read `allPreviews` / `filterToolbar` / `renderFocusInspector`
// and don't fit a pure-DOM module.
//
// `boundsInScreen` is the renderer's source-bitmap pixel space; we
// translate to percentages of the image's natural dimensions so the
// overlay layer scales with the image without a resize handler. See
// `docs/daemon/DATA-PRODUCTS.md` § "Worked example" for the
// `a11y/atf` (findings) and `a11y/hierarchy` (nodes) data products.
//
// The labelled legends (`buildA11yLegend`, `buildA11yHierarchyLegend`)
// that used to live alongside these helpers were replaced by the
// dedicated accessibility tab in the bundle shell — see PR #1065.

import type { AccessibilityFinding, AccessibilityNode } from "../shared/types";
import { parseBounds } from "./cardData";

/**
 * Builds the absolutely-positioned overlay boxes on top of the
 * rendered preview image. Runs once per image load —
 * `boundsInScreen` is in image-pixel coordinates, so we translate to
 * percentages of the image natural dimensions. The overlay layer
 * scales with the image (`position: absolute; inset: 0` inside
 * `.image-container` which sizes to the `<img>`), so percentage
 * bounds stay correct across layout changes without a resize
 * handler.
 *
 * Caller pre-creates the empty `.a11y-overlay` div via `createCard`
 * (when findings exist at card-creation time) or
 * `applyA11yUpdate` (when findings arrive later); this function
 * just paints the box children.
 */
export function buildA11yOverlay(
    card: HTMLElement,
    findings: readonly AccessibilityFinding[],
    img: HTMLImageElement,
): void {
    const overlay = card.querySelector<HTMLElement>(".a11y-overlay");
    if (!overlay) return;
    overlay.innerHTML = "";
    const natW = img.naturalWidth;
    const natH = img.naturalHeight;
    if (!natW || !natH) return;
    findings.forEach((f, idx) => {
        const bounds = parseBounds(f.boundsInScreen);
        if (!bounds) return;
        const box = document.createElement("div");
        box.className =
            "a11y-box a11y-level-" + (f.level || "info").toLowerCase();
        box.dataset.findingIdx = String(idx);
        box.style.left = (bounds.left / natW) * 100 + "%";
        box.style.top = (bounds.top / natH) * 100 + "%";
        box.style.width = ((bounds.right - bounds.left) / natW) * 100 + "%";
        box.style.height = ((bounds.bottom - bounds.top) / natH) * 100 + "%";
        const badge = document.createElement("span");
        badge.className = "a11y-badge";
        badge.textContent = String(idx + 1);
        box.appendChild(badge);
        overlay.appendChild(box);
    });
}

/**
 * Idempotently attaches the empty `.a11y-hierarchy-overlay` layer to
 * the card's image container, ready for `applyHierarchyOverlay` to
 * paint into. Called by `applyA11yUpdate` when fresh nodes arrive
 * from the daemon.
 */
export function ensureHierarchyOverlay(container: Element | null): void {
    if (!container) return;
    if (container.querySelector(".a11y-hierarchy-overlay")) return;
    const layer = document.createElement("div");
    layer.className = "a11y-hierarchy-overlay";
    layer.setAttribute("aria-hidden", "true");
    container.appendChild(layer);
}

/**
 * Palette cycled per node index so overlay boxes get deterministic,
 * perceptually-distinct hues. Wraps with `% length` so node lists
 * longer than the palette still get repeatable colours.
 */
export const A11Y_HIERARCHY_PALETTE: readonly string[] = [
    "#e57373",
    "#64b5f6",
    "#81c784",
    "#ffb74d",
    "#ba68c8",
    "#4db6ac",
    "#f06292",
    "#a1887f",
];

function colorForNodeIndex(idx: number): string {
    return A11Y_HIERARCHY_PALETTE[idx % A11Y_HIERARCHY_PALETTE.length];
}

/**
 * Paints one translucent rectangle per accessibility-relevant node.
 * Uses the same `boundsInScreen` percent-of-natural translation as
 * the finding overlay, so the math stays trivial. Each box gets a
 * `title` summarising `label / role / states` so a hover yields the
 * same data TalkBack would announce, without baking any of it into
 * the PNG. Per-node colour comes from `A11Y_HIERARCHY_PALETTE`
 * (cycled by index) and is exposed via the `--a11y-hier-color` CSS
 * custom property so downstream consumers (e.g. the accessibility
 * tab) can match swatches.
 */
export function applyHierarchyOverlay(
    card: HTMLElement,
    nodes: readonly AccessibilityNode[],
    img: HTMLImageElement,
): void {
    const overlay = card.querySelector<HTMLElement>(".a11y-hierarchy-overlay");
    if (!overlay) return;
    overlay.innerHTML = "";
    const natW = img.naturalWidth;
    const natH = img.naturalHeight;
    if (!natW || !natH) return;
    nodes.forEach((n, idx) => {
        const bounds = parseBounds(n.boundsInScreen);
        if (!bounds) return;
        const box = document.createElement("div");
        box.className =
            "a11y-hierarchy-box" + (n.merged ? "" : " a11y-hierarchy-unmerged");
        box.dataset.nodeIdx = String(idx);
        box.style.setProperty("--a11y-hier-color", colorForNodeIndex(idx));
        box.style.left = (bounds.left / natW) * 100 + "%";
        box.style.top = (bounds.top / natH) * 100 + "%";
        box.style.width = ((bounds.right - bounds.left) / natW) * 100 + "%";
        box.style.height = ((bounds.bottom - bounds.top) / natH) * 100 + "%";
        const tooltipParts: string[] = [];
        if (n.label) tooltipParts.push(n.label);
        if (n.role) tooltipParts.push(n.role);
        if (n.states && n.states.length) tooltipParts.push(n.states.join(", "));
        if (tooltipParts.length) box.title = tooltipParts.join(" · ");
        overlay.appendChild(box);
    });
}
