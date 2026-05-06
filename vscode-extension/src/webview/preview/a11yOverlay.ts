// Per-card accessibility overlays — finding legend + finding box layer +
// hierarchy box layer.
//
// Lifted verbatim from `behavior.ts`'s `buildA11yLegend` /
// `buildA11yOverlay` / `highlightA11yFinding` / `ensureHierarchyOverlay`
// / `applyHierarchyOverlay` cluster. All five functions are pure DOM
// helpers — same `<div class="a11y-legend">` row layout, same
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

import type {
    AccessibilityFinding,
    AccessibilityNode,
    PreviewInfo,
} from "../shared/types";
import { parseBounds } from "./cardData";

/**
 * Builds the per-card legend (one row per finding) shown beneath the
 * preview image. Hovering a row surfaces the matching overlay box via
 * the shared `.a11y-active` class — the hover callback walks the
 * card's own `[data-finding-idx]` matches, so it doesn't need a
 * cross-card DOM lookup. Caller is responsible for `appendChild`-ing
 * the result to the card.
 *
 * Pre: `p.a11yFindings` is non-null and non-empty. `behavior.ts`
 * gates the call on `findings.length > 0` already.
 */
export function buildA11yLegend(
    card: HTMLElement,
    p: PreviewInfo,
): HTMLElement {
    const findings = p.a11yFindings ?? [];
    const legend = document.createElement("div");
    legend.className = "a11y-legend";
    const header = document.createElement("div");
    header.className = "a11y-legend-header";
    header.textContent = "Accessibility (" + findings.length + ")";
    legend.appendChild(header);
    findings.forEach((f, idx) => {
        const row = document.createElement("div");
        row.className =
            "a11y-row a11y-level-" + (f.level || "info").toLowerCase();
        row.dataset.previewId = p.id;
        row.dataset.findingIdx = String(idx);

        const badge = document.createElement("span");
        badge.className = "a11y-badge";
        badge.textContent = String(idx + 1);
        row.appendChild(badge);

        const text = document.createElement("div");
        text.className = "a11y-text";
        const title = document.createElement("div");
        title.className = "a11y-title";
        title.textContent = f.level + " · " + f.type;
        const msg = document.createElement("div");
        msg.className = "a11y-msg";
        msg.textContent = f.message;
        text.appendChild(title);
        text.appendChild(msg);
        if (f.viewDescription) {
            const elt = document.createElement("div");
            elt.className = "a11y-elt";
            elt.textContent = f.viewDescription;
            text.appendChild(elt);
        }
        row.appendChild(text);
        row.addEventListener("mouseenter", () =>
            highlightA11yFinding(card, idx),
        );
        row.addEventListener("mouseleave", () =>
            highlightA11yFinding(card, null),
        );
        legend.appendChild(row);
    });
    return legend;
}

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
 * Paints one translucent rectangle per accessibility-relevant node.
 * Uses the same `boundsInScreen` percent-of-natural translation as
 * the finding overlay, so the math stays trivial. Each box gets a
 * `title` summarising `label / role / states` so a hover yields the
 * same data TalkBack would announce, without baking any of it into
 * the PNG.
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
    nodes.forEach((n) => {
        const bounds = parseBounds(n.boundsInScreen);
        if (!bounds) return;
        const box = document.createElement("div");
        box.className =
            "a11y-hierarchy-box" + (n.merged ? "" : " a11y-hierarchy-unmerged");
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

/**
 * Adds `.a11y-active` to the legend row + overlay box matching
 * [idx] inside [card], removing any previously-active siblings.
 * Pass `null` to clear.
 */
function highlightA11yFinding(card: HTMLElement, idx: number | null): void {
    for (const el of card.querySelectorAll(
        ".a11y-row.a11y-active, .a11y-box.a11y-active",
    )) {
        el.classList.remove("a11y-active");
    }
    if (idx === null) return;
    for (const el of card.querySelectorAll(
        '[data-finding-idx="' + idx + '"]',
    )) {
        el.classList.add("a11y-active");
    }
}
