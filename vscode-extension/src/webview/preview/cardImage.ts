// Per-frame image paint for `<preview-card>`.
//
// Lifted from `cardBuilder.ts`'s `updateImage` so the per-frame paint
// is testable under happy-dom without dragging cardBuilder's wider
// transitive imports into the host tsconfig. The component
// (`<preview-card>`) calls `paintCardCapture` from its `paintCapture`
// method, which the message dispatcher invokes after resolving the
// card by id.
//
// Logic is unchanged from the old `updateImage`: cache mutation,
// currently-displayed-vs-arriving capture comparison, container
// teardown (skeleton / loading / error), `<img>` swap (or create) +
// fade-in / live-frame class, interactive-input rebind, mapsRevision
// bump for the `<preview-card>` overlay subscription, carousel
// indicator refresh, diff-overlay auto-refresh.
//
// The `<preview-card>` reads the card by id from messageHandlers and
// calls `paintCapture(captureIndex, imageData)` on the component;
// the component delegates here with `this` as `card`.

import { mimeFor } from "./cardData";
import { showDiffOverlay, type DiffOverlayConfig } from "./diffOverlay";
import type { FrameCarouselController } from "./frameCarousel";
import {
    attachInteractiveInputHandlers,
    type InteractiveInputConfig,
} from "./interactiveInput";
import type { LiveStateController } from "./liveState";
import { bumpPreviewMapsRevision, previewStore } from "./previewStore";
import type { VsCodeApi } from "../shared/vscode";

/** Subset of the card-builder collaborator surface that
 *  `paintCardCapture` reaches for — every per-frame paint dependency.
 *  The per-preview caches (`cardCaptures`, `cardA11yFindings`,
 *  `cardA11yNodes`) live in `previewStore` and are read directly there. */
export interface CardImageConfig {
    vscode: VsCodeApi<unknown>;
    frameCarousel: Pick<FrameCarouselController, "updateIndicator">;
    liveState: Pick<LiveStateController, "isLive">;
    interactiveInputConfig: InteractiveInputConfig;
    diffOverlayConfig: DiffOverlayConfig;
    /** Whether `composePreview.earlyFeatures` is on — gates the diff
     *  overlay auto-refresh. */
    earlyFeatures(): boolean;
}

/**
 * Paint a fresh capture image into the matching card. Idempotent — if
 * the currently-displayed capture index doesn't match the arriving
 * bytes, the cache is updated and the carousel indicator refreshed but
 * no `<img>` is touched (the bytes wait for prev/next).
 *
 * Side effects (when [captureIndex] matches the displayed capture):
 *  - Mutates the per-capture cache entry on `previewStore`'s
 *    `cardCaptures` and bumps `mapsRevision` so subscribers selecting
 *    on it see the new bytes. The bump is issued AFTER the `<img>`
 *    `src` swap so the `<preview-card>` StoreController fires with the
 *    new image element / src in place and its on-load a11y deferral
 *    attaches against the right src.
 *  - Replaces the card's `<img>` `src` (or creates one if missing).
 *  - Re-attaches the live pointer/wheel handlers via
 *    `attachInteractiveInputHandlers`.
 *  - If a diff overlay is open against `head` / `main`, re-issues the
 *    diff request so the user sees the new bytes without clicking.
 *
 * The a11y overlay repaint that used to live at the tail of the old
 * `updateImage` runs inside `<preview-card>`'s `mapsRevision`
 * subscription — the `bumpPreviewMapsRevision()` call here drives it.
 *
 * `card` is the `<preview-card>` host element; the caller (the
 * component's `paintCapture` method) resolves `this` from the dispatcher.
 */
export function paintCardCapture(
    card: HTMLElement,
    previewId: string,
    captureIndex: number,
    imageData: string,
    config: CardImageConfig,
): void {
    // Cache so carousel navigation can restore this capture without
    // a fresh extension round-trip. Mutates the capture object in
    // place — the Map identity is unchanged. The matching
    // `bumpPreviewMapsRevision()` is deferred until after the
    // `<img>` `src` swap below, so the `<preview-card>` StoreController
    // fires with the new image element / src in place.
    const caps = previewStore.getState().cardCaptures.get(previewId);
    const capture =
        caps && captureIndex >= 0 && captureIndex < caps.length
            ? caps[captureIndex]
            : null;
    if (capture) {
        capture.imageData = imageData;
        capture.errorMessage = null;
        capture.renderError = null;
    }

    // Only paint the <img> if the currently-displayed capture is the
    // one that just arrived. Otherwise the cached bytes wait for
    // prev/next.
    const cur = parseInt(card.dataset.currentIndex || "0", 10);
    if (cur !== captureIndex) {
        if (caps) config.frameCarousel.updateIndicator(card);
        return;
    }

    const container = card.querySelector<HTMLElement>(".image-container");
    if (!container) return;
    // Tear down every prior state before showing the new image.
    // Leftover .error-message divs here are what caused the
    // "Render pending — save the file to trigger a render" banner
    // to stay visible forever even after a successful render.
    container.querySelector(".skeleton")?.remove();
    container.querySelector(".loading-overlay")?.remove();
    container.querySelector(".error-message")?.remove();
    card.classList.remove("has-error");
    // Clear the render-error stamp set by handleErrorMessage so the
    // focus inspector's render-error presenter stops surfacing the
    // stale banner. Paired write/clear keeps the banner state aligned
    // with the visible image state.
    delete card.dataset.renderError;
    delete card.dataset.renderErrorDetail;

    const ro = capture ? capture.renderOutput : "";
    const newSrc = "data:" + mimeFor(ro) + ";base64," + imageData;

    let img = container.querySelector("img");
    if (!img) {
        img = document.createElement("img");
        img.alt = (card.dataset.function ?? "") + " preview";
        container.appendChild(img);
    }
    img.src = newSrc;
    // In live mode the new bytes are a frame, not a card reload —
    // skip the fade-in so successive frames read as a stream rather
    // than a sequence of independent renders. See INTERACTIVE.md § 3.
    const isLive = config.liveState.isLive(previewId);
    img.className = isLive ? "live-frame" : "fade-in";
    attachInteractiveInputHandlers(card, config.interactiveInputConfig);

    // Bump AFTER `img.src` is set so `<preview-card>`'s StoreController
    // sees the new image when it re-runs `_repaintA11yOverlaysFromCache`.
    // The component handles both the immediately-decoded path and the
    // on-load deferral itself — see PreviewCard.
    if (capture) bumpPreviewMapsRevision();

    if (caps) config.frameCarousel.updateIndicator(card);

    // If a diff overlay is open on this card and uses the live render
    // as its left anchor (head / main / current), the bytes the
    // overlay is showing just went stale. Re-issue so the user sees
    // the new render without clicking — symmetric with the
    // compose-preview/main ref watcher's auto-refresh on the right anchor.
    const openDiff = container.querySelector<HTMLElement>(
        ".preview-diff-overlay",
    );
    if (config.earlyFeatures() && openDiff) {
        const against = openDiff.dataset.against;
        if (against === "head" || against === "main") {
            showDiffOverlay(
                card,
                against,
                null,
                null,
                config.diffOverlayConfig,
            );
            config.vscode.postMessage({
                command: "requestPreviewDiff",
                previewId,
                against,
            });
        }
    }
}
