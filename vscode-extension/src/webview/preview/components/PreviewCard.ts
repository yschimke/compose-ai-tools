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
// Step 4 still leaves the `<img>` paint and the on-image-load a11y
// branch in `updateImage` — only the cache-change overlay refresh
// path moves into the component. That gives us the StoreController
// integration without disturbing the more delicate image-load
// timing or the legend rebuild.
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
     *    The image-load case stays in `updateImage` — that path
     *    triggers when the `<img>` becomes paintable, not when the
     *    cache changes. */
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
     *  per-preview store snapshot, if the `<img>` is ready to drive
     *  the percent-of-natural math. No-op if the cache is empty for
     *  this preview, the image hasn't loaded, or `earlyFeatures` is
     *  off — matches the gating in `applyA11yUpdate`. The overlay
     *  paint helpers are idempotent (they `innerHTML = ""` the layer
     *  before re-emitting boxes), so calling this on every store fire
     *  is safe. The legend rebuild stays in `applyA11yUpdate` — that
     *  side effect lives next to the per-id store write and isn't
     *  driven by `mapsRevision`. */
    private _repaintA11yOverlaysFromCache(): void {
        const preview = this.preview;
        const config = this.config;
        if (!preview || !config) return;
        if (!config.earlyFeatures()) return;
        const img = this.querySelector<HTMLImageElement>(
            ".image-container img",
        );
        if (!img || !img.complete || img.naturalWidth === 0) return;
        const state = previewStore.getState();
        const findings = state.cardA11yFindings.get(preview.id);
        if (findings && findings.length > 0) {
            buildA11yOverlay(this, findings, img);
        }
        const nodes = state.cardA11yNodes.get(preview.id);
        if (nodes && nodes.length > 0) {
            applyHierarchyOverlay(this, nodes, img);
        }
    }
}
