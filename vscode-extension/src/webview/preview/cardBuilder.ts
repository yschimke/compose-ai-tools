// Initial DOM build for a single `<div class="preview-card">`.
//
// Lifted verbatim from `behavior.ts`'s `createCard` so the imperative DOM
// construction stops needing closure access to the rest of the panel. The
// dynamic update paths (`updateCardMetadata`, `updateImage`, `applyA11yUpdate`)
// still live in `behavior.ts` for now — they query the card via
// `document.getElementById("preview-" + sanitizeId(id))` and patch in place,
// independent of the initial build path.
//
// The eventual `<preview-card>` Lit component will fold both the initial
// build and the dynamic updates into one reactive `render()`. This module is
// the bridge: it captures every closed-over collaborator
// (`vscode`, `liveState`, `staleBadge`, `frameCarousel`, viewport observer,
// the `cardCaptures` map, the `interactiveInputConfig`) into a typed config
// so the same logic can move into the component without re-resolving each
// dependency through `setupPreviewBehavior`.

import { buildA11yLegend } from "./a11yOverlay";
import {
    buildTooltip,
    buildVariantLabel,
    isAnimatedPreview,
    isWearPreview,
    sanitizeId,
} from "./cardData";
import type {
    CapturePresentation,
    FrameCarouselController,
} from "./frameCarousel";
import type { LiveStateController } from "./liveState";
import type { StaleBadgeController } from "./staleBadge";
import type { PreviewInfo } from "../shared/types";
import type { VsCodeApi } from "../shared/vscode";

export interface CardBuilderConfig {
    vscode: VsCodeApi<unknown>;
    /** Per-preview carousel runtime state — populated here on creation,
     *  read by `updateImage` / `setImageError` / `frameCarousel` later. */
    cardCaptures: Map<string, CapturePresentation[]>;
    staleBadge: StaleBadgeController;
    frameCarousel: FrameCarouselController;
    liveState: LiveStateController;
    /** Whether `composePreview.earlyFeatures` is on — gates the a11y legend
     *  + overlay layer that the build path attaches up front. */
    earlyFeatures(): boolean;
    /** Predicate for "panel is currently in focus layout" — keeps the
     *  per-card focus button's enter/exit toggle in sync with the toolbar. */
    inFocus(): boolean;
    /** Imperative actions the builder hands back to `behavior.ts` rather
     *  than reaching for them at construction time. */
    enterFocus(card: HTMLElement): void;
    exitFocus(): void;
    /** Hook the freshly-built card into the viewport tracker so daemon
     *  scroll-ahead works. */
    observeForViewport(card: HTMLElement): void;
}

/**
 * Build the initial DOM for a preview card. Returns a detached
 * `HTMLDivElement` — caller is responsible for inserting it into the grid in
 * the right position (`renderPreviews` orchestrates a stable insertion order
 * keyed on `previewId`).
 *
 * Side effects:
 *  - Seeds `config.cardCaptures.set(p.id, ...)` with one
 *    `CapturePresentation` per `p.captures` entry.
 *  - Calls `config.staleBadge.apply(card, false)` once so the badge slot
 *    exists in DOM order before the rest of the header is appended.
 *  - Calls `config.observeForViewport(card)` so the viewport tracker
 *    starts watching.
 */
export function buildPreviewCard(
    p: PreviewInfo,
    config: CardBuilderConfig,
): HTMLElement {
    const animated = isAnimatedPreview(p);
    const captures = p.captures;

    const card = document.createElement("div");
    card.className = "preview-card" + (animated ? " animated-card" : "");
    card.id = "preview-" + sanitizeId(p.id);
    card.setAttribute("role", "listitem");
    card.dataset.function = p.functionName;
    card.dataset.group = p.params.group || "";
    card.dataset.previewId = p.id;
    card.dataset.className = p.className;
    card.dataset.wearPreview = isWearPreview(p) ? "1" : "0";
    card.dataset.currentIndex = "0";
    config.cardCaptures.set(
        p.id,
        captures.map(
            (c): CapturePresentation => ({
                label: c.label || "",
                renderOutput: c.renderOutput || "",
                imageData: null,
                errorMessage: null,
                renderError: null,
            }),
        ),
    );

    const header = document.createElement("div");
    header.className = "card-header";

    const titleRow = document.createElement("div");
    titleRow.className = "card-title-row";

    const title = document.createElement("button");
    title.className = "card-title";
    title.textContent =
        p.functionName + (p.params.name ? " — " + p.params.name : "");
    title.title = buildTooltip(p);
    title.addEventListener("click", () => {
        config.vscode.postMessage({
            command: "openFile",
            className: p.className,
            functionName: p.functionName,
        });
    });
    titleRow.appendChild(title);

    if (animated) {
        // Inline marker so the title row telegraphs "this one has
        // multiple captures"; the carousel strip under the image is
        // the interactive surface.
        const icon = document.createElement("i");
        icon.className = "codicon codicon-play-circle animation-icon";
        icon.title = captures.length + " captures";
        icon.setAttribute(
            "aria-label",
            "Animated preview (" + captures.length + " captures)",
        );
        titleRow.appendChild(icon);
    }

    // Per-card focus icon. Replaces the previous "double-click image"
    // affordance — single-click on the image is now reserved for
    // entering LIVE (interactive) mode, so we need an explicit handle
    // for "view this card by itself". Same hot zone toggles between
    // enter-focus (other layouts) and exit-focus (focus layout).
    const focusBtn = document.createElement("button");
    focusBtn.type = "button";
    focusBtn.className = "card-focus-btn";
    focusBtn.innerHTML =
        '<i class="codicon codicon-screen-full" aria-hidden="true"></i>';
    focusBtn.title = "Focus this preview";
    focusBtn.setAttribute("aria-label", "Focus this preview");
    focusBtn.addEventListener("click", (evt) => {
        evt.stopPropagation();
        if (config.inFocus()) {
            config.exitFocus();
        } else {
            config.enterFocus(card);
        }
    });
    titleRow.appendChild(focusBtn);

    // Stale-tier refresh button — only attached up front for cards
    // already known to be stale at setPreviews time. updateStaleBadges
    // also adds/removes it on subsequent renders. Placed before the
    // header is appended so its DOM order stays predictable.
    config.staleBadge.apply(card, false);

    header.appendChild(titleRow);
    card.appendChild(header);

    const imgContainer = document.createElement("div");
    imgContainer.className = "image-container";
    const skeleton = document.createElement("div");
    skeleton.className = "skeleton";
    skeleton.setAttribute("aria-label", "Loading preview");
    imgContainer.appendChild(skeleton);
    card.appendChild(imgContainer);

    // Single-click on the image enters LIVE for this preview (in any
    // layout — focus, grid, flow, column). The first click toggles
    // interactive on; subsequent clicks while LIVE forward as pointer
    // events to the daemon (handled by attachInteractiveInputHandlers
    // attached via updateImage). The handler is on the container, not
    // the <img>, so clicks land before the image renders too. Modifier-
    // aware: Shift+click follows the multi-stream semantics from
    // toggleInteractive().
    imgContainer.addEventListener("click", (evt) => {
        const previewId = card.dataset.previewId;
        if (!previewId) return;
        // If we're already live for this preview, the per-image click
        // handler routes to recordInteractiveInput. Check before the
        // stale-card branch so interactive clicks do not also queue a
        // heavyweight refresh for stale captures.
        if (config.liveState.isLive(previewId)) return;
        if (card.classList.contains("is-stale")) {
            evt.preventDefault();
            evt.stopPropagation();
            config.staleBadge.requestHeavyRefresh(card);
            return;
        }
        config.liveState.enterInteractiveOnCard(card, evt.shiftKey);
    });

    // ATF legend + overlay layer — rendered in the webview (not
    // baked into the PNG) so rows stay interactive: hovering a
    // finding highlights its bounds on the clean image. Populated
    // only when findings exist AND `composePreview.earlyFeatures`
    // is on; the overlay layer's boxes get computed lazily once
    // the image is loaded (see buildA11yOverlay).
    if (config.earlyFeatures() && p.a11yFindings && p.a11yFindings.length > 0) {
        const overlay = document.createElement("div");
        overlay.className = "a11y-overlay";
        overlay.setAttribute("aria-hidden", "true");
        imgContainer.appendChild(overlay);
        card.appendChild(buildA11yLegend(card, p));
    }

    const variantLabel = buildVariantLabel(p);
    if (variantLabel) {
        const badge = document.createElement("div");
        badge.className = "variant-badge";
        badge.textContent = variantLabel;
        card.appendChild(badge);
    }

    if (animated) {
        card.appendChild(config.frameCarousel.buildControls(card));
    }

    config.observeForViewport(card);
    return card;
}
