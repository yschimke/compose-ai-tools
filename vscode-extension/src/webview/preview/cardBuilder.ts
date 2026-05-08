// Lifecycle DOM operations for a single `<preview-card>`:
// initial build (`buildPreviewCard` + `populatePreviewCard`),
// grid-wide relative-sizing (`applyRelativeSizing`, re-exported
// from `./relativeSizing`), daemon-attached a11y refresh
// (`applyA11yUpdate`), and the manifest-reseed orchestration
// (`renderPreviews`).
//
// The metadata-refresh path (`updateCardMetadata`) lives in
// `./cardMetadata.ts` and runs from `<preview-card>`'s reactive
// `updated()` hook on a `preview` property reassignment. The
// per-frame image paint (`updateImage`) lives in `./cardImage.ts`
// and runs from the component's `paintCapture` method, invoked
// by the message dispatcher after resolving the card by id.
//
// Lifted verbatim from `behavior.ts` so the imperative DOM operations
// stop needing closure access to the rest of the panel. Each function
// takes a narrow `Pick` of `CardBuilderConfig` covering only the fields
// it touches — the shared interface is the single source of truth for
// the card's collaborator surface.

import { buildA11yLegend } from "./a11yOverlay";
import {
    applyA11yUpdate as applyA11yUpdateImpl,
    type A11yUpdateConfig as A11yUpdateConfigBase,
} from "./applyA11yUpdate";
import {
    buildTooltip,
    buildVariantLabel,
    isAnimatedPreview,
    isWearPreview,
    sanitizeId,
} from "./cardData";
import type { DiffOverlayConfig } from "./diffOverlay";
import type { FocusInspectorController } from "./focusInspector";
import type {
    CapturePresentation,
    FrameCarouselController,
} from "./frameCarousel";
import type { InteractiveInputConfig } from "./interactiveInput";
import type { LiveStateController } from "./liveState";
import type { StaleBadgeController } from "./staleBadge";
import type { PreviewGrid } from "./components/PreviewGrid";
import type { PreviewCard } from "./components/PreviewCard";
import type { MessageOwner } from "./components/MessageBanner";
import {
    clearCardA11yFindings,
    deleteCardA11yFindings,
    deleteCardA11yNodes,
    deleteCardCaptures,
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

// `applyRelativeSizing` lives in `./relativeSizing.ts` so the DOM
// operation is testable under happy-dom without dragging cardBuilder's
// wider transitive imports into the host tsconfig. Re-exported here for
// the existing `behavior.ts` import paths.
export { applyRelativeSizing } from "./relativeSizing";

/** Subset `applyA11yUpdate` reaches for. The a11y caches themselves
 *  live in `previewStore` and are written via the `setCardA11y…` /
 *  `deleteCardA11y…` helpers — the cardBuilder-level facade binds those
 *  mutators automatically so callers only need this slimmer surface. */
export type A11yUpdateConfig = Pick<
    CardBuilderConfig,
    "getAllPreviews" | "inspector" | "earlyFeatures" | "inFocus" | "focusedCard"
>;

// Re-export the underlying narrow helper (and its bag-with-mutators
// config type) so test code that wants to stub the cache writes can
// drive it directly without going through the cardBuilder facade.
export {
    applyA11yUpdate as applyA11yUpdateNarrow,
    type A11yUpdateConfig as A11yUpdateNarrowConfig,
} from "./applyA11yUpdate";

/**
 * D2 — handles `updateA11y` from the extension (daemon-attached a11y data
 * products). Updates the per-preview caches and re-applies whichever
 * overlays are now relevant without rebuilding the whole card. Findings
 * → legend + finding overlay; nodes → hierarchy overlay. Either argument
 * may be omitted to leave that side untouched. Gated on `earlyFeatures()`
 * so daemon-attached a11y data is dropped silently when the user has not
 * opted into the accessibility-overlay feature surface.
 *
 * Thin facade over the narrow helper in `./applyA11yUpdate.ts` — this
 * file binds the per-preview cache mutators (`setCardA11yFindings`,
 * etc.) from `previewStore` so callers don't have to thread them through
 * `CardBuilderConfig`. The helper itself is testable in isolation under
 * happy-dom; see `vscode-extension/src/test/applyA11yUpdate.test.ts`.
 */
export function applyA11yUpdate(
    previewId: string,
    findings: readonly AccessibilityFinding[] | null | undefined,
    nodes: readonly AccessibilityNode[] | null | undefined,
    config: A11yUpdateConfig,
): void {
    const narrow: A11yUpdateConfigBase = {
        getAllPreviews: config.getAllPreviews,
        inspector: config.inspector,
        earlyFeatures: config.earlyFeatures,
        inFocus: config.inFocus,
        focusedCard: config.focusedCard,
        setCardA11yFindings,
        deleteCardA11yFindings,
        setCardA11yNodes,
        deleteCardA11yNodes,
    };
    applyA11yUpdateImpl(previewId, findings, nodes, narrow);
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
