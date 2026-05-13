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

import { buildA11yOverlay } from "./a11yOverlay";
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

    // Refresh the a11y overlay layer in place when findings change
    // (e.g. toggling a11y on turns findings from null → list, or a
    // fresh render updates the set). The labelled legend moved to the
    // A11y bundle tab (#1054); the card itself keeps only the
    // boxes-on-image overlay so spatial context survives.
    const existingOverlay = card.querySelector(".a11y-overlay");
    if (existingOverlay) existingOverlay.innerHTML = "";
    if (config.earlyFeatures() && p.a11yFindings && p.a11yFindings.length > 0) {
        const container = card.querySelector(".image-container");
        if (container && !container.querySelector(".a11y-overlay")) {
            const overlay = document.createElement("div");
            overlay.className = "a11y-overlay";
            overlay.setAttribute("aria-hidden", "true");
            container.appendChild(overlay);
        }
        // Repopulate box geometry if the image is already loaded —
        // otherwise `paintCardCapture`'s store-write triggers a
        // re-paint via `<preview-card>`'s mapsRevision subscription.
        const img = card.querySelector<HTMLImageElement>(
            ".image-container img",
        );
        if (img && img.complete && img.naturalWidth > 0) {
            buildA11yOverlay(card, p.a11yFindings, img);
        }
    } else if (existingOverlay) {
        // No findings or feature off — drop any leftover overlay
        // div so cards stay clean when the user toggles
        // earlyFeatures off mid-session.
        existingOverlay.remove();
    }
}
