// Per-card bundle overlay paint — stamps `<box-overlay>` elements
// inside a preview card's `.image-container` so any active bundle
// whose data carries bounds (history-diff regions, a11y findings,
// future: text-overflow rectangles) shows tinted boxes on the
// rendered preview.
//
// Two host entry points:
//   - `paintBundleBoxes(card, bundleId, boxes)` — single-card paint,
//     used when focus mode is active and only the focused card is
//     showing data overlays.
//   - `paintBundleBoxesEverywhere(bundleId, perCardData)` — grid
//     mode: paints every visible `.preview-card` with that card's
//     own per-bundle boxes. The multi-preview fallback: focused
//     preview wins, otherwise every visible card paints. (Production
//     scopes overlays to the focused preview — see `bundleRegistry.ts`
//     — so this path is exercised only by the grid-paint primitive.)
//
// Multiple bundles can paint simultaneously — each owns its own
// `<box-overlay>` layer keyed on `data-bundle`. Teardown
// (`clearBundleBoxes(null, id)`) wipes every card the bundle touched
// in one pass via `document.querySelectorAll`.

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
    // stacking listeners across rapid refreshes via a single dataset
    // field on the `<img>` holding a JSON-encoded `{ [bundleId]: true }`
    // map. One field for all bundles avoids proliferating
    // `bundleOverlayPaint_<id>` keys on the dataset as the catalogue
    // grows (issue #1104).
    const pending = readPendingPaintMap(img);
    if (pending[bundleId]) return;
    pending[bundleId] = true;
    writePendingPaintMap(img, pending);
    img.addEventListener(
        "load",
        () => {
            const after = readPendingPaintMap(img);
            delete after[bundleId];
            writePendingPaintMap(img, after);
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
 * Read the pending-paint map off [img]. Returns a fresh object — never
 * shares the underlying record so callers can mutate freely before
 * writing it back. The dataset value is JSON; if it's missing,
 * malformed, or decodes to something other than a plain object we treat
 * it as empty rather than throwing, so a corrupted dataset attribute
 * (set by external code in tests, devtools, etc.) can't wedge the
 * paint pipeline.
 */
function readPendingPaintMap(img: HTMLImageElement): Record<string, true> {
    const raw = img.dataset.bundleOverlayPaint;
    if (!raw) return {};
    try {
        const parsed = JSON.parse(raw) as unknown;
        if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
            return { ...(parsed as Record<string, true>) };
        }
    } catch {
        // Malformed JSON — fall through to empty map.
    }
    return {};
}

/**
 * Write the pending-paint map back onto [img]. When the map is empty we
 * delete the dataset attribute so the DOM stays tidy (and so a read of
 * a never-set image returns `{}` cleanly).
 */
function writePendingPaintMap(
    img: HTMLImageElement,
    map: Record<string, true>,
): void {
    const keys = Object.keys(map);
    if (keys.length === 0) {
        delete img.dataset.bundleOverlayPaint;
        return;
    }
    img.dataset.bundleOverlayPaint = JSON.stringify(map);
}

/**
 * Tear down the per-bundle layer on [card] (or every card when
 * omitted). Called from each bundle's deactivation path so chip
 * dismissal clears the boxes from every card the bundle touched —
 * preserves the chip-toggle = full-teardown contract (see
 * `bundleRegistry.ts`).
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

/**
 * Maximum number of cards we'll stamp `<box-overlay>` layers into
 * during a single grid-mode paint. Above this cap we abort with a
 * `console.warn` and paint zero cards rather than blocking the main
 * thread for an unbounded number of DOM mutations on huge previews
 * sets. The number is intentionally generous — typical screens host
 * a handful of cards and even dense column layouts rarely cross the
 * dozens — but bounded so a pathological workspace doesn't freeze
 * the panel. See `paintBundleBoxesEverywhere`.
 */
export const GRID_PAINT_CARD_CAP = 50;

/**
 * Return the `data-preview-id` of every `.preview-card` currently
 * mounted in the DOM, in document order, skipping cards that are
 * hidden via the `[hidden]` attribute or `display:none`. Used by
 * grid-mode bundle paint to iterate cards exactly once per refresh
 * without double-stamping hidden siblings the user can't see.
 */
export function getVisiblePreviewIds(): string[] {
    const cards = document.querySelectorAll<HTMLElement>(
        ".preview-card[data-preview-id]",
    );
    const ids: string[] = [];
    for (const card of Array.from(cards)) {
        if (isCardHidden(card)) continue;
        const id = card.dataset.previewId;
        if (id) ids.push(id);
    }
    return ids;
}

/**
 * Paint [bundleId]'s boxes on every visible `.preview-card` in the
 * grid. [perCardData] maps `previewId → OverlayBox[]` — cards
 * present in the map paint those boxes, cards absent from the map
 * get the empty-set call (clear-in-place) so a stale layer from a
 * previous refresh doesn't linger.
 *
 * When the number of visible cards exceeds `GRID_PAINT_CARD_CAP`
 * the call is a no-op apart from a single `console.warn`. The
 * existing `previewStore` already holds every card's data; the cost
 * a cap protects against is per-card DOM stamping, not data layout.
 */
export function paintBundleBoxesEverywhere(
    bundleId: string,
    perCardData: ReadonlyMap<string, readonly OverlayBox[]>,
): void {
    const cards = document.querySelectorAll<HTMLElement>(
        ".preview-card[data-preview-id]",
    );
    const visibleCards: HTMLElement[] = [];
    for (const card of Array.from(cards)) {
        if (isCardHidden(card)) continue;
        visibleCards.push(card);
    }
    if (visibleCards.length > GRID_PAINT_CARD_CAP) {
        console.warn(
            "[cardBundleOverlay] paintBundleBoxesEverywhere skipping " +
                bundleId +
                ": " +
                visibleCards.length +
                " visible cards exceeds cap " +
                GRID_PAINT_CARD_CAP,
        );
        return;
    }
    for (const card of visibleCards) {
        const id = card.dataset.previewId;
        if (!id) continue;
        const boxes = perCardData.get(id) ?? [];
        paintBundleBoxes(card, bundleId, boxes);
    }
}

/**
 * Paint [bundleId]'s boxes on the focused card only, or tear the layer
 * down when there is no focused card. This is the production paint path
 * for bundle overlays: bundles scope to the focused preview, and
 * `bundleChipBar` / `<data-tabs>` are hidden outside focus layout, so a
 * bundle must never paint an overlay on an ambient grid selection
 * (bundles scope to the focused preview — see `bundleRegistry.ts`).
 *
 * Passing `null` (grid/flow/column layout, or focus mode with no card
 * selected) clears every layer the bundle previously stamped — without
 * this, a layer painted while a card was focused would linger on that
 * card after the user dropped back to the grid (#1567). Passing a card
 * with no `data-preview-id` is treated the same as `null` since there's
 * no preview to compute boxes for.
 */
export function paintBundleBoxesForFocus(
    bundleId: string,
    focusedCard: HTMLElement | null,
    computeOverlay: (previewId: string) => readonly OverlayBox[],
): void {
    const previewId = focusedCard?.dataset.previewId;
    if (!focusedCard || !previewId) {
        clearBundleBoxes(null, bundleId);
        return;
    }
    paintBundleBoxes(focusedCard, bundleId, computeOverlay(previewId));
}

function isCardHidden(card: HTMLElement): boolean {
    if (card.hasAttribute("hidden")) return true;
    // happy-dom doesn't run CSS layout so `getComputedStyle` only
    // resolves the inline declaration — sufficient for our test
    // fixtures and matches how the grid toggles cards
    // (`card.style.display = "none"` via `FilterController`).
    if (card.style.display === "none") return true;
    const view = card.ownerDocument?.defaultView;
    if (view && typeof view.getComputedStyle === "function") {
        const computed = view.getComputedStyle(card);
        if (computed.display === "none") return true;
    }
    return false;
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
