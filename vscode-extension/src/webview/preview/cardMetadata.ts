// Per-card metadata refresh for `<preview-card>` reseeds.
//
// Lifted from `cardBuilder.ts` so the manifest-reseed path is testable
// under happy-dom without dragging cardBuilder's wider transitive
// imports (Lit-decorator-using `<message-banner>`, the focus inspector,
// the diff overlay) into the host tsconfig. The component
// (`<preview-card>`) calls `refreshCardMetadata` from its reactive
// `updated()` hook when the `preview` property is reassigned by
// `renderPreviews`'s manifest reseed.
//
// Logic is unchanged from the old `updateCardMetadata`: dataset patch,
// title text, capture-cache merge (preserving `imageData` for surviving
// renderOutputs), variant badge add/remove, a11y legend + overlay
// rebuild. The capture-cache merge is the load-bearing bit — tested
// directly here so the @RoboComposePreviewOptions count-change path
// stays guarded.
//
// Companion to `cardImage.ts` (per-frame paint) and `populatePreviewCard`
// in `cardBuilder.ts` (initial DOM build, which the `<preview-card>`
// shell still reaches into during `firstUpdated`).

import {
    buildTooltip,
    buildVariantLabel,
    isAnimatedPreview,
    isWearPreview,
} from "./cardData";
import type {
    CapturePresentation,
    FrameCarouselController,
} from "./frameCarousel";
import { previewStore, setCardCaptures } from "./previewStore";
import type { PreviewInfo } from "../shared/types";

/** Subset of the card-builder collaborator surface that
 *  `refreshCardMetadata` reaches for. Kept narrow so this helper —
 *  and its tests — only depend on what's actually used. The capture
 *  cache lives in `previewStore` (see `setCardCaptures`), so it
 *  isn't part of this surface. */
export interface CardMetadataConfig {
    frameCarousel: Pick<FrameCarouselController, "updateIndicator">;
    /** Whether `composePreview.earlyFeatures` is on — gates the a11y
     *  legend / overlay rebuild. */
    earlyFeatures(): boolean;
}

/**
 * Refresh an existing card after a `setPreviews` for an id we already
 * have in the grid. Patches the card's dataset, title text, capture
 * cache (preserving already-received `imageData` for surviving
 * renderOutputs), variant badge, and a11y overlay layer.
 *
 * `card` is the `<preview-card>` host element — its DOM was built up
 * front by `populatePreviewCard` so the selectors below
 * (`.card-title`, `.image-container`, `.variant-badge`,
 * `.a11y-overlay`) all resolve. Idempotent: subsequent calls overwrite
 * the patched fields cleanly.
 */
export function refreshCardMetadata(
    card: HTMLElement,
    p: PreviewInfo,
    config: CardMetadataConfig,
): void {
    card.dataset.function = p.functionName;
    card.dataset.group = p.params.group || "";
    card.dataset.wearPreview = isWearPreview(p) ? "1" : "0";
    const title = card.querySelector<HTMLButtonElement>(".card-title");
    if (title) {
        title.textContent =
            p.functionName + (p.params.name ? " — " + p.params.name : "");
        title.title = buildTooltip(p);
    }
    // Refresh capture labels in place. If the capture count changed
    // (e.g. user edited @RoboComposePreviewOptions) we preserve
    // already-received imageData for renderOutputs that carry over.
    const newCaps = p.captures.map((c) => ({
        renderOutput: c.renderOutput,
        label: c.label || "",
    }));
    const prior = previewStore.getState().cardCaptures.get(p.id) ?? [];
    // Match by index rather than renderOutput since filenames may
    // legitimately change (e.g. a preview gains a @RoboComposePreviewOptions
    // annotation). Mismatched positions just reset to null-image.
    const mergedCaps = newCaps.map(
        (nc, i): CapturePresentation => ({
            label: nc.label,
            renderOutput: nc.renderOutput || "",
            imageData: prior[i]?.imageData ?? null,
            errorMessage: prior[i]?.errorMessage ?? null,
            renderError: prior[i]?.renderError ?? null,
        }),
    );
    setCardCaptures(p.id, mergedCaps);
    const curIdx = parseInt(card.dataset.currentIndex || "0", 10);
    if (curIdx >= mergedCaps.length) {
        card.dataset.currentIndex = String(Math.max(0, mergedCaps.length - 1));
    }
    if (isAnimatedPreview(p)) config.frameCarousel.updateIndicator(card);
    const variantLabel = buildVariantLabel(p);
    let badge = card.querySelector(".variant-badge");
    if (variantLabel) {
        if (!badge) {
            badge = document.createElement("div");
            badge.className = "variant-badge";
            card.appendChild(badge);
        }
        badge.textContent = variantLabel;
    } else if (badge) {
        badge.remove();
    }

    // A11y overlay paint moved to the A11y bundle (#1087): the chip
    // is the gate, `refreshA11yBundle` calls
    // `paintBundleBoxes(card, 'a11y', ...)` via the shared
    // `cardBundleOverlay` helper, and chip dismissal tears the layer
    // down through `clearBundleBoxes`. cardMetadata stays focused on
    // dataset/badge/capture-cache refresh; legacy `.a11y-overlay`
    // stamps are gone.
}
