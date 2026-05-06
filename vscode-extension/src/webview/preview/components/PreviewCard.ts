// `<preview-card>` — Lit shell that owns the preview-card DOM the
// imperative `buildPreviewCard` previously built directly on a
// `<div class="preview-card">`.
//
// Step 2 of #857. The shell is intentionally minimal: it carries the
// `preview` (PreviewInfo) and `config` (CardBuilderConfig) the host
// hands in, and on `firstUpdated()` runs the imperative population
// logic against `this`. Step 3 (#857) lifts the metadata refresh into
// reactive `@state`; step 4 (this file) wires a `StoreController`
// against `previewStore.mapsRevision` so the per-preview a11y caches
// drive overlay repaints from the component itself instead of from
// `applyA11yUpdate`.
//
// The `<img>` paint itself stays in `updateImage` (it owns the
// `img.src` swap, the live-frame class, and the interactive-input
// rebind). The on-image-load a11y repaint, however, lives here:
// when a `mapsRevision` bump arrives but the `<img>` has not yet
// resolved its `naturalWidth`, this component attaches a one-time
// `load` listener instead of bailing. Combined with `updateImage`
// bumping `mapsRevision` AFTER assigning the new `src`, the
// component's StoreController fires with the right image element
// and the load listener attaches against the new src.
//
// We pass `preview` as a `@property` rather than just a `previewId`
// because `populatePreviewCard` needs the full `PreviewInfo` (function
// name, params, captures, a11yFindings, …). Resolving from
// `previewStore.allPreviews` would work but adds an indirection that
// step 3 will need to undo when it switches to a reactive selection
// against the store.
//
// Light DOM only — `media/preview.css` selectors target
// `.preview-card .card-header .card-title-row` etc. and would not
// pierce a shadow root. The id (`preview-${sanitizeId(p.id)}`),
// `preview-card` className, and dataset attributes (`previewId`,
// `className`, `wearPreview`, `currentIndex`, `function`, `group`,
// `referenced`) all land on the host element so the existing
// `document.getElementById` paths in `messageHandlers.ts` /
// `liveState.ts` and the `.preview-card` selector in `renderPreviews`
// keep working unchanged.

import { LitElement, html, type TemplateResult } from "lit";
import { customElement, property } from "lit/decorators.js";
import { applyHierarchyOverlay, buildA11yOverlay } from "../a11yOverlay";
import type { CardBuilderConfig } from "../cardBuilder";
import { populatePreviewCard, updateCardMetadata } from "../cardBuilder";
import { previewStore, type PreviewState } from "../previewStore";
import { StoreController } from "../../shared/storeController";
import type { PreviewInfo } from "../../shared/types";

@customElement("preview-card")
export class PreviewCard extends LitElement {
    /** The preview manifest entry this card renders. Required —
     *  `firstUpdated` no-ops until both `preview` and `config` are set. */
    @property({ attribute: false }) preview: PreviewInfo | null = null;

    /** Collaborator surface the host hands in. Carries `vscode`,
     *  `staleBadge`, `frameCarousel`, `liveState`, focus / viewport /
     *  message hooks — everything `populatePreviewCard` reaches for. */
    @property({ attribute: false }) config: CardBuilderConfig | null = null;

    /** Set once `firstUpdated` has populated the card. Guards `updated`
     *  from running the metadata patch on the initial render — that path
     *  is already covered by `populatePreviewCard`. */
    private _built = false;

    /** Subscription to `previewStore.mapsRevision`. Each per-preview
     *  Map mutation (`setCardCaptures` / `setCardA11yFindings` /
     *  `setCardA11yNodes`) bumps the counter, which fires
     *  `requestUpdate()` on this component. The `updated()` hook then
     *  re-paints the a11y overlays for this card from the latest
     *  cache snapshot — `applyA11yUpdate` no longer needs to drive the
     *  overlay repaint itself. */
    private readonly _mapsRevision = new StoreController<PreviewState, number>(
        this,
        previewStore,
        (s) => s.mapsRevision,
    );

    // Light DOM keeps `media/preview.css` rules applying unchanged.
    protected createRenderRoot(): HTMLElement {
        return this;
    }

    // Lit calls `render()` even though we populate imperatively; return
    // an empty template so it doesn't wipe children we add in
    // `firstUpdated`. Step 4 will start producing real template content
    // here as the image / a11y paths go reactive.
    protected render(): TemplateResult {
        return html``;
    }

    protected firstUpdated(): void {
        const preview = this.preview;
        const config = this.config;
        if (!preview || !config) return;
        populatePreviewCard(this, preview, config);
        this._built = true;
    }

    /** React to a `preview` reassignment from the host (`renderPreviews`
     *  reseed) AND to per-preview store map mutations.
     *
     *  - Preview reassignment: patches the card's dataset, title,
     *    capture cache, variant badge, and a11y legend / overlay in
     *    place — same body the imperative call site used to drive
     *    directly. Skipped on the first render: `firstUpdated`
     *    already built the card.
     *  - `mapsRevision` change: re-paint the a11y findings / hierarchy
     *    overlays from the cache snapshot for `this.preview.id`. This
     *    runs on every `updated()` (Lit re-renders fire the hook
     *    regardless of which property changed) so the cache-change
     *    case is covered without an explicit changedProperties guard.
     *    If the `<img>` has not yet resolved natural dimensions,
     *    `_repaintA11yOverlaysFromCache` falls back to a one-time
     *    `load` listener so a fresh `updateImage` swap is followed by
     *    a deferred repaint once the new bytes are decoded. */
    protected updated(changedProperties: Map<string, unknown>): void {
        if (!this._built) return;
        if (changedProperties.has("preview")) {
            const preview = this.preview;
            const config = this.config;
            if (preview && config) {
                updateCardMetadata(this, preview, config);
            }
        }
        this._repaintA11yOverlaysFromCache();
    }

    /** Re-paint the a11y findings / hierarchy overlays from the latest
     *  per-preview store snapshot. No-op if the cache is empty for
     *  this preview or `earlyFeatures` is off — matches the gating in
     *  `applyA11yUpdate`. If the `<img>` has resolved its natural
     *  dimensions the paint runs immediately; if not (e.g. a fresh
     *  `updateImage` just assigned a new `src` and decoding hasn't
     *  finished) we attach a one-time `load` listener instead and
     *  paint when the bytes land. The overlay paint helpers are
     *  idempotent (they `innerHTML = ""` the layer before re-emitting
     *  boxes), so calling this on every store fire is safe. The
     *  legend rebuild stays in `applyA11yUpdate` — that side effect
     *  lives next to the per-id store write and isn't driven by
     *  `mapsRevision`. */
    private _repaintA11yOverlaysFromCache(): void {
        const preview = this.preview;
        const config = this.config;
        if (!preview || !config) return;
        if (!config.earlyFeatures()) return;
        const img = this.querySelector<HTMLImageElement>(
            ".image-container img",
        );
        if (!img) return;
        const state = previewStore.getState();
        const findings = state.cardA11yFindings.get(preview.id);
        const nodes = state.cardA11yNodes.get(preview.id);
        const haveFindings = !!findings && findings.length > 0;
        const haveNodes = !!nodes && nodes.length > 0;
        if (!haveFindings && !haveNodes) return;
        if (img.complete && img.naturalWidth > 0) {
            if (haveFindings) buildA11yOverlay(this, findings, img);
            if (haveNodes) applyHierarchyOverlay(this, nodes, img);
            return;
        }
        // Image not yet decoded — defer to the next `load` event.
        // `mapsRevision` may bump again before the image lands (e.g.
        // a follow-up live frame), and Lit re-runs `updated()` on
        // every fire, so guard against stacking listeners on the
        // same `<img>` via a dataset flag. The handler clears the
        // flag before painting so a later swap (new `<img>` element
        // or a future `src` change once this one is loaded) can
        // re-arm.
        if (img.dataset.a11yPaintScheduled === "1") return;
        img.dataset.a11yPaintScheduled = "1";
        img.addEventListener(
            "load",
            () => {
                delete img.dataset.a11yPaintScheduled;
                // Re-read the cache at fire time — it may have been
                // updated again between schedule and load.
                const s = previewStore.getState();
                const f = s.cardA11yFindings.get(preview.id);
                const n = s.cardA11yNodes.get(preview.id);
                if (f && f.length > 0) buildA11yOverlay(this, f, img);
                if (n && n.length > 0) applyHierarchyOverlay(this, n, img);
            },
            { once: true },
        );
    }
}
