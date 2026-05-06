// Lifecycle DOM operations for a single `<div class="preview-card">`:
// initial build (`buildPreviewCard`), metadata refresh on a fresh
// `setPreviews` (`updateCardMetadata`), grid-wide relative-sizing
// (`applyRelativeSizing`), per-frame image swap (`updateImage`), and
// daemon-attached a11y refresh (`applyA11yUpdate`).
//
// Lifted verbatim from `behavior.ts` so the imperative DOM operations
// stop needing closure access to the rest of the panel. Each function
// takes a narrow `Pick` of `CardBuilderConfig` covering only the fields
// it touches — the shared interface is the single source of truth for
// the card's collaborator surface, and the eventual `<preview-card>`
// Lit component will reach for those collaborators through the same
// shape.

import {
    applyHierarchyOverlay,
    buildA11yLegend,
    buildA11yOverlay,
    ensureHierarchyOverlay,
} from "./a11yOverlay";
import {
    buildTooltip,
    buildVariantLabel,
    isAnimatedPreview,
    isWearPreview,
    mimeFor,
    sanitizeId,
} from "./cardData";
import { showDiffOverlay, type DiffOverlayConfig } from "./diffOverlay";
import type { FocusInspectorController } from "./focusInspector";
import type {
    CapturePresentation,
    FrameCarouselController,
} from "./frameCarousel";
import {
    attachInteractiveInputHandlers,
    type InteractiveInputConfig,
} from "./interactiveInput";
import type { LiveStateController } from "./liveState";
import type { StaleBadgeController } from "./staleBadge";
import type { PreviewGrid } from "./components/PreviewGrid";
import type { PreviewCard } from "./components/PreviewCard";
import type { MessageOwner } from "./components/MessageBanner";
import {
    bumpPreviewMapsRevision,
    clearCardA11yFindings,
    deleteCardA11yFindings,
    deleteCardA11yNodes,
    deleteCardCaptures,
    previewStore,
    setCardA11yFindings,
    setCardA11yNodes,
    setCardCaptures,
} from "./previewStore";
import type {
    AccessibilityFinding,
    AccessibilityNode,
    PreviewInfo,
} from "../shared/types";
import type { VsCodeApi } from "../shared/vscode";

export interface CardBuilderConfig {
    vscode: VsCodeApi<unknown>;
    /** The `<preview-grid>` host — `renderPreviews` walks its
     *  `.preview-card` children to diff against the new manifest, and
     *  uses `insertBefore` to keep the manifest's order stable. */
    grid: PreviewGrid;
    staleBadge: StaleBadgeController;
    frameCarousel: FrameCarouselController;
    liveState: LiveStateController;
    /** Pointer-input config for live cards — handed to
     *  `attachInteractiveInputHandlers` whenever a fresh `<img>` lands
     *  via `updateImage`. */
    interactiveInputConfig: InteractiveInputConfig;
    /** Diff overlay persistence + vscode handle — passed through to
     *  `showDiffOverlay` when an open diff needs auto-refresh on a new
     *  render. */
    diffOverlayConfig: DiffOverlayConfig;
    /** Focus-inspector handle so `applyA11yUpdate` can re-render when
     *  a11y data lands for the focused card. */
    inspector: FocusInspectorController;
    /** Latest `setPreviews` manifest — `applyA11yUpdate` mutates the
     *  matching entry's `a11yFindings` so legend rebuilds via
     *  `buildA11yLegend(card, p)` see the fresh findings without a
     *  separate parameter. */
    getAllPreviews(): readonly PreviewInfo[];
    /** Whether `composePreview.earlyFeatures` is on — gates the a11y
     *  legend / overlay rebuild and the diff-overlay auto-refresh. */
    earlyFeatures(): boolean;
    /** Predicate for "panel is currently in focus layout" — keeps the
     *  per-card focus button's enter/exit toggle in sync with the toolbar
     *  and gates the inspector re-render in `applyA11yUpdate`. */
    inFocus(): boolean;
    /** The currently-focused preview card, or null when none is focused.
     *  `applyA11yUpdate` uses it to decide whether to re-render the
     *  inspector. */
    focusedCard(): HTMLElement | null;
    /** Imperative actions the builder hands back to `behavior.ts` rather
     *  than reaching for them at construction time. */
    enterFocus(card: HTMLElement): void;
    exitFocus(): void;
    /** Hook the freshly-built card into the viewport tracker so daemon
     *  scroll-ahead works. */
    observeForViewport(card: HTMLElement): void;
    /** Drop a card from the viewport tracker — paired with
     *  `observeForViewport`; called by `renderPreviews` when an existing
     *  preview disappears from the new manifest. */
    forgetViewport(previewId: string, card: HTMLElement): void;
    /** Set the message banner text + owner, with `ensureNotBlank()`
     *  backstop. Used by `renderPreviews`'s empty-state fallback. */
    setMessage(text: string, owner: MessageOwner): void;
    /** Read the current message-banner owner so `renderPreviews` knows
     *  whether to clear a transient `loading` / `fallback` placeholder
     *  after cards land in the DOM. */
    getMessageOwner(): MessageOwner | null;
}

/**
 * Build the initial DOM for a preview card. Returns a `<preview-card>`
 * Lit element — caller is responsible for inserting it into the grid in
 * the right position (`renderPreviews` orchestrates a stable insertion order
 * keyed on `previewId`).
 *
 * Step 2 of #857: this is now a thin shim that constructs the
 * `<preview-card>` shell and hands it the `preview` + `config` props.
 * The imperative population (id / dataset / className / header /
 * imgContainer / a11y / variant / carousel / observe-for-viewport)
 * lives in `populatePreviewCard` and runs from the element's
 * `firstUpdated()`.
 *
 * Side effects (deferred until the element's `firstUpdated`):
 *  - Seeds `previewStore`'s `cardCaptures` with one `CapturePresentation`
 *    per `p.captures` entry (via `setCardCaptures`).
 *  - Calls `config.staleBadge.apply(card, false)` once so the badge slot
 *    exists in DOM order before the rest of the header is appended.
 *  - Calls `config.observeForViewport(card)` so the viewport tracker
 *    starts watching.
 */
export function buildPreviewCard(
    p: PreviewInfo,
    config: CardBuilderConfig,
): HTMLElement {
    const card = document.createElement("preview-card") as PreviewCard;
    card.preview = p;
    card.config = config;
    return card;
}

/**
 * Imperative population helper — runs from the `<preview-card>` shell's
 * `firstUpdated()` against the host element itself. Extracted from the
 * old `buildPreviewCard` body; logic is otherwise unchanged.
 *
 * `card` here is the `<preview-card>` host (not a child div). The id,
 * `preview-card` class, dataset attributes, and all child DOM land on
 * `card` directly so existing `document.getElementById("preview-…")`
 * lookups, `.preview-card` selectors, and CSS rules keep targeting the
 * same element.
 */
export function populatePreviewCard(
    card: HTMLElement,
    p: PreviewInfo,
    config: CardBuilderConfig,
): void {
    const animated = isAnimatedPreview(p);
    const captures = p.captures;

    // `referenced` previews live in another file but target the active one
    // (idiomatic `XxxPreviews.kt` / `screenshotTest` layout). The CSS hook lets
    // the panel render them under a "from elsewhere" treatment without changing
    // the message shape.
    card.className =
        "preview-card" +
        (animated ? " animated-card" : "") +
        (p.referenced ? " referenced" : "");
    card.id = "preview-" + sanitizeId(p.id);
    card.setAttribute("role", "listitem");
    card.dataset.function = p.functionName;
    card.dataset.group = p.params.group || "";
    card.dataset.previewId = p.id;
    card.dataset.className = p.className;
    card.dataset.wearPreview = isWearPreview(p) ? "1" : "0";
    card.dataset.currentIndex = "0";
    if (p.referenced) {
        card.dataset.referenced = "1";
    }
    setCardCaptures(
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
}

/** Subset of `CardBuilderConfig` that `updateCardMetadata` actually
 *  reaches for. Kept narrow so callers — and the eventual reactive
 *  Lit component — only have to satisfy what's used. The capture
 *  cache itself lives in `previewStore` (see `setCardCaptures`),
 *  so it isn't part of this surface. */
export type CardUpdateConfig = Pick<
    CardBuilderConfig,
    "frameCarousel" | "earlyFeatures"
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

// `applyRelativeSizing` lives in `./relativeSizing.ts` so the DOM
// operation is testable under happy-dom without dragging cardBuilder's
// wider transitive imports into the host tsconfig. Re-exported here for
// the existing `behavior.ts` import paths.
export { applyRelativeSizing } from "./relativeSizing";

/** Subset `updateImage` reaches for — every per-frame paint dependency.
 *  The per-preview caches (`cardCaptures`, `cardA11yFindings`,
 *  `cardA11yNodes`) live in `previewStore` and are read directly there. */
export type UpdateImageConfig = Pick<
    CardBuilderConfig,
    | "vscode"
    | "frameCarousel"
    | "liveState"
    | "interactiveInputConfig"
    | "diffOverlayConfig"
    | "earlyFeatures"
>;

/**
 * Paint a fresh capture image into the matching card. Idempotent — if the
 * currently-displayed capture index doesn't match the arriving bytes, the
 * cache is updated and the carousel indicator refreshed but no `<img>` is
 * touched (the bytes wait for prev/next).
 *
 * Side effects (when [captureIndex] matches the displayed capture):
 *  - Updates / mutates the per-capture cache entry on
 *    `previewStore`'s `cardCaptures` and bumps `mapsRevision` so
 *    subscribers selecting on the cache notice the new bytes.
 *  - Replaces the card's `<img>` `src` (or creates one if missing).
 *  - Re-attaches the live pointer/wheel handlers via
 *    `attachInteractiveInputHandlers`.
 *  - If a diff overlay is open against `head` / `main`, re-issues the
 *    diff request so the user sees the new bytes without clicking.
 *  - Repaints the a11y finding / hierarchy overlays once the image's
 *    natural dimensions are known.
 */
export function updateImage(
    previewId: string,
    captureIndex: number,
    imageData: string,
    config: UpdateImageConfig,
): void {
    const card = document.getElementById("preview-" + sanitizeId(previewId));
    if (!card) return;

    // Cache so carousel navigation can restore this capture without
    // a fresh extension round-trip. Mutates the capture object in
    // place — the Map identity is unchanged but `mapsRevision` is
    // bumped so subscribers selecting on it can react to fresh
    // frames in live mode.
    const caps = previewStore.getState().cardCaptures.get(previewId);
    const capture =
        caps && captureIndex >= 0 && captureIndex < caps.length
            ? caps[captureIndex]
            : null;
    if (capture) {
        capture.imageData = imageData;
        capture.errorMessage = null;
        capture.renderError = null;
        bumpPreviewMapsRevision();
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
    attachInteractiveInputHandlers(card, img, config.interactiveInputConfig);

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

    // Re-build the a11y overlay once the image natural dimensions
    // are known. Data-URL srcs may resolve synchronously; in that
    // case img.complete is true and load will not fire, so we
    // check both. Findings are stashed at setPreviews time via the
    // renderPreviews pipeline. Gated on earlyFeatures so the
    // overlay only paints when the user has opted into the
    // accessibility-overlay feature surface.
    const findings = previewStore.getState().cardA11yFindings.get(previewId);
    const nodes = previewStore.getState().cardA11yNodes.get(previewId);
    if (
        config.earlyFeatures() &&
        ((findings && findings.length > 0) || (nodes && nodes.length > 0))
    ) {
        const paintedImg = img;
        const apply = (): void => {
            if (findings && findings.length > 0)
                buildA11yOverlay(card, findings, paintedImg);
            if (nodes && nodes.length > 0)
                applyHierarchyOverlay(card, nodes, paintedImg);
        };
        if (paintedImg.complete && paintedImg.naturalWidth > 0) {
            apply();
        } else {
            paintedImg.addEventListener("load", apply, { once: true });
        }
    }
}

/** Subset `applyA11yUpdate` reaches for. The a11y caches themselves
 *  live in `previewStore` and are written via the `setCardA11y…` /
 *  `deleteCardA11y…` helpers. */
export type A11yUpdateConfig = Pick<
    CardBuilderConfig,
    "getAllPreviews" | "inspector" | "earlyFeatures" | "inFocus" | "focusedCard"
>;

/**
 * D2 — handles `updateA11y` from the extension (daemon-attached a11y data
 * products). Updates the per-preview caches and re-applies whichever
 * overlays are now relevant without rebuilding the whole card. Findings
 * → legend + finding overlay; nodes → hierarchy overlay. Either argument
 * may be omitted to leave that side untouched. Gated on `earlyFeatures()`
 * so daemon-attached a11y data is dropped silently when the user has not
 * opted into the accessibility-overlay feature surface.
 */
export function applyA11yUpdate(
    previewId: string,
    findings: readonly AccessibilityFinding[] | null | undefined,
    nodes: readonly AccessibilityNode[] | null | undefined,
    config: A11yUpdateConfig,
): void {
    if (!config.earlyFeatures()) return;
    const card = document.getElementById("preview-" + sanitizeId(previewId));
    if (!card) return;
    const container = card.querySelector(".image-container");
    const img = container?.querySelector<HTMLImageElement>("img") ?? null;
    if (findings !== undefined) {
        if (findings && findings.length > 0) {
            // Ensure the empty `.a11y-overlay` container exists before
            // bumping the store — `<preview-card>`'s mapsRevision
            // subscription will paint into it once the per-id Map
            // write below fires the rebroadcast.
            if (container && !container.querySelector(".a11y-overlay")) {
                const overlay = document.createElement("div");
                overlay.className = "a11y-overlay";
                overlay.setAttribute("aria-hidden", "true");
                container.appendChild(overlay);
            }
            const existingLegend = card.querySelector(".a11y-legend");
            if (existingLegend) existingLegend.remove();
            const p = config.getAllPreviews().find((pp) => pp.id === previewId);
            if (p) {
                p.a11yFindings = [...findings];
                card.appendChild(buildA11yLegend(card, p));
            }
            // Store write last — the `mapsRevision` bump triggers the
            // component's `_repaintA11yOverlaysFromCache()` which runs
            // `buildA11yOverlay` against the fresh findings. The
            // component gates on `img.complete && img.naturalWidth > 0`
            // exactly as the previous imperative branch did.
            setCardA11yFindings(previewId, findings);
        } else {
            deleteCardA11yFindings(previewId);
            const overlay = card.querySelector(".a11y-overlay");
            if (overlay) overlay.remove();
            const legend = card.querySelector(".a11y-legend");
            if (legend) legend.remove();
        }
    }
    if (nodes !== undefined) {
        if (nodes && nodes.length > 0) {
            ensureHierarchyOverlay(container);
            // Same pattern as findings above: store write fires the
            // mapsRevision bump, the component re-paints the layer
            // via `applyHierarchyOverlay`.
            setCardA11yNodes(previewId, nodes);
        } else {
            deleteCardA11yNodes(previewId);
            const layer = card.querySelector(".a11y-hierarchy-overlay");
            if (layer) layer.remove();
        }
    }
    if (config.inFocus() && config.focusedCard() === card) {
        config.inspector.render(card);
    }
}

/** Subset `renderPreviews` reaches for — initial-build + metadata-refresh
 *  collaborator surface plus the grid + viewport + message-banner hooks.
 *  The per-preview Maps live in `previewStore` and are mutated through
 *  the `…CardCaptures` / `…CardA11y…` helpers, so they don't appear on
 *  this surface. */
export type RenderPreviewsConfig = Pick<
    CardBuilderConfig,
    | "vscode"
    | "grid"
    | "staleBadge"
    | "frameCarousel"
    | "liveState"
    | "interactiveInputConfig"
    | "diffOverlayConfig"
    | "inspector"
    | "getAllPreviews"
    | "earlyFeatures"
    | "inFocus"
    | "focusedCard"
    | "enterFocus"
    | "exitFocus"
    | "observeForViewport"
    | "forgetViewport"
    | "setMessage"
    | "getMessageOwner"
>;

/**
 * Incremental diff against the grid's current contents: update existing
 * cards, add new ones, remove missing. Keeps rendered images in place
 * during refresh — they're replaced as new images stream in from
 * `updateImage` messages.
 *
 * Side effects:
 *  - Removed cards drop their `previewStore` `cardCaptures` entry (via
 *    `deleteCardCaptures`) and detach from the viewport tracker via
 *    `config.forgetViewport`.
 *  - The `cardA11yFindings` cache on `previewStore` is fully rebuilt
 *    from each preview's `a11yFindings` so `updateImage`'s on-load
 *    handler can repaint overlays consistently.
 *  - Insert order matches the manifest; `insertBefore` keeps existing
 *    cards in place when their position survives.
 *  - After cards land, transient owner messages (`loading`, `fallback`)
 *    are cleared via `config.setMessage("", owner)` — the
 *    `extension`-owned messages (build errors, empty-state notices) are
 *    left alone.
 */
export function renderPreviews(
    previews: readonly PreviewInfo[],
    config: RenderPreviewsConfig,
): void {
    if (previews.length === 0) {
        // Defensive fallback — the extension now always sends an
        // explicit showMessage for empty states, so this branch
        // shouldn't normally fire. Kept so the view never ends up
        // with an empty grid + empty message if a bug slips through.
        config.grid.innerHTML = "";
        config.setMessage("No @Preview functions found", "fallback");
        return;
    }
    const newIds = new Set(previews.map((p) => p.id));
    const existingCards = new Map<string, HTMLElement>();
    config.grid
        .querySelectorAll<HTMLElement>(".preview-card")
        .forEach((card) => {
            const id = card.dataset.previewId;
            if (id) existingCards.set(id, card);
        });

    // Remove cards that no longer exist — drop their cached capture
    // data so stale entries don't pile up if a preview is renamed.
    for (const [id, card] of existingCards) {
        if (!newIds.has(id)) {
            deleteCardCaptures(id);
            config.forgetViewport(id, card);
            card.remove();
        }
    }

    // Refresh per-preview findings cache so updateImage can attach
    // them to each new image load. Drop stale entries (preview
    // removed) so the map doesn't grow across sessions. Mutates the
    // store's Map in place; the per-id `setCardA11yFindings` helper
    // bumps `mapsRevision` for each addition, plus `clearCardA11y…`
    // covers the case where the manifest reseed brings zero findings.
    clearCardA11yFindings();
    for (const p of previews) {
        if (p.a11yFindings && p.a11yFindings.length > 0) {
            setCardA11yFindings(p.id, p.a11yFindings);
        }
    }

    // Add new cards / update existing ones, preserving order
    let lastInsertedCard: HTMLElement | null = null;
    for (const p of previews) {
        const existing = existingCards.get(p.id);
        if (existing) {
            // Reassigning `.preview` triggers `<preview-card>`'s reactive
            // `updated()` hook, which calls `updateCardMetadata` against
            // the host element. Cast is safe — `existingCards` is
            // populated from the `.preview-card`-classed elements
            // `buildPreviewCard` creates, all of which are
            // `<preview-card>` instances.
            (existing as PreviewCard).preview = p;
            // Ensure correct position
            if (lastInsertedCard) {
                if (lastInsertedCard.nextSibling !== existing) {
                    config.grid.insertBefore(
                        existing,
                        lastInsertedCard.nextSibling,
                    );
                }
            } else if (config.grid.firstChild !== existing) {
                config.grid.insertBefore(existing, config.grid.firstChild);
            }
            lastInsertedCard = existing;
        } else {
            const card = buildPreviewCard(p, config);
            if (lastInsertedCard) {
                config.grid.insertBefore(card, lastInsertedCard.nextSibling);
            } else {
                config.grid.insertBefore(card, config.grid.firstChild);
            }
            lastInsertedCard = card;
        }
    }

    // Clear transient owner messages now that cards are in the DOM.
    // The 'loading' Building… banner and the 'fallback' "Preparing
    // previews…" placeholder both get cleared here. 'extension'-owned
    // messages (build errors, empty-state notices) are left alone —
    // those are terminal states the extension is asserting and the
    // caller wouldn't be sending setPreviews alongside them anyway.
    //
    // Must run *after* cards are inserted: setMessage('', …) calls
    // ensureNotBlank, which would re-set "Preparing previews…" if
    // the grid still looked empty when the message was cleared.
    const owner = config.getMessageOwner();
    if (owner && owner !== "extension") {
        config.setMessage("", owner);
    }
}
