// Per-card bundle overlay paint — stamps `<box-overlay>` elements
// inside a focused preview card's `.image-container` so any active
// bundle whose data carries bounds (history-diff regions,
// future: a11y migration off `applyHierarchyOverlay`, text overflow
// rectangles) shows tinted boxes on the rendered preview.
//
// Initial slice (#1054 follow-up) is focused-only and per-bundle:
// the host calls `paintBundleBoxes(card, bundleId, boxes)` from each
// bundle's refresh function, and `clearBundleBoxes(card?, bundleId)`
// from the bundle's deactivation path. Multiple bundles can paint at
// once — each gets its own `<box-overlay>` layer keyed on
// `data-bundle`. Generalising to grid-mode selection (paint on every
// visible card) is a follow-up; this slice closes the gap on the
// focused card only, mirroring how a11y already paints today via
// `applyHierarchyOverlay`.

import { BoxOverlay, type OverlayBox } from "./components/BoxOverlay";
import { sanitizeId } from "./cardData";

/**
 * Find the card element for [previewId] in the grid. Mirrors the
 * id-construction the rest of the panel uses (`sanitizeId(previewId)`
 * via `populatePreviewCard`). Returns `null` when the card hasn't been
 * rendered yet — callers no-op silently in that case.
 */
export function findCardElement(previewId: string): HTMLElement | null {
    return document.getElementById("preview-" + sanitizeId(previewId));
}

/**
 * Mount or update the per-bundle `<box-overlay>` layer on [card]. The
 * layer is keyed on `data-bundle=${bundleId}` so multiple bundles can
 * coexist without clobbering each other's boxes.
 *
 * Empty `boxes` clears the layer in place rather than removing the DOM
 * — the next non-empty refresh repopulates it without thrash, matching
 * `BoxOverlay.setBoxes`'s own no-op behaviour.
 *
 * Natural dimensions come from the card's `<img>`. When the image
 * hasn't loaded yet we attach a one-time `load` listener so the layer
 * paints once the bytes land — same pattern
 * `_repaintA11yOverlaysFromCache` uses.
 */
export function paintBundleBoxes(
    card: HTMLElement,
    bundleId: string,
    boxes: readonly OverlayBox[],
): void {
    const container = card.querySelector<HTMLElement>(".image-container");
    if (!container) return;
    const overlay = ensureBundleOverlayLayer(container, bundleId);
    overlay.setBoxes(boxes);
    const img = card.querySelector<HTMLImageElement>(".image-container img");
    if (!img) return;
    if (img.complete && img.naturalWidth > 0) {
        overlay.setNaturalSize(img.naturalWidth, img.naturalHeight);
        return;
    }
    // Image not yet decoded — defer to the load event. Guard against
    // stacking listeners across rapid refreshes via a per-bundle
    // dataset flag on the `<img>`.
    const flag = "bundleOverlayPaint_" + bundleId.replace(/[^a-z0-9]/gi, "_");
    if (img.dataset[flag] === "1") return;
    img.dataset[flag] = "1";
    img.addEventListener(
        "load",
        () => {
            delete img.dataset[flag];
            const stillThere = container.querySelector<BoxOverlay>(
                'box-overlay[data-bundle="' + bundleId + '"]',
            );
            if (stillThere && img.naturalWidth > 0) {
                stillThere.setNaturalSize(img.naturalWidth, img.naturalHeight);
            }
        },
        { once: true },
    );
}

/**
 * Tear down the per-bundle layer on [card] (or every card when
 * omitted). Called from each bundle's deactivation path so chip
 * dismissal clears the boxes from every card the bundle touched —
 * preserves the chip-toggle = full-teardown contract from
 * `docs/design/EXTENSION_DATA_EXPOSURE.md`.
 */
export function clearBundleBoxes(
    card: HTMLElement | null,
    bundleId: string,
): void {
    const root = card ?? document;
    const layers = root.querySelectorAll(
        'box-overlay[data-bundle="' + bundleId + '"]',
    );
    for (const layer of Array.from(layers)) layer.remove();
}

function ensureBundleOverlayLayer(
    container: HTMLElement,
    bundleId: string,
): BoxOverlay {
    const existing = container.querySelector<BoxOverlay>(
        'box-overlay[data-bundle="' + bundleId + '"]',
    );
    if (existing) return existing;
    const overlay = document.createElement("box-overlay") as BoxOverlay;
    overlay.dataset.bundle = bundleId;
    overlay.classList.add("bundle-card-overlay");
    container.appendChild(overlay);
    return overlay;
}
