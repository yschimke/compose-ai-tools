// Lifecycle DOM operations for a single `<div class="preview-card">`:
// initial build (`buildPreviewCard`), metadata refresh on a fresh
// `setPreviews` (`updateCardMetadata`), and the grid-wide relative-sizing
// pass (`applyRelativeSizing`).
//
// Lifted verbatim from `behavior.ts`'s `createCard` / `updateCardMetadata` /
// `applyRelativeSizing` so the imperative DOM operations stop needing
// closure access to the rest of the panel. The remaining update paths —
// `updateImage` (per-frame image swap) and `applyA11yUpdate` (daemon-
// attached a11y refresh) — still live in `behavior.ts` and patch the card
// in place via `document.getElementById`. The eventual `<preview-card>`
// Lit component will fold all five into a single reactive `render()`.

import { buildA11yLegend, buildA11yOverlay } from "./a11yOverlay";
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

/** Subset of `CardBuilderConfig` that `updateCardMetadata` actually
 *  reaches for. Kept narrow so callers — and the eventual reactive
 *  Lit component — only have to satisfy what's used. */
export type CardUpdateConfig = Pick<
    CardBuilderConfig,
    "cardCaptures" | "frameCarousel" | "earlyFeatures"
>;

/**
 * Refresh an existing card after a `setPreviews` for an id we already
 * have in the grid. Patches the card's dataset, title text, capture cache
 * (preserving already-received `imageData` for surviving renderOutputs),
 * variant badge, and a11y legend / overlay layer.
 *
 * Caller is responsible for finding the card — `renderPreviews` walks the
 * grid once and dispatches to `updateCardMetadata` vs `buildPreviewCard`
 * based on whether the previewId existed previously.
 */
export function updateCardMetadata(
    card: HTMLElement,
    p: PreviewInfo,
    config: CardUpdateConfig,
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
    const prior = config.cardCaptures.get(p.id) ?? [];
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
    config.cardCaptures.set(p.id, mergedCaps);
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

    // Refresh the a11y legend + overlay in place when findings
    // change (e.g. toggling a11y on turns findings from null → list,
    // or a fresh render updates the set). Tear down the old nodes
    // and rebuild: simpler than reconciling row-by-row for what is
    // a rare event.
    const existingLegend = card.querySelector(".a11y-legend");
    const existingOverlay = card.querySelector(".a11y-overlay");
    if (existingLegend) existingLegend.remove();
    if (existingOverlay) existingOverlay.innerHTML = "";
    if (config.earlyFeatures() && p.a11yFindings && p.a11yFindings.length > 0) {
        const container = card.querySelector(".image-container");
        if (container && !container.querySelector(".a11y-overlay")) {
            const overlay = document.createElement("div");
            overlay.className = "a11y-overlay";
            overlay.setAttribute("aria-hidden", "true");
            container.appendChild(overlay);
        }
        const legend = buildA11yLegend(card, p);
        card.appendChild(legend);
        // Repopulate box geometry if the image is already loaded —
        // otherwise updateImage's load handler will pick it up on
        // the next render cycle.
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

/**
 * Scale image containers so preview variants at different device sizes
 * (e.g. wearos_large_round 227dp vs wearos_small_round 192dp) render at
 * relative sizes in fixed-layout modes. Only applied when we have real
 * widthDp/heightDp — variants without known dimensions fall back to the
 * default CSS (full card width, auto aspect).
 *
 * Walks all of [previews] once; idempotent — subsequent calls overwrite
 * the CSS custom properties cleanly.
 */
export function applyRelativeSizing(previews: readonly PreviewInfo[]): void {
    const widths = previews
        .map((p) => p.params.widthDp ?? 0)
        .filter((w) => w > 0);
    const maxW = widths.length > 0 ? Math.max(...widths) : 0;
    for (const p of previews) {
        const card = document.getElementById("preview-" + sanitizeId(p.id));
        if (!card) continue;
        const w = p.params.widthDp;
        const h = p.params.heightDp;
        if (w && h && maxW > 0) {
            card.style.setProperty("--size-ratio", (w / maxW).toFixed(4));
            card.style.setProperty("--aspect-ratio", w + " / " + h);
        } else {
            card.style.removeProperty("--size-ratio");
            card.style.removeProperty("--aspect-ratio");
        }
    }
}
